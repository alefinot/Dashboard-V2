package com.alefinot.dashboardpp.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudTouch
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The fuel tank calibration table, as a table.
 *
 * The unit's own page drew this card as a grid of numbered ohm inputs — one per point —
 * under a live sender reading, with a button to refill the grid from the empty/full ohms
 * and a slot box that stored the live reading into one point (`git show
 * 7104bf96^:src/webui.html`, the `touch-table` renderer). The app first drew the same
 * table as one comma-separated line; the maintainer asked for the real thing back
 * (2026-11-20), and on the same date asked for it to *look* like a table and read better
 * than the page did. So this is that grid with the page's job done properly: a header,
 * one point per row, hairlines and zebra rows, a live reading that shows where on the
 * ramp the sender actually sits, and one capture mark per row — the slot-number box is
 * gone, since the row *is* the slot.
 *
 * It stays the *one row* the schema says it is: `fuelCalOhms` is one array setting, and
 * every edit — one point, a fill, a capture — commits the whole array through
 * `ConfigStore.edit` exactly as the comma list did. What changed is the drawing and the
 * things around it, not the write.
 *
 * The two repair rules the firmware enforces (`repairFuelTable` in `src/config.cpp`,
 * issue #19) are enforced here first, for the same reason the page did: the boxes on
 * screen should be the values the device keeps, not the values that get silently sorted
 * on the way in. Direction comes from the two ends, exactly like the firmware.
 */

/** The firmware band of one calibration point, in ohms (`constrain(v, 0.0f, 100000.0f)`). */
const val FUEL_POINT_MIN_OHM = 0.0
const val FUEL_POINT_MAX_OHM = 100000.0

/** A sender reading the unit reports but cannot calibrate with: open circuit, unplugged. */
private const val FUEL_OPEN_CIRCUIT_OHM = 9999.9

// ---------------------------------------------------------------------------
// The pure part — what the table decides, kept away from the pixels so it can
// be tested without a card (the same split SettingsLayout makes).
// ---------------------------------------------------------------------------

/**
 * The straight empty→full ramp [points] slots wide, in ohms, one decimal deep — the
 * `fillFuelTable()` of the unit's page, and the same arithmetic the firmware runs when the
 * point count changes (`fillFuelTableFromOhms`). Both ends are exact, so an N-point table
 * always starts at `emptyOhm` and ends at `fullOhm`.
 */
fun fuelRamp(points: Int, emptyOhm: Double, fullOhm: Double): List<Double> {
    if (points < 2) return emptyList()
    return List(points) { i ->
        roundFuelOhm(emptyOhm + (fullOhm - emptyOhm) * i / (points - 1))
    }
}

/**
 * The table sorted back into the direction its own two ends imply, or null when it is
 * already monotonic — `repairFuelTableForm` on the page and `repairFuelTable` in the
 * firmware, in Kotlin. The result counts the points that were out of order; the caller
 * says so on screen because a table whose boxes jumped is otherwise a bug report.
 */
fun repairFuelRamp(points: List<Double>): Pair<List<Double>, Int>? {
    if (points.size < 2) return null
    val descending = points.first() >= points.last()
    val broken = points.zipWithNext().count { (a, b) -> if (descending) a < b else a > b }
    if (broken == 0) return null
    val sorted = points.sortedWith(if (descending) compareByDescending { it } else compareBy { it })
    return sorted to broken
}

/** [points] with slot [slot] holding the live reading, or null when the slot is off the table. */
fun captureIntoSlot(points: List<Double>, slot: Int, liveOhm: Double): List<Double>? {
    if (slot !in points.indices) return null
    return points.toMutableList().also { it[slot] = roundFuelOhm(liveOhm) }
}

/** A calibration point as it is typed, in the firmware's band, or null when it is not a number. */
fun parseFuelPoint(text: String): Double? =
    text.trim().toDoubleOrNull()?.coerceIn(FUEL_POINT_MIN_OHM, FUEL_POINT_MAX_OHM)

/** The precision the unit streams the array at — one decimal (both ends round the same way). */
private fun roundFuelOhm(v: Double): Double = (v * 10.0).roundToInt() / 10.0

/**
 * Where a live sender reading sits on the table: the two rows it falls between, and how
 * far along that segment it is (0.0 at [FuelBracket.rowA], 1.0 at [FuelBracket.rowB]).
 * A reading past either end is clamped to it — the sender can read outside the calibrated
 * ramp, and the gauge walks clamped too, so the table shows the same truth the gauge uses.
 * The table is assumed monotonic (the repair above guarantees that before anything is
 * drawn), in either direction. Null below two points, where there is no segment to sit in.
 */
data class FuelBracket(val rowA: Int, val rowB: Int, val fraction: Double)

fun fuelBracket(points: List<Double>, liveOhm: Double): FuelBracket? {
    if (points.size < 2) return null
    val ends = points.first() to points.last()
    val v = liveOhm.coerceIn(
        minOf(ends.first, ends.second),
        maxOf(ends.first, ends.second),
    )
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        if (v <= maxOf(a, b) || i == points.size - 2) {
            val frac = if (b != a) ((v - a) / (b - a)).coerceIn(0.0, 1.0) else 0.0
            return FuelBracket(i, i + 1, frac)
        }
    }
    return null // unreachable with size >= 2; kept so the loop's contract is total
}

/**
 * The tank percentage a live reading implies on this table, 0–100 — the same interpolation
 * the gauge does between the bracketing points, expressed against the whole ramp
 * (`row / (n-1)`), so it is exactly what the level bar draws.
 */
fun fuelLevelPercent(points: List<Double>, liveOhm: Double): Int? {
    val b = fuelBracket(points, liveOhm) ?: return null
    return ((b.rowA + b.fraction) / (points.size - 1) * 100.0).roundToInt()
}

// ---------------------------------------------------------------------------
// The pixels.
// ---------------------------------------------------------------------------

/**
 * The table card-body. Everything it reads beyond its own row — the live sender reading,
 * the point count, the empty/full ohms the fill button needs — the caller passes in, so
 * this stays a drawing of a row the store owns.
 */
@Composable
fun FuelPointTable(
    item: SchemaItem,
    view: RowView?,
    enabled: Boolean,
    /** The `FUEL_TOUCH_POINTS` value the unit holds; null until it has been read. */
    pointCount: Int?,
    emptyOhm: Double?,
    fullOhm: Double?,
    /** The live sender reading, ohms and the `fuelInputState` code; null before the first poll. */
    liveOhm: Double?,
    liveState: Int?,
    onApply: (List<Double>) -> Unit,
    onRefresh: () -> Unit,
) {
    val points = (view?.value as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toDouble() }

    // One line of news from the last press — fill refused, points sorted, capture waiting —
    // local to the table, because it is about the table and dies with it.
    var note by remember(item.key) { mutableStateOf<String?>(null) }

    /** Commit a whole array, repaired first, the way the page repaired before posting. */
    fun apply(next: List<Double>, pre: String? = null) {
        val repaired = repairFuelRamp(next)
        if (repaired != null) {
            val moved = repaired.second
            note = (pre?.let { "$it · " } ?: "") +
                "$moved point(s) were out of order — sorted ${
                    if (next.first() >= next.last()) "descending" else "ascending"
                }, as the unit does"
            onApply(repaired.first)
        } else {
            note = pre
            onApply(next)
        }
    }

    // The count row holds what was asked for; the array is what the unit still has until the
    // next read. That gap is the rebuild the warning on the table promises, so it is drawn
    // where it happens, and after the write has had its moment the table goes and asks for
    // the rebuilt ramp on its own (the page's `Refresh Table`, minus the manual part).
    val rebuiltWanted = pointCount != null && points.isNotEmpty() && pointCount != points.size
    LaunchedEffect(rebuiltWanted, enabled) {
        if (rebuiltWanted && enabled) {
            delay(1200)
            onRefresh()
        }
    }

    // What the sender is doing right now, in one block rather than in a dim note: the
    // number big, the state word under it in the state's own colour, and the level bar
    // that says where on the ramp the reading sits. An open circuit gets no bar — there
    // is no level in a wire that fell off, and drawing one would be a lie.
    val openCircuit = liveOhm != null && liveOhm >= FUEL_OPEN_CIRCUIT_OHM
    val bracket =
        if (liveOhm != null && !openCircuit) fuelBracket(points, liveOhm) else null
    val levelPct =
        if (bracket != null) fuelLevelPercent(points, liveOhm!!) else null

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            item.label,
            style = HudeParamName(),
            color = if (enabled) HudColors.TextPrimary else HudColors.TextDim,
        )

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text(
                "Live sender",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
            )
            Text(
                liveOhm?.let { "${renderNumber(it, 1)} Ω" } ?: "—",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = when {
                    liveOhm == null -> HudColors.TextDim
                    liveState == 1 -> HudColors.Green
                    liveState != null -> HudColors.Amber
                    else -> HudColors.TextPrimary
                },
            )
        }
        liveState?.let {
            Text(
                DiagFormat.fuelState(it),
                style = HudeCfgNote(),
                color = when (it) {
                    1 -> HudColors.Green
                    2, 3 -> HudColors.Amber
                    else -> HudColors.TextDim
                },
                modifier = Modifier.padding(top = 1.dp),
            )
        }

        // The level bar: one interpolated number (the gauge's own maths, `fuelLevelPercent`),
        // a cyan fill, and a brighter mark at the exact spot. The `—` keeps the row's height
        // whether or not the sender has been seen yet.
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(HudColors.CardInner, RoundedCornerShape(4.dp)),
            ) {
                if (bracket != null && liveOhm != null) {
                    val frac = ((bracket.rowA + bracket.fraction) / (points.size - 1)).toFloat()
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .background(HudColors.CyanDim, RoundedCornerShape(4.dp)),
                    )
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = maxWidth * frac - 1.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(HudColors.Cyan),
                    )
                }
            }
            Text(
                levelPct?.let { "$it%" } ?: "—",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                color = if (levelPct != null) HudColors.Cyan else HudColors.TextDim,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp),
            )
        }
        // The bracket, in the table's own row numbers — the sentence that explains the bar,
        // and the same pair of rows the table highlights beside it.
        Text(
            when {
                bracket != null ->
                    "between #${bracket.rowA} (${renderNumber(points[bracket.rowA], 1)} Ω) " +
                        "and #${bracket.rowB} (${renderNumber(points[bracket.rowB], 1)} Ω)"
                liveOhm == null -> "waiting for a live sender reading"
                else -> "no level in an open circuit"
            },
            style = HudeCfgNote(),
            color = HudColors.TextDim,
            modifier = Modifier.padding(top = 3.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            HudButton(
                label = "fill from empty/full",
                style = HudButtonStyle.SECONDARY,
                enabled = enabled,
                // `action.write-to-unit`: this builds the ramp from the two end ohms and
                // commits it to the unit in the one press — which is exactly what the page's
                // `⤓ Fill from empty/full ohm` did, fill boxes and Save included.
                glyph = HudIcons.Flush,
                onClick = {
                    val e = emptyOhm
                    val f = fullOhm
                    val n = pointCount ?: points.size
                    when {
                        n < 2 -> note = "set the point count first (2–20)"
                        e == null || f == null -> note = "enter empty and full ohms first"
                        abs(f - e) < 0.5 -> note =
                            "empty and full ohms must differ by at least 0.5 Ω"
                        else -> apply(fuelRamp(n, e, f))
                    }
                },
            )
            HudButton(
                label = "refresh table",
                style = HudButtonStyle.SECONDARY,
                enabled = enabled,
                // `list.reload`: the same arrow the page's `↻ Refresh Table` was.
                glyph = HudIcons.Reset,
                onClick = { onRefresh() },
            )
        }

        // The table proper. The page flowed bare `#n [box]` pairs two to a line, which read
        // as a wall of numbers; this is the same array drawn the way a figure is read — a
        // header, one point per row, fixed columns, and the hairline/zebra rule every other
        // surface in the app already follows. The Ω cell is the one boxed control here: the
        // 2026-11-16 rule retired value boxes across the settings form, but inside a grid a
        // cell *is* the box, and a column of bare figures does not say "editable" the way
        // the maintainer's own table did.
        TableHeaderRow()
        points.forEachIndexed { index, value ->
            val bracketed =
                bracket != null && (index == bracket.rowA || index == bracket.rowB)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (index % 2 == 1) Modifier.background(HudColors.CardInner)
                        else Modifier
                    )
                    .then(
                        if (bracketed) {
                            Modifier.border(
                                1.dp,
                                HudColors.HairlineActive,
                                RoundedCornerShape(6.dp),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    "#$index",
                    style = HudeCfgNote(),
                    color = if (bracketed) HudColors.Cyan else HudColors.TextDim,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(38.dp),
                )
                // The litres cell, and the two ends named where they live — this is where
                // the page's `#0 = empty, #N-1 = full` note goes to retire.
                Box(Modifier.width(58.dp), contentAlignment = Alignment.Center) {
                    when (index) {
                        0 -> EndChip("EMPTY")
                        points.lastIndex -> EndChip("FULL")
                        else -> Text(
                            "$index",
                            style = HudeCfgNote(),
                            color = HudColors.TextDim,
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    OhmCell(
                        index = index,
                        value = value,
                        enabled = enabled,
                        onCommit = { v ->
                            val next = points.toMutableList()
                            next[index] = v
                            apply(next)
                        },
                    )
                }
                // The row *is* the slot: one mark per point stores the live reading right
                // here, and the page's `slot N + CAPTURE` arithmetic leaves with it.
                Box(
                    Modifier
                        .width(52.dp)
                        .heightIn(min = HudTouch.Min)
                        .hudPressable(
                            enabled = enabled,
                            onClick = {
                                val ohm = liveOhm
                                when {
                                    ohm == null -> note =
                                        "waiting for a live sender reading — is the sender enabled?"
                                    ohm >= FUEL_OPEN_CIRCUIT_OHM -> note =
                                        "the sender reads an open circuit — capture would store a broken wire"
                                    else -> {
                                        val captured = captureIntoSlot(points, index, ohm)
                                        if (captured != null) {
                                            apply(
                                                captured,
                                                "slot #$index stores ${renderNumber(ohm, 1)} Ω",
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        HudIcons.Check,
                        contentDescription = "store the live sender reading in point #$index",
                        // The mark dims when there is nothing in front of it to store —
                        // a live-less or broken-wire capture is offered, then refused,
                        // exactly as the slot-box version was.
                        colorFilter = ColorFilter.tint(
                            when {
                                !enabled -> HudColors.TextDim
                                liveOhm == null || openCircuit -> HudColors.TextDim
                                else -> HudColors.Cyan
                            },
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (index != points.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
            }
        }

        if (points.isNotEmpty()) {
            Text(
                "Points are tank slots: #n is n litres, so #0 is empty and " +
                    "#${points.lastIndex} is full.",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (rebuiltWanted) {
            Text(
                "Point count is ${pointCount} and the unit still has ${points.size} points — " +
                    "it rebuilt the ramp from the empty/full ohms. Re-reading…",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        val line = listOfNotNull(note, statusNote(view)).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(
                line,
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The table's own head. Fixed columns match the rows exactly; the capture column wears
 * the same mark the rows do, so the column says what its buttons do without a word.
 */
@Composable
private fun TableHeaderRow() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 3.dp),
        ) {
            Text(
                "#",
                style = HudeCfgNote(),
                color = HudColors.CyanDim,
                textAlign = TextAlign.End,
                modifier = Modifier.width(38.dp),
            )
            Text(
                "L",
                style = HudeCfgNote(),
                color = HudColors.CyanDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(58.dp),
            )
            Text(
                "Ω",
                style = HudeCfgNote(),
                color = HudColors.CyanDim,
                textAlign = TextAlign.End,
                // Ends against the Ω cells' right edge: the FIELD box keeps 10 dp of inner
                // padding, and the figures inside end there, so the header must too.
                modifier = Modifier.weight(1f).padding(end = 10.dp),
            )
            // Same 52 dp column as the rows' capture marks, so the header's mark sits
            // exactly over the ones it explains.
            Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                Image(
                    HudIcons.Check,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(HudColors.CyanDim),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.HairlineActive))
    }
}

/** The `EMPTY` / `FULL` names the page used to spell out in a note under the grid. */
@Composable
private fun EndChip(word: String) {
    Text(
        word,
        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
        color = HudColors.Cyan,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(Color(0x1A00E5FF), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * One point of the table: the figure the page let you type, as a boxed cell.
 * The cell re-seeds from the array whenever that point's value moves — a fill, a capture
 * or a repair redraws the box exactly like the page's `dispatchEvent(new Event('input'))`.
 */
@Composable
private fun OhmCell(
    index: Int,
    value: Double,
    enabled: Boolean,
    onCommit: (Double) -> Unit,
) {
    var text by remember(index, value) { mutableStateOf(renderNumber(value, 1)) }
    HudInput(
        value = text,
        onValueChange = { raw -> text = raw.filter { it.isDigit() || it == '.' } },
        modifier = Modifier.fillMaxWidth(),
        // The box is the grid; inside a table cell it is the decision the 2026-11-16 rule
        // asks to be written down, and this is that sentence.
        style = HudInputStyle.FIELD,
        keyboardType = KeyboardType.Number,
        textAlign = TextAlign.End,
        onDone = { if (enabled) parseFuelPoint(text)?.let(onCommit) },
    )
}
