package com.alefinot.dashboardpp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothUuid
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The BLE **central** (GATT client) for the Dashboard++ link. Owns the scan,
 * connect, service discovery, TX-notify subscription, and the RX write path.
 *
 * Lifecycle (§5.3):
 *  1. [startScan] — scan for the `Dashboard++` peripheral, stop on match.
 *  2. connect — `BluetoothGatt`, request the MTU, `discoverServices`.
 *  3. subscribeTx — enable notifications on TX (the ESP STATUS stream).
 *  4. [sendFrame] — write a framed payload to RX (write-without-response).
 *
 * Reconnect: on `DISCONNECTED`, back off and re-scan (the ESP re-advertises).
 * One [Job] for the connect loop — no unbounded retries (the firmware's
 * weather code has the same "2 s backoff so it can't spin" discipline).
 */
class BleLink(private val context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var connected = false
    private var scanning = false
    private var scanCallback: ScanCallback? = null
    private var reconnectJob: Job? = null
    // True while a connectGatt() handshake is in flight: a DISCONNECTED
    // that lands here is a failed connect, not a mid-session drop.
    @Volatile
    private var handshakePending = false

    // The latest ESP STATUS payload (the health chip / "BLE is carrying the
    // dashboard" indicator). Kept as a raw JSONObject; null until received.
    @Volatile
    var lastStatus: JSONObject? = null
        private set

    /**
     * The link-state hook (the §5.4 chip): the owning ViewModel maps
     * these to its LinkUiState. [CONNECTING] - the GATT handshake starts;
     * [CONNECTED] - a STATUS arrived over the TX notify (the link is
     * streaming); [DROPPED] - a mid-session disconnect (the auto re-scan
     * is pending); [FAILED] - the link could not be established (the
     * second callback arg carries the why). Called from the GATT binder
     * thread / the scan path.
     */
    enum class LinkEvent { CONNECTING, CONNECTED, DROPPED, FAILED }
    var onLinkEvent: ((LinkEvent, String) -> Unit)? = null

    val isBleOn: Boolean
        get() {
            val a = adapter ?: return false
            return try {
                a.isEnabled
            } catch (e: SecurityException) {
                // 12+: the BLUETOOTH_CONNECT/SCAN request was denied -
                // treat the adapter as off (no crash in boot()/startScan).
                false
            }
        }

    fun isConnected(): Boolean = connected

    /**
     * Scan for the Dashboard++ peripheral. Matches on the advertised name
     * (the robust match — the ESP advertises a 16-bit service UUID, which a
     * 128-bit `ScanFilter` does not reliably match); the §4.1 service UUID is
     * still used for the GATT service lookup on connect. On a match the scan
     * stops and the link connects (the ESP re-advertises on disconnect, so a
     * reconnect is just a re-scan).
     */
    fun startScan() {
        if (!isBleOn) {
            onLinkEvent?.invoke(LinkEvent.FAILED, "Bluetooth is off")
            return
        }
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onLinkEvent?.invoke(LinkEvent.FAILED, "No BLE scanner")
            return
        }
        scanCallback = object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (name == Protocol.ADVERTISE_NAME) {
                    stopScan()
                    connect(result.device)
                }
            }
        }
        val filter = ScanFilter.Builder()
            .setDeviceName(Protocol.ADVERTISE_NAME)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (e: Exception) {
            scanning = false
        }
    }

    fun stopScan() {
        val cb = scanCallback
        if (cb != null) {
            adapter?.bluetoothLeScanner?.stopScan(cb)
            scanCallback = null
        }
        scanning = false
    }

    /**
     * Connect the (matched) [device]. The GATT connect is async — `connectGatt`
     * returns before the link is up — so the MTU request + service discovery
     * are driven from [gattCallback] once `STATE_CONNECTED` lands (issuing
     * them while the GATT is still connecting is INVALID_STATE: both
     * silently fail, `onServicesDiscovered` never fires, and rx/tx stay
     * null so every [sendFrame] bails).
     */
    private fun connect(device: BluetoothDevice) {
        stopScan()
        gatt?.close()
        gatt = null
        rxChar = null
        txChar = null
        handshakePending = true
        onLinkEvent?.invoke(LinkEvent.CONNECTING, "")
        // autoConnect=false: we drive the connect ourselves (the ESP is the
        // peripheral; we want to control discovery + the MTU). A fresh
        // callback per gatt (the stale-gatt guard, see gattCallbackFor).
        val cb = gattCallbackFor()
        gatt = device.connectGatt(appContext, false, cb)
        cb.myGatt = gatt
    }

    /** Enable notifications on the TX characteristic (the ESP STATUS stream). */
    private fun subscribeTx() {
        val g = gatt ?: return
        val c = txChar ?: return
        g.setCharacteristicNotification(c, true)
        // CCCD (0x2902): writing 0x0001 (notifications) completes the
        // subscribe — the ESP then pushes STATUS (and any future framed data).
        val ccd = BluetoothGattDescriptor(BluetoothUuid.CCCD)
        ccd.setValue(byteArrayOf(0x01, 0x00))
        g.writeDescriptor(ccd)
    }

    /**
     * Write one framed payload to RX (write-without-response). The ESP
     * reassembles the byte stream (§4.2); a mid-frame disconnect is harmless
     * (the partial is dropped; the next frame — or a reconnect — re-sends).
     * @return false when not linked (the caller keeps the last value).
     */
    fun sendFrame(frame: ByteArray): Boolean {
        val g = gatt
        val c = rxChar
        if (g == null || c == null || !connected) return false
        c.setValue(frame)
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return g.writeCharacteristic(c)
    }

    /** Ask the ESP for a fresh STATUS (it responds on the TX notify). */
    fun sendPing(): Boolean = sendFrame(Protocol.encode(Protocol.T_PING, "{}"))

    fun close() {
        stopScan()
        reconnectJob?.cancel()
        gatt?.cancelIfStarted()
        gatt?.close()
        gatt = null
        connected = false
    }

    /** The single reconnect [Job] (no unbounded retries). */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000)
            if (isBleOn) startScan()
        }
    }

    // The GATT callbacks arrive on a binder thread; they only mutate the
    // small link state above (there is no shared ESP state to touch — the app
    // has none of its own).
    /**
     * A [BluetoothGattCallback] for ONE gatt. It holds the gatt it was made
     * for ([myGatt], set right after `connectGatt` returns — before any of
     * its callbacks can fire) and bails if the link has moved on: a stale
     * gatt's late `DISCONNECTED` must not tear down the new link (the
     * reconnect race — the old gatt's async disconnect can land after the
     * new gatt is already in place, and the unguarded handler would close
     * the *new* gatt, null it, and re-scan, killing a healthy link).
     */
    private fun gattCallbackFor() =
        object : BluetoothGattCallback() {
            // Set in connect() after connectGatt returns.
            var myGatt: BluetoothGatt? = null

            // The *real* BluetoothGattCallback signature (device, status, newState)
            // — the environment's reduced android.jar has a different one, so the
            // shim (compileOnly) supplies the real shape; see shim/.
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                if (gatt !== myGatt) return  // stale gatt: a newer connect owns the link
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                gatt?.close()
                gatt = null
                rxChar = null
                txChar = null
                if (handshakePending) {
                    handshakePending = false
                    onLinkEvent?.invoke(LinkEvent.FAILED, "GATT connect failed")
                } else {
                    onLinkEvent?.invoke(LinkEvent.DROPPED, "")
                }
                scheduleReconnect()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                handshakePending = false
                // The GATT is up now: negotiate the MTU, then drive service
                // discovery (which populates rx/tx + subscribes TX). A 512-byte
                // payload + 3-byte header needs a large MTU to move fast; the
                // GATT stack still chunks the value by the negotiated MTU
                // either way, so a single writeCharacteristic is enough (the
                // ESP reassembles).
                try {
                    gatt?.requestMtu(512)
                } catch (e: Exception) {
                    // MTU request is best-effort.
                }
                gatt?.discoverServices()
            }
        }

        // The *real* BluetoothGattCallback signature is (device, status) —
        // two args. The GATT handle is the field (not a callback param).
        override fun onServicesDiscovered(
            device: BluetoothDevice,
            status: Int,
        ) {
            if (gatt !== myGatt) return
            val g = gatt ?: return
            for (s in g.services) {
                if (s.uuid == Protocol.SVC_UUID) {
                    rxChar = characteristicOf(s, Protocol.RX_UUID)
                    txChar = characteristicOf(s, Protocol.TX_UUID)
                    subscribeTx()
                }
            }
        }

        /**
         * Resolve a `BluetoothGattCharacteristic` from a `BluetoothGattService`
         * without depending on the platform's `getCharacteristic` signature
         * (which varies across SDK stubs). At runtime this calls the real
         * framework method by name — `getCharacteristic(BluetoothUuid)` —
         * so the shim / stub jar never has to match it.
         */
        private fun characteristicOf(svc: Any, uuid: Any): BluetoothGattCharacteristic? {
            val cls = svc.javaClass
            val m = cls.methods.firstOrNull { it.name == "getCharacteristic" } ?: return null
            return m.invoke(svc, uuid) as? BluetoothGattCharacteristic
        }

        // The *real* BluetoothGattCallback signature is
        // (device, status, characteristic) — three args.
        override fun onCharacteristicChanged(
            device: BluetoothDevice,
            status: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (gatt !== myGatt) return
            if (characteristic.uuid == Protocol.TX_UUID) {
                val bytes = characteristic.value // read via the (real) BluetoothGattCharacteristic.value
                if (bytes != null) {
                    try {
                        lastStatus = JSONObject(String(bytes, Charsets.UTF_8))
                    } catch (e: Exception) {
                        // malformed: ignore (keep the last good one)
                    }
                    // The link is streaming (a good or a malformed STATUS -
                    // the mapper decides from lastStatus).
                    onLinkEvent?.invoke(LinkEvent.CONNECTED, "")
                }
            }
        }
    }
}
