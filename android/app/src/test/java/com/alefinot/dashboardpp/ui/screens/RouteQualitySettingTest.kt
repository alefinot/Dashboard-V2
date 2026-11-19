package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's ROUTE QUALITY slider, end to end (maintainer, 2026-11-19: "in the app settings
 * add a quality slider for the route, make it go full quality up to 10000km at max
 * quality").
 *
 * The slider crosses three files that never import each other - the settings page, the
 * DataStore row, and the recorder that builds the live track - and a future edit is free to
 * delete any one of them without breaking a line of compilation in the other two. So this
 * guard walks the whole lane: the rail on the page, the key in the store, the read in the
 * service, and the dials it hands the live track. The numbers behind the ends
 * (2 m never climbing, a 1 000 000-position budget covering 10 000 km) are behaviour and
 * tested there, in `LiveRouteTest`; here it is only that the lane is connected.
 */
class RouteQualitySettingTest {

    @Test
    fun theSliderSitsOnTripsAndStorage() {
        val page = moduleSource("SettingsScreen.kt")
        assertTrue(
            "the ROUTE QUALITY label left App Settings - the slider has nowhere to be moved",
            Regex("\"ROUTE QUALITY\"").containsMatchIn(page),
        )
        assertTrue(
            "the row lost its HudSlider - Material's own is the control this page does not " +
                "use (see HudSlider)",
            Regex("HudSlider\\(").containsMatchIn(page) &&
                Regex("valueRange = 0f\\.\\.100f").containsMatchIn(page),
        )
        assertTrue(
            "the page writes the store directly on every drag frame instead of hoisting the " +
                "drag and saving on release",
            Regex("var routeDrag by remember").containsMatchIn(page) &&
                Regex("onValueChangeFinished").containsMatchIn(page) &&
                Regex("settings\\.setRouteQuality\\(dragged\\)").containsMatchIn(page),
        )
    }

    @Test
    fun theStoreKeepsTheFigureAndWarmsItAtBoot() {
        val store = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/Stores.kt")
        assertTrue(
            "the route_quality key left the store - the slider would forget its value",
            Regex("intPreferencesKey\\(\"route_quality\"\\)").containsMatchIn(store),
        )
        assertTrue(
            "the default stopped being full quality - the maintainer's last word on the " +
                "blue line was quality first",
            Regex("it\\[KEY_ROUTE_QUALITY\\] \\?: 100").containsMatchIn(store),
        )
        assertTrue(
            "routeQuality is not warmed at boot - the recorder's start-up path would pay " +
                "the cold DataStore read on the link's first frame",
            Regex("routeQuality\\.first\\(\\)").containsMatchIn(store),
        )
    }

    @Test
    fun theServiceHandsItToTheRecorderAndTheRecorderToTheTrack() {
        val service = moduleFile("src/main/java/com/alefinot/dashboardpp/service/TripLogService.kt")
        assertTrue(
            "the recorder is built without reading the slider again - the page's switch " +
                "would be decoration",
            Regex("appSettings\\.routeQuality\\.first\\(\\)").containsMatchIn(service) &&
                Regex("\\}, prior, routeQuality\\)").containsMatchIn(service),
        )
        val recorder = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/TripRecorder.kt")
        assertTrue(
            "the recorder stopped passing the quality to the live track - every drive would " +
                "run the built-in defaults whatever the owner slid",
            Regex("TrackQuality\\.fromQuality\\(routeQuality\\)").containsMatchIn(recorder) &&
                Regex("totalThinAt = q\\.totalThinAt").containsMatchIn(recorder),
        )
    }

    @Test
    fun theTopEndStillMeansTenThousandKilometres() {
        val route = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/LiveRoute.kt")
        assertTrue(
            "the full-quality budget shrank below what covers 10 000 km at a real road's " +
                "thinning rate (the maintainer's spec for the slider's top end)",
            Regex("MAX_TOTAL_THIN_AT = 1_000_000").containsMatchIn(route) &&
                Regex("baseToleranceM = 2\\.0 \\* 2\\.0\\.pow\\(down\\)").containsMatchIn(route),
        )
    }

    // ─── helpers (same walking-up search as ConnectActionsOnOnePageTest) ──────────

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
        error("no app module found above ${System.getProperty("user.dir")}")
    }
}
