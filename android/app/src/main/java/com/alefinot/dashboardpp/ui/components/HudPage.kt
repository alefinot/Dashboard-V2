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

object HudPage {

    /** `.main-content { max-width: 920px }` - the column every page is drawn in. */
    val Width = 920.dp

    /** `.main-content { padding: 16px 12px }`, rounded up to the app's 16 dp gutter. */
    val Gutter = 16.dp
}

fun Modifier.hudContentColumn(): Modifier = widthIn(max = HudPage.Width).fillMaxWidth()

fun Modifier.hudPage(): Modifier = this
    .fillMaxSize()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = HudPage.Width)
    .fillMaxWidth()

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
