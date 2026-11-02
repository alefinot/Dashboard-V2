package com.alefinot.dashboardpp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One advertising Dashboard++ unit, as the scan saw it. */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

/** What the link is doing, for the screen that drives it. */
sealed interface BleLinkState {
    /** No link, and none being made. */
    data object Idle : BleLinkState

    /** Connecting, exchanging the MTU and discovering the service. */
    data class Connecting(val name: String) : BleLinkState

    /** Waiting for the passkey the unit shows on its panel to be typed on the phone. */
    data class Pairing(val name: String) : BleLinkState

    /** Encrypted, subscribed, ready for requests and telemetry. */
    data class Ready(val name: String, val address: String, val mtu: Int) : BleLinkState

    /** The last attempt ended badly; [reason] is a stable code, the screen says why. */
    data class Failed(val reason: String) : BleLinkState
}

/**
 * One answer to one request.
 *
 * [fields] is the parsed `r` object; [chunks] is present only for a streamed
 * answer (`config.get`), merged from every `BT_F_CHUNK` that arrived before the
 * closing response. [body] is the raw envelope, kept for logging.
 */
data class BleReply(
    val ok: Boolean,
    val error: String? = null,
    val body: String = "",
    val fields: Map<String, Any?> = emptyMap(),
    val chunks: Map<String, Any?>? = null,
) {
    companion object {
        fun fail(code: String) = BleReply(ok = false, error = code)
    }
}

/**
 * The GATT client for a Dashboard++ unit.
 *
 * One instance owns one connection: it scans for the service UUID, connects,
 * bonds (the unit's characteristics require an encrypted link and it displays the
 * passkey), negotiates a 517-byte MTU, subscribes to telemetry and RPC, and then
 * turns the frame protocol into two things - a [telemetry] stream of the same JSON
 * the unit once served on `/api/snapshot`, and [request] for everything that used
 * to be an HTTP endpoint.
 *
 * Fragments are reassembled per characteristic, never per link: the two handles
 * can interleave on the air, and a shared accumulator would splice a snapshot
 * into the middle of a config chunk.
 *
 * Requests are serialised. The firmware handles one RPC at a time by design (its
 * `s_rxBusy`), so a second overlapping request would be rejected or answered out
 * of order; a queue in the app is the honest place for that limit.
 */
class BleLink(context: Context) {

    private val appContext = context.applicationContext

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<BleLinkState>(BleLinkState.Idle)
    val state: StateFlow<BleLinkState> = _state.asStateFlow()

    /**
     * The peer the link last came up with, kept across the panel that drives it.
     *
     * The settings tab offers a restart of the unit; when the unit takes it, `state`
     * goes to `Failed("disconnected")` and the address goes with it, so the only way
     * back is a fresh scan. The bond is already Android's, and the unit advertises
     * again within seconds — remembering the peer is what turns that into RETRY.
     */
    var lastPeer: BleLinkState.Ready? = null
        private set

    /** The 1 Hz snapshot, exactly as the unit frames it (the old `/api/snapshot` body). */
    private val _telemetry = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val telemetry: SharedFlow<String> = _telemetry.asSharedFlow()

    /** `BT_F_LOG` frames, when the unit sends one. */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /**
     * `BT_F_OTA` frames: what the unit says about a firmware install it is running.
     *
     * These are unsolicited — the phone asked once (`ota.begin`) and the unit then reports as it
     * writes: `begin_ok`, an `ack` every 4 KiB (one flash sector on the unit), then `done` or
     * `error`. That is why they are a
     * flow and not replies: an ACK arrives while the phone is busy pushing the next chunk, and
     * a request/response pairing would have nothing to pair it with. `ble/OtaCore.kt` parses
     * them; the collector has to be running before the first write or the ACKs are lost, which
     * only costs a coarser progress bar (the unit's own `ota.status` still says where it stands).
     */
    private val _otaEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val otaEvents: SharedFlow<String> = _otaEvents.asSharedFlow()

    /**
     * The index of each streamed `config.get` chunk as it lands, so a settings
     * screen can say "reading your settings, chunk 4" instead of showing a spinner
     * for eight seconds. Hot, not sticky: a chunk nobody is watching is a chunk
     * nobody needed to see, and a collector started before the request is issued
     * sees every one.
     */
    private val _syncChunks = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val syncChunks: SharedFlow<Int> = _syncChunks.asSharedFlow()

    private val connectMutex = Mutex()
    private val requestMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var mtu = 23

    @Volatile
    private var telemChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var rpcChar: BluetoothGattCharacteristic? = null

    /**
     * The write-only characteristic a firmware image travels on.
     *
     * A unit from before the BLE OTA state machine has it too (the profile always declared it),
     * so its presence says nothing about whether an install is possible — that is answered by
     * `ota.begin`. It is therefore optional at discovery: a link without it still runs every
     * other method, and only the firmware card's start button goes quiet.
     */
    @Volatile
    private var otaChar: BluetoothGattCharacteristic? = null

    // Callback-thread only.
    private val telemFrames = FrameAssembler()
    private val rpcFrames = FrameAssembler()

    // One in-flight GATT operation at a time; each is completed by its callback.
    private var connectDone: CompletableDeferred<Boolean>? = null
    private var bondDone: CompletableDeferred<Boolean>? = null
    private var serviceDone: CompletableDeferred<Boolean>? = null
    private var mtuDone: CompletableDeferred<Int>? = null
    private var descriptorDone: CompletableDeferred<Int>? = null
    private var writeDone: CompletableDeferred<Int>? = null
    private var otaWriteDone: CompletableDeferred<Int>? = null

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()

    private class Pending(val id: Int, val done: CompletableDeferred<BleReply>) {
        val chunks = LinkedHashMap<String, Any?>()
    }

    /** True when the phone has a Bluetooth radio at all. */
    fun available(): Boolean = adapter != null

    /** True when the radio exists and is switched on. */
    fun enabled(): Boolean = adapter?.isEnabled == true

    /**
     * Whether this phone still holds a **bond** for [address].
     *
     * Distinct from a link: a bond survives a disconnect, an app uninstall and even the
     * card being forgotten, and it is what makes the next pairing silent. The app clears
     * its own memory of a pairing but cannot clear Android's (`removeBond` is not a
     * public API on the versions this app runs on), so the vehicle page needs this to say
     * honestly which half of a forget it could actually do.
     */
    fun isBonded(address: String): Boolean = try {
        adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: IllegalArgumentException) {
        // An address this adapter never saw is not bonded to it; the store is only
        // trusted to hold well-formed strings, and a malformed one answers the same way.
        false
    }

    /**
     * Scans for units advertising the dashboard service.
     *
     * The flow emits the whole list each time a new unit appears and closes when
     * the collector is cancelled. The service UUID filter is the first pass; the
     * `DPP-` name guard keeps a foreign device that reuses the UUID out of the
     * list.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<BleDevice>> = callbackFlow {
        val radio = adapter
        if (radio == null) {
            close(IOException("no_bluetooth"))
            return@callbackFlow
        }
        if (!radio.isEnabled) {
            close(IOException("bluetooth_off"))
            return@callbackFlow
        }
        val scanner = radio.bluetoothLeScanner
        if (scanner == null) {
            close(IOException("no_scanner"))
            return@callbackFlow
        }

        val found = LinkedHashMap<String, BleDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName
                    ?: runCatching { result.device.name }.getOrNull()
                    ?: return
                if (!name.startsWith("DPP-")) return
                found[result.device.address] = BleDevice(name, result.device.address, result.rssi)
                trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("scan_failed_$errorCode"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            close(IOException("permission"))
            return@callbackFlow
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Connects, bonds, negotiates the MTU, discovers the service and subscribes.
     *
     * Returns only when the link is ready or has failed; the screen watches
     * [state] for the stage it is in while this runs.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, name: String): Boolean = connectMutex.withLock {
        disconnectInternal()
        resetAssemblers()

        val radio = adapter ?: return fail("no_bluetooth")
        if (!radio.isEnabled) return fail("bluetooth_off")

        _state.value = BleLinkState.Connecting(name)

        val device = try {
            radio.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return fail("bad_address")
        } catch (e: SecurityException) {
            return fail("permission")
        }

        val connected = CompletableDeferred<Boolean>()
        connectDone = connected
        val client = try {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            connectDone = null
            return fail("permission")
        }
        if (client == null) {
            connectDone = null
            return fail("connect_failed")
        }
        gatt = client

        try {
            var up = try {
                withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
            } catch (e: TimeoutCancellationException) {
                false
            } finally {
                connectDone = null
            }

            if (!up) {
                // One patient retry. A unit that has just restarted - which is how every
                // firmware install ends - is advertising again within seconds, but a direct
                // connect to it comes back GATT_ERROR (133) on the bench (2026-11-02: after a
                // completed install the link card sat in connect_timeout for minutes while the
                // unit advertised happily). autoConnect = true asks the stack to wait for the
                // advertisement instead of demanding a connection now. When the unit is simply
                // gone this costs the same timeout it would have cost anyway.
                disconnectInternal()
                val patient = CompletableDeferred<Boolean>()
                connectDone = patient
                val waiting = try {
                    device.connectGatt(appContext, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    connectDone = null
                    return fail("permission")
                }
                if (waiting == null) {
                    connectDone = null
                    throw IOException("connect_failed")
                }
                gatt = waiting
                up = try {
                    withTimeout(CONNECT_TIMEOUT_MS) { patient.await() }
                } catch (e: TimeoutCancellationException) {
                    false
                } finally {
                    connectDone = null
                }
            }
            if (!up) throw IOException("connect_timeout")

            // A larger MTU is what keeps a config chunk to a handful of fragments.
            mtu = requestMtu(client, BleProto.TARGET_MTU)
            if (mtu < 23) mtu = 23

            _state.value = BleLinkState.Pairing(name)
            bond(device)

            _state.value = BleLinkState.Connecting(name)
            discover(client)

            val service = client.getService(SERVICE_UUID)
                ?: throw IOException("service_missing")
            val telem = service.getCharacteristic(TELEM_UUID)
                ?: throw IOException("telem_missing")
            val rpc = service.getCharacteristic(RPC_UUID)
                ?: throw IOException("rpc_missing")
            // Optional on purpose, see the field comment.
            otaChar = service.getCharacteristic(OTA_UUID)

            if (!subscribe(client, telem)) throw IOException("telem_subscribe_failed")
            if (!subscribe(client, rpc)) throw IOException("rpc_subscribe_failed")

            telemChar = telem
            rpcChar = rpc
            _state.value = BleLinkState.Ready(name, address, mtu).also { lastPeer = it }
            true
        } catch (e: Exception) {
            // A disconnect reported by the stack already said why; keep that reason
            // instead of replacing it with whatever the next await threw.
            if (_state.value is BleLinkState.Failed) {
                closeGatt()
                false
            } else {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Sends one request and waits for its answer.
     *
     * `config.get` arrives as several `BT_F_CHUNK` frames followed by the ordinary
     * response; both are collected under the same id, so the caller sees one
     * [BleReply] with [BleReply.chunks] merged.
     */
    suspend fun request(
        method: String,
        paramsJson: String? = null,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): BleReply {
        val client = gatt
        if (client == null || _state.value !is BleLinkState.Ready) return BleReply.fail("not_connected")

        return requestMutex.withLock {
            val id = nextId()
            val pendingReply = Pending(id, CompletableDeferred())
            pending[id] = pendingReply
            val json = buildString {
                append("{\"m\":").append(BleJson.quote(method))
                append(",\"id\":").append(id)
                if (paramsJson != null) append(",\"p\":").append(paramsJson)
                append('}')
            }
            try {
                send(BleProto.T_REQ, id, json.toByteArray(Charsets.UTF_8), client)
                try {
                    withTimeout(timeoutMs) { pendingReply.done.await() }
                } catch (e: TimeoutCancellationException) {
                    BleReply.fail("timeout")
                }
            } catch (e: Exception) {
                BleReply.fail(e.message ?: "write_failed")
            } finally {
                pending.remove(id)
            }
        }
    }

    /** True when the unit exposes the OTA characteristic at all. */
    fun otaAvailable(): Boolean = otaChar != null

    /**
     * Puts one firmware chunk on the wire.
     *
     * `WRITE_TYPE_NO_RESPONSE`, and the write is only issued once the stack reports the previous
     * one: that is the flow control a 1.5 MB transfer needs. It also means the transfer stops by
     * itself when the unit cannot keep up, instead of filling Android's outgoing queue, missing
     * ATT slots and getting the connection torn down — the failure mode that looks like a broken
     * cable.
     *
     * The write shares [writeMutex] with the RPC frames, one chunk at a time rather than one
     * locked burst, so `ota.status` and `ota.abort` still fit between chunks when the driver
     * presses a button mid-transfer.
     */
    suspend fun otaWrite(frame: ByteArray): Boolean {
        val client = gatt ?: return false
        val characteristic = otaChar ?: return false
        if (_state.value !is BleLinkState.Ready) return false
        return writeMutex.withLock {
            try {
                writeOtaFrame(client, characteristic, frame)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeOtaFrame(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
    ) {
        val done = CompletableDeferred<Int>()
        otaWriteDone = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == 0
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            client.writeCharacteristic(characteristic)
        }
        if (!started) {
            otaWriteDone = null
            throw IOException("ota_write_busy")
        }
        val status = try {
            withTimeout(OTA_WRITE_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            // Nothing came back for [OTA_WRITE_TIMEOUT_MS] of airtime. Say so rather than queue
            // more writes into a link that may already be gone; the transfer's resume path decides
            // whether it continues.
            throw IOException("ota_write_timeout")
        } finally {
            otaWriteDone = null
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("ota_write_status_$status")
    }

    /** Drops the link. The next [connect] starts from a clean GATT client. */
    fun disconnect() {
        disconnectInternal()
        _state.value = BleLinkState.Idle
    }

    /** Frees every callback; the instance cannot be reused afterwards. */
    fun close() {
        disconnect()
    }

    // -----------------------------------------------------------------------
    // Requests, writes and notifications
    // -----------------------------------------------------------------------

    private fun nextId(): Int {
        val next = seq.getAndIncrement()
        return if (next > 0) next else seq.getAndSet(1)
    }

    private suspend fun send(
        type: Int,
        seq: Int,
        payload: ByteArray,
        client: BluetoothGatt,
    ) {
        val characteristic = rpcChar ?: throw IOException("rpc_missing")
        for (frame in BleProto.frames(type, seq, payload, mtu)) {
            writeMutex.withLock { writeFrame(client, characteristic, frame) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeFrame(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
    ) {
        val done = CompletableDeferred<Int>()
        writeDone = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The API 33 overload answers with a status code, not a Boolean.
            client.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == 0
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            client.writeCharacteristic(characteristic)
        }
        if (!started) {
            writeDone = null
            throw IOException("write_busy")
        }
        val status = try {
            withTimeout(WRITE_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("write_timeout")
        } finally {
            writeDone = null
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("write_status_$status")
    }

    private suspend fun requestMtu(client: BluetoothGatt, wanted: Int): Int {
        val done = CompletableDeferred<Int>()
        mtuDone = done
        @SuppressLint("MissingPermission")
        val started = client.requestMtu(wanted)
        if (!started) {
            mtuDone = null
            return 23
        }
        return try {
            withTimeout(MTU_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            23
        } finally {
            mtuDone = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun bond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        val done = CompletableDeferred<Boolean>()
        bondDone = done
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                    ?: return
                if (changed.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            // False means "already bonding" or "already bonded"; the bond state is
            // the source of truth either way.
            device.createBond()
            // The broadcast is the fast path, but a bond can complete while the
            // system is showing its two dialogs and a receiver that misses one
            // event would hold the link in PAIRING until the timeout. Polling the
            // device's own bond state makes a missed broadcast harmless.
            val deadline = System.currentTimeMillis() + BOND_TIMEOUT_MS
            var bonded = false
            while (System.currentTimeMillis() < deadline) {
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    bonded = true
                    break
                }
                val event = withTimeoutOrNull(1_000) { done.await() }
                if (event == true) {
                    bonded = true
                    break
                }
                if (event == false) break
            }
            if (!bonded) throw IOException("pairing_failed")
        } finally {
            bondDone = null
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discover(client: BluetoothGatt) {
        // Retried with a settling pause rather than decided once. A link that came up through the
        // stack's pending/auto-connect path - which is how the app reconnects after a firmware
        // install reboots the unit - can refuse a discovery issued the instant it connects:
        // measured on the bench on 2026-11-02, `discoverServices()` came back false ~150 ms after
        // a status-0 connection, and the link was thrown away for it. The stack accepts the same
        // call a moment later.
        var tries = 0
        while (true) {
            val done = CompletableDeferred<Boolean>()
            serviceDone = done
            val started = client.discoverServices()
            val ok = if (!started) {
                serviceDone = null
                false
            } else {
                try {
                    withTimeout(DISCOVER_TIMEOUT_MS) { done.await() }
                } catch (e: TimeoutCancellationException) {
                    false
                } finally {
                    serviceDone = null
                }
            }
            if (ok) return
            // Someone tore the link down while we were asking; retrying on a dead client is a lie.
            if (gatt !== client) throw IOException("disconnected")
            tries++
            if (tries >= DISCOVER_ATTEMPTS) throw IOException("discover_failed")
            delay(250L * tries)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun subscribe(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!client.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: return false
        val done = CompletableDeferred<Int>()
        descriptorDone = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The API 33 overload answers with a status code, not a Boolean.
            client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            client.writeDescriptor(descriptor)
        }
        if (!started) {
            descriptorDone = null
            return false
        }
        val status = try {
            withTimeout(DESCRIPTOR_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            return false
        } finally {
            descriptorDone = null
        }
        return status == BluetoothGatt.GATT_SUCCESS
    }

    private fun onResponse(text: String) {
        val obj = BleJson.obj(text) ?: return
        val id = BleJson.int(obj, "id") ?: return
        val reply = pending[id] ?: return
        val ok = BleJson.bool(obj, "ok") ?: false
        val fields = obj["r"] as? Map<String, Any?> ?: emptyMap()
        val chunks = synchronized(reply) {
            if (reply.chunks.isEmpty()) null else LinkedHashMap(reply.chunks)
        }
        reply.done.complete(
            BleReply(
                ok = ok,
                error = BleJson.string(obj, "e"),
                body = text,
                fields = fields,
                chunks = chunks,
            ),
        )
    }

    private fun onChunk(text: String) {
        val obj = BleJson.obj(text) ?: return
        val id = BleJson.int(obj, "id") ?: return
        val reply = pending[id] ?: return
        val body = obj["d"] as? Map<String, Any?> ?: emptyMap()
        synchronized(reply) {
            reply.chunks.putAll(body)
        }
        BleJson.int(obj, "i")?.let { _syncChunks.tryEmit(it) }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            TELEM_UUID -> {
                val message = telemFrames.accept(value) ?: return
                if (message.type == BleProto.T_TELEM) {
                    _telemetry.tryEmit(String(message.payload, Charsets.UTF_8))
                }
            }
            RPC_UUID -> {
                val message = rpcFrames.accept(value) ?: return
                val text = String(message.payload, Charsets.UTF_8)
                when (message.type) {
                    BleProto.T_RESP -> onResponse(text)
                    BleProto.T_CHUNK -> onChunk(text)
                    BleProto.T_LOG -> _logs.tryEmit(text)
                    BleProto.T_OTA -> _otaEvents.tryEmit(text)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // GATT callbacks
    // -----------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectDone?.complete(true)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectDone?.complete(false)
                closeGatt()
                failPending("disconnected")
                if (_state.value is BleLinkState.Ready || _state.value is BleLinkState.Pairing ||
                    _state.value is BleLinkState.Connecting
                ) {
                    _state.value = BleLinkState.Failed("disconnected")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuDone?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            serviceDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            descriptorDone?.complete(status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // One in-flight write at a time across both characteristics, so only one of the two
            // deferreds is ever armed.
            writeDone?.complete(status)
            otaWriteDone?.complete(status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotify(characteristic.uuid, value)
        }

        @Deprecated("The three-argument callback is what API 26-32 delivers.")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            onNotify(characteristic.uuid, characteristic.value ?: return)
        }
    }

    // -----------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun disconnectInternal() {
        bondDone?.complete(false)
        bondDone = null
        closeGatt()
        failPending("disconnected")
    }

    private fun closeGatt() {
        val client = gatt ?: return
        gatt = null
        telemChar = null
        rpcChar = null
        otaChar = null
        // A transfer waiting on its next write must learn about this now, not four seconds later
        // when its own timeout fires.
        otaWriteDone?.complete(BluetoothGatt.GATT_FAILURE)
        mtu = 23
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }

    private fun failPending(reason: String) {
        val copy = pending.values.toList()
        pending.clear()
        for (reply in copy) reply.done.complete(BleReply.fail(reason))
    }

    private fun fail(reason: String): Boolean {
        closeGatt()
        _state.value = BleLinkState.Failed(reason)
        return false
    }

    private fun resetAssemblers() {
        telemFrames.reset()
        rpcFrames.reset()
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString(BleProto.SERVICE_UUID)
        val TELEM_UUID: UUID = UUID.fromString(BleProto.TELEM_UUID)
        val RPC_UUID: UUID = UUID.fromString(BleProto.RPC_UUID)
        val OTA_UUID: UUID = UUID.fromString(BleProto.OTA_UUID)
        val CCCD_UUID: UUID = UUID.fromString(BleProto.CCCD_UUID)

        const val CONNECT_TIMEOUT_MS = 20_000L
        const val MTU_TIMEOUT_MS = 5_000L
        const val BOND_TIMEOUT_MS = 180_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L

        /**
         * How many times [discover] asks the stack before giving up on a link that will not
         * enumerate its services. The pauses between are 250, 500, 750 ms.
         */
        const val DISCOVER_ATTEMPTS = 4
        const val DESCRIPTOR_TIMEOUT_MS = 8_000L
        const val WRITE_TIMEOUT_MS = 8_000L
        const val OTA_WRITE_TIMEOUT_MS = 8_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
