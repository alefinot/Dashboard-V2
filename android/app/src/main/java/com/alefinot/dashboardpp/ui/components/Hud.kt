package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeButtonLabel

enum class HudButtonStyle { PRIMARY, SECONDARY, WARNING, DANGER }

@Composable
fun HudButton(
    label: String,
    modifier: Modifier = Modifier,
    style: HudButtonStyle = HudButtonStyle.PRIMARY,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = HudRadius.CardShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        tween(HudMotion.SHORT_MS, easing = HudMotion.Standard),
        label = "buttonScale",
    )
    val brush: Brush? = when (style) {
        HudButtonStyle.PRIMARY ->
            if (pressed) HudBrushes.buttonActive() else HudBrushes.button()
        HudButtonStyle.WARNING -> HudBrushes.warning()
        HudButtonStyle.DANGER -> HudBrushes.danger()
        HudButtonStyle.SECONDARY -> null        // .btn-secondary is a flat fill
    }
    val border = when (style) {
        HudButtonStyle.PRIMARY -> HudColors.CyanDim              // rgba(0,229,255,.3)
        HudButtonStyle.SECONDARY -> Color(0x1AFFFFFF)            // rgba(255,255,255,.1)
        HudButtonStyle.WARNING -> Color(0x66FF9100)              // rgba(255,145,0,.4)
        HudButtonStyle.DANGER -> Color(0x66FF1744)               // rgba(255,23,68,.4)
    }
    val foreground = when (style) {
        HudButtonStyle.PRIMARY -> HudColors.White
        HudButtonStyle.SECONDARY -> HudColors.TextPrimary
        HudButtonStyle.WARNING -> Color(0xFFFFB74D)              // .btn-warning color
        HudButtonStyle.DANGER -> Color(0xFFFF8A80)               // .btn-danger color
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // `box-shadow: 0 2px 8px rgba(0,0,0,.2)`, deepening on the active state.
            .shadow(
                if (pressed) 4.dp else 2.dp, shape, clip = false,
                ambientColor = HudColors.Shadow, spotColor = HudColors.Shadow,
            )
            .clip(shape)
            .then(
                if (brush != null) Modifier.background(brush) else Modifier.background(HudBrushes.secondary),
            )
            .border(1.dp, border, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = foreground, style = HudeButtonLabel())
    }
}

@Composable
fun HudToggle(
    on: Boolean,
    modifier: Modifier = Modifier,
    /** What this switch turns on and off, for a screen reader. */
    label: String? = null,
    onToggle: (Boolean) -> Unit,
) {
    val track by animateColorAsState(
        if (on) Color(0x4000FFFF) else Color(0xFF2A2A2A),
        tween(HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "switchTrack",
    )
    val knobColor by animateColorAsState(
        if (on) HudColors.Cyan else Color(0xFF666666),
        tween(HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "switchKnob",
    )
    val knobX by animateDpAsState(
        if (on) 18.dp else 2.dp,
        tween(HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "switchKnobX",
    )
    Box(modifier.size(width = 44.dp, height = 44.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 34.dp, height = 18.dp)
                .clip(CircleShape)
                .background(track)
                .semantics {
                    if (label != null) contentDescription = label
                    stateDescription = if (on) "on" else "off"
                }
                .toggleable(
                    value = on,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Switch,
                    onValueChange = onToggle,
                ),
        ) {
            Box(
                Modifier
                    .offset(x = knobX, y = 2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(knobColor),
            )
        }
    }
}

enum class HudInputStyle { SEARCH, FIELD }

@Composable
fun HudInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: HudInputStyle = HudInputStyle.FIELD,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    /** `.search-icon`: an 18dp glyph at the field's left edge, never part of the text. */
    leading: (@Composable () -> Unit)? = null,
) {
    val search = style == HudInputStyle.SEARCH
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            !focused -> if (search) HudColors.Panel else HudColors.Field
            search -> HudColors.PanelFocus
            else -> HudColors.FieldFocus
        },
        tween(if (search) HudMotion.SHORT_MS else HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "inputFill",
    )
    val edge by animateColorAsState(
        if (focused) HudColors.Cyan else HudColors.Hairline,
        tween(if (search) HudMotion.SHORT_MS else HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "inputEdge",
    )
    val shape = if (search) HudRadius.RowShape else HudRadius.FieldShape
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(
            color = HudColors.TextPrimary,
            fontFamily = FontFamily.SansSerif,
            fontSize = if (search) 13.sp else 12.sp,
        ),
        cursorBrush = SolidColor(HudColors.Cyan),
        modifier = modifier.onFocusEvent { state -> focused = state.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .shadow(
                        if (focused && search) 4.dp else if (search) 8.dp else 0.dp,
                        shape,
                        clip = false,
                        ambientColor = if (focused && search) HudColors.CyanDim else HudColors.Shadow,
                        spotColor = if (focused && search) HudColors.CyanDim else HudColors.Shadow,
                    )
                    .clip(shape)
                    .background(fill)
                    .border(1.dp, edge, shape),
            ) {
                if (leading != null) {
                    Box(
                        Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { leading() }
                }
                Box(
                    Modifier.fillMaxWidth().padding(
                        start = when {
                            leading != null -> 40.dp
                            search -> 16.dp
                            else -> 10.dp
                        },
                        end = if (search) 16.dp else 10.dp,
                        top = if (search) 8.dp else 6.dp,
                        bottom = if (search) 8.dp else 6.dp,
                    ),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            placeholder,
                            color = HudColors.TextDim,
                            fontSize = if (search) 13.sp else 12.sp,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/** The page's own spinner (`#pullRefresh`, `refreshSpin 1s linear`): a cyan ring. */
@Composable
fun ScanProgressRing(progress: Float, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        progress = { progress },
        modifier = modifier,
        color = HudColors.Cyan,
        trackColor = HudColors.Hairline,
        strokeWidth = 3.dp,
    )
}

@Composable
fun ScanRing(progress: Float, modifier: Modifier = Modifier) {
    when {
        progress > 0f -> ScanProgressRing(progress, modifier)
        !HudMotion.enabled -> CircularProgressIndicator(
            progress = { 0.35f },
            modifier = modifier,
            color = HudColors.Cyan,
            trackColor = HudColors.Hairline,
            strokeWidth = 3.dp,
        )
        else -> CircularProgressIndicator(
            modifier = modifier,
            color = HudColors.Cyan,
            trackColor = HudColors.Hairline,
            strokeWidth = 3.dp,
        )
    }
}
