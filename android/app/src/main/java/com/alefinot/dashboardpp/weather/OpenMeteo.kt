package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.ble.BleJson
import java.util.Locale

/**
 * One day of weather at the car, in the units the unit already expects.
 *
 * Everything here is in the API's own units - °C, %, km/h, degrees - because the firmware does the
 * imperial/metric conversion itself (`src/ui.cpp:2436-2444` reads `UNIT_SYSTEM` at draw time). A
 * conversion on this side would be applied twice the day the unit's setting changes, and once
 * wrongly forever.
 *
 * The numbers are [Double] where the RPC carries a Double: `BleApi.WeatherPush` sends `temperature`
 * and `windSpeed` as Doubles, and a `Float` 6.1f widens back to 6.100000095367432, which is the
 * text that would then go over the air. So the reading arrives as Double and is copied straight
 * into the push, which is the only reason the two sets of names line up so exactly.
 *
 * The sun times are nullable on purpose. Open-Meteo answers `null` for a sunrise or sunset that
 * does not occur on the requested day - polar day and night are the real case, not a curiosity -
 * and the unit's documented fallback for a sun field that is not a number is `--:--`
 * (`src/bt_rpc.cpp:717-722`). A `0` would be a number, and the panel would print a confident
 * midnight on 1 January 1970 in the car's timezone. Null is what lets the caller send null.
 *
 * @param sunriseEpochSec absolute unixtime, untouched. The unit formats it with the *car's*
 *   `TZ_OFFSET_HOURS` + `TZ_DST_RULE` (`epochToHHMM`), which is the only way a car that crosses a
 *   timezone boundary keeps a correct sunrise while the phone sits in the pocket that made the
 *   request.
 */
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

/**
 * The Open-Meteo forecast endpoint: a URL builder and a parser, nothing else.
 *
 * No network here and no Android: [url] turns coordinates into one string and [parse] turns a body
 * into a reading or `null`. The fetch belongs to [WeatherHttp], which is the only file in the
 * module allowed to own a connection, and the schedule belongs to the coordinator. That split is
 * what makes all of this testable without a phone, a radio or a server - and it is the same split
 * `trips/` uses for GPX export, where the writer is pure and the share sheet is not.
 *
 * A provider's host is allowed to be a literal in this file and nowhere else; the transport guard
 * (`SettingsTabReplacesTheWebPageTest`) fails the build the day a second file names one, because
 * a second host in a second file is how a second transport grows back.
 */
object OpenMeteo {

    /** The forecast endpoint. Keyless, no account, no auth header. */
    const val HOST = "https://api.open-meteo.com/v1/forecast"

    /**
     * Two days, and this is load-bearing rather than cautious. Asked for one day at 00:30 local,
     * that day's sunrise is already behind the request and Open-Meteo answers `null` for it -
     * which the panel would show as "no sunrise today" in a town that has one in four hours. The
     * second day is what keeps index [0] a day with both a sunrise and a sunset in it.
     */
    const val FORECAST_DAYS = 2

    /**
     * The `current=` list. The plan's line, kept whole: `apparent_temperature` is the one field
     * the panel has no slot for today, and fetching it costs a few bytes a month while it keeps a
     * feels-like line from being a query change later.
     */
    private const val CURRENT_FIELDS =
        "temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m," +
            "wind_direction_10m,weather_code,cloud_cover"

    /** Seven decimals is ~1 cm; it is also more than the 2 km the feed uses as a move trigger. */
    private const val COORDINATE_DECIMALS = 7

    /**
     * The request URL for a position, or **null** when there is no position to ask about.
     *
     * A finite coordinate out of range is a glitched fix and gets clamped back onto the earth -
     * weather 500 km away is wrong in a way the owner can see and blame on the sky. A NaN or
     * infinite coordinate is a different animal: clamping it to 0 would ask for the Gulf of Guinea
     * and the panel would show it as the car's weather, silently. Null means "ask for nothing",
     * which is the answer the coordinator already knows how to handle: keep the last frame.
     *
     * The formatting is pinned to [Locale.US]. A device set to a German locale writing the default
     * locale's decimal separator would put `latitude=48,13743` into a query, and the provider
     * would answer with an error that looks like the car drove off the map.
     */
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
            // `auto`, not `UTC`: the sun times must belong to the day the car is standing in.
            // Keyed to UTC they drift by the car's offset, and the panel's day/night flip
            // (`weatherIsNight`) starts and ends at the wrong hour.
            append("&timezone=auto")
            // Numbers, not ISO strings: the unit's `epochToHHMM` takes an epoch and owns the
            // timezone maths. Anything else would make the phone format a time it does not own.
            append("&timeformat=unixtime")
        }
    }

    /**
     * A body into a reading, or null when the body is not a usable forecast.
     *
     * Null is the answer for every shape that is not an Open-Meteo forecast object: an HTML error
     * page from a proxy or a captive portal, an `{"error":true,"reason":...}` object, a body cut
     * off mid-response by a dropped connection, an object with no `current` block. All of them
     * mean the same thing to the caller - do not push, keep the last frame - and inventing a
     * reading of zeros is how a car in a tunnel ends up showing 0 °C.
     *
     * [WeatherHttp] answers null for a non-2xx or an empty body, so this also has to be strict
     * about a **200 that is not JSON**: that is the case a status code cannot catch.
     */
    fun parse(body: String): OpenMeteoReading? {
        // `BleJson` is the repo's only parser that survives the JVM test environment, and it was
        // written for our own BLE envelopes. A provider's body is somebody else's input: its
        // reader lets one class of exception out (a `\uZZZZ` escape fails inside `toInt(16)`), so
        // the boundary that faces the internet catches wide rather than trusting a parser built
        // for a friend.
        val root = try {
            BleJson.obj(body)
        } catch (e: Exception) {
            null
        } ?: return null

        val current = root["current"] as? Map<*, *> ?: return null
        // The one field with no honest default. The unit refuses a `weather.set` whose
        // `temperature` is not a number, so a 0 filled in here would be accepted, displayed, and
        // believed.
        val temperature = current.number("temperature_2m") ?: return null

        return OpenMeteoReading(
            temperatureC = temperature,
            humidityPct = current.number("relative_humidity_2m")?.toInt() ?: 0,
            windKmh = current.number("wind_speed_10m") ?: 0.0,
            // Normalised, not trusted: the API is documented 0-359 and answers 90 for east, but a
            // bearing the panel turns into a compass point has to be one whatever the provider did.
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

    /**
     * Index [0] of `daily.<key>`: the location's own first day. The array is aligned with
     * `daily.time`, so [0] is today where the car is standing - taking the last element, which is
     * the tempting "the array has two days, use the newest" reading, would put tomorrow's sunrise
     * on today's panel.
     */
    private fun dailyEpoch(root: Map<String, Any?>, key: String): Long? {
        val daily = root["daily"] as? Map<*, *> ?: return null
        val days = daily[key] as? List<*> ?: return null
        return (days.firstOrNull() as? Double)?.takeIf { it.isFinite() }?.toLong()
    }
}
