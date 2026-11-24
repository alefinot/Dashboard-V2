package com.alefinot.dashboardpp.trips

import kotlin.math.roundToInt

/**
 * The line a recorded drive is drawn with, bounded to what a phone can paint per frame.
 *
 * The live map has a budget it can spend freely: it grows one position at a time, and
 * [LiveTrack] thins as the drive goes. A sealed drive arrives all at once — a twelve-hour file is
 * ~43 000 positions — and osmdroid rebuilds the projected part of a polyline on **every draw**
 * (`PolyOverlayWithIW.draw`), so the point count is the frame cost of every pan, pinch and
 * repaint on the detail page, not a one-off at open.
 *
 * So the page draws a thinned copy of the same line, built by the same redundancy rule the live
 * map uses ([deviationM], exported from `LiveRoute.kt` so there is one definition): a position is
 * dropped when it lies no further than [BASE_TOLERANCE_M] from the line joining the kept position
 * before it and the next raw position after it. Two metres is under the GPS noise the fixes carry,
 * so the drawn shape stays as true as the drive it came from.
 *
 * "Measured in metres" is the rule; "under [BUDGETS] positions" is the ceiling. On a real drive
 * the 2 m pass usually lands inside 3 000 by itself. When it does not — an hour of dense city
 * fixes, a drive across a whole country — the tolerance is allowed to climb exactly the way the
 * live track lets it climb, doubling up to [MAX_TOLERANCE_M], and only a pathological shape falls
 * back to an even stride. The first and last positions are always kept: START and END are pinned
 * to them.
 */
object RouteThinning {

    /** The live map's own first tolerance, in metres. */
    const val BASE_TOLERANCE_M = 2.0

    /** Ceiling for the tolerance; reached only by doubling from [BASE_TOLERANCE_M]. */
    const val MAX_TOLERANCE_M = 64.0

    /**
     * The point budgets a detail page may draw, finest first.
     *
     * 3 000 is the picture the maintainer approved (2026-11-24): a 30 km drive at two metres still
     * reads as the road it was, and a 43 000-position file costs a thrift of its vertices instead
     * of a 14× draw. The coarser rungs are not a different map — they are the same drive drawn
     * with less detail when the phone says it cannot keep up (see `AdaptiveQuality`).
     */
    val BUDGETS = intArrayOf(3_000, 1_500, 750, 400)

    /**
     * Drop every position a straight line already covers, at [toleranceM].
     *
     * Same sequential pass as [LiveTrack]'s own thinning, over a list instead of a growing track.
     * A list of two has nothing to drop and is returned as it is — the identity matters to the
     * map's own guard, which redraws only when the list it is handed is a different object.
     */
    fun thin(points: List<TrackPoint>, toleranceM: Double = BASE_TOLERANCE_M): List<TrackPoint> {
        val n = points.size
        if (n < 3 || toleranceM <= 0.0) return points
        val out = ArrayList<TrackPoint>(n)
        out.add(points[0])
        var kept = points[0]
        for (i in 1 until n - 1) {
            if (deviationM(kept, points[i], points[i + 1]) > toleranceM) {
                out.add(points[i])
                kept = points[i]
            }
        }
        out.add(points[n - 1])
        return out
    }

    /**
     * The same drive at every budget the page can draw, finest first.
     *
     * A rung is the 2 m line whenever that already fits its budget — the budgets only ever make
     * the line coarser, never finer, so a short drive is one rung and the page has nothing left
     * to spend. Rungs that would be the same size as the one above are dropped rather than drawn
     * twice: a step that changes no pixel is not a step.
     */
    fun ladder(points: List<TrackPoint>, budgets: IntArray = BUDGETS): List<List<TrackPoint>> {
        if (points.size < 2) return listOf(points)
        val natural = thin(points)
        val out = ArrayList<List<TrackPoint>>(budgets.size)
        for (budget in budgets) {
            if (budget < 2) continue
            val rung = if (natural.size <= budget) natural else fit(natural, budget)
            if (out.isNotEmpty() && out.last().size == rung.size) continue
            out.add(rung)
            // The 2 m line is already inside this budget, so it is inside every coarser one too.
            if (rung === natural) break
        }
        return if (out.isEmpty()) listOf(natural) else out
    }

    /** The thinnest line that fits [budget]: climb the tolerance before giving up on the rule. */
    private fun fit(points: List<TrackPoint>, budget: Int): List<TrackPoint> {
        var tolerance = BASE_TOLERANCE_M * 2.0
        while (tolerance <= MAX_TOLERANCE_M) {
            val thinned = thin(points, tolerance)
            if (thinned.size <= budget) return thinned
            tolerance *= 2.0
        }
        return stride(points, budget)
    }

    /**
     * Last resort for a shape no tolerance can shrink (a parking-lot log that doubles back on
     * itself thousands of times): an even walk over the thinned line. Both ends still land, so
     * START and END stay where the drive did.
     */
    private fun stride(points: List<TrackPoint>, budget: Int): List<TrackPoint> {
        val step = (points.size - 1).toDouble() / (budget - 1)
        val out = ArrayList<TrackPoint>(budget)
        for (i in 0 until budget) {
            val index = (i * step).roundToInt().coerceIn(0, points.lastIndex)
            out.add(points[index])
        }
        return out
    }
}
