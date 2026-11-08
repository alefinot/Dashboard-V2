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
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
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
import kotlin.coroutines.cancellation.CancellationException
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

const val DPP_CONN_LOG_TAG = "DPP-CONN"

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

class BleLink(context: Context) {

    private val appContext = context.applicationContext

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<BleLinkState>(BleLinkState.Idle)
    val state: StateFlow<BleLinkState> = _state.asStateFlow()

    var lastPeer: BleLinkState.Ready? = null
        private set

    /** The 1 Hz snapshot, exactly as the unit frames it (the old `/api/snapshot` body). */
    private val _telemetry = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val telemetry: SharedFlow<String> = _telemetry.asSharedFlow()

    /** `BT_F_LOG` frames, when the unit sends one. */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _otaEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val otaEvents: SharedFlow<String> = _otaEvents.asSharedFlow()

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
    private var connectedAtMs = 0L

    @Volatile
    private var lastCloseAtMs = 0L

    @Volatile
    private var lastClosedAddress: String? = null

    @Volatile
    private var telemChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var rpcChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var otaChar: BluetoothGattCharacteristic? = null

    // Callback-thread only.
    private val telemFrames = FrameAssembler()
    private val rpcFrames = FrameAssembler()

    @Volatile private var connectDone: CompletableDeferred<Boolean>? = null
    @Volatile private var bondDone: CompletableDeferred<Boolean>? = null
    @Volatile private var serviceDone: CompletableDeferred<Boolean>? = null
    @Volatile private var mtuDone: CompletableDeferred<Int>? = null
    @Volatile private var descriptorDone: CompletableDeferred<Int>? = null
    @Volatile private var writeDone: CompletableDeferred<Int>? = null
    @Volatile private var otaWriteDone: CompletableDeferred<Int>? = null

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()

    private class Pending(val id: Int, val done: CompletableDeferred<BleReply>) {
        val chunks = LinkedHashMap<String, Any?>()
    }

    /** True when the phone has a Bluetooth radio at all. */
    fun available(): Boolean = adapter != null

    /** True when the radio exists and is switched on. */
    fun enabled(): Boolean = adapter?.isEnabled == true

    fun isBonded(address: String): Boolean = try {
        adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: SecurityException) {
        Log.w(DPP_CONN_LOG_TAG, "isBonded refused by permission address=$address")
        false
    }

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
        if (!locationServiceOn()) {
            close(IOException("location_off"))
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

    private fun locationServiceOn(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                // 8.0 and 8.1 have no isLocationEnabled: the switch is the two providers behind it.
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(true)
    }

    private suspend fun settleFor(address: String, why: String) {
        val closed = lastCloseAtMs
        if (closed <= 0L || lastClosedAddress != address) return
        val since = System.currentTimeMillis() - closed
        val waitMs = CLOSE_SETTLE_MS - since
        if (waitMs <= 0L) return
        Log.d(
            DPP_CONN_LOG_TAG,
            "redial settle waited=${waitMs}ms address=$address sinceClose=${since}ms reason=$why",
        )
        delay(waitMs)
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, name: String): Boolean = connectMutex.withLock {
        Log.d(DPP_CONN_LOG_TAG, "connect start name=$name address=$address")
        disconnectInternal("replaced by a new connect")
        resetAssemblers()

        val radio = adapter ?: return fail("no_bluetooth")
        if (!radio.isEnabled) return fail("bluetooth_off")

        _state.value = BleLinkState.Connecting(name)

        settleFor(address, "entry")

        val device = try {
            radio.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return fail("bad_address")
        } catch (e: SecurityException) {
            return fail("permission")
        }

        val connected = CompletableDeferred<Boolean>()
        connectDone = connected
        Log.d(DPP_CONN_LOG_TAG, "dial direct address=$address timeoutMs=$CONNECT_TIMEOUT_MS")
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
                Log.d(DPP_CONN_LOG_TAG, "dial direct timed out address=$address after ${CONNECT_TIMEOUT_MS} ms")
                false
            } finally {
                connectDone = null
            }

            if (!up) {
                disconnectInternal("patient retry after a direct timeout")
                settleFor(address, "patient retry")
                Log.d(DPP_CONN_LOG_TAG, "dial patient address=$address autoConnect=true timeoutMs=$CONNECT_TIMEOUT_MS")
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
                    Log.d(DPP_CONN_LOG_TAG, "dial patient timed out address=$address after ${CONNECT_TIMEOUT_MS} ms")
                    false
                } finally {
                    connectDone = null
                }
            }
            if (!up) throw IOException("connect_timeout")
            Log.d(DPP_CONN_LOG_TAG, "dial up address=$address")

            // A larger MTU is what keeps a config chunk to a handful of fragments.
            mtu = requestMtu(client, BleProto.TARGET_MTU)
            if (mtu < 23) mtu = 23
            Log.d(DPP_CONN_LOG_TAG, "mtu negotiated=$mtu address=$address")

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
            Log.d(DPP_CONN_LOG_TAG, "ready name=$name address=$address mtu=$mtu")
            true
        } catch (e: CancellationException) {
            Log.d(DPP_CONN_LOG_TAG, "connect cancelled address=$address cause=${e.message ?: e.javaClass.simpleName}")
            closeGatt("attempt cancelled")
            _state.value = BleLinkState.Idle
            throw e
        } catch (e: Exception) {
            if (_state.value is BleLinkState.Failed) {
                closeGatt("link already reported failed")
                false
            } else {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

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

    @SuppressLint("MissingPermission")
    private fun applyConnectionPriority(high: Boolean): Boolean {
        val client = gatt ?: return false
        if (_state.value !is BleLinkState.Ready) return false
        return runCatching {
            client.requestConnectionPriority(
                if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH
                else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
            )
        }.getOrDefault(false)
    }

    suspend fun setConnectionPriority(high: Boolean): Boolean = applyConnectionPriority(high)

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
            throw IOException("ota_write_timeout")
        } finally {
            otaWriteDone = null
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("ota_write_status_$status")
    }

    /** Drops the link. The next [connect] starts from a clean GATT client. */
    fun disconnect() {
        disconnectInternal("app disconnect")
        _state.value = BleLinkState.Idle
    }

    /** Frees every callback; the instance cannot be reused afterwards. */
    fun close() {
        disconnect()
    }

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
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.d(DPP_CONN_LOG_TAG, "bond already bonded address=${device.address}")
            return
        }
        Log.d(DPP_CONN_LOG_TAG, "bond start address=${device.address} timeoutMs=$BOND_TIMEOUT_MS")

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
            device.createBond()
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
            if (!bonded) {
                Log.d(DPP_CONN_LOG_TAG, "bond failed address=${device.address} after ${BOND_TIMEOUT_MS} ms")
                throw IOException("pairing_failed")
            }
            Log.d(DPP_CONN_LOG_TAG, "bond ok address=${device.address}")
        } finally {
            bondDone = null
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discover(client: BluetoothGatt) {
        var tries = 0
        while (true) {
            val done = CompletableDeferred<Boolean>()
            serviceDone = done
            Log.d(DPP_CONN_LOG_TAG, "discover attempt=${tries + 1} of $DISCOVER_ATTEMPTS address=${client.device.address}")
            val started = client.discoverServices()
            val ok = if (!started) {
                serviceDone = null
                Log.d(DPP_CONN_LOG_TAG, "discover refused by the stack attempt=${tries + 1}")
                false
            } else {
                try {
                    withTimeout(DISCOVER_TIMEOUT_MS) { done.await() }
                } catch (e: TimeoutCancellationException) {
                    Log.d(DPP_CONN_LOG_TAG, "discover timed out attempt=${tries + 1} after ${DISCOVER_TIMEOUT_MS} ms")
                    false
                } finally {
                    serviceDone = null
                }
            }
            if (ok) {
                Log.d(DPP_CONN_LOG_TAG, "discover ok services=${client.services?.size ?: 0}")
                return
            }
            // Someone tore the link down while we were asking; retrying on a dead client is a lie.
            if (gatt !== client) throw IOException("disconnected")
            tries++
            if (tries >= DISCOVER_ATTEMPTS) throw IOException("discover_failed")
            delay(250L * tries)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun subscribe(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!client.setCharacteristicNotification(characteristic, true)) {
            Log.d(DPP_CONN_LOG_TAG, "subscribe ${characteristic.uuid} refused by the stack")
            return false
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: run {
                Log.d(DPP_CONN_LOG_TAG, "subscribe ${characteristic.uuid} has no CCCD")
                return false
            }
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
            Log.d(DPP_CONN_LOG_TAG, "subscribe ${characteristic.uuid} write refused by the stack")
            return false
        }
        val status = try {
            withTimeout(DESCRIPTOR_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            Log.d(DPP_CONN_LOG_TAG, "subscribe ${characteristic.uuid} timed out after ${DESCRIPTOR_TIMEOUT_MS} ms")
            return false
        } finally {
            descriptorDone = null
        }
        val subbed = status == BluetoothGatt.GATT_SUCCESS
        Log.d(DPP_CONN_LOG_TAG, "subscribed ${characteristic.uuid} ${if (subbed) "ok" else "failed status=$status"}")
        return subbed
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

private fun ownsClient(from: BluetoothGatt, event: String): Boolean {
        val live = gatt
        if (live != null && from === live) return true
        Log.d(
            DPP_CONN_LOG_TAG,
            "callback from a client this link no longer holds ignored event=$event" +
                " address=${from.device.address}" +
                " from=0x${System.identityHashCode(from).toString(16)}" +
                " live=${if (live == null) "none" else "0x" + System.identityHashCode(live).toString(16)}",
        )
        return false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!ownsClient(g, "state=$newState status=$status")) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedAtMs = System.currentTimeMillis()
                Log.d(DPP_CONN_LOG_TAG, "gatt connected status=$status address=${g.device.address}")
                connectDone?.complete(true)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(DPP_CONN_LOG_TAG, "gatt disconnected status=$status (0x${status.toString(16)}) newState=$newState address=${g.device.address}")
                connectDone?.complete(false)
                closeGatt("gatt status=$status")
                failPending("disconnected")
                if (_state.value is BleLinkState.Ready || _state.value is BleLinkState.Pairing ||
                    _state.value is BleLinkState.Connecting
                ) {
                    _state.value = BleLinkState.Failed("disconnected")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!ownsClient(g, "mtu=$mtu status=$status")) return
            mtuDone?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!ownsClient(g, "services status=$status")) return
            serviceDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!ownsClient(g, "descriptor=${descriptor.uuid} status=$status")) return
            descriptorDone?.complete(status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!ownsClient(g, "write=${characteristic.uuid} status=$status")) return
            writeDone?.complete(status)
            otaWriteDone?.complete(status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!ownsClient(g, "notify=${characteristic.uuid}")) return
            onNotify(characteristic.uuid, value)
        }

        @Deprecated("The three-argument callback is what API 26-32 delivers.")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!ownsClient(g, "notify=${characteristic.uuid}")) return
            @Suppress("DEPRECATION")
            onNotify(characteristic.uuid, characteristic.value ?: return)
        }
    }

@SuppressLint("MissingPermission")
    private fun disconnectInternal(cause: String) {
        bondDone?.complete(false)
        bondDone = null
        closeGatt(cause)
        failPending("disconnected")
    }

    private fun closeGatt(cause: String) {
        val client = gatt ?: return
        val ageMs = if (connectedAtMs > 0) System.currentTimeMillis() - connectedAtMs else -1L
        connectedAtMs = 0L
        Log.d(DPP_CONN_LOG_TAG, "teardown cause=$cause address=${client.device.address}" +
            (if (ageMs >= 0) " linkAgeMs=$ageMs" else ""))
        applyConnectionPriority(false)
        gatt = null
        telemChar = null
        rpcChar = null
        otaChar = null
        connectDone?.complete(false)
        connectDone = null
        bondDone?.complete(false)
        bondDone = null
        serviceDone?.complete(false)
        serviceDone = null
        mtuDone?.complete(23)
        mtuDone = null
        descriptorDone?.complete(BluetoothGatt.GATT_FAILURE)
        descriptorDone = null
        writeDone?.complete(BluetoothGatt.GATT_FAILURE)
        writeDone = null
        otaWriteDone?.complete(BluetoothGatt.GATT_FAILURE)
        otaWriteDone = null
        mtu = 23
        lastCloseAtMs = System.currentTimeMillis()
        lastClosedAddress = client.device.address
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }

    private fun failPending(reason: String) {
        val copy = pending.values.toList()
        pending.clear()
        for (reply in copy) reply.done.complete(BleReply.fail(reason))
    }

    private fun fail(reason: String): Boolean {
        Log.d(DPP_CONN_LOG_TAG, "connect failed reason=$reason was=${_state.value::class.simpleName}")
        closeGatt("connect failed $reason")
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

        const val CLOSE_SETTLE_MS = 250L

        const val DISCOVER_ATTEMPTS = 4
        const val DESCRIPTOR_TIMEOUT_MS = 8_000L
        const val WRITE_TIMEOUT_MS = 8_000L
        const val OTA_WRITE_TIMEOUT_MS = 8_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
