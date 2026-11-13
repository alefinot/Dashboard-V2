package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudSpace
import com.alefinot.dashboardpp.ui.theme.HudTouch
import com.alefinot.dashboardpp.ui.theme.HudeMetricUnit

/**
 * The three sizes an icon is drawn at, and nothing in between.
 *
 * The app had drawn glyphs at 30, 22, 18, 16 and 14 dp before this existed, and the difference
 * between two adjacent steps was one or two dp — invisible as a rhythm, visible as sloppiness.
 * Every one of them is a decision about distance, not about the artwork:
 *
 *  - [Card] is read across a room or across a card — the bar's centre slot and the placeholder on
 *    a vehicle with no photo. It is the only size allowed to carry fine detail.
 *  - [Action] is read at arm's length in a line of peers — the bar's outer slots and the unit
 *    settings page's five actions. This is the size [HudIcons]' stroke rule is tuned for.
 *  - [Row] is read next to a word that already names the thing — a settings row's reset, a
 *    card's corner, a header's mark. It is the size where the set's rule stops being a preference:
 *    18 dp is ~49 px on the bench phone, so one grid unit is ~2.05 px, and an interior detail that
 *    is not cut out of a body is not drawn at all.
 */
enum class HudGlyphSize(val dp: Dp) {
    Card(30.dp),
    Action(22.dp),
    Row(18.dp),
}

/**
 * A glyph you can press: the one control for every icon-only action in the app.
 *
 * Before this there were three ways to draw one — the bar's own [BarGlyph] (tint-animated, badged,
 * the only one that measured its bounds for the travelling sheet), the settings row's reset (a
 * hand-rolled 18 dp `Image` in a 48 dp box), and the unit settings page's `ActionSlot` (a glyph
 * under a word). Same gesture, three implementations, so the press, the tint and the dimmed state
 * could — and did — drift apart.
 *
 * Two things every one of them needed and only some of them had:
 *
 *  - **A name that cannot be skipped.** [description] has no default. An icon is a word that has to
 *    be guessed, so when the word comes off the screen it has to go onto the node: this is what
 *    TalkBack reads and what `uiautomator` reports in `content-desc`. The test in
 *    `GlyphLedgerTest` walks the sources and fails the build on a call site that passes a blank.
 *  - **One ink for every channel of the control.** The glyph, its caption in
 *    [HudCaptionedAction] and the ring behind a chip all take this one colour, so an action that
 *    is not available cannot keep a bright word or a bright dot next to a dim glyph.
 *
 * The press is [hudPressable] — the page's `button:active` scale, no ripple — and the tint moves on
 * the page's control easing, so a slot that stops being available fades rather than snaps.
 *
 * [box] is the touch box, not the drawing: the glyph stays [size] and the box reaches [HudTouch.Min]
 * around it, which is how a row's 18 dp reset stays hittable with gloves on.
 */
@Composable
fun HudGlyphAction(
    glyph: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: HudGlyphSize = HudGlyphSize.Action,
    box: Dp = HudTouch.Min,
    ink: Color = HudColors.Cyan,
    enabled: Boolean = true,
    /** The amber corner mark — the one thing a bar slot is allowed to shout about. */
    badge: Boolean = false,
    /** An unselected slot recedes; 1f is "this is the one". */
    emphasis: Float = 1f,
    /** Paint the artwork as drawn instead of tinting it — the bar's centre mark. */
    asDrawn: Boolean = false,
    /** Where the pressed box landed, for the callers that park a layer on it. */
    onBounds: (Rect) -> Unit = {},
    onClick: () -> Unit,
) {
    val ms = if (HudMotion.enabled) HudMotion.CONTROL_MS else 0
    val tint by animateColorAsState(ink, tween(ms, easing = HudMotion.Ease), label = "glyphInk")
    val fade by animateFloatAsState(emphasis, tween(ms, easing = HudMotion.Ease), label = "glyphFade")
    Box(
        modifier
            .size(box)
            .clip(HudRadius.CardShape)
            .onGloballyPositioned { c -> onBounds(c.boundsInRoot()) }
            .hudPressable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (asDrawn) {
            Image(
                glyph, description,
                modifier = Modifier.size(size.dp).graphicsLayer { alpha = fade },
            )
        } else {
            Image(
                glyph, description,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(size.dp).graphicsLayer { alpha = fade },
            )
        }
        if (badge) {
            // Amber, not cyan: a selected slot is already cyan, and a mark that matches the
            // selection cannot say "something here you have not looked at".
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(8.dp)
                    .background(HudColors.Amber, CircleShape),
            )
        }
    }
}

/**
 * A glyph with the one word it needs, under it: the shape is what a finger finds, the word is what
 * confirms the shape was the right one.
 *
 * This is the unit settings page's `ActionSlot`, which was written for that one line of five
 * actions and is now the app's answer for every fixed action line — the editor footers, the update
 * cards, the notice cards. Promoting it is what makes "glyph + word" mean the same height, ink,
 * caption role and press box on every one of them instead of being re-derived per screen.
 *
 * The box is [HudTouch.Min] and not [HudTouch.Button], and that is deliberate: a labelled button may
 * be small, because the label tells you what it is before your finger arrives. A glyph with a caption
 * still cannot be, because a finger does not read an 11 px word before pressing — it reads the shape,
 * and the word is only there to confirm afterwards that the shape was the right one.
 *
 * The word is inside the pressed box and painted in the glyph's own ink, for the two failure modes
 * that happen silently: a caption outside the box is a tap target a finger misses, and a caption in
 * a fixed grey keeps an unavailable action looking available under a dimmed mark. It is drawn in the
 * ramp's tiny-label role ([HudeMetricUnit], the same 11 px/600 that marks a metric's unit), so the
 * caption costs the line nothing it did not already spend on the glyph.
 *
 * [description] is the sentence the word buttons used to print — the caption is one word, and a
 * screen reader gets the whole clause, both as the image's own name and as `onClickLabel`, which is
 * what TalkBack reads as the action a double-tap will take.
 *
 * [progress], when the act this slot names is running, replaces the mark with a ring at that
 * fraction in the same ink. It is what the `…` on `FETCHING…` and `WORKING…` used to be: a typed
 * pretend glyph, and one that said nothing a bar elsewhere on the card was not already saying. A ring
 * in the mark's place says *the thing this control does is the thing that is happening*, in the same
 * 22 dp box, without adding a character to the app.
 */
@Composable
fun HudCaptionedAction(
    glyph: ImageVector,
    caption: String,
    description: String,
    ink: Color,
    enabled: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    CaptionedBody(
        glyph = glyph,
        caption = caption,
        description = description,
        ink = ink,
        enabled = enabled,
        progress = progress,
        onClick = onClick,
    )
}

/** [HudCaptionedAction] as an equal share of a line, which is what keeps five of them one apart. */
@Composable
fun RowScope.HudCaptionedAction(
    glyph: ImageVector,
    caption: String,
    description: String,
    ink: Color,
    enabled: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    Box(Modifier.weight(1f)) {
        CaptionedBody(
            glyph = glyph,
            caption = caption,
            description = description,
            ink = ink,
            enabled = enabled,
            progress = progress,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CaptionedBody(
    glyph: ImageVector,
    caption: String,
    description: String,
    ink: Color,
    enabled: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        // The floor is both ways, not just tall. A line that is not evenly shared — one glyph beside
        // a labelled word button — leaves each slot its own width, and a caption is ~30 dp of word:
        // a 30 × 48 box is a target a thumb misses sideways, which is the same failure the 48 dp
        // height was put here to stop.
        modifier.heightIn(min = HudTouch.Min)
            .widthIn(min = HudTouch.Min)
            .hudPressable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CaptionedMark(glyph, description, ink, progress)
            Text(
                caption,
                style = HudeMetricUnit(),
                color = ink,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The mark's place in a [CaptionedBody]: the glyph, or — while the act it names is running — a ring at
 * that fraction in the same ink.
 *
 * Two forms in one fixed box, so a control that starts working does not grow, shrink or move the
 * caption beside it. The ring is the answer to `FETCHING…` and `WORKING…`: those labels spelled a
 * state with a typed character, and the character said less than the ring does while saying nothing a
 * bar on the same card was not already saying.
 */
@Composable
private fun CaptionedMark(
    glyph: ImageVector,
    description: String,
    ink: Color,
    progress: Float?,
) {
    if (progress == null) {
        Image(
            glyph,
            description,
            colorFilter = ColorFilter.tint(ink),
            modifier = Modifier.size(HudGlyphSize.Action.dp),
        )
    } else {
        ScanProgressRing(
            progress.coerceIn(0f, 1f),
            modifier = Modifier.size(HudGlyphSize.Action.dp),
            ink = ink,
        )
    }
}

/**
 * One step of a numeric setting, drawn as [HudIcons.Plus] or [HudIcons.Minus].
 *
 * This was `StepButton("−")` / `StepButton("+")`: a MINUS SIGN codepoint and an ASCII plus in a
 * `Text`, which a font centres however it likes — and which is why the two buttons of one stepper
 * never looked like siblings of each other. The marks come from the same set as every other verb in
 * the app, so a step up and a step down are the same family as a dismiss and a confirm.
 *
 * The 30 dp square is the maintainer's, not an accident: the row is dense, the field beside it is
 * the primary input, and the phone-sized 48 dp box was asked back down on 2026-11-08 (see
 * `SettingsRow`'s note on `.adv-btn`). [description] is still mandatory — a glyph that small is
 * exactly the case where a screen reader has nothing else to say.
 */
@Composable
fun HudStepButton(
    glyph: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(30.dp)
            .hudPressable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .clip(shape)
            .background(if (enabled) HudColors.Field else HudColors.Panel)
            .border(1.dp, HudColors.Hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            glyph,
            description,
            colorFilter = ColorFilter.tint(if (enabled) HudColors.Cyan else HudColors.TextDim),
            modifier = Modifier.size(HudGlyphSize.Row.dp),
        )
    }
}

/** A row's line of captioned actions, spaced on the app's hair step. */
@Composable
fun HudActionLine(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.padding(vertical = HudSpace.Hair),
        horizontalArrangement = Arrangement.spacedBy(HudSpace.Hair),
        content = content,
    )
}
