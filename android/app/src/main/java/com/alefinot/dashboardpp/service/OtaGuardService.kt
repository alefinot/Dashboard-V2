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
import com.alefinot.dashboardpp.MainActivity
import com.alefinot.dashboardpp.R
import com.alefinot.dashboardpp.ble.OtaController
import com.alefinot.dashboardpp.ble.OtaStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a firmware install is running (OTA background-survival
 * plan, 2026-11-13).
 *
 * The reason this service exists is one bench log line:
 * `ActivityManager: Process com.alefinot.dashboardpp (pid 9263) has died: prev LAST`,
 * 62 s after the app went to the background mid-install. A 1.5 MB install takes about a
 * minute of BLE airtime, the person presses START, locks the phone, and on aggressive
 * power managers (MIUI most of all) the process is trimmed while the transfer is at 40 %.
 * The unit then sits in its 120 s silence window with nobody answering and closes the
 * install — the user sees a progress line that froze, which is the "it starts and then
 * stalls" report in full.
 *
 * A started foreground service is the shape Android documents for work that must outlive
 * the screen, and a short partial wake lock keeps the CPU (and with it the BLE stack)
 * awake through a locked screen. The unit's own protocol is already forgiving — a retry
 * inside the window resumes from the settled offset — but resuming needs a living app,
 * and this is the part that keeps it alive.
 *
 * It owns no transfer state. `OtaController` is still the only installer; this service
 * watches the controller's own view and stands down the moment the job is not running —
 * installed, failed, cancelled or paused. RESUME calls `hold()` again. The wake lock
 * carries a timeout as a safety net for the one path that skips `onDestroy` entirely: a
 * killed process releases it by itself, a service somehow left running does not.
 */
class OtaGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // startForegroundService() was used to get here; Android ANRs the process if the
        // transition is not posted within ~5 s, so it happens before anything else.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (intent?.action == ACTION_RELEASE) {
            stopSelf()
            return START_NOT_STICKY
        }
        holdWakeLock()
        watchInstall()
        return START_NOT_STICKY
    }

    /**
     * Self-release. `busy()` is the controller's own answer to "is the transfer job
     * running", so the service cannot outlive the install it guards. The completion state
     * is published while the job is still winding down, so a terminal stage also schedules
     * one late re-check; paused counts as terminal too — RESUME calls `hold()` again.
     */
    private fun watchInstall() {
        watchJob?.cancel()
        watchJob = scope.launch {
            OtaController.view.collect { v ->
                if (!OtaController.busy()) {
                    stopSelf()
                    return@collect
                }
                if (v.stage in TerminalStages) {
                    launch {
                        delay(StopSettleMs)
                        if (!OtaController.busy()) stopSelf()
                    }
                }
            }
        }
    }

    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            // The whole install is a minute plus a verify; the timeout is a runaway
            // guard, not the real stop condition — watchInstall() is the real one.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        wakeLock = lock
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL, "Firmware install", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a firmware image is being installed on the dashboard"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dashboard)
            .setContentTitle("Installing firmware")
            .setContentText("Keep Dashboard++ open until the unit restarts")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "firmware_install"
        private const val NOTIF_ID = 4202
        private const val WAKE_TAG = "DPP:ota-install"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

        /** Stages no running job can leave again except backwards through CLEAR. */
        private val TerminalStages = setOf(
            OtaStage.Idle, OtaStage.Installed, OtaStage.Failed, OtaStage.Paused,
        )

        /** Grace for the job coroutine to wind down before the late re-check asks `busy()`. */
        private const val StopSettleMs = 500L

        private const val ACTION_HOLD = "com.alefinot.dashboardpp.action.OTA_HOLD"
        private const val ACTION_RELEASE = "com.alefinot.dashboardpp.action.OTA_RELEASE"

        /**
         * Arms the guard. Called by `OtaController.start()` — the one door every install
         * and every RESUME goes through — so nothing can start a transfer without the
         * guard knowing.
         */
        fun hold(context: Context) {
            val intent = Intent(context, OtaGuardService::class.java).apply { action = ACTION_HOLD }
            context.startForegroundService(intent)
        }
    }
}
