package com.alefinot.dashboardpp.ui.components

import java.util.Locale

/**
 * The reference-point arithmetic behind the trip page's trend chart: where the value ticks go, where
 * the time ticks go, and how each is spelled.
 *
 * It is pure and free of Compose for the same reason `sparkScale` is: a chart's most common bug is
 * not a crash, it is a picture that lies — labels that do not cover the data, a step that lands on
 * 3.7, a trace glued to the ceiling because the scale had no headroom. Those are arithmetic, so the
 * arithmetic lives where a test can hold it, and the painter only maps numbers to pixels.
 *
 * Both scales use a 1/2/5 ladder. That is the same reason a gauge face uses it: those steps land on
 * digits the eye already knows, so a label is a reference point instead of a figure to divide.
 */

/**
 * A value axis: the bottom and top of the drawn range, the distance between labels, and the labels
 * themselves. [min] and [max] are inclusive bounds of what gets drawn, so a trace can never leave
 * the plot; [ticks] are the values worth writing down inside it.
 */
data class ValueScale(
    val min: Double,
    val max: Double,
    val step: Double,
    val ticks: List<Double>,
) {
    /** Fraction of the plot height at which [value] sits: 0 at the top, 1 at the bottom. */
    fun fraction(value: Double): Float {
        val span = max - min
        if (span <= 0.0) return 0.5f
        return (1.0 - (value - min) / span).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * A 1/2/5 scale that covers `lo..hi` with about [target] gaps.
 *
 * The step is chosen by **counting the labels the axis would get**, not by dividing the span and
 * hoping: the smallest ladder value whose rounded-out band fits in `target + 1` gaps wins. That is
 * the whole of the headroom story. The bench build of this chart padded the range by 8 % *before*
 * rounding out, and rounding out then added a further step at each end — so a drive that logged 0 to
 * 94.8 km/h got an axis of **-20 … 120**: two wasted gridlines, a negative speed the wheel never
 * turned, and the trace squeezed into two thirds of the plot it was drawn in. A scale that rounds
 * out to the step already leaves air (half a step on average) and keeps every bound a round label,
 * so the pad is gone and the plot uses its height.
 *
 * A window whose values never move (a long flat stretch, an engine at one temperature) gets the same
 * band it would get on a phone screen at any zoom: it is widened by half a unit each way, so a flat
 * trace sits in the middle of the plot with labels above and below it rather than on a zero-height
 * scale that would divide by itself.
 */
fun niceScale(lo: Double, hi: Double, target: Int = 4): ValueScale {
    if (!lo.isFinite() || !hi.isFinite()) return ValueScale(0.0, 1.0, 0.25, listOf(0.0, 0.25, 0.5, 0.75, 1.0))
    var low = minOf(lo, hi)
    var high = maxOf(lo, hi)
    if (high - low < 1e-9) {
        // A flat window: widen around the value rather than build a zero-height scale. Half a unit
        // each way keeps single-digit quantities readable and puts the trace dead centre.
        low -= 0.5
        high += 0.5
    }
    val step = pickStep(low, high, target.coerceAtLeast(1) + 1)
    val min = round(Math.floor(low / step + 1e-9) * step)
    val max = round(Math.ceil(high / step - 1e-9) * step)
    val ticks = ArrayList<Double>(target + 2)
    var v = min
    var guard = 0
    while (v <= max + step * 0.5 && guard++ < 64) {
        ticks.add(round(v))
        v += step
    }
    return ValueScale(min, max, step, ticks)
}

/**
 * The smallest `1 / 2 / 5 × decade` step that puts no more than [maxGaps] between [low] and [high].
 *
 * Counting is the only way to get this right, because rounding the bounds out to the step adds up to
 * one gap more than the quotient promises: a 94.8 wide window on a step of 20 is 4.74 by division and
 * **5** by the labels it actually draws (0, 20, 40, 60, 80, 100).
 */
private fun pickStep(low: Double, high: Double, maxGaps: Int): Double {
    val rough = (high - low) / maxGaps
    // Start a decade under the estimate, so there is always somewhere to climb from, and give the
    // climb 16 decades: that spans figures far wider than anything this app can log.
    var magnitude = Math.pow(10.0, Math.floor(Math.log10(rough)) - 1.0)
    for (decade in 0 until 16) {
        for (m in LADDER) {
            val candidate = m * magnitude
            if (!candidate.isFinite() || candidate <= 0.0) continue
            val gaps = Math.ceil(high / candidate - 1e-9) - Math.floor(low / candidate + 1e-9)
            if (gaps >= 1.0 && gaps <= maxGaps + 1e-9) return candidate
        }
        magnitude *= 10.0
    }
    return rough
}

private val LADDER = doubleArrayOf(1.0, 2.0, 5.0)

/** Kills the `1.7999999999999998` a 0.2 ladder accumulates, so labels never wobble. */
private fun round(v: Double): Double = Math.round(v * 1e6) / 1e6

/**
 * Where the time labels go across the window `[fromSec, toSec)`, as seconds from the drive's start.
 *
 * Nice time steps, not nice decimals: a minute and a half is `1:30`, not `90`, and a step of 75 s
 * would put a label at every quarter-past-something no one can read at a glance. So the ladder is
 * 1/2/5/10/15/30 s, then 1/2/5/10/15/30 min, then 1/2/3/6/12 h, and the first step wide enough to
 * keep the count at or under [target] wins.
 *
 * The window's own end is always labelled: it is the reference point the owner actually wants ("the
 * drive ended at 10:20:57"), and without it the right-hand edge of every zoom is unanchored.
 */
fun timeTicks(fromSec: Int, toSec: Int, target: Int = 4): List<Int> {
    val span = (toSec - fromSec).coerceAtLeast(1)
    val steps = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200)
    var step = steps.last()
    for (s in steps) {
        // Ceiling, not truncation: a 70 s window with a 15 s step needs five labels, not four, and
        // the target is a ceiling on what gets drawn rather than on the quotient.
        if ((span + s - 1) / s <= target) {
            step = s
            break
        }
    }
    val out = ArrayList<Int>(target + 2)
    var t = ((fromSec + step - 1) / step) * step
    var guard = 0
    while (t < toSec && guard++ < 64) {
        out.add(t)
        t += step
    }
    if (out.isEmpty() || out.last() != toSec) out.add(toSec)
    return out
}

/**
 * The time labels that actually fit side by side: [ticks] in the same order, minus any whose label
 * would be laid on top of the one before it.
 *
 * Nice steps are arithmetic, and arithmetic does not know a clock label is 52 dp wide: the bench build
 * drew `15:32` and `16:20` **touching** at the right edge of a 4 h 47 m drive, and the same thing
 * happened at `12:22`/`12:29` one pan later. So this knows the geometry the painter uses, in fractions
 * of the plot's width: [labelWidth] is the room one label needs (its own width plus the air it must
 * keep) and [gutterWidth] is the value gutter that sits to the left of the plot.
 *
 * The geometry is not a uniform gap, which is the part that bit twice. A label is centred on its tick,
 * so it overhangs half its width either side, and the pair at the edges is pulled inside the plot
 * rather than clipped by it — so the window's own end, whose tick is at the far edge, sits **half a
 * label to the left** of where its gridline is, and needs proportionally more room from its neighbour
 * than two labels in the middle do. Measuring the ticks against each other, or against the tick list's
 * own span instead of the window's, both miss that.
 *
 * When a label has to go, the one that goes is never the window's own end: dropping a gridline is
 * invisible next to dropping the figure that says where this zoom stops. So the end is kept and the
 * neighbour in its way is dropped — possibly cascading, which only ever makes the axis sparser.
 */
fun fitTimeTicks(
    ticks: List<Int>,
    windowFromSec: Int,
    windowToSec: Int,
    labelWidth: Float,
    gutterWidth: Float,
): List<Int> {
    if (ticks.size < 2 || labelWidth <= 0f) return ticks
    val span = (windowToSec - windowFromSec).coerceAtLeast(1).toFloat()
    val half = labelWidth / 2f
    // The left edge the painter will give this tick: centred on it, then pulled inside the plot. In
    // fractions of the plot's width, with the gutter counted as part of the plot's real estate because
    // a label is allowed to sit over it.
    val rightMost = (gutterWidth + 1f - labelWidth).coerceAtLeast(0f)
    fun left(t: Int): Float =
        (gutterWidth + (t - windowFromSec) / span - half).coerceIn(0f, rightMost)

    val out = ArrayList<Int>(ticks.size)
    for (t in ticks) {
        if (out.isNotEmpty() && left(t) - left(out.last()) < labelWidth) continue
        out.add(t)
    }
    val end = ticks.last()
    if (out.isNotEmpty() && out.last() != end) {
        // Make room for the end by dropping whatever stands in it, as far back as it takes.
        while (out.isNotEmpty() && left(end) - left(out.last()) < labelWidth) out.removeAt(out.size - 1)
        out.add(end)
    }
    return out
}

/**
 * Below this window width a clock label needs its seconds; above it, seconds are noise. Six hundred
 * seconds (ten minutes) is the point where a 4-label axis has more than a minute between labels, so
 * the seconds stop carrying information there.
 */
fun showSecondsOnAxis(spanSec: Int): Boolean = spanSec <= 600

/**
 * A value tick as the chart writes it: no decimals for a step of 1 or more, two below it. A step of
 * 0.5 km/L of instantaneous economy is a real figure and must not be rounded to `1`, while `60.0` on
 * a speed axis is two characters of noise.
 */
fun formatTick(value: Double, step: Double): String {
    val decimals = if (step >= 1.0) 0 else if (step >= 0.1) 1 else 2
    return String.format(Locale.US, "%.${decimals}f", value)
}
