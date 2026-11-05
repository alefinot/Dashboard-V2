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

/**
 * The weather feed's schedule and its position ladder, with no Android and no I/O in
 * it — every rule the coordinator applies is here so it can be read, changed and
 * argued about in one place.
 *
 * The intervals are the plan's, with one of them borrowed from the firmware's own
 * behaviour: `TIME_REPUSH_MS` is six hours because that is the drift the unit's RTC
 * error budget already assumes (README: "the +200 ppm figure is what its worst-case
 * clock error per day assumes"), and re-pushing the clock costs 24 bytes.
 */
object WeatherPolicy {

    const val INTERVAL_MS = 15 * 60 * 1000L

    /** A car that has moved this far is in a different forecast. */
    const val MOVE_TRIGGER_KM = 2.0

    /** Older than this, the unit's last position is not a place — it is a guess. */
    const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** Older than this, the picture on the panel is treated as no longer current. */
    const val OFFLINE_AFTER_MS = 3 * 60 * 60 * 1000L

    /** A failed attempt is retried this soon, provided a link is still up. */
    const val RETRY_AFTER_FAILURE_MS = 5 * 60 * 1000L

    /** `time.set` gap. Must stay at or under the drift budget above or the clock rots. */
    const val TIME_REPUSH_MS = 6 * 60 * 60 * 1000L

    /** A finished OTA install moves the unit; feed again within a minute. */
    const val RETRY_AFTER_OTA_MS = 60 * 1000L

    /**
     * The position the unit itself reports, or null when it has none.
     *
     * `Snapshot.hasPosition` is the whole rule and deliberately not restated here: it
     * is what already keeps route points honest, and it means `fix == 1`, coordinates
     * in range, and not the 0,0 a receiver prints before it has ever fixed.
     */
    fun positionOf(snapshot: Snapshot?): LatLon? =
        snapshot?.takeIf { it.hasPosition }?.let { LatLon(it.lat, it.lon) }

    /**
     * Fix, else the unit's cached position younger than [CACHE_MAX_AGE_MS], else the
     * city the owner typed. Null means there is no place to ask about, and the feed
     * says so rather than inventing a coordinate.
     */
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

    /**
     * Great-circle distance in km. The cheap formula, not a flat-earth approximation:
     * at 48° N a degree of longitude is ~75 km, so a box test would call a 2 km east-west
     * drive "the same place" and miss a 1.5 km north-south one as a move.
     */
    fun movedKm(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(minOf(1.0, sqrt(h)))
    }

    /**
     * Should the feed run now? A move outranks the clock: a car driving into different
     * weather must not wait out the remaining minutes of a 15-minute interval to get a
     * forecast that is already wrong.
     *
     * [last] is the position the last successful feed used; [current] is the best
     * position right now. Either may be null, in which case there is no move to measure.
     */
    fun due(nowMs: Long, lastFetchMs: Long?, last: LatLon?, current: LatLon?, enabled: Boolean): WeatherDue {
        if (!enabled) return WeatherDue.NEVER
        if (lastFetchMs == null) return WeatherDue.FIRST
        if (last != null && current != null && movedKm(last, current) > MOVE_TRIGGER_KM) return WeatherDue.MOVED
        if (nowMs - lastFetchMs >= INTERVAL_MS) return WeatherDue.INTERVAL
        return WeatherDue.NEVER
    }

    /**
     * Is the panel's picture too old to present as current? Null [lastSuccessMs] means
     * there has been no successful feed since the app started, so there is nothing to
     * mark stale — the coordinator treats that case separately.
     */
    fun offline(nowMs: Long, lastSuccessMs: Long?): Boolean =
        lastSuccessMs != null && nowMs - lastSuccessMs >= OFFLINE_AFTER_MS

    /**
     * The reading to display, with the label the ladder settled on.
     *
     * Deliberately no unit conversion anywhere: the payload's units are the firmware's
     * (°C, km/h, %) and the panel does its own °F conversion for Imperial mode
     * (src/ui.cpp:2435), so a conversion here would be applied twice.
     */
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

    /**
     * The frame that tells the unit "this is not current any more". The numbers are
     * zero on purpose: the unit ignores the whole payload when `valid` is false and
     * draws its own "Weather Offline (waiting for phone...)" (src/ui.cpp:2417), so
     * there is no stale value to carry, and zero is the one thing that cannot be
     * mistaken for a reading.
     */
    fun offlinePush(label: String?): WeatherPush = WeatherPush(
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
