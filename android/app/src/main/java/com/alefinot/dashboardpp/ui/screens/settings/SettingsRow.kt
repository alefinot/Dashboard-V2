package com.alefinot.dashboardpp.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudSelectMenu
import com.alefinot.dashboardpp.ui.components.HudSlider
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudTouch
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One settings row, in the two shapes the app has: label on the left and control on the
 * right, or — on a phone — the label across the top with the control on a line of its own.
 * See [WideFormRow] for where the line between those two is drawn and why.
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
    /**
     * Set for a value the unit streams but does not accept back — a live reading, a
     * compiled-in version string. The row shows the value as text with this sentence
     * under it instead of a control: the old page showed these too, but a slider that
     * cannot move is worse than a line that says what it is.
     */
    readOnlyHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val box = SettingsUnits.toBox(item, view?.value, imperial)
    val unit = SettingsUnits.label(item, imperial, unitSwap)

    // Why the row has two shapes. The desktop row gave the control a fixed 78-250 dp and let
    // the label keep whatever was left. Measured on the bench phone (393 dp window, 341 dp
    // inside the card): a number row left the label 105 dp, so "Backlight PWM Frequency (kHz)"
    // arrived broken mid-word across two lines inside a 42.5 dp row that has no room for the
    // second one. A phone window cannot grow past `WideFormRow`, so below it the label takes the
    // whole width and the control gets a line to itself; wider than that (tablet, desktop
    // preview) the page's one-line row is the better shape and stays.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The value stays on its setting's line: the maintainer asked for that on
        // 2026-11-08, and the row was never broken by it - a label that needs two lines wraps at
        // a word and the row grows to fit it. Only the controls that cannot survive beside a
        // label drop below it: a slider or a picker's chips squeezed into what is left of a
        // 322 dp row is unusable, and an X/Y pair simply falls off the edge (both measured on the
        // bench, same day). See [NeedsItsOwnLine].
        val stacked = readOnlyHint == null && maxWidth < WideFormRow &&
            item.control in NeedsItsOwnLine

        Column(Modifier.padding(vertical = 4.dp)) {
            @Composable
            fun LabelBlock(width: Modifier) {
                Column(width) {
                    Text(
                        item.label + (unit?.let { " ($it)" } ?: ""),
                        style = HudeParamName(),
                        color = if (enabled) HudColors.TextPrimary else HudColors.TextDim,
                    )
                    if (item.labelIsGuessed) {
                        Text(
                            "guessed from ${item.key}",
                            style = HudeCfgNote(),
                            color = HudColors.TextDim,
                        )
                    }
                }
            }
            @Composable
            fun Trailing(root: Modifier = Modifier) {
                if (readOnlyHint != null) {
                    Text(
                        SettingsUnits.text(item, box) + (unit?.let { " $it" } ?: ""),
                        style = HudeParamName(),
                        color = HudColors.TextDim,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                } else {
                    Control(item, box, view, imperial, enabled, !stacked, root) { x, y ->
                        onEdit(x, y)
                    }
                    // The unit's own `config.reset`, one key at a time — the WebUI's reset
                    // button. Typing a default back in by hand would be a guess; this asks
                    // the firmware what the default is and gets it written the same way.
                    onReset?.let { back ->
                        Box(
                            // The glyph is 18 dp, but the box around it is what a thumb hits, and
                            // a thumb in gloves is what this row gets. The old word `reset` was
                            // 17 dp tall and needed the same box; drawing it cost nothing and gave
                            // the label a few dp more of the line.
                            Modifier.heightIn(min = HudTouch.Min)
                                .padding(horizontal = 8.dp)
                                .hudPressable(enabled = enabled, onClick = back),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                HudIcons.Reset,
                                // The word is gone from the screen, so the word has to be here:
                                // an icon with no description is a mystery button on a page of
                                // 158 of them.
                                contentDescription = "reset ${item.label} to its default",
                                colorFilter = ColorFilter.tint(
                                    if (enabled) HudColors.Cyan else HudColors.TextDim,
                                ),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            if (stacked) {
                LabelBlock(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The slider and the dropdown are the two stacked controls that fill their
                    // line. Without the weight their `fillMaxWidth` claimed the whole row and the
                    // reset beside them was measured at zero width - an invisible 48 dp box that
                    // still set the row's height. Weighted, each gives up the glyph's ~34 dp and
                    // the reset keeps the 48 dp target every other row's reset has.
                    val fills = item.control == RowControl.SLIDER || item.control == RowControl.SELECT
                    Trailing(if (fills) Modifier.weight(1f) else Modifier)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelBlock(Modifier.weight(1f))
                    Trailing()
                }
            }
            (listOfNotNull(statusNote(view), readOnlyHint)).let { parts ->
                val note = parts.joinToString(" · ")
                if (note.isNotEmpty()) {
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
    }
}

/**
 * The window width below which a settings row puts its control under its label. A phone in
 * portrait is 393 dp and a foldable's cover screen is ~350 dp; the app's own desktop column is
 * 920 dp, and a tablet in landscape is over 1000 dp — so this breakpoint is not a guess about a
 * device, it is the difference between the two page shapes.
 */
private val WideFormRow = 480.dp

/**
 * The controls that get a line of their own on a phone, and the reason each one is in the list
 * rather than beside its label. Everything else — the values, the toggles, the colour
 * fields — stays on the label's line.
 *
 * - [RowControl.SELECT] is a dropdown trigger that fills its line; beside a label it gets the
 *   remnant of the row, which is not wide enough to show the option the unit is actually holding
 *   (the time zone's labels run to `(UTC+05:30) India Standard Time`).
 * - [RowControl.SLIDER] is a track you aim at. The page's 250 dp is already the shortest usable
 *   slider; the ~90 dp left beside a long label is a fidget control.
 * - [RowControl.PAIR] is an X and a Y with their reserved widths, and the pair reads as one
 *   control or not at all. It wants the line to itself so the two axes break together.
 * - [RowControl.FUEL_TABLE] is a comma list of up to twenty ohm values plus an apply button.
 */
private val NeedsItsOwnLine = setOf(
    RowControl.SELECT,
    RowControl.SLIDER,
    RowControl.PAIR,
    RowControl.FUEL_TABLE,
)

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

/**
 * The row's control. `wide` is the desktop shape: the control sits beside a label, so it carries
 * the page's 12 dp of breathing room on its leading edge and a fixed width. Stacked, it owns the
 * line, so it drops the indent and lets the text fields use the width they are given.
 */
@Composable
private fun Control(
    item: SchemaItem,
    box: Any?,
    view: RowView?,
    imperial: Boolean,
    enabled: Boolean,
    wide: Boolean,
    root: Modifier = Modifier,
    onEdit: (Any?, Any?) -> Unit,
) {
    val lead = if (wide) 12.dp else 0.dp
    when (item.control) {
        RowControl.TOGGLE -> Box(
            // The switch is drawn inside the page's value column and centred in it, so its
            // knob sits on the same vertical line as every bare figure on the page
            // (maintainer's order, 2026-11-16). It used to draw at its own 48 dp, which
            // parked its centre 22 dp right of the numbers beside it: the row read as two
            // columns, one for switches and one for values. Centring it in the slot costs
            // nothing — the 48 dp press target is untouched — and both controls now end at
            // the reset glyph the same way. It takes no `lead` padding for the same reason
            // [NumberControl] does not: the slot is the position, and any indent inside it
            // moves the figure off the line the page is built on.
            modifier = Modifier.width(SettingsValueSlot),
            contentAlignment = Alignment.Center,
        ) {
            HudToggle(
                on = box as? Boolean ?: false,
                label = item.label,
                onToggle = { if (enabled) onEdit(it, null) },
            )
        }
        RowControl.SELECT -> MenuControl(item, box, enabled, wide, root) { onEdit(it, null) }
        RowControl.SLIDER -> SliderControl(item, box, imperial, enabled, wide, root) {
            onEdit(it, null)
        }
        RowControl.SWATCH -> ColorControl(item, box, enabled, wide) { onEdit(it, null) }
        RowControl.PAIR -> PairControl(item, view, wide) { x, y -> onEdit(x, y) }
        RowControl.FUEL_TABLE -> FuelTableControl(item, view, enabled, wide) { onEdit(it, null) }
        RowControl.SECRET -> HudInput(
            // The unit never streams a secret back, so the field always starts empty
            // and an empty field means "leave the stored one alone".
            value = "",
            onValueChange = { if (enabled) onEdit(it, null) },
            modifier = Modifier.width(230.dp).padding(start = lead),
            style = HudInputStyle.BARE,
            textAlign = TextAlign.End,
            placeholder = "stored — type to replace",
        )
        RowControl.TEXT -> HudInput(
            value = box?.toString() ?: "",
            onValueChange = { if (enabled) onEdit(it, null) },
            modifier = Modifier.width(230.dp).padding(start = lead),
            style = HudInputStyle.BARE,
            textAlign = TextAlign.End,
        )
        RowControl.NUMBER -> NumberControl(item, box) { onEdit(it, null) }
    }
}

/**
 * The row's number, typed in place.
 *
 * No box around it and no `+`/`-` beside it (maintainer's order, 2026-11-16): the figure
 * itself is the control — one tap puts the caret in it, the keyboard comes up on the row,
 * `Done` commits. It is drawn in the page's shared value column, [SettingsValueSlot], and
 * centred in it, so every figure on the page lines up on one line regardless of how long
 * its own band's numbers are or what the row leads with.
 *
 * The steppers it replaces were the row's other two ways to change a value, and they are
 * the reason the cluster needed ~430 dp on a phone row that has 317. A step of
 * `SettingsUnits.boxStep` was a fast way to move a value by one when the exact figure did
 * not matter; on this page it almost always does, and the rail of a slider row is where a
 * coarse move is still a drag.
 */
@Composable
private fun NumberControl(
    item: SchemaItem,
    box: Any?,
    onCommit: (Double) -> Unit,
) {
    val decimals = SettingsUnits.decimals(item)
    // Re-seeded whenever the confirmed value changes, so a value the store clamped
    // shows up in the text instead of being argued with.
    var text by remember(item.key, box) {
        mutableStateOf(renderNumber((box as? Number)?.toDouble(), decimals))
    }
    val commit: () -> Unit = {
        val parsed = text.trim().replace(',', '.').toDoubleOrNull()
        if (parsed != null && parsed != (box as? Number)?.toDouble()) onCommit(parsed)
    }
    HudInput(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }
        },
        modifier = Modifier.width(SettingsValueSlot),
        style = HudInputStyle.BARE,
        textAlign = TextAlign.Center,
        keyboardType = if (item.kind == ValueKind.INT) KeyboardType.Number else KeyboardType.Decimal,
        imeAction = ImeAction.Done,
        onDone = commit,
    )
}

/**
 * The page's **one** value column: every editable figure on the Dashboard settings page is
 * drawn inside a slot of this width, centred in it, and the slot sits in the gap between
 * whatever the row leads with (a slider rail, a colour swatch, an axis name) and the reset
 * glyph. The maintainer asked for the figures to sit "in the center between the sliders and
 * the reset button", and to be lined up that way on every row of the page (2026-11-16).
 *
 * One width, one alignment, one column is the whole idea. A ladder of per-row widths — the
 * 78/104/132 dp this replaced, sized to each row's band — made each figure end where its own
 * band's longest number happened to end, so the page's numbers were a ragged edge, and a
 * slider row's number sat somewhere else again. Now a brightness `31`, a PWM `12`, a pair
 * axis `-135` and a colour `#ff0000` share one vertical line down the page, wherever their
 * rails and labels end.
 *
 * 92 dp is the width that holds the widest figure the page can produce at
 * [HudeBareValue]'s 17px — seven digits, or `999999.9` with its point — inside the field's
 * 10 dp of text padding, and it still leaves a phone's 317 dp card a ~190 dp slider rail.
 * The rail is what this column takes from, and a drag gains nothing from the last 30 dp of
 * a track a thumb already crosses 400 times.
 */
internal val SettingsValueSlot = 92.dp

/**
 * An option row drawn as a dropdown: the trigger wears the label of the value the unit is
 * holding, and the list that opens is the unit's own option table from the schema — the app
 * does not invent labels or values.
 *
 * This replaced a wall of one chip per option, which the maintainer asked for off the settings
 * page on 2026-11-16 ("the dst rule, time zone and manual cpu frequency should be dropdown
 * menus", and Speed Source with them so no row on the page is styled unlike its neighbours).
 * Chips suited three short choices and nothing else: the time zone's 27 options stacked into a
 * block about twenty lines tall, and a label like `(UTC+05:30) India Standard Time` wrapped
 * across three of them. One menu draws all four rows the same way, and it is the same menu the
 * vehicle-type editor and the firmware card open ([HudSelectMenu]) — 44 dp rows, three visible,
 * the current one scrolled into view when the menu opens.
 */
@Composable
private fun MenuControl(
    item: SchemaItem,
    box: Any?,
    enabled: Boolean,
    wide: Boolean,
    root: Modifier = Modifier,
    onPick: (Any?) -> Unit,
) {
    val options = item.options ?: return
    val current = (box as? Number)?.toDouble()
    val selected = options.firstOrNull { option ->
        when (val v = option.value) {
            is Number -> current == v.toDouble()
            else -> box?.toString() == v.toString()
        }
    }
    // A unit can hold something the option table does not offer — a value an older firmware
    // wrote, or one set over RPC. The trigger says what is stored instead of naming the
    // nearest option it does not offer.
    val trigger = selected?.label ?: box?.toString()?.takeIf { it.isNotBlank() } ?: "—"
    HudSelectMenu(
        triggerLabel = trigger,
        items = options,
        label = { it.label },
        isSelected = { it == selected },
        onPick = { onPick(it.value) },
        enabled = enabled,
        modifier = root
            .then(if (wide) Modifier.width(300.dp) else Modifier.fillMaxWidth())
            .padding(start = if (wide) 12.dp else 0.dp),
        // Wider than the component's own default: these labels come from the unit and run long
        // ("EU / EEA — last Sunday of Mar & Oct, 01:00 UTC"), and at the default 280 dp the menu
        // ellipsized exactly the part that tells the zones apart. A DropdownMenu is clamped to
        // the window, so a phone never gets a popup wider than its screen.
        menuModifier = Modifier.widthIn(min = 220.dp, max = 360.dp),
    )
}

/**
 * A slider over the range the schema says the control may offer — never over the
 * firmware band, and never narrower than the value it starts at. The store still
 * clamps to the band; the slider simply cannot ask for a value outside what the old
 * page allowed.
 *
 * The control is [HudSlider] on one 30 dp line, with the row's own bare value beside it:
 * the rail is the aim and the number is both the reading and the exact entry, so the row
 * is the same shape as a number row with a rail added. It used to be a Material slider
 * (a 48 dp box) plus a `0 – 100` caption on the next line, which made this the tallest
 * row on the page and the only one that did not look like the app — and the number the
 * thumb was setting was nowhere on it until the thumb moved.
 *
 * The value used to be a readout chip inside [HudSlider] that only the thumb could move.
 * The maintainer took every value box off the app on 2026-11-16 and asked for the slider's
 * number to be editable too, so it is a `BARE` field like any other value here: one tap
 * puts the keyboard on it, `Done` clamps what was typed into the row's window and commits
 * it. While a drag is in progress the field is written from the draft rather than the
 * other way round, so the figure under the finger is the figure the thumb is on.
 *
 * An unreported key shows `—` as a placeholder, not a number: the thumb resting at the
 * bottom of the window is not a value the unit ever stored, and printing it as one would
 * be inventing a setting.
 *
 * The figure sits in the page's shared value column ([SettingsValueSlot]), centred in it,
 * which puts it in the middle of the gap between the rail and the reset glyph and puts
 * every other figure on the page on the same vertical line as this one.
 */
@Composable
private fun SliderControl(
    item: SchemaItem,
    box: Any?,
    imperial: Boolean,
    enabled: Boolean,
    wide: Boolean,
    root: Modifier = Modifier,
    onCommit: (Double) -> Unit,
) {
    val decimals = SettingsUnits.decimals(item)
    val lo = (SettingsUnits.toBox(item, item.sliderMin, imperial) as? Number)?.toDouble() ?: 0.0
    val hi = (SettingsUnits.toBox(item, item.sliderMax, imperial) as? Number)?.toDouble() ?: 0.0
    val top = lo.coerceAtLeast(hi)
    val stored = (box as? Number)?.toDouble() ?: lo
    var draft by remember(item.key, box) { mutableStateOf(stored.coerceIn(lo, top)) }
    // The figure beside the rail, re-seeded from the confirmed value the way a number row's
    // is. Empty means "the unit never reported this key" and shows as `—`; the moment a
    // drag or a keystroke happens it is a number the row is about to write.
    var text by remember(item.key, box) {
        mutableStateOf(if (box == null) "" else renderNumber(draft, decimals))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // In the wide shape the row is a fixed 250 dp because an unweighted child that fills
        // the row starves the `weight(1f)` label beside it. Stacked it owns the line, so it
        // takes the whole width — a 250 dp slider in a 341 dp row is 90 dp of dead track — but
        // the caller passes a weight when a reset sits beside it, and then the 34 dp the glyph
        // takes is the only thing the rail gives up.
        modifier = root
            .then(if (wide) Modifier.width(250.dp) else Modifier.fillMaxWidth())
            .padding(start = if (wide) 12.dp else 0.dp),
    ) {
        HudSlider(
            value = draft.toFloat().coerceIn(lo.toFloat(), top.toFloat()),
            onValueChange = { v ->
                draft = v.toDouble()
                text = renderNumber(v.toDouble(), decimals)
            },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = lo.toFloat()..top.toFloat(),
            steps = sliderSteps(lo, hi, SettingsUnits.boxStep(item, imperial)),
            enabled = enabled,
            // No end figures on the rail: the value beside it is the number the row exists to
            // show, and the width the ends used to take is rail - the longer the rail, the
            // finer a drag is.
            modifier = Modifier.weight(1f),
        )
        HudInput(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }
            },
            modifier = Modifier.width(SettingsValueSlot),
            style = HudInputStyle.BARE,
            textAlign = TextAlign.Center,
            placeholder = "—",
            keyboardType = if (item.kind == ValueKind.INT) {
                KeyboardType.Number
            } else {
                KeyboardType.Decimal
            },
            imeAction = ImeAction.Done,
            onDone = {
                val parsed = text.trim().replace(',', '.').toDoubleOrNull() ?: return@HudInput
                // Typed values are clamped into the row's own window, the same window a drag
                // cannot leave, and the field shows the clamped figure at once instead of
                // arguing with the echo.
                val clamped = parsed.coerceIn(lo, top)
                draft = clamped
                text = renderNumber(clamped, decimals)
                if (clamped != (box as? Number)?.toDouble()) onCommit(clamped)
            },
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
    wide: Boolean,
    onCommit: (String) -> Unit,
) {
    var hex by remember(item.key, box) { mutableStateOf(box?.toString().orEmpty()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(max = if (wide) 420.dp else Dp.Infinity)
            .padding(start = if (wide) 12.dp else 0.dp),
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
            modifier = Modifier.width(SettingsValueSlot).padding(start = 6.dp),
            style = HudInputStyle.BARE,
            textAlign = TextAlign.Center,
            onDone = { parseHex(hex)?.let { onCommit(normalizeHex(hex)) } },
        )
        for (preset in COLOR_PRESETS) {
            Box(
                Modifier.size(18.dp).padding(start = 4.dp)
                    .hudPressable(enabled = enabled) {
                        hex = preset
                        onCommit(preset)
                    }
                    .clip(RoundedCornerShape(3.dp))
                    .background(parseHex(preset) ?: Color.Transparent)
                    .border(1.dp, HudColors.Hairline, RoundedCornerShape(3.dp)),
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

/**
 * An `*_X` / `*_Y` pair as two bare values, each typed in place. The unit keeps them as
 * two rows; the page drew one.
 *
 * The two axes are grouped so the pair wraps as a pair: with the steppers gone they are
 * two short numbers, but an X and a Y with their reserved widths still want to break
 * together rather than one per line, which is what [FlowRow] gives them on a phone card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairControl(
    item: SchemaItem,
    view: RowView?,
    wide: Boolean,
    onCommit: (Double, Double) -> Unit,
) {
    val x = (view?.value as? Number)?.toDouble() ?: 0.0
    val y = (view?.yValue as? Number)?.toDouble() ?: 0.0
    val onlyX = item.copy(label = "X")
    val onlyY = item.copy(key = item.keyY ?: item.key, label = "Y", min = item.minY, max = item.maxY)

    @Composable
    fun Axis(name: String, value: Double, schema: SchemaItem, onValue: (Double) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = HudeCfgNote(), color = HudColors.TextDim)
            NumberControl(schema, value, onValue)
        }
    }

    // No cross-axis alignment: `FlowRow` in this Compose version has no such parameter, and each
    // axis is a `Row` of its own, so the only thing left to align is the two lines.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.widthIn(max = if (wide) 420.dp else Dp.Infinity)
            .padding(start = if (wide) 12.dp else 0.dp),
    ) {
        Axis("X", x, onlyX) { nx -> onCommit(nx, y) }
        Axis("Y", y, onlyY) { ny -> onCommit(x, ny) }
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
    wide: Boolean,
    onApply: (List<Double>) -> Unit,
) {
    val points = (view?.value as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toDouble() }
    var text by remember(item.key, points) {
        mutableStateOf(points.joinToString(", ") { renderNumber(it, 1) })
    }
    Column(
        Modifier.widthIn(max = if (wide) 420.dp else Dp.Infinity)
            .padding(start = if (wide) 12.dp else 0.dp),
    ) {
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
            style = HudInputStyle.BARE,
            // Not the value band: this field is twenty figures on one line, not a figure,
            // and at 17px the list stops fitting the card and scrolls where it should read.
            textSize = 13.sp,
            keyboardType = KeyboardType.Number,
            onDone = { parseOhmList(text)?.let(onApply) },
        )
        HudButton(
            label = "apply table",
            style = HudButtonStyle.SECONDARY,
            enabled = enabled,
            // The page's SAVE wears this mark, and this is the same act with one row's worth of
            // scope: it writes what is in the field into the unit. Before the mark existed the two
            // agreed only in prose.
            glyph = HudIcons.Flush,
            modifier = Modifier.padding(top = 6.dp),
            onClick = { parseOhmList(text)?.let(onApply) },
        )
    }
}

/** The comma-separated ohms the unit's `fuelCalOhms` array is, or null if unusable. */
fun parseOhmList(text: String): List<Double>? =
    text.split(',').mapNotNull { it.trim().toDoubleOrNull() }.ifEmpty { null }

/** A number as the row shows it: no trailing zeros, at most as many digits as the step needs. */
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
