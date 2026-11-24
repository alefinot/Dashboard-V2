package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 0-50 run is one line, worn by two maps (maintainer, 2026-11-22: "the 0-50 runs should be
 * recorded in the trips too").
 *
 * The live page drew them first; the trip detail page draws them now, out of the same file's rows.
 * Left to itself that is two call sites free to grow two inks, two tap radii and two result cards —
 * and the owner reading two different answers about one launch. So the yellow, its casing, the white
 * selection, the hit test and the wording of the result all live in `ui/map/RunOverlay.kt` and
 * `ui/screens/RunResultCard.kt`, and both surfaces only ever ask those two files. What is being
 * recorded here is that neither surface went back to drawing its own.
 *
 * The assertions read source text with plain `contains`, like `MapStyleSettingTest`: the lane crosses
 * files that never import each other, so a deleted call site is invisible to the compiler.
 */
class RunLinesOnBothMapsTest {

    /** The yellow, the casing, the white selection and the tap radius belong to exactly one file. */
    @Test
    fun theRunInkIsNotDuplicatedOntoEitherMap() {
        val overlay = mapSource("RunOverlay.kt")
        assertTrue(
            "RunOverlay lost the yellow the line is named for",
            overlay.contains("HudColors.Yellow.toArgb()"),
        )
        assertTrue(
            "RunOverlay lost the white a tapped run dresses itself in",
            overlay.contains("HudColors.White.toArgb()"),
        )
        for (name in listOf("LiveRouteMap.kt", "RouteMap.kt")) {
            val src = mapSource(name)
            assertTrue("$name stopped wearing the shared overlay", src.contains("RunOverlay()"))
            assertTrue("$name stopped asking the overlay to draw", src.contains("runOverlay.sync("))
            assertTrue("$name stopped asking it where the finger landed", src.contains("runOverlay.hit("))
            assertFalse(
                "$name painted a run of its own again — the ink would be one edit away from the " +
                    "other map's",
                src.contains("HudColors.Yellow.toArgb()"),
            )
        }
    }

    /**
     * A drive read back from its file reaches the detail map, with its runs recovered once.
     *
     * The recovery moved out of the view model when the detail page became a single pass over the
     * file ([com.alefinot.dashboardpp.trips.TripDetailReader]): the rows are gone by the time the
     * page sees anything, so the runs have to be streamed beside the route and the charts or they
     * cost a second parse of a 43 k-row file. What must not move is the rule — one replay state
     * machine, the same one the live frame lane feeds.
     */
    @Test
    fun aRecordedDriveArrivesWithItsRunsAndThePageHandsThemToTheMap() {
        val reader = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/TripDetailReader.kt")
        assertTrue(
            "the detail reader stopped feeding the file's rows through the run replay",
            reader.contains("replay.row(sample)"),
        )
        assertTrue(
            "TripDetail no longer carries them across to the screen",
            reader.contains("val runs: List<AccelRun>"),
        )
        val replay = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/AccelRun.kt")
        assertTrue(
            "RunReplay stopped being the same state machine the live lane runs — an archived run " +
                "could then disagree with the yellow line the live map drew a minute earlier",
            replay.contains("class RunReplay") && replay.contains("AccelRunTracker(maxRuns, RunSelection.QUICKEST)"),
        )
        val page = moduleSource("TripDetailScreen.kt")
        assertTrue("the detail map stopped receiving the drive's runs", page.contains("runs = runs"))
        assertTrue("a tapped run no longer has a line dressed for it", page.contains("selectedRun = shownRun"))
        assertTrue("the map's run tap no longer reaches the page", page.contains("onRunTap ="))
    }

    /** One card and one wording: neither page may own a private answer to "how long was it?". */
    @Test
    fun bothPagesAnswerWithTheSameCard() {
        val card = moduleSource("RunResultCard.kt")
        assertTrue(
            "the card lost the number the unit measured",
            card.contains("hudNum(run.seconds, 2)"),
        )
        for (name in listOf("LiveTab.kt", "TripDetailScreen.kt")) {
            val src = moduleSource(name)
            assertTrue("$name stopped showing the shared card", src.contains("RunResultCard("))
            assertFalse(
                "$name grew its own copy of the card — two wordings of one measurement",
                src.contains("private fun RunResultCard"),
            )
        }
    }

    // ─── helpers (same walking-up search as MapStyleSettingTest) ────────────────

    private fun moduleSource(name: String): String =
        moduleFile("src/main/java/com/alefinot/dashboardpp/ui/screens/$name")

    private fun mapSource(name: String): String =
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
