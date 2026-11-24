package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.HudeTable
import com.alefinot.dashboardpp.ui.theme.hudCard

/**
 * The trip page's figure groups: a name, and under it one row per fact.
 *
 * Both groups used to be a grid of [com.alefinot.dashboardpp.ui.components.StatCell] tiles — nine
 * boxes, four of them reading `0.00` on a drive that did not measure those things. The maintainer
 * rejected that on the bench on 2026-11-24: a card around every number leaves the eye nothing to run
 * down, and the page spent ~420 dp saying what ~300 dp says here. A ledger is what the same facts look
 * like when they are read rather than admired — name at the left margin, figure at the right, one
 * hairline between, and both on the **same line**, because a figure wrapped under its own label cannot
 * be compared with its neighbour.
 *
 * Kept from the old shape, per that sign-off (`§9` of
 * `Implementation plans/TRIP-FIGURES-AND-ZOOMABLE-CHARTS-2026-11-24.md`):
 *  - an unmeasured fact still says `0.00`. It is not hidden, not turned into an em dash and not
 *    footnoted — the drive did not report it, and that *is* the answer (decision 2);
 *  - `DASHBOARD++ TRIP` keeps its wording. It is the unit's **cumulative** counter, not this drive's
 *    distance, which is why it can read 201.91 km beside a 2.62 km drive. Renaming it was refused.
 */

/** The hairline between two rows. One per gap: never above the first row, never under the last. */
private val ROW_RULE = 1.dp

/** A proportion indicator, not a chart: tall enough to see through a windscreen, short enough to ignore. */
private val BAR_HEIGHT = 3.dp

/**
 * A named group of facts: the caps, then its rows on one card.
 *
 * The rows — not the card — carry the hairlines, so a row's height depends on its own content and not
 * on a border insets. See [LedgerRow] for why the first row is the odd one out.
 */
@Composable
internal fun LedgerGroup(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, color = HudColors.Cyan, style = HudeSectionCaps())
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth()
                .hudCard(fill = HudColors.CardInner)
                .padding(horizontal = 12.dp),
        ) { content() }
    }
}

/**
 * One fact, on one line: name at the left margin, figure — with its unit, small and dim — at the right.
 *
 * [topRule] is the gap *above* this row. Every row draws its own, because the group cannot know its
 * own order at composition time without trusting a counter that recomposition may skip — so the
 * caller says it once, on the first row of a group, and every row added later arrives ruled, which is
 * correct wherever it lands except directly above the first.
 *
 * [bar] is the one exception that makes a row taller: a 0..100 proportion gets a 3 dp rule under the
 * name, in the same ink as the figure. Only GNSS FIX uses it, because it is the one fact on the page
 * whose meaning *is* a proportion of the drive — a bare `73%` asks the owner to picture three quarters
 * of something they cannot see. No animation: a bar that grows implies a measurement arriving.
 */
@Composable
internal fun LedgerRow(
    label: String,
    value: String,
    unit: String = "",
    ink: Color = HudColors.TextPrimary,
    bar: Double? = null,
    topRule: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (topRule) Box(Modifier.fillMaxWidth().height(ROW_RULE).background(HudColors.Hairline))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = HudColors.TextMuted,
                style = HudeTable(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                color = ink,
                style = HudePerfValue(),
                maxLines = 1,
                textAlign = TextAlign.End,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = HudColors.TextDim, style = HudePerfLabel(), maxLines = 1)
            }
        }
        if (bar != null) {
            CoverageBar(bar, ink)
            Spacer(Modifier.height(11.dp))
        }
    }
}

/** [pct] of a full-width track, in the row's own ink. Zero still paints a hairline of ink so the track is not read as a missing bar. */
@Composable
private fun CoverageBar(pct: Double, ink: Color) {
    val fraction = (pct / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        Modifier.fillMaxWidth().height(BAR_HEIGHT).background(HudColors.Track),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(BAR_HEIGHT)
                .background(ink),
        )
    }
}
