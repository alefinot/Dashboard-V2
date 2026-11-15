package com.alefinot.dashboardpp.trips

import java.util.Locale

/** One position of a trip's preview shape. */
data class TrackPoint(val lat: Double, val lon: Double)

/**
 * The shape of a drive, bounded so a card can draw it.
 *
 * The Trips list wants to show where a drive went without opening its detail
 * page, and the detail page's own reader parses the whole file (a 12 h trip is
 * ~43k lines). A card has room for a few dozen segments, so this keeps a
 * decimated copy instead: once [maxPoints] positions are held, every second one
 * is dropped before the next is appended. The first position never moves and
 * the newest sample is always the last point, which is all a thumbnail needs.
 *
 * Coordinates are rejected by the same rule the map applies (`hasPosition`),
 * so a file with a poisoned line cannot put a dot in the ocean.
 */
class TrackPreview(private val maxPoints: Int = MAX_POINTS) {
    private val points = ArrayList<TrackPoint>(maxPoints + 1)

    fun add(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite()) return
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        // A unit with no fix reports 0,0; it is not a place on Earth.
        if (lat == 0.0 && lon == 0.0) return
        if (points.size >= maxPoints) reduce()
        points.add(TrackPoint(lat, lon))
    }

    /** Halve the list, keeping the oldest sample and every other one after it. */
    private fun reduce() {
        var kept = 0
        var i = 0
        while (i < points.size) {
            points[kept++] = points[i]
            i += 2
        }
        while (points.size > kept) points.removeAt(points.size - 1)
    }

    fun points(): List<TrackPoint> = points.toList()

    companion object {
        /**
         * Enough to keep a 40 km drive recognisable (a point roughly every
         * 300 m) while the encoded form stays around 1.5 kB in the index cache.
         */
        const val MAX_POINTS = 128
    }
}

/**
 * Text form of a preview track for the summary cache, stored beside the file it
 * describes. `v1` alone means "this file was scanned and has no route" — a
 * deliberate answer that keeps a fix-less drive from being re-read on every
 * list pass. A *missing* row is the one and only "not scanned yet".
 */
object TrackPreviewCodec {
    const val V = "v1"

    fun encode(points: List<TrackPoint>): String {
        if (points.isEmpty()) return V
        return buildString(points.size * 20) {
            append(V)
            for (p in points) {
                append(';')
                append(d(p.lat))
                append(',')
                append(d(p.lon))
            }
        }
    }

    /** Null when the text is not a preview; an empty list when it holds no route. */
    fun decode(text: String): List<TrackPoint>? {
        if (!text.startsWith(V)) return null
        if (text.length == V.length) return emptyList()
        val body = text.substring(V.length + 1)
        val out = ArrayList<TrackPoint>()
        for (part in body.split(';')) {
            val pair = part.split(',')
            if (pair.size != 2) return null
            val lat = pair[0].toDoubleOrNull() ?: return null
            val lon = pair[1].toDoubleOrNull() ?: return null
            out.add(TrackPoint(lat, lon))
        }
        return out
    }

    /** 5 decimals is about a metre: under GPS noise, so the shape is unchanged. */
    private fun d(v: Double) = String.format(Locale.US, "%.5f", v)
}
