package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudGlass

/**
 * The page frame every screen shares, taken from the ESP's own Web UI - the page the
 * unit used to serve from `src/webui.html`. That page is deleted (BLE-only firmware),
 * so this file is the surviving copy of its layout; the CSS the numbers below were
 * read from is still in `git show 7104bf96^:src/webui.html`.
 *
 * On a 1920 px tablet the app used to draw a 13 px label at the far left of a 1900 px
 * line and put the switch it belongs to 1800 px away on the right. The page never
 * did that: `.main-content` is a 920 px column with `margin: 0 auto`, and everything -
 * header, search, groups, action grid - lives inside it. This file is that rule, plus
 * the three pieces the page repeated at the top of the column (`h2`, `.ip-banner`,
 * `details > summary`), so a screen composes them instead of re-deriving them.
 *
 * The numbers are the page's own, in dp: on a 1.5x tablet 1 CSS px and 1 dp are the same
 * physical size, which is why the app can copy the page's pixel values rather than
 * scaling them.
 */
object HudPage {

    /** `.main-content { max-width: 920px }` - the column every page is drawn in. */
    val Width = 920.dp

    /** `.main-content { padding: 16px 12px }`, rounded up to the app's 16 dp gutter. */
    val Gutter = 16.dp
}

/**
 * The centred content column: `widthIn` first so 920 dp is a *ceiling*, then
 * `fillMaxWidth` so a phone gets the whole width rather than a 920 dp box hanging off
 * the left edge. Put this before `fillMaxHeight()`/`fillMaxSize()` in a modifier chain -
 * the other order fixes the width to the parent's before the cap can apply.
 */
fun Modifier.hudContentColumn(): Modifier = widthIn(max = HudPage.Width).fillMaxWidth()

/**
 * `.main-content` for a page root: fill the window, cap the content at 920 dp and centre
 * what is left. `wrapContentWidth` is what does the centring - the layout keeps the
 * window's size and places its (narrower) content in the middle of it, which is exactly
 * `margin: 0 auto`.
 *
 * A page uses this on the one container it already has (`Column`, `LazyColumn`), so the
 * column cap costs a page no extra nesting.
 */
fun Modifier.hudPage(): Modifier = this
    .fillMaxSize()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = HudPage.Width)
    .fillMaxWidth()

/**
 * `.header`: the page's `h2` in its gradient, centred over whatever the page says about
 * itself, with an optional control pinned to the top-right corner.
 *
 * The corner slot is the page's `.adv-mode-toggle` (`position: absolute; top: 6px;
 * right: 4px`). It is only placed there when the column is wide enough to keep it clear
 * of the title; on a phone-sized column it drops under the text instead, because a
 * control sitting on top of the heading is worse than one two lines lower.
 */
@Composable
fun HudPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val corner = maxWidth >= 600.dp
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = HudeHeading().copy(brush = HudBrushes.title),
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    style = HudeCfgNote(),
                    color = HudColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (content != null) {
                Spacer(Modifier.height(8.dp))
                content(this)
            }
            if (!corner && trailing != null) {
                Spacer(Modifier.height(8.dp))
                trailing()
            }
        }
        if (corner && trailing != null) {
            Box(Modifier.align(Alignment.TopEnd)) { trailing() }
        }
    }
}

/**
 * `.ip-banner`: the pill the page puts under its title - `inline-flex`, 4x12 padding,
 * radius 30, `--bg-card` fill, a 1 px hairline, 11 px `--text-muted`. The caller passes
 * the status colour, so the same pill says "linked" in green and "no link" in dim
 * without a second recipe.
 */
@Composable
fun HudBanner(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = HudColors.TextMuted,
) {
    Row(
        modifier
            .hudGlass(shape = HudRadius.PillShape, elevation = 0.dp, highlight = 0f)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = HudeFormLabel(), color = color)
    }
}

/**
 * `details`: the accordion shell - `--bg-card` fill, 1 px hairline, 12 px corners, and
 * `--card-shadow`, with the border turning `rgba(0,229,255,.25)` while open. The header
 * band is a child so it can paint `rgba(18,25,38,.6)` over the top of the same sheet.
 */
@Composable
fun HudAccordion(
    open: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ms = if (HudMotion.enabled) HudMotion.CONTROL_MS else 0
    val edge by animateColorAsState(
        if (open) HudColors.HairlineActive else HudColors.Hairline,
        tween(ms, easing = HudMotion.Ease),
        label = "accordionEdge",
    )
    val lift by animateDpAsState(
        if (open) 16.dp else 8.dp,
        tween(ms, easing = HudMotion.Ease),
        label = "accordionLift",
    )
    Column(
        modifier
            .fillMaxWidth()
            .shadow(
                lift, HudRadius.OverlayShape, clip = false,
                ambientColor = HudColors.Shadow, spotColor = HudColors.Shadow,
            )
            .clip(HudRadius.OverlayShape)
            .background(HudColors.Panel)
            .border(1.dp, edge, HudRadius.OverlayShape),
        content = content,
    )
}

/**
 * `summary`: the 32 px cyan icon badge, the 14 px/700 title, the count, and the circular
 * chevron - the four things the page's group rows are made of.
 *
 * The chevron is drawn, not typed. It used to be a `"▾"`/`"▸"` character, and this
 * device's sans font has no glyph for either: the row rendered a boxed question mark
 * where the arrow should be. A vector has no font to depend on and can turn on the
 * page's own 0.3 s instead of swapping two characters.
 *
 * [badge] is null for rows that are not schema groups (the readings block, the
 * performance monitor): those have no firmware-supplied icon, and inventing one would
 * claim the app knows what the group is.
 */
@Composable
fun HudAccordionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badge: Int? = null,
    meta: String? = null,
    open: Boolean = false,
    onClick: () -> Unit,
) {
    val ms = if (HudMotion.enabled) HudMotion.CONTROL_MS else 0
    val titleColor by animateColorAsState(
        if (open) HudColors.Cyan else HudColors.TextPrimary,
        tween(ms, easing = HudMotion.Ease),
        label = "summaryTitle",
    )
    val turn by animateFloatAsState(
        if (open) 90f else 0f,
        tween(ms, easing = HudMotion.Standard),
        label = "summaryChevron",
    )
    val chipFill by animateColorAsState(
        if (open) Color(0x2600E5FF) else Color(0x0DFFFFFF),
        tween(ms, easing = HudMotion.Ease),
        label = "summaryChipFill",
    )
    val chipInk by animateColorAsState(
        if (open) HudColors.Cyan else HudColors.TextMuted,
        tween(ms, easing = HudMotion.Ease),
        label = "summaryChipInk",
    )
    val badgeShape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(HudColors.PanelHead)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (badge != null) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(badgeShape)
                    .background(HudColors.Cyan.copy(alpha = 0.08f))
                    .border(1.dp, HudColors.Cyan.copy(alpha = 0.20f), badgeShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(badge),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (open) HudColors.Cyan else HudColors.CyanDim),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            title,
            style = HudeSectionTitle(0f).copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = titleColor,
            modifier = Modifier.weight(1f),
        )
        if (meta != null) {
            Text(meta, style = HudeCfgNote(), color = HudColors.TextDim)
        }
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(chipFill),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                HudIcons.Chevron,
                contentDescription = null,
                colorFilter = ColorFilter.tint(chipInk),
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
            )
        }
    }
}
