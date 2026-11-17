package com.alefinot.dashboardpp.ui.map

import com.alefinot.dashboardpp.trips.TrackPoint
import com.alefinot.dashboardpp.trips.TripAccumulator

/**
 * Which way the live position is travelling.
 *
 * The wire snapshot carries no course, so the direction has to come from the positions the
 * recorder is already collecting. The naive answer - the bearing between the last two fixes -
 * is a poor compass at 1 Hz: a couple of metres of GNSS noise at walking pace swings it tens
 * of degrees, and a parked vehicle's jitter would spin the arrow through a full circle.
 *
 * So the bearing is taken across a baseline: from the newest fix **at least
 * [BASELINE_M] metres back** to the newest one. That is the shortest line long enough for
 * the noise to be a small part of it, and because the walk starts at the newest qualifying
 * point the arrow reacts as quickly as the positions honestly allow - on a straight road it
 * is the direction of the last several metres, and through a corner it turns once the drive
 * has actually turned.
 *
 * No qualifying pair means no direction (null): the drive has not moved six metres yet, or
 * it is standing still. The map keeps the last direction it had, which is the truthful thing
 * to show for a vehicle that has not gone anywhere.
 */
object LiveHeading {

    /**
     * Shortest baseline the bearing may be taken across, in metres.
     *
     * Six metres is about two seconds of a 10 km/h crawl: long enough that a stationary
     * jitter walk (typically 2-5 m) cannot clear it often enough to steer the arrow, short
     * enough that the arrow follows a real turn within a car length.
     */
    const val BASELINE_M = 6.0

    /**
     * Direction of travel at the newest position, in degrees clockwise from north
     * ([0, 360)), or null when the recorded drive has not yet covered [BASELINE_M].
     */
    fun of(points: List<TrackPoint>): Double? {
        if (points.size < 2) return null
        val newest = points.last()
        for (i in points.size - 2 downTo 0) {
            val from = points[i]
            if (TripAccumulator.haversineM(from.lat, from.lon, newest.lat, newest.lon) >= BASELINE_M) {
                return bearingDeg(from, newest)
            }
        }
        return null
    }

    /**
     * Initial great-circle bearing from [from] to [to], in degrees clockwise from north.
     *
     * The great-circle form rather than a flat `atan2` of the coordinate deltas: it costs
     * four sines and a handful of multiplications once a second, and it stays correct at the
     * latitudes a dashboard should be able to drive to.
     */
    fun bearingDeg(from: TrackPoint, to: TrackPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) -
            Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val deg = Math.toDegrees(Math.atan2(y, x))
        return (deg % 360.0 + 360.0) % 360.0
    }

    /**
     * The signed turn from [from] to [to], in (-180, 180].
     *
     * Used to interpolate the arrow through a segment: 350° → 10° is +20°, not -340°, so a
     * heading that crosses north turns the short way instead of spinning the long one.
     */
    fun shortestArcDeg(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** [deg] folded into [0, 360), including negative angles. */
    fun normalizeDeg(deg: Float): Float {
        val wrapped = deg % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }
}
