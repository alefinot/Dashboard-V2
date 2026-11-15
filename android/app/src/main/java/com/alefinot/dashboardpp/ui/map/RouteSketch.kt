package com.alefinot.dashboardpp.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.trips.TrackPoint
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudRadius

/**
 * The card-sized shape of a drive: the route in the HUD's cyan, a green dot at
 * the start and a red one at the end, on the same inset tile the detail page
 * gives its charts. Vector only, on purpose — a real map per row would mean a
 * tile fetcher, a thread and a bitmap cache behind every card in a scrolling
 * list, and it would draw nothing at all with no data connection. The basemap
 * is the detail page's job; this is the "where did it go" answer at a glance.
 */
@Composable
fun RouteSketch(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(HudRadius.CardShape)
            .background(HudColors.CardInner)
            .border(1.dp, HudColors.CardHairline, HudRadius.CardShape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (points.size < 2) return@Canvas
            // 7 dp keeps a dot's radius plus its edge inside the tile.
            val projected = TrackFit.project(points, size.width, size.height, inset = 7.dp.toPx())
            if (projected.size < 2) return@Canvas

            val path = Path()
            projected.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(
                path,
                color = HudColors.Cyan,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
            val dot = 2.6.dp.toPx()
            drawCircle(HudColors.Green, radius = dot, center = projected.first())
            drawCircle(HudColors.Red, radius = dot, center = projected.last())
        }
    }
}
