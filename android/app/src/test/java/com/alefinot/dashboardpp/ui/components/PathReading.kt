package com.alefinot.dashboardpp.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/**
 * The path-data readers the glyph tests share.
 *
 * Compose 1.7.5 keeps a vector's paths to itself (`pathComponents` is gone and what replaced it is
 * not public API), so the winding that a fill actually obeys can only be read off the `String` the
 * glyph was built from — which is why the sets keep their outlines as `internal` data (`CogOutline`,
 * `WheelData`, `WarnData`, …) rather than only as a built `ImageVector`.
 *
 * These deliberately do *not* form a general SVG parser. They answer three questions and nothing
 * else: how many contours is this, where are a contour's vertices, and which way does it wind.
 *
 * Moved out of `BarLayoutTest`, which had its own private copies while the same rule was being
 * tested against glyphs that live nowhere near the bar.
 */

private val COMMAND = Regex("([MLQCZ])([^MLQCZ]*)")

/** One subpath per `M`: a contour, which is what a fill rule reads. */
internal fun subpaths(data: String): List<String> =
    Regex("M[^M]*").findAll(data).map { it.value.trim() }.toList()

/**
 * The vertices a fill sees, endpoint by endpoint. A curve contributes only its end point: a
 * control point is not on the outline, and counting it as a vertex makes a sampled arch
 * polygon cross itself — the quadratics that draw the wheel arches bulge outside the band.
 */
internal fun vertices(sub: String): List<Offset> {
    val points = mutableListOf<Offset>()
    for (command in COMMAND.findAll(sub)) {
        val numbers = command.groupValues[2].trim()
            .takeIf { it.isNotEmpty() }
            ?.split(Regex("[,\\s]+"))
            ?.map { it.toFloat() }
            .orEmpty()
        when (command.groupValues[1]) {
            "M", "L" -> numbers.chunked(2) { points += Offset(it[0], it[1]) }
            // Q takes four numbers, C six: the last two are the end point.
            "Q", "C" -> if (numbers.size >= 4) {
                points += Offset(numbers[numbers.size - 2], numbers[numbers.size - 1])
            }
        }
    }
    return points
}

/** Signed area, y growing down: positive is the direction a fill winds as ink here. */
internal fun signedArea(points: List<Offset>): Double =
    0.5 * points.indices.sumOf { i ->
        val a = points[i]
        val b = points[(i + 1) % points.size]
        a.x.toDouble() * b.y - b.x.toDouble() * a.y
    }

/** A circle drawn as two half arcs, and the direction it goes round in. */
internal data class Circle(val centre: Offset, val radius: Float, val sign: Double)

private val ARCS = Regex(
    """M\s*([-\d.]+),([-\d.]+)\s+A\s*([\d.]+),([-\d.]+)\s+0 1 ([01])\s+([-\d.]+),([-\d.]+)""" +
        """\s+A\s*([\d.]+),([-\d.]+)\s+0 1 ([01])\s+([-\d.]+),([-\d.]+)\s*Z""",
)

/**
 * Read a contour that claims to be a circle: two arcs of one radius, meeting at opposite ends of a
 * diameter. Everything is asserted, so the shape really is the circle the flags claim rather than
 * something that happens to parse.
 */
internal fun circle(sub: String): Circle {
    val m = requireNotNull(ARCS.matchEntire(sub.trim())) {
        "not a circle drawn as two half arcs: $sub"
    }.groupValues
    val start = Offset(m[1].toFloat(), m[2].toFloat())
    val mid = Offset(m[6].toFloat(), m[7].toFloat())
    val end = Offset(m[11].toFloat(), m[12].toFloat())
    val radius = m[3].toFloat()
    require(end == start && m[4] == m[3] && m[9] == m[3] && m[5] == m[10]) {
        "not a closed pair of equal arcs: $sub"
    }
    val centre = Offset((start.x + mid.x) / 2f, (start.y + mid.y) / 2f)
    require(kotlin.math.abs(hypot(mid.x - start.x, mid.y - start.y) - 2f * radius) < 0.02f) {
        "arcs are not semicircles of r$radius: $sub"
    }
    // sweep flag 1 = angle increasing in this y-down grid = the sign `signedArea` calls ink.
    return Circle(centre, radius, if (m[5] == "1") 1.0 else -1.0)
}

/** Every vertex of every contour in a mark, for the "nothing leaves the 24-unit grid" check. */
internal fun allVertices(data: String): List<Offset> =
    subpaths(data).flatMap { vertices(it) }

private val ANY_COMMAND = Regex("([MLQCAZ])([^MLQCAZ]*)")

/**
 * Every point an outline actually passes through, *including* the ends of arcs — which [vertices]
 * deliberately leaves out, because counting an arc's end as a corner made the wheel's sampled arches
 * self-cross. A bounding box is different: the extremes of a rounded body are arc ends (the camera's
 * body is 22 wide only because of the arc that reaches 22), so measuring walls or overflows has to
 * see them.
 */
internal fun contourPoints(data: String): List<Offset> {
    val points = mutableListOf<Offset>()
    for (command in ANY_COMMAND.findAll(data)) {
        val numbers = command.groupValues[2].trim()
            .takeIf { it.isNotEmpty() }
            ?.split(Regex("[,\\s]+"))
            ?.map { it.toFloat() }
            .orEmpty()
        // windowed(n, n) walks the list in non-overlapping groups and drops a trailing partial one.
        when (command.groupValues[1]) {
            "M", "L" -> numbers.windowed(2, 2) { points += Offset(it[0], it[1]) }
            "Q" -> numbers.windowed(4, 4) { points += Offset(it[2], it[3]) }
            "C" -> numbers.windowed(6, 6) { points += Offset(it[4], it[5]) }
            // A: rx ry rotation large-arc sweep x y — the end point is the sixth and seventh.
            "A" -> numbers.windowed(7, 7) { points += Offset(it[5], it[6]) }
        }
    }
    return points
}
