package com.alefinot.dashboardpp.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.VehicleStore
import com.alefinot.dashboardpp.viewmodel.BackgroundTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings the always-on lane back when the phone does (background link plan §4).
 *
 * This is the one behaviour that makes the app a companion rather than a window: a Xiaomi
 * that rebooted overnight is talking to the dashboard before anybody unlocks it, and the
 * ignition that comes on at 06:40 is recorded without a page being opened. It answers the
 * two events that mean "this install has just started over" - the boot finished, and the
 * app replaced itself after an update.
 *
 * It asks for the lane; it does not decide what the lane does. [TripLogService.reconsider]
 * is the only place that question gets answered, so a receiver with ten seconds of
 * allowance and no window is not also a place with rules in it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext
        // DataStore is the first thing that has to answer, and a receiver that reads it
        // synchronously is a receiver ANR'd before it decides anything.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val settings = RecorderSettings(app)
                val vehicles = VehicleStore(app)
                val enabled = try {
                    settings.backgroundEnabled.first()
                } catch (e: Exception) {
                    false
                }
                val target = try {
                    BackgroundTarget.pick(vehicles.all().first(), vehicles.selectedId(), settings.pausedUnits.first())
                } catch (e: Exception) {
                    null
                }
                val bluetooth = granted(app)
                if (!BootDecision.allowed(enabled, target, bluetooth)) {
                    Log.d(TripLogService.LOG_TAG, "boot: nothing to start " +
                        "enabled=$enabled target=${target?.id} bluetooth=$bluetooth")
                    return@launch
                }
                Log.d(TripLogService.LOG_TAG, "boot: lane for ${target?.id}")
                TripLogService.ensure(app)
            } catch (e: Exception) {
                // A boot receiver that throws is a crash the owner sees as the app failing
                // to open. Everything here is optional; a lane that comes up at the next
                // launch is the acceptable outcome.
                Log.w(TripLogService.LOG_TAG, "boot: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun granted(context: Context): Boolean =
        // `BLUETOOTH_CONNECT` is the runtime permission Android 12 introduced; below that
        // the legacy scan/connect pair is already granted by the manifest, so there is
        // nothing to have lost.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether a boot should start the lane - all of it pure, because "did the app decide to
     * wake up" is the question a bench cannot re-run on demand.
     */
    object BootDecision {
        fun allowed(enabled: Boolean, target: Vehicle?, bluetoothGranted: Boolean): Boolean =
            enabled && target != null && bluetoothGranted
    }
}
