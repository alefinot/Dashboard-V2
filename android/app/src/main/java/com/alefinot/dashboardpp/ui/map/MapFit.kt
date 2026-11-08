package com.alefinot.dashboardpp.ui.map

import org.osmdroid.util.GeoPoint
import kotlin.math.min

object MapFit {

    const val STATIONARY_ZOOM = 15.0

    /** Below this the box has no extent — about 0.1 mm, far under GPS noise. */
    const val MIN_SPAN_DEG = 1e-9

    fun usable(points: List<GeoPoint>): List<GeoPoint> = points.filter {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude >= -90.0 && it.latitude <= 90.0 &&
            it.longitude >= -180.0 && it.longitude <= 180.0
    }

    data class Plan(
        val center: GeoPoint,
        val zoom: Double,
        val useBoundingBox: Boolean,
    )

    fun plan(points: List<GeoPoint>, width: Int, height: Int, maxZoom: Double): Plan? {
        if (points.isEmpty()) return null
        var minLat = points.first().latitude
        var maxLat = minLat
        var minLon = points.first().longitude
        var maxLon = minLon
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        val center = GeoPoint((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
        val hasExtent = maxLat - minLat > MIN_SPAN_DEG && maxLon - minLon > MIN_SPAN_DEG
        val measured = width > 0 && height > 0
        return Plan(
            center = center,
            zoom = min(STATIONARY_ZOOM, maxZoom),
            useBoundingBox = hasExtent && measured,
        )
    }
}
