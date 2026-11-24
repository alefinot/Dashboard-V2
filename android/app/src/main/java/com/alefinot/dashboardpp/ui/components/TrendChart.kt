package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.trips.EnvelopeSink
import com.alefinot.dashboardpp.trips.TrendChannel
import com.alefinot.dashboardpp.trips.trendEnvelope
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.hudCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The trip page's trend chart: one zoomable, pannable plot of speed, altitude or economy against the
 * drive's own clock (`Implementation plans/TRIP-FIGURES-AND-ZOOMABLE-CHARTS-2026-11-24.md` §4).
 *
 * It replaces three 96 dp sparklines the maintainer rejected on the bench: *"the graphs are useless
 * without reference points"*. A shape with no axis tells you neither *when* something happened nor
 * *what* it reached. So every mark in here is a reference point — labelled y gridlines that refit to
 * the visible window, time labels on nice time steps, the whole-drive average as a dashed rule that
 * does **not** move when you zoom, a cursor that reads out the exact instant and figure you tapped,
 * and yellow ticks on the speed channel marking where the drive's 0-50 launches began, in the same
 * ink the map draws those same runs in.
 *
 * **Not a charting library.** Like the route map, this paints on one `Canvas` with the app's own
 * tokens; the arithmetic that decides *where the labels go* is in [ChartScale.kt], where a test can
 * hold it, and nothing new enters `dependencies`.
 *
 * **The data is reduced per view, not per file.** [TrendChannel] holds the drive's samples whole;
 * [trendEnvelope] collapses only the samples inside the current window into at most one bucket per
 * pixel, keeping each bucket's low, high and mean. That is what makes zooming honest — there is
 * always something finer underneath — and what keeps a spike, or an altitude dip, on the screen at
 * any zoom level instead of being averaged away.
 */

/** How tall the plot is, axis band included: the specced 200 dp (§4, outcome 4). */
private val PLOT_HEIGHT = 200.dp

/** The gutter the y labels live in. The plot never draws under it. */
private val GUTTER = 44.dp

/** The band under the plot that carries the time labels. */
private val AXIS_BAND = 18.dp

/** Label counts, as the plan specced them (outcome 4). */
private const val Y_TICKS = 4
private const val X_TICKS = 4

/** Half the height a label is given, so a label can be centred on the line it belongs to. */
private val LABEL_HALF_DP = 7.dp

/** The width a clock label is laid out in: long enough for `HH:mm:ss`, and the clamp's ruler too. */
private val TIME_LABEL_DP = 52.dp

/** Air two clock labels must keep between them, or they read as one figure (`15:3216:20`). */
private val LABEL_GAP = 12.dp

/**
 * One plottable quantity of a drive, as the trip page builds it: the samples, the words the axis
 * needs, and the whole-drive figures the header states and the dashed rule marks.
 *
 * @param digits how many decimals the readout uses for this quantity
 * @param avg the whole drive's mean of **this channel's own samples**, so the dashed rule compares
 *   like with like instead of quoting a figure derived some other way
 * @param peak the whole drive's maximum of the same samples
 * @param runStarts seconds of the drive at which an acceleration run began; only speed has them
 */
data class Channel(
    val label: String,
    val unit: String,
    val digits: Int,
    val series: TrendChannel,
    val avg: Double,
    val peak: Double,
    val runStarts: List<Int> = emptyList(),
)

/**
 * Where the chart is looking: the selected channel, the visible window, and where the cursor stands.
 *
 * The page holds exactly **one** of these per opened drive and hands it to the chips and to the plot,
 * because the two must never disagree about which minute of the drive you are standing in. Switching
 * SPEED → ALTITUDE keeps the window: that continuity is the whole point of putting a time axis under
 * the trace.
 *
 * Window bounds are whole seconds from the drive's start, half-open `[fromSec, toSec)` — which is
 * what a [TrendChannel] is indexed by, so a pan never re-derives anything from a timestamp.
 */
class TrendViewState {
    /** Selected index into the channel list. */
    var channel by mutableIntStateOf(0)

    /** The drive's length in whole seconds; 0 until its samples arrive. */
    var endSec by mutableIntStateOf(0)

    var fromSec by mutableIntStateOf(0)
    var toSec by mutableIntStateOf(1)

    /** The instant the owner tapped, or -1 for no cursor. */
    var cursorSec by mutableIntStateOf(-1)

    val spanSec: Int get() = (toSec - fromSec).coerceAtLeast(1)

    /** True while the plot is not showing the whole drive — which is when a refit is worth offering. */
    val isZoomed: Boolean get() = endSec > 0 && (fromSec > 0 || toSec < endSec)

    /** The drive's samples arrived: the window opens on the whole of it. */
    fun openAt(spanSec: Int) {
        endSec = spanSec.coerceAtLeast(1)
        fit()
    }

    /** Back to the whole drive, cursor put away (double-tap and the ⟲ glyph both mean this). */
    fun fit() {
        fromSec = 0
        toSec = endSec
        cursorSec = -1
    }

    /**
     * Slide the window by [deltaSec] seconds — which may be fractional, because at a ten-second
     * window a phone's plot is ~400 px across a 3600 s drive and a one-pixel drag is under a
     * hundredth of a second. Whole seconds are moved as they accumulate, so a slow drag still creeps
     * instead of sticking at zero.
     */
    fun panBy(deltaSec: Float) {
        if (endSec <= 0 || !deltaSec.isFinite()) return
        panDebt += deltaSec
        val whole = panDebt.toInt()
        if (whole == 0) return
        panDebt -= whole
        // The width is read **before** `fromSec` moves. `spanSec` is derived from the two bounds, so a
        // second pan step would otherwise see the window it just narrowed and paint a chart that
        // collapses while the finger is still down (held by TrendViewStateTest).
        val span = spanSec
        val last = (endSec - span).coerceAtLeast(0)
        val moved = (fromSec + whole).coerceIn(0, last)
        fromSec = moved
        toSec = moved + span
    }

    /**
     * Scale the window about [focusSec], which stays under the fingers.
     *
     * Clamped both ways, per the contract in §5: never wider than the drive (zooming out must not
     * invent time the car did not log) and never narrower than [MIN_WINDOW_SEC], so a committed pinch
     * cannot park on an empty sliver where there is nothing left to resolve.
     */
    fun zoomBy(factor: Float, focusSec: Int) {
        if (endSec <= 0 || !factor.isFinite() || factor <= 0f) return
        val span = (spanSec / factor).roundToInt()
            .coerceIn(min(MIN_WINDOW_SEC, endSec), endSec)
        if (span == spanSec) return
        val ratio = (focusSec - fromSec).toFloat() / spanSec
        val start = (focusSec - (span * ratio).roundToInt())
            .coerceIn(0, (endSec - span).coerceAtLeast(0))
        fromSec = start
        toSec = start + span
        panDebt = 0f
    }

    /** The second of the drive a pointer's x stands on. */
    fun secAt(x: Float, gutterPx: Float, canvasWidth: Int): Int {
        val width = (canvasWidth - gutterPx).coerceAtLeast(1f)
        val frac = ((x - gutterPx) / width).coerceIn(0f, 1f)
        return (fromSec + (spanSec * frac).roundToInt())
            .coerceIn(fromSec, (toSec - 1).coerceAtLeast(fromSec))
    }

    /** Fractional pan debt, so sub-second drags are not thrown away (§5). */
    private var panDebt = 0f

    companion object {
        /** The finest window the chart offers. */
        const val MIN_WINDOW_SEC = 10
    }
}

/** The channel ink, by position: the trip page's own speed / altitude / economy colours. */
private val ACCENTS = listOf(HudColors.Cyan, HudColors.Amber, HudColors.Green)

private fun accentOf(index: Int): Color = ACCENTS[index % ACCENTS.size]

/**
 * The trend card: its title, the channel chips, the whole-drive figures, the window while it is not
 * the whole drive, and the plot.
 *
 * The chips are the Trips page's filter-chip treatment — `hudCard` + `HudeSectionCaps`, moving on the
 * page's `CONTROL_MS` — rather than a Material chip, which would be the one element on this page that
 * does not look like the rest of the app.
 */
@Composable
fun TrendCard(
    channels: List<Channel>,
    state: TrendViewState,
    startMs: Long,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) return
    val index = state.channel.coerceIn(0, channels.lastIndex)
    val ch = channels[index]
    val accent = accentOf(index)

    Column(
        modifier.fillMaxWidth()
            .hudCard(fill = HudColors.CardInner)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TREND", color = HudColors.Cyan, style = HudeSectionTitle(0.2f / 13f))
            Spacer(Modifier.weight(1f))
            // The refit control exists only while the window is not the drive, so a page showing
            // everything never offers an action that would change nothing.
            if (state.isZoomed) {
                HudGlyphAction(
                    glyph = HudIcons.Reset,
                    description = "Fit the whole drive",
                    size = HudGlyphSize.Row,
                    onClick = { state.fit() },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            channels.forEachIndexed { i, c ->
                ChannelChip(c.label, i == index) { state.channel = i }
            }
        }

        Spacer(Modifier.height(6.dp))
        if (ch.series.isPlottable) {
            // Peak and average of the **whole** drive, and they do not change under zoom: this is
            // the figure the dashed rule is, and the window's shape has to be readable against it.
            Text(
                "peak ${hudNum(ch.peak, ch.digits)}  ·  avg ${hudNum(ch.avg, ch.digits)} ${ch.unit}",
                color = accent,
                style = HudePerfValue(),
            )
        } else {
            // An empty channel is a real answer: a drive can log no economy at all. Said in the
            // card's own voice rather than drawn as a flat line, which reads as a measurement of 0.
            Text(
                "This drive logged no ${ch.label.lowercase(Locale.US)}.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }
        if (state.isZoomed) {
            Text(
                hudDuration(state.fromSec * 1000L) + " – " + hudDuration(state.toSec * 1000L) +
                    " of " + hudDuration(state.endSec * 1000L),
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }

        Spacer(Modifier.height(4.dp))
        TrendChartView(channels, state, startMs, Modifier.fillMaxWidth())
    }
}

/** A channel chip: the Trips filter chip's anatomy, nothing else. */
@Composable
private fun ChannelChip(label: String, on: Boolean, onSelect: () -> Unit) {
    val ms = if (HudMotion.enabled) HudMotion.CONTROL_MS else 0
    val fill by animateColorAsState(
        if (on) HudColors.Cyan.copy(alpha = 0.12f) else HudColors.CardFill,
        tween(ms, easing = HudMotion.Ease),
        label = "trendChipFill",
    )
    val edge by animateColorAsState(
        if (on) HudColors.CyanDim else HudColors.CardHairline,
        tween(ms, easing = HudMotion.Ease),
        label = "trendChipEdge",
    )
    val ink by animateColorAsState(
        if (on) HudColors.Cyan else HudColors.TextMuted,
        tween(ms, easing = HudMotion.Ease),
        label = "trendChipInk",
    )
    Box(
        Modifier
            .hudPressable(role = Role.Button, onClickLabel = "Show this channel") { onSelect() }
            .hudCard(shape = HudRadius.FieldShape, fill = fill, borderColor = edge)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = ink, style = HudeSectionCaps())
    }
}

/**
 * The plot itself: axes, envelope, average rule, run ticks, cursor — and the gestures that move the
 * window.
 */
@Composable
fun TrendChartView(
    channels: List<Channel>,
    state: TrendViewState,
    startMs: Long,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) return
    val index = state.channel.coerceIn(0, channels.lastIndex)
    val ch = channels[index]
    val accent = accentOf(index)
    val density = LocalDensity.current
    var outer by remember { mutableStateOf(IntSize.Zero) }
    val gutterPx = with(density) { GUTTER.toPx() }
    val plotW = outer.width - gutterPx
    val plotH = outer.height - with(density) { AXIS_BAND.toPx() }

    // ---- reduce what is visible: at most one bucket per pixel, native resolution below that.
    // The sink is remembered, so a pan rewrites the same arrays instead of allocating new ones; the
    // frame is the one small object this does build per step, because its tick list *is* the labels
    // the overlay below draws.
    val sink = remember { EnvelopeSink() }
    val frame = remember(ch, state.fromSec, state.toSec, plotW) {
        val filled = if (plotW > 8f && ch.series.isPlottable && state.endSec > 0) {
            trendEnvelope(ch.series, state.fromSec, state.toSec, plotW.toInt(), sink)
        } else {
            0
        }
        if (filled == 0) {
            ChartFrame(0, null)
        } else {
            var low = Float.MAX_VALUE
            var high = -Float.MAX_VALUE
            for (j in 0 until filled) {
                if (sink.hi[j] < sink.lo[j]) continue
                low = min(low, sink.lo[j])
                high = max(high, sink.hi[j])
            }
            // The average belongs on the axis even when the window is nowhere near it: that is the
            // comparison the dashed rule exists to make, and a scale that clipped it would hide it.
            if (ch.avg.isFinite()) {
                low = min(low, ch.avg.toFloat())
                high = max(high, ch.avg.toFloat())
            }
            // No pad here, deliberately: `niceScale` picks its step by counting the labels the axis
            // would draw and rounds its bounds out to it, and that rounding *is* the headroom. Padding
            // before it cost the bench build a -20 km/h gridline under a drive that never went below
            // zero, and two gridlines of dead plot on top.
            ChartFrame(filled, niceScale(low.toDouble(), high.toDouble(), Y_TICKS))
        }
    }

    // The figure the cursor stands on: the bucket mean nearest the tapped instant.
    val cursorValue = if (state.cursorSec >= 0 && frame.scale != null) {
        nearestBucket(sink, frame.filled, state.cursorSec)?.let { sink.mean[it].toDouble() }
    } else {
        null
    }

    val scale = frame.scale
    val plotHDp = with(density) { plotH.toDp() }
    val plotWDp = with(density) { plotW.toDp() }
    val widthDp = with(density) { outer.width.toDp() }
    val labelSpanPx = with(density) { (TIME_LABEL_DP + LABEL_GAP).toPx() }
    val timeMarks = remember(state.fromSec, state.toSec, plotW, gutterPx) {
        // A clock label needs its own width plus air, and the plot's width is what turns that into a
        // fraction of the window: `fitTimeTicks` drops the ones that would be laid on top of a
        // neighbour and keeps the end, which is the label the owner is actually reading.
        val stepped = timeTicks(state.fromSec, state.toSec, X_TICKS)
        if (plotW > 1f) {
            fitTimeTicks(
                stepped,
                state.fromSec,
                state.toSec,
                labelSpanPx / plotW,
                gutterPx / plotW,
            )
        } else {
            stepped
        }
    }
    val withSeconds = showSecondsOnAxis(state.spanSec)
    val clock = remember(withSeconds) {
        SimpleDateFormat(if (withSeconds) "HH:mm:ss" else "HH:mm", Locale.US)
    }

    Box(
        modifier.fillMaxWidth().height(PLOT_HEIGHT)
            .onSizeChanged { outer = it },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .chartDragZoom(state, gutterPx)
                .chartTaps(state, gutterPx),
        ) {
            val bottom = size.height - AXIS_BAND.toPx()
            val left = gutterPx
            if (scale == null) {
                drawEmptyPlot(left, size.width, bottom)
                return@Canvas
            }
            val span = state.spanSec.toFloat()
            // The two mappings every mark in this file uses. Seconds→x, value→y: nothing else.
            val xOf = { sec: Int -> left + ((sec - state.fromSec) / span) * (size.width - left) }
            val yOf = { value: Double -> scale.fraction(value) * bottom }

            drawGrid(scale, left, size.width, bottom)
            drawAverageRule(ch.avg, scale, yOf, left, size.width, bottom, accent)
            drawEnvelope(sink, frame.filled, xOf, scale, yOf, bottom, accent)
            if (ch.runStarts.isNotEmpty()) drawRunTicks(ch.runStarts, state, xOf, bottom)
            if (state.cursorSec >= 0) {
                drawCursor(xOf(state.cursorSec), bottom, cursorBucketY(sink, frame.filled, state.cursorSec, yOf))
            }
        }

        if (scale != null) {
            // ---- y labels: `Text` in the gutter rather than canvas glyphs, so they keep the page's
            // typography and stay crisp at any density. The gutter is theirs alone.
            for (v in scale.ticks) {
                val frac = scale.fraction(v)
                if (frac < 0f || frac > 1f) continue
                // Centred on its gridline — except the two at the edges, which are pushed inside so the
                // topmost label never lands on the card's header. `niceScale` rounds its bounds to the
                // step, so those two always exist.
                val y = (plotHDp * frac - LABEL_HALF_DP).coerceIn(0.dp, (plotHDp - LABEL_HALF_DP * 2).coerceAtLeast(0.dp))
                Text(
                    formatTick(v, scale.step),
                    color = HudColors.TextDim,
                    style = HudePerfLabel(),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(GUTTER - 6.dp)
                        .offset(y = y),
                )
            }
            // ---- time labels, on nice steps, at the drive's own wall clock. Each is pulled back by
            // half its own width so it sits *under* its tick rather than starting at it; the pair at
            // the edges is clamped into the plot instead of being clipped by it.
            val labelW = TIME_LABEL_DP
            for (sec in timeMarks) {
                val frac = (sec - state.fromSec).toFloat() / state.spanSec
                val x = (GUTTER + plotWDp * frac - labelW / 2).coerceIn(0.dp, (widthDp - labelW).coerceAtLeast(0.dp))
                Text(
                    clock.format(Date(startMs + sec * 1000L)),
                    color = HudColors.TextDim,
                    style = HudePerfLabel(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(labelW)
                        .offset(x = x, y = plotHDp + 3.dp),
                )
            }
        }

        // ---- the cursor's readout: this instant, this figure, in the channel's own digits, on a
        // glass chip so it stays legible over the trace. Tapping the mark puts it away.
        if (state.cursorSec >= 0 && cursorValue != null) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .background(HudColors.Panel, HudRadius.RowShape)
                    .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    clock.format(Date(startMs + state.cursorSec * 1000L)) + "  ·  " +
                        hudNum(cursorValue, ch.digits) + " " + ch.unit,
                    color = HudColors.TextPrimary,
                    style = HudePerfValue(),
                    maxLines = 1,
                )
                HudGlyphAction(
                    glyph = HudIcons.Close,
                    description = "Hide the reading",
                    size = HudGlyphSize.Row,
                    ink = HudColors.TextMuted,
                    onClick = { state.cursorSec = -1 },
                )
            }
        }
    }
}

/** What the per-view reduction produced: the buckets to draw and the scale that fits them. */
private class ChartFrame(val filled: Int, val scale: ValueScale?)

// ---------------------------------------------------------------------- gestures

/**
 * Pinch to zoom, one finger horizontally to pan.
 *
 * Living inside a scrolling column is the hard part of this, and the answer is to decide who owns the
 * gesture **before** taking it: the handler waits until horizontal movement beats both the touch slop
 * and the vertical movement so far, and the instant the finger is clearly travelling vertically it
 * leaves the gesture entirely and lets the page have it. Only then does it consume, so a fling that
 * began on the plot still scrolls the page — the maintainer rejected the alternative where a chart
 * swallows the screen it sits on. Once a pan is established the drag moves nothing but the window's
 * horizontal position (`panBy` is the only state it touches), which is the whole point of a chart that
 * is wide rather than tall.
 *
 * Zoom is anchored on the midpoint between the fingers, so the part of the drive being pinched stays
 * under them instead of sliding away.
 */
private fun Modifier.chartDragZoom(state: TrendViewState, gutterPx: Float): Modifier =
    pointerInput(state, gutterPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var lastX = down.position.x
            var lastPinch = 0f
            var panning = false
            var pinching = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val dist = (a - b).getDistance()
                    if (lastPinch > 0f && dist > 0f) {
                        val focusX = (a.x + b.x) / 2f
                        state.zoomBy(dist / lastPinch, state.secAt(focusX, gutterPx, size.width))
                        pinching = true
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }
                    lastPinch = dist
                } else {
                    val change = pressed.first()
                    val dx = change.position.x - lastX
                    val dy = change.position.y - down.position.y
                    val slop = viewConfiguration.touchSlop
                    if (!panning && !pinching) {
                        when {
                            abs(dx) > slop && abs(dx) > abs(dy) -> panning = true
                            abs(dy) > slop -> return@awaitEachGesture // the page wants this one
                        }
                    }
                    if (panning && dx != 0f) {
                        val perPx = state.spanSec / (size.width - gutterPx).coerceAtLeast(1f)
                        // Grabbing the plot and pulling it right shows earlier time. Only the window's
                        // *horizontal* position moves: `panBy` is the only state this touches, and the
                        // gesture already gave up above the moment it looked vertical.
                        state.panBy(-dx * perPx)
                        lastX = change.position.x
                        change.consume()
                    }
                }
            }
        }
    }

/** Tap puts the cursor on an instant; double-tap lets the whole drive back in. */
private fun Modifier.chartTaps(state: TrendViewState, gutterPx: Float): Modifier =
    pointerInput(state, gutterPx) {
        detectTapGestures(
            onDoubleTap = { state.fit() },
            onTap = { pos -> state.cursorSec = state.secAt(pos.x, gutterPx, size.width) },
        )
    }

// ---------------------------------------------------------------------- painting

/** A channel with nothing to draw. Said in words by the card's header line; here, an axis and nothing else. */
private fun DrawScope.drawEmptyPlot(left: Float, width: Float, bottom: Float) {
    drawLine(HudColors.Hairline, Offset(left, 0f), Offset(left, bottom), 1f)
    drawLine(HudColors.Hairline, Offset(left, bottom), Offset(width, bottom), 1f)
}

/** The labelled gridlines, plus the plot's own left edge and baseline. */
private fun DrawScope.drawGrid(scale: ValueScale, left: Float, right: Float, bottom: Float) {
    for (v in scale.ticks) {
        val y = scale.fraction(v) * bottom
        if (y < 0f || y > bottom) continue
        drawLine(HudColors.CardHairline, Offset(left, y), Offset(right, y), 1f)
    }
    drawLine(HudColors.Hairline, Offset(left, 0f), Offset(left, bottom), 1f)
    drawLine(HudColors.Hairline, Offset(left, bottom), Offset(right, bottom), 1f)
}

/**
 * The whole-drive average, dashed.
 *
 * It stays put while the window moves and the scale refits, which is the point: zoomed into two
 * minutes of a twelve-hour drive the trace fills the plot either way, and the only thing saying
 * whether those minutes were fast or slow **for this drive** is this line. Declined when the window
 * does not contain it, exactly as the Live page's sparkline rule is — a line pinned to the top edge
 * reads as "at the limit", which is the opposite of the truth.
 */
private fun DrawScope.drawAverageRule(
    avg: Double,
    scale: ValueScale,
    yOf: (Double) -> Float,
    left: Float,
    right: Float,
    bottom: Float,
    accent: Color,
) {
    if (!avg.isFinite() || avg < scale.min || avg > scale.max) return
    val y = yOf(avg)
    if (y < 0f || y > bottom) return
    drawLine(
        accent.copy(alpha = 0.55f),
        Offset(left, y),
        Offset(right, y),
        1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f),
    )
}

/**
 * The envelope: a band from each bucket's low to its high, then the trace through the means.
 *
 * The band is what keeps a spike or a dip on screen once a bucket holds more than one sample
 * (visibility standard §5, point 3); the mean line is what makes the shape readable. At or below one
 * sample per pixel the two coincide, so a zoomed-in chart **is** the raw drive, never less of it.
 *
 * Buckets that held no sample are skipped in both passes, so a window that runs past the last logged
 * second stops drawing instead of repeating the last value across the rest of the plot.
 */
private fun DrawScope.drawEnvelope(
    sink: EnvelopeSink,
    filled: Int,
    xOf: (Int) -> Float,
    scale: ValueScale,
    yOf: (Double) -> Float,
    bottom: Float,
    accent: Color,
) {
    val band = Path()
    val under = Path()
    val trace = Path()
    var started = false
    var lastX = 0f
    for (j in 0 until filled) {
        if (sink.hi[j] < sink.lo[j]) continue
        val x = xOf(sink.midTime[j])
        val yMean = yOf(sink.mean[j].toDouble())
        if (!started) {
            started = true
            lastX = x
            band.moveTo(x, yOf(sink.hi[j].toDouble()))
            under.moveTo(x, bottom)
            under.lineTo(x, yMean)
            trace.moveTo(x, yMean)
        } else {
            band.lineTo(x, yOf(sink.hi[j].toDouble()))
            under.lineTo(x, yMean)
            trace.lineTo(x, yMean)
            lastX = x
        }
    }
    if (!started) return
    for (j in filled - 1 downTo 0) {
        if (sink.hi[j] < sink.lo[j]) continue
        band.lineTo(xOf(sink.midTime[j]), yOf(sink.lo[j].toDouble()))
    }
    band.close()
    under.lineTo(lastX, bottom)
    under.close()

    // The fill under the trace first, then the extreme band over it, then the line: the eye reads the
    // mass before the hairline, and the band's job is to say "this bucket held more than one sample".
    drawPath(
        under,
        Brush.verticalGradient(
            0f to accent.copy(alpha = 0.20f),
            1f to accent.copy(alpha = 0.02f),
            startY = 0f,
            endY = bottom,
        ),
    )
    drawPath(
        band,
        Brush.verticalGradient(
            0f to accent.copy(alpha = 0.26f),
            1f to accent.copy(alpha = 0.06f),
            startY = 0f,
            endY = bottom,
        ),
    )
    drawPath(trace, accent, style = Stroke(width = 1.6f))
}

/** Where each recorded 0-50 launch began, on the time axis, in the map's run yellow. */
private fun DrawScope.drawRunTicks(
    runStarts: List<Int>,
    state: TrendViewState,
    xOf: (Int) -> Float,
    bottom: Float,
) {
    for (i in runStarts.indices) {
        val sec = runStarts[i]
        if (sec < state.fromSec || sec >= state.toSec) continue
        val x = xOf(sec)
        drawLine(HudColors.Yellow, Offset(x, bottom - 8f), Offset(x, bottom), 2f)
    }
}

/** The cursor: a hairline at the tapped instant, and a dot on the bucket it stands on. */
private fun DrawScope.drawCursor(x: Float, bottom: Float, y: Float) {
    drawLine(HudColors.HairlineActive, Offset(x, 0f), Offset(x, bottom), 1f)
    if (y >= 0f) drawCircle(HudColors.TextPrimary, radius = 3f, center = Offset(x, y))
}

// ---------------------------------------------------------------------- small helpers

/** The bucket nearest [sec], or null if nothing was filled. */
private fun nearestBucket(sink: EnvelopeSink, filled: Int, sec: Int): Int? {
    var best = -1
    var bestGap = Int.MAX_VALUE
    for (j in 0 until filled) {
        if (sink.hi[j] < sink.lo[j]) continue
        val gap = abs(sink.midTime[j] - sec)
        if (gap < bestGap) {
            bestGap = gap
            best = j
        }
    }
    return if (best < 0) null else best
}

/** The y the cursor's dot sits at: the bucket mean nearest the cursor, or -1 for none. */
private fun cursorBucketY(sink: EnvelopeSink, filled: Int, cursorSec: Int, yOf: (Double) -> Float): Float {
    val j = nearestBucket(sink, filled, cursorSec) ?: return -1f
    return yOf(sink.mean[j].toDouble())
}
