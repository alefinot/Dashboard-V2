package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.trips.TripRuntimeState
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudGauge
import com.alefinot.dashboardpp.ui.components.HudGlyphLedger
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudStateChip
import com.alefinot.dashboardpp.ui.components.Sparkline
import com.alefinot.dashboardpp.ui.components.hudDuration
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudSpace
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudCard

/**
 * The gauge's box, square. The page's own gauge filled a `#live-display` panel; the
 * app drew it 200 dp tall and the Live page needed three scrolls for what the unit's
 * 3.2" screen shows at a glance. 132 dp is the largest box that still leaves the GNSS
 * card on the first screen of a 393x776 dp window - the mock that settled the layout
 * (phone-layout plan §4a) drew 148, and the last 16 dp went to the trace, which shows
 * more in the same height. `HudGauge` derives its stroke from the box (9% of the
 * shorter side), so the smaller circle is not a thinner drawing, only a smaller one.
 */
private val GaugeBox = 132.dp

/**
 * The native live view: the same data the unit's deleted web page rendered, drawn
 * natively so the app can chart and log it (trip recording plan §5). It is read-only
 * by design - configuration belongs to the settings page, so there is only ever one
 * writer of the unit's state and no second implementation of the config logic to drift.
 *
 * It reads [TripRuntime] and nothing else. That used to be an optimisation - "one
 * poller only" - and now it is the whole design: the unit pushes one frame a second on
 * its own characteristic, the recording service follows that stream, and this page renders
 * what the service published. A page that fetched for itself would be a second reader of
 * one link, which is how the HTTP version of this app needed a lane to arbitrate.
 *
 * So there is no address in the signature, and there is no fallback path. Over the old
 * transport this page had to cope with a device that answered `/api/live` but not
 * `/api/snapshot` - firmware from before that endpoint existed - which meant a second
 * data model, a "limited data" notice and a whole branch of the layout. A unit this app
 * can pair with answers `snapshot`; anything else is a link that did not come up, and the
 * state says so in one place.
 *
 * The layout is the phone card stack of the phone-layout plan (§4a), and it is a stack
 * rather than a wall of stat cells for a measured reason: the two-up cells were a 68 dp
 * pitch for two readings, so speed, fuel, GNSS and unit health together needed three
 * scrolls on the bench phone. Six cards carry the same nineteen readings in one screen,
 * because a figure with no empty cell around it costs about a third of the height - and a
 * trace answers the one question a snapshot cannot: was it always that hot?
 */
@Composable
fun LiveTab(active: Boolean) {
    var state by remember { mutableStateOf(TripRuntime.state.value) }

    // One history for the page, kept across recompositions and filled by the collector
    // below, not by a timer of its own: the flow is already the unit's one-frame-a-second
    // clock, and a second timer would be a second opinion about when a sample happened.
    val trends = remember { LiveTrends() }

    LaunchedEffect(active) {
        if (active) {
            TripRuntime.state.collect { next ->
                state = next
                val frame = next.snapshot
                if (frame != null) {
                    trends.sample(
                        android.os.SystemClock.elapsedRealtime(),
                        next.deviceKey,
                        frame,
                    )
                }
            }
        }
    }

    Column(
        Modifier.hudPage()
            // `html { background-color: #000000 }`, `body { background-color:
            // --bg-base }`: the page's ground is flat black, with no gradient and
            // no glow anywhere. Nothing is added here - the cards' translucent
            // fills do the modelling, exactly as they do on the page.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HudPage.Gutter)
            .padding(top = 6.dp, bottom = 24.dp),
    ) {
        HudEnter(modifier = Modifier.fillMaxWidth()) {
            // `.header` was the page's centred 26 px h2 with a subtitle under it - 85 dp
            // measured on the bench phone, spent on a word the tab bar has already said.
            // The run state is the half worth keeping, so: the name at the left, the chip
            // at the right, one 52 dp row.
            Row(
                Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LIVE", style = HudeSectionTitle(), color = HudColors.Cyan)
                Spacer(Modifier.width(HudSpace.Gap))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    StatusChip(state, active)
                }
            }
        }

        val snapshot = state.snapshot
        if (snapshot != null) {
            // `.card`, unmodified: rgba(22,30,46,.6), a 5% hairline, 8px corners, no
            // shadow - so the cyan on this screen comes from the gauge and the card
            // headings, exactly as the page lets its headings do the talking.
            // `pageFadeIn` per card, one stagger step apart: the stack arrives inside a
            // single page entrance.
            SpeedCard(snapshot, trends, step = 1)
            TripCard(state, snapshot, step = 2)
            PowerCard(snapshot, trends, step = 3)
            FuelCard(snapshot, step = 4)
            GnssCard(snapshot, step = 5)
        } else {
            // The page is here, the link may be up, and nothing has been
            // measured yet. Say that and nothing more: the reason a frame is
            // missing belongs to the link, and the vehicles page already shows
            // it. Two pages inventing two explanations is how "no data" used to
            // turn into a walk through four screens.
            Spacer(Modifier.height(HudSpace.Gap))
            HudEnter(modifier = Modifier.fillMaxWidth(), delayMs = HudMotion.STAGGER_MS) {
                Text(
                    "Waiting for the dashboard…",
                    color = HudColors.TextMuted,
                    style = HudeCfgHelp(),
                )
            }
        }
    }
}

// ─── The card recipe ────────────────────────────────────────────────────────────

/**
 * The `.card` every Live panel is built from: the page's fill, hairline and 8 px corners,
 * and inside it the pairing every Dashboard++ Settings card wears - a cyan caps heading, a dim
 * note to its right, a `--border-color` hairline under both, then the content.
 *
 * The settings cards use `.perf-card`'s denser fill; these are the wider `.card`, because
 * the Live page puts one card across the window and is read at arm's length rather than
 * scanned as a grid.
 */
@Composable
private fun LiveCard(
    title: String,
    step: Int,
    modifier: Modifier = Modifier,
    meta: String? = null,
    /** The card's readout mark, from the ledger (`readout.speed`, `readout.fuel`, …). */
    mark: ImageVector? = null,
    /** A mark worn by the meta note - `Warn` beside a `NO FIX`, and nothing else today. */
    metaMark: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    HudEnter(modifier = Modifier.fillMaxWidth(), delayMs = step * HudMotion.STAGGER_MS) {
        Column(
            modifier
                .fillMaxWidth()
                .padding(top = HudSpace.Gap)
                .hudCard()
                .padding(horizontal = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // D2: the header says the card twice, the way every other card does - the
                // mark the ledger owns for the reading, at the row size, in the title's own
                // cyan. It names the card; it is not pressed, so the word next to it is
                // what the a11y node reads and the picture carries no sentence of its own.
                if (mark != null) {
                    Image(
                        mark,
                        null,
                        modifier = Modifier.size(HudGlyphSize.Row.dp).padding(end = 6.dp),
                        colorFilter = ColorFilter.tint(HudColors.Cyan),
                    )
                }
                Text(title, style = HudeSectionCaps(), color = HudColors.Cyan)
                if (meta != null) {
                    Spacer(Modifier.width(HudSpace.Gap))
                    if (metaMark != null) {
                        Image(
                            metaMark,
                            null,
                            modifier = Modifier.size(HudGlyphSize.Row.dp).padding(end = 4.dp),
                            colorFilter = ColorFilter.tint(HudColors.Amber),
                        )
                    }
                    Text(
                        meta,
                        style = HudeCfgNote(),
                        color = HudColors.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
            content()
            // 8 dp, not the page's 12: five of these stacked inside one 748 dp screen is the
            // whole difference between the GNSS card landing above the tab bar and needing a
            // scroll (measured on the bench, 2026-11-08, and still true with the UNIT card
            // gone on 2026-11-12 - the page got shorter, the gap stayed the reason it fits).
            Spacer(Modifier.height(HudSpace.Gap))
        }
    }
}

/** One label / figure pair in a card's strip. */
private data class Figure(
    val label: String,
    val value: String,
    val unit: String = "",
    val color: Color = HudColors.TextPrimary,
)

/**
 * The strip: two to four figures across the card, a `--border-color` hairline between them,
 * the label in `.section-title`'s caps at `--text-dim` and the figure in the page's mono.
 *
 * This replaced [com.alefinot.dashboardpp.ui.components.StatCell] pairs on this page. A cell
 * wore the page's 12 px of padding on all four sides of one figure and 44 px of
 * `#live-display` type: on the bench phone, two of them made a 68 dp row for two readings. A
 * strip draws the same two figures in 48 dp and fits two more beside them - which is what let
 * nineteen readings fit one screen. The figure is 17 sp rather than `.perf-value`'s 13: four
 * columns of 13 sp produced wrapped figures at the widest value each column can hold.
 */
@Composable
private fun Strip(vararg figures: Figure) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        figures.forEachIndexed { i, f ->
            if (i > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(HudColors.CardHairline))
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    f.label,
                    style = HudeSectionCaps(),
                    color = HudColors.TextDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                FigureText(f.value, f.unit, f.color)
            }
        }
    }
}

/** A mono figure with its unit in dim ink at a size that does not compete with it. */
@Composable
private fun FigureText(
    value: String,
    unit: String,
    color: Color,
    size: Int = 17,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.W600)) { append(value) }
            if (unit.isNotEmpty()) {
                withStyle(SpanStyle(color = HudColors.TextDim, fontSize = 10.sp)) { append(" $unit") }
            }
        },
        style = HudePerfValue(),
        fontSize = size.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        textAlign = align,
    )
}

// ─── The cards ─────────────────────────────────────────────────────────────────

/**
 * Speed, and the five minutes that led to it.
 *
 * The round gauge stayed, because it is the one element here that answers "how fast am I
 * going" without being read - the question a phone at the handlebars exists to answer - and
 * the figures it cannot carry (AVG, MAX, the acceleration timer) sit beside it rather than
 * under it, so the row costs the gauge's height and nothing else. The trace runs under both
 * because a trace only reads at the width of the card.
 */
@Composable
private fun SpeedCard(s: Snapshot, trends: LiveTrends, step: Int) {
    val trace = trends.values("spd")
    val peak = trace.maxOrNull()
    LiveCard(
        title = "SPEED",
        step = step,
        mark = HudGlyphLedger.mark("readout.speed"),
        meta = if (peak == null) {
            "${trends.windowLabel()} · collecting"
        } else {
            "${trends.windowLabel()} · peak ${fmt(peak, 0)} · ${trace.size} pts"
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudGauge(
                value = s.spd,
                fullScale = speedScale(s),
                label = "SPEED",
                unit = "km/h · ${sourceLabel(s.src)}",
                modifier = Modifier.size(GaugeBox),
                accent = if (s.spd > 0.0) HudColors.Cyan else HudColors.CyanDim,
            )
            Column(Modifier.weight(1f).padding(start = HudSpace.Tile)) {
                SideFigure("AVG", fmt(s.avg, 1), "km/h")
                SideFigure("MAX", fmt(s.max, 1), "km/h")
                SideFigure(
                    "TIMER",
                    if (s.tmrSt == 0) "READY" else fmt(s.tmrS, 2),
                    if (s.tmrSt == 0) "" else "s",
                    color = when (s.tmrSt) {
                        1 -> HudColors.Amber
                        2 -> HudColors.Green
                        else -> HudColors.TextPrimary
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        TraceOr(
            values = trace,
            height = 44.dp,
            color = HudColors.Cyan,
            empty = "one point lands every ${trends.everyMs / 1000L} s - the trace starts at two",
        )
    }
}

/** AVG / MAX / TIMER beside the gauge: the page's `.perf-row` pair, turned right. */
@Composable
private fun SideFigure(
    label: String,
    value: String,
    unit: String = "",
    color: Color = HudColors.TextPrimary,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = HudeSectionCaps(), color = HudColors.TextDim, fontSize = 10.sp)
        Spacer(Modifier.width(HudSpace.Gap))
        FigureText(
            value,
            unit,
            color,
            modifier = Modifier.weight(1f),
            align = TextAlign.End,
        )
    }
}

/**
 * A sparkline, or, before it can be drawn, a line saying what it is waiting for.
 *
 * [fill] is a parameter rather than always-on because of what it does to a bounded trace: a
 * steady 13.4 V line on a 10-16 V scale has an area under it that is most of the box, and filled,
 * that row became a solid green slab with a hairline on top (seen on the bench, 2026-11-08). An
 * unbounded scale hides that, because the trace always touches the bottom of its own box.
 */
@Composable
private fun TraceOr(
    values: List<Double>,
    height: androidx.compose.ui.unit.Dp,
    color: Color,
    empty: String,
    domain: ClosedFloatingPointRange<Double>? = null,
    mark: Double? = null,
    fill: Color? = color,
) {
    if (values.size > 1) {
        Sparkline(
            values = values,
            modifier = Modifier.fillMaxWidth().height(height),
            line = color,
            fill = fill,
            strokeWidth = 1.5f,
            domain = domain,
            mark = mark,
        )
    } else {
        // An empty graph reads as a bug, not as a fact. Say what fills it and how long
        // that takes, in the space the graph would have taken.
        Box(
            Modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(empty, style = HudeCfgNote(), color = HudColors.TextDim)
        }
    }
}

/**
 * The run this phone is recording.
 *
 * TIME is the app's clock, not the unit's: the recorder opened the file at `tripStartMs`, so
 * that is the only honest start of an elapsed figure, and it keeps running while a stale
 * frame holds every other column still.
 */
@Composable
private fun TripCard(state: TripRuntimeState, s: Snapshot, step: Int) {
    val elapsedMs = System.currentTimeMillis() - state.tripStartMs
    LiveCard(
        title = "THIS TRIP",
        step = step,
        mark = HudGlyphLedger.mark("readout.trip"),
        meta = if (state.recording && elapsedMs >= 0L) {
            "recording ${hudDuration(elapsedMs)}"
        } else {
            when {
                state.lastOkMs == 0L -> "connecting"
                state.stale -> "no data"
                else -> "not recording"
            }
        },
    ) {
        Strip(
            Figure("DIST", fmt(s.trpKm, 2), "km"),
            Figure(
                "TIME",
                if (state.recording && elapsedMs >= 0L) {
                    val total = elapsedMs / 60_000L
                    String.format(java.util.Locale.US, "%d:%02d", total / 60L, total % 60L)
                } else {
                    "—"
                },
            ),
            Figure("AVG", fmt(s.avg, 1), "km/h"),
            Figure("ODO", fmt(s.odo, 0), "km"),
        )
    }
}

/**
 * Battery and coolant, each over the five minutes the page remembers.
 *
 * Both are drawn against a fixed scale with a dashed rule at the limit, not scaled to
 * themselves: the useful question about either one is "how far from where it should be, and
 * which way is it going", and a 12.4-12.7 V window stretched across the box answers neither -
 * it makes a sagging battery look flat and a cold engine look urgent. The rules are the same
 * figures the stat cells coloured themselves on (11.5 V, 105 °C), so the trace and the number
 * agree about what counts as a problem.
 */
@Composable
private fun PowerCard(s: Snapshot, trends: LiveTrends, step: Int) {
    LiveCard(
        title = "POWER · COOLANT",
        step = step,
        mark = HudGlyphLedger.mark("readout.power"),
        meta = trends.windowLabel(),
    ) {
        Spacer(Modifier.height(HudSpace.Gap))
        TrendRow(
            label = "BATT",
            values = trends.values("volts"),
            domain = 10.0..16.0,
            mark = 11.5,
            line = HudColors.Green,
            figure = fmt(s.volts, 2),
            unit = "V",
            color = if (s.volts < 11.5 || s.volts > 15.0) HudColors.Red else HudColors.TextPrimary,
            everyMs = trends.everyMs,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.CardHairline))
        TrendRow(
            label = "TEMP",
            values = trends.values("temp"),
            domain = 0.0..130.0,
            mark = 105.0,
            line = HudColors.Amber,
            figure = fmt(s.temp, 1),
            unit = "°C",
            color = if (s.temp > 105.0) HudColors.Red else HudColors.TextPrimary,
            everyMs = trends.everyMs,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** One sparkline row: a dim caps label, the trace, and the figure it is standing on. */
@Composable
private fun TrendRow(
    label: String,
    values: List<Double>,
    domain: ClosedFloatingPointRange<Double>,
    mark: Double,
    line: Color,
    figure: String,
    unit: String,
    color: Color,
    everyMs: Long,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = HudeSectionCaps(), color = HudColors.TextDim, fontSize = 10.sp)
        Spacer(Modifier.width(HudSpace.Gap))
        Box(Modifier.weight(1f)) {
            TraceOr(
                values = values,
                height = 26.dp,
                color = line,
                empty = "collecting - one point every ${everyMs / 1000L} s",
                domain = domain,
                mark = mark,
                // No area fill here: on a fixed scale a steady reading is a slab, and the slab
                // swallows both the trace and the rule it is measured against.
                fill = null,
            )
        }
        Spacer(Modifier.width(HudSpace.Gap))
        FigureText(figure, unit, color, size = 16, align = TextAlign.End)
    }
}

/**
 * Fuel: the level as a bar, everything else as one strip.
 *
 * A bar is the right shape for a sender the rider cannot calibrate from the seat - what
 * matters is roughly how much is left and which way it is going, not a third decimal. The
 * state of the input used to be its own cell; it is the card's note now, because it is a
 * sentence and not a reading.
 */
@Composable
private fun FuelCard(s: Snapshot, step: Int) {
    val fraction = (s.fuelPct.coerceIn(0, 100)) / 100f
    val barColor = when {
        s.fuelSt != 1 -> HudColors.TextDim
        s.fuelPct <= 10 -> HudColors.Red
        s.fuelPct <= 25 -> HudColors.Amber
        else -> HudColors.Cyan
    }
    LiveCard(
        title = "FUEL",
        step = step,
        mark = HudGlyphLedger.mark("readout.fuel"),
        meta = "${fmt(s.trpL, 2)} L used · ${if (s.akml > 0.0) fmt(s.akml, 1) else "—"} km/L avg",
    ) {
        Spacer(Modifier.height(HudSpace.Gap))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(HudColors.Track, HudRadius.PillShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(barColor, HudRadius.PillShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Strip(
            Figure("LEVEL", "${s.fuelPct}", "%", color = barColor),
            Figure("TANK", fmt(s.fuelL, 2), "L"),
            Figure("INST", if (s.ikml > 0.0) fmt(s.ikml, 1) else "—", "km/L"),
            Figure(
                "INPUT",
                fuelStateLabel(s.fuelSt),
                color = when (s.fuelSt) {
                    1 -> HudColors.Green
                    0 -> HudColors.TextDim
                    else -> HudColors.Red
                },
            ),
        )
        if (s.fuelSt != 1) {
            Spacer(Modifier.height(2.dp))
            Text(
                when (s.fuelSt) {
                    0 -> "Sender not wired - the unit reports no fuel input (fuel_st 0)."
                    2 -> "Fuel input open circuit - the reading cannot be trusted (fuel_st 2)."
                    3 -> "Fuel input shorted - the reading cannot be trusted (fuel_st 3)."
                    else -> "Fuel input state ${s.fuelSt} - the reading cannot be trusted."
                },
                style = HudeCfgNote(),
                color = HudColors.Amber,
            )
        }
    }
}

/**
 * The GNSS fix, and the position it produced.
 *
 * HDOP and altitude arrive as -1 when the fix did not carry them, which is the firmware
 * declining to invent a value; the figure says so in the same dim ink rather than drawing
 * "0.0", which would read as a superb fix.
 */
@Composable
private fun GnssCard(s: Snapshot, step: Int) {
    LiveCard(
        title = "GNSS",
        step = step,
        mark = HudGlyphLedger.mark("readout.gnss"),
        meta = if (s.hasPosition) "${fmt(s.lat, 5)}, ${fmt(s.lon, 5)}" else "NO FIX",
        // The declined position is a warning the page used to spell in three letters; the
        // ledger already owns the mark for a stale/absent reading, so it wears that.
        metaMark = if (s.hasPosition) null else HudGlyphLedger.mark("readout.stale"),
    ) {
        Strip(
            Figure(
                "FIX",
                if (s.fix == 1) "3D" else "NO",
                color = if (s.fix == 1) HudColors.Green else HudColors.Amber,
            ),
            Figure("SATS", s.sats.toString(), color = if (s.fix == 1) HudColors.Green else HudColors.Amber),
            Figure("HDOP", if (s.hdop >= 0) fmt(s.hdop, 1) else "—"),
            Figure("ALT", if (s.alt >= 0) fmt(s.alt, 0) else "—", if (s.alt >= 0) "m" else ""),
        )
    }
}

// ─── History ────────────────────────────────────────────────────────────────────

/**
 * The five minutes the Live page draws, one bounded window per figure.
 *
 * A single frame cannot show a coolant temperature creeping up or a battery that sags only
 * under load, and the unit keeps no history of its own - so the app holds the window. It is
 * sampled every [everyMs] rather than every frame: sixty 5 s points span five minutes and
 * hold sixty boxes per figure, where one point a second needs three hundred for the same
 * span, and three hundred segments across a 341 dp card is more line than the screen has
 * pixels.
 *
 * The window is capped, so the cost does not grow with the length of a ride, and a change of
 * dashboard clears it: the previous unit's warm-up is not this unit's history.
 */
class LiveTrends(val cap: Int = 60, val everyMs: Long = 5_000L) {

    private val extractors: Map<String, (Snapshot) -> Double> = linkedMapOf(
        "spd" to { it.spd },
        "volts" to { it.volts },
        "temp" to { it.temp },
    )

    private val windows: Map<String, ArrayDeque<Double>> =
        extractors.keys.associateWith { ArrayDeque<Double>(cap.coerceAtLeast(1)) }

    private var lastSampleMs = Long.MIN_VALUE
    private var deviceKey: String? = null

    /**
     * Records one point per figure, at most once per [everyMs]. Returns true when this frame
     * was far enough past the last one to be stored - the page draws the same either way, but
     * a test can assert the cadence instead of a wall clock.
     */
    fun sample(nowMs: Long, unitKey: String?, s: Snapshot): Boolean {
        if (unitKey != deviceKey) {
            deviceKey = unitKey
            clear()
        }
        if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs < everyMs) return false
        lastSampleMs = nowMs
        for ((name, extract) in extractors) {
            val window = windows[name] ?: continue
            window.addLast(extract(s))
            while (window.size > cap) window.removeFirst()
        }
        return true
    }

    /** Oldest first, so a trace's right edge is now. Empty before the first sample. */
    fun values(name: String): List<Double> = windows[name]?.toList() ?: emptyList()

    /** The span the window covers, as a card note wants it ("5 min"). */
    fun windowLabel(): String {
        val seconds = cap.toLong() * everyMs / 1000L
        return if (seconds % 60L == 0L) "${seconds / 60L} min" else "${seconds} s"
    }

    fun clear() {
        windows.values.forEach { it.clear() }
        lastSampleMs = Long.MIN_VALUE
    }
}

// ─── Formatting and state labels ────────────────────────────────────────────────

/** Gauge scale: quiet drive gets 60, highway gets 180, anything past that gets 240. */
private fun speedScale(s: Snapshot): Double = when {
    s.max > 200.0 || s.spd > 200.0 -> 240.0
    s.max > 120.0 || s.spd > 120.0 -> 180.0
    s.max > 60.0 || s.spd > 60.0 -> 120.0
    else -> 60.0
}

private fun sourceLabel(src: Int): String = when (src) {
    0 -> "HALL"
    1 -> "GPS"
    2 -> "FUSED"
    else -> "SRC$src"
}

private fun fuelStateLabel(st: Int): String = when (st) {
    0 -> "OFF"
    1 -> "OK"
    2 -> "OPEN"
    3 -> "SHORT"
    else -> "ST$st"
}

private fun fmt(value: Double, places: Int = 1): String =
    String.format(java.util.Locale.US, "%.${places}f", value)

/**
 * The run state as one [HudStateChip] pill: the mark the ledger owns for the state,
 * its word, and its ink. The mapping lives here, next to the state it reads; the
 * pill itself - the `.ip-banner` shape, the `.msg-*` tint, the breath while
 * recording - is the shared component the icon plan made of it (P8), so any other
 * page with a run state says it in the same words.
 */
@Composable
private fun StatusChip(state: TripRuntimeState, active: Boolean) {
    when {
        state.recording -> HudStateChip(
            "RECORDING", HudGlyphLedger.mark("readout.recording"), HudColors.Green,
            pulse = true, active = active,
        )
        // Nothing has come back yet on this connection - that is not the same
        // claim as "the dashboard stopped answering", which is what NO DATA means.
        // It is "the link is not up yet", and that is the link-off mark, in dim.
        state.lastOkMs == 0L -> HudStateChip(
            "CONNECTING", HudGlyphLedger.mark("link.off"), HudColors.TextDim,
            active = active,
        )
        state.polling && state.stale -> HudStateChip(
            "NO DATA", HudGlyphLedger.mark("readout.stale"), HudColors.Red,
            active = active,
        )
        state.polling -> HudStateChip(
            "LOGGING IDLE", HudGlyphLedger.mark("link.on"), HudColors.Cyan,
            active = active,
        )
        else -> HudStateChip(
            // The recorder's own state in the not-running ink: the same mark, so
            // a dim pill cannot claim a second meaning for the dot.
            "NOT RECORDING", HudGlyphLedger.mark("readout.recording"), HudColors.TextDim,
            active = active,
        )
    }
}
