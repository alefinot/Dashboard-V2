package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live page is a map now (2026-11-19): the header and the four scrolling cards became one
 * full-bleed live map with two glass shelves over it, and the page split in two files - the map
 * stage (`LiveTab.kt`) and the shelves and their vocabulary (`LiveHud.kt`). A layout change on
 * the one page whose job is to show *everything* is exactly where a reading gets quietly
 * dropped, so the guard here is on the data, not on the wording: every figure the card stack
 * drew has to be read by the shelves, or has to be named below as something the page no longer
 * claims.
 *
 * Because one guard can span both files (the clock lives in the ribbon, the pill in the chip),
 * [livePage] is the two sources concatenated - a regex proven against it cannot be satisfied by
 * moving a reading from one half of the page to the other.
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

    /** The page is the map stage plus the shelves: one page, two files. */
    private fun livePage(): String = codeOnly("LiveTab.kt") + "\n" + codeOnly("LiveHud.kt")

    /**
     * Every reading the card stack drew, by the field it came from. A shelf that stops reading
     * one of these does not look wrong in a screenshot of a parked bike - it looks wrong on a
     * ride, which is the only time it matters.
     */
    @Test
    fun everyFigureTheStatCellsDrewIsStillOnThePage() {
        val page = livePage()
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
        val page = livePage()
        assertFalse(
            "the Live page draws a UNIT shelf again",
            Regex("title = \"UNIT\"").containsMatchIn(page),
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
     * The trip ribbon's elapsed figure is the app's clock, and it has to keep running between
     * the unit's one-frame-a-second updates. The ribbon is a third of the top shelf now, so the
     * tick has to be scoped to it: a clock that recomposed the whole HUD - gauge, traces and the
     * map's update lambda with it - four times a second would spend the page's frame budget on
     * a stopwatch.
     */
    @Test
    fun theTripRibbonClockTicksBetweenFrames() {
        val page = livePage()
        assertTrue(
            "the ribbon is back on the frame clock: its elapsed figure must read its own ticking " +
                "clock (nowMs), not System.currentTimeMillis() at composition time",
            Regex("""nowMs - state\.tripStartMs""").containsMatchIn(page),
        )
        assertTrue(
            "the ribbon stopped ticking between frames - the elapsed figure would freeze " +
                "whenever the link goes quiet, which is exactly when it is the only live number",
            Regex("""delay\(TRIP_CLOCK_TICK_MS\)""").containsMatchIn(page),
        )
        assertTrue(
            "the clock no longer follows the pager's viewport flag: every page stays composed " +
                "in this pager, so a ribbon nobody can see would tick four times a second for " +
                "nobody and redraw itself all the way through a ride",
            Regex("""LaunchedEffect\(state\.recording, state\.paused, state\.tripStartMs, inView\)""")
                .containsMatchIn(page),
        )
        assertTrue(
            "the TIME figure went back to hours and minutes: without seconds the ribbon sits " +
                "on one value for a minute at a time",
            Regex("""hudDuration\(elapsedMs\)""").containsMatchIn(page),
        )
        // The owner's PAUSE (2026-11-17): a frozen stopwatch, not one that keeps climbing while
        // the file receives nothing. The paused figure has to come from the pause instant.
        assertTrue(
            "the trip clock runs through a pause again: the elapsed figure must freeze at " +
                "state.pausedAtMs, or the ribbon claims time the file never got",
            Regex("""state\.pausedAtMs - state\.tripStartMs""").containsMatchIn(page),
        )
        assertTrue(
            "the tick no longer stops while paused - a frozen figure must not recompose the " +
                "ribbon four times a second for a clock that cannot move",
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
            "the Live page stopped drawing the live route",
            Regex("LiveRouteMap\\(\\s*route = route").containsMatchIn(page),
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
                Regex("liveTrail").containsMatchIn(page),
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
            Regex("CursorTrail\\(\\)").containsMatchIn(codeOnlyIn("service", "TripLogService.kt")),
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
        val page = livePage()
        // No continuous *figure* may reach fmt() raw any more; it has to pass through flow().
        val raw = listOf(
            "avg", "max", "odo", "volts", "temp", "trpKm", "fuelL",
            "ikml", "akml", "trpL", "tmrS", "hdop", "alt",
        ).filter { Regex("fmt\\(s\\.$it\\b").containsMatchIn(page) }
        assertTrue("these Live-page figures are back on raw 1 Hz steps: $raw", raw.isEmpty())
        // A caption is a different animal, and the rule is worth writing down because the
        // obvious edit breaks it: a shelf's body holds the gauge, the traces, the fuel rail and
        // the map's AndroidView update lambda, so a glide there would rebuild all of it at frame
        // rate to smooth one line of small dim text. Bench-measured, that was the Live page's
        // entire frame budget. So the figures flow in their own leaves, the notes step.
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
        // The maintainer asked for the gauge to stay when the map HUD was approved: it is the
        // one element that answers "how fast" without being read. It shrank to 96 dp when the
        // page was compacted (2026-11-19), moved into the bottom shelf's single row beside the
        // power figures and the fuel rail, and lost the word over its figure the same day.
        val page = livePage()
        assertTrue(
            "HudGauge is gone from the Live page; the approved HUD keeps it and shrinks the " +
                "box instead of deleting it",
            Regex("HudGauge\\(").containsMatchIn(page),
        )
        assertTrue(
            "the gauge box drifted off its 96 dp anchor: it is sized by GaugeBox, measured so " +
                "the gauge, the power figures and the fuel rail share one row",
            Regex("private val GaugeBox = (9[0-9]|10[0-5])\\.dp").containsMatchIn(page),
        )
        val gaugeParams = codeOnlyIn("ui/components", "HudCharts.kt")
            .substringAfter("fun HudGauge(").substringBefore(") {")
        assertTrue(
            "the dial grew its word back. SPEED over a 96 dp figure is a line taken out of the " +
                "circle the number lives in, for a word the page has no use for - the dial IS the " +
                "speed (maintainer, 2026-11-19). HudGauge has no label parameter to pass, and " +
                "the page's own SPEED caps word stands over the AVG/MAX/TIMER strip, never over " +
                "the dial",
            !page.substringAfter("HudGauge(").take(320).contains("\"SPEED\"") &&
                !gaugeParams.contains("label"),
        )
    }

    /**
     * The shelves are as tight as they are legible, because the page is the map and every dp
     * of glass is a dp of road the owner cannot see (2026-11-19: "compact the UI more"). The
     * numbers below are not taste - they are the measurement the compaction was priced
     * against, and a shelf that quietly grows back to a card's padding undoes it.
     */
    @Test
    fun theShelvesStayCompensatedForTheMapTheyCover() {
        val hud = codeOnly("LiveHud.kt")
        assertTrue(
            "a shelf went back to a card's 12 dp of air - glass over a map pays less for its " +
                "padding, and this is the whole compaction in one line",
            Regex("padding\\(horizontal = ShelfPadX, vertical = ShelfPadY\\)").findAll(hud).count() >= 1,
        )
        assertTrue(
            "the shelf padding tokens disappeared",
            Regex("private val ShelfPadX = (8|9|10)\\.dp").containsMatchIn(hud) &&
                Regex("private val ShelfPadY = (4|5|6)\\.dp").containsMatchIn(hud),
        )
        assertTrue(
            "the strip figures grew past the compact size - four of them have to fit beside a " +
                "gauge, which is what the second compaction bought",
            Regex("private const val StripFigure = (1[2-5])").containsMatchIn(hud),
        )
        // The bottom shelf is ONE row: the fuel, power and speed zones side by side, the
        // gauge at the end. That is where the map came from - stacked rows became one, and
        // the 2026-11-19 reorganisation spread the single column across the shelf's width.
        val shelf = hud.substringAfter("internal fun BottomShelf").substringBefore("@Composable")
        assertTrue(
            "the bottom shelf went back to stacked rows instead of one row of glass",
            Regex("Column\\(\\s*\\n?\\s*modifier\\s*\\n?\\s*\\.hudGlass").containsMatchIn(shelf).not(),
        )
        assertTrue(
            "the bottom shelf's zones left the row beside the gauge - fuel, power and speed " +
                "each own a weighted stretch of the glass and the dial its end of it " +
                "(maintainer, 2026-11-19: \"reorganize the elements to make the LIVE UI " +
                "more readable\")",
            Regex("FuelZone\\(s, Modifier\\.weight").containsMatchIn(shelf) &&
                Regex("PowerZone\\(s, Modifier\\.weight").containsMatchIn(shelf) &&
                Regex("SpeedZone\\(s, Modifier\\.weight").containsMatchIn(shelf) &&
                Regex("HudGauge\\(").containsMatchIn(shelf),
        )
        assertTrue(
            "the old side-of-gauge row vocabulary is back next to the dial: AVG/MAX/TIMER are a " +
                "strip now, which is what let the row collapse",
            listOf("SideNum(", "SideFigure(", "SideRow(")
                .none { needle -> Regex(Regex.escape(needle)).containsMatchIn(hud) },
        )
    }

    /**
     * The rail over the map: the eye that puts the two shelves away, and the two zoom steps.
     *
     * It is glass on the page and not a widget inside the map for the same reason the pill
     * is: a Compose control over the osmdroid view receives nothing unless the map refuses
     * the DOWN under it, so the rail reports its bounds to the same list the shelves do. And
     * its marks come from the ledger: a map that zooms one level is a stepper, and a typed
     * `+` or `−` is not a glyph this app allows (`TypedPseudoGlyphTest`).
     */
    @Test
    fun theRailOverTheMapCarriesTheEyeAndTheMapLetsItThrough() {
        val hud = codeOnly("LiveHud.kt")
        val rail = hud.substringAfter("internal fun HudRail").substringBefore("// ───")
        assertTrue(
            "the rail lost its eye - the one control that can put the readings off the map, and " +
                "the only glass that stays up when they are off",
            Regex("HudGlyphLedger\\.mark\\(\"action\\.hud-visibility\"\\)").containsMatchIn(rail) &&
                Regex("onToggleHud").containsMatchIn(rail),
        )
        assertTrue(
            "the eye stopped saying which way the press goes. It is one mark for one act in two " +
                "voices (see HudIcons.Eye), so the sentence on the node and the ink are the whole " +
                "state - a glyph-only control without a description is invisible to TalkBack",
            Regex("hide the telemetry HUD").containsMatchIn(rail) &&
                Regex("show the telemetry HUD").containsMatchIn(rail) &&
                Regex("description = eyeSentence").containsMatchIn(rail) &&
                Regex("ink = if \\(hudVisible\\) HudColors\\.Cyan else HudColors\\.TextDim")
                    .containsMatchIn(rail),
        )
        assertTrue(
            "the zoom rail lost its stepper marks - they are the ledger's, because zooming a " +
                "map one level IS a step and a second pair of plus/minus shapes is the second " +
                "meaning the ledger exists to catch",
            Regex("HudGlyphLedger\\.mark\\(\"action\\.step-up\"\\)").containsMatchIn(rail) &&
                Regex("HudGlyphLedger\\.mark\\(\"action\\.step-down\"\\)").containsMatchIn(rail),
        )
        assertTrue(
            "the rail's buttons lost the sentence they are known by (a glyph-only control " +
                "without a description is invisible to TalkBack)",
            Regex("description = \"zoom the map in\"").containsMatchIn(rail) &&
                Regex("description = \"zoom the map out\"").containsMatchIn(rail),
        )
        assertTrue(
            "the rail is no longer glass: it floats over the map, so it wears the overlay " +
                "family like the shelves, not a flat card fill",
            Regex("hudGlass\\(").containsMatchIn(rail),
        )
        for (typed in listOf("\"+\"", "\"−\"", "\"-\"")) {
            assertTrue(
                "a typed $typed is being drawn as a mark - the app draws its plus and minus " +
                    "(HudIcons.Plus / HudIcons.Minus), it does not type them",
                !hud.contains(typed) && !codeOnly("LiveTab.kt").contains(typed),
            )
        }
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the rail stopped reporting its bounds: the map would take the DOWN and the " +
                "buttons would do nothing at all",
            Regex("hudRail != next\\) hudRail = next").containsMatchIn(page) &&
                Regex("listOf\\(hudRail\\) \\+ listOfNotNull\\(").containsMatchIn(page),
        )
        assertTrue(
            "the rail is offered while there is no map to zoom",
            Regex("if \\(hasPosition\\) \\{\\s*HudRail\\(").containsMatchIn(page),
        )
        assertTrue(
            "a shelf that has been put away is still refusing touches where it used to be. It " +
                "keeps its last measured bounds in state, so the refusal has to be switched off " +
                "with the shelf - otherwise a hidden shelf eats pans over ground nobody covers, " +
                "and the page can never be cleared by the eye",
            Regex("topShelf\\.takeIf \\{ hudVisible \\}").containsMatchIn(page) &&
                Regex("bottomShelf\\.takeIf \\{ hudVisible \\}").containsMatchIn(page) &&
                Regex("if \\(hudVisible && bottomShelf\\.height > 0f\\)").containsMatchIn(page),
        )
        assertTrue(
            "the eye is drawing the shelves back through a path that would also stop the " +
                "recorder, or remembering a clean map across a restart: the toggle is one " +
                "remember{} in the page, session-only by the maintainer's call (2026-11-19), and " +
                "hiding the numbers does not touch the trip file",
            Regex("var hudVisible by remember \\{ mutableStateOf\\(true\\) \\}").containsMatchIn(page) &&
                Regex("onToggleHud = \\{ hudVisible = !hudVisible \\}").containsMatchIn(page),
        )
        val mapCode = codeOnly("../map/LiveRouteMap.kt")
        assertTrue(
            "the rail's presses no longer reach the map",
            Regex("holder\\.zoom\\(map, zoomInTicks, zoomOutTicks\\)").containsMatchIn(mapCode),
        )
        assertTrue(
            "the map stopped treating the rail as a difference of counters: its update lambda " +
                "runs at frame rate while the figures glide, and re-applying the same zoom " +
                "every frame would pin the level against the owner's fingers",
            Regex("\\(zoomInTicks - zoomInSeen\\) - \\(zoomOutTicks - zoomOutSeen\\)").containsMatchIn(mapCode) &&
                Regex("if \\(steps == 0\\) return").containsMatchIn(mapCode),
        )
        assertTrue(
            "the map's zoom step jumped again. The owner asked for a smooth zoom (2026-11-19): " +
                "the travel is the holder's own, one frame at a time, over ZOOM_RAMP_MS",
            Regex("view\\.postOnAnimation\\(zoomRamp\\)").containsMatchIn(mapCode) &&
                Regex("setZoomLevel\\(zoomFrom \\+ \\(zoomTo - zoomFrom\\)").containsMatchIn(mapCode) &&
                Regex("private const val ZOOM_RAMP_MS = (2[0-9]{2}|300)L").containsMatchIn(mapCode),
        )
        assertTrue(
            "the rail started using the library's animated zoom (zoomTo/animateTo). That is a " +
                "second authority over a camera this file keeps one of, and it is also a silent " +
                "one: MapController.zoomToFixing returns false while its animator is running, so " +
                "the second and third press of a fast run do nothing at all (6.1.20 source read " +
                "2026-11-19)",
            !Regex("controller\\.zoomTo\\(").containsMatchIn(mapCode) &&
                !Regex("animateTo").containsMatchIn(mapCode),
        )
        assertTrue(
            "a press during a travel measures its new target from wherever the view happens to " +
                "stand this frame instead of from where the travel is heading - three fast " +
                "presses would be worth one and a half levels",
            Regex("if \\(zoomTo\\.isNaN\\(\\)\\) view\\.zoomLevelDouble else zoomTo").containsMatchIn(mapCode),
        )
        assertTrue(
            "the travel went back to a one-sided curve. The maintainer asked for an easy ease " +
                "(2026-11-19): an ease-out launches at full speed on the first frame, which " +
                "reads as a shove. The level now leaves and arrives gently, so the frame is " +
                "shaped by easeEase - a cubic in two mirror halves - and nothing else",
            Regex("setZoomLevel\\(zoomFrom \\+ \\(zoomTo - zoomFrom\\) \\* easeEase\\(t\\)\\)")
                .containsMatchIn(mapCode) &&
                Regex("if \\(t < 0\\.5\\) 4\\.0 \\* t \\* t \\* t else 1\\.0 - \\(-2\\.0 \\* t \\+ 2\\.0\\)\\.pow\\(3\\) / 2\\.0")
                    .containsMatchIn(mapCode) &&
                !Regex("1\\.0 - k \\* k \\* k").containsMatchIn(mapCode),
        )
    }

    /**
     * The five-minute BATT and TEMP traces went off the page on 2026-11-19 ("the battery and
     * temp graphs are useless"); the two figures stayed, and with them the thresholds they
     * colour themselves on - 11.5 V / 15.0 V and 105 °C, the same numbers the old stat cells
     * and the traces were judged against. The same day's reorganisation gave each figure a
     * fill bar riding the scale its retired trace was pinned to (battery 10-16 V, coolant
     * 0-130 °C) - a position without a chart, at the maintainer's word - so this guards the
     * figures, their thresholds, and the bars that replaced the shapes.
     */
    @Test
    fun thePowerFiguresSurvivedTheirTraces() {
        val page = livePage()
        assertTrue(
            "the battery figure left the shelf with its trace - the owner retired the graphs, " +
                "not the reading",
            Regex("num = s\\.volts").containsMatchIn(page),
        )
        assertTrue("the coolant figure left the shelf", Regex("num = s\\.temp").containsMatchIn(page))
        assertTrue(
            "the battery figure stopped colouring itself on the 11.5 V floor and the 15.0 V ceiling",
            Regex("s\\.volts < 11\\.5 \\|\\| s\\.volts > 15\\.0").containsMatchIn(page),
        )
        assertTrue(
            "the coolant figure stopped colouring itself on the 105 °C ceiling",
            Regex("s\\.temp > 105\\.0").containsMatchIn(page),
        )
        assertTrue(
            "the battery bar lost the scale the retired trace ran on (10-16 V, never " +
                "autoscaled): on its own scale a flat 13.4 V bar is indistinguishable from a " +
                "sagging one",
            Regex("\\(s\\.volts - 10\\.0\\) / 6\\.0 \\* 100\\.0").containsMatchIn(page),
        )
        assertTrue(
            "the coolant bar lost its pinned scale (0-130 °C)",
            Regex("s\\.temp / 1\\.3").containsMatchIn(page),
        )
        assertTrue(
            "the fill bar stopped tinting with its figure: out of range the number goes red " +
                "and the bar has to say the same thing",
            Regex("if \\(s\\.volts < 11\\.5 \\|\\| s\\.volts > 15\\.0\\) HudColors\\.Red else HudColors\\.Cyan")
                .containsMatchIn(page) &&
                Regex("if \\(s\\.temp > 105\\.0\\) HudColors\\.Red else HudColors\\.Cyan")
                    .containsMatchIn(page),
        )
        assertFalse(
            "a sparkline is back on the Live page - the traces were retired, not paused",
            Regex("Sparkline\\(|TraceHeight|trends\\.values").containsMatchIn(page),
        )
        assertFalse(
            "the five-minute history window is being kept again for traces nobody draws",
            Regex("class LiveTrends").containsMatchIn(page),
        )
    }

    /**
     * The gauge's dial is the session, not a road sign: the scale is the top speed reached
     * plus a little headroom (maintainer, 2026-11-19), so the arc answers "how close am I to
     * the best today" instead of snapping between four fixed bands. The arc fill itself is
     * the live figure - the needle must never run past its own dial, so MAX and the current
     * speed both feed the scale.
     */
    @Test
    fun theGaugeDialGrowsWithTheTopSpeedReached() {
        val hud = codeOnly("LiveHud.kt")
        assertTrue(
            "the gauge went back to a fixed band (60/120/180/240): the sweep is measured " +
                "against the top speed this session has reached, with 10 km/h of headroom",
            Regex("private fun speedScale\\(s: Snapshot\\): Double = max\\(s\\.max, s\\.spd\\) \\+ 10\\.0")
                .containsMatchIn(hud),
        )
    }

    /**
     * Three things the maintainer asked the Live page for on 2026-11-19, all about the HUD
     * behaving as one instrument: the rail with the eye stands still while the shelves come
     * and go, the glass leaves through the page edges rather than blinking out, and each
     * section of glass wears a caps word so a glance can find it.
     */
    @Test
    fun theGlassSlidesTheRailStandsStillAndEverySectionIsNamed() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the rail is travelling again: it hangs off the page's Box at the vertical middle " +
                "(align(Alignment.CenterEnd)), not off the space between the shelves - hiding " +
                "the glass must not move its own handle",
            Regex("Modifier\\s*\\n?\\s*\\.align\\(Alignment\\.CenterEnd\\)").containsMatchIn(page),
        )
        assertTrue(
            "the shelves left their slide: AnimatedVisibility is on the page, and the shelves " +
                "arrive and leave through the page edges (shelfEnter / shelfExit)",
            Regex("AnimatedVisibility\\(").containsMatchIn(page) &&
                Regex("shelfEnter\\(top = true\\)").containsMatchIn(page) &&
                Regex("shelfExit\\(top = false\\)").containsMatchIn(page),
        )
        assertTrue(
            "the slide outstays the app's motion switch - with motion off the glass has to " +
                "simply be there and gone, the same promise HudEnter makes",
            Regex("if \\(HudMotion\\.enabled\\)").containsMatchIn(page) &&
                Regex("EnterTransition\\.None").containsMatchIn(page) &&
                Regex("ExitTransition\\.None").containsMatchIn(page),
        )
        assertTrue(
            "the page stopped clipping itself: the slide is a graphicsLayer translation, so " +
                "without clipToBounds the glass paints over the tab bar on its way out " +
                "instead of passing under it (maintainer, 2026-11-19: \"it should go under " +
                "the 5 icons menu bar\")",
            Regex("Modifier\\.fillMaxSize\\(\\)\\.clipToBounds\\(\\)").containsMatchIn(page),
        )
        val hud = codeOnly("LiveHud.kt")
        for (title in listOf("GNSS", "TRIP", "SPEED")) {
            assertTrue(
                "the shelf section \"$title\" lost its caps word",
                Regex("SectionTitle\\(\"$title\"\\)").containsMatchIn(hud),
            )
        }
        assertTrue(
            "the fuel zone stopped naming itself - even with the INPUT cell gone the section " +
                "title is what a glance reads, healthy or not",
            Regex("else \"FUEL\"").containsMatchIn(hud),
        )
    }

    /**
     * The INPUT/`OK` cell left the Live page on 2026-11-19 ("remove the input ok part"): a
     * healthy sender's whole message was a green OK, and a green nothing is the same
     * message. The honest half of that cell - the fault - did not go with it: the FUEL
     * title itself turns red and reads `FUEL · OPEN / SHORT / OFF`, and the amber sentence
     * under the bar still says what cannot be trusted. A future edit that deletes the fault
     * path too would make a broken sender invisible, which is how the old cell earned its
     * keep.
     */
    @Test
    fun theSenderFaultStillSpeaksAndTheOkCellIsGone() {
        val hud = codeOnly("LiveHud.kt")
        assertFalse(
            "the INPUT cell is back - the maintainer took it off the page, a green OK beside " +
                "a bar that says the same thing is a cell doing nothing",
            Regex("\"INPUT\"").containsMatchIn(hud),
        )
        assertTrue(
            "a faulty sender stopped naming itself in the fuel title",
            Regex("if \\(fault\\) \"FUEL · \\$\\{fuelStateLabel\\(s\\.fuelSt\\)\\}\"")
                .containsMatchIn(hud) &&
                Regex("color = if \\(fault\\) HudColors\\.Red else HudColors\\.TextDim")
                    .containsMatchIn(hud),
        )
        assertTrue(
            "the amber fault sentence left the page - a fault that only recolours a title is " +
                "a fault nobody learns the meaning of",
            Regex("cannot be trusted").containsMatchIn(hud),
        )
    }

    @Test
    fun everyIdleWordWearsTheNotRecordingLook() {
        val page = livePage()
        // Two words say the same thing - nothing is recording: the fresh idle pill, and the one
        // the display-only lane wears while the live page goes on moving. Both draw the red
        // hairline and wash; a pill that offers START in a link-coloured skin reads as a
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

    /**
     * The page is the map. The header, the four cards and the page scroll are gone, and this is
     * the guard that keeps a redesign from quietly becoming a card stack with a backdrop again:
     * a gallery of figures over a map is a different screen from a gallery of figures, and the
     * owner asked for the first one.
     */
    @Test
    fun thePageIsAMapNotACardStack() {
        val page = codeOnly("LiveTab.kt")
        assertFalse(
            "the Live page went back to the page's desktop header: that is a centred 26 sp " +
                "heading plus a subtitle, 85 dp measured on the bench phone, for a word the tab " +
                "bar already says",
            Regex("HudPageHeader\\(").containsMatchIn(page),
        )
        assertFalse(
            "a LiveCard came back: the four cards are the shelves now, and a card floating over " +
                "a map is a second surface competing with the glass",
            Regex("LiveCard\\(").containsMatchIn(page),
        )
        assertFalse(
            "the page started scrolling again - the window is the map, and a scroller over it " +
                "steals the drags the map exists to answer",
            Regex("verticalScroll").containsMatchIn(page),
        )
        assertFalse(
            "the old 150 dp map box is back: the map fills the page now",
            Regex("LiveMapBox").containsMatchIn(page),
        )
        assertTrue(
            "the map stopped filling its slot: a full-bleed map is the page, and a smaller box " +
                "would put the HUD over the page's ground instead of over the map",
            Regex("modifier = Modifier\\.fillMaxSize\\(\\)").containsMatchIn(page),
        )
        assertTrue(
            "the page root went back to a padded content column: the map has to reach every " +
                "edge - and the Box itself is what clips the shelf slide, so the clip lives " +
                "on this exact root and nowhere else",
            page.contains("Box(Modifier.fillMaxSize().clipToBounds().background(HudColors.Bg))"),
        )
        // The shelves are the overlay family, not the flat card one: they sit ON something.
        val hud = codeOnly("LiveHud.kt")
        assertTrue(
            "the shelves stopped being glass - two flat card fills over a moving map read as " +
                "holes cut in it, and the overlay family's shadow is what makes them float",
            Regex("hudGlass\\(").findAll(hud).count() >= 2,
        )
        assertTrue(
            "the run-state pill left the page: the shelf carries it where the header did",
            Regex("StatusChip\\(").containsMatchIn(livePage()),
        )
        // Since 2.0.7/178 the pill is also the recorder's control, and the shelf is where the
        // drive is started, paused, resumed and stopped from. This keeps it from drifting back
        // into a readout nobody can press.
        assertTrue(
            "the run-state pill went back to being a readout: a press must do what the state " +
                "says, and a three-second press must fill it red and stop the drive",
            Regex("rememberHoldToAct\\(").containsMatchIn(hud) &&
                Regex("hold = hold::progress").containsMatchIn(hud),
        )
    }

    /**
     * The map opens at the first *position*, not at the first recorded one. Since the shell keeps
     * a display-only frame lane open whenever a dashboard is linked, a drive that was never
     * started still has a trail - and a full-screen map is the page's whole point, so waiting for
     * a file would leave the page a poster until the owner pressed START. A map with no position
     * at all is not mounted: 0,0 is a place in the Gulf of Guinea, not "where the car is".
     */
    @Test
    fun theMapMountsForTheTrailNotOnlyForTheRoute() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the map went back to mounting only for an open file or a recorded route: the trail " +
                "is where the vehicle is, and it is the one input every state has",
            Regex("val hasPosition = trail\\.isNotEmpty\\(\\) \\|\\| route\\.hasTrack").containsMatchIn(page),
        )
        assertTrue(
            "the map is drawn before there is a position to draw: the placeholder has to hold " +
                "the slot until the first fix, or the tile view opens on a made-up place",
            Regex("if \\(hasPosition\\)[\\s\\S]{0,600}?LiveRouteMap\\(\\s*route").containsMatchIn(page),
        )
        for (promise in listOf("Waiting for the dashboard…", "WAITING FOR GNSS FIX", "The map opens at the first position.")) {
            assertTrue("the no-position promise \"$promise\" is gone from the page", page.contains(promise))
        }
    }

    /**
     * The touch door (bench, 2026-11-15): a Compose control painted over an osmdroid
     * `AndroidView` receives nothing at all - the platform view owns the slot's touches at the
     * ViewGroup level and an interop filter never sees a consumed DOWN. So the shelves hand
     * their bounds to the map and the map refuses a DOWN that lands inside them; that refusal is
     * what lets the recorder pill be pressed and a swipe across a shelf page the tabs. Only a
     * DOWN is refused: a pan that began on the open map keeps the whole stream.
     */
    @Test
    fun theShelvesHandTheirBoundsToTheMap() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the shelves stopped reporting their bounds - without them the map owns every touch " +
                "in its slot and the pill cannot be pressed",
            Regex("touchRefusals = refusals").containsMatchIn(page),
        )
        assertTrue(
            "the shelf bounds are no longer measured from the same root the map is placed in, so " +
                "the refusal rectangles would be offset from the glass they protect",
            Regex("boundsInRoot\\(\\)").findAll(page).count() >= 2,
        )
        val mapCode = codeOnly("../map/LiveRouteMap.kt")
        assertTrue(
            "the map lost the refusal line: a touch that starts on the HUD has to reach Compose",
            Regex("holder\\.refusesAt\\(event\\.x, event\\.y\\)").containsMatchIn(mapCode),
        )
        assertTrue(
            "the refusal stopped being DOWN-only: re-checking it on every MOVE would steal a " +
                "pan the moment it crossed a shelf's edge",
            Regex("event\\.actionMasked == MotionEvent\\.ACTION_DOWN &&").containsMatchIn(mapCode),
        )
        assertTrue(
            "the show-only refusal must stay the first door (the Trips thumbnail), before the " +
                "HUD's rectangles are consulted",
            mapCode.indexOf("if (!holder.interactive)") in 0 until mapCode.indexOf("holder.refusesAt"),
        )
        assertTrue(
            "the interactive map lost its pinch/double-tap zoom: the full-screen map is the " +
                "page, and the owner asked to be able to pan and zoom it",
            Regex("setMultiTouchControls\\(true\\)").containsMatchIn(mapCode),
        )
    }

    /**
     * RE-CENTER is drawn by the map, not by the page - a Compose pill over the map would receive
     * nothing (see [theShelvesHandTheirBoundsToTheMap]) - so the page has to tell the map how
     * much of its bottom edge the instrument shelf covers. A hard-coded inset would put the
     * label under the glass on the first font scale that grew the shelf.
     */
    @Test
    fun theRecenterPillClearsTheBottomShelf() {
        val page = codeOnly("LiveTab.kt")
        assertTrue(
            "the page stopped passing the shelf inset: RE-CENTER lands under the fuel rail",
            Regex("recenterBottom = recenterBottom").containsMatchIn(page),
        )
        assertTrue(
            "the inset stopped coming from the measured shelf - a magic number cannot follow a " +
                "font scale, a fuel fault sentence or a wrapped row",
            Regex("bottomShelf\\.height\\.toDp\\(\\)").containsMatchIn(page),
        )
        val mapCode = codeOnly("../map/LiveRouteMap.kt")
        assertTrue(
            "the map stopped applying the inset to its label",
            Regex("bottom = recenterBottom").containsMatchIn(mapCode),
        )
        assertTrue(
            "the inset's default (the Trips thumbnail's 6 dp) is gone, so every other caller " +
                "would have to know about a shelf it does not have",
            Regex("recenterBottom: Dp = 6\\.dp").containsMatchIn(mapCode),
        )
    }

    /**
     * The display lane owes no file. Since the Live page became a map it is the page's normal
     * reader: the shell starts it from the owner it just resolved, before any drive exists, and
     * the service is not allowed to demand that a route be on the maps first - that condition
     * was exactly what kept the page a poster until START. It is foreground-only, because a lane
     * nobody puts down would stream for the rest of the day.
     */
    @Test
    fun theDisplayLaneNeedsNoDrive() {
        val service = codeOnlyIn("service", "TripLogService.kt")
        assertFalse(
            "the watch lane went back to requiring a route: a display lane with no drive is the " +
                "live page's normal state now",
            Regex("hasTrack").containsMatchIn(service),
        )
        val branch = service.substringAfter("ACTION_WATCH ->").substringBefore("ACTION_STOP ->")
        assertTrue("the ACTION_WATCH branch disappeared", branch.isNotEmpty())
        assertTrue(
            "the watch lane stopped reading the caller's device key: the shell resolved an owner " +
                "a moment ago and the runtime may not have published one on a cold start",
            Regex("EXTRA_DEVICE_KEY").containsMatchIn(branch),
        )
        assertFalse(
            "the display lane went foreground: it promises nothing beyond \"the app is open\", " +
                "and an ongoing notification for a drive that is not being written is an alarm " +
                "about nothing",
            Regex("startForeground").containsMatchIn(branch),
        )
        assertFalse(
            "the display lane started holding the CPU awake",
            Regex("applyWake").containsMatchIn(branch),
        )
        assertFalse(
            "the display lane started posting a notification of its own",
            Regex("\\bnotify\\(").containsMatchIn(branch),
        )
        assertTrue(
            "watch() lost the device-key extra the shell hands it",
            Regex("fun watch\\(context: Context, deviceKey: String\\? = null\\)").containsMatchIn(service),
        )
        val activity = codeOnlyIn(".", "MainActivity.kt")
        assertFalse(
            "the app's onStart went back to asking for a finished route before it would start the " +
                "display lane",
            Regex("hasTrack").containsMatchIn(activity),
        )
        assertTrue(
            "onStart stopped starting the display lane",
            Regex("TripLogService\\.watch\\(this\\)").containsMatchIn(activity),
        )
        val shell = codeOnly("ConnectedTabs.kt")
        assertTrue(
            "the shell stopped starting the display lane from the owner it resolved, so the Live " +
                "page arrives dead and only START wakes it",
            Regex("TripLogService\\.watch\\(context, owner\\.deviceKey\\)").containsMatchIn(shell),
        )
        assertTrue(
            "a lane kept alive for the display is idle, not waiting: its press must offer START " +
                "rather than a STOP the app undoes a moment later",
            Regex("polling && !watching -> WAITING").containsMatchIn(
                codeOnlyIn("trips", "RecordingControls.kt"),
            ),
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
