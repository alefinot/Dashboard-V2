package com.alefinot.dashboardpp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.theme.HudColors
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
 * 7104bf96^:src/webui.html`, the `touch-table` renderer). The app drew the same table as
 * one comma-separated line; the maintainer asked for the real thing back (2026-11-20), so
 * this is that grid: same live line, same fill, same slot capture, same `#0 = empty,
 * #N-1 = full` rule.
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

// ---------------------------------------------------------------------------
// The pixels.
// ---------------------------------------------------------------------------

/**
 * The table card-body. Everything it reads beyond its own row — the live sender reading,
 * the point count, the empty/full ohms the fill button needs — the caller passes in, so
 * this stays a drawing of a row the store owns.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            item.label,
            style = HudeParamName(),
            color = if (enabled) HudColors.TextPrimary else HudColors.TextDim,
        )

        // The page's live line: `Live sender reading: <n> ohm <state>`. An open circuit is
        // reported by the state word, not by the clamped number — 9999.9 Ω is a wire that
        // fell off, and DiagFormat.fuelState is where the app already says which.
        val liveText = if (liveOhm != null) {
            "${renderNumber(liveOhm, 1)} Ω" +
                (liveState?.let { " · ${DiagFormat.fuelState(it)}" } ?: "")
        } else {
            "—"
        }
        Text(
            "Live sender reading: $liveText",
            style = HudeCfgNote(),
            color = HudColors.TextDim,
            modifier = Modifier.padding(top = 2.dp),
        )

        Text(
            "${points.size} points — tank slots = litres + 1. Changing the point count " +
                "rebuilds the whole table from the empty/full ohms; captured points are lost.",
            style = HudeCfgNote(),
            color = HudColors.TextDim,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (rebuiltWanted) {
            Text(
                "Point count is ${pointCount} and the unit still has ${points.size} points — " +
                    "it rebuilt the ramp from the empty/full ohms. Re-reading…",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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

        // The grid: `#idx` and one bare figure per point, flowed two-to-a-row on a phone.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            points.forEachIndexed { index, value ->
                PointField(
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
        }

        // The page's capture row, kept as the maintainer asked for it: a slot box and one
        // CAPTURE, with the ends named below. The reading it stores is the live one above.
        var slot by remember(item.key) { mutableStateOf("0") }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                "Store the live reading in slot",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
            )
            HudInput(
                value = slot,
                onValueChange = { raw -> slot = raw.filter { it.isDigit() } },
                modifier = Modifier.width(56.dp),
                style = HudInputStyle.BARE,
                keyboardType = KeyboardType.Number,
                textAlign = TextAlign.Center,
            )
            HudButton(
                label = "capture",
                style = HudButtonStyle.SECONDARY,
                enabled = enabled,
                // `action.live-capture`: accept the reading the sender is making right now. The
                // set's accept mark already means "the thing in front of you" — here the live
                // reading is exactly that thing, which is why this press needs no number typed.
                glyph = HudIcons.Check,
                onClick = {
                    val idx = slot.toIntOrNull()
                    val ohm = liveOhm
                    when {
                        ohm == null -> note =
                            "waiting for a live sender reading — is the sender enabled?"
                        ohm >= FUEL_OPEN_CIRCUIT_OHM -> note =
                            "the sender reads an open circuit — capture would store a broken wire"
                        idx == null -> note = "name the slot first"
                        else -> {
                            val captured = captureIntoSlot(points, idx, ohm)
                            if (captured == null) {
                                note = "slot #$idx is not in the table"
                            } else {
                                apply(captured, "slot #$idx stores ${renderNumber(ohm, 1)} Ω")
                            }
                        }
                    }
                },
            )
        }
        if (points.isNotEmpty()) {
            Text(
                "#0 = empty, #${points.size - 1} = full.",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
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
 * One point of the table: the `#idx` the page printed and the figure it let you type.
 * The field re-seeds from the array whenever that point's value moves — a fill, a capture
 * or a repair redraws the box exactly like the page's `dispatchEvent(new Event('input'))`.
 */
@Composable
private fun PointField(
    index: Int,
    value: Double,
    enabled: Boolean,
    onCommit: (Double) -> Unit,
) {
    var text by remember(index, value) { mutableStateOf(renderNumber(value, 1)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "#$index",
            style = HudeCfgNote(),
            color = HudColors.TextDim,
            modifier = Modifier.widthIn(min = 26.dp),
        )
        HudInput(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' }
            },
            modifier = Modifier.width(72.dp),
            style = HudInputStyle.BARE,
            keyboardType = KeyboardType.Number,
            textAlign = TextAlign.End,
            onDone = { if (enabled) parseFuelPoint(text)?.let(onCommit) },
        )
    }
}
