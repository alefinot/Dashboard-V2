package com.alefinot.dashboardpp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One settings row: label on the left, control on the right, and — only when there
 * is something to say — a note underneath saying what happened to the value.
 *
 * The note is the part the WebUI never had. A row that was clamped, refused, or
 * applied in RAM but not written to flash says so in the same place every time,
 * because "the unit answered `{"ok":true}`" is not the same statement as "this
 * setting is what you typed".
 *
 * Everything here is in **box units** (mph, °F, in) on the way in and out: the row
 * receives the value as the user sees it and hands back what the user typed, and
 * [ConfigStore] owns conversion, clamping and coercion.
 */
@Composable
fun SettingsRow(
    item: SchemaItem,
    view: RowView?,
    imperial: Boolean,
    unitSwap: Map<String, Any?>,
    enabled: Boolean,
    onEdit: (boxX: Any?, boxY: Any?) -> Unit,
    /** Puts the row back to its compiled default with `config.reset`. */
    onReset: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val box = SettingsUnits.toBox(item, view?.value, imperial)
    val unit = SettingsUnits.label(item, imperial, unitSwap)

    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.label + (unit?.let { " ($it)" } ?: ""),
                    style = HudeParamName(),
                    color = if (enabled) HudColors.TextPrimary else HudColors.TextDim,
                )
                if (item.labelIsGuessed) {
                    Text(
                        "no label for ${item.key} in the firmware's form map",
                        style = HudeCfgNote(),
                        color = HudColors.TextDim,
                    )
                }
            }
            Control(item, box, view, imperial, enabled, onEdit)
            // The unit's own `config.reset`, one key at a time — the WebUI's reset
            // button. Typing a default back in by hand would be a guess; this asks
            // the firmware what the default is and gets it written the same way.
            onReset?.let { back ->
                Text(
                    "reset",
                    style = HudeCfgNote(),
                    color = if (enabled) HudColors.Cyan else HudColors.TextDim,
                    modifier = Modifier.padding(start = 10.dp)
                        .clickable(enabled = enabled, onClick = back),
                )
            }
        }
        statusNote(view)?.let { note ->
            Text(
                note,
                style = HudeCfgNote(),
                color = when (view?.status) {
                    RowStatus.FAILED, RowStatus.RAM_ONLY, RowStatus.MISSING -> HudColors.Amber
                    else -> HudColors.TextDim
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The one-line explanation a row carries: its status plus whatever the store said
 * about it (a clamp, an NVS failure, a refused key). Null when the row has nothing
 * to report — a saved row stays quiet rather than showing "saved" forever.
 */
fun statusNote(view: RowView?): String? {
    val v = view ?: return null
    val status = when (v.status) {
        RowStatus.PENDING -> "saving…"
        RowStatus.SAVED, RowStatus.SYNCED -> null
        RowStatus.RAM_ONLY -> "in RAM, not in flash"
        RowStatus.FAILED -> "not saved"
        RowStatus.MISSING -> "this unit does not report the key"
    }
    val text = listOfNotNull(status, v.message?.takeIf { it.isNotEmpty() })
        .joinToString(" — ")
    return text.ifEmpty { null }
}

@Composable
private fun Control(
    item: SchemaItem,
    box: Any?,
    view: RowView?,
    imperial: Boolean,
    enabled: Boolean,
    onEdit: (Any?, Any?) -> Unit,
) {
    when (item.control) {
        RowControl.TOGGLE -> HudToggle(
            on = box as? Boolean ?: false,
            modifier = Modifier.padding(start = 12.dp),
            onToggle = { if (enabled) onEdit(it, null) },
        )
        RowControl.SELECT -> SelectControl(item, box, enabled) { onEdit(it, null) }
        RowControl.SLIDER -> SliderControl(item, box, imperial, enabled) { onEdit(it, null) }
        RowControl.SWATCH -> ColorControl(item, box, enabled) { onEdit(it, null) }
        RowControl.PAIR -> PairControl(item, view, enabled) { x, y -> onEdit(x, y) }
        RowControl.FUEL_TABLE -> FuelTableControl(item, view, enabled) { onEdit(it, null) }
        RowControl.SECRET -> HudInput(
            // The unit never streams a secret back, so the box always starts empty
            // and an empty box means "leave the stored one alone".
            value = "",
            onValueChange = { if (enabled) onEdit(it, null) },
            modifier = Modifier.width(230.dp).padding(start = 12.dp),
            placeholder = "stored — type to replace",
        )
        RowControl.TEXT -> HudInput(
            value = box?.toString() ?: "",
            onValueChange = { if (enabled) onEdit(it, null) },
            modifier = Modifier.width(230.dp).padding(start = 12.dp),
        )
        RowControl.NUMBER -> NumberControl(item, box, imperial, enabled) { onEdit(it, null) }
    }
}

/** A number with steppers a tablet can actually use, committed on Done, or a tap, or a step. */
@Composable
private fun NumberControl(
    item: SchemaItem,
    box: Any?,
    imperial: Boolean,
    enabled: Boolean,
    onCommit: (Double) -> Unit,
) {
    val decimals = SettingsUnits.decimals(item)
    val step = SettingsUnits.boxStep(item, imperial)
    // Re-seeded whenever the confirmed value changes, so a value the store clamped
    // shows up in the box instead of being argued with.
    var text by remember(item.key, box) {
        mutableStateOf(renderNumber((box as? Number)?.toDouble(), decimals))
    }
    val commit: () -> Unit = {
        val parsed = text.trim().replace(',', '.').toDoubleOrNull()
        if (parsed != null && parsed != (box as? Number)?.toDouble()) onCommit(parsed)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled) {
            val next = ((box as? Number)?.toDouble() ?: 0.0) - step
            text = renderNumber(next, decimals)
            onCommit(next)
        }
        HudInput(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }
            },
            modifier = Modifier.width(numberWidth(item)).padding(horizontal = 6.dp),
            keyboardType = if (item.kind == ValueKind.INT) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = ImeAction.Done,
            onDone = commit,
        )
        StepButton("+", enabled) {
            val next = ((box as? Number)?.toDouble() ?: 0.0) + step
            text = renderNumber(next, decimals)
            onCommit(next)
        }
    }
}

/** Wide enough for the largest number the row allows, so the value is never cut off. */
private fun numberWidth(item: SchemaItem): Dp = when {
    (item.uiMax ?: item.max ?: 0.0) >= 100000 -> 132.dp
    (item.uiMax ?: item.max ?: 0.0) >= 1000 -> 104.dp
    else -> 78.dp
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(30.dp).clip(shape)
            .background(if (enabled) HudColors.Field else HudColors.Panel)
            .border(1.dp, HudColors.Hairline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = if (enabled) HudColors.Cyan else HudColors.TextDim,
            fontSize = 15.sp,
            fontFamily = FontFamily.SansSerif,
        )
    }
}

/**
 * A picker drawn as its options, not as a dropdown: on a tablet every choice is
 * visible at a glance, and one tap commits it. The unit's own option list comes
 * from the schema — the app does not invent labels or values.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectControl(
    item: SchemaItem,
    box: Any?,
    enabled: Boolean,
    onPick: (Any?) -> Unit,
) {
    val options = item.options ?: return
    val current = (box as? Number)?.toDouble()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
    ) {
        for (option in options) {
            val selected = when (val v = option.value) {
                is Number -> current == v.toDouble()
                else -> box?.toString() == v.toString()
            }
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier.clip(shape)
                    .background(if (selected) HudColors.CyanDim else HudColors.Field)
                    .border(1.dp, if (selected) HudColors.Cyan else HudColors.Hairline, shape)
                    .clickable(enabled = enabled) { onPick(option.value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    option.label,
                    color = if (selected) HudColors.TextPrimary else HudColors.TextDim,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * A slider over the range the schema says the control may offer — never over the
 * firmware band, and never narrower than the value it starts at. The store still
 * clamps to the band; the slider simply cannot ask for a value outside what the old
 * page allowed.
 */
@Composable
private fun SliderControl(
    item: SchemaItem,
    box: Any?,
    imperial: Boolean,
    enabled: Boolean,
    onCommit: (Double) -> Unit,
) {
    val decimals = SettingsUnits.decimals(item)
    val lo = (SettingsUnits.toBox(item, item.sliderMin, imperial) as? Number)?.toDouble() ?: 0.0
    val hi = (SettingsUnits.toBox(item, item.sliderMax, imperial) as? Number)?.toDouble() ?: 0.0
    val stored = (box as? Number)?.toDouble() ?: lo
    var draft by remember(item.key, box) { mutableStateOf(stored.coerceIn(lo, lo.coerceAtLeast(hi))) }
    Column(Modifier.width(250.dp).padding(start = 12.dp)) {
        Slider(
            value = draft.toFloat().coerceIn(lo.toFloat(), lo.coerceAtLeast(hi).toFloat()),
            onValueChange = { draft = it.toDouble() },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = lo.toFloat()..lo.coerceAtLeast(hi).toFloat(),
            steps = sliderSteps(lo, hi, SettingsUnits.boxStep(item, imperial)),
            enabled = enabled,
        )
        Text(
            "${renderNumber(lo, decimals)} – ${renderNumber(hi, decimals)}",
            style = HudeCfgNote(),
            color = HudColors.TextDim,
        )
    }
}

/** Material3 counts the *gaps between* endpoints, so 10..20 in steps of 2 gets 4. */
fun sliderSteps(lo: Double, hi: Double, step: Double): Int {
    if (step <= 0.0 || hi - lo <= step) return 0
    return (((hi - lo) / step).toInt() - 1).coerceAtLeast(0)
}

/** Quick picks, taken from the unit's own colour defaults in `CFG_TABLE`. */
private val COLOR_PRESETS = listOf("#ffffff", "#ff0000", "#ffff00", "#00ff00")

@Composable
private fun ColorControl(
    item: SchemaItem,
    box: Any?,
    enabled: Boolean,
    onCommit: (String) -> Unit,
) {
    var hex by remember(item.key, box) { mutableStateOf(box?.toString().orEmpty()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 12.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(4.dp))
                .background(parseHex(hex) ?: HudColors.Field)
                .border(1.dp, HudColors.Hairline, RoundedCornerShape(4.dp)),
        )
        HudInput(
            value = hex,
            onValueChange = { raw ->
                hex = raw.filter { it.isDigit() || "abcdefABCDEF#".contains(it) }
            },
            modifier = Modifier.width(92.dp).padding(horizontal = 6.dp),
            onDone = { parseHex(hex)?.let { onCommit(normalizeHex(hex)) } },
        )
        for (preset in COLOR_PRESETS) {
            Box(
                Modifier.size(18.dp).padding(start = 4.dp).clip(RoundedCornerShape(3.dp))
                    .background(parseHex(preset) ?: Color.Transparent)
                    .border(1.dp, HudColors.Hairline, RoundedCornerShape(3.dp))
                    .clickable(enabled = enabled) {
                        hex = preset
                        onCommit(preset)
                    },
            )
        }
    }
}

/** `#rrggbb`, the form the unit stores, read as leniently as the page's colour input was. */
fun parseHex(text: String?): Color? {
    val t = text?.trim()?.removePrefix("#") ?: return null
    if (t.length != 6) return null
    val v = t.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or v)
}

fun normalizeHex(text: String): String = "#" + text.trim().removePrefix("#").lowercase()

/** An `*_X` / `*_Y` pair as two steppers. The unit keeps them as two rows; the page drew one. */
@Composable
private fun PairControl(
    item: SchemaItem,
    view: RowView?,
    enabled: Boolean,
    onCommit: (Double, Double) -> Unit,
) {
    val x = (view?.value as? Number)?.toDouble() ?: 0.0
    val y = (view?.yValue as? Number)?.toDouble() ?: 0.0
    val onlyX = item.copy(label = "X")
    val onlyY = item.copy(key = item.keyY ?: item.key, label = "Y", min = item.minY, max = item.maxY)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 12.dp),
    ) {
        Text("X", style = HudeCfgNote(), color = HudColors.TextDim)
        NumberControl(onlyX, x, false, enabled) { nx -> onCommit(nx, y) }
        Text("Y", style = HudeCfgNote(), color = HudColors.TextDim)
        NumberControl(onlyY, y, false, enabled) { ny -> onCommit(x, ny) }
    }
}

/**
 * The fuel calibration table — the ohm array the unit streams as `fuelCalOhms`.
 * Drawn readably (count, empty and full ends) and edited as the comma-separated
 * list the firmware's array actually is. A drag-to-set curve editor belongs with
 * the fuel screens, not here; what matters in the settings tab is that the table is
 * reachable, visible and restorable, which a text list already is.
 */
@Composable
private fun FuelTableControl(
    item: SchemaItem,
    view: RowView?,
    enabled: Boolean,
    onApply: (List<Double>) -> Unit,
) {
    val points = (view?.value as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toDouble() }
    var text by remember(item.key, points) {
        mutableStateOf(points.joinToString(", ") { renderNumber(it, 1) })
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        Text(
            "${points.size} points · " +
                (points.firstOrNull()?.let { "${renderNumber(it, 1)} Ω empty" } ?: "empty ?") +
                " → " +
                (points.lastOrNull()?.let { "${renderNumber(it, 1)} Ω full" } ?: "full ?"),
            style = HudeCfgNote(),
            color = HudColors.TextDim,
        )
        HudInput(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            keyboardType = KeyboardType.Number,
            onDone = { parseOhmList(text)?.let(onApply) },
        )
        HudButton(
            label = "apply table",
            style = HudButtonStyle.SECONDARY,
            enabled = enabled,
            modifier = Modifier.padding(top = 6.dp),
            onClick = { parseOhmList(text)?.let(onApply) },
        )
    }
}

/** The comma-separated ohms the unit's `fuelCalOhms` array is, or null if unusable. */
fun parseOhmList(text: String): List<Double>? =
    text.split(',').mapNotNull { it.trim().toDoubleOrNull() }.ifEmpty { null }

/** A number as the box shows it: no trailing zeros, at most as many digits as the step needs. */
fun renderNumber(value: Double?, decimals: Int): String {
    val raw = value ?: return ""
    if (raw.isNaN() || raw.isInfinite()) return ""
    val negative = raw < 0
    val factor = when (decimals) {
        1 -> 10.0
        2 -> 100.0
        else -> 1000.0
    }
    val scaled = abs(raw).let { (it * factor).roundToInt() / factor }
    val whole = scaled.toLong()
    if (decimals <= 0 || scaled == whole.toDouble()) {
        return (if (negative) "-" else "") + whole.toString()
    }
    val frac = ((scaled - whole) * factor).roundToInt().toLong().coerceIn(0L, (factor * factor).toLong())
    val digits = frac.toString().padStart(decimals, '0').trimEnd('0')
    return (if (negative) "-" else "") + whole + "." + digits.ifEmpty { "0" }
}
