package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.ble.BleJson
import java.util.Locale

data class OpenMeteoReading(
    val temperatureC: Double,
    val humidityPct: Int,
    val windKmh: Double,
    val windDeg: Int,
    val weatherCode: Int,
    val cloudCoverPct: Int,
    val sunriseEpochSec: Long?,
    val sunsetEpochSec: Long?,
)

object OpenMeteo {

    /** The forecast endpoint. Keyless, no account, no auth header. */
    const val HOST = "https://api.open-meteo.com/v1/forecast"

    const val FORECAST_DAYS = 2

    private const val CURRENT_FIELDS =
        "temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m," +
            "wind_direction_10m,weather_code,cloud_cover"

    /** Seven decimals is ~1 cm; it is also more than the 2 km the feed uses as a move trigger. */
    private const val COORDINATE_DECIMALS = 7

    fun url(lat: Double, lon: Double): String? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        val safeLat = lat.coerceIn(-90.0, 90.0)
        val safeLon = lon.coerceIn(-180.0, 180.0)
        return buildString {
            append(HOST)
            append("?latitude=").append(fixed(safeLat))
            append("&longitude=").append(fixed(safeLon))
            append("&current=").append(CURRENT_FIELDS)
            append("&daily=sunrise,sunset")
            append("&forecast_days=").append(FORECAST_DAYS)
            append("&timezone=auto")
            append("&timeformat=unixtime")
        }
    }

    fun parse(body: String): OpenMeteoReading? {
        val root = try {
            BleJson.obj(body)
        } catch (e: Exception) {
            null
        } ?: return null

        val current = root["current"] as? Map<*, *> ?: return null
        val temperature = current.number("temperature_2m") ?: return null

        return OpenMeteoReading(
            temperatureC = temperature,
            humidityPct = current.number("relative_humidity_2m")?.toInt() ?: 0,
            windKmh = current.number("wind_speed_10m") ?: 0.0,
            windDeg = current.number("wind_direction_10m")?.toInt()?.let { ((it % 360) + 360) % 360 }
                ?: 0,
            weatherCode = current.number("weather_code")?.toInt() ?: 0,
            cloudCoverPct = current.number("cloud_cover")?.toInt() ?: 0,
            sunriseEpochSec = dailyEpoch(root, "sunrise"),
            sunsetEpochSec = dailyEpoch(root, "sunset"),
        )
    }

    private fun fixed(value: Double): String =
        String.format(Locale.US, "%.${COORDINATE_DECIMALS}f", value)

    /** A JSON number that is actually a number; null for a string, a bool, a null or an NaN. */
    private fun Map<*, *>.number(key: String): Double? =
        (this[key] as? Double)?.takeIf { it.isFinite() }

    private fun dailyEpoch(root: Map<String, Any?>, key: String): Long? {
        val daily = root["daily"] as? Map<*, *> ?: return null
        val days = daily[key] as? List<*> ?: return null
        return (days.firstOrNull() as? Double)?.takeIf { it.isFinite() }?.toLong()
    }
}
