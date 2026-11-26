package com.alefinot.dashboardpp.ui.screens

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * The three things the always-on lane needs from the phone that its own code cannot do
 * (background link plan §4), and the system pages that grant them.
 *
 * They are grouped here because the answers are only ever read together, on the Always On
 * card, and because each one is a ROM-specific negotiation the app loses by getting wrong:
 * a battery-optimisation ask that throws on a Xiaomi, or an autostart page that does not
 * exist on a Pixel, both have to end as a sentence on screen rather than a crash. Nothing
 * here reads or writes the app's own state - that is [com.alefinot.dashboardpp.trips.RecorderSettings].
 *
 * None of it is asked for at install or at launch. The card offers it, once, and the owner
 * presses the button that opens the system page.
 */
object BackgroundPermissions {

    /**
     * True when the platform is allowed to freeze the app's background work.
     *
     * Defaulting to *true* (exempt) on a device that will not answer is deliberate: the row
     * then stays quiet instead of nagging about a restriction the phone says it does not
     * have. Every real device answers - this is a `PowerManager` call, not a ROM hook.
     */
    fun batteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /**
     * Opens the page that grants [batteryExempt]. True when the direct page opened; false
     * when the ROM refused it, in which case the caller should point at the app's details
     * page instead - `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is not exported by every
     * skin, and MIUI's own battery-saver screen hooks in through the details route.
     */
    fun askBatteryExemption(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true
        return openAppDetails(context)
    }

    /**
     * True when the lane may show its ongoing notification.
     *
     * A foreground service whose notification is blocked is torn down on many devices, so
     * this is a working-condition check, not a cosmetic one: with notifications off, the
     * always-on lane is expected to die, and the card says so.
     */
    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Opens the notification settings for this app (Android 8+ shows the channel list). */
    fun openNotificationSettings(context: Context): Boolean {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /**
     * Opens the OEM's autostart page, for the one case no Android API can answer: whether
     * MIUI will deliver BOOT_COMPLETED to a stopped app at all.
     *
     * There is no supported way to *read* that switch - it is not an Android permission and
     * the only way to it is reflection into Xiaomi's own settings provider, which breaks
     * between ROMs - so the card never claims to know its state. It opens the list and says
     * what to look for. False means this phone has no such page, which is the honest answer
     * on a Pixel and on most newer Xiaomi ROMs that dropped the entry.
     */
    fun openAutoStartPage(context: Context): Boolean {
        val candidates = listOf(
            // MIUI / HyperOS security's autostart list. The two component names are the ones
            // in circulation across MIUI 8 through 14; newer builds moved the entry under the
            // battery screens, which is what the details fallback is for.
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
        )
        for (component in candidates) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** The app's own system page: where every restriction on this one app is gathered. */
    fun openAppDetails(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}
