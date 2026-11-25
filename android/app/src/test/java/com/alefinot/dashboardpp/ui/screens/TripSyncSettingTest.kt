package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App Settings → Trips & Storage → **SYNC TRIP WITH DASHBOARD**, the lane (maintainer,
 * 2026-11-25). The switch crosses four files that never import each other: the page that
 * draws it, the DataStore row that remembers it, the service that sends the reset, and the
 * recorder that splits on the dashboard's. A future edit is free to delete any one of them
 * and still compile, so the lane is walked here the same way `AdaptiveQualitySettingTest`
 * walks the AUTO QUALITY switch's. The behaviour behind it — the drop rule, the reboot
 * precedence, the re-open suppression — is tested in `TripSyncTest`.
 */
class TripSyncSettingTest {

    @Test
    fun theSwitchSitsOnTripsAndStorage() {
        val page = moduleSource("SettingsScreen.kt")
        assertTrue(
            "the SYNC TRIP WITH DASHBOARD switch left App Settings",
            Regex("\"SYNC TRIP WITH DASHBOARD\"").containsMatchIn(page),
        )
        assertTrue(
            "the switch is not connected to the store",
            Regex("settings\\.setTripSyncEnabled\\(on\\)").containsMatchIn(page) &&
                Regex("settings\\.tripSyncEnabled").containsMatchIn(page),
        )
        assertTrue(
            "the switch is drawn as a raw Material control instead of the page's ToggleRow",
            Regex("ToggleRow\\([\\s\\S]{0,200}?\"SYNC TRIP WITH DASHBOARD\"").containsMatchIn(page),
        )
    }

    @Test
    fun theStoreKeepsItOnByDefaultAndWarmsItAtBoot() {
        val store = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/Stores.kt")
        assertTrue(
            "the trip_sync_enabled key left the store — the switch would forget its value",
            Regex("booleanPreferencesKey\\(\"trip_sync_enabled\"\\)").containsMatchIn(store),
        )
        assertTrue(
            "the default stopped being on (owner decision, 2026-11-25)",
            Regex("it\\[KEY_TRIP_SYNC\\] \\?: true").containsMatchIn(store),
        )
        assertTrue(
            "tripSyncEnabled is not warmed at boot",
            Regex("tripSyncEnabled\\.first\\(\\)").containsMatchIn(store),
        )
    }

    @Test
    fun onlyAManualStartResetsTheDashboard() {
        val service = moduleFile("src/main/java/com/alefinot/dashboardpp/service/TripLogService.kt")
        assertTrue(
            "the service no longer sends trip.reset",
            Regex("resetTrip\\(\\)").containsMatchIn(service),
        )
        assertTrue(
            "trip.reset is not gated on a manual START and the sync switch",
            Regex("if \\(manualStart && tripSyncEnabled\\)").containsMatchIn(service),
        )
        // The auto-start lane must stay out of the reset path: that is the whole
        // "reopening the app resets nothing" half of the feature.
        assertTrue(
            "the auto-start lane lost the manual flag that keeps it from resetting",
            Regex("manual = beginNewFile").containsMatchIn(moduleSource("ConnectedTabs.kt")),
        )
    }

    @Test
    fun theRecorderSplitsOnlyWhenTheSwitchIsOn() {
        val recorder = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/TripRecorder.kt")
        assertTrue(
            "the recorder stopped consulting the sync switch",
            Regex("tripSync\\(\\) && EspTripSync\\.isReset").containsMatchIn(recorder),
        )
        assertTrue(
            "the reset seal no longer forgets the path — the 24 h rule would re-open the old file",
            Regex("seal\\(EspTripSync\\.RESET_SEAL_REASON, forgetPath = true\\)")
                .containsMatchIn(recorder),
        )
        assertTrue(
            "the reboot check no longer wins over the reset check",
            recorder.indexOf("rebooted(prev, s)") < recorder.indexOf("EspTripSync.isReset(prev, s)"),
        )
    }

    // ─── helpers (same walking-up search as AdaptiveQualitySettingTest) ─────────────

    private fun moduleSource(name: String): String =
        moduleFile("src/main/java/com/alefinot/dashboardpp/ui/screens/$name")

    private fun moduleFile(relative: String): String {
        val f = File(moduleDir(), relative)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    private fun moduleDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/ui/screens"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (cand in listOf(File(dir, "android/app"), File(dir, "app"), dir)) {
                if (File(cand, marker).isDirectory) return cand
            }
            dir = dir.parentFile
        }
        error("no app module found above ${System.getProperty("user.dir") ?: "?"}")
    }
}
