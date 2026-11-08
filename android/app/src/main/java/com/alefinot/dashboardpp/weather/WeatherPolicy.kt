package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.ble.WeatherPush
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A coordinate with no string attached — the unit of the ladder and the move test. */
data class LatLon(val lat: Double, val lon: Double)

/** The unit's last good position, as kept between sessions. */
data class CachedPosition(
    val lat: Double,
    val lon: Double,
    /** When the unit reported it, in phone epoch milliseconds. */
    val fixEpochMs: Long,
    /** The name that went with it, so a fresh link needs no geocoding call. */
    val label: String?,
)

/** Where the coordinates for this feed came from — shown in the status line. */
enum class PositionSource { FIX, CACHE, CITY }

/** The place to ask the weather service about, and what it is already called. */
data class WeatherTarget(
    val source: PositionSource,
    val lat: Double,
    val lon: Double,
    /** Known name, or null when the caller has to ask Nominatim for one. */
    val labelHint: String?,
)

/** Why (or whether) the next feed should run. */
enum class WeatherDue { FIRST, INTERVAL, MOVED, NEVER }

object WeatherPolicy {

    const val INTERVAL_MS = 5 * 60 * 1000L

    /** A car that has moved this far is in a different forecast. */
    const val MOVE_TRIGGER_KM = 2.0

    /** Older than this, the unit's last position is not a place — it is a guess. */
    const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** Older than this, the picture on the panel is treated as no longer current. */
    const val STALE_AFTER_MS = 3 * 60 * 60 * 1000L

    /** A failed attempt is retried this soon, provided a link is still up. */
    const val RETRY_AFTER_FAILURE_MS = 5 * 60 * 1000L

    /** `time.set` gap. Must stay at or under the drift budget above or the clock rots. */
    const val TIME_REPUSH_MS = 6 * 60 * 60 * 1000L

    /** A finished OTA install moves the unit; feed again within a minute. */
    const val RETRY_AFTER_OTA_MS = 60 * 1000L

    fun positionOf(snapshot: Snapshot?): LatLon? =
        snapshot?.takeIf { it.hasPosition }?.let { LatLon(it.lat, it.lon) }

    fun positionLadder(
        fix: Snapshot?,
        cached: CachedPosition?,
        city: Place?,
        nowMs: Long,
    ): WeatherTarget? {
        positionOf(fix)?.let { return WeatherTarget(PositionSource.FIX, it.lat, it.lon, null) }
        cached?.takeIf { nowMs - it.fixEpochMs in 0..CACHE_MAX_AGE_MS }?.let {
            return WeatherTarget(PositionSource.CACHE, it.lat, it.lon, it.label)
        }
        city?.let { return WeatherTarget(PositionSource.CITY, it.lat, it.lon, it.name) }
        return null
    }

    fun movedKm(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(minOf(1.0, sqrt(h)))
    }

    fun due(nowMs: Long, lastFetchMs: Long?, last: LatLon?, current: LatLon?, enabled: Boolean): WeatherDue {
        if (!enabled) return WeatherDue.NEVER
        if (lastFetchMs == null) return WeatherDue.FIRST
        if (last != null && current != null && movedKm(last, current) > MOVE_TRIGGER_KM) return WeatherDue.MOVED
        if (nowMs - lastFetchMs >= INTERVAL_MS) return WeatherDue.INTERVAL
        return WeatherDue.NEVER
    }

    fun stale(nowMs: Long, lastSuccessMs: Long?): Boolean =
        lastSuccessMs != null && nowMs - lastSuccessMs >= STALE_AFTER_MS

    fun buildPush(reading: OpenMeteoReading, label: String): WeatherPush = WeatherPush(
        temperature = reading.temperatureC,
        humidity = reading.humidityPct,
        windSpeed = reading.windKmh,
        windDirection = reading.windDeg.toDouble(),
        weatherCode = reading.weatherCode,
        cloudCover = reading.cloudCoverPct,
        sunrise = reading.sunriseEpochSec,
        sunset = reading.sunsetEpochSec,
        city = label,
        valid = true,
    )

    fun stalePush(label: String?): WeatherPush = WeatherPush(
        temperature = 0.0,
        humidity = 0,
        windSpeed = 0.0,
        windDirection = 0.0,
        weatherCode = 0,
        cloudCover = 0,
        sunrise = null,
        sunset = null,
        city = label,
        valid = false,
    )

    private const val EARTH_RADIUS_KM = 6371.0
}
