package com.alefinot.dashboardpp.weather

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoUrlTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `the query asks for exactly the fields the panel shows`() {
        val url = OpenMeteo.url(48.13743, 11.57549)!!
        assertTrue(url.startsWith(OpenMeteo.HOST))
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
        assertTrue(url.contains("timezone=auto"))
        assertFalse(url.contains("timezone=UTC"))
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
        val url = OpenMeteo.url(48.12345678, 11.98765432)!!
        assertTrue(url.contains("latitude=48.1234568"))
        assertTrue(url.contains("longitude=11.9876543"))
    }

    @Test
    fun `a coordinate outside the earth is pulled back onto it`() {
        val url = OpenMeteo.url(95.0, -200.0)!!
        assertTrue(url.contains("latitude=90.0000000"))
        assertTrue(url.contains("longitude=-180.0000000"))
        assertTrue(OpenMeteo.url(-100.0, 200.0)!!.contains("latitude=-90.0000000"))
        assertTrue(OpenMeteo.url(-100.0, 200.0)!!.contains("longitude=180.0000000"))
    }

    @Test
    fun `a coordinate that is not a number asks for no weather at all`() {
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
