package com.alefinot.dashboardpp.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parser half of the Open-Meteo client (weather push plan, `weather/OpenMeteo.kt`).
 *
 * The fixture is a real answer, captured from `api.open-meteo.com` for München on 2026-11-05 with
 * the query `OpenMeteoUrlTest` pins, copied verbatim. A parser tested against a payload someone
 * wrote from memory tests the memory, not the API.
 *
 * What this suite is really defending is that the eight numbers reach the unit the way the
 * firmware expects them: °C, %, km/h and degrees unconverted (the panel does its own imperial
 * maths), and the sun times as the absolute epochs the provider sent, which the unit formats with
 * the car's own timezone rules.
 */
class OpenMeteoParseTest {

    /** Verbatim response, München, 2026-11-05. Two forecast days, `timeformat=unixtime`. */
    private val munich = """
        {"latitude":48.12743,"longitude":11.576928,"generationtime_ms":0.2855062484741211,
        "utc_offset_seconds":3600,"timezone":"Europe/Berlin","timezone_abbreviation":"GMT+1",
        "elevation":524.0,
        "current_units":{"time":"unixtime","interval":"seconds","temperature_2m":"°C",
        "relative_humidity_2m":"%","apparent_temperature":"°C","wind_speed_10m":"km/h",
        "wind_direction_10m":"°","weather_code":"wmo code","cloud_cover":"%"},
        "current":{"time":1793882700,"interval":900,"temperature_2m":6.1,
        "relative_humidity_2m":79,"apparent_temperature":3.5,"wind_speed_10m":6.8,
        "wind_direction_10m":90,"weather_code":3,"cloud_cover":90},
        "daily_units":{"time":"unixtime","sunrise":"unixtime","sunset":"unixtime"},
        "daily":{"time":[1793833200,1793919600],"sunrise":[1793858692,1793945186],
        "sunset":[1793893749,1793980060]}}
    """.trimIndent().replace("\n", "")

    @Test
    fun `a real response becomes the eight values the unit asked for`() {
        val reading = OpenMeteo.parse(munich)!!
        // The exact numbers from the capture. Double, not Float: `WeatherPush` carries Doubles and
        // a Float 6.1f widens back out as 6.100000095367432, which is what would then travel to
        // the unit over the RPC.
        assertEquals(6.1, reading.temperatureC, 0.0)
        assertEquals(79, reading.humidityPct)
        assertEquals(6.8, reading.windKmh, 0.0)
        assertEquals(90, reading.windDeg)
        assertEquals(3, reading.weatherCode)
        assertEquals(90, reading.cloudCoverPct)
        assertEquals(1793858692L, reading.sunriseEpochSec)
        assertEquals(1793893749L, reading.sunsetEpochSec)
    }

    @Test
    fun `the sun epochs travel untouched because the unit owns the timezone`() {
        // `utc_offset_seconds: 3600` is the tempting thing to add in: it produces the right wall
        // clock in Munich and a panel that is an hour wrong everywhere else, twice over when the
        // unit also applies its `TZ_OFFSET_HOURS`. `src/bt_rpc.cpp` `epochToHHMM` is the formatter.
        val reading = OpenMeteo.parse(munich)!!
        assertEquals(1793858692L, reading.sunriseEpochSec)
        assertEquals(1793893749L, reading.sunsetEpochSec)
        val tokyo = munich.replace("\"utc_offset_seconds\":3600", "\"utc_offset_seconds\":32400")
        assertEquals("the offset is the response's, not ours to apply",
            reading.sunriseEpochSec, OpenMeteo.parse(tokyo)!!.sunriseEpochSec)
    }

    @Test
    fun `the second forecast day is not the one the panel shows`() {
        // Two days are requested so a local midnight still has a day with both a sunrise and a
        // sunset in it. Index [0] is that day; taking the last element would show tomorrow's sun.
        val reading = OpenMeteo.parse(munich)!!
        assertEquals(1793858692L, reading.sunriseEpochSec)
        assertEquals(1793893749L, reading.sunsetEpochSec)
    }

    @Test
    fun `a missing sun time is null and not a midnight`() {
        // Open-Meteo answers `null` for sunrise/sunset where there is none that day - polar day
        // and night are the real case. The unit's own fallback for a sun field that is not a
        // number is `--:--` (`src/bt_rpc.cpp:717-722`), which is the honest panel. A 0 here would
        // be a number, and the panel would print a confident midnight at the epoch.
        val polar = munich
            .replace("\"sunrise\":[1793858692,1793945186]", "\"sunrise\":[null,null]")
            .replace("\"sunset\":[1793893749,1793980060]", "\"sunset\":[null,null]")
        val reading = OpenMeteo.parse(polar)!!
        assertNull(reading.sunriseEpochSec)
        assertNull(reading.sunsetEpochSec)
        assertEquals("the temperature is still today's", 6.1, reading.temperatureC, 0.0)
    }

    @Test
    fun `a null secondary field is a zero and not a crash`() {
        // A real shape: Open-Meteo answers `null` for a field it has no data for at that hour.
        // The secondary values are safe to show as 0; the temperature is not, which is the next
        // test.
        val partial = munich
            .replace("\"cloud_cover\":90", "\"cloud_cover\":null")
            .replace("\"wind_speed_10m\":6.8", "\"wind_speed_10m\":null")
            .replace("\"apparent_temperature\":3.5", "\"apparent_temperature\":null")
        val reading = OpenMeteo.parse(partial)!!
        assertEquals(0, reading.cloudCoverPct)
        assertEquals(0.0, reading.windKmh, 0.0)
        assertEquals(6.1, reading.temperatureC, 0.0)
    }

    @Test
    fun `a response without a temperature is not a reading`() {
        // The unit rejects a `weather.set` whose `temperature` is not a number, and a 0 invented
        // here would be accepted and shown. Better no push at all: the coordinator keeps the last
        // frame it sent, and the card keeps the values it had.
        val noCurrent = munich.replace(Regex("\"current\":\\{.*?\\},"), "")
        assertNull(OpenMeteo.parse(noCurrent))
        val nullTemp = munich.replace("\"temperature_2m\":6.1", "\"temperature_2m\":null")
        assertNull(OpenMeteo.parse(nullTemp))
    }

    @Test
    fun `an error object is not a reading`() {
        assertNull(
            OpenMeteo.parse(
                """{"error":true,"reason":"No matching forecast available for the given coordinates"}"""
            ),
        )
        // A body that is valid JSON but says nothing we can use must not become a frame of zeros.
        assertNull(OpenMeteo.parse("""{"hourly":{}}"""))
    }

    @Test
    fun `an html error page is not a reading`() {
        // The shape a reverse proxy or a captive portal actually returns.
        assertNull(OpenMeteo.parse("<html><body>502 Bad Gateway</body></html>"))
        assertNull(OpenMeteo.parse(""))
        assertNull(OpenMeteo.parse("null"))
        assertNull(OpenMeteo.parse("""[1,2,3]"""))
    }

    @Test
    fun `a truncated body is not a reading`() {
        // WeatherHttp answers with the body it got; a connection cut mid-response yields JSON that
        // stops early. `BleJson` answers null for trailing garbage, and this is the case where
        // that choice pays for itself.
        assertNull(OpenMeteo.parse(munich.substring(0, munich.length / 2)))
        assertNotNull(OpenMeteo.parse(munich))
    }
}
