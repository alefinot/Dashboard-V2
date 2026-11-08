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

/**
 * The tag every connect-path and teardown line in the app is logged under, and nothing else
 * is: no telemetry frame, no RPC body, no OTA byte. Those stay on the wire and out of logcat.
 *
 * This is the permanent record behind the reconnect work, and it is meant to be captured on a
 * drive and read afterwards, not watched live:
 *
 * ```
 * adb logcat -c
 * adb logcat -s DPP-CONN bt > dpp-conn.logcat.txt      # in another window, for the whole drive
 * adb shell dumpsys bluetooth_manager > dpp-conn.dumpsys.txt   # at the end, once
 * ```
 *
 * The point of one tag is that the app's decisions and the stack's land on one clock: this tag
 * says which dial was made, which stage it reached and which path let the link go (with the
 * GATT `status` beside it), `bt` says what the stack made of the same moment, and
 * `dumpsys bluetooth_manager` says what the phone believes it is bonded to. Only the three
 * together separate "the unit took the connection and ended it" from "the stack tore it down
 * itself" — which is the question the reconnect ladder cannot answer from inside itself.
 *
 * A top-level constant rather than a logger class, because the tag is the whole idea; the view
 * model imports it, so a ladder's decision and the stack's answer to it share one filter.
 */
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

    /**
     * When the stack reported a link up, for the one number the bench capture needs: how long
     * the link just dropped had been alive. Zero while nothing is up, and nothing in this class
     * decides on it — the `DPP-CONN` log is its only reader.
     */
    @Volatile
    private var connectedAtMs = 0L

    /**
     * When and where the **last** client was closed, for the settle floor in [connect].
     *
     * The stack needs a moment between `close()` on an address and a new `connectGatt()` on the
     * same one. Measured on the bench on 2026-11-07: a redial issued ~18 ms after the previous
     * client went down came back with `requestMtu()` refused (link logged at MTU 23 one
     * millisecond after `dial up`) and `discoverServices()` refused, and the same call
     * `discover()` makes seconds later was accepted. The floor costs a quarter of a second on a
     * redial and removes a whole class of "the phone refuses to reconnect until you restart it".
     */
    @Volatile
    private var lastCloseAtMs = 0L

    @Volatile
    private var lastClosedAddress: String? = null

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
    //
    // Volatile, and not for tidiness: every one of these is assigned on the connect coroutine's
    // thread and *completed* on a binder callback thread, so without a visibility guarantee the
    // callback may read a stale `null` and drop the answer it was carrying. That reads on the bench
    // exactly like a stack that refuses the call - `mtu negotiated=23`, `discover refused` - which is
    // why it is fixed here rather than left as a style question. The deferreds themselves are
    // thread-safe; only the references were racing.
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
    } catch (e: SecurityException) {
        // On Android 12+ both `getRemoteDevice` and `bondState` are gated on BLUETOOTH_CONNECT, and
        // this answer is asked from the vehicle page's FORGET, which runs uncaught in the
        // view model's scope. A card the owner is trying to forget must never be able to take the
        // process with it - and "this phone cannot say" is genuinely not bonded, for every decision
        // this feeds: there is no pairing the app may claim to hold. (Bench audit 2026-11-07, item
        // found by the whole-tree call-site inventory; the API 31+ path is otherwise untested here.)
        Log.w(DPP_CONN_LOG_TAG, "isBonded refused by permission address=$address")
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
        // Ask Android whether it would answer at all before spending the sweep's window on a
        // radio that is going to call back zero times. See [locationServiceOn].
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

    /**
     * Whether Android would hand this scan a single result.
     *
     * On Android 11 and below the system folds the position **switch** into the BLE rule: with
     * `ACCESS_FINE_LOCATION` granted and the switch off, `startScan()` accepts the request, costs
     * the sweep its whole window, and calls back zero times. Nothing in the callback distinguishes
     * that from a unit that is switched off, so the empty list was read as "the dashboard did not
     * answer" and the owner was sent to check a unit that was never asked (SM-T590 on Android 10,
     * 2026-11-07: the garage hunt found nothing while the same unit happily served the phone next
     * to it — which holds a saved pairing and so dials an address instead of scanning). Android 12
     * moved the gate to `BLUETOOTH_SCAN`, which the manifest declares with `neverForLocation`, and
     * the same manifest caps `ACCESS_FINE_LOCATION` at `maxSdkVersion="30"`; this check stops at
     * the same line the manifest does.
     *
     * It fails **open**: a missing or unreadable [LocationManager] answers "on", because a wrong
     * refusal here blocks a sweep Android was going to deliver, and that is the failure this app
     * can least explain to a user. Only a definite off stops the sweep.
     */
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

    /**
     * Sit out the remainder of [CLOSE_SETTLE_MS] before opening another client for [address].
     *
     * Android tears a GATT client down asynchronously: `close()` returns immediately, and the
     * stack is still reaping that address's connection when the next `connectGatt()` lands on it.
     * The client that arrives too early is accepted and then crippled - it connects, and then the
     * calls that follow are refused: `requestMtu()` returns false in the same millisecond (so the
     * link is logged at MTU 23 with no `onMtuChanged` at all), `discoverServices()` returns false,
     * and the connection is dropped seconds later. Every one of those numbers is in the bench log
     * of 2026-11-07 as `mtu negotiated=23`, `discover refused by the stack` and
     * `linkAgeMs=7` - and the same dial, a quarter of a second later, comes up at MTU 517 and
     * stays up.
     *
     * The floor is per address, because it is about that address's teardown: dialling a different
     * unit is a different client and is never held back. It is also deliberately short and always
     * logged - a wait nobody can see reads to a user as a hung CONNECT button, and an unlogged
     * quarter-second makes every later number in a capture suspect.
     */
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

    /**
     * Connects, bonds, negotiates the MTU, discovers the service and subscribes.
     *
     * Returns only when the link is ready or has failed; the screen watches
     * [state] for the stage it is in while this runs.
     */
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
                // One patient retry. A unit that has just restarted - which is how every
                // firmware install ends - is advertising again within seconds, but a direct
                // connect to it comes back GATT_ERROR (133) on the bench (2026-11-02: after a
                // completed install the link card sat in connect_timeout for minutes while the
                // unit advertised happily). autoConnect = true asks the stack to wait for the
                // advertisement instead of demanding a connection now. When the unit is simply
                // gone this costs the same timeout it would have cost anyway.
                disconnectInternal("patient retry after a direct timeout")
                // The same floor as at the top of this method, and this is the dial that needs it
                // more: it follows a `close()` a few milliseconds ago, in the same breath. Without
                // it the bench produced, twice on 2026-11-07, a patient dial that came up
                // (`gatt connected status=0`), had `requestMtu()` refused inside the millisecond
                // (`mtu negotiated=23` with no `onMtuChanged` in between), had `discoverServices()`
                // refused, and was dropped seven milliseconds after it connected - the pattern of a
                // client the stack is still reaping for that address. With it, the same dial comes
                // up at MTU 517 and stays up.
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
            // Somebody cancelled the attempt - a second SEARCH pressed over this one, the screen
            // leaving the foreground, the vehicle changing under the hunt. That is not a fact about
            // the radio, and today it was reported as one: this method used to end in
            // `catch (e: Exception)`, which swallows cancellation and returns `false`, so the
            // ladder spent a rung and printed `link failed reason=StandaloneCoroutine was
            // cancelled` about its own coroutine machinery (bench, 2026-11-07).
            //
            // Two things are true on the way out. The client this call opened is closed - a
            // cancelled attempt that leaves a client registered is what made the *next* dial see
            // `services=0` and a refused `discoverServices()`. And the state goes to `Idle`, not
            // `Failed`: "no link, nobody is trying" is the honest answer, and the reconnect guards
            // read a lingering `Connecting` as a link being built, which would park every rearm
            // behind an attempt that no longer exists.
            Log.d(DPP_CONN_LOG_TAG, "connect cancelled address=$address cause=${e.message ?: e.javaClass.simpleName}")
            closeGatt("attempt cancelled")
            _state.value = BleLinkState.Idle
            throw e
        } catch (e: Exception) {
            // A disconnect reported by the stack already said why; keep that reason
            // instead of replacing it with whatever the next await threw.
            if (_state.value is BleLinkState.Failed) {
                closeGatt("link already reported failed")
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
     * Asks the stack for the fast or the ordinary connection interval, and answers whether it asked.
     *
     * This is the biggest single multiplier available on a firmware push. `CONNECTION_PRIORITY_BALANCED`
     * (what Android uses when nobody asks, which is what this app did until now) is a 30-50 ms
     * interval; `CONNECTION_PRIORITY_HIGH` is 11.25-15 ms. At 496 bytes per write the bench install
     * ran one write every 79 ms - about half the slots the ordinary interval already offers are
     * being lost in the app's own write path, and the fast interval would roughly triple what is
     * there to lose (`INSTALL-SPEED-PLAN-2026-11-03`, task A2).
     *
     * It is therefore asked for **for the chunk phase of an install only**, and given back the moment
     * the install ends, aborts, fails or the link goes away. That is not decoration: there are field
     * reports of OEM stacks dropping the link outright while HIGH is held, and a dropped install
     * costs a RESUME press inside the unit's 120 s window rather than the whole image. Android gives
     * no way to read back the interval that was actually agreed (and some stacks simply ignore the
     * request), so its effect is measured as writes/s, never observed directly.
     */
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

    /**
     * The transfer's entry point to [applyConnectionPriority]: `true` once the unit has opened its
     * spare slot, `false` when the chunk phase ends.
     *
     * Suspended because everything around an install is suspending. [applyConnectionPriority] is
     * not, because the teardown path that has to hand the ordinary interval back runs on a GATT
     * callback thread with no coroutine to suspend in - and a fallback that can be skipped by
     * picking the wrong call site is not a fallback.
     */
    suspend fun setConnectionPriority(high: Boolean): Boolean = applyConnectionPriority(high)

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
        disconnectInternal("app disconnect")
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
        // A GATT write answers with a status, not an exception, and this status is the
        // difference between notifications arriving and silence forever. Both characteristics
        // are logged because the bench report that started this was a link that came up, said
        // nothing, and left the screen showing the previous minute.
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

    // -----------------------------------------------------------------------
    // GATT callbacks
    // -----------------------------------------------------------------------

    /**
     * Whether a callback belongs to the client this link currently holds, and therefore whether
     * its answer may be allowed to touch anything.
     *
     * One `BluetoothGattCallback` instance is handed to every `connectGatt()` this class makes, so
     * every callback used to complete *whatever operation was armed at that moment*, with no way to
     * tell which client the stack was talking about. That is the bug the bench caught on
     * 2026-11-07: the *previous* client's `STATE_DISCONNECTED` arrived 18 ms into a fresh attempt,
     * completed the *new* attempt's `connectDone` with `false`, and `closeGatt()` then threw the
     * brand-new link away at `linkAgeMs=7`. Same shape, second symptom: a status-0 connection on
     * which `requestMtu()` and `discoverServices()` were both refused, `mtu negotiated=23` logged
     * one millisecond after `dial up`, and a redial that then saw `services=0`.
     *
     * Android gives the callback no token of its own, so identity is the only thing there is to
     * compare, and `BluetoothGatt` does not implement `equals()` — which makes `===` exactly what
     * is wanted, not an accident. A callback from a client this class no longer holds is a fact
     * about a dead link, and the only honest thing to do with it is log it and drop it. The
     * deliberate teardown that ended that client already logged its own `teardown cause=` line with
     * the link's age, so nothing about the story is lost — the pair of lines is the whole event.
     *
     * It answers `false` while a teardown is in flight (`gatt == null`) on purpose: after
     * [closeGatt] the operations are already unarmed, so the callback has nothing left to answer.
     */
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
                // `status` is the number the capture is after. The stack names "Local Host
                // Terminated" (0x16) here just as plainly as a supervision timeout, and whether
                // the phone or the unit hung up is the one thing the ladder cannot work out.
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
            // A frame from a zombie client is worse than no frame: both assemblers are per
            // characteristic and shared by the live link, so a FIRST/LAST pair that belonged to the
            // dead one splices a snapshot into the middle of a config chunk.
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

    // -----------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(cause: String) {
        bondDone?.complete(false)
        bondDone = null
        closeGatt(cause)
        failPending("disconnected")
    }

    /**
     * [cause] is for the log and not for a decision: the capture that wants to know why phones
     * stop answering a dial has to be able to tell which path ended each link, and every
     * teardown of a live client is exactly one line — including how long that link had been up.
     */
    private fun closeGatt(cause: String) {
        val client = gatt ?: return
        // A teardown of a link the stack never reported up carries no age, and logging `0` there
        // would read as an instant.
        val ageMs = if (connectedAtMs > 0) System.currentTimeMillis() - connectedAtMs else -1L
        connectedAtMs = 0L
        Log.d(DPP_CONN_LOG_TAG, "teardown cause=$cause address=${client.device.address}" +
            (if (ageMs >= 0) " linkAgeMs=$ageMs" else ""))
        // Hand the ordinary connection interval back before the client goes. On a real disconnect
        // the stack may well refuse - the link is already gone - but this is the one place that
        // every teardown path runs through, so a phone cannot be left holding HIGH after an install
        // that ended by dropping the link. The honest guarantee is still the transfer's own
        // end/abort/failure paths; this is the net under them.
        applyConnectionPriority(false)
        gatt = null
        telemChar = null
        rpcChar = null
        otaChar = null
        // Every operation this client was allowed to be waiting on is answered NOW, from here, and
        // never again by the stack: the guard in [ownsClient] drops that client's callbacks from this
        // instant, so a deferred left armed here would sit until its own timeout - `bond`'s poll out
        // of a 180 s budget, `connectDone` out of 20 s - turning a clean teardown into a link card
        // that says "PAIRING" for three minutes after the unit went out of range. Values are the
        // same ones the failure paths already use, so nobody downstream can mistake these for
        // answers from the air.
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
        // A transfer waiting on its next write must learn about this now, not four seconds later
        // when its own timeout fires.
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

        /**
         * How long to sit after closing a client before opening another one for the **same**
         * address. See [lastCloseAtMs] for the bench measurement that put the number here; it is
         * the same settling pause [discover] already found it needed for its own retries.
         */
        const val CLOSE_SETTLE_MS = 250L

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
