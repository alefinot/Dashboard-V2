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
import com.alefinot.dashboardpp.trips.CsvTripWriter
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.TelemetryFeed
import com.alefinot.dashboardpp.trips.TripEvent
import com.alefinot.dashboardpp.trips.TripPolicy
import com.alefinot.dashboardpp.trips.TripRecorder
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.trips.TripStore
import com.alefinot.dashboardpp.weather.HostFrames
import com.alefinot.dashboardpp.weather.WeatherRuntime
import com.alefinot.dashboardpp.trips.TripSummary
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

class TripLogService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var feedJob: Job? = null
    private var recorder: TripRecorder? = null
    private var writer: CsvTripWriter? = null
    private var store: TripStore? = null
    private var settings: RecorderSettings? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceKey: String = ""

    /** When the weather feed was last woken from this service's frame stream. */
    private var lastWeatherWakeMs = 0L

    private var vehicleId: String = ""
    private var vehicleName: String = ""
    private var eff: String = ""
    private var telemetryInNotification = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        val key = intent?.getStringExtra(EXTRA_DEVICE_KEY)
        if (key.isNullOrEmpty()) {
            shutdown()
            return START_NOT_STICKY
        }
        deviceKey = key
        vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID).orEmpty()
        vehicleName = intent.getStringExtra(EXTRA_VEHICLE_NAME).orEmpty()
        eff = intent.getStringExtra(EXTRA_EFF).orEmpty()
        recorder?.bindVehicle(vehicleId, eff, vehicleName)
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

    fun shutdown() {
        feedJob?.cancel()
        feedJob = null
        recorder?.stop()
        recorder = null
        writer?.closeAll()
        writer = null
        releaseWake()
        TripRuntime.update { it.copy(polling = false, recording = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun beginFeeding() {
        if (feedJob?.isActive == true) return
        feedJob = scope.launch {
            if (!awaitRecorder()) return@launch
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
                        TripRuntime.update { it.copy(recording = false, tripPath = null, summary = null) }
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

    private suspend fun nextFrame(link: BleLink): Snapshot? = Snapshot.parse(link.telemetry.first())

    /** True when the device has been unreachable long enough to stop the service. */
    private fun shouldGiveUp(): Boolean {
        val state = TripRuntime.state.value
        if (state.recording) return false
        if (state.lastOkMs == 0L) return false
        return System.currentTimeMillis() - state.lastOkMs > TripPolicy.IDLE_RETRY_MS
    }

    /** One accepted sample: append it, publish it, show it. */
    private fun handleSnapshot(s: Snapshot) {
        noteWeatherFrame(s)
        val rec = recorder ?: return
        val event = rec.onSnapshot(deviceKey, s)
        val summary = rec.summary()
        val now = System.currentTimeMillis()
        TripRuntime.update {
            it.copy(
                snapshot = s,
                summary = summary,
                lastOkMs = now,
                recording = rec.isRecording,
                tripPath = rec.openTrip?.path,
                tripStartMs = rec.openTrip?.startMs ?: 0L,
                lastError = null,
            )
        }
        applyWake(rec.isRecording)
        notify(s, summary)
        when (event) {
            TripEvent.OPENED, TripEvent.REOPENED, TripEvent.SEALED_THEN_OPENED -> persistSession()
            TripEvent.APPENDED -> if (summary != null && summary.samples % 30 == 0) persistSession()
            else -> Unit
        }
    }

    private fun noteWeatherFrame(s: Snapshot) {
        val key = deviceKey
        if (key.isEmpty()) return
        HostFrames.note(key, s)
        val now = System.currentTimeMillis()
        if (now - lastWeatherWakeMs < WEATHER_WAKE_INTERVAL_MS) return
        lastWeatherWakeMs = now
        scope.launch { WeatherRuntime.coordinator(applicationContext).wake("recording", key) }
    }

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
        if (!wanted) {
            TripRuntime.update { it.copy(lastError = "disabled-by-user") }
            shutdown()
            return false
        }
        val prior = try {
            appSettings.sessionFor(deviceKey)
        } catch (e: Exception) {
            null
        }
        recorder = TripRecorder(csvWriter, { startMs -> tripStore.newName(startMs) }, {
            System.currentTimeMillis()
        }, prior)
        recorder?.bindVehicle(vehicleId, eff, vehicleName)
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
        val title = when {
            recording && summary != null ->
                "Recording · ${formatDuration(summary.durationMs)}" +
                    " · ${format1(summary.distanceKm)} km"
            recording -> "Recording this drive"
            else -> "Waiting for the dashboard"
        }
        val text = when {
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
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dashboard)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Stop recording", stopPending)
            .setVisibility(
                if (telemetryInNotification) {
                    NotificationCompat.VISIBILITY_PRIVATE
                } else {
                    // The toggle exists for exactly this: numbers off the lock screen.
                    NotificationCompat.VISIBILITY_SECRET
                },
            )
            .build()
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
        const val EXTRA_DEVICE_KEY = "device_key"
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_LIVE = "live"

        /** The card whose drives these are, and the MAC the dashboard reported. */
        const val EXTRA_VEHICLE_ID = "vehicle_id"
        const val EXTRA_VEHICLE_NAME = "vehicle_name"
        const val EXTRA_EFF = "eff"

        private const val CHANNEL = "trip_recording"
        private const val NOTIF_ID = 4201

        /** How often the frame stream may wake the weather feed (see [noteWeatherFrame]). */
        private const val WEATHER_WAKE_INTERVAL_MS = 10_000L
        private const val WAKE_TAG = "dashboardpp:trip"
        fun start(
            context: Context,
            deviceKey: String,
            vehicleId: String,
            vehicleName: String,
            eff: String,
        ) {
            val intent = Intent(context, TripLogService::class.java).apply {
                putExtra(EXTRA_DEVICE_KEY, deviceKey)
                putExtra(EXTRA_VEHICLE_ID, vehicleId)
                putExtra(EXTRA_VEHICLE_NAME, vehicleName)
                putExtra(EXTRA_EFF, eff)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripLogService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Already stopped.
            }
        }
    }
}
