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
        // The five cards, counting call sites: `fun LiveCard(` is the declaration, not a card,
        // and it used to sit inside the count because the POWER call happened to fit one line.
        // All five calls are wrapped now (icon plan P8 gave every card its mark), so the
        // declaration must be excluded or the cap means six.
        val cards = Regex("(?<!fun )LiveCard\\(\\s").findAll(page).count()
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

    /**
     * THIS TRIP's elapsed figure is the app's clock, and it has to keep running between the
     * unit's one-frame-a-second updates. The card used to read `System.currentTimeMillis()`
     * once per composition and print hours and minutes, so a link that went quiet froze it
     * and a healthy link still showed the same value for sixty seconds at a time - a
     * stopwatch that only moves on the minute is indistinguishable from a stuck one.
     */
    @Test
    fun theTripCardClockTicksBetweenFrames() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the trip card is back on the frame clock: its elapsed figure must read the card's " +
                "own ticking clock (nowMs), not System.currentTimeMillis() at composition time",
            Regex("""val elapsedMs = nowMs - state\.tripStartMs""").containsMatchIn(page),
        )
        assertTrue(
            "the trip card stopped ticking between frames - the elapsed figure would freeze " +
                "whenever the link goes quiet, which is exactly when it is the only live number",
            Regex("""delay\(TRIP_CLOCK_TICK_MS\)""").containsMatchIn(page),
        )
        assertTrue(
            "the clock no longer follows the pager's viewport flag: every page stays composed " +
                "in this pager, so a card nobody can see would tick four times a second for " +
                "nobody and redraw itself all the way through a ride",
            Regex("""LaunchedEffect\(state\.recording, state\.tripStartMs, inView\)""").containsMatchIn(page),
        )
        assertTrue(
            "the TIME figure and its meta note no longer share the H:MM:SS format; without " +
                "seconds the card sits on one value for a minute",
            Regex("""hudDuration\(elapsedMs\)""").findAll(page).count() >= 2,
        )
    }

    /**
     * The open drive draws its route while it is being made (live route plan, 2026-11-15):
     * the Live card and the Trips RECORDING row both render the live map, and both only
     * while a trip file is open - a map outside `recording` would fetch a basemap for a
     * drive that does not exist. The map itself follows the newest position; it must
     * never frame the point set, which on a growing track means a zoom-punch on every
     * metre the drive makes.
     */
    @Test
    fun theRecordingTripDrawsItsRouteLive() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the THIS TRIP card stopped drawing the live route",
            Regex("LiveRouteMap\\(state\\.liveTrack").containsMatchIn(page),
        )
        assertTrue(
            "the live map escaped the recording gate: with no file open there is no route, " +
                "and a bare map is tiles bought for nothing",
            Regex("if \\(state\\.recording\\)[\\s\\S]{0,600}?LiveRouteMap\\(state\\.liveTrack").containsMatchIn(page),
        )
        val row = codeOnly("TripsTab.kt")
        assertTrue(
            "the RECORDING row stopped drawing the live route - the live drive should look " +
                "like the card it becomes",
            Regex("LiveRouteMap\\(\\s*state\\.liveTrack").containsMatchIn(row),
        )
        assertTrue(
            "the row map drifted back to the right of the numbers: the sketch belongs on the " +
                "left, the corner the sealed card this drive becomes wears it in",
            row.indexOf("state.liveTrack") < row.indexOf("Text(\"RECORDING\""),
        )
        assertTrue(
            "osmdroid's +/− slabs grew back on the 72 dp thumbnail, where they cover more of " +
                "the drive than the drive does (pinch-zoom stays; it is the control that goes)",
            Regex("showZoomButtons\\s*=\\s*false").containsMatchIn(row),
        )
        val map = File("src/main/java/com/alefinot/dashboardpp/ui/map/LiveRouteMap.kt")
        assertTrue("missing LiveRouteMap.kt", map.exists())
        val mapCode = codeOnly("../map/LiveRouteMap.kt")
        assertTrue(
            "the live map grew a fit: framing a point set that gains a point every second " +
                "zoom-punches the view; the camera follows instead",
            !Regex("zoomToBoundingBox").containsMatchIn(mapCode),
        )
        assertTrue(
            "the hidden-zoom rule lost its gate: the hiding belongs to the thumbnail caller, " +
                "the Live page's card map is big enough to keep its control",
            Regex("if \\(!showZoomButtons\\)\\s*view\\.setBuiltInZoomControls\\(false\\)")
                .containsMatchIn(mapCode),
        )
        val dots = codeOnly("../map/RouteMap.kt")
        assertTrue(
            "a map dot started popping osmdroid's title callout again (showInfoWindow on tap): " +
                "the word beside the dot is drawn into its bitmap, and on the live maps a tap " +
                "IS the resume-follow gesture - the bubble is noise from aiming at the car",
            Regex("setOnMarkerClickListener").containsMatchIn(dots),
        )
    }

    /**
     * The unit answers once a second, and the page used to format each frame as it landed -
     * so every figure teleported once a second, and the page read as broken even when every
     * jump was honest. The continuous readings now travel through one sample period
     * (ui/components/HudFlow.kt): linearly, between two real samples, never outside them,
     * late by at most a second. The discrete readings - satellite count, fix state, the fuel
     * sender's health - stay steps, because a count gliding through 9.4 is not smoothness,
     * it is fiction.
     */
    @Test
    fun theLiveFiguresFlowInsteadOfStepping() {
        val flow = File("src/main/java/com/alefinot/dashboardpp/ui/components/HudFlow.kt")
        assertTrue("missing HudFlow.kt", flow.exists())
        val glide = flow.readText()
        assertTrue(
            "flow() stopped being a constant-speed glide - the steady pace is what makes a wall " +
                "of 1 Hz figures read as flowing instead of as a pulse with dead air between beats",
            Regex("tween\\(FLOW_MS,\\s*easing = LinearEasing").containsMatchIn(glide),
        )
        val page = codeOnly("LiveTab.kt")
        // No continuous *figure* may reach fmt() raw any more; it has to pass through flow().
        val raw = listOf(
            "avg", "max", "odo", "volts", "temp", "trpKm", "fuelL",
            "ikml", "tmrS", "hdop", "alt",
        ).filter { Regex("fmt\\(s\\.$it\\b").containsMatchIn(page) }
        assertTrue("these Live-page figures are back on raw 1 Hz steps: $raw", raw.isEmpty())
        // A caption is a different animal, and the rule is worth writing down because the
        // obvious edit breaks it: a card's caption is built in the card's body, so a glide
        // there recomposes the whole body - trace, strip, bar and the live map beside it -
        // at frame rate to smooth one line of small dim text. Bench-measured, that was the
        // Live page's entire frame budget. So the figures flow, the notes step.
        val captionFlow = page.lines().filter { it.contains("flow(") && it.contains('"') }
        assertTrue(
            "a caption started gliding again: $captionFlow - move the reading into a leaf " +
                "that paints it, the way FlowBar and FlowFigureText do, or leave the note a step",
            captionFlow.isEmpty(),
        )
        assertTrue(
            "a discrete reading started gliding - the satellite count and the fix state change " +
                "in steps honestly, and the page must keep saying so",
            !Regex("flow\\(s\\.(sats|fix)\\b").containsMatchIn(page),
        )
        val row = codeOnly("TripsTab.kt")
        assertTrue(
            "the RECORDING row's numbers stepped again while the card they mirror flows",
            Regex("flow\\(summary\\?\\.distanceKm").containsMatchIn(row),
        )
        assertTrue(
            "the recording row's glide moved back into the row's body, which paints a map: it " +
                "has to live in the leaf that draws the line (TripFlowLine)",
            Regex("TripFlowLine\\(state\\)").containsMatchIn(row),
        )
        val charts = codeOnly("../components/HudCharts.kt")
        assertTrue(
            "the gauge went back to a short burst of its own while every figure around it glides " +
                "for a whole second: a needle that settles and then rests is a pulse, and \"the " +
                "data moves in steps\" is the complaint this answers",
            Regex("flowSpec\\(\\)").containsMatchIn(charts) &&
                !Regex("tween\\(HudMotion\\.GAUGE_MS").containsMatchIn(charts),
        )
        // The pager keeps its neighbours composed, so an ungated glide animates pages
        // nobody is looking at. Measured on the bench phone: a build with the glide and
        // no gate rendered the idle App Settings page at sixty frames a second.
        assertTrue(
            "the glide stopped being gated on the page being in view - off screen it has to " +
                "snap, or the pager's hidden pages spend the drive animating for nobody",
            Regex("LocalEnterActive\\.current").containsMatchIn(flow.readText()) &&
                Regex("else snap\\(\\)").containsMatchIn(flow.readText()),
        )
        val mapCode = codeOnly("../map/LiveRouteMap.kt")
        assertTrue(
            "the live map stopped gliding: the dot and the camera have to travel the segment " +
                "between two fixes frame by frame, or a drive is a slideshow at one frame a second",
            Regex("postOnAnimation").containsMatchIn(mapCode) &&
                Regex("SystemClock\\.uptimeMillis").containsMatchIn(mapCode),
        )
        assertTrue(
            "the map's camera is being animated by osmdroid again - with the holder's own ticker " +
                "pointing it every frame, a transition of the library's is two authorities and a stutter",
            !Regex("animateTo|setMapCenter").containsMatchIn(mapCode),
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
