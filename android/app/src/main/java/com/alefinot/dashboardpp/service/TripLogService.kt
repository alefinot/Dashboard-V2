package com.alefinot.dashboardpp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alefinot.dashboardpp.MainActivity
import com.alefinot.dashboardpp.R
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.trips.AccelRunTracker
import com.alefinot.dashboardpp.trips.CsvTripWriter
import com.alefinot.dashboardpp.trips.CursorTrail
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.TelemetryFeed
import com.alefinot.dashboardpp.trips.TripEvent
import com.alefinot.dashboardpp.trips.TripPolicy
import com.alefinot.dashboardpp.trips.TripRecorder
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.trips.TripStore
import com.alefinot.dashboardpp.trips.TrackQuality
import com.alefinot.dashboardpp.weather.HostFrames
import com.alefinot.dashboardpp.weather.WeatherRuntime
import com.alefinot.dashboardpp.trips.TripSummary
import com.alefinot.dashboardpp.trips.routeToShow
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The recording lane (trip recording plan §5): a foreground service that owns one
 * 1 Hz stream from the dashboard, appends each sample to the open trip file and
 * keeps the session state machine alive while the app is in the background.
 *
 * It is a foreground service with an ongoing notification because that is the
 * only shape Android permits for work that must continue while the phone is
 * driving; the notification also says what is happening, which is the honest trade for
 * a partial wake lock.
 *
 * What changed when this became a Bluetooth app: the service used to *ask* once a second
 * (`/api/snapshot`), because HTTP offers no other way to be told anything. The unit now
 * pushes on its own characteristic and this class mostly waits, which retires the
 * one-lane negotiation this file used to carry. There was a second reader of that
 * endpoint - the ESP's own Web UI, whose tab could make the service stand down - and
 * there is no second reader now: the settings page reads
 * configuration and the performance monitor reads diagnostics, and neither samples
 * speed. What a frame is worth is decided in [TelemetryFeed]: a re-sent frame does not
 * become a sample, and a link that goes quiet gets prodded once instead of polled.
 */
class TripLogService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var feedJob: Job? = null
    private var recorder: TripRecorder? = null

    /**
     * Where the vehicle is, as far as this lane has heard: every position the stream
     * reported, written to a file or not (2.0.7/179). The live arrow rides it, so it belongs
     * to the frame lane rather than to a recorder - a pause must not freeze the arrow, and a
     * stop must not take the car off the map.
     */
    private val trail = CursorTrail()

    /**
     * The 0-50 runs this lane has heard the unit complete, and the drive they belong to.
     *
     * Read off the frame stream rather than out of the recorder (see
     * [com.alefinot.dashboardpp.trips.AccelRunTracker]), so a launch made before anybody pressed
     * START is still on the map. It is dropped with the drive it belongs to — the same rule the
     * route follows, so a map never wears the results of a drive it is not drawing.
     */
    private val accelRuns = AccelRunTracker()

    /**
     * The drive [accelRuns] were collected for, as [com.alefinot.dashboardpp.trips.LiveRoute.driveId]
     * spells it, and the unit they were collected from. A change in either is a new picture.
     */
    private var runDriveId = 0L
    private var runUnitKey: String? = null

    /**
     * True while this lane follows the stream for the display alone: no recorder, no file, no
     * wake lock, no foreground notification. See [TripRuntimeState.watching].
     */
    private var watching = false

    private var writer: CsvTripWriter? = null
    private var store: TripStore? = null
    private var settings: RecorderSettings? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /**
     * Which dashboard the files belong to, as `UnitIdentity.key` spells it - handed over
     * on the start command by the view model, the only place that knows both the card and
     * the MAC the unit reported. Deliberately not read off the link: a trip spans a
     * re-pairing and a reboot, and the point of a device key is that it stays put while
     * the link under it changes.
     */
    private var deviceKey: String = ""

    /** When the weather feed was last woken from this service's frame stream. */
    private var lastWeatherWakeMs = 0L

    /**
     * Whose drives the files are: the card selected when this service was pointed at
     * [ip], and the MAC that dashboard reported when the app verified it. The
     * snapshot endpoint carries no identity of its own, so the recorder can only be
     * told [plan §6]; the values arrive as extras on the start command.
     *
     * They are a point-in-time copy on purpose: "the name at record time" is what a
     * trip's tag should hold, and renaming a vehicle later must not relabel drives
     * that already happened.
     */
    private var vehicleId: String = ""
    private var vehicleName: String = ""
    private var eff: String = ""
    private var telemetryInNotification = true

    /**
     * A pause asked for by the owner. Kept here as well as in the recorder because the
     * service command can land before [awaitRecorder] has built the recorder; the flag
     * is applied to the recorder the moment it exists.
     */
    private var pausedByUser = false

    /**
     * True when this start command came from the pages' START control rather than from
     * the shell's auto-start lane. A manual start begins a new trip file: the user is
     * starting a recording, not resuming a session the previous run let go of.
     */
    private var manualStart = false

    /**
     * The App Settings → SYNC TRIP WITH DASHBOARD switch, mirrored here so a flip reaches
     * the recorder's next sample without the service being rebuilt. Default on, matching
     * the store's default.
     */
    @Volatile
    private var tripSyncEnabled = true

    /** The collector that keeps [tripSyncEnabled] live for this service's lifetime. */
    private var syncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WATCH -> {
                // The display-only reader: the same frame lane with no recorder behind it.
                // Since 2.0.7/179 it keeps a finished drive's live map moving; since the
                // Live page became a map it is also what makes the page live before anybody
                // presses START - the app opens, a dashboard is linked, and the map, the HUD
                // and the traces follow the unit's 1 Hz stream with no file on disk. It
                // deliberately does NOT go foreground: "while the app is open" is the whole
                // promise, a plain started service lives exactly that long, and an ongoing
                // notification for a drive that is not being written would be a permanent
                // alarm about nothing. MainActivity puts this down on the way to the
                // background, which is where the picture starts freezing on purpose.
                if (recorder != null || feedJob?.isActive == true) return START_STICKY
                val key = intent.getStringExtra(EXTRA_DEVICE_KEY)
                    ?.takeIf { it.isNotEmpty() }
                    ?: TripRuntime.state.value.deviceKey
                if (key.isNullOrEmpty()) {
                    // No link to follow. The shell starts this lane from the owner it just
                    // resolved, so an empty key means the link went away in between.
                    stopSelf()
                    return START_NOT_STICKY
                }
                deviceKey = key
                watching = true
                TripRuntime.update { it.copy(watching = true, deviceKey = key) }
                beginFeeding()
                return START_STICKY
            }
            ACTION_STOP -> {
                // Ending a drive says nothing about auto-start (owner, 2026-11-18): the switch
                // means "record when a dashboard connects", which is a decision about the *next*
                // ride, while STOP answers "end this one". Every stop - pill, card or shade - is
                // therefore just a stop, and the switch keeps whatever the owner left it.
                val keep = intent.getBooleanExtra(EXTRA_KEEP_DISPLAY, true)
                shutdown(keepDisplay = keep)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (recorder == null && feedJob?.isActive != true) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                pausedByUser = true
                recorder?.pause()
                publishPaused(true)
                applyWake(false)
                refreshNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (recorder == null && feedJob?.isActive != true) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                pausedByUser = false
                recorder?.resume()
                publishPaused(false)
                applyWake(recorder?.isRecording == true)
                refreshNotification()
                return START_STICKY
            }
        }
        val key = intent?.getStringExtra(EXTRA_DEVICE_KEY)
        if (key.isNullOrEmpty()) {
            shutdown()
            return START_NOT_STICKY
        }
        deviceKey = key
        manualStart = intent.getBooleanExtra(EXTRA_MANUAL, false)
        vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID).orEmpty()
        vehicleName = intent.getStringExtra(EXTRA_VEHICLE_NAME).orEmpty()
        eff = intent.getStringExtra(EXTRA_EFF).orEmpty()
        // The owner's START does NOT blank the maps (owner, 2026-11-18: "when the recording is
        // stopped and you press to start it the live map disappears for a moment"). Clearing the
        // finished drive's shape here left the page with nothing to draw for a whole sample
        // period; the shape now leaves when the drive that replaces it has a position of its own
        // - see [routeToShow], which is what makes that handover atomic. The trail does go,
        // straight away: it is the arrow's history, and a new drive's arrow has none until its
        // first fix - pointing it back down the old drive's road would be the first thing the
        // new picture got wrong.
        if (manualStart && recorder?.isRecording != true) {
            TripRuntime.update { it.copy(liveTrail = emptyList()) }
            trail.clear()
        }
        // A lane that was following the stream for the display (no recorder) has to be handed
        // to a recorder now: [beginFeeding] keeps an already-running lane as it is, so a START
        // that landed on a watching service would announce itself in the shade and then write
        // nothing at all.
        if (recorder == null && feedJob?.isActive == true) {
            feedJob?.cancel()
            feedJob = null
        }
        watching = false
        // Published, not just remembered: `watching` is one of the five facts the pages read to
        // decide what a press means, and a stale true here would make the window before the
        // first sample offer START again instead of the STOP that backs out of it.
        TripRuntime.update { it.copy(watching = false) }
        // A recorder already running from an earlier command keeps the file it is
        // inside - re-pointing only changes the tag of the next file it creates.
        recorder?.bindVehicle(vehicleId, eff, vehicleName)
        // A foreground service must announce itself immediately after
        // startForegroundService(); without this the process is ANR'd in ~5 s.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        beginFeeding()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /**
     * Tear the lane down: seal, publish, stop.
     *
     * [keepDisplay] is the owner's Stop, and it means "the drive is over, the picture is not":
     * with the app on screen, the same lane is asked back with no recorder behind it so the map
     * keeps living (2.0.7/179). It is asked only of a foreground app, because the lane that
     * follows the stream for the display is put down by
     * [com.alefinot.dashboardpp.MainActivity.onStop] - a Stop pressed from the shade with the
     * app behind it has nobody looking at a map, and a lane nobody puts down would stream for
     * the rest of the day.
     */
    fun shutdown(keepDisplay: Boolean = false) {
        feedJob?.cancel()
        feedJob = null
        syncJob?.cancel()
        syncJob = null
        pausedByUser = false
        val finishing = recorder
        finishing?.stop()
        // Read the route after the stop: [TripRecorder.stop] is what turns a paused drive's
        // orange dot into the red END dot, and the shape stays on the maps after the service
        // is gone (2.0.7/179) - it is replaced by the next START, not by a service ending.
        val keptRoute = finishing?.liveRoute()
        recorder = null
        writer?.closeAll()
        writer = null
        releaseWake()
        val route = keptRoute ?: TripRuntime.state.value.liveRoute
        TripRuntime.update {
            it.copy(
                polling = false, recording = false, paused = false, pausedAtMs = 0L,
                watching = false, liveRoute = routeToShow(it.liveRoute, route),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // With the app on screen a Stop leaves the display lane running - a finished drive's
        // map is still worth watching, and since the Live page became a map, so is a drive
        // that never started. Only the foreground window changes that: a lane nobody puts
        // down would stream for the rest of the day.
        if (keepDisplay && appForeground) watch(applicationContext)
    }

    /**
     * Publish the pause to the pages and the notification. [pausedAtMs] is taken once,
     * at the press, so the trip clock freezes where the owner stopped it.
     */
    private fun publishPaused(paused: Boolean) {
        val route = recorder?.liveRoute()
        TripRuntime.update {
            it.copy(
                paused = paused,
                pausedAtMs = when {
                    !paused -> 0L
                    it.paused && it.pausedAtMs > 0L -> it.pausedAtMs
                    else -> System.currentTimeMillis()
                },
                liveRoute = routeToShow(it.liveRoute, route ?: it.liveRoute),
            )
        }
    }

    private fun refreshNotification() {
        notify(recorder?.lastSnapshot, recorder?.summary())
    }

    /**
     * The stream lane: wait for the unit's frames, and ask only when they stop coming.
     *
     * One accepted frame per pass, which is also one tick of the trip session machine —
     * the frame rate and the tick rate were the same number when both were a poll, and
     * they are still the same number now that only one of them is. Waiting with a
     * timeout instead of sleeping is what makes the quiet case interesting: a link that
     * reports `Ready` and sends nothing for [TelemetryFeed.SILENT_MS] gets exactly one
     * `snapshot` request, which tells "the subscription dropped" apart from "the unit is
     * rebooting" without a second mechanism. When that answer is absent too, the stale
     * counter and the give-up back-off say so plainly.
     */
    private fun beginFeeding() {
        if (feedJob?.isActive == true) return
        feedJob = scope.launch {
            if (!watching && !awaitRecorder()) return@launch
            val link = BleRuntime.link(this@TripLogService)
            val api = BleApi(link)
            var lastUp = -1L
            while (isActive) {
                TripRuntime.update { it.copy(deviceKey = deviceKey, polling = true) }
                val frame = withTimeoutOrNull(TelemetryFeed.SILENT_MS) { nextFrame(link) }
                if (frame != null && TelemetryFeed.accepts(lastUp, frame.up)) {
                    lastUp = frame.up
                    handleSnapshot(frame)
                } else if (frame == null) {
                    val now = System.currentTimeMillis()
                    val ready = link.state.value is BleLinkState.Ready
                    val lastOk = TripRuntime.state.value.lastOkMs
                    if (ready && TelemetryFeed.dueForPoll(lastOk, now, false)) {
                        // The prod is also the answer to "is this link still the unit":
                        // a request that times out here is the failure the old loop used
                        // to meet on its next poll, found five seconds sooner.
                        val merged = TelemetryFeed.mergeProd(lastUp, api.snapshot())
                        if (merged != null) {
                            lastUp = merged.up
                            handleSnapshot(merged)
                        } else {
                            TripRuntime.update { it.copy(lastError = "no-answer") }
                        }
                    } else {
                        TripRuntime.update { it.copy(lastError = "no-answer") }
                    }
                }
                recorder?.onTick()?.let { ev ->
                    if (ev == TripEvent.SEALED) {
                        val route = recorder?.liveRoute()
                        TripRuntime.update {
                            it.copy(
                                recording = false, tripPath = null, summary = null,
                                liveRoute = routeToShow(it.liveRoute, route ?: it.liveRoute),
                            )
                        }
                        persistSession()
                    }
                }
                if (shouldGiveUp()) {
                    shutdown()
                    break
                }
            }
        }
    }

    /**
     * The next pushed frame, or null when it cannot be read as a snapshot.
     *
     * A frame that will not parse is not retried: the unit sends another one a second
     * later, and a frame that mattered would show up as the stale counter, which is the
     * signal the user already has.
     */
    private suspend fun nextFrame(link: BleLink): Snapshot? = Snapshot.parse(link.telemetry.first())

    /** True when the device has been unreachable long enough to stop the service. */
    private fun shouldGiveUp(): Boolean {
        val state = TripRuntime.state.value
        if (state.recording) return false
        if (state.lastOkMs == 0L) return false
        return System.currentTimeMillis() - state.lastOkMs > TripPolicy.IDLE_RETRY_MS
    }

    /** One accepted sample: publish it, and append it if a recorder is running. */
    private fun handleSnapshot(s: Snapshot) {
        noteWeatherFrame(s)
        if (s.hasPosition) trail.add(s.lat, s.lon)
        val heard = trail.snapshot()
        val now = System.currentTimeMillis()
        // The unit measures a launch whether or not a file is open, so the runs are read here,
        // ahead of the branch, and both lanes publish them: a 0-50 the owner made before pressing
        // START is a run they made, and it belongs on the map.
        if (deviceKey != runUnitKey) {
            // Re-pointed at another dashboard: what is on the maps is another unit's launches.
            accelRuns.clear()
            runUnitKey = deviceKey
            runDriveId = 0L
        }
        accelRuns.onFrame(s, now)
        val rec = recorder
        if (rec == null) {
            // The watching lane (2.0.7/179): the picture moves, nothing is written. The route
            // stays as the finished drive left it - only the vehicle's position advances.
            TripRuntime.update {
                it.copy(
                    snapshot = s,
                    liveTrail = heard,
                    accelRuns = accelRuns.snapshot(),
                    lastOkMs = now,
                    lastError = null,
                )
            }
            return
        }
        val event = rec.onSnapshot(deviceKey, s)
        val summary = rec.summary()
        val route = rec.liveRoute()
        if (route.driveId != runDriveId) {
            // The new drive has a position of its own, which is the moment its picture replaces
            // the last one's ([routeToShow]) - so the last drive's results go out here too.
            accelRuns.clear()
            runDriveId = route.driveId
        }
        val runs = accelRuns.snapshot()
        TripRuntime.update {
            it.copy(
                snapshot = s,
                summary = summary,
                liveRoute = routeToShow(it.liveRoute, route),
                liveTrail = heard,
                accelRuns = runs,
                lastOkMs = now,
                recording = rec.isRecording,
                paused = rec.isPaused,
                pausedAtMs = when {
                    !rec.isPaused -> 0L
                    it.paused && it.pausedAtMs > 0L -> it.pausedAtMs
                    else -> now
                },
                tripPath = rec.openTrip?.path,
                tripStartMs = rec.openTrip?.startMs ?: 0L,
                lastError = null,
            )
        }
        // A paused drive is not logging: holding the CPU awake for it would be a
        // battery cost with no file behind it. Resume re-acquires on the next frame.
        applyWake(rec.isRecording && !rec.isPaused)
        notify(s, summary)
        when (event) {
            TripEvent.OPENED, TripEvent.REOPENED, TripEvent.SEALED_THEN_OPENED,
            TripEvent.SEALED -> persistSession()
            TripEvent.APPENDED -> if (summary != null && summary.samples % 30 == 0) persistSession()
            else -> Unit
        }
    }

    /**
     * Hand this frame to the weather feed, and wake the feed on a bounded cadence.
     *
     * The frame itself is only a map write — the coordinator reads it later as the free
     * answer to "has the car moved 2 km?", which is the question that would otherwise
     * cost a `snapshot` RPC. The wake-up is throttled because the store read it does is
     * not free: at 2 km at 100 km/h that is 72 seconds of driving, so ten seconds is
     * plenty of resolution for the trigger and six store reads a minute instead of sixty.
     */
    private fun noteWeatherFrame(s: Snapshot) {
        val key = deviceKey
        if (key.isEmpty()) return
        HostFrames.note(key, s)
        val now = System.currentTimeMillis()
        if (now - lastWeatherWakeMs < WEATHER_WAKE_INTERVAL_MS) return
        lastWeatherWakeMs = now
        scope.launch { WeatherRuntime.coordinator(applicationContext).wake("recording", key) }
    }

    /**
     * Builds the recorder, or returns false when recording must not run at all.
     * The user's switch is checked here as well as where the service is started:
     * "off" has to mean no polling, no wake lock and no file, even if something
     * else started the service by mistake.
     */
    private suspend fun awaitRecorder(): Boolean {
        if (recorder != null) return true
        val tripStore = TripStore(this)
        store = tripStore
        val csvWriter = CsvTripWriter(tripStore)
        writer = csvWriter
        val appSettings = RecorderSettings(this)
        settings = appSettings
        telemetryInNotification = try {
            appSettings.notificationEnabled.first()
        } catch (e: Exception) {
            true
        }
        val wanted = try {
            appSettings.recordingEnabled.first()
        } catch (e: Exception) {
            true
        }
        // The live line's quality, read once for this recorder's life: the slider takes
        // effect on the next drive, never under a running one's feet.
        val routeQuality = try {
            appSettings.routeQuality.first()
        } catch (e: Exception) {
            TrackQuality.MAX
        }
        // The dashboard trip sync switch: read once here and kept live, because a flip in
        // App Settings must reach the next sample, not the next service.
        tripSyncEnabled = try {
            appSettings.tripSyncEnabled.first()
        } catch (e: Exception) {
            true
        }
        syncJob?.cancel()
        syncJob = scope.launch {
            try {
                appSettings.tripSyncEnabled.collect { tripSyncEnabled = it }
            } catch (e: Exception) {
                // The store died; the last answer stands.
            }
        }
        if (!wanted && !manualStart) {
            TripRuntime.update { it.copy(lastError = "disabled-by-user") }
            shutdown()
            return false
        }
        // Carry the last session across service restarts, so the ESP rebooting
        // while the app was closed still continues the same trip file. A start the
        // owner asked for begins a new drive instead: STOP was pressed on the last one.
        val prior = if (manualStart) null else try {
            appSettings.sessionFor(deviceKey)
        } catch (e: Exception) {
            null
        }
        recorder = TripRecorder(csvWriter, { startMs -> tripStore.newName(startMs) }, {
            System.currentTimeMillis()
        }, prior, routeQuality, { tripSyncEnabled })
        // The identity arrives on the intent, the recorder is built later (this is
        // the first place it can be built): hand it over before the first sample.
        recorder?.bindVehicle(vehicleId, eff, vehicleName)
        // A pause can land before this point (the notification's action is available
        // as soon as the service is); apply it now instead of logging a sample first.
        if (pausedByUser) {
            recorder?.pause()
            publishPaused(true)
        }
        // A manual START is the owner's new trip, so with sync on it begins on both sides:
        // the dashboard's counter goes back to zero and the app's new file starts from it.
        // One attempt, no retry - a lost answer means the counter was not reset, and a retry
        // could zero a counter the new file has already logged. The marker tells the
        // recorder that the drop this causes is not a second, manual reset.
        if (manualStart && tripSyncEnabled) {
            val reply = BleApi(BleRuntime.link(this@TripLogService)).resetTrip()
            if (reply.ok) recorder?.noteOwnTripReset()
        }
        return true
    }

    private fun persistSession() {
        val record = recorder?.sessionRecord() ?: return
        try {
            scope.launch {
                try {
                    settings?.saveSession(record)
                } catch (e: Exception) {
                    // Losing the session note costs the 24 h re-open, never the trip.
                }
            }
        } catch (e: Exception) {
            // scope already cancelled during shutdown
        }
    }

    // ---- notification -------------------------------------------------------

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL, "Trip recording", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while drive data is being logged"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notify(snapshot: Snapshot?, summary: TripSummary?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIF_ID, buildNotification(snapshot, summary))
        } catch (e: Exception) {
            // Notification permission revoked mid-drive; recording continues.
        }
    }

    private fun buildNotification(snapshot: Snapshot?, summary: TripSummary?): Notification {
        val recording = recorder?.isRecording == true
        val paused = recording && recorder?.isPaused == true
        val title = when {
            paused && summary != null ->
                "Paused · ${formatDuration(summary.durationMs)}" +
                    " · ${format1(summary.distanceKm)} km"
            paused -> "Paused · not logging"
            recording && summary != null ->
                "Recording · ${formatDuration(summary.durationMs)}" +
                    " · ${format1(summary.distanceKm)} km"
            recording -> "Recording this drive"
            else -> "Waiting for Dashboard++"
        }
        val text = when {
            paused -> "Not logging samples"
            !telemetryInNotification -> "Logging is on"
            snapshot == null -> "No answer from the device yet"
            else -> "${snapshot.spd.toInt()} km/h · ${format1(snapshot.trpL)} L · " +
                "${format1(snapshot.volts)} V · ${snapshot.sats} sats"
        }
        val open = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_TAB, TAB_LIVE)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, TripLogService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_DEVICE_KEY, deviceKey)
            // The shade's Stop ends this drive and nothing else: it leaves the auto-start
            // switch as the owner left it, exactly like the pill's and the card's.
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dashboard)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(
                if (telemetryInNotification) {
                    NotificationCompat.VISIBILITY_PRIVATE
                } else {
                    // The toggle exists for exactly this: numbers off the lock screen.
                    NotificationCompat.VISIBILITY_SECRET
                },
            )
        when {
            paused -> builder
                .addAction(0, "Resume recording", recordingPending(ACTION_RESUME, 3))
                .addAction(0, "Stop recording", stopPending)
            recording -> builder
                .addAction(0, "Pause recording", recordingPending(ACTION_PAUSE, 2))
                .addAction(0, "Stop recording", stopPending)
            else -> builder.addAction(0, "Stop recording", stopPending)
        }
        return builder.build()
    }

    private fun recordingPending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TripLogService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun formatDuration(ms: Long): String {
        val s = TimeUnit.MILLISECONDS.toSeconds(ms)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun format1(v: Double): String = String.format(Locale.US, "%.1f", v)

    // ---- wake lock ---------------------------------------------------------

    private fun applyWake(recording: Boolean) {
        if (!recording) {
            releaseWake()
            return
        }
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            // Never hold indefinitely: a stuck session cannot drain the battery
            // past the recording cap.
            acquire(TimeUnit.HOURS.toMillis(13))
        }
        wakeLock = lock
    }

    private fun releaseWake() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // already released
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.alefinot.dashboardpp.action.STOP_TRIPS"
        const val ACTION_PAUSE = "com.alefinot.dashboardpp.action.PAUSE_TRIPS"
        const val ACTION_RESUME = "com.alefinot.dashboardpp.action.RESUME_TRIPS"

        /** Follow the stream for the display, with no recorder behind it (see [watch]). */
        const val ACTION_WATCH = "com.alefinot.dashboardpp.action.WATCH_TRIPS"
        const val EXTRA_DEVICE_KEY = "device_key"
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_LIVE = "live"

        /** False means "and take the display-only reader with you" - see [stop]. */
        private const val EXTRA_KEEP_DISPLAY = "keep_display"

        /**
         * A start the owner asked for (the pages' START), not the shell's auto-start
         * lane. It begins a new trip file.
         */
        const val EXTRA_MANUAL = "manual_start"

        /** The card whose drives these are, and the MAC the dashboard reported. */
        const val EXTRA_VEHICLE_ID = "vehicle_id"
        const val EXTRA_VEHICLE_NAME = "vehicle_name"
        const val EXTRA_EFF = "eff"

        private const val CHANNEL = "trip_recording"
        private const val NOTIF_ID = 4201

        /** How often the frame stream may wake the weather feed (see [noteWeatherFrame]). */
        private const val WEATHER_WAKE_INTERVAL_MS = 10_000L
        private const val WAKE_TAG = "dashboardpp:trip"

        /**
         * Whether the app's own window is up - [com.alefinot.dashboardpp.MainActivity]'s
         * onStart and onStop say so. It is the whole life span of a display-only lane: what
         * the finished drive's map promises is "while the app is open", so a Stop pressed from
         * the shade with the app behind it re-arms nothing, and a lane that nobody puts down
         * would stream for the rest of the day. Written on the UI thread, read from the
         * service's IO scope.
         */
        @Volatile
        private var appForeground = false

        /** Tell the recorder's lane that the app came to the front, or went away from it. */
        fun setAppForeground(foreground: Boolean) {
            appForeground = foreground
        }

        /**
         * Point the recorder at a dashboard. No address in the signature anywhere: the
         * link is the process-wide one ([BleRuntime]), and what a trip file needs is the
         * device key, which outlives the link.
         */
        fun start(
            context: Context,
            deviceKey: String,
            vehicleId: String,
            vehicleName: String,
            eff: String,
            manual: Boolean = false,
        ) {
            val intent = Intent(context, TripLogService::class.java).apply {
                putExtra(EXTRA_DEVICE_KEY, deviceKey)
                putExtra(EXTRA_VEHICLE_ID, vehicleId)
                putExtra(EXTRA_VEHICLE_NAME, vehicleName)
                putExtra(EXTRA_EFF, eff)
                putExtra(EXTRA_MANUAL, manual)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Follow the unit's stream for the display, writing nothing, so the live map keeps
         * moving while the app is open (2.0.7/179). Since the Live page became a map this is
         * the page's normal reader: it runs whenever a dashboard is linked and the app is in
         * the foreground, whether a drive was ever started or not. A plain `startService`,
         * not `startForegroundService`: this lane is only meant to live while the app does,
         * so it owes nobody an ongoing notification, and it must never be started from the
         * background.
         *
         * [deviceKey] is the dashboard the caller resolved a moment ago; omitted, the service
         * falls back to the key already published in [TripRuntime].
         */
        fun watch(context: Context, deviceKey: String? = null) {
            val intent = Intent(context, TripLogService::class.java).apply {
                action = ACTION_WATCH
                if (!deviceKey.isNullOrEmpty()) putExtra(EXTRA_DEVICE_KEY, deviceKey)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // No lane to keep alive; the last picture stays on the maps as a still.
            }
        }

        /**
         * End the drive. [keepDisplay] is the difference between an owner pressing STOP while
         * looking at the map and the app being put away: the first leaves a finished drive on
         * the maps and asks the lane back to keep its picture moving, the second is the very
         * moment the picture is allowed to freeze (2.0.7/179).
         */
        fun stop(context: Context, keepDisplay: Boolean = true) {
            val intent = Intent(context, TripLogService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_KEEP_DISPLAY, keepDisplay)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Already stopped.
            }
        }

        /** Pause the open trip; the file stays open and the service keeps following the link. */
        fun pause(context: Context) {
            sendAction(context, ACTION_PAUSE)
        }

        /** Continue appending to the same open trip. */
        fun resume(context: Context) {
            sendAction(context, ACTION_RESUME)
        }

        private fun sendAction(context: Context, action: String) {
            val intent = Intent(context, TripLogService::class.java).apply { this.action = action }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // The service is already gone; there is no open trip to act on.
            }
        }
    }
}
