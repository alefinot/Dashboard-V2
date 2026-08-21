package com.alefinot.dashboardpp.weather

/**
 * The normalized weather DTO the app pushes over BLE. It maps 1:1 onto the
 * ESP's `struct WeatherData` (src/dashboard.h): `temperature`, `humidity`,
 * `windSpeed` (km/h — the widget /3.6's to m/s, untouched), `windDirection`,
 * `weatherCode` (WMO), `cloudCover`, `sunriseTime`, `sunsetTime`, `cityName`.
 * The ESP fills `g_weatherData` from these under `g_stateMutex` and sets
 * `valid=true` / `lastUpdated`; the existing widget then redraws itself.
 *
 * There is no second weather DTO in the app — this *is* the shape the phone
 * fetches and pushes (the ESP no longer fetches Open-Meteo itself).
 */
data class WeatherDto(
    val temperature: Float,
    val humidity: Int,
    val windSpeed: Float,
    val windDirection: Float,
    val weatherCode: Int,
    val cloudCover: Int,
    val sunriseTime: String,
    val sunsetTime: String,
    val cityName: String,
) {
    /** The §4.3 WEATHER payload — the exact keys the ESP's ingest reads. */
    fun toJson(): String {
        val o = org.json.JSONObject()
        o.put("temp", temperature.toDouble())
        o.put("hum", humidity)
        o.put("wind", windSpeed.toDouble())
        o.put("windDir", windDirection.toDouble())
        o.put("code", weatherCode)
        o.put("cloud", cloudCover)
        o.put("sun", sunriseTime)
        o.put("sunset", sunsetTime)
        o.put("city", cityName)
        return o.toString()
    }
}
