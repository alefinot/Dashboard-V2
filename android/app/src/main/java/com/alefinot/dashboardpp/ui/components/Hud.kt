package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.alefinot.dashboardpp.ui.theme.HudTouch
import com.alefinot.dashboardpp.ui.theme.HudeButtonLabel

/**
 * `button` from the Web UI, copied: 8px radius, a 135° cyan→blue gradient fill, a
 * cyan hairline, 9x14 padding, 12px/600 label.
 *
 * There is deliberately no ripple: the page's press feedback is `button:active` -
 * the fill brightens, the shadow deepens and the whole button scales to 0.98 - and
 * that is what this does on press, on the page's own 0.25s. Copying the look while
 * keeping Material's white ripple would put a gesture the page never makes back
 * into the same control.
 *
 * [glyph] is the mark of the act the button performs, drawn by [HudIcons] and painted in the
 * button's own ink. It is for the slots where the word cannot come off - a destructive verb, a
 * flash, anything the owner must not have to guess - and it is what keeps a word honest: a button
 * that writes to the unit wears the same mark as the page's SAVE, so the two say they are one act.
 * Inside a word button the mark is decorative and carries no description of its own; the label is
 * the name. A control that is *only* a mark goes through [HudGlyphAction] instead, where a
 * description cannot be omitted.
 */
enum class HudButtonStyle { PRIMARY, SECONDARY, WARNING, DANGER }

/**
 * The word button, kept on purpose as the grammar's exception box — read this before
 * adding a call site, because `GlyphLedgerTest`'s ratchet says a bare `HudButton(label)`
 * is no longer legal anywhere (the ceiling is zero) and this list is the only thing a
 * new call may reach for.
 *
 * Legal shapes, both from the icon plan:
 *
 *  - **D3 — the risky verbs.** `START`/`CONFIRM FLASH`, the DELETE family, and the
 *    `CONFIRM`/`CANCEL` footer of [HudConfirmDialog]: glyph **and** word, never bare.
 *    The word is the second channel that survives the mark, on the acts that must not
 *    be guessed.
 *  - **D4 — the labels that cannot be drawn.** `ALLOW INSTALLS`, `GRANT STORAGE
 *    ACCESS`, `PHONE BLUETOOTH SETTINGS`, `OPEN VEHICLE PAGE`, `RETRY`: a mark says the
 *    family (usually the cog, "an errand into a screen this app does not own") and the
 *    word says which.
 *
 * Every other verb left the screen during P0–P8 as [HudGlyphAction] (a mark with a
 * mandatory a11y sentence) or [HudCaptionedAction] (a mark over an 11 px caption).
 * Inside a word button the [glyph] is decorative — the label is the name; a control
 * that is *only* a mark goes through [HudGlyphAction], where a description cannot be
 * omitted.
 *
 * `button` from the Web UI, copied: 8px radius, a 135° cyan→blue gradient fill, a
 * cyan hairline, 9x14 padding, 12px/600 label.
 *
 * There is deliberately no ripple: the page's press feedback is `button:active` -
 * the fill brightens, the shadow deepens and the whole button scales to 0.98 - and
 * that is what this does on press, on the page's own 0.25s. Copying the look while
 * keeping Material's white ripple would put a gesture the page never makes back
 * into the same control.
 *
 * [glyph] is the mark of the act the button performs, drawn by [HudIcons] and painted in the
 * button's own ink — mandatory in practice, since a mark-less call site is what the
 * ratchet counts. A button that writes to the unit wears the same mark as the page's
 * SAVE, so the two say they are one act.
 */
@Composable
fun HudButton(
    label: String,
    modifier: Modifier = Modifier,
    style: HudButtonStyle = HudButtonStyle.PRIMARY,
    enabled: Boolean = true,
    glyph: ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = HudRadius.CardShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        tween(
            if (pressed) HudMotion.PRESS_MS else HudMotion.SHORT_MS,
            easing = HudMotion.Standard,
        ),
        label = "buttonScale",
    )
    // `button` and `button:hover` are the two gradient states; a touch screen has
    // no hover, so the pressed state takes hover's job.
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
            // The page's 9 px of vertical padding around 13 px of text makes a button ~31 dp
            // tall. This first pass lifted it to Android's 48 dp floor; on the bench phone the
            // maintainer had that back down again on 2026-11-08 - "the buttons are too tall" -
            // so the box is lifted only to HudTouch.Button, which keeps the page's drawing and
            // stops four action buttons from eating a phone screenful. See the token.
            .heightIn(min = HudTouch.Button)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    glyph,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(foreground),
                    modifier = Modifier.size(HudGlyphSize.Row.dp),
                )
                Text(label.uppercase(), color = foreground, style = HudeButtonLabel())
            }
        } else {
            Text(label.uppercase(), color = foreground, style = HudeButtonLabel())
        }
    }
}

/**
 * `.adv-switch`, copied to the pixel: a 34x18 track, radius 9, `#2a2a2a` off /
 * rgba(0,255,255,.25) on, and a 14px knob inset 2px that slides 16px and turns
 * accent-cyan - on the switch's own 0.2s transition, in the CSS's own `ease`.
 *
 * The control is 34x18dp but the touch target around it is `HudTouch.Min` (48 dp) tall.
 * On the page the whole `<label>` wraps the switch, so its tappable area is larger than
 * the control as well; 34x18dp on its own would be a hard target to hit on a phone in a
 * moving car.
 *
 * [label] is what the control is *of*. The old page got that from the text next to
 * its `<input type=checkbox>`; a Compose `Box` drawn from scratch has nothing to
 * announce, so a screen reader reads this switch as an unnamed box and cannot say
 * whether it is on. Passing the label makes it a named, checkable switch.
 */
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
    Box(modifier.size(width = HudTouch.Min, height = HudTouch.Min), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 34.dp, height = 18.dp)
                .clip(CircleShape)
                .background(track)
                // `toggleable`, not `clickable`: it puts the on/off state on the node,
                // so TalkBack says "advanced settings, switch, off" and `uiautomator`
                // reports checkable/checked instead of an unnamed clickable box.
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

/**
 * The page's `:active` press - the 0.97 scale `button:active` draws - for the
 * controls that are not [HudButton]: a vehicle card, a choice chip, a stepper, a
 * bar glyph. The press lands on the next frame ([HudMotion.PRESS_MS]) and the
 * release takes the page's [HudMotion.SHORT_MS], so every tappable thing in the
 * app answers a finger the same way instead of only the buttons doing it.
 *
 * No ripple, for the same reason [HudButton] has none: the page never makes that
 * gesture, and copying it would put a Material motion into a copied page.
 *
 * Apply it *before* the modifiers that draw the control - `.hudCard(...)`,
 * `.background(...)`, `.border(...)`. A modifier's draw runs outside the layers of
 * the modifiers that come after it, so a scale placed at the end only moves the
 * text inside a card whose fill and outline stay at full size; placed first, it
 * takes the whole control down, which is what `button:active` does.
 */
@Composable
fun Modifier.hudPressable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && HudMotion.enabled) 0.97f else 1f,
        animationSpec = tween(
            if (pressed) HudMotion.PRESS_MS else HudMotion.SHORT_MS,
            easing = HudMotion.Standard,
        ),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * `#confirm-overlay`'s scrim, as an entrance: the sheet behind a modal fades in on
 * the page's [HudMotion.SHORT_MS] while the card over it scales and rises. The
 * overlay used to appear at full strength in the same frame as the card, so the
 * card animated and the dimming did not - one half of the modal arriving, the
 * other half already there.
 *
 * It still consumes every tap, exactly as the plain `.clickable {}` scrim did: a
 * modal that lets a control behind it be pressed is not modal.
 */
@Composable
fun HudOverlayScrim(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val fade = remember { Animatable(if (HudMotion.enabled) 0f else 1f) }
    LaunchedEffect(fade) {
        if (HudMotion.enabled) {
            fade.animateTo(1f, tween(HudMotion.SHORT_MS, easing = HudMotion.Ease))
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(HudColors.Scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * The page's two field recipes, copied. `#searchBar`: 10px radius, glass fill,
 * 8x16 padding, 13px text, `transition: all .25s ease`, and on focus a cyan
 * border, `rgba(20,28,45,.9)` behind it and `0 0 16px rgba(0,229,255,.2)` around
 * it - which is why the focused search field here gets a *cyan* shadow: Compose
 * shadows take a colour, so the glow is the same shadow, in the page's colour.
 * `input[type=text|number]` / `select`: 6px radius, `--bg-input` fill, 6x10
 * padding, 12px text, `transition: all .2s ease`, `#182030` on focus.
 *
 * `#searchBar`'s 40px left padding is room for the `.search-icon` glyph, so a search
 * field that is given a [leading] slot (the magnifier) indents its text by the same 40dp
 * and draws the slot at 14dp, the way the page's absolutely-positioned icon does.
 */
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
    // The tap rule (`KeyboardFocus.ClearFocusOnTapOutside`) must not touch a tap that
    // lands on a text field, so every live field publishes its rectangle here. It is a
    // per-field state written only on change, so a scrolling list moves its own rect
    // without disturbing anybody else's.
    val fieldRects = LocalTextRectRegistry.current
    val ownRect = remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(fieldRects) {
        fieldRects.register(ownRect)
        onDispose { fieldRects.unregister(ownRect) }
    }
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
    // The focus glow is part of the same transition as the fill and the border: the
    // shadow and its cyan lift used to swap in one frame while the other two eased.
    val lift by animateDpAsState(
        if (focused && search) 4.dp else if (search) 8.dp else 0.dp,
        tween(if (search) HudMotion.SHORT_MS else HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "inputLift",
    )
    val glow by animateColorAsState(
        if (focused && search) HudColors.CyanDim else HudColors.Shadow,
        tween(if (search) HudMotion.SHORT_MS else HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "inputGlow",
    )
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
        modifier = modifier
            .onGloballyPositioned { coords ->
                val r = coords.boundsInRoot()
                if (ownRect.value != r) ownRect.value = r
            }
            .onFocusEvent { state -> focused = state.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .shadow(
                        lift,
                        shape,
                        clip = false,
                        ambientColor = glow,
                        spotColor = glow,
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

/**
 * The page's own spinner (`#pullRefresh`, `refreshSpin 1s linear`): a ring.
 *
 * [ink] is the caller's state ink, not a decoration: rule 3 says a mark's caption, badge and ring all
 * inherit the glyph's ink, so a control that has gone dim cannot keep a bright ring, and one that is
 * amber because it is *waiting* says so in the ring too. The default is the cyan the page's pull
 * refresh has always drawn.
 */
@Composable
fun ScanProgressRing(progress: Float, modifier: Modifier = Modifier, ink: Color = HudColors.Cyan) {
    CircularProgressIndicator(
        progress = { progress },
        modifier = modifier,
        color = ink,
        trackColor = HudColors.Hairline,
        strokeWidth = 3.dp,
    )
}

/**
 * The ring a sweep shows while it is looking for a dashboard (`.scan-ring`).
 *
 * The sweep has two halves with different information: the subnet walk reports how far it
 * has got, and mDNS plus the 50 ms probes answer with no count at all. So the ring has two
 * forms — an arc that fills while there is a fraction to show, and a turning ring while
 * there is nothing to count. With animation switched off it holds still at a partial arc:
 * the state is "a search is running", and that does not need motion to be understood.
 */
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
