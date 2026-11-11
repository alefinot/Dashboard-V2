package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live page was re-composed for the phone (phone-layout plan §4a): the wall of two-up stat
 * cells became cards with traces. That is a layout change, and a layout change on the one
 * page whose job is to show *everything* is exactly where a reading gets quietly dropped - so
 * the guard here is on the data, not on the wording: every figure the old page drew has to be
 * read by the new one, or has to be named below as something the page no longer claims.
 *
 * Labels were renamed where the old name only made sense beside its neighbours ("AVG ECO" next
 * to "INST ECO" became "INST"/"AVG" inside a card already titled FUEL), which is why these
 * assertions match the `Snapshot` field behind each figure instead of its caption.
 */
class LivePageReadoutsTest {

    private val screens = File("src/main/java/com/alefinot/dashboardpp/ui/screens")

    private fun codeOnly(name: String): String {
        val f = File(screens, name)
        assertTrue("missing $name", f.exists())
        var s = f.readText()
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m -> s = s.replace(m.value, " ") }
        return s.replace(Regex("//[^\n]*"), "")
    }

    /**
     * Every reading the stat-cell page drew, by the field it came from. A card that stops
     * reading one of these does not look wrong in a screenshot of a parked bike - it looks
     * wrong on a ride, which is the only time it matters.
     */
    @Test
    fun everyFigureTheStatCellsDrewIsStillOnThePage() {
        val page = codeOnly("LiveTab.kt")
        val readings = listOf(
            "spd" to "the gauge",
            "src" to "the speed source in the gauge's unit line",
            "avg" to "AVG beside the gauge",
            "max" to "MAX beside the gauge",
            "trpKm" to "the trip distance",
            "trpL" to "the fuel this trip has burned",
            "ikml" to "instant consumption",
            "akml" to "average consumption",
            "odo" to "the odometer",
            "fuelPct" to "the fuel bar",
            "fuelL" to "litres in the tank",
            "fuelSt" to "the fuel input's state",
            "volts" to "battery",
            "temp" to "coolant",
            "sats" to "satellite count",
            "hdop" to "HDOP",
            "alt" to "altitude",
            "tmrSt" to "the acceleration timer's state",
            "tmrS" to "the acceleration timer's seconds",
            "lat" to "the position",
            "lon" to "the position",
            "fix" to "the fix state",
        )
        val missing = readings
            .filter { (field, _) -> !Regex("\\.$field\\b").containsMatchIn(page) }
            .map { (field, what) -> "$field ($what)" }
        assertTrue("the redesigned Live page stopped reading: $missing", missing.isEmpty())
    }

    /**
     * Three readings the UNIT card drew, taken out on 2026-11-12 at the maintainer's request:
     * a driver does not come to the Live page to ask how the dashboard itself is doing, and
     * every one of them is measured better where it now lives.
     *
     *  - `fps` and `heap` — the **Performance Monitor** card on the unit's settings page reads
     *    them from the unit's own `perf` reply, at 1 s, with current/average/target frame rate,
     *    the low-water heap mark and the largest free block beside them. The Live card had one
     *    number from the 1 Hz telemetry frame.
     *  - `amb` — the **Auto Brightness** card on the unit's settings page draws it as *Ambient
     *    light reading (ADC)*, above the two capture buttons that store that very reading
     *    (`SettingsLayout.CARD_READINGS`, held by `SettingsLayoutTest`), and the same value is
     *    what auto-brightness dims the panel from. A bare `LIGHT 2844` with no band beside it
     *    was the least useful place in the app for that number.
     *
     * What the card also carried — the firmware version and the uptime — is not a reading and
     * is not lost either: `perf` prints *Uptime*, and the version is in the settings page's
     * firmware card and on the vehicle card.
     */
    @Test
    fun theUnitCardIsGoneAndItsThreeReadingsLiveWhereTheyAreMeasured() {
        val page = codeOnly("LiveTab.kt")
        assertFalse(
            "the Live page draws a UNIT card again",
            Regex("title = \"UNIT\"").containsMatchIn(page),
        )
        val cards = Regex("LiveCard\\(\\s").findAll(page).count()
        assertTrue(
            "the Live page grew past the five cards it is allowed to hold ($cards LiveCard calls) - " +
                "the sixth, UNIT, came out on 2026-11-12: fps and heap belong to the Performance " +
                "Monitor and the light ADC to the Auto Brightness card",
            cards <= 5,
        )
        for (field in listOf("fps", "heap", "amb")) {
            assertFalse(
                "the Live page reads .$field again - it belongs to the Performance Monitor and " +
                    "the Auto Brightness card, not to a driving screen",
                Regex("\\.$field\\b").containsMatchIn(page),
            )
        }
        // Where they went. Named here so the move cannot be undone from one file.
        val monitor = File(screens, "settings/PerfMonitorCard.kt")
        assertTrue("missing PerfMonitorCard.kt", monitor.exists())
        val monitorText = monitor.readText()
        assertTrue(
            "the performance monitor no longer reads the frame rate the Live card gave up",
            Regex("fpsCurrent").containsMatchIn(monitorText),
        )
        assertTrue(
            "the performance monitor no longer reads the heap the Live card gave up",
            Regex("freeHeap").containsMatchIn(monitorText),
        )
        assertTrue(
            "the performance monitor no longer prints the uptime the Live card carried",
            Regex("Uptime").containsMatchIn(monitorText),
        )
    }

    @Test
    fun theRoundGaugeSurvivedTheRedesign() {
        // The maintainer asked for the gauge to stay when the card stack was approved: it is
        // the one element that answers "how fast" without being read.
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "HudGauge is gone from the Live page; the approved design keeps it and shrinks the " +
                "box instead of deleting it",
            Regex("HudGauge\\(").containsMatchIn(page),
        )
        assertTrue(
            "the gauge box grew past what the first screen can hold: it is sized by GaugeBox, " +
                "measured so GNSS still lands on screen",
            Regex("private val GaugeBox = (1[0-3][0-9]|9[0-9])\\.dp").containsMatchIn(page),
        )
    }

    /**
     * The battery and coolant traces are drawn on a fixed scale with a dashed rule at the
     * threshold, and that is a decision, not a style: an autoscaled five-minute battery trace
     * stretches a 0.3 V sag across the whole box, which makes a healthy bike look urgent and a
     * dying one look flat. Both figures also have to agree with the colour of the number beside
     * them, which is the same 11.5 V / 105 °C the old stat cells coloured themselves on.
     */
    @Test
    fun thePowerTracesArePinnedToAScaleAndDrawTheirLimit() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the battery trace lost its pinned scale (domain) - on its own scale a flat " +
                "13.4 V trace is indistinguishable from a sagging one",
            Regex("""domain = 10\.0\.\.16\.0""").containsMatchIn(page),
        )
        assertTrue(
            "the 11.5 V floor stopped being drawn: the number beside the trace goes red at it, " +
                "so the trace has to show it too",
            Regex("""mark = 11\.5""").containsMatchIn(page),
        )
        assertTrue(
            "the coolant trace lost its pinned scale",
            Regex("""domain = 0\.0\.\.130\.0""").containsMatchIn(page),
        )
        assertTrue("the 105 °C ceiling stopped being drawn", Regex("""mark = 105\.0""").containsMatchIn(page))
        assertTrue(
            "the bounded traces started filling their area again: under a nearly flat line on a " +
                "fixed scale the fill is most of the box, and it drew a green slab over the rule " +
                "(bench, 2026-11-08)",
            Regex("fill = null").containsMatchIn(page),
        )
    }

    @Test
    fun thePageKeepsItsHistoryBounded() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the traces are no longer fed by a bounded window; an unbounded history grows with " +
                "the ride and redraws every point on every frame",
            Regex("class LiveTrends\\(val cap: Int = 60, val everyMs: Long = 5_000L\\)").containsMatchIn(page),
        )
        assertTrue(
            "the window no longer drops its oldest point, so the cap is decorative",
            Regex("while \\(window\\.size > cap\\) window\\.removeFirst\\(\\)").containsMatchIn(page),
        )
    }

    @Test
    fun theHeaderStoppedSpendingTwoThirdsOfAScreenOnAWord() {
        val page = codeOnly("LiveTab.kt")
        assertFalse(
            "the Live page went back to the page's desktop header: that is a centred 26 sp " +
                "heading plus a subtitle, 85 dp measured on the bench phone, for a word the tab " +
                "bar already says",
            Regex("HudPageHeader\\(").containsMatchIn(page),
        )
        assertTrue(
            "the run state stopped being drawn - the chip is the header's other half, and it is " +
                "the half that was worth the height",
            Regex("StatusChip\\(state, active\\)").containsMatchIn(page),
        )
        assertTrue(
            "the stack stopped scrolling; six cards plus the header is taller than the bench " +
                "phone's window, so a clipped page is what a missing scroll gives",
            Regex("verticalScroll\\(rememberScrollState\\(\\)\\)").containsMatchIn(page),
        )
    }
}
