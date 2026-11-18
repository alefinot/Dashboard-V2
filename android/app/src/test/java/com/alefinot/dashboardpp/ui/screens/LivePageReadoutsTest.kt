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

    private val main = File("src/main/java/com/alefinot/dashboardpp")
    private val screens = File(main, "ui/screens")

    private fun codeOnly(name: String): String = codeOnlyIn("ui/screens", name)

    /** [codeOnly] for a file in another package - the recorder's shell has friends in `service`. */
    private fun codeOnlyIn(pkg: String, name: String): String {
        val f = File(File(main, pkg), name)
        assertTrue("missing $pkg/$name", f.exists())
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
     *    light reading (ADC)*, above the READ buttons on its Dark and Bright value rows that
     *    store that very reading (`SettingsLayout.CARD_READINGS`, held by `SettingsLayoutTest`),
     *    and the same value is
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
            Regex("""nowMs - state\.tripStartMs""").containsMatchIn(page),
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
            Regex("""LaunchedEffect\(state\.recording, state\.paused, state\.tripStartMs, inView\)""")
                .containsMatchIn(page),
        )
        assertTrue(
            "the TIME figure and its meta note no longer share the H:MM:SS format; without " +
                "seconds the card sits on one value for a minute",
            Regex("""hudDuration\(elapsedMs\)""").findAll(page).count() >= 2,
        )
        // The owner's PAUSE (2026-11-17): a frozen stopwatch, not one that keeps climbing while
        // the file receives nothing. The paused figure has to come from the pause instant.
        assertTrue(
            "the trip clock runs through a pause again: the elapsed figure must freeze at " +
                "state.pausedAtMs, or the card claims time the file never got",
            Regex("""state\.pausedAtMs - state\.tripStartMs""").containsMatchIn(page),
        )
        assertTrue(
            "the tick no longer stops while paused - a frozen figure must not recompose the " +
                "card four times a second for a clock that cannot move",
            Regex("""if \(!state\.recording \|\| state\.paused""").containsMatchIn(page),
        )
    }

    /**
     * A drive's route is drawn while it is being made (live route plan, 2026-11-15) and it
     * stays after the stop (2.0.7/179): the recorded line freezes at an amber PAUSE dot while
     * paused, the arrow keeps riding the newest GPS fix, and a closed drive keeps its shape
     * with a red END dot until the next START replaces it. The map itself follows the newest
     * position; it must never frame the point set, which on a growing track means a
     * zoom-punch on every metre the drive makes.
     */
    @Test
    fun theLiveRouteStaysDrawnThroughPauseAndStop() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the THIS TRIP card stopped drawing the live route",
            Regex("LiveRouteMap\\(\\s*route").containsMatchIn(page),
        )
        assertTrue(
            "the live map went back behind the open file: a paused drive and the drive that " +
                "just stopped both have a route to show, and losing it on stop is exactly the " +
                "picture the owner asked to keep",
            Regex(
                "if \\(state\\.recording \\|\\| route\\.hasTrack\\)" +
                    "[\\s\\S]{0,1400}?LiveRouteMap\\(\\s*route"
            ).containsMatchIn(page),
        )
        val row = codeOnly("TripsTab.kt")
        assertTrue(
            "the RECORDING row stopped drawing the live route - the live drive should look " +
                "like the card it becomes",
            Regex("LiveRouteMap\\(\\s*route").containsMatchIn(row),
        )
        assertTrue(
            "the recording row went back to a map that vanishes when the file closes; it " +
                "draws whenever the route has a shape",
            Regex("if \\(route\\.hasTrack\\)").containsMatchIn(row),
        )
        assertTrue(
            "the row map drifted back to the right of the numbers: the sketch belongs on the " +
                "left, the corner the sealed card this drive becomes wears it in",
            row.indexOf("state.liveRoute") < row.indexOf("\"RECORDING\""),
        )
        assertTrue(
            "the row map started swallowing gestures again: the thumbnail is a status " +
                "picture, not a second map - it must never pan, zoom or eat the list's " +
                "scroll that starts on it",
            Regex("interactive\\s*=\\s*false").containsMatchIn(row),
        )
        assertTrue(
            "the recording card lost its press grammar: since 2.0.7/178 the card is the " +
                "control - a press starts, pauses or resumes the drive it shows, and a " +
                "three-second press fills it red and stops it",
            Regex("rememberHoldToAct\\(").containsMatchIn(row) &&
                Regex("hold\\.modifier").containsMatchIn(row),
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
            "the map lost its pause/end vocabulary: a paused route must stop at the amber " +
                "PAUSE dots it reports, and a closed one must land on the red END dot",
            Regex("route\\.pauses").containsMatchIn(mapCode) &&
                Regex("route\\.endAt").containsMatchIn(mapCode) &&
                Regex("\"PAUSE\"").containsMatchIn(mapCode) &&
                Regex("\"END\"").containsMatchIn(mapCode),
        )
        assertTrue(
            "the map's arrow went back to reading the recorded route for its heading: a pause " +
                "freezes the route and not the car, so the head ended up pointing back at the " +
                "pause dot instead of down the road (2026-11-18) - the trail is the compass",
            Regex("headingPoints\\(trail\\)").containsMatchIn(mapCode) &&
                Regex("liveTrail").containsMatchIn(codeOnly("LiveTab.kt")),
        )
        assertTrue(
            "the blue line went back to appearing one fix at a time: while a piece is open its " +
                "newest position is held from the polyline and drawn by the gliding tail, so " +
                "the line generates under the arrow at the arrow's speed (owner, 2026-11-18)",
            Regex("syncTail\\(").containsMatchIn(mapCode) &&
                Regex("tail\\?\\.let").containsMatchIn(mapCode) &&
                Regex("writing && i == segments\\.size - 1").containsMatchIn(mapCode),
        )
        assertTrue(
            "the frame lane stopped keeping the arrow's history: the last minute of positions " +
                "is what the head turns on, and a recorder's route is not it - after a stop " +
                "there is no recorder at all, and the map still has to be able to turn",
            Regex("CursorTrail\\(\\)").containsMatchIn(codeOnly("../../service/TripLogService.kt")),
        )
        assertTrue(
            "the map stopped drawing one polyline per recorded piece: a pause is a gap, and " +
                "a single line would draw across ground the file never saw",
            Regex("syncSegments\\(").containsMatchIn(mapCode),
        )
        assertTrue(
            "the show-only rule lost its teeth: the map must refuse the touch at " +
                "dispatchTouchEvent (osmdroid answers Compose's dispatch itself and never " +
                "consults onTouchEvent - refusing there alone keeps the page's scroller " +
                "deaf to the drag), and keep the gesture detector and the +/− control off",
            Regex(
                "override fun dispatchTouchEvent[^{]*\\{\\s*if \\(!holder\\.interactive\\)"
            ).containsMatchIn(mapCode) &&
                Regex("setMultiTouchControls\\(false\\)").containsMatchIn(mapCode) &&
                Regex("setBuiltInZoomControls\\(false\\)").containsMatchIn(mapCode),
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
    fun everyIdleWordWearsTheNotRecordingLook() {
        val page = codeOnly("LiveTab.kt")
        // Two words say the same thing - nothing is recording: the fresh idle pill, and the one
        // the display-only lane wears while a finished drive's map keeps moving. Both draw the
        // red hairline and wash; a pill that offers START in a link-coloured skin reads as a
        // readout, which is the mistake this guards.
        for (word in listOf("LOGGING IDLE", "NOT RECORDING")) {
            val chip = page.substringAfter("\"$word\"", "")
            assertTrue("the \"$word\" pill left the status chip", chip.isNotEmpty())
            val recipe = chip.take(600)
            assertTrue(
                "\"$word\" went back to a plain hairline: a recording surface that is not " +
                    "recording wears red",
                Regex("edge = HudColors\\.Red").containsMatchIn(recipe),
            )
            assertTrue(
                "\"$word\" lost its red wash",
                Regex("washAlpha = 0\\.12f").containsMatchIn(recipe),
            )
        }
        // ...and neither word is a dead end: the state that turns a press into START reads the
        // display-only lane, which is why LOGGING IDLE can be left at all (2026-11-18).
        assertTrue(
            "the status chip stopped reading the display-only lane: a lane kept alive for the " +
                "map is idle, and its press must start a drive rather than stop a lane that is " +
                "put straight back up again",
            Regex("watching = state\\.watching").containsMatchIn(page),
        )
        // LOGGING IDLE wears the red all the way through (2.0.7/180): its word and its mark are
        // the warning's red too, not the link's cyan. NOT RECORDING keeps its dim word, because
        // that one is not a claim about the radio at all.
        assertTrue(
            "\"LOGGING IDLE\" went back to the link's cyan for its word: a pill that offers " +
                "START in a healthy-link colour reads as a readout, and the maintainer asked " +
                "for the red",
            Regex("LOGGING IDLE\", HudGlyphLedger\\.mark\\(\"link\\.on\"\\), HudColors\\.Red").containsMatchIn(page),
        )
        assertFalse(
            "\"NOT RECORDING\" took the red word - its ink is deliberately not a claim about " +
                "the link, only its hairline and wash are the warning",
            Regex("NOT RECORDING\", HudGlyphLedger\\.mark\\(\"readout\\.recording\"\\), HudColors\\.Red").containsMatchIn(page),
        )
        // The Trips page's card is the other half of the same sentence, so it wears the same
        // skin: the recipe lives on the card's `stopped` flag, which is what the display-only
        // lane resolves to once the lane is read.
        val card = codeOnly("TripsTab.kt")
        assertTrue(
            "the Trips card's idle edge stopped being the warning's red - the page a swipe away " +
                "would say the same thing in a different colour",
            Regex("edge = if \\(stopped\\) HudColors\\.Red").containsMatchIn(card),
        )
        assertTrue(
            "the Trips card's idle hairline drifted off the pill's 55%",
            Regex("borderAlpha = if \\(stopped\\) 0\\.55f").containsMatchIn(card),
        )
        assertTrue(
            "the Trips card's idle wash drifted off the pill's 12%",
            Regex("washAlpha = if \\(stopped\\) 0\\.12f").containsMatchIn(card),
        )
        assertTrue(
            "the card stopped treating a display-only lane as idle: `stopped` must cover the " +
                "START state, which is what that lane resolves to",
            Regex("stopped = control == RecordingControlState\\.START").containsMatchIn(card),
        )
        assertTrue(
            "the Trips card stopped reading the display-only lane and went back to calling it " +
                "STARTING, a word whose only press is a STOP the app undoes a moment later",
            Regex("watching = state\\.watching").containsMatchIn(card),
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
            Regex("StatusChip\\(state, active, actions\\)").containsMatchIn(page),
        )
        // Since 2.0.7/178 the pill is also the recorder's control: the header is where the
        // drive is started, paused, resumed and stopped from, and this is the guard that keeps
        // it from drifting back into a readout nobody can press.
        assertTrue(
            "the run-state pill went back to being a readout: a press must do what the state " +
                "says, and a three-second press must fill it red and stop the drive",
            Regex("rememberHoldToAct\\(").containsMatchIn(page) &&
                Regex("hold = hold::progress").containsMatchIn(page),
        )
        assertTrue(
            "the stack stopped scrolling; six cards plus the header is taller than the bench " +
                "phone's window, so a clipped page is what a missing scroll gives",
            Regex("verticalScroll\\(rememberScrollState\\(\\)\\)").containsMatchIn(page),
        )
    }

    @Test
    fun stoppingADriveLeavesTheAutoStartSwitchAlone() {
        // Owner, 2026-11-18: "when the recording is stopped it should keep the auto start on
        // connect on". The switch is a decision about the *next* ride; STOP answers a question
        // about this one. START still turns it on, so the only thing that turns it off is the
        // owner, on the App Settings page.
        val shell = codeOnly("ConnectedTabs.kt")
        assertFalse(
            "a stop from the pill or the card started switching auto-start off again - pressing " +
                "STOP for this drive silently cancelled the next one",
            Regex("setRecordingEnabled\\(false\\)").containsMatchIn(shell),
        )
        assertTrue(
            "START stopped turning auto-start on: with STOP no longer writing the switch, this " +
                "is the only place that turns it on, and the recorder would never start by " +
                "itself again",
            Regex("setRecordingEnabled\\(true\\)").containsMatchIn(shell),
        )
        val service = codeOnlyIn("service", "TripLogService.kt")
        assertFalse(
            "the shade's STOP grew a preference write back: every stop has to end only the drive",
            Regex("EXTRA_DISABLE_AUTO|stopAndDisableAuto").containsMatchIn(service),
        )
    }

    @Test
    fun pressingStartNeverBlanksTheLiveMap() {
        // The finished drive's shape is what the page draws between the START press and the new
        // drive's first fix; the service publishes it through `routeToShow` rather than clearing
        // it on the way in (owner, 2026-11-18: "the live map disappears for a moment").
        val service = codeOnlyIn("service", "TripLogService.kt")
        assertTrue(
            "the START path went back to clearing the map itself: it empties the route a whole " +
                "sample period before the new drive has a position to put there",
            !Regex("liveRoute = LiveRoute\\(").containsMatchIn(service),
        )
        assertTrue(
            "the published route stopped going through the handover rule, so an empty new drive " +
                "erases the drive that just finished",
            Regex("liveRoute = routeToShow\\(").containsMatchIn(service),
        )
        // And the map must not treat that leftover shape as one it is drawing under the arrow:
        // a route with an END dot belongs to a finished drive, and holding its newest point back
        // to a live cursor would draw a line across a drive that is over.
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the follow map started growing a live tail on a finished drive's shape - while a " +
                "new drive waits for its first fix the picture on screen is the OLD drive's",
            Regex("writing = state\\.recording && !state\\.paused && !route\\.closed").containsMatchIn(page),
        )
    }
}
