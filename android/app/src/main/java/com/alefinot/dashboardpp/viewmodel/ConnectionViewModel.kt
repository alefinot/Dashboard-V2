package com.alefinot.dashboardpp.viewmodel

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.ble.DPP_CONN_LOG_TAG
import com.alefinot.dashboardpp.ble.scanFailureText
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.RecorderTarget
import com.alefinot.dashboardpp.vehicles.UnitIdentity
import com.alefinot.dashboardpp.weather.HostFrames
import com.alefinot.dashboardpp.weather.WeatherRuntime
import com.alefinot.dashboardpp.weather.WeatherSettings
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.CropRect
import com.alefinot.dashboardpp.vehicles.VehiclePhotoStore
import com.alefinot.dashboardpp.vehicles.VehicleStore
import com.alefinot.dashboardpp.vehicles.displayName
import com.alefinot.dashboardpp.vehicles.macConflict
import com.alefinot.dashboardpp.vehicles.mergeTypedFields
import com.alefinot.dashboardpp.vehicles.refusalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ConnectionViewModel(
    context: Context,
    private val vehicles: VehicleStore = VehicleStore(context),
    private val tripSettings: RecorderSettings = RecorderSettings(context),
    /** The one link for the process. Injected so the ladder can be tested without a radio. */
    val link: BleLink = BleRuntime.link(context),
    private val api: BleApi = BleApi(link),
) {
    private val appContext = context.applicationContext
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    /** The attempt the core is driving right now, if any. */
    private var ladder: Job? = null

    /** The retry the reconnect ladder scheduled, if any. One at a time, always replaced. */
    private var retry: Job? = null

    /** Mirrors [BleLink.state] so an unsolicited link change still reaches the ladder. */
    private var linkWatch: Job? = null

    private var adapterReceiver: BroadcastReceiver? = null

    private var attempts = 0

    private var rearm: Job? = null

    private var rearms = 0

    private var foreground = false

    private var gaveUp = false

    val uiState = mutableStateOf<ConnectionUiState>(ConnectionUiState.Idle(null))

    /** True while a radio sweep is running. */
    val scanning = mutableStateOf(false)

    val scanResults = mutableStateOf<List<Found>>(emptyList())

    /** Why the last sweep could not run, in words — never a stack trace, never a code. */
    val scanError = mutableStateOf<String?>(null)

    /** Whether the phone's radio is on, as last heard from the system. */
    val adapterOn = mutableStateOf(false)

    val permissionMissing = mutableStateOf(false)

    val unclaimed = mutableStateOf<UnclaimedUnit?>(null)

    val bondNote = mutableStateOf<String?>(null)

    val notice = mutableStateOf<String?>(null)

    /** The card the app is currently reaching for. */
    val selectedVehicleId = mutableStateOf<String?>(null)

    val timeNote = mutableStateOf<String?>(null)

    /** The link whose clock was last pushed; null means "this link, not yet". */
    private var clockPushedFor: String? = null

    private var weatherWokenFor: String? = null

    /** The 60 s feed tick. Owned here because this is where a link's life is visible. */
    private var weatherTick: Job? = null

    val photoVersion = mutableIntStateOf(0)

    /** The garage, for the vehicle page. */
    fun vehicleCards() = vehicles.all()

    suspend fun garageDamage(): String? = vehicles.damageNote()

    suspend fun recorderTarget(): RecorderTarget? {
        val c = uiState.value as? ConnectionUiState.Connected ?: return null
        val name = try {
            vehicles.all().first().firstOrNull { it.id == c.vehicleId }?.name
        } catch (e: Exception) {
            null
        }
        return RecorderTarget(
            UnitIdentity.key(mac = c.mac, bleAddress = c.address),
            c.vehicleId, name.orEmpty(), c.mac.orEmpty(),
        )
    }

    fun boot() {
        watchAdapter()
        watchLink()
        startWeatherTick()
        adapterOn.value = link.enabled()
        permissionMissing.value = missingBlePermissions().isNotEmpty()
        scope.launch {
            selectedVehicleId.value = vehicles.selectedId()
            startAttempt(ConnectMode.SILENT)
        }
    }

    fun onForegroundChanged(on: Boolean) {
        Log.d(DPP_CONN_LOG_TAG, "foreground on=$on gaveUp=$gaveUp rearms=$rearms")
        foreground = on
        if (!on) {
            rearm?.cancel()
            rearm = null
            return
        }
        scope.launch {
            if (!gaveUp) return@launch
            if (rearmWantedNow()) dialForRearm() else scheduleRearm()
        }
    }

    fun rescan() {
        attempts = 0
        gaveUp = false
        rearm?.cancel()
        bondNote.value = null
        startAttempt(ConnectMode.SEARCH)
    }

    fun pairWith(found: Found) {
        attempts = 0
        gaveUp = false
        rearm?.cancel()
        bondNote.value = null
        startAttempt(ConnectMode.SEARCH, pinned = found, preScanned = scanResults.value)
    }

    fun forgetUnit(vehicleId: String) {
        scope.launch {
            val card = vehicles.get(vehicleId) ?: return@launch
            val bonded = card.bleAddress?.let { link.isBonded(it) } == true
            val decision = ConnectionCore.forgetUnit(card.bleAddress != null, bonded)
            if (card.bleAddress == null && card.mac == null) {
                bondNote.value = "That card holds no pairing on this phone."
                return@launch
            }
            if (!vehicles.put(card.copy(bleAddress = null, mac = null))) {
                bondNote.value = vehicles.saveFailure("Pairing not forgotten") +
                    " The card may look unpaired until the app is restarted."
                return@launch
            }
            if ((uiState.value as? ConnectionUiState.Connected)?.vehicleId == vehicleId) {
                disconnect()
            } else {
                uiState.value = ConnectionUiState.Idle(
                    "Select a vehicle to connect to it over Bluetooth.",
                )
            }
            bondNote.value = decision.note
            unclaimed.value = null
            notice.value = null
        }
    }

    fun retryAfterRadio() {
        val st = uiState.value
        if (st is ConnectionUiState.Connecting || st is ConnectionUiState.Connected) return
        startAttempt(ConnectMode.SILENT)
    }

    suspend fun vehicleCount(): Int = try {
        vehicles.all().first().size
    } catch (e: Exception) {
        0
    }

    fun connectVehicle(vehicleId: String, mode: ConnectMode = ConnectMode.SEARCH) {
        attempts = 0
        gaveUp = false
        ladder?.cancel()
        retry?.cancel()
        rearm?.cancel()
        scope.launch {
            vehicles.select(vehicleId)
            selectedVehicleId.value = vehicleId
            notice.value = null
            unclaimed.value = null
            startAttempt(mode)
        }
    }

    fun huntNow(mode: ConnectMode = ConnectMode.PROBE) {
        attempts = 0
        gaveUp = false
        rearm?.cancel()
        scope.launch {
            val card = selectedCard()
            when (val step = ReconnectLadder.step(
                LinkEvent.UserAsked, attempts, card?.hasPairing == true, link.enabled(),
            )) {
                is LadderStep.GiveUp -> uiState.value = ConnectionUiState.Failed(step.reason)
                else -> startAttempt(mode)
            }
        }
    }

    fun selectVehicle(vehicleId: String) {
        attempts = 0
        gaveUp = false
        ladder?.cancel()
        retry?.cancel()
        rearm?.cancel()
        scope.launch {
            vehicles.select(vehicleId)
            selectedVehicleId.value = vehicleId
            notice.value = null
            unclaimed.value = null
            val live = uiState.value
            val ends = CardSelection.endsLiveSession(
                sessionVehicleId = (live as? ConnectionUiState.Connected)?.vehicleId,
                selectedVehicleId = vehicleId,
            )
            if (ends) {
                Log.d(
                    DPP_CONN_LOG_TAG,
                    "session ended cause=selected another card vehicle=${
                        (live as ConnectionUiState.Connected).vehicleId
                    } -> $vehicleId",
                )
                uiState.value = ConnectionUiState.Idle(null)
            }
        }
    }

    fun editDraft(vehicleId: String, onDraft: (Vehicle?) -> Unit) {
        scope.launch { onDraft(vehicles.get(vehicleId)) }
    }

    fun adoptDraft(onDraft: (Vehicle?) -> Unit) {
        val unit = unclaimed.value ?: return
        scope.launch {
            val seen = now()
            onDraft(
                Vehicle(
                    id = vehicles.nextId(),
                    mac = unit.mac,
                    bleAddress = unit.address,
                    firmwareVersion = unit.version,
                    lastConnectedAtMs = seen,
                    createdAtMs = seen,
                ),
            )
        }
    }

    fun saveVehicle(typed: Vehicle, onDone: (refusal: String?) -> Unit = {}) {
        scope.launch {
            val all = vehicles.all().first()
            val stored = all.firstOrNull { it.id == typed.id }
            val merged = stored?.let { mergeTypedFields(it, typed) } ?: typed
            val taken = all.filter { it.id != merged.id }.map { it.name }.toSet()
            val finished = merged.copy(name = displayName(merged.name, merged, taken))
            macConflict(finished, all)?.let { clash ->
                onDone(refusalMessage(clash))
                return@launch
            }
            if (!vehicles.put(finished)) {
                onDone(vehicles.saveFailure())
                return@launch
            }
            if (stored == null) {
                vehicles.select(finished.id)
                selectedVehicleId.value = finished.id
                unclaimed.value = null
            }
            onDone(null)
        }
    }

    fun deleteVehicle(vehicleId: String, onDone: (refusal: String?) -> Unit = {}) {
        scope.launch {
            val v = vehicles.get(vehicleId) ?: run {
                onDone(null)
                return@launch
            }
            val ownsSession = (uiState.value as? ConnectionUiState.Connected)?.vehicleId == vehicleId
            if (ownsSession) ladder?.cancel()
            val key = UnitIdentity.key(v.mac, v.bleAddress)
            tripSettings.clearSessions(listOf(key))
            WeatherSettings(appContext).clearCaches(listOf(key))
            HostFrames.clear(key)
            if (!vehicles.delete(vehicleId)) {
                onDone(vehicles.saveFailure("Card not deleted"))
                return@launch
            }
            if (selectedVehicleId.value == vehicleId) {
                val next = vehicles.all().first().firstOrNull()
                if (next != null) {
                    vehicles.select(next.id)
                    selectedVehicleId.value = next.id
                } else {
                    selectedVehicleId.value = null
                }
            }
            if (ownsSession) {
                uiState.value = ConnectionUiState.Idle(null)
                notice.value = null
                unclaimed.value = null
            }
            onDone(null)
        }
    }

    fun photo(vehicleId: String, maxEdgePx: Int, onReady: (Bitmap?) -> Unit) {
        scope.launch { onReady(vehicles.photos.read(vehicleId, maxEdgePx)) }
    }

    fun stagePickedPhoto(uri: Uri, vehicleId: String, onStaged: (File?) -> Unit) {
        scope.launch {
            val staged = vehicles.photos.stageFrom(uri, vehicleId)
            withContext(Dispatchers.Main) { onStaged(staged) }
        }
    }

    fun cameraCapture(vehicleId: String, onCapture: (VehiclePhotoStore.Capture?) -> Unit) {
        scope.launch {
            val capture = vehicles.photos.captureFor(vehicleId)
            withContext(Dispatchers.Main) { onCapture(capture) }
        }
    }

    fun applyPhotoCrop(
        source: File,
        vehicleId: String,
        rect: CropRect,
        onDone: (landed: File?, refusal: String?) -> Unit,
    ) {
        scope.launch {
            val card = vehicles.get(vehicleId) ?: run {
                vehicles.photos.clearCapture(vehicleId)
                onDone(null, null)
                return@launch
            }
            val landed = vehicles.photos.cropFrom(source, vehicleId, rect)
            if (landed == null) {
                onDone(null, null)
                return@launch
            }
            if (!vehicles.put(card.copy(photoFile = landed.name))) {
                photoVersion.intValue++
                onDone(landed, vehicles.saveFailure("Photo not attached"))
                return@launch
            }
            photoVersion.intValue++
            onDone(landed, null)
        }
    }

    /** The user decided against the picture they framed: the scratch capture goes. */
    fun cancelPhoto(vehicleId: String) {
        scope.launch { vehicles.photos.clearCapture(vehicleId) }
    }

    fun removePhoto(vehicleId: String, onRefused: (String) -> Unit = {}) {
        scope.launch {
            vehicles.photos.delete(vehicleId)
            vehicles.get(vehicleId)?.let { card ->
                if (card.photoFile != null && !vehicles.put(card.copy(photoFile = null))) {
                    onRefused(vehicles.saveFailure("Photo not removed"))
                }
            }
            photoVersion.intValue++
        }
    }

    private fun logPersistMiss(vehicleId: String, from: String) {
        Log.w(DPP_CONN_LOG_TAG, "garage write refused ($from) vehicle=$vehicleId — " +
            "identity/version fields stay in memory only")
    }

    /** The connected card's DISCONNECT. The selection stays; the unit goes. */
    fun disconnect() {
        ladder?.cancel()
        retry?.cancel()
        rearm?.cancel()
        gaveUp = false
        attempts = 0
        link.disconnect()
        if (uiState.value !is ConnectionUiState.Connected) return
        uiState.value = ConnectionUiState.Idle(null)
    }

    // ------------------------------------------------------------- the attempt loop

    private fun startAttempt(
        mode: ConnectMode,
        pinned: Found? = null,
        preScanned: List<Found>? = null,
    ) {
        Log.d(DPP_CONN_LOG_TAG, "attempt start mode=$mode pinned=${pinned?.address ?: "-"} attempts=$attempts")
        ladder?.cancel()
        ladder = scope.launch { runAttempt(mode, pinned, preScanned) }
    }

    private suspend fun runAttempt(
        mode: ConnectMode,
        pinned: Found? = null,
        preScanned: List<Found>? = null,
    ) {
        unclaimed.value = null
        scanError.value = null
        if (preScanned == null) scanResults.value = emptyList()
        var tried = emptyList<String>()
        var scanDone = preScanned != null
        var found = preScanned ?: emptyList()
        var outcome: LinkOutcome? = null
        val me = currentCoroutineContext().job
        while (currentCoroutineContext().isActive) {
            val r = ConnectionCore.decide(
                vehicles.all().first(), selectedVehicleId.value,
                tried, scanDone, found, outcome, mode, now(), pinned,
            )
            r.persist?.let { if (!vehicles.put(it)) logPersistMiss(it.id, "ladder") }
            unclaimed.value = r.adoption
            r.notice?.let { notice.value = it }
            when (val n = r.next) {
                is NextAction.Idle -> {
                    uiState.value = ConnectionUiState.Idle(n.reason)
                    return
                }
                is NextAction.Failed -> {
                    uiState.value = ConnectionUiState.Failed(n.reason)
                    if (mode == ConnectMode.SEARCH && link.state.value != BleLinkState.Idle) {
                        link.disconnect()
                    }
                    gaveUp = true
                    scheduleRearm(except = me)
                    Log.d(DPP_CONN_LOG_TAG, "attempt ended reason=${n.reason} mode=$mode, handing the failure to the slow tick")
                    return
                }
                is NextAction.Connect -> {
                    applyConnect(n)
                    return
                }
                is NextAction.Scan -> {
                    uiState.value = ConnectionUiState.Connecting(mode, AttemptStage.SCAN)
                    scanDone = true
                    found = sweep()
                    outcome = null
                }
                is NextAction.Pair -> {
                    tried = tried + n.address
                    outcome = dial(n, mode)
                }
            }
        }
    }

    private suspend fun dial(step: NextAction.Pair, mode: ConnectMode): LinkOutcome {
        uiState.value = ConnectionUiState.Connecting(mode, AttemptStage.DIAL)
        val mirror = scope.launch {
            link.state.collect { s ->
                val stage = when (s) {
                    is BleLinkState.Pairing -> AttemptStage.PAIR
                    is BleLinkState.Connecting -> AttemptStage.DIAL
                    else -> null
                }
                if (stage != null) {
                    uiState.value = ConnectionUiState.Connecting(mode, stage)
                }
            }
        }
        val up = try {
            link.connect(step.address, step.name)
        } finally {
            mirror.cancel()
        }
        if (!up) return LinkOutcome.Fail(step.address)

        val ready = link.state.value as? BleLinkState.Ready
        val info = readIdentity()
        return LinkOutcome.Ok(
            vehicleId = step.vehicleId,
            address = ready?.address ?: step.address,
            name = ready?.name ?: step.name,
            version = info.version,
            mac = info.mac,
            mtu = ready?.mtu ?: 0,
        )
    }

    private suspend fun sweep(): List<Found> {
        if (!link.available()) {
            scanError.value = scanFailureText("no_bluetooth")
            return emptyList()
        }
        if (!link.enabled()) {
            scanError.value = scanFailureText("bluetooth_off")
            adapterOn.value = false
            return emptyList()
        }
        val missing = missingBlePermissions()
        if (missing.isNotEmpty()) {
            permissionMissing.value = true
            scanError.value = scanFailureText("permission")
            return emptyList()
        }
        val units = mutableListOf<Found>()
        scanning.value = true
        try {
            withTimeoutOrNull(BLE_SCAN_MS) {
                try {
                    link.scan().collect { list ->
                        units.clear()
                        list.forEach { units.add(Found(it.address, it.name, it.rssi)) }
                        scanResults.value = units.toList()
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    scanError.value = scanFailureText(e.message)
                }
            }
        } finally {
            scanning.value = false
        }
        return units
    }

    /** The permissions this Android version needs from the radio, of those not granted. */
    fun missingBlePermissions(): List<String> {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun blePermissionRequest(): List<String> = missingBlePermissions()

    /** The launcher's answer, so the page stops warning about a grant it now has. */
    fun notePermissionsResult(granted: Map<String, Boolean>) {
        permissionMissing.value = missingBlePermissions().isNotEmpty()
        if (!permissionMissing.value) scanError.value = null
    }

    /** The unit's own eFuse MAC and firmware version, read over the link that is up. */
    private suspend fun readIdentity(): UnitInfo {
        val snap = link.request("snapshot")
        var mac: String? = null
        var version: String? = null
        if (snap.ok) {
            mac = BleJson.string(snap.fields, "mac")?.takeIf { it.isNotEmpty() }
            version = BleJson.string(snap.fields, "ver")
        }
        if (mac == null) {
            val perf = link.request("perf")
            if (perf.ok) mac = BleJson.string(perf.fields, "mac")?.takeIf { it.isNotEmpty() }
        }
        return UnitInfo(mac = mac, version = version)
    }

    /** What one link-up read knows about the unit on the other end. */
    private data class UnitInfo(val mac: String?, val version: String?)

    private suspend fun applyConnect(n: NextAction.Connect) {
        if (!currentCoroutineContext().isActive) return
        if (n.vehicleId != selectedVehicleId.value) {
            selectedVehicleId.value = n.vehicleId
            scope.launch { vehicles.select(n.vehicleId) }
        }
        Log.d(DPP_CONN_LOG_TAG, "linked name=${n.name} address=${n.address} vehicle=${n.vehicleId} mtu=${n.mtu} rearmsSpent=$rearms")
        attempts = 0
        rearms = 0
        gaveUp = false
        rearm?.cancel()
        uiState.value = ConnectionUiState.Connected(
            address = n.address,
            name = n.name,
            version = n.version,
            mac = n.mac,
            vehicleId = n.vehicleId,
            mtu = n.mtu,
        )
    }

    // ------------------------------------------------------------- the radio's events

    private fun watchAdapter() {
        if (adapterReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR,
                )
                val on = state == BluetoothAdapter.STATE_ON
                adapterOn.value = on
                scope.launch { onLinkEvent(if (on) LinkEvent.AdapterOn else LinkEvent.AdapterOff) }
            }
        }
        adapterReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun stopAdapterWatch() {
        val r = adapterReceiver ?: return
        adapterReceiver = null
        try {
            appContext.unregisterReceiver(r)
        } catch (e: Exception) {
            // Already gone: the system drops receivers with the process anyway.
        }
    }

    private fun watchLink() {
        linkWatch?.cancel()
        linkWatch = scope.launch {
            link.state.collect { s ->
                adapterOn.value = link.enabled()
                if (s !is BleLinkState.Ready) {
                    clockPushedFor = null
                    weatherWokenFor = null
                }
                when (s) {
                    is BleLinkState.Ready -> {
                        pushClock(s)
                        wakeWeather(s)
                        if (ladder?.isActive != true) adoptLiveLink(s)
                    }
                    is BleLinkState.Failed -> {
                        if (ladder?.isActive != true) {
                            onLinkEvent(LinkEvent.Dropped(s.reason))
                        } else {
                            Log.d(DPP_CONN_LOG_TAG, "link failed reason=${s.reason} while an attempt runs; that attempt owns it")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun pushClock(ready: BleLinkState.Ready) {
        if (clockPushedFor == ready.address) return
        clockPushedFor = ready.address
        scope.launch {
            val reply = api.pushTime()
            Log.d(
                DPP_CONN_LOG_TAG,
                "clock pushed ${if (reply.ok) "ok" else "failed err=${reply.error}"} " +
                    "address=${ready.address}",
            )
            timeNote.value = if (reply.ok) {
                null
            } else {
                "This phone could not set the unit's clock. Trip files are stamped by the " +
                    "phone, so they are unaffected; the dashboard's own stamps may be wrong."
            }
        }
    }

    private fun wakeWeather(ready: BleLinkState.Ready) {
        if (weatherWokenFor != ready.address) {
            weatherWokenFor = ready.address
            val address = ready.address
            scope.launch {
                WeatherRuntime.coordinator(appContext).unitWoke(weatherKeyFor(address))
            }
        }
    }

    private fun startWeatherTick() {
        if (weatherTick?.isActive == true) return
        weatherTick = scope.launch {
            while (isActive) {
                delay(WEATHER_TICK_MS)
                val key = feedUnitKey() ?: continue
                WeatherRuntime.coordinator(appContext).wake("tick", key)
            }
        }
    }

    private suspend fun feedUnitKey(): String? {
        (uiState.value as? ConnectionUiState.Connected)?.let {
            return UnitIdentity.key(it.mac, it.address)
        }
        val selected = vehicles.all().first().firstOrNull { it.id == selectedVehicleId.value }
            ?: return null
        // Neither a MAC nor a pairing means no identity to file a forecast under.
        return UnitIdentity.key(selected.mac, selected.bleAddress)
            .takeIf { it != UnitIdentity.UNKNOWN }
    }

    private suspend fun weatherKeyFor(address: String): String =
        UnitIdentity.key(
            vehicles.all().first().firstOrNull { it.bleAddress == address }?.mac,
            address,
        )

    private suspend fun adoptLiveLink(ready: BleLinkState.Ready) {
        val current = uiState.value as? ConnectionUiState.Connected
        if (current != null && current.address == ready.address) {
            attempts = 0
            return
        }
        val info = readIdentity()
        val r = ConnectionCore.decide(
            vehicles.all().first(), selectedVehicleId.value,
            tried = listOf(ready.address), scanDone = true, found = emptyList(),
            outcome = LinkOutcome.Ok(
                vehicleId = null, address = ready.address, name = ready.name,
                version = info.version, mac = info.mac, mtu = ready.mtu,
            ),
            mode = ConnectMode.SILENT, nowMs = now(),
        )
        r.persist?.let { if (!vehicles.put(it)) logPersistMiss(it.id, "silent identity read") }
        unclaimed.value = r.adoption
        r.notice?.let { notice.value = it }
        when (val n = r.next) {
            is NextAction.Connect -> applyConnect(n)
            is NextAction.Failed -> uiState.value = ConnectionUiState.Failed(n.reason)
            is NextAction.Idle -> uiState.value = ConnectionUiState.Idle(n.reason)
            else -> Unit // unreachable: an answer is in hand, so nothing is left to try
        }
    }

    /** One radio event, one ladder decision, at most one scheduled dial. */
    private suspend fun onLinkEvent(event: LinkEvent) {
        val card = selectedCard()
        val step = ReconnectLadder.step(
            event, attempts, card?.hasPairing == true, link.enabled(),
        )
        Log.d(DPP_CONN_LOG_TAG, "event ${event::class.simpleName} attempts=$attempts pairing=${card?.hasPairing == true} radio=${link.enabled()} -> $step")
        when (step) {
            LadderStep.Linked -> {
                attempts = 0
                rearms = 0
                gaveUp = false
                rearm?.cancel()
                retry?.cancel()
            }
            LadderStep.Stop -> Unit
            is LadderStep.GiveUp -> {
                attempts = 0
                retry?.cancel()
                uiState.value = ConnectionUiState.Failed(step.reason)
                gaveUp = true
                scheduleRearm()
            }
            is LadderStep.Retry -> {
                val now = link.state.value
                if (now is BleLinkState.Ready || now is BleLinkState.Connecting ||
                    now is BleLinkState.Pairing
                ) {
                    return
                }
                attempts++
                retry?.cancel()
                gaveUp = false
                rearm?.cancel()
                retry = scope.launch {
                    delay(step.delayMs)
                    if (currentCoroutineContext().isActive) {
                        startAttempt(ConnectMode.PROBE)
                    }
                }
            }
        }
    }

    /** The selected card, read from the store. Null when nothing is selected. */
    private suspend fun selectedCard(): Vehicle? {
        val id = selectedVehicleId.value ?: return null
        return try {
            vehicles.all().first().firstOrNull { it.id == id }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- the quiet restart

    private suspend fun rearmWantedNow(except: Job? = null): Boolean {
        val card = selectedCard()
        val s = link.state.value
        return ReconnectLadder.rearmWanted(
            foreground = foreground,
            hasPairing = card?.hasPairing == true,
            adapterOn = link.enabled(),
            linkBusy = s is BleLinkState.Ready || s is BleLinkState.Connecting ||
                s is BleLinkState.Pairing,
            dialInFlight = ladder?.isActive == true && ladder !== except,
        )
    }

    private suspend fun scheduleRearm(except: Job? = null) {
        if (!rearmWantedNow(except)) {
            Log.d(DPP_CONN_LOG_TAG, "rearm not scheduled: guards closed (foreground=$foreground pairing=${selectedCard()?.hasPairing == true} radio=${link.enabled()} state=${link.state.value::class.simpleName} dialInFlight=${ladder?.isActive == true})")
            return
        }
        val waitMs = ReconnectLadder.rearmDelayMs(rearms)
        rearm?.cancel()
        Log.d(DPP_CONN_LOG_TAG, "rearm scheduled in ${waitMs} ms rearmsSoFar=$rearms")
        rearm = scope.launch {
            delay(waitMs)
            if (currentCoroutineContext().isActive && rearmWantedNow()) dialForRearm()
        }
    }

    private fun dialForRearm() {
        rearms++
        attempts = 0
        gaveUp = true
        Log.d(DPP_CONN_LOG_TAG, "rearm dial rearms=$rearms mode=PROBE")
        startAttempt(ConnectMode.PROBE)
    }

    fun dispose() {
        stopAdapterWatch()
        ladder?.cancel()
        retry?.cancel()
        rearm?.cancel()
        linkWatch?.cancel()
        weatherTick?.cancel()
        job.cancel()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** How long one radio sweep runs; the link panel used the same window. */
        const val BLE_SCAN_MS = 15_000L

        /** How often this host wakes the weather feed while a link is up. */
        const val WEATHER_TICK_MS = 60_000L
    }
}
