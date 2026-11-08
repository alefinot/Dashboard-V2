package com.alefinot.dashboardpp.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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
        val reading = OpenMeteo.parse(munich)!!
        assertEquals(1793858692L, reading.sunriseEpochSec)
        assertEquals(1793893749L, reading.sunsetEpochSec)
        val tokyo = munich.replace("\"utc_offset_seconds\":3600", "\"utc_offset_seconds\":32400")
        assertEquals("the offset is the response's, not ours to apply",
            reading.sunriseEpochSec, OpenMeteo.parse(tokyo)!!.sunriseEpochSec)
    }

    @Test
    fun `the second forecast day is not the one the panel shows`() {
        val reading = OpenMeteo.parse(munich)!!
        assertEquals(1793858692L, reading.sunriseEpochSec)
        assertEquals(1793893749L, reading.sunsetEpochSec)
    }

    @Test
    fun `a missing sun time is null and not a midnight`() {
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
        assertNull(OpenMeteo.parse(munich.substring(0, munich.length / 2)))
        assertNotNull(OpenMeteo.parse(munich))
    }
}
