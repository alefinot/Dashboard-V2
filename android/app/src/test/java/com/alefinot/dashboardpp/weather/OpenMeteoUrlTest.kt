package com.alefinot.dashboardpp.weather

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL half of the Open-Meteo client (weather push plan, `weather/OpenMeteo.kt`).
 *
 * This is the file where the app decides what it asks the weather service for, and the answer is
 * a contract with two other places at once: the panel's fields on the unit, and the units the
 * firmware expects (°C, %, km/h - it does its own imperial conversion, see `src/ui.cpp`). The
 * parameters are asserted as literal text rather than through a URL parser because the failure
 * this guards against is spelling: a comma-decimal coordinate, a `timezone=UTC` that silently
 * changes which day's sunrise comes back, a `forecast_days=1` that returns no sunrise at all
 * after local midnight.
 */
class OpenMeteoUrlTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        // The comma-locale test below changes the JVM default. Leaking it into the rest of the
        // suite would make an unrelated test pass or fail depending on the order they ran in.
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `the query asks for exactly the fields the panel shows`() {
        val url = OpenMeteo.url(48.13743, 11.57549)!!
        assertTrue(url.startsWith(OpenMeteo.HOST))
        // The seven current fields. The unit's `weather.set` has a slot for each of them;
        // `apparent_temperature` is the one the plan asked for and the panel has no place for -
        // fetched, unused, cheap to keep, so a later feels-like line does not change the query.
        for (field in listOf(
            "temperature_2m", "relative_humidity_2m", "wind_speed_10m", "wind_direction_10m",
            "weather_code", "cloud_cover",
        )) {
            assertTrue("current=$field is missing from $url", url.contains("current=.*$field".toRegex()))
        }
        assertTrue("apparent_temperature is part of the plan's query", url.contains("apparent_temperature"))
        assertTrue(url.contains("daily=sunrise,sunset"))
    }

    @Test
    fun `the day is the location's own day, not utc's`() {
        val url = OpenMeteo.url(48.13743, 11.57549)!!
        // `timezone=auto` is what makes `daily.sunrise[0]` the sunrise at that latitude, which is
        // the thing the panel prints. With `timezone=UTC` the response is keyed to the UTC day,
        // and the panel's sun times drift by the car's offset.
        assertTrue(url.contains("timezone=auto"))
        assertFalse(url.contains("timezone=UTC"))
        // And the epochs, not ISO text: the unit's `epochToHHMM` takes a number and applies the
        // car's `TZ_OFFSET_HOURS` + `TZ_DST_RULE` itself.
        assertTrue(url.contains("timeformat=unixtime"))
        assertFalse("an ISO time would arrive as a string the parser has no use for", url.contains("timeformat=iso"))
    }

    @Test
    fun `coordinates are written with a dot on a comma locale device`() {
        Locale.setDefault(Locale.GERMANY)
        val url = OpenMeteo.url(48.13743, 11.57549)!!
        assertTrue("comma-decimal coordinate in $url", url.contains("latitude=48.1374300"))
        assertTrue(url.contains("longitude=11.5754900"))
        // The query's own commas are the parameter lists; the coordinate must not add any.
        assertFalse(url.substringAfter("latitude=").substringBefore('&').contains(","))
        assertFalse(url.substringAfter("longitude=").substringBefore('&').contains(","))
    }

    @Test
    fun `coordinates keep seven decimals`() {
        // Seven places is about 1 cm, and it is the precision the plan pinned: a GNSS fix that
        // gets cut to two decimals moves the car by a kilometre, which is also the distance the
        // feed uses to decide it is worth fetching again.
        val url = OpenMeteo.url(48.12345678, 11.98765432)!!
        assertTrue(url.contains("latitude=48.1234568"))
        assertTrue(url.contains("longitude=11.9876543"))
    }

    @Test
    fun `a coordinate outside the earth is pulled back onto it`() {
        // A finite coordinate out of range is a glitched fix, not a bug in the caller: clamping
        // keeps the feed alive with weather that is somewhere near the car.
        val url = OpenMeteo.url(95.0, -200.0)!!
        assertTrue(url.contains("latitude=90.0000000"))
        assertTrue(url.contains("longitude=-180.0000000"))
        assertTrue(OpenMeteo.url(-100.0, 200.0)!!.contains("latitude=-90.0000000"))
        assertTrue(OpenMeteo.url(-100.0, 200.0)!!.contains("longitude=180.0000000"))
    }

    @Test
    fun `a coordinate that is not a number asks for no weather at all`() {
        // Clamping a NaN to 0 would not be a failure the user could see - it would be the panel
        // confidently showing Gulf-of-Guinea weather for a car in Bavaria. Null is the answer the
        // coordinator already handles: keep the last pushed frame.
        assertNull(OpenMeteo.url(Double.NaN, 11.57549))
        assertNull(OpenMeteo.url(48.13743, Double.NaN))
        assertNull(OpenMeteo.url(Double.POSITIVE_INFINITY, 11.57549))
        assertFalse("NaN must never reach a URL", OpenMeteo.url(0.0, 0.0).toString().contains("NaN"))
    }

    @Test
    fun `two forecast days are requested because a local midnight still needs today's sunrise`() {
        val url = OpenMeteo.url(48.13743, 11.57549)!!
        // With forecast_days=1 and the location's local time past midnight, that day's sunrise is
        // already behind the request and Open-Meteo answers `null` - which reads on the panel as
        // "no sunrise today". Two days keeps index [0] a day that has both.
        assertTrue(url.contains("forecast_days=2"))
        assertFalse(url.contains("forecast_days=1&"))
        assertFalse(url.endsWith("forecast_days=1"))
    }

    @Test
    fun `the query carries nothing beyond the six parameters`() {
        val query = OpenMeteo.url(48.13743, 11.57549)!!.substringAfter('?')
        assertEquals(
            "a new parameter is a new contract with the provider - add it on purpose",
            listOf("latitude", "longitude", "current", "daily", "forecast_days", "timezone", "timeformat"),
            query.split('&').map { it.substringBefore('=') },
        )
    }
}
