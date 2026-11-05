package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.api.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weather schedule and the position ladder, as pure arithmetic.
 *
 * These are the two decisions the coordinator makes, and they are the two that an
 * owner can disagree about without any hardware in front of them: when is a refresh
 * due, and which position gets asked about. `MIN`/`HOUR` here are the plan's numbers;
 * the tests pin them so a later "tweak" is a visible decision, not a silent one.
 */
class WeatherPolicyTest {

    private val minute = 60_000L
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    /** One snapshot with a GNSS position, built the way the app receives one. */
    private fun snap(lat: Double, lon: Double, fix: Int = 1): Snapshot? =
        Snapshot.parse("""{"up":420,"spd":0.0,"lat":$lat,"lon":$lon,"fix":$fix}""")

    // ---- where to ask for weather -----------------------------------------

    @Test
    fun `a position from the unit wins over everything`() {
        val target = WeatherPolicy.positionLadder(
            fix = snap(48.13743, 11.57549),
            cached = CachedPosition(47.0, 9.0, now - minute, label = "Memmingen"),
            city = Place("Munich", "Bavaria", "Germany", 48.13743, 11.57549),
            nowMs = now,
        )!!

        assertEquals(PositionSource.FIX, target.source)
        assertEquals(48.13743, target.lat, 0.0)
        assertEquals(11.57549, target.lon, 0.0)
        // A fix carries no name of its own: that is the one case where the app
        // owes itself a reverse geocode.
        assertNull(target.labelHint)
    }

    @Test
    fun `a position the unit does not actually have is not a position`() {
        // fix == 0: the frame carries lat/lon from the last fix, and the unit says
        // so by sending fix 0. Snapshot.hasPosition is the single rule for this.
        assertNull(WeatherPolicy.positionOf(snap(48.13743, 11.57549, fix = 0)))
        // The 0,0 null island a receiver reports before it has ever fixed.
        assertNull(WeatherPolicy.positionOf(snap(0.0, 0.0)))
        // Out of range is never a position either.
        assertNull(WeatherPolicy.positionOf(snap(91.0, 11.5)))
        assertNull(WeatherPolicy.positionOf(null))
    }

    @Test
    fun `the last known position is next, and it remembers its name`() {
        val target = WeatherPolicy.positionLadder(
            fix = snap(0.0, 0.0, fix = 0),
            cached = CachedPosition(47.98, 11.47, fixEpochMs = now - 10 * hour, label = "Wolfratshausen"),
            city = null,
            nowMs = now,
        )!!

        assertEquals(PositionSource.CACHE, target.source)
        assertEquals(47.98, target.lat, 0.0)
        // The cached label is what lets a link come up and push weather with no
        // network call at all.
        assertEquals("Wolfratshausen", target.labelHint)
    }

    @Test
    fun `a position older than a day is not a position`() {
        val target = WeatherPolicy.positionLadder(
            fix = null,
            cached = CachedPosition(47.98, 11.47, fixEpochMs = now - 25 * hour, label = "Wolfratshausen"),
            city = Place("Munich", "Bavaria", "Germany", 48.13743, 11.57549),
            nowMs = now,
        )!!

        // Twenty-four hours is the plan's cap: a fixed-mount unit that has not moved
        // still gets the city its owner typed rather than a day-old coordinate.
        assertEquals(PositionSource.CITY, target.source)
        assertEquals("Munich", target.labelHint)
    }

    @Test
    fun `a typed city is the last thing standing`() {
        val target = WeatherPolicy.positionLadder(null, null, Place("Seeshaupt", "Bavaria", "Germany", 47.87, 11.21), now)!!

        assertEquals(PositionSource.CITY, target.source)
        assertEquals(47.87, target.lat, 0.0)
    }

    @Test
    fun `nothing usable is no target at all`() {
        assertNull(WeatherPolicy.positionLadder(null, null, null, now))
    }

    // ---- when a refresh is due --------------------------------------------

    @Test
    fun `the first feed is always due`() {
        assertEquals(WeatherDue.FIRST, WeatherPolicy.due(now, lastFetchMs = null, last = null, current = null, enabled = true))
    }

    @Test
    fun `fifteen minutes makes it due`() {
        val last = LatLon(48.13743, 11.57549)

        assertEquals(
            WeatherDue.NEVER,
            WeatherPolicy.due(now + 14 * minute, lastFetchMs = now, last = last, current = last, enabled = true),
        )
        assertEquals(
            WeatherDue.INTERVAL,
            WeatherPolicy.due(now + WeatherPolicy.INTERVAL_MS, lastFetchMs = now, last = last, current = last, enabled = true),
        )
    }

    @Test
    fun `two kilometres is a new place`() {
        val last = LatLon(48.13743, 11.57549)
        // 1.9 km: same town, no reason to ask again.
        val near = LatLon(48.15200, 11.57549)
        // 2.4 km: the other side of the river, and a different forecast.
        val far = LatLon(48.15900, 11.57549)

        assertEquals(1.62, WeatherPolicy.movedKm(last, near), 0.05)
        assertEquals(2.40, WeatherPolicy.movedKm(last, far), 0.05)

        assertEquals(
            WeatherDue.NEVER,
            WeatherPolicy.due(now + 5 * minute, lastFetchMs = now, last = last, current = near, enabled = true),
        )
        assertEquals(
            WeatherDue.MOVED,
            WeatherPolicy.due(now + 5 * minute, lastFetchMs = now, last = last, current = far, enabled = true),
        )
    }

    @Test
    fun `a move is due even when the interval says otherwise`() {
        // MOVED has to beat the clock, or a car driving into different weather waits
        // up to fifteen minutes for a forecast that is already wrong.
        val t = WeatherPolicy.due(
            now + minute, lastFetchMs = now,
            last = LatLon(48.13743, 11.57549), current = LatLon(48.40, 11.90), enabled = true,
        )
        assertEquals(WeatherDue.MOVED, t)
    }

    @Test
    fun `a disabled feed never runs`() {
        assertEquals(WeatherDue.NEVER, WeatherPolicy.due(now, null, null, null, enabled = false))
    }

    @Test
    fun `the schedule constants are the plan's numbers`() {
        // Pinned on purpose: the 6 h time gap is the firmware's RTC drift figure
        // (README), and the rest are the plan's. Changing one is a decision.
        assertEquals(15 * minute, WeatherPolicy.INTERVAL_MS)
        assertEquals(2.0, WeatherPolicy.MOVE_TRIGGER_KM, 0.0)
        assertEquals(24 * hour, WeatherPolicy.CACHE_MAX_AGE_MS)
        assertEquals(3 * hour, WeatherPolicy.OFFLINE_AFTER_MS)
        assertEquals(5 * minute, WeatherPolicy.RETRY_AFTER_FAILURE_MS)
        assertEquals(6 * hour, WeatherPolicy.TIME_REPUSH_MS)
        assertEquals(60_000L, WeatherPolicy.RETRY_AFTER_OTA_MS)
    }

    // ---- "is the picture on the panel still current?" ---------------------

    @Test
    fun `three hours without a successful feed is offline`() {
        assertFalse(WeatherPolicy.offline(now, lastSuccessMs = now - 179 * minute))
        assertTrue(WeatherPolicy.offline(now, lastSuccessMs = now - WeatherPolicy.OFFLINE_AFTER_MS))
        // No successful feed yet: there is nothing on the panel to mark stale.
        assertFalse(WeatherPolicy.offline(now, lastSuccessMs = null))
    }

    @Test
    fun `two minutes parked is neither a move nor stale`() {
        // The combination the plan cares about: a unit parked for two hours keeps its
        // weather "current" (under the 3 h cap) while the 15 min clock still refreshes.
        val last = LatLon(48.13743, 11.57549)
        assertEquals(WeatherDue.NEVER, WeatherPolicy.due(now + 2 * minute, now, last, last, true))
        assertFalse(WeatherPolicy.offline(now + 2 * minute, lastSuccessMs = now))
        // A link down for three hours is the other half of the same rule.
        assertTrue(WeatherPolicy.offline(now + 3 * hour, lastSuccessMs = now))
    }

    @Test
    fun `the distance arithmetic holds where it can be checked`() {
        // Munich (48.14, 11.58) to Seeshaupt, straight north-south: 0.01 deg of
        // latitude is about 1.11 km, and longitude must not matter here.
        assertEquals(1.11, WeatherPolicy.movedKm(LatLon(48.0, 11.0), LatLon(48.01, 11.0)), 0.02)
        assertEquals(0.0, WeatherPolicy.movedKm(LatLon(48.0, 11.0), LatLon(48.0, 11.0)), 0.0)
        // The anti-meridian must not read as a jump around the world.
        assertTrue(WeatherPolicy.movedKm(LatLon(0.0, 179.9), LatLon(0.0, -179.9)) < 25.0)
    }
}
