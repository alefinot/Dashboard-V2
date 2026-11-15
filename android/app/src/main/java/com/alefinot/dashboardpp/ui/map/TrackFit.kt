package com.alefinot.dashboardpp.ui.map

import androidx.compose.ui.geometry.Offset
import com.alefinot.dashboardpp.trips.TrackPoint
import kotlin.math.min

/**
 * Where a preview track's points land inside a card's square, decided without
 * a canvas so the rule can be tested on the JVM.
 *
 * The shape keeps its aspect ratio — a drive must not look stretched to fill
 * the box — is drawn north up, and is centred inside [inset] of every edge so
 * the start and end dots are never flush with the rounded corner. Longitude is
 * unwrapped eastwards when a track crosses the antimeridian: the naive span of
 * a drive through ±180 is nearly the whole world, which would draw both halves
 * on top of each other in the middle.
 */
object TrackFit {

    /** Below this a span is GPS noise, not an extent (about 0.1 mm). */
    const val MIN_SPAN_DEG = 1e-9

    /** Offsets in the same coordinate space as [width] and [height]; empty when nothing can be drawn. */
    fun project(points: List<TrackPoint>, width: Float, height: Float, inset: Float): List<Offset> {
        if (points.isEmpty()) return emptyList()
        val boxW = width - 2f * inset
        val boxH = height - 2f * inset
        if (boxW <= 0f || boxH <= 0f) return emptyList()

        val lons = points.map { it.lon }
        val naiveSpan = (lons.maxOrNull() ?: 0.0) - (lons.minOrNull() ?: 0.0)
        val wrapped = naiveSpan > 180.0
        val xs = if (wrapped) lons.map { if (it < 0.0) it + 360.0 else it } else lons
        val minLon = xs.minOrNull()!!
        val maxLon = xs.maxOrNull()!!
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }

        val spanX = maxLon - minLon
        val spanY = maxLat - minLat
        val scale = when {
            // A single position, or a vehicle parked with a live fix: no extent
            // to scale by, so the dot belongs in the middle of the box.
            spanX < MIN_SPAN_DEG && spanY < MIN_SPAN_DEG -> 0.0
            spanX < MIN_SPAN_DEG -> boxH / spanY
            spanY < MIN_SPAN_DEG -> boxW / spanX
            else -> min(boxW / spanX, boxH / spanY)
        }
        val drawnW = spanX * scale
        val drawnH = spanY * scale
        val left = inset + (boxW - drawnW) / 2.0
        val top = inset + (boxH - drawnH) / 2.0

        return xs.mapIndexed { i, lon ->
            Offset(
                x = (left + (lon - minLon) * scale).toFloat(),
                y = (top + (maxLat - points[i].lat) * scale).toFloat(),
            )
        }
    }
}
