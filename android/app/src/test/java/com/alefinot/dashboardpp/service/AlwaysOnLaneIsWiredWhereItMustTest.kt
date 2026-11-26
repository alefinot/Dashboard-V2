package com.alefinot.dashboardpp.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The always-on lane's wiring, in the places the compiler cannot see (background link plan §4).
 *
 * The feature fails in a way no build reports. A missing `<receiver>` and a missing
 * `BOOT_COMPLETED` filter are the same thing to the runtime: a phone reboots, the owner drives
 * to work, and there is no trip. A DISCONNECT that forgets to persist its latch is the same
 * thing: the app calls the dashboard back five seconds after being told not to. None of it is a
 * type error, and the bench phone has to be rebooted to find out, which is exactly why these
 * four agreements are asserted over the source text instead.
 *
 * Text tests on purpose - the repo has no Robolectric, and what is being guarded is the pairing
 * of names across files (a class named in Kotlin and in XML, a latch written in one file and
 * read in another), which is the half a JVM test can hold. The decisions themselves are pure and
 * tested where they live: [BackgroundTarget] and [BootReceiver.BootDecision].
 */
class AlwaysOnLaneIsWiredWhereItMustTest {

    // ── The manifest half ─────────────────────────────────────────────────────────────

    @Test
    fun `the boot receiver is declared for the two events that restart an install`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml")
        val declared = Regex("uses-permission[^>]*android\\.permission\\.([A-Z_]+)")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(
            "RECEIVE_BOOT_COMPLETED is gone: the phone reboots, the app never hears it, and the " +
                "next drive is not recorded with nothing in the log to say why - $declared",
            declared.contains("RECEIVE_BOOT_COMPLETED"),
        )

        val receiver = Regex("<receiver[^>]*android:name=\"\\.service\\.BootReceiver\"[^>]*>(.*?)</receiver>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)
        if (receiver == null) {
            throw AssertionError(
                "AndroidManifest.xml declares no .service.BootReceiver. The class exists, compiles " +
                    "and is never called: an installed app cannot restart itself after a reboot.",
            )
        }
        for (action in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")) {
            assertTrue(
                "the boot receiver lost $action - after an app update the lane is only restored " +
                    "the next time the owner happens to open the app",
                receiver.value.contains(action),
            )
        }
    }

    /**
     * The lane survives the two things that end a normal service: the task being swiped away,
     * and the OEM killing the process (which is what a sticky restart, and the whole
     * read-it-from-disk design, exists for).
     */
    @Test
    fun `the recorder lane is still a task-independent connected-device service`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml")
        val service = Regex("<service[^>]*android:name=\"\\.service\\.TripLogService\"[^>]*/?>")
            .find(manifest)
        if (service == null) {
            throw AssertionError("AndroidManifest.xml no longer declares .service.TripLogService")
        }
        assertTrue(
            "stopWithTask=\"false\" was dropped: swiping the app away would end a drive that is " +
                "being written, and would end the companion the owner set up on purpose",
            service.value.contains("android:stopWithTask=\"false\""),
        )
        assertTrue(
            "foregroundServiceType=\"connectedDevice\" was dropped, which is the type that makes " +
                "a Bluetooth link legal as ongoing work on Android 14",
            service.value.contains("android:foregroundServiceType=\"connectedDevice\""),
        )
    }

    // ── The two doors ─────────────────────────────────────────────────────────────────

    @Test
    fun `the boot receiver asks for the lane through the one door that re-derives everything`() {
        val src = moduleFile("src/main/java/com/alefinot/dashboardpp/service/BootReceiver.kt")
        assertTrue(
            "BootReceiver no longer consults BootDecision.allowed, so a boot can start the lane " +
                "with the switch off or with the unit the owner disconnected by hand",
            src.contains("BootDecision.allowed("),
        )
        assertTrue(
            "BootReceiver no longer asks for the lane through TripLogService.ensure, which is the " +
                "only start that re-reads the master switch after announcing",
            src.contains("TripLogService.ensure("),
        )
        // The ten-second rule: a receiver that reads DataStore before announcing is an ANR on a
        // cold boot, and `announce()` is inside ensure() - so the receiver must not call the
        // recorder's door directly.
        assertFalse(
            "BootReceiver starts the recorder directly (TripLogService.start): the lane would be " +
                "asked to record a car before anything has announced it, and the ten-second " +
                "foreground promise is spent in DataStore",
            Regex("TripLogService\\.start\\(").containsMatchIn(src),
        )
    }

    @Test
    fun `opening the app hands the lane back only when the owner left the switch on`() {
        val src = moduleFile("src/main/java/com/alefinot/dashboardpp/MainActivity.kt")
        val ensure = Regex("backgroundEnabled\\.first\\(\\)\\)\\s*\\{[^}]*TripLogService\\.ensure\\(")
            .containsMatchIn(src)
        assertTrue(
            "MainActivity.onStart no longer reads backgroundEnabled before asking for the lane. " +
                "Asking anyway is what makes a notification appear and vanish on every launch of " +
                "an app whose owner turned the companion off.",
            ensure,
        )
    }

    // ── The latch ─────────────────────────────────────────────────────────────────────

    /**
     * The agreement a reboot is measured against: DISCONNECT writes a latch, and the target rule
     * reads it. Written in [ConnectionViewModel], stored by [com.alefinot.dashboardpp.trips.RecorderSettings],
     * answered by [com.alefinot.dashboardpp.viewmodel.BackgroundTarget] - three files, one promise.
     *
     * The DISCONNECT button and the delete-card path share one drop, so this pins the pair: the
     * button hands over the unit's key, the drop is what persists it and tells the lane. Pinning
     * only the button would let the latch stop being written the day someone moved it, which is
     * the same regression with a greener build.
     */
    @Test
    fun `a manual disconnect is persisted and the lane is told`() {
        val vm = moduleFile("src/main/java/com/alefinot/dashboardpp/viewmodel/ConnectionViewModel.kt")
        val disconnect = section(vm, "fun disconnect()")
        assertTrue(
            "disconnect() no longer hands a unit key to the shared drop, so the reconnect ladder " +
                "reaches for the dashboard the owner just disconnected, and a reboot does it again",
            Regex("dropLink\\(\\s*pauseKey = [^n]").containsMatchIn(disconnect),
        )

        val drop = section(vm, "private fun dropLink(")
        assertTrue(
            "the shared drop no longer writes the pause latch (tripSettings.pauseUnit), so the " +
                "latch is a promise that lasts until the next frame re-arms it",
            drop.contains("tripSettings.pauseUnit("),
        )
        assertTrue(
            "the shared drop no longer asks the lane to reconsider, so an always-on lane keeps its " +
                "notification and its chase for a unit that is now paused",
            drop.contains("TripLogService.reconsider("),
        )

        val settings = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/Stores.kt")
        assertTrue(
            "RecorderSettings lost the background_paused_units key - a latch that lives only in " +
                "memory does not survive the reboot it was written for",
            settings.contains("background_paused_units"),
        )
        val target = moduleFile("src/main/java/com/alefinot/dashboardpp/viewmodel/BackgroundTarget.kt")
        assertTrue(
            "BackgroundTarget no longer reads the pause latch, which makes every latch a write " +
                "nobody ever reads",
            target.contains("pausedKeys"),
        )
    }

    /**
     * The STOP latch's one load-bearing word.
     *
     * "Recording may start again" is only true of a link that just came up, and the only caller
     * that knows which links those are is the view model's per-link auto-record (plan §3). The
     * pages' own auto-start effect fires on a link that is merely Ready, so if it could claim a
     * new link, the owner's STOP would be undone by the next frame - and the app would look like
     * it had started recording on its own again.
     */
    @Test
    fun `only the view model may claim a link just came up`() {
        val vm = moduleFile("src/main/java/com/alefinot/dashboardpp/viewmodel/ConnectionViewModel.kt")
        assertTrue(
            "the view model stopped saying newLink = true, so an owner's STOP is never re-armed " +
                "and one link is the last drive this app will ever record on its own",
            Regex("newLink\\s*=\\s*true").containsMatchIn(vm),
        )
        for (rel in listOf(
            "src/main/java/com/alefinot/dashboardpp/ui/screens/ConnectedTabs.kt",
            "src/main/java/com/alefinot/dashboardpp/ui/screens/SettingsScreen.kt",
        )) {
            val src = moduleFile(rel)
            assertFalse(
                "$rel now passes newLink: a screen cannot know that a link came up, it only sees " +
                    "that one is Ready, and the STOP latch exists to tell those two apart",
                Regex("newLink\\s*=\\s*true").containsMatchIn(src),
            )
        }
    }

    // ── The card ──────────────────────────────────────────────────────────────────────

    /**
     * The switch is the only thing an owner has, so its two writes are asserted: the preference,
     * and the lane that obeys it. A card that saved the switch without telling the service would
     * read "On" while nothing followed the car, which is the state a silent week is made of - and
     * turning it back on has to clear the hand-disconnect latches, because the owner's "always on"
     * is a fresh grant for every unit, not a reopening of the old argument with one of them.
     */
    @Test
    fun `the always-on switch writes its preference and moves the lane`() {
        val src = moduleFile("src/main/java/com/alefinot/dashboardpp/ui/screens/SettingsScreen.kt")
        assertTrue(
            "the Always On card is gone from App Settings. Its switch is the only place the " +
                "companion is granted or refused, and the battery, notification and MIUI autostart " +
                "asks live nowhere else.",
            src.contains("title = \"Always On\""),
        )
        for (needed in listOf(
            "settings.setBackgroundEnabled(", "settings.clearAllPauses()",
            "TripLogService.ensure(", "TripLogService.disable(",
            "BackgroundPermissions.batteryExempt(", "BackgroundPermissions.openAutoStartPage(",
        )) {
            assertTrue(
                "the Always On card lost $needed - the switch and the asks are one agreement, and " +
                    "half of it is how the app ends up running without permission and saying nothing",
                src.contains(needed),
            )
        }
    }

    /**
     * One function's body, braces counted. The same approach as
     * [com.alefinot.dashboardpp.viewmodel.DeletingAVehicleForgetsItTest]: a guard that matches
     * `.*?` up to the next indentation is a guard that reads half the class and passes anyway.
     */
    private fun section(src: String, header: String): String {
        val at = src.indexOf(header)
        if (at < 0) throw AssertionError("no `$header` in the source - update this guard")
        val open = src.indexOf('{', at)
        if (open < 0) throw AssertionError("`$header` has no body - update this guard")
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("`$header`'s body never closed - update this guard")
    }

    /**
     * Module-relative read, same tolerance as [com.alefinot.dashboardpp.vehicles.AppXmlWiringTest]:
     * a guard test that fails because it could not find its own input teaches you to ignore it.
     */
    private fun moduleFile(rel: String): String {
        val prefixes = listOf("", "app/", "android/app/")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val here: File = dir
            for (p in prefixes) {
                val f = File(here, p + rel)
                if (f.isFile) return f.readText()
            }
            dir = here.parentFile
        }
        throw IllegalStateException(
            "cannot find $rel (or app/$rel, or android/app/$rel) walking up from " +
                System.getProperty("user.dir").orEmpty(),
        )
    }
}
