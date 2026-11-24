package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.ui.codeOnly
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trip page's two redesigned surfaces, guarded the way [PhoneViewportTest] and
 * [SettingsPageBenchFindingsTest] guard the rest of the app — by reading the sources, because the
 * module has no Compose test harness.
 *
 * Both guards below mark something the maintainer rejected on the bench on 2026-11-24
 * (`Implementation plans/TRIP-FIGURES-AND-ZOOMABLE-CHARTS-2026-11-24.md`): nine identical `StatCell`
 * tiles, several of them reading `0.00`, and three 96 dp sparklines with no axis, no time reference
 * and no way to zoom — *"the graphs are useless without reference points and they should be zoommable
 * and should approximate while zoomed out to guarantee a certain visibility standard"*. A "refactor"
 * that quietly goes back to either shape has to fail here rather than on the phone.
 *
 * Note which file each surface actually lives in: the chips and the plot are one card, so they are
 * guarded in [chartSource]; the page is only allowed to say which channels exist and to hold the
 * window state. That split is the reason a zoom cannot make the chips and the plot disagree.
 */
class TripPageLayoutTest {

    private val moduleRoot = findAndroidDir()

    private fun screenFile(): File =
        File(moduleRoot, "app/src/main/java/com/alefinot/dashboardpp/ui/screens/TripDetailScreen.kt")

    private fun ledgerFile(): File =
        File(moduleRoot, "app/src/main/java/com/alefinot/dashboardpp/ui/screens/TripLedger.kt")

    private fun chartFile(): File =
        File(moduleRoot, "app/src/main/java/com/alefinot/dashboardpp/ui/components/TrendChart.kt")

    /** Raw text, for asserting that a word or a control **is** there. */
    private fun screenSource(): String = screenFile().readText()

    private fun ledgerSource(): String = ledgerFile().readText()

    private fun chartSource(): String = chartFile().readText()

    /**
     * Comments and string literals stripped (see [codeOnly]), for asserting a thing is **not** there:
     * these surfaces explain at length what they replaced, and prose must not be able to satisfy or
     * trip a prohibition.
     */
    private fun screenCode(): String = screenFile().codeOnly()

    private fun chartCode(): String = chartFile().codeOnly()

    private fun ledgerCode(): String = ledgerFile().codeOnly()

    private fun findAndroidDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val here = dir!!
            if (File(here, "app/src/main/java/com/alefinot/dashboardpp").isDirectory) return here
            dir = here.parentFile
        }
        error("could not locate android/ from ${System.getProperty("user.dir")}")
    }

    // ------------------------------------------------------------------ the figures

    @Test
    fun `neither figure group builds a stat tile any more`() {
        assertFalse(
            "the vehicle and log-quality figures used to be nine identical StatCell tiles — the " +
                "layout the maintainer rejected on 2026-11-24. The ledger rows replaced it.",
            Regex("StatCell\\(").containsMatchIn(screenCode()),
        )
        assertFalse("and the page does not even import it", screenSource().contains("components.StatCell"))
    }

    @Test
    fun `both groups are ledgers and all nine facts are rows`() {
        val page = screenSource()
        assertTrue("VEHICLE must be a LedgerGroup", Regex("""LedgerGroup\(\s*label = "VEHICLE"""").containsMatchIn(page))
        assertTrue("LOG QUALITY must be a LedgerGroup", Regex("""LedgerGroup\(\s*label = "LOG QUALITY"""").containsMatchIn(page))
        val rows = Regex("""LedgerRow\(\s*label = "([^"]+)"""").findAll(page).map { it.groupValues[1] }.toList()
        assertTrue("expected the nine known facts as ledger rows, got $rows", rows.containsAll(
            listOf(
                "FUEL USED", "ECONOMY", "BATTERY", "COOLANT",
                "GNSS FIX", "GNSS TRACK", "DASHBOARD++ TRIP", "LOG GAPS", "SAMPLES",
            ),
        ))
    }

    @Test
    fun `a row puts its name and its figure on one line`() {
        val ledger = ledgerSource()
        // The whole point of the ledger: a figure wrapped under its own label cannot be compared with
        // its neighbour. Both Texts are `maxLines = 1`, in one Row, and the label ellipsises instead
        // of pushing the figure off the card.
        assertTrue("one Row per row", Regex("Row\\(\\s*\\n\\s*Modifier\\.fillMaxWidth\\(\\)\\.padding").containsMatchIn(ledger))
        assertTrue("centred against the figure", ledger.contains("verticalAlignment = Alignment.CenterVertically"))
        val oneLine = Regex("maxLines = 1").findAll(ledger).count()
        assertTrue("label and figure both stay on one line, saw $oneLine", oneLine >= 3)
        assertTrue("the label yields width before the figure does", ledger.contains("Modifier.weight(1f, fill = false)"))
    }

    @Test
    fun `rows are separated by one hairline each and the bar is three dp`() {
        val ledger = ledgerSource()
        assertTrue("1 dp hairline", Regex("ROW_RULE = 1\\.dp").containsMatchIn(ledger))
        assertTrue(ledger.contains("background(HudColors.Hairline)"))
        // Outcome of the sign-off: one coverage bar, 3 dp, for the one fact that *is* a proportion.
        assertTrue("3 dp coverage bar", Regex("BAR_HEIGHT = 3\\.dp").containsMatchIn(ledger))
        assertTrue(screenSource().contains("bar = s.fixPct.toDouble()"))
    }

    @Test
    fun `the copy and the zeros the maintainer kept are still what they kept`() {
        val page = screenSource()
        // Refused veto (2026-11-24): the row stays "DASHBOARD++ TRIP". It is the unit's cumulative
        // counter, not this drive's distance, and the name was not the agent's to change.
        assertTrue(page.contains("\"DASHBOARD++ TRIP\""))
        assertFalse("the rename was refused", page.contains("ESP TRIP COUNTER"))
        // Decision 2: an unmeasured fact still reads 0.00 — no em dash standing in for a zero, no
        // "not reported" footnote, and no row hidden because the drive did not measure it. Only the
        // ink changes, and that is why the strip-to-code view is the right one to check here.
        assertFalse(page.contains("not reported"))
        assertFalse("no em dash in any figure the ledger can draw", ledgerCode().contains("\u2014"))
        assertTrue("unmeasured ink recedes", Regex("ink = if \\(").containsMatchIn(page))
    }

    // ------------------------------------------------------------------ the chart

    @Test
    fun `the page has one trend card, four channels and no sparkline of its own`() {
        val page = screenSource()
        assertTrue("one card, holding chips and plot together", page.contains("TrendCard("))
        assertFalse("the three sparkline cards are gone", page.contains("fun ChartRow("))
        assertFalse("and the page no longer borrows the Live page's sparkline", page.contains("components.Sparkline"))
        for (name in listOf("SPEED", "ALTITUDE", "ECONOMY", "COOLANT")) {
            assertTrue("channel $name", Regex("""label = "$name"""").containsMatchIn(page))
        }
        // A channel the drive did not log is not offered at all, rather than drawn flat at zero.
        val guards = Regex("if \\((speed|altitude|economy|temp)\\.isPlottable\\)").findAll(page).count()
        assertTrue("each channel is offered only if it was logged, saw $guards", guards == 4)
    }

    @Test
    fun `the window state lives once and both surfaces read the same object`() {
        val page = screenSource()
        assertTrue("one view state per opened drive",
            page.contains("remember(detail.file.absolutePath) { TrendViewState() }"))
        assertTrue("both the chips and the plot get it", page.contains("state = viewState"))
        assertTrue("it is opened on the drive's own span",
            page.contains("LaunchedEffect(spanSec) { viewState.openAt(spanSec) }"))
        // The chips are inside the card, so there is no callback left to fall out of step with.
        assertFalse("no second source of truth for the selected channel",
            page.contains("onChannel ="))
        assertFalse("and no Material chip anywhere on this page", screenCode().contains("FilterChip"))
    }

    @Test
    fun `the plot reduces the visible window and paints its own reference points`() {
        val chart = chartSource()
        // The visibility standard's mechanism: reduce per view, into a reused sink, at one bucket per
        // pixel — never a static polyline built once at read time.
        assertTrue(chart.contains("trendEnvelope("))
        assertTrue("the sink is reused between pans", chart.contains("remember { EnvelopeSink() }"))
        assertTrue("the bucket count is the plot's pixel width",
            chart.contains("trendEnvelope(ch.series, state.fromSec, state.toSec, plotW.toInt(), sink)"))
        // The reference points themselves.
        assertTrue("labelled y gridlines", chart.contains("for (v in scale.ticks)"))
        assertTrue("time labels on nice steps", chart.contains("timeTicks(state.fromSec, state.toSec"))
        // Nice steps are arithmetic and arithmetic does not know a label is 52 dp wide: the bench axis
        // drew `15:32` and `16:20` touching. The painter hands the step list to a fitter that knows.
        // The fitter is handed the painter's own geometry: the window's bounds, the room a label needs
        // and the gutter it may overhang — all as fractions of the plot, so the rule stays out of pixels.
        assertTrue("labels that would be laid on top of each other are dropped, the end kept",
            Regex("fitTimeTicks\\(\\s*stepped,\\s*state\\.fromSec,\\s*state\\.toSec,").containsMatchIn(chart))
        assertTrue("the label's room and the gutter go to the fitter",
            chart.contains("labelSpanPx / plotW,") && chart.contains("gutterPx / plotW,"))
        assertTrue("the clock, not a sample index", chart.contains("clock.format(Date(startMs + sec * 1000L))"))
        assertTrue("seconds only while they carry information", chart.contains("showSecondsOnAxis(state.spanSec)"))
        assertTrue("the whole-drive average rule", chart.contains("drawAverageRule(ch.avg"))
        assertTrue("the low/high envelope", chart.contains("drawEnvelope(sink, frame.filled"))
        assertTrue("the cursor readout", chart.contains("drawCursor(xOf(state.cursorSec)"))
    }

    @Test
    fun `the y axis refits to the window without ever clipping the average rule`() {
        val chart = chartSource()
        assertTrue("the scale follows the visible envelope", chart.contains("niceScale(low.toDouble(), high.toDouble(), Y_TICKS)"))
        // The dashed rule is the comparison the card exists to make; a window nowhere near it still
        // has to include it, or the one line the owner is measuring against disappears.
        assertTrue(chart.contains("low = min(low, ch.avg.toFloat())"))
        assertTrue(chart.contains("high = max(high, ch.avg.toFloat())"))
        // The painter used to pad the window by 8 % **before** the scale rounded out to its step, and
        // the rounding added a further step at each end: a drive of 0..94.8 km/h came back on an axis
        // of -20..120. Counting labels is `niceScale`'s job, so the painter must not pad in front of it.
        assertFalse("ad-hoc padding in front of the scale",
            Regex("niceScale\\([^)]*-").containsMatchIn(chartCode()))
    }

    @Test
    fun `the pan decides who owns the gesture before it takes one, and hands a vertical drag to the page`() {
        val chart = chartCode()
        assertTrue("a real gesture loop", chart.contains("awaitEachGesture"))
        assertTrue("nothing is taken before the slop is beaten", chart.contains("viewConfiguration.touchSlop"))
        assertTrue("the pan needs horizontal movement to beat both the slop and the vertical",
            chart.contains("abs(dx) > slop && abs(dx) > abs(dy) -> panning = true"))
        assertTrue("a vertical drag belongs to the scroll, entirely",
            chart.contains("abs(dy) > slop -> return@awaitEachGesture"))
        assertTrue("nothing is consumed until the gesture is decided",
            Regex("if \\(panning && dx != 0f\\) \\{[\\s\\S]{0,400}?change\\.consume\\(\\)").containsMatchIn(chart))
        assertTrue("the pan moves the window and nothing else", chart.contains("state.panBy(-dx * perPx)"))
        assertTrue("a second finger means zoom, not pan", chart.contains("pressed.size >= 2"))
        assertTrue("zoom is anchored on the pinch midpoint",
            chart.contains("state.zoomBy(dist / lastPinch, state.secAt(focusX, gutterPx, size.width))"))
        assertTrue("tap places the cursor", chart.contains("detectTapGestures("))
        assertTrue("double-tap refits", chart.contains("onDoubleTap = { state.fit() }"))
    }

    @Test
    fun `the plot carries no run ticks and paints nothing yellow`() {
        val chart = chartSource()
        // Maintainer, 2026-11-24: the yellow launch marks come off the speed chart. The 0-50 RUNS list
        // and the route map keep theirs — this is the plot's paint, and a test is the only thing that
        // keeps the marks from drifting back in on the next "helpful" pass.
        assertFalse("the run ticks are gone", chart.contains("drawRunTicks"))
        assertFalse("nothing on this card is the map's run yellow", chart.contains("HudColors.Yellow"))
        assertFalse("and a channel carries no run starts at all", chart.contains("runStarts"))
        // A channel with nothing to plot says so in words; a flat line at zero reads as a measurement.
        assertTrue(chart.contains("This drive logged no"))
        assertTrue(chart.contains("drawEmptyPlot(left, size.width, bottom)"))
    }

    @Test
    fun `the card keeps its size across a zoom so the plot never moves under a finger`() {
        val chart = chartSource()
        // The refit control means nothing while the plot is the whole drive, but its 48 dp touch box is
        // laid out either way: the header used to grow ~28 dp the instant a pinch began.
        assertTrue(
            "the refit's slot is reserved whether or not the control is in it",
            Regex("Box\\(Modifier\\.size\\(HudTouch\\.Min\\),[^)]*\\)\\s*\\{\\s*if \\(state\\.isZoomed\\)")
                .containsMatchIn(chart),
        )
        // Same for the window readout: one Text, empty when there is nothing to report, so the 200 dp
        // plot below it cannot gain or lose a line mid-gesture.
        assertTrue("the window line has a permanent line box",
            Regex("Text\\(\\s*text = if \\(state\\.isZoomed\\)").containsMatchIn(chart))
        assertFalse("it is not added and removed with the state",
            Regex("if \\(state\\.isZoomed\\) \\{\\s*Text\\(").containsMatchIn(chart))
    }

    @Test
    fun `the chips are the app's own treatment, not Material`() {
        val chart = chartSource()
        assertTrue(chart.contains("ChannelChip("))
        assertTrue("the Trips filter chip's anatomy", chart.contains("hudPressable(role = Role.Button"))
        // Four channels are wider than a phone card, and a clipped chip is a channel nobody can pick.
        assertTrue("the chip row scrolls instead of clipping the last chip",
            chart.contains("Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())"))
        assertTrue("so it is the Trips row's scroll, not a Material scroller",
            chartCode().contains("import androidx.compose.foundation.horizontalScroll"))
        assertTrue(chart.contains("animateColorAsState"))
        assertTrue("and the page's own easing", chart.contains("tween(ms, easing = HudMotion.Ease)"))
        assertFalse(chartCode().contains("FilterChip"))
    }

    @Test
    fun `no charting library is anywhere near this`() {
        // The plan's hard constraint: hand-painted Canvas like the route map, nothing new in
        // `dependencies`. Reading the imports is how that stays true after the next person arrives.
        for (file in listOf(chartFile(), ledgerFile())) {
            val foreign = file.readLines()
                .filter { it.startsWith("import ") }
                .map { it.removePrefix("import ").trim() }
                .filterNot {
                    it.startsWith("androidx.compose") || it.startsWith("com.alefinot") ||
                        it.startsWith("java.") || it.startsWith("kotlin.")
                }
            assertTrue("${file.name} imports something that is not the platform: $foreign", foreign.isEmpty())
        }
    }
}
