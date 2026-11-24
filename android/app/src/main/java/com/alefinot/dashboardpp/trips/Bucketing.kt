package com.alefinot.dashboardpp.trips

/**
 * Bounding a recorded drive's chart series to something a phone draws per frame.
 *
 * 43 000 samples make a 43 000-point path, and no screen is 43 000 pixels wide: everything past
 * the screen's own width costs paint time and shows nothing. So a series is cut into buckets and
 * every bucket collapses to a few figures that still tell the truth about everything inside it.
 *
 * Two reducers live here, because they answer two different questions:
 *  - [bucketMax] is the **one-shot** form: a fixed point budget decided without looking at the
 *    screen. The trip page stopped using it on 2026-11-24 (a series reduced once at read time cannot
 *    be zoomed), so it currently has no caller in the app and is kept for the next caller that has
 *    no window to ask about — say the word and it goes.
 *  - [trendEnvelope] is the **per-view** form: the trip page's trend chart asks it for the window
 *    it is currently showing, at the width the plot actually has, so zooming always has something
 *    finer underneath (`Implementation plans/TRIP-FIGURES-AND-ZOOMABLE-CHARTS-2026-11-24.md` §5).
 *
 * These live beside the trip data rather than in the HUD components because both work on the
 * reader's own arrays and neither knows anything about Compose.
 */
fun bucketMax(values: List<Double>, maxPoints: Int): List<Double> {
    if (values.size <= maxPoints || maxPoints < 2) return values
    val out = ArrayList<Double>(maxPoints)
    val bucket = (values.size + maxPoints - 1) / maxPoints
    var i = 0
    while (i < values.size) {
        var best = values[i]
        val end = minOf(i + bucket, values.size)
        for (j in i until end) if (values[j] > best) best = values[j]
        out.add(best)
        i = end
    }
    return out
}

fun bucketMax(values: DoubleArray, maxPoints: Int): DoubleArray {
    if (values.size <= maxPoints || maxPoints < 2) return values
    val out = DoubleArray(maxPoints)
    val bucket = (values.size + maxPoints - 1) / maxPoints
    var outIndex = 0
    var i = 0
    while (i < values.size && outIndex < maxPoints) {
        var best = values[i]
        val end = minOf(i + bucket, values.size)
        for (j in i until end) if (values[j] > best) best = values[j]
        out[outIndex++] = best
        i = end
    }
    return if (outIndex == maxPoints) out else out.copyOf(outIndex)
}

/**
 * One chartable quantity of a recorded drive: the values in the order they were logged, and the
 * whole seconds from the drive's start at which each was taken.
 *
 * Raw rather than reduced. The trip page used to bucket every series to 600 points during the read
 * (`TripSeries.MAX_POINTS`), which bounded memory nicely and made zooming impossible: the page kept
 * one fixed-resolution copy of the drive and could not show it any finer than it had been thrown
 * away. Keeping the samples instead costs about 8 bytes each — a twelve-hour drive is ~43 000
 * samples, so ~350 KB per channel while the page is open, against the peak the reader already paid
 * for the `DoubleBuffer`s it was filling — and the reduction then happens per view, where the width
 * of the actual plot and the current zoom level are known.
 *
 * `Float`/`Int`, not `Double`/`Long`: these arrays exist only to be drawn, and half the memory is
 * the same argument the CSV reader makes about the same file.
 */
class TrendChannel(val values: FloatArray, val seconds: IntArray) {
    val count: Int get() = values.size

    /** Non-empty, with the two arrays the same length — what [trendEnvelope] requires. */
    val isPlottable: Boolean get() = values.size > 0 && seconds.size == values.size

    /** The last second the channel holds a sample at, or 0 when it holds none. */
    fun lastSecond(): Int = if (seconds.isEmpty()) 0 else seconds[seconds.size - 1]

    /** Mean of the channel's **own** samples, or 0 for an empty channel. */
    fun meanValue(): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }

    /** Maximum of the channel's own samples, or 0 for an empty channel. */
    fun maxValue(): Double {
        var best = -Float.MAX_VALUE
        for (v in values) if (v > best) best = v
        return if (best == -Float.MAX_VALUE) 0.0 else best.toDouble()
    }

    companion object {
        val EMPTY = TrendChannel(FloatArray(0), IntArray(0))
    }
}

/**
 * A reusable destination for [trendEnvelope]. The chart holds one and passes it every frame, so a
 * pan or a zoom allocates nothing: only the samples currently on screen are touched and the results
 * land in these arrays.
 *
 * Read `0 until count`. A bucket that held no sample keeps `hi[j] < lo[j]`, so the painter can skip
 * it instead of drawing a line from a stale value.
 */
class EnvelopeSink(capacity: Int = DEFAULT_CAPACITY) {
    val lo = FloatArray(capacity)
    val hi = FloatArray(capacity)
    val mean = FloatArray(capacity)
    val midTime = IntArray(capacity)
    var count = 0

    companion object {
        /**
         * Wider than any phone's plot in pixels, so a full-width bucket is the only thing that can
         * ask for less and the sink never has to grow mid-gesture.
         */
        const val DEFAULT_CAPACITY = 1500
    }
}

/**
 * Reduce [channel]'s samples inside the half-open window `[fromSec, toSec)` into at most [buckets]
 * equal-time buckets, each keeping its own low, high, mean and mid-time; returns how many buckets
 * were filled, and clamps the sink's `count` to it.
 *
 * This is the chart's approximation, and the standard it has to hold
 * (`Implementation plans/TRIP-FIGURES-AND-ZOOMABLE-CHARTS-2026-11-24.md` §5) is stated in numbers:
 *  - **never more than one bucket per pixel** — the caller passes the plot's width, and below one
 *    bucket per pixel the extra points cost paint time and show nothing;
 *  - **native resolution below the pixel budget** — when the window holds no more samples than there
 *    are buckets, every sample gets its own bucket, so no zoom level ever shows less than the raw
 *    drive;
 *  - **extremes survive** — a bucket keeps its low and its high, so a spike is not averaged away and
 *    neither is an altitude dip. `bucketMax` above could only ever show the top of a bucket, which
 *    is one of the reasons the old sparklines read as decoration.
 *
 * The window is found by binary search on [TrendChannel.seconds], which the reader fills in
 * non-decreasing order, so the loop touches only the samples in view.
 */
fun trendEnvelope(channel: TrendChannel, fromSec: Int, toSec: Int, buckets: Int, sink: EnvelopeSink): Int {
    val values = channel.values
    val seconds = channel.seconds
    val n = minOf(values.size, seconds.size)
    val from = lowerBound(seconds, n, maxOf(0, fromSec))
    val to = lowerBound(seconds, n, maxOf(0, toSec))
    val inView = to - from
    val width = maxOf(1, minOf(buckets, sink.lo.size))
    if (inView <= 0) {
        sink.count = 0
        return 0
    }
    // At or below the pixel budget: stop approximating. Each sample is its own bucket, lo == hi.
    if (inView <= width) {
        for (k in 0 until inView) {
            val v = values[from + k]
            sink.lo[k] = v
            sink.hi[k] = v
            sink.mean[k] = v
            sink.midTime[k] = seconds[from + k]
        }
        sink.count = minOf(inView, width)
        return sink.count
    }
    val windowStart = maxOf(0, fromSec)
    val windowEnd = maxOf(windowStart + 1, toSec)
    val span = (windowEnd - windowStart).toDouble()
    val bucketWidth = span / width
    var i = from
    var filled = 0
    var prevTime = windowStart
    for (b in 0 until width) {
        val t0 = (windowStart + bucketWidth * b).toInt().coerceIn(windowStart, windowEnd)
        val bound = (windowStart + bucketWidth * (b + 1)).toInt().coerceIn(t0, windowEnd)
        if (i >= to) {
            // Past the last sample in the window; keep the bucket marked empty rather than stale.
            sink.lo[b] = Float.MAX_VALUE
            sink.hi[b] = -Float.MAX_VALUE
            sink.mean[b] = 0f
            sink.midTime[b] = t0
            continue
        }
        // The bucket's left edge is somewhere between the previous bucket's last sample and this one;
        // the mid-time is taken around the samples actually inside, which is what puts a spike at the
        // x where it happened rather than at its bucket's first index.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var sum = 0.0
        var taken = 0
        var lastTime = t0
        while (i < to && seconds[i] < bound) {
            val v = values[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
            sum += v
            taken++
            if (taken == 1) prevTime = seconds[i]
            lastTime = seconds[i]
            i++
        }
        if (taken > 0) {
            sink.lo[b] = lo
            sink.hi[b] = hi
            sink.mean[b] = (sum / taken).toFloat()
        } else {
            sink.lo[b] = Float.MAX_VALUE
            sink.hi[b] = -Float.MAX_VALUE
            sink.mean[b] = 0f
        }
        sink.midTime[b] = (prevTime + lastTime) / 2
        filled = b + 1
    }
    sink.count = width
    return filled
}

/** Index of the first element >= [value] in the sorted prefix `0 until n`. */
private fun lowerBound(a: IntArray, n: Int, value: Int): Int {
    var lo = 0
    var hi = n
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] < value) lo = mid + 1 else hi = mid
    }
    return lo
}
