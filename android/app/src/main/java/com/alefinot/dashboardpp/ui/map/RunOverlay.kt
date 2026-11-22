package com.alefinot.dashboardpp.ui.map

import android.graphics.Paint
import android.graphics.Point
import androidx.compose.ui.graphics.toArgb
import com.alefinot.dashboardpp.trips.AccelRun
import com.alefinot.dashboardpp.ui.theme.HudColors
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * The 0-50 run lines a map wears, and the tap that reads one of them out.
 *
 * Both maps draw runs — the live map while the drive happens, the detail page from the
 * recorded file afterwards — and a run has to look and behave the same in both, or the owner
 * is taught two languages for one thing. So the ink, the width and the tap radius live here
 * once, and neither map gets to invent its own.
 *
 * Each run is a pair: [HudColors.Yellow] at [RUN_DP] over a casing in the ink the ground needs
 * ([MapStyles.casing]), the same treatment the drive's own cyan gets, because a run lies exactly
 * on the line it is part of and would turn into a smear over aerial imagery without it. The run
 * whose result is on screen takes a white casing — on a line this thin that is the only
 * difference a glance can find, and it is the same white the result card's border is.
 *
 * The tap is measured here too, in pixels against the projected polyline, because touches in a
 * map slot belong to the platform view: a page can be told where to paint but never what was
 * touched under a finger, and osmdroid's own clickable overlays cannot answer a question the page
 * asks. Nearest within [TAP_DP] wins, so two runs drawn over each other give the finger the
 * decision instead of list order.
 */
internal class RunOverlay {

    /** One run's two lines, and the geometry they are stroked from — the tap is measured on it. */
    private class Ink(val line: Polyline, val casing: Polyline, val points: ArrayList<GeoPoint>)

    private val drawn = ArrayList<Pair<AccelRun, Ink>>()

    /**
     * The run whose result is on screen: the one line dressed in white.
     *
     * Identity, not `id`: a derived list is rebuilt whenever a trip is opened, and the object the
     * page holds is the one it was given, so `===` is both the cheap test and the right one.
     */
    var selected: AccelRun? = null

    /** True when nothing is drawn — the tap test then has nothing to measure against. */
    val isEmpty: Boolean get() = drawn.isEmpty()

    /**
     * True when [runs] is exactly what is on screen, run for run.
     *
     * Both callers hand out the list by identity — the frame lane publishes a new list only when a
     * run closes, the detail page builds one `remember`-ed per file — so this is a cheap guard in
     * front of a rebuild, and the frames that mention no new result never reach [sync].
     */
    fun sameAs(runs: List<AccelRun>): Boolean {
        if (drawn.size != runs.size) return false
        for (i in runs.indices) {
            if (drawn[i].first !== runs[i]) return false
        }
        return true
    }

    /**
     * Put the drawn lines in step with [runs], inserting each pair at [insertAt] plus two per run.
     *
     * A run is finished business the moment it closes — nothing about a kept run ever moves — so
     * this is a rebuild over at most a couple of hundred finished items and never an append
     * problem. Pairs go in list order, so where two runs cross the newer one is on top, which is
     * the same chronology the drive's own pieces follow.
     *
     * [insertAt] is the caller's, because only the caller knows what it has already put down: the
     * live map drops these above its drive lines and its tail, the detail map above its one route.
     * [style] is optional for the same reason: a holder that has already dressed these lines — every
     * holder does, on the first pass of its `update` lambda — need not carry it again.
     */
    fun sync(view: MapView, runs: List<AccelRun>, insertAt: Int, style: MapStyle? = null) {
        if (sameAs(runs)) return
        if (style != null) dressed = style
        removeFrom(view)
        for ((index, run) in runs.withIndex()) {
            val points = ArrayList<GeoPoint>(run.points.size)
            for (point in run.points) points.add(GeoPoint(point.lat, point.lon))
            // One point is not a line, and the tracker can hand one out (a launch whose only fix
            // was the finish frame). It is drawn anyway rather than skipped: [drawn] stays index
            // for index with the caller's list, which is what lets [sameAs] answer in one pass, and
            // a single-point polyline costs a draw of nothing.
            val line = newLine()
            val casing = newLine()
            line.setPoints(points)
            casing.setPoints(points)
            val at = insertAt + 2 * index
            view.overlayManager.add(at, casing)
            view.overlayManager.add(at + 1, line)
            drawn.add(run to Ink(line, casing, points))
        }
        ink(view)
    }

    /**
     * The ground these lines were last dressed for.
     *
     * Held here so a tap — the one change that re-inks without a style change — can say "dress
     * them again" without knowing anything about the map it sits on.
     */
    private var dressed: MapStyle? = null

    /** The yellow, and the one run in [selected] dressed in white. */
    fun ink(view: MapView) {
        val casingInk = MapStyles.casing(dressed ?: return)
        dress(view, casingInk)
    }

    /** The ground changed under these lines: remember the new one and re-dress them. */
    fun ink(view: MapView, style: MapStyle) {
        dressed = style
        ink(view)
    }

    private fun dress(view: MapView, casingInk: MapCasing) {
        val density = view.resources.displayMetrics.density
        val highlight = HudColors.White.toArgb()
        val yellow = HudColors.Yellow.toArgb()
        for ((run, pair) in drawn) {
            val edge = pair.casing.getOutlinePaint()
            edge.color = if (run === selected) highlight else casingInk.colorArgb
            edge.strokeWidth = (RUN_DP + casingInk.extraDp) * density
            val line = pair.line.getOutlinePaint()
            line.color = yellow
            line.strokeWidth = RUN_DP * density
        }
        view.invalidate()
    }

    /**
     * The run under `(x, y)` in view pixels, or null.
     *
     * Pixels, not metres, at the zoom on screen: what the owner aimed at is a thing they could see.
     * A radius in ground distance would make a run untappable at a city zoom and, at a
     * parking-lot zoom, steal taps from ground the owner meant.
     */
    fun hit(view: MapView, x: Float, y: Float): AccelRun? {
        if (drawn.isEmpty()) return null
        val radius = TAP_DP * view.resources.displayMetrics.density
        val radiusSq = radius * radius
        val projection = view.projection
        val screen = Point()
        var best: AccelRun? = null
        var bestSq = Float.MAX_VALUE
        for ((run, ink) in drawn) {
            val points = ink.points
            if (points.size < 2) continue
            // Walk the polyline as a chain of segments: at 1 Hz a run is four or five points, so
            // this is a handful of arithmetic per run, and the whole test is cheaper than the
            // overlay hit test it replaces would have been.
            projection.toPixels(points[0], screen)
            var ax = screen.x.toFloat()
            var ay = screen.y.toFloat()
            for (i in 1 until points.size) {
                projection.toPixels(points[i], screen)
                val bx = screen.x.toFloat()
                val by = screen.y.toFloat()
                val d2 = pointToSegmentSq(x, y, ax, ay, bx, by)
                // Two points that project to the same pixel make a zero-length segment, and the
                // formula falls back to the point distance on its own - no special case to get wrong.
                if (d2 <= radiusSq && d2 < bestSq) {
                    bestSq = d2
                    best = run
                }
                ax = bx
                ay = by
            }
        }
        return best
    }

    /** Take the lines off this view and forget them: the drive they belonged to is gone. */
    fun removeFrom(view: MapView) {
        for ((_, ink) in drawn) {
            view.overlayManager.remove(ink.line)
            view.overlayManager.remove(ink.casing)
        }
        clear()
    }

    /**
     * Forget the bookkeeping only, for a view that is going away: its overlay manager goes with
     * it, and there is nothing left to take the lines off.
     */
    fun clear() {
        drawn.clear()
        selected = null
    }

    private fun newLine(): Polyline {
        val poly = Polyline()
        val paint = poly.getOutlinePaint()
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        return poly
    }

    companion object {
        /** How close a tap has to come, in dp, for the line to count as the thing aimed at. */
        const val TAP_DP = 24f

        /** A run's width — the drive's own width, so the yellow reads as a painted-on section. */
        private const val RUN_DP = 5f
    }
}

/**
 * Squared distance from `(px, py)` to the segment `(ax, ay)-(bx, by)`, in the caller's units.
 *
 * Squared, because every caller has a squared radius at hand and the square root is the one part
 * of this that costs anything. The projection is clamped to the segment, so a point beyond an end
 * measures to that end and not to the line's extension — which for a tap means a finger past the
 * last fix of a run is measured against the fix, where the run actually is.
 *
 * A degenerate segment (both ends at one pixel, which a run of two fixes 15 cm apart really is at
 * a city zoom) takes the `else` branch: `t` is 0 and the answer is the distance to that point.
 */
internal fun pointToSegmentSq(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Float {
    val dx = bx - ax
    val dy = by - ay
    val length = dx * dx + dy * dy
    val t = if (length > 0f) {
        ((px - ax) * dx + (py - ay) * dy / length).coerceIn(0f, 1f)
    } else {
        0f
    }
    val ox = px - (ax + t * dx)
    val oy = py - (ay + t * dy)
    return ox * ox + oy * oy
}
