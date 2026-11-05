package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `weather.set` payload, field for field.
 *
 * The unit's `methodWeatherSet` (`src/bt_rpc.cpp:725-741`) reads exactly ten keys and
 * defaults every one of them, so a wrong name here is not an error — it is a card that
 * quietly shows the wrong thing. These tests are the app's half of that contract
 * (AGENTS.md rule 17), including the one shape the firmware already understands but
 * the app never sent before: a missing sun time as JSON `null`.
 */
class WeatherPushShapeTest {

    /** The live Open-Meteo answer from the ledger's Task 3 probe, in reading form. */
    private val reading = OpenMeteoReading(
        temperatureC = 6.1,
        humidityPct = 79,
        windKmh = 6.8,
        windDeg = 90,
        weatherCode = 3,
        cloudCoverPct = 90,
        sunriseEpochSec = 1793858692L,
        sunsetEpochSec = 1793891432L,
    )

    @Test
    fun `every value reaches the unit unconverted`() {
        val push = WeatherPolicy.buildPush(reading, "Munich")

        // The firmware's units are Celsius, km/h and percent (src/bt_rpc.cpp:732-736,
        // and the panel does its own °F conversion), so the app converting anything
        // here would be applied twice.
        assertEquals(6.1, push.temperature, 0.0)
        assertEquals(79, push.humidity)
        assertEquals(6.8, push.windSpeed, 0.0)
        assertEquals(90.0, push.windDirection, 0.0)
        assertEquals(3, push.weatherCode)
        assertEquals(90, push.cloudCover)
        assertEquals("Munich", push.city)
        assertTrue(push.valid)
    }

    @Test
    fun `the sun times are the provider's epochs, unshifted`() {
        val push = WeatherPolicy.buildPush(reading, "Munich")

        // The unit formats them with its own timezoneOffsetSec, which the app set with
        // time.set; a local-timezone conversion here would offset them twice.
        assertEquals(1793858692L, push.sunrise)
        assertEquals(1793891432L, push.sunset)
    }

    @Test
    fun `a missing sun time is sent as null, never as zero`() {
        val push = WeatherPolicy.buildPush(
            reading.copy(sunriseEpochSec = null, sunsetEpochSec = null),
            "Munich",
        )
        val body = BleApi.weatherParams(push)

        assertNull(push.sunrise)
        assertTrue(body, body.contains("\"sunrise\":null"))
        assertTrue(body, body.contains("\"sunset\":null"))
        // A zero here would make the panel's fmtHmMf print 00:00, which reads as a
        // time, not as missing data; the unit turns any non-number into "--:--".
        assertFalse(body, body.contains("\"sunrise\":0"))
    }

    @Test
    fun `the offline frame keeps the name and drops the numbers`() {
        val push = WeatherPolicy.stalePush("Munich")
        val body = BleApi.weatherParams(push)

        assertFalse(push.valid)
        // The unit draws its own "Weather Offline (waiting for phone...)" when valid
        // is false and never reads the rest, so zeros carry no risk of a stale number
        // flashing on the card — the label is kept for the app's own status line.
        assertEquals("Munich", push.city)
        assertEquals(0.0, push.temperature, 0.0)
        assertEquals(0, push.humidity)
        assertNull(push.sunrise)
        assertTrue(body, body.contains("\"valid\":false"))
    }

    @Test
    fun `the payload carries the ten keys the unit reads and no others`() {
        val keys = BleJson.obj(BleApi.weatherParams(WeatherPolicy.buildPush(reading, "Munich")))!!.keys

        assertEquals(
            setOf(
                "temperature", "humidity", "windSpeed", "windDirection", "weatherCode",
                "cloudCover", "sunrise", "sunset", "city", "valid",
            ),
            keys,
        )
    }

    @Test
    fun `no name is still a legal frame`() {
        val body = BleApi.weatherParams(WeatherPolicy.stalePush(null))

        // The unit's own default is "no name"; sending null keeps the app from
        // inventing a place the owner never chose.
        assertTrue(body, body.contains("\"city\":null"))
    }
}
