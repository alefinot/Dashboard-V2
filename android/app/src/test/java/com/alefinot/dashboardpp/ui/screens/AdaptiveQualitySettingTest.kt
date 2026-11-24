package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App Settings → Trips & Storage → **AUTO QUALITY**, end to end (maintainer, 2026-11-24: *"below
 * 20 fps the route quality must degrade automatically; add a switch for this in App Settings"*).
 *
 * The switch crosses four files that never import each other: the page that draws it, the DataStore
 * row that remembers it, the view model that reads it, and the detail page that spends it. A future
 * edit is free to delete any one of the four and still compile, so the lane is walked here the same
 * way `RouteQualitySettingTest` walks the slider's. The behaviour behind the switch — when a frame
 * clock is considered too slow, and what a step does to a route — is tested in
 * `FrameRateWatcherTest` and `RouteThinningTest`.
 */
class AdaptiveQualitySettingTest {

    @Test
    fun theSwitchSitsUnderTheRouteQualitySlider() {
        val page = moduleSource("SettingsScreen.kt")
        assertTrue(
            "the AUTO QUALITY switch left App Settings — a page that cannot keep up has no way " +
                "to be told it may step down",
            Regex("\"AUTO QUALITY\"").containsMatchIn(page),
        )
        assertTrue(
            "the switch is not connected to the store",
            Regex("settings\\.setAdaptiveQuality\\(on\\)").containsMatchIn(page) &&
                Regex("settings\\.adaptiveQuality").containsMatchIn(page),
        )
        assertTrue(
            "the switch is drawn as a raw Material control instead of the page's ToggleRow",
            Regex("ToggleRow\\([\\s\\S]{0,200}?\"AUTO QUALITY\"").containsMatchIn(page),
        )
    }

    @Test
    fun theStoreKeepsItOnByDefaultAndWarmsItAtBoot() {
        val store = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/Stores.kt")
        assertTrue(
            "the adaptive_quality key left the store — the switch would forget its value",
            Regex("booleanPreferencesKey\\(\"adaptive_quality\"\\)").containsMatchIn(store),
        )
        assertTrue(
            "the default stopped being on — the safety net only exists while it is armed",
            Regex("it\\[KEY_ADAPTIVE_QUALITY\\] \\?: true").containsMatchIn(store),
        )
        assertTrue(
            "adaptiveQuality is not warmed at boot",
            Regex("adaptiveQuality\\.first\\(\\)").containsMatchIn(store),
        )
    }

    @Test
    fun theDetailPageSpendsIt() {
        val vm = moduleFile("src/main/java/com/alefinot/dashboardpp/viewmodel/TripsViewModel.kt")
        assertTrue(
            "the view model stopped exposing the setting",
            Regex("val adaptiveQuality: Flow<Boolean>").containsMatchIn(vm),
        )
        val page = moduleSource("TripDetailScreen.kt")
        assertTrue(
            "the detail page stopped reading the switch",
            Regex("vm\\.adaptiveQuality\\.collectAsStateWithLifecycle").containsMatchIn(page),
        )
        assertTrue(
            "the detail page no longer asks the ladder to step",
            Regex("rememberAdaptiveRouteLevel\\(").containsMatchIn(page) &&
                Regex("rungs = routes\\.size").containsMatchIn(page),
        )
    }

    /**
     * A step is a *drawing* decision: the file on disk, the summary cache and the live map's line
     * are all untouched by it. The one thing the detail page must keep doing when it steps is
     * framing the camera on the drive rather than on the rung — otherwise a coarser route would
     * yank a viewport the owner had panned.
     */
    @Test
    fun steppingDownNeverTouchesTheCameraOrTheFile() {
        val detail = moduleSource("TripDetailScreen.kt")
        assertTrue(
            "the detail map stopped being framed on the drive",
            Regex("fitKey = row\\.file\\.absolutePath").containsMatchIn(detail),
        )
        val map = mapFile("RouteMap.kt")
        assertTrue(
            "the map stopped guarding a re-draw on the list it was handed",
            Regex("points !== drawnPoints \\|\\| runs !== drawnRuns").containsMatchIn(map),
        )
        assertFalse(
            "the map went back to re-fitting on the point list, which a route step would move",
            Regex("h = h \\* 31 \\+ track\\.size").containsMatchIn(map),
        )
    }

    // ─── helpers (same walking-up search as RouteQualitySettingTest) ─────────────

    private fun moduleSource(name: String): String =
        moduleFile("src/main/java/com/alefinot/dashboardpp/ui/screens/$name")

    private fun mapFile(name: String): String =
        moduleFile("src/main/java/com/alefinot/dashboardpp/ui/map/$name")

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
        error("no app module found above ${System.getProperty("user.dir")}")
    }
}
