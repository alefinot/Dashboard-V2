package com.alefinot.dashboardpp.weather

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The app's *only* weather fetch path — Open-Meteo over HTTPS (the phone has
 * internet; the ESP no longer fetches). It mirrors the exact URL/fields the
 * ESP's old `updateWeather()` used (§2.1) and parses them into [WeatherDto].
 * The result is pushed over BLE (§5.5); there is no second client.
 *
 * No API key in v1 — Open-Meteo's free tier needs none for the base
 * forecast. A key, if ever added, is a phone-side DataStore value (not on
 * the ESP).
 */
object OpenMeteoClient {
    private const val BASE =
        "https://api.open-meteo.com/v1/forecast"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch the forecast for (lat, lon) and normalize it into a [WeatherDto].
     * [city] is the ESP's `WEATHER_CITY` (read from `/api/config`) — it becomes
     * the widget's city label (the ESP keeps `WEATHER_CITY` as the location
     * of record; the phone reads it).
     * @throws Exception on a non-2xx response or a malformed body (the
     * caller catches and simply keeps the last-pushed value).
     */
    suspend fun fetch(lat: Double, lon: Double, city: String): WeatherDto {
        val url = "$BASE?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code" +
            ",cloud_cover,wind_speed_10m,wind_direction_10m" +
            "&daily=sunrise,sunset&timezone=auto&forecast_days=1"
        val response = client.newCall(
            Request.Builder().url(url).build(),
        ).execute()
        val body = response.body?.string() ?: ""
        response.close()
        if (response.code !in 200..299) throw IllegalStateException("HTTP ${response.code}")
        val json = org.json.JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        val sunrise = daily?.optJSONArray("sunrise")?.optString(0) ?: ""
        val sunset = daily?.optJSONArray("sunset")?.optString(0) ?: ""
        return WeatherDto(
            temperature = current.getDouble("temperature_2m").toFloat(),
            humidity = current.getInt("relative_humidity_2m"),
            windSpeed = current.getDouble("wind_speed_10m").toFloat(),
            windDirection = current.getDouble("wind_direction_10m").toFloat(),
            weatherCode = current.getInt("weather_code"),
            cloudCover = current.getInt("cloud_cover"),
            sunriseTime = sunrise,
            sunsetTime = sunset,
            cityName = city,
        )
    }
}
