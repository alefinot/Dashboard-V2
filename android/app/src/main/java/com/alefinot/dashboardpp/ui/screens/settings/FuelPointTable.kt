package com.alefinot.dashboardpp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudTouch
import com.alefinot.dashboardpp.ui.theme.HudeButtonLabel
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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
 * one point per row, ruled columns and zebra rows, a live reading that shows where on
 * the ramp the sender actually sits, and one small READ per row — the slot-number box is
 * gone, since the row *is* the slot.
 *
 * The same date's second pass retired the last two holdovers from the page: the value
 * boxes (the 2026-11-16 rule applies here too — bare figures, like every other row in
 * the app, with the column rules doing the structuring the boxes used to), and the
 * manual `Refresh Table` button — the table watches the calibration-point count itself
 * and re-reads until the unit's array agrees with it.
 *
 * It stays the *one row* the schema says it is: `fuelCalOhms` is one array setting, and
 * every edit — one point, a fill, a READ — commits the whole array through
 * `ConfigStore.edit` exactly as the comma list did. What changed is the drawing and the
 * things around it, not the write.
 *
 * The two repair rules the firmware enforces (`repairFuelTable` in `src/config.cpp`,
 * issue #19) are enforced here first, for the same reason the page did: the figures on
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
 * says so on screen because a table whose figures jumped is otherwise a bug report.
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
    // next read — the rebuild the warning below announces. There is no `Refresh Table`
    // button any more (maintainer, 2026-11-20): the table watches that gap itself and asks
    // the unit for the rebuilt ramp until the array agrees with the count, then stops.
    // `collectLatest` does the stopping — every fresh (count, size) pair from the store
    // re-decides, so one wait-and-ask runs per mismatch and the moment the rebuilt array
    // arrives the loop is cancelled instead of polling forever.
    val rebuiltWanted = pointCount != null && points.isNotEmpty() && pointCount != points.size
    val wantCount by rememberUpdatedState(pointCount)
    val haveSize by rememberUpdatedState(points.size)
    val refreshNow by rememberUpdatedState(onRefresh)
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        combine(
            snapshotFlow { wantCount },
            snapshotFlow { haveSize },
        ) { want, have -> want != null && have > 0 && want != have }
            .collectLatest { stillWaiting ->
                while (stillWaiting) {
                    delay(1200) // let the count write land and the unit rebuild first
                    refreshNow()
                    delay(2500) // and if the answer was still stale, keep asking
                }
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text(
                "Live sender",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.alignByBaseline(),
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
                modifier = Modifier.alignByBaseline(),
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
                    "between point ${bracket.rowA} (${renderNumber(points[bracket.rowA], 1)} Ω) " +
                        "and point ${bracket.rowB} (${renderNumber(points[bracket.rowB], 1)} Ω)"
                liveOhm == null -> "waiting for a live sender reading"
                else -> "no level in an open circuit"
            },
            style = HudeCfgNote(),
            color = HudColors.TextDim,
            modifier = Modifier.padding(top = 3.dp),
        )

        // The table proper. The page flowed bare `#n [box]` pairs two to a line, which read
        // as a wall of numbers; this is the same array drawn the way a figure is read — a
        // header, one point per row, and ruled columns. The figures are bare like every
        // other value in the app (2026-11-16 rule, applied here 2026-11-20): the vertical
        // hairlines carry the grid the boxes used to, so an editable column still reads as
        // a column and not as floating text.
        TableHeaderRow()
        points.forEachIndexed { index, value ->
            val bracketed =
                bracket != null && (index == bracket.rowA || index == bracket.rowB)
            // `IntrinsicSize.Min` is what lets the column rules span the whole row height:
            // the row measures itself as tall as its tallest cell, and the rules fill that.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
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
                // The litres cell doubles as the point number (point n holds n litres, and
                // the firmware walks the array by exactly that index), and the two ends are
                // named where they live — both the page's `#n` column and its `#0 = empty,
                // #N-1 = full` note retire into this one cell.
                Box(
                    Modifier.width(58.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (index) {
                        0 -> EndChip("EMPTY")
                        points.lastIndex -> EndChip("FULL")
                        else -> Text(
                            "$index",
                            style = HudeCfgNote(),
                            color = if (bracketed) HudColors.Cyan else HudColors.TextDim,
                        )
                    }
                }
                ColumnRule()
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
                ColumnRule()
                // The row *is* the slot: one READ per point stores the live reading right
                // here, in the same bordered-word shape the ambient calibration's READ
                // wears — and the page's `slot N + CAPTURE` arithmetic leaves with it.
                ReadCell(
                    index = index,
                    enabled = enabled,
                    // Dims when there is nothing in front of it to store — a live-less or
                    // broken-wire READ is offered, then refused by the note, on purpose:
                    // the button that greys out explains nothing, the word that refuses says
                    // why.
                    available = enabled && liveOhm != null && !openCircuit,
                    onClick = {
                        val ohm = liveOhm
                        when {
                            ohm == null -> note =
                                "waiting for a live sender reading — is the sender enabled?"
                            ohm >= FUEL_OPEN_CIRCUIT_OHM -> note =
                                "the sender reads an open circuit — READ would store a broken wire"
                            else -> {
                                val captured = captureIntoSlot(points, index, ohm)
                                if (captured != null) {
                                            apply(
                                                captured,
                                                "point $index stores ${renderNumber(ohm, 1)} Ω",
                                            )
                                }
                            }
                        }
                    },
                )
            }
            if (index != points.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
            }
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

        // LINEAR FILL sits *under* the table it rewrites — the press and its result read as
        // one gesture, top down (maintainer, 2026-11-20). It builds the straight empty→full
        // ramp and commits it to the unit in the one press, exactly what the page's
        // `⤓ Fill from empty/full ohm` did, fill boxes and Save included; the page's
        // `↻ Refresh Table` is retired, the watch above does its job. The tank mark is the
        // ledger's `action.linear-fill`: this card says fuel, not download.
        HudButton(
            label = "linear fill",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            style = HudButtonStyle.SECONDARY,
            enabled = enabled,
            glyph = HudIcons.FuelTank,
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
 * The table's own head. Fixed columns and rules match the rows exactly; the READ column
 * wears the same word the rows do, so the column says what its buttons do without needing
 * the glyph it replaced.
 */
@Composable
private fun TableHeaderRow() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(bottom = 3.dp),
        ) {
            // No `#` column: the litres column *is* the point number (point n holds n
            // litres, and the firmware walks the array by exactly that index), so the page's
            // two-number habit retired to one column when the maintainer called the extra
            // one out (2026-11-20).
            Text(
                "L",
                style = HudeCfgNote(),
                color = HudColors.CyanDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(58.dp),
            )
            ColumnRule()
            Text(
                "Ω",
                style = HudeCfgNote(),
                color = HudColors.CyanDim,
                textAlign = TextAlign.End,
                // Ends against the Ω figures' right edge: the bare input keeps the field's
                // 10 dp of inner padding, and the figures inside end there, so the header
                // must too.
                modifier = Modifier.weight(1f).padding(end = 10.dp),
            )
            ColumnRule()
            // Same 64 dp column as the rows' READ words, so the header's sits exactly
            // over the ones it names.
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                Text(
                    "READ",
                    style = HudeCfgNote(),
                    color = HudColors.CyanDim,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.HairlineActive))
    }
}

/** One vertical hairline between table columns — the ruling that replaced the boxes. */
@Composable
private fun ColumnRule() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .background(HudColors.Hairline),
    )
}

/**
 * One point's READ: the same bordered word the ambient calibration's READ is
 * (`SettingsRow`'s capture action) — one word in a rounded rule, no glyph, so both READs
 * in the settings say they are one act.
 */
@Composable
private fun ReadCell(
    index: Int,
    enabled: Boolean,
    /** Whether there is a live reading worth storing; drives the ink, never the press. */
    available: Boolean,
    onClick: () -> Unit,
) {
    val ink = if (available) HudColors.Cyan else HudColors.TextDim
    Box(
        Modifier
            .width(64.dp)
            .heightIn(min = HudTouch.Button)
            .clip(RoundedCornerShape(HudRadius.Field))
            .border(1.dp, ink, RoundedCornerShape(HudRadius.Field))
            .hudPressable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "store the live sender reading in point $index",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "READ",
            style = HudeButtonLabel(),
            color = ink,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
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
 * One point of the table: the figure the page let you type, as bare as every other value
 * in the settings (2026-11-16 rule) — the column rules around it carry the grid.
 * The cell re-seeds from the array whenever that point's value moves — a fill, a READ
 * or a repair redraws the figure exactly like the page's `dispatchEvent(new Event('input'))`.
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
        // Bare like the rest of the form's values: no box, a cyan rule under the figure
        // only while the keyboard is on it.
        style = HudInputStyle.BARE,
        keyboardType = KeyboardType.Number,
        textAlign = TextAlign.End,
        onDone = { if (enabled) parseFuelPoint(text)?.let(onCommit) },
    )
}
