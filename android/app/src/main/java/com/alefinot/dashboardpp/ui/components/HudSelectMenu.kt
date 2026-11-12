package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote

/** An option menu is a field, not a dialog: exactly three choices fit in its viewport. */
private const val HudSelectMenuVisibleRows = 3
private val HudSelectMenuRowHeight = 44.dp
private val HudSelectMenuMaxHeight = HudSelectMenuRowHeight * HudSelectMenuVisibleRows

/**
 * The app's option menu: a field-shaped trigger over an opaque HUD sheet.
 *
 * The closed control follows the same field rule as `HudInput` — 6 dp corners, `Field`
 * fill, hairline, and a cyan active edge at [HudMotion.CONTROL_MS]. The open viewport
 * uses `Sheet`, custom rows, no Material ripple, and three visible choices; the rest is
 * reachable by scrolling. That keeps the vehicle-type and firmware-version menus drawing
 * the same furniture instead of reusing Material's default popup.
 */
@Composable
fun <T> HudSelectMenu(
    triggerLabel: String,
    items: List<T>,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menuModifier: Modifier = Modifier.widthIn(min = 220.dp, max = 280.dp),
    labelColor: (T) -> Color = { if (isSelected(it)) HudColors.Cyan else HudColors.TextPrimary },
    showsCheck: (T) -> Boolean = { isSelected(it) },
) {
    var open by remember { mutableStateOf(false) }
    val menuScrollState = rememberScrollState()
    val density = LocalDensity.current
    val fill by animateColorAsState(
        if (open) HudColors.FieldFocus else HudColors.Field,
        tween(if (HudMotion.enabled) HudMotion.CONTROL_MS else 0, easing = HudMotion.Ease),
        label = "selectMenuFill",
    )
    val edge by animateColorAsState(
        if (open) HudColors.Cyan else HudColors.Hairline,
        tween(if (HudMotion.enabled) HudMotion.CONTROL_MS else 0, easing = HudMotion.Ease),
        label = "selectMenuEdge",
    )
    // Right-pointing in the set, so 90 degrees is "opens downward" and 270 is "closes upward".
    val triggerTurn by animateFloatAsState(
        if (open) 270f else 90f,
        tween(if (HudMotion.enabled) HudMotion.CONTROL_MS else 0, easing = HudMotion.Standard),
        label = "selectMenuChevron",
    )

    LaunchedEffect(open) {
        if (open) {
            val selectedIndex = items.indexOfFirst(isSelected).coerceAtLeast(0)
            val lastScrollableRow = (items.size - HudSelectMenuVisibleRows).coerceAtLeast(0)
            val rowToReveal = selectedIndex.coerceAtMost(lastScrollableRow)
            val revealPx = with(density) { (HudSelectMenuRowHeight * rowToReveal).toPx().toInt() }
            menuScrollState.scrollTo(revealPx)
        }
    }

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HudSelectMenuRowHeight)
                .hudPressable(enabled = enabled, onClickLabel = "Choose an option") { open = true }
                .background(fill, HudRadius.FieldShape)
                .border(1.dp, edge, HudRadius.FieldShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    triggerLabel,
                    modifier = Modifier.weight(1f),
                    color = if (enabled) HudColors.TextPrimary else HudColors.TextDim,
                    style = HudeCfgNote(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The mark that says "this opens", drawn for the same reason the accordion's was:
                // the field used to print a typed `▾`, and this device's font has no glyph for it, so
                // the field showed a boxed question mark where the arrow should be (see Chevron).
                // It turns on the page's control easing instead of swapping characters.
                Image(
                    HudIcons.Chevron,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        if (enabled) HudColors.TextMuted else HudColors.TextDim,
                    ),
                    modifier = Modifier
                        .size(HudGlyphSize.Row.dp)
                        .graphicsLayer { rotationZ = triggerTurn },
                )
            }
        }
        DropdownMenu(
            expanded = open && enabled && items.isNotEmpty(),
            onDismissRequest = { open = false },
            modifier = menuModifier.heightIn(max = HudSelectMenuMaxHeight),
            scrollState = menuScrollState,
            shape = HudRadius.FieldShape,
            containerColor = HudColors.Sheet,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, HudColors.HairlineActive),
        ) {
            items.forEachIndexed { index, option ->
                HudSelectMenuRow(
                    label = label(option),
                    selected = isSelected(option),
                    textColor = labelColor(option),
                    showCheck = showsCheck(option),
                    first = index == 0,
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** One row in a [HudSelectMenu] viewport, drawn with the app's row surfaces. */
@Composable
private fun HudSelectMenuRow(
    label: String,
    selected: Boolean,
    textColor: Color,
    showCheck: Boolean,
    first: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowFill by animateColorAsState(
        when {
            pressed -> HudColors.CardFillHover
            selected -> HudColors.FieldFocus
            else -> HudColors.Sheet
        },
        tween(if (HudMotion.enabled) HudMotion.SWITCH_MS else 0, easing = HudMotion.Ease),
        label = "selectMenuRowFill",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(HudSelectMenuRowHeight)
            .background(rowFill)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = textColor,
                style = HudeCfgNote(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showCheck) {
                // The mark a row wears when it is the value already in force. It replaces a typed
                // U+2713: a codepoint this app can neither size nor weight, and one the bench
                // phone's font has already failed on once for a relative of it (see Chevron).
                Image(
                    HudIcons.Check,
                    contentDescription = "current value",
                    colorFilter = ColorFilter.tint(HudColors.Cyan),
                    modifier = Modifier.size(HudGlyphSize.Row.dp),
                )
            }
        }
        if (!first) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HudColors.Hairline),
            )
        }
    }
}
