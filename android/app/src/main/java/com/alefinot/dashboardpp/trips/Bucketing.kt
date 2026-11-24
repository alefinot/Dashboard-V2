package com.alefinot.dashboardpp.trips

/**
 * Bounding a recorded drive's chart series to something a phone draws per frame.
 *
 * 43 000 samples make a 43 000-point path, and no screen is 43 000 pixels wide: everything past
 * the screen's own width costs paint time and shows nothing. So each series is cut into at most
 * `maxPoints` buckets, and every bucket collapses to its **maximum** — a spike in a long trip
 * still shows, where averaging would have erased the one moment the owner is looking for.
 *
 * These live beside the trip data rather than in the HUD components because the detail page's
 * one-pass reader is what buckets a series now: the page draws what the file produced, and no
 * composition ever walks the samples again.
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
