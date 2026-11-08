package com.alefinot.dashboardpp.ui.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudColors

object HudIcons {

    /** The grid every glyph is drawn on, matching the CSS icons' 24x24 box. */
    private const val VB = 24f

    /** Stroke weight shared by the four drawn glyphs. */
    private const val W = 1.9f

    val Gauge: ImageVector by lazy {
        val dial = "M34.47,72.89 A25.5,25.5 0 1 1 78.74,62.67"
        val redline = "M78.74,62.67 A25.5,25.5 0 0 1 73.53,72.89"
        val needle = "M66.86,41.18 L51.40,54.31 L50.53,60.64 L56.60,58.69 Z"
        val hub = "M54.00,49.50 A7.0,7.0 0 1 1 54.00,63.50 A7.0,7.0 0 1 1 54.00,49.50 Z"
        val pivot = "M54.00,54.00 A2.5,2.5 0 1 1 54.00,59.00 A2.5,2.5 0 1 1 54.00,54.00 Z"
        ImageVector.Builder(
            name = "HudGauge", defaultWidth = VB.dp, defaultHeight = VB.dp,
            viewportWidth = VB, viewportHeight = VB,
        ).group(
            pivotX = 0f, pivotY = 0f, scaleX = 0.32f, scaleY = 0.32f,
            translationX = 12f - 59.6f * 0.32f,
            translationY = 12f - 54.05f * 0.32f,
        ) {
            // Dial, the long way round: `hud_cyan` in the launcher.
            addPath(
                PathParser().parsePathString(dial).toNodes(),
                stroke = SolidColor(HudColors.Cyan), strokeLineWidth = 8.5f,
                strokeLineCap = StrokeCap.Round,
            )
            // The redline that closes it: `hud_red`.
            addPath(
                PathParser().parsePathString(redline).toNodes(),
                stroke = SolidColor(HudColors.Red), strokeLineWidth = 8.5f,
                strokeLineCap = StrokeCap.Round,
            )
            // Needle and the hub it pivots on: `hud_amber`.
            addPath(PathParser().parsePathString(needle).toNodes(), fill = SolidColor(HudColors.Amber))
            addPath(PathParser().parsePathString(hub).toNodes(), fill = SolidColor(HudColors.Amber))
            addPath(PathParser().parsePathString(pivot).toNodes(), fill = SolidColor(HudColors.Bg))
        }.build()
    }

    val Pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "HudPin", defaultWidth = VB.dp, defaultHeight = VB.dp,
            viewportWidth = VB, viewportHeight = VB,
        ).addPath(
            PathParser().parsePathString(
                // Teardrop (clockwise) with a circle inside it (counter-clockwise).
                "M12,2.6 C8.4,2.6 5.5,5.5 5.5,9.1 C5.5,13.6 10.2,18.9 12,21.0 " +
                    "C13.8,18.9 18.5,13.6 18.5,9.1 C18.5,5.5 15.6,2.6 12,2.6 Z " +
                    "M12,11.9 A2.8,2.8 0 1 1 12,6.3 A2.8,2.8 0 1 1 12,11.9 Z",
            ).toNodes(),
            fill = SolidColor(HudColors.Cyan),
        ).build()
    }

    val Chip: ImageVector by lazy {
        ImageVector.Builder(
            name = "HudChip", defaultWidth = VB.dp, defaultHeight = VB.dp,
            viewportWidth = VB, viewportHeight = VB,
        ).addPath(
            PathParser().parsePathString(
                "M6.1,4.4 H17.9 A1.7,1.7 0 0 1 19.6,6.1 V17.9 A1.7,1.7 0 0 1 17.9,19.6 " +
                    "H6.1 A1.7,1.7 0 0 1 4.4,17.9 V6.1 A1.7,1.7 0 0 1 6.1,4.4 Z " +
                    "M7.0,7.0 V17.0 H17.0 V7.0 Z",
            ).toNodes(),
            fill = SolidColor(HudColors.Cyan),
        ).apply {
            // Pins, top and bottom.
            for (x in listOf(7.6f, 12.0f, 16.4f)) {
                addPath(
                    PathParser().parsePathString("M$x,2.3 L$x,4.4 M$x,19.6 L$x,21.7").toNodes(),
                    stroke = SolidColor(HudColors.Cyan), strokeLineWidth = W,
                    strokeLineCap = StrokeCap.Round,
                )
            }
            // Pins, left and right.
            for (y in listOf(7.6f, 12.0f, 16.4f)) {
                addPath(
                    PathParser().parsePathString("M2.3,$y L4.4,$y M19.6,$y L21.7,$y").toNodes(),
                    stroke = SolidColor(HudColors.Cyan), strokeLineWidth = W,
                    strokeLineCap = StrokeCap.Round,
                )
            }
        }.build()
    }

    val Cog: ImageVector by lazy {
        val teeth = (0 until 8).joinToString(" ") { i ->
            val a = Math.toRadians(i * 45.0)
            val nx = 12.0 + 8.4 * Math.cos(a)
            val ny = 12.0 + 8.4 * Math.sin(a)
            val bx = 12.0 + 5.4 * Math.cos(a)
            val by = 12.0 + 5.4 * Math.sin(a)
            "M%.2f,%.2f L%.2f,%.2f".format(bx, by, nx, ny)
        }
        ImageVector.Builder(
            name = "HudCog", defaultWidth = VB.dp, defaultHeight = VB.dp,
            viewportWidth = VB, viewportHeight = VB,
        ).addPath(
            // Ring: outer r 6.7 (wound clockwise), bore r 3.4 (wound the other way).
            PathParser().parsePathString(
                "M12,5.3 A6.7,6.7 0 1 1 12,18.7 A6.7,6.7 0 1 1 12,5.3 Z " +
                    "M12,8.6 A3.4,3.4 0 1 0 12,15.4 A3.4,3.4 0 1 0 12,8.6 Z",
            ).toNodes(),
            fill = SolidColor(HudColors.Cyan),
        ).addPath(
            PathParser().parsePathString(teeth).toNodes(),
            stroke = SolidColor(HudColors.Cyan), strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
        ).build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "HudSearch", defaultWidth = VB.dp, defaultHeight = VB.dp,
            viewportWidth = VB, viewportHeight = VB,
        ).addPath(
            // The circle as two half arcs: one closed subpath, no fill.
            PathParser().parsePathString(
                "M19,11 A8,8 0 1 1 3,11 A8,8 0 1 1 19,11 Z",
            ).toNodes(),
            stroke = SolidColor(HudColors.Cyan), strokeLineWidth = W,
            strokeLineCap = StrokeCap.Round,
        ).addPath(
            PathParser().parsePathString("M21,21 L16.65,16.65").toNodes(),
            stroke = SolidColor(HudColors.Cyan), strokeLineWidth = W,
            strokeLineCap = StrokeCap.Round,
        ).build()
    }

    val Chevron: ImageVector by lazy {
        ImageVector.Builder(
            name = "HudChevron", defaultWidth = VB.dp, defaultHeight = VB.dp,
            viewportWidth = VB, viewportHeight = VB,
        ).addPath(
            PathParser().parsePathString("M9,18 L15,12 L9,6").toNodes(),
            stroke = SolidColor(HudColors.Cyan), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        ).build()
    }

    val Car: ImageVector by lazy {
        ImageVector.Builder(
            name = "HudCar", defaultWidth = VB.dp, defaultHeight = VB.dp,
            viewportWidth = VB, viewportHeight = VB,
        ).addPath(
            // Sill, and the two shoulders that rise from it to the roofline's feet.
            PathParser().parsePathString(
                "M2.6,14.8 H21.4 M3.4,14.8 V12.6 A1.6,1.6 0 0 1 5.0,11.0 " +
                    "H19.0 A1.6,1.6 0 0 1 20.6,12.6 V14.8",
            ).toNodes(),
            stroke = SolidColor(HudColors.Cyan), strokeLineWidth = W,
            strokeLineCap = StrokeCap.Round,
        ).addPath(
            // Roofline: a cabin with raked ends, sitting on the body's top edge.
            PathParser().parsePathString("M7.0,11.0 L9.2,7.2 H14.8 L17.0,11.0").toNodes(),
            stroke = SolidColor(HudColors.Cyan), strokeLineWidth = W,
            strokeLineCap = StrokeCap.Round,
        ).addPath(
            // Wheels, drawn under the sill so the body line stays the car's baseline.
            PathParser().parsePathString(
                "M5.6,14.8 A2.2,2.2 0 0 0 10.0,14.8 M14.0,14.8 A2.2,2.2 0 0 0 18.4,14.8",
            ).toNodes(),
            stroke = SolidColor(HudColors.Cyan), strokeLineWidth = W,
            strokeLineCap = StrokeCap.Round,
        ).build()
    }
}
