package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.trips.RecordingControlState
import com.alefinot.dashboardpp.trips.RecordingTap
import com.alefinot.dashboardpp.trips.TripRuntimeState
import com.alefinot.dashboardpp.trips.canHold
import com.alefinot.dashboardpp.trips.tap
import com.alefinot.dashboardpp.ui.components.HudGauge
import com.alefinot.dashboardpp.ui.components.HudGlyphAction
import com.alefinot.dashboardpp.ui.components.HudGlyphLedger
import com.alefinot.dashboardpp.ui.components.HudStateChip
import com.alefinot.dashboardpp.ui.components.LocalEnterActive
import com.alefinot.dashboardpp.ui.components.flow
import com.alefinot.dashboardpp.ui.components.hudDuration
import com.alefinot.dashboardpp.ui.components.rememberHoldToAct
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudSpace
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.hudGlass
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * The Live page's two frosted shelves, and every reading the four cards used to carry.
 *
 * The map is the page now (`LiveTab`), and these composables float over it. They are
 * split in two because the page has two questions: "is anything recording, and where is
 * the car" belongs at the top with the recorder's own control; "how fast, how much
 * fuel, is the engine hot" belongs at the bottom where a thumb can rest. Both shelves
 * are `hudGlass` - the page's overlay family - because they sit *on* something: the
 * flat `.card` family has no shadow for a reason, and a shadow is exactly what makes a
 * shelf read as lifted off the map.
 *
 * The one rule that keeps the map affordable is the same one the card stack had: a
 * continuous figure glides inside its own leaf (`FlowFigureText`), never in a body that
 * also draws a map. The body recomposes at the unit's 1 Hz; the leaves
 * animate at frame rate on top of it.
 *
 * The third member of the family is the [HudRail], which is glass for the same reason and
 * reports its bounds for the same reason the shelves do - and which is the one piece of glass
 * that stays up when the shelves are switched off, because a page that can hide its readings
 * has to keep the way back. All three are drawn as tight as they read: the bottom shelf is
 * one row rather than three, the strips' figures are 14 sp instead of 17, and the gauge is
 * 96 dp instead of 132 and says no word over its figure, because the owner asked for the map
 * back (2026-11-19) and every dp of glass is a dp of road not shown.
 *
 * The two shelves name their sections - GNSS and TRIP on the top one, SPEED on the bottom one
 * beside the FUEL line it already carried - so a glance can find a figure by its section
 * before it reads any of them (maintainer, 2026-11-19). The five-minute BATT and TEMP traces
 * went the same day ("the battery and temp graphs are useless"); their figures, thresholds and
 * colours stayed, and on 2026-11-20 both figures came back as fill bars on the fuel rail's own
 * shape - between the speed strip and the fuel line - so the bottom shelf reads as one
 * instrument: how fast, is the bike healthy, how much is left.
 */

/**
 * The gauge's box, square.
 *
 * 96 dp is what the shelf can pay for it now that the gauge shares its row with the power
 * figures, the fuel rail and the three speed figures instead of owning a row of its own: the
 * page is the map, and every dp of shelf is dp the owner asked for back (2026-11-19). It is
 * still the largest circle the dial can be read at with its four lines - SPEED, the figure,
 * `km/h` and the source - without the numbers touching the ring.
 */
private val GaugeBox = 96.dp

/** A shelf's inner padding. 12 dp was a card's; glass over a map pays less for its air. */
private val ShelfPadX = 10.dp
private val ShelfPadY = 6.dp

/** The figures in a compact strip: big enough to read at arm's length, small enough to fit four. */
private const val StripFigure = 14

/**
 * How often the trip ribbon reads its own clock while a recording is open.
 *
 * The unit pushes a frame a second; four reads a second keeps the seconds flipping close
 * to the real second without rebuilding the ribbon for every frame the display draws.
 */
private const val TRIP_CLOCK_TICK_MS = 250L

// ─── The shelves ───────────────────────────────────────────────────────────────

/**
 * Top shelf: the recorder's pill, the GNSS capsule and the trip ribbon.
 *
 * [s] is null only before the first frame of a connection. The pill still has to be
 * there - START is the one control a page with no numbers owes its owner - so the shelf
 * draws short until the first sample arrives instead of inventing a second set of
 * placeholders.
 *
 * It is one row of controls over one row of trip figures, at the padding glass can afford
 * rather than the 12 dp a card used to spend on its own air. That is the whole point of
 * the shelf's shape: the map is the page, so a shelf is measured in how much of it the
 * shelf does not cover (2026-11-19). Each row wears the caps word of the section it
 * belongs to - GNSS names the capsule, TRIP the ribbon (maintainer, 2026-11-19).
 */
@Composable
internal fun TopShelf(
    state: TripRuntimeState,
    active: Boolean,
    actions: TripRecordingActions,
    s: Snapshot?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .hudGlass(shape = HudRadius.CardShape, elevation = 12.dp)
            .padding(horizontal = ShelfPadX, vertical = ShelfPadY),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                StatusChip(state, active, actions)
                // The pill only prints its reason where it cannot act: no link, or a
                // firmware with no trip store. Same sentence as the card-era header.
                val control = RecordingControlState.of(
                    recording = state.recording,
                    paused = state.paused,
                    polling = state.polling,
                    watching = state.watching,
                    canRecord = actions.canRecord,
                )
                if (control == RecordingControlState.UNAVAILABLE && actions.blocked.isNotEmpty()) {
                    Spacer(Modifier.height(HudSpace.Hair))
                    Text(actions.blocked, color = HudColors.TextDim, style = HudeCfgNote())
                }
            }
            if (s != null) {
                Spacer(Modifier.width(HudSpace.Gap))
                // Wider than the pill: four figures and a position line need the room, and
                // the pill's word does not.
                GnssCapsule(s, Modifier.weight(1.4f))
            }
        }
        if (s != null) {
            Spacer(Modifier.height(HudSpace.Hair))
            SectionTitle("TRIP")
            TripRibbon(state, s)
        }
    }
}

/**
 * Bottom shelf: one row - the readings in a column on the left, the gauge on the right.
 *
 * The gauge used to own a row of the shelf and the traces another; that is two rows of
 * glass where one column of figures beside the dial does the same job for a third less of
 * the map. The order inside the left column is the order the questions arrive in: how fast
 * have I been going, is the bike healthy, how much fuel is left. The five-minute traces
 * under BATT and TEMP are gone (maintainer, 2026-11-19: "the battery and temp graphs are
 * useless") - and on 2026-11-20 the two figures came back as fill bars in the fuel rail's
 * own shape, between the speed strip and the fuel line: label, figure, bar. Their scales are
 * the ones the retired traces were pinned to (10-16 V, 0-130 °C) and their thresholds are
 * the ones the figures always coloured themselves on (11.5 V / 15.0 V, 105 °C).
 *
 * The FUEL line keeps its label, its bar and the litres; the percentage moved down into the
 * strip's last cell, where the INPUT `OK` cell used to stand (maintainer, 2026-11-20). One
 * percentage on the shelf, in the place a healthy sender was the only word.
 */
@Composable
internal fun BottomShelf(
    s: Snapshot,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .hudGlass(shape = HudRadius.CardShape, elevation = 12.dp)
            .padding(horizontal = ShelfPadX, vertical = ShelfPadY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionTitle("SPEED")
            SpeedStrip(s)
            PowerBar(
                label = "BATT",
                num = s.volts,
                places = 2,
                unit = "V",
                // 10-16 V: the scale the retired trace was pinned to, never autoscaled.
                percent = (s.volts - 10.0) / 6.0 * 100.0,
                barColor = if (s.volts < 11.5 || s.volts > 15.0) HudColors.Red else HudColors.Cyan,
                // The colour decides on the truth, not on the gliding display figure.
                figureColor = if (s.volts < 11.5 || s.volts > 15.0) HudColors.Red else HudColors.TextPrimary,
            )
            PowerBar(
                label = "TEMP",
                num = s.temp,
                places = 1,
                unit = "°C",
                // 0-130 °C: the same promise, one scale up.
                percent = s.temp / 1.3,
                barColor = if (s.temp > 105.0) HudColors.Red else HudColors.Cyan,
                figureColor = if (s.temp > 105.0) HudColors.Red else HudColors.TextPrimary,
            )
            FuelRail(s)
        }
        Spacer(Modifier.width(HudSpace.Gap))
        Box(Modifier.width(1.dp).height(GaugeBox).background(HudColors.CardHairline))
        Spacer(Modifier.width(HudSpace.Gap))
        HudGauge(
            value = s.spd,
            fullScale = speedScale(s),
            // No label: the dial is the page's speed, and inside 96 dp the word took a line
            // of the circle it was drawn in for nothing (HudGauge, 2026-11-19).
            unit = "km/h",
            // The source is a fact about the reading, not a unit, so it keeps its line under
            // the unit rather than trailing it after a middot.
            note = sourceLabel(s.src),
            modifier = Modifier.size(GaugeBox),
            accent = if (s.spd > 0.0) HudColors.Cyan else HudColors.CyanDim,
        )
    }
}

/**
 * AVG / MAX / TIMER, the three figures that used to stand beside the gauge in a column.
 *
 * They are a strip now, because a column of three rows costs the shelf three times the
 * height a row of three cells does, and the shelf sits on the map. TIMER is the one cell
 * that is a word most of the time - `READY` before the unit has measured a launch - and
 * [Figure] already draws words and flowing numbers in the same cell.
 */
@Composable
private fun SpeedStrip(s: Snapshot) {
    Strip(
        Figure("AVG", "", "km/h", num = s.avg, places = 1),
        Figure("MAX", "", "km/h", num = s.max, places = 1),
        if (s.tmrSt == 0) {
            Figure("TIMER", "READY")
        } else {
            Figure(
                "TIMER",
                "",
                "s",
                color = when (s.tmrSt) {
                    1 -> HudColors.Amber
                    2 -> HudColors.Green
                    else -> HudColors.TextPrimary
                },
                num = s.tmrS,
                places = 2,
            )
        },
    )
}

/**
 * The page's rail over the map: the readings off and on, and the map's two stepper presses,
 * in the one place a control can live.
 *
 * It is glass on the page, not a widget in the map, for the reason the whole page is
 * arranged around: a Compose control over an osmdroid `AndroidView` receives nothing unless
 * the map refuses the touch under it (`LiveTab`'s door). So the rail reports its bounds to
 * the page like the two shelves do, and the map declines the DOWN.
 *
 * It is also the only glass that stays when the shelves are put away. A page that can hide
 * its readings has to leave the hand that brings them back somewhere visible, and the eye is
 * that hand - which is why it sits at the top of the rail, in the thumb's first reach, and
 * not under the zoom steps.
 *
 * The zoom marks are the ledger's steppers (`action.step-up` / `action.step-down`): zooming a
 * map one level is a stepper press, and drawing a second pair of plus and minus shapes for
 * a map would be the second meaning the ledger exists to catch. Typed `+` and `−` are not an
 * option at all (`TypedPseudoGlyphTest`). The top mark is `action.hud-visibility` - one mark
 * for one act in two voices, the way the pill is one mark for the recorder: cyan says the
 * glass is up and the press takes it away, dim says it is away and the press brings it back.
 * A slashed eye was not drawn, because a line crossing a line at 22 dp is a smudge and not a
 * "hidden" (see `HudIcons.Eye`), so the state rides on the ink and on the sentence the node
 * carries.
 *
 * The rail sits on the right, pinned to the vertical middle of the whole page - the one
 * place it cannot move when the shelves slide away, so the eye that hides the readings is
 * never itself hidden from the thumb that asks for them back (maintainer, 2026-11-19). A
 * thumb finds it there without standing over the car.
 */
@Composable
internal fun HudRail(
    hudVisible: Boolean,
    onToggleHud: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The one sentence the button cannot print at 22 dp, said to the node instead. It names
    // the act the press performs, not the state the page is in, so the same words that TalkBack
    // reads are the words the owner would use to ask for it.
    val eyeSentence = if (hudVisible) "hide the telemetry HUD" else "show the telemetry HUD"
    Column(
        modifier
            .hudGlass(shape = HudRadius.PillShape, elevation = 10.dp)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HudGlyphAction(
            glyph = HudGlyphLedger.mark("action.hud-visibility"),
            description = eyeSentence,
            ink = if (hudVisible) HudColors.Cyan else HudColors.TextDim,
            onClick = onToggleHud,
        )
        Box(Modifier.width(26.dp).height(1.dp).background(HudColors.CardHairline))
        HudGlyphAction(
            glyph = HudGlyphLedger.mark("action.step-up"),
            description = "zoom the map in",
            onClick = onZoomIn,
        )
        Box(Modifier.width(26.dp).height(1.dp).background(HudColors.CardHairline))
        HudGlyphAction(
            glyph = HudGlyphLedger.mark("action.step-down"),
            description = "zoom the map out",
            onClick = onZoomOut,
        )
    }
}

// ─── Top shelf pieces ──────────────────────────────────────────────────────────

/**
 * FIX / SATS / HDOP / ALT and the position, as one row of figures over one line of place.
 *
 * It was a 2×2 capsule when the shelf had a column of its own; the row of four is what the
 * map asked for back (2026-11-19). The four stay label-over-figure rather than
 * label-beside-figure because the figures are the narrower axis here: a cell is 48 dp and
 * `HDOP 1.2` sideways would not fit one.
 *
 * It reads [Snapshot.hasPosition] rather than `fix` alone: a fix without a valid
 * coordinate pair is the receiver before it has settled, and "0.00000, 0.00000" is a
 * claim about a place in the Gulf of Guinea. The firmware sends -1/0 rather than
 * inventing a position, and this capsule declines the same way.
 */
@Composable
private fun GnssCapsule(s: Snapshot, modifier: Modifier = Modifier) {
    val fixColor = if (s.fix == 1) HudColors.Green else HudColors.Amber
    Column(modifier) {
        SectionTitle("GNSS")
        Row(Modifier.fillMaxWidth()) {
            GnssWord("FIX", if (s.fix == 1) "3D" else "NO", fixColor, Modifier.weight(1f))
            GnssWord("SATS", s.sats.toString(), fixColor, Modifier.weight(1f))
            GnssNum(
                "HDOP",
                if (s.hdop >= 0) s.hdop else null,
                1,
                "",
                Modifier.weight(1f),
            )
            GnssNum(
                "ALT",
                if (s.alt >= 0) s.alt.toDouble() else null,
                0,
                "m",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(1.dp))
        Text(
            if (s.hasPosition) "${fmt(s.lat, 5)}, ${fmt(s.lon, 5)}" else "NO FIX",
            style = HudeCfgNote(),
            color = if (s.hasPosition) HudColors.TextDim else HudColors.Amber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One word cell of the GNSS capsule (FIX, SATS). */
@Composable
private fun GnssWord(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionTitle(label)
        FigureText(value, "", color, size = 13)
    }
}

/** One flowing cell of the GNSS capsule (HDOP, ALT), with the em dash for a declined value. */
@Composable
private fun GnssNum(
    label: String,
    num: Double?,
    places: Int,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionTitle(label)
        if (num != null) {
            FlowFigureText(num, places, unit, HudColors.TextPrimary, size = 13)
        } else {
            FigureText("—", unit, HudColors.TextPrimary, size = 13)
        }
    }
}

/**
 * The shelf's caps word: a section's title, or one figure's label - the page's caps role at
 * the size the compact shelves can fit. The same word is both voices on purpose: a section
 * title here (GNSS, TRIP, SPEED, FUEL) is the same act as labelling one figure in a strip,
 * and two sizes of the same caption over a map is one too many.
 */
@Composable
private fun SectionTitle(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = HudeSectionCaps(),
        color = HudColors.TextDim,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * DIST / TIME / ODO, the app's own trip clock in the middle.
 *
 * TIME is the app's clock, not the unit's: the recorder opened the file at `tripStartMs`,
 * so that is the only honest start of an elapsed figure. The ribbon keeps that clock
 * itself rather than borrowing the 1 Hz frame: a stopwatch that stops when the link goes
 * quiet - or that only moves when the minute rolls over - is not a stopwatch. The tick is
 * scoped here, so the gauge and the fuel rail are not redrawn for it.
 *
 * A pause freezes the figure where the owner pressed PAUSE ([TripRuntimeState.pausedAtMs])
 * and stops the tick: a stopwatch that keeps climbing while the file gets nothing is a lie
 * about the drive. On RESUME the clock is read from wall time again, so it jumps forward
 * by the pause - which is what the file records, because `TripSummary.durationMs` is the
 * span from the first sample to the last and the pause is a gap inside it.
 */
@Composable
private fun TripRibbon(state: TripRuntimeState, s: Snapshot) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks while the page can be seen and a file is being written. Off-screen pages stay
    // composed in this pager, and a hidden ribbon has nothing to keep current for anybody;
    // a paused drive has nothing arriving, so its clock is frozen by definition.
    val inView = LocalEnterActive.current
    LaunchedEffect(state.recording, state.paused, state.tripStartMs, inView) {
        if (!state.recording || state.paused || state.tripStartMs <= 0L || !inView) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(TRIP_CLOCK_TICK_MS)
        }
    }
    // Paused: the figure is the pause instant, not now. A pause that began before the
    // open file (the service can be paused while it waits for the first sample) has no
    // pause instant to show yet, so the clock reads zero rather than a negative span.
    val elapsedMs = when {
        state.paused -> if (state.pausedAtMs > 0L) state.pausedAtMs - state.tripStartMs else 0L
        else -> nowMs - state.tripStartMs
    }
    Strip(
        Figure("DIST", "", "km", num = s.trpKm, places = 2),
        Figure(
            "TIME",
            if (state.recording && elapsedMs >= 0L) {
                // Seconds: a figure that drops them sits on one value for a minute at a
                // time even while it ticks every second.
                hudDuration(elapsedMs)
            } else {
                "—"
            },
        ),
        // One decimal, because the unit sends two: a whole-kilometre figure sits still
        // for the better part of a minute at demo speed and reads as a dead ribbon.
        Figure("ODO", "", "km", num = s.odo, places = 1),
    )
}

// ─── Bottom shelf pieces ──────────────────────────────────────────────────────

/**
 * BATT or TEMP as the fuel rail's shape: the caps label, the figure, the reading as a fill
 * bar.
 *
 * The five-minute trace that used to sit under each of them is gone (maintainer, 2026-11-19:
 * "the battery and temp graphs are useless"); the figure stayed, and on 2026-11-20 it got a
 * bar back - not a chart, just the reading on the scale the trace was pinned to, so a glance
 * answers "what is it" and "is that a lot" in one line. The thresholds are the same numbers
 * the figures always coloured themselves on (11.5 V / 15.0 V, 105 °C). A rider who wants the
 * shape of the last five minutes gets it from the sealed trip file, where the samples live
 * whole.
 */
@Composable
private fun PowerBar(
    label: String,
    num: Double,
    places: Int,
    unit: String,
    percent: Double,
    barColor: Color,
    figureColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = HudeSectionCaps(),
            color = HudColors.TextDim,
            fontSize = 9.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(HudSpace.Hair))
        FlowFigureText(num, places, unit, figureColor, size = StripFigure)
        Spacer(Modifier.width(HudSpace.Hair))
        Box(Modifier.weight(1f)) {
            FlowBar(percent, barColor)
        }
    }
}

/**
 * Fuel: the level as a bar, everything else as one strip.
 *
 * A bar is the right shape for a sender the rider cannot calibrate from the seat - what
 * matters is roughly how much is left and which way it is going, not a third decimal. The
 * line keeps the level's bar and the tank's litres; the percentage moved into the strip's
 * last cell on 2026-11-20, where the sender's health used to print `OK` (maintainer: "remove
 * the input ok field and turn it into fuel percentage"). A broken sender is not silent: the
 * bar and the percentage go dim, and the amber sentence under the strip names the fault.
 */
@Composable
private fun FuelRail(s: Snapshot) {
    val barColor = when {
        s.fuelSt != 1 -> HudColors.TextDim
        s.fuelPct <= 10 -> HudColors.Red
        s.fuelPct <= 25 -> HudColors.Amber
        else -> HudColors.Cyan
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("FUEL", style = HudeSectionCaps(), color = HudColors.TextDim, fontSize = 9.sp)
        Spacer(Modifier.width(HudSpace.Hair))
        Box(Modifier.weight(1f)) {
            FlowBar(s.fuelPct.toDouble(), barColor)
        }
        Spacer(Modifier.width(HudSpace.Hair))
        FlowFigureText(s.fuelL, 2, "L", HudColors.TextPrimary, size = StripFigure)
    }
    Strip(
        Figure(
            "INST",
            if (s.ikml > 0.0) "" else "—",
            "km/L",
            num = s.ikml.takeIf { it > 0.0 },
            places = 1,
        ),
        Figure(
            "AVG",
            if (s.akml > 0.0) "" else "—",
            "km/L",
            num = s.akml.takeIf { it > 0.0 },
            places = 1,
        ),
        Figure("USED", "", "L", num = s.trpL, places = 2),
        Figure("PCT", "", "%", barColor, num = s.fuelPct.toDouble(), places = 0),
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

// ─── The figure vocabulary (moved from the card stack) ──────────────────────

/**
 * One label / figure pair in a strip.
 *
 * A figure is either a word (a state, a clock, a declined "—") or a number that
 * [flows][com.alefinot.dashboardpp.ui.components.flow] - the numeric form carries the raw
 * reading so the glide happens inside the number's own little composable, and a strip or a
 * bar is never dragged into frame-rate recomposition by it.
 */
private data class Figure(
    val label: String,
    val value: String,
    val unit: String = "",
    val color: Color = HudColors.TextPrimary,
    /** Not null for a flowing number; [value] is then unused. */
    val num: Double? = null,
    val places: Int = 1,
)

/**
 * The strip: two to four figures across the shelf, a hairline between them, the label in
 * the page's 9 sp caps and the figure in the page's mono at [StripFigure].
 *
 * Both numbers came down when the page became a map: a strip that used to have a card to
 * itself now shares a row with a gauge or another strip, and its job is to be legible in
 * a glance at arm's length, not to fill a box.
 */
@Composable
private fun Strip(vararg figures: Figure) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        figures.forEachIndexed { i, f ->
            if (i > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(HudColors.CardHairline))
            }
            Column(Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 3.dp)) {
                Text(
                    f.label,
                    style = HudeSectionCaps(),
                    color = HudColors.TextDim,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                if (f.num != null) {
                    FlowFigureText(f.num, f.places, f.unit, f.color, size = StripFigure)
                } else {
                    FigureText(f.value, f.unit, f.color, size = StripFigure)
                }
            }
        }
    }
}

/** The numeric face of [FigureText]: the reading glides, and only this text moves. */
@Composable
private fun FlowFigureText(
    num: Double,
    places: Int,
    unit: String,
    color: Color,
    size: Int = 17,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    FigureText(fmt(flow(num), places), unit, color, size, modifier, align)
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

/**
 * The tank as a pill, and the only part of the fuel rail that moves between frames.
 *
 * The fraction glides inside this one Box rather than in the shelf body: a `flow` at the
 * body's level would rebuild the gauge, the fuel rail and both glass shelves sixty times a
 * second for a bar's motion.
 */
@Composable
private fun FlowBar(percent: Double, color: Color) {
    val fraction = (flow(percent).coerceIn(0.0, 100.0) / 100.0).toFloat()
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .background(HudColors.Track, HudRadius.PillShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(color, HudRadius.PillShape),
        )
    }
}

// ─── Formatting and state labels ─────────────────────────────────────────────

/**
 * Gauge scale: the top speed the session has reached, plus 10 km/h of headroom (maintainer,
 * 2026-11-19). The dial is no longer one of four fixed bands - it answers "how close am I to
 * the best this vehicle has done today", and the fastest mark always sits a little inside a
 * full sweep. [Snapshot.spd] is folded into the maximum because MAX moves on the unit's own
 * cadence: a needle must never run past its own dial.
 */
private fun speedScale(s: Snapshot): Double = max(s.max, s.spd) + 10.0

private fun sourceLabel(src: Int): String = when (src) {
    0 -> "HALL"
    1 -> "GPS"
    2 -> "FUSED"
    else -> "SRC$src"
}

private fun fmt(value: Double, places: Int = 1): String =
    String.format(java.util.Locale.US, "%.${places}f", value)

// ─── The run-state pill (moved unchanged) ────────────────────────────────────

/**
 * The run state as one [HudStateChip] pill: the mark the ledger owns for the state,
 * its word, and its ink.
 *
 * The pill is also the recorder's control (2.0.7/178). A press does what the state says
 * it does - start when idle, pause while writing, resume while paused - and a three-second
 * hold fills it red and stops the drive ([rememberHoldToAct]). The mapping lives in
 * `trips/RecordingControls.kt` rather than here, because the Trips page's recording card
 * offers the same two gestures and the two pages must not drift apart.
 *
 * The states with nothing to toggle stay readouts: CONNECTING and NO DATA describe the
 * link, not the recorder, and a pill that is pressable in one state and inert in the next
 * would teach the owner that pressing it is a gamble.
 */
@Composable
private fun StatusChip(
    state: TripRuntimeState,
    active: Boolean,
    actions: TripRecordingActions,
) {
    val control = RecordingControlState.of(
        recording = state.recording,
        paused = state.paused,
        polling = state.polling,
        watching = state.watching,
        canRecord = actions.canRecord,
    )
    val tap = control.tap()
    val hold = rememberHoldToAct(
        enabled = tap != RecordingTap.NONE,
        onTap = {
            when (tap) {
                RecordingTap.START -> actions.onStart()
                RecordingTap.PAUSE -> actions.onPause()
                RecordingTap.RESUME -> actions.onResume()
                RecordingTap.STOP -> actions.onStop()
                RecordingTap.NONE -> Unit
            }
        },
        onHold = if (control.canHold()) actions.onStop else null,
        tapLabel = when (tap) {
            RecordingTap.START -> "start recording a drive"
            RecordingTap.PAUSE -> "pause recording"
            RecordingTap.RESUME -> "resume recording"
            RecordingTap.STOP -> "stop recording"
            RecordingTap.NONE -> "recording unavailable"
        },
        holdLabel = "stop recording and seal this drive",
    )
    // The pill's ink stops at 29 dp; the finger does not. The gesture lives on a wrapper with
    // a 44 dp minimum height - the smallest target a thumb can be expected to find - while
    // the pill keeps its own size and the red fill stays inside it.
    Box(
        Modifier.heightIn(min = 44.dp).then(hold.modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // A paused drive is still a recording - the file is open - so it outranks the
            // RECORDING pill rather than replacing it with a link state. Amber and no breath:
            // nothing is being written, and a pill that breathes would claim it is.
            state.paused -> HudStateChip(
                "PAUSED", HudGlyphLedger.mark("readout.paused"), HudColors.Amber,
                pulse = false, active = active,
                hold = hold::progress,
            )
            state.recording -> HudStateChip(
                "RECORDING", HudGlyphLedger.mark("readout.recording"), HudColors.Green,
                pulse = true, active = active,
                hold = hold::progress,
            )
            // Nothing has come back yet on this connection - that is not the same
            // claim as "the dashboard stopped answering", which is what NO DATA means.
            state.lastOkMs == 0L -> HudStateChip(
                "CONNECTING", HudGlyphLedger.mark("link.off"), HudColors.TextDim,
                active = active,
            )
            state.polling && state.stale -> HudStateChip(
                "NO DATA", HudGlyphLedger.mark("readout.stale"), HudColors.Red,
                active = active,
            )
            state.polling -> HudStateChip(
                // Streaming, and writing nothing: the whole pill is the warning about the
                // drive that NOT RECORDING carries - hairline, wash, word and mark all in
                // the red (2.0.7/179, the word following on 2.0.7/180 at the maintainer's
                // request). A tap here starts a drive.
                "LOGGING IDLE", HudGlyphLedger.mark("link.on"), HudColors.Red,
                active = active,
                edge = HudColors.Red,
                borderAlpha = 0.55f,
                washAlpha = 0.12f,
            )
            else -> HudStateChip(
                // The recorder's own state in the not-running ink: the same mark, so a dim
                // pill cannot claim a second meaning for the dot. The hairline and wash are
                // red (2.0.7/179): "nothing is recording" is a warning about the drive, and
                // the word stays dim because it is not a claim about the link.
                "NOT RECORDING", HudGlyphLedger.mark("readout.recording"), HudColors.TextDim,
                active = active,
                edge = HudColors.Red,
                borderAlpha = 0.55f,
                washAlpha = 0.12f,
            )
        }
    }
}
