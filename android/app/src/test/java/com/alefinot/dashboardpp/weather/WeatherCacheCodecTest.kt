package com.alefinot.dashboardpp.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCacheCodecTest {

    private val munich = WeatherCache(
        lat = 48.13743,
        lon = 11.57549,
        label = "Munich",
        fetchEpochMs = 1_762_000_000_000L,
        lastPushEpochMs = 1_762_000_060_000L,
        offlineSent = false,
    )

    @Test
    fun `a cache row survives the round trip`() {
        assertEquals(munich, WeatherSettings.readCache(WeatherSettings.encodeCache(munich)))
    }

    @Test
    fun `a label with a semicolon stays whole`() {
        val odd = munich.copy(label = "Sturgis; South Dakota")
        assertEquals("Sturgis; South Dakota", WeatherSettings.readCache(WeatherSettings.encodeCache(odd))?.label)
    }

    @Test
    fun `no label is not a corrupt row`() {
        val none = munich.copy(label = null)
        val back = WeatherSettings.readCache(WeatherSettings.encodeCache(none))!!

        assertNull(back.label)
        assertEquals(48.13743, back.lat, 0.0)
    }

    @Test
    fun `the flag and the never-happened timestamps survive`() {
        val never = munich.copy(fetchEpochMs = WeatherSettings.NO_EPOCH, lastPushEpochMs = WeatherSettings.NO_EPOCH, offlineSent = true)
        val back = WeatherSettings.readCache(WeatherSettings.encodeCache(never))!!

        assertEquals(WeatherSettings.NO_EPOCH, back.fetchEpochMs)
        assertEquals(WeatherSettings.NO_EPOCH, back.lastPushEpochMs)
        assertTrue(back.offlineSent)
    }

    @Test
    fun `a coordinate keeps the precision the app sends`() {
        val precise = munich.copy(lat = 60.1234567, lon = -149.9876543)

        val back = WeatherSettings.readCache(WeatherSettings.encodeCache(precise))!!

        assertEquals(60.1234567, back.lat, 1e-7)
        assertEquals(-149.9876543, back.lon, 1e-7)
    }

    @Test
    fun `a row this app did not write is refused`() {
        assertNull(WeatherSettings.readCache(null))
        assertNull(WeatherSettings.readCache(""))
        assertNull(WeatherSettings.readCache("garbage"))
        assertNull(WeatherSettings.readCache("w2;48.1;11.5;1;1;0;Munich"))
        assertNull(WeatherSettings.readCache("w1;abc;11.5;1;1;0;Munich"))
        assertNull(WeatherSettings.readCache("w1;48.1;11.5;not-a-number;1;0;Munich"))
    }

    @Test
    fun `a coordinate outside the world is not read back`() {
        val broken = munich.copy(lat = 999.0)

        assertNull(WeatherSettings.readCache(WeatherSettings.encodeCache(broken)))
    }

    @Test
    fun `the cache row belongs to a unit identity, not an address`() {
        assertEquals("weather:cache:mac:AABBCCDDEEFF", WeatherSettings.cacheKey("mac:AABBCCDDEEFF"))
        assertEquals("weather:cache:ble:AA:BB:CC:DD:EE:FF", WeatherSettings.cacheKey("ble:AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `forgetting one unit cannot take another unit's row`() {
        // The trap the vehicle-card deletion flow already learned about: one identity's
        // key is a text prefix of another's. Only whole keys may be removed.
        val existing = setOf(
            WeatherSettings.cacheKey("ble:AA:BB:CC:DD:EE:FF"),
            WeatherSettings.cacheKey("ble:AA:BB:CC:DD:EE:FF1"),
            WeatherSettings.cacheKey("mac:AABBCCDDEEFF"),
        )

        val gone = WeatherSettings.cacheKeysToRemove(existing, listOf("ble:AA:BB:CC:DD:EE:FF"))

        assertEquals(1, gone.size)
        assertEquals(WeatherSettings.cacheKey("ble:AA:BB:CC:DD:EE:FF"), gone.first())
        // Nothing asked for, nothing removed - including no "remove everything" default.
        assertTrue(WeatherSettings.cacheKeysToRemove(existing, emptyList()).isEmpty())
        assertNotNull(WeatherSettings.cacheKeysToRemove(existing, listOf("mac:AABBCCDDEEFF")))
    }
}
