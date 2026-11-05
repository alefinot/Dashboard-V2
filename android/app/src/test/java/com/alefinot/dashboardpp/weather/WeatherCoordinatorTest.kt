package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.ble.BleReply
import com.alefinot.dashboardpp.api.Snapshot
import java.util.ArrayDeque
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The weather feed's schedule, end to end, with the unit and the network both faked.
 *
 * What is under test here is not parsing — that has its own files — but **what the app
 * decides to do**: which calls a wake spends, which it refuses to spend, and what it
 * remembers for the next wake. Those are the rules the plan set (15 min, 2 km, 3 h, one
 * offline frame per transition, one snapshot RPC per cycle, a unit writing firmware left
 * alone), and they are the ones that are invisible on a bench unless something is wrong.
 */
class WeatherCoordinatorTest {

    private val unitKey = "mac:AABBCCDDEEFF"
    private val t0 = 1_762_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    private var now = t0
    private val unit = FakeUnit()
    private val store = FakeStore()
    private val http = RoutingHttp()
    private lateinit var feed: WeatherCoordinator

    @Before
    fun setUp() {
        HostFrames.clear(unitKey)
        now = t0
        feed = WeatherCoordinator(unit, store, http, PlaceNames(http, { now }), { now })
    }

    // ---- the first feed -------------------------------------------------------------------

    @Test
    fun `a first link is fed from the unit's own fix`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)
        http.reverseBody = """{"address":{"city":"Munich","state":"Bavaria"}}"""

        feed.wake("link", unitKey)

        assertEquals(OpenMeteo.url(48.13743, 11.57549), http.forecasts.single())
        val push = unit.pushes.single()
        assertEquals("Munich", push.city)
        assertEquals(11.5, push.temperature, 0.0)
        assertTrue(push.valid)

        val row = store.rows.getValue(unitKey)
        assertEquals(t0, row.fetchEpochMs)
        assertEquals(t0, row.lastPushEpochMs)
        assertEquals("Munich", row.label)
        assertEquals(PositionSource.FIX, feed.status.value.source)
    }

    @Test
    fun `no position anywhere means no forecast call and a note that says so`() = runBlocking {
        unit.fix = null

        feed.wake("link", unitKey)

        assertTrue(http.forecasts.isEmpty())
        assertTrue(unit.pushes.isEmpty())
        assertEquals(1, unit.snapshotCalls)
        assertTrue(feed.status.value.note!!.contains("city"))
    }

    @Test
    fun `the typed city feeds the forecast when the unit has no fix`() = runBlocking {
        unit.fix = null
        store.city = CityFallback(
            typed = "Munich",
            place = Place("Munich", "Bavaria", "Germany", 48.13743, 11.57549),
        )

        feed.wake("link", unitKey)

        assertEquals(OpenMeteo.url(48.13743, 11.57549), http.forecasts.single())
        // The city already carries its name, so the card does not pay a reverse call for it.
        assertTrue(http.reverses.isEmpty())
        assertEquals(PositionSource.CITY, feed.status.value.source)
    }

    // ---- the schedule ---------------------------------------------------------------------

    @Test
    fun `a feed five minutes old with no movement spends nothing at all`() = runBlocking {
        store.rows[unitKey] = cacheRow(fetchedAt = t0 - 5 * minute, pushedAt = t0 - 5 * minute)

        feed.wake("tick", unitKey)

        assertTrue(http.forecasts.isEmpty())
        assertTrue(unit.pushes.isEmpty())
        // Not even the snapshot RPC: the ladder cannot need a position this pass.
        assertEquals(0, unit.snapshotCalls)
    }

    @Test
    fun `fifteen minutes re-fetches`() = runBlocking {
        store.rows[unitKey] = cacheRow(fetchedAt = t0 - WeatherPolicy.INTERVAL_MS, pushedAt = t0 - WeatherPolicy.INTERVAL_MS)
        unit.fix = snapshotAt(48.13743, 11.57549)

        feed.wake("tick", unitKey)

        assertEquals(1, http.forecasts.size)
        assertEquals(1, unit.pushes.size)
    }

    @Test
    fun `two kilometres of driving re-fetches`() = runBlocking {
        store.rows[unitKey] = cacheRow(fetchedAt = t0 - 5 * minute, pushedAt = t0 - 5 * minute)

        // 0.02 deg of latitude is about 2.2 km, just over the trigger.
        feed.wake("frame", unitKey, snapshotAt(48.15743, 11.57549))

        assertEquals(OpenMeteo.url(48.15743, 11.57549), http.forecasts.single())
    }

    @Test
    fun `two hundred metres of driving does not`() = runBlocking {
        store.rows[unitKey] = cacheRow(fetchedAt = t0 - 5 * minute, pushedAt = t0 - 5 * minute)

        feed.wake("frame", unitKey, snapshotAt(48.13923, 11.57549))

        assertTrue(http.forecasts.isEmpty())
        assertTrue(unit.pushes.isEmpty())
    }

    @Test
    fun `the move check costs nothing when the host hands over its frame`() = runBlocking {
        store.rows[unitKey] = cacheRow(fetchedAt = t0 - 5 * minute, pushedAt = t0 - 5 * minute)

        feed.wake("frame", unitKey, snapshotAt(48.13843, 11.57549))

        assertEquals(0, unit.snapshotCalls)
    }

    @Test
    fun `PUSH NOW fetches even a minute after the last feed`() = runBlocking {
        store.rows[unitKey] = cacheRow(fetchedAt = t0 - minute, pushedAt = t0 - minute)
        unit.fix = snapshotAt(48.13743, 11.57549)

        feed.pushNow(unitKey)

        assertEquals(1, http.forecasts.size)
    }

    @Test
    fun `a switch off stops every call`() = runBlocking {
        store.enabled = false
        unit.fix = snapshotAt(48.13743, 11.57549)
        store.rows[unitKey] = cacheRow(fetchedAt = t0 - hour, pushedAt = t0 - hour)

        feed.wake("tick", unitKey)

        assertTrue(http.urls.isEmpty())
        assertTrue(unit.pushes.isEmpty())
        assertEquals(0, unit.snapshotCalls)
    }

    @Test
    fun `two hosts waking at once cost one fetch`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)

        coroutineScope {
            launch { feed.wake("tick", unitKey) }
            launch { feed.wake("frame", unitKey) }
        }

        assertEquals(1, http.forecasts.size)
        assertEquals(1, unit.pushes.size)
    }

    // ---- failure paths --------------------------------------------------------------------

    @Test
    fun `a provider that says nothing is a note, not a push, and is retried on its own clock`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)
        http.openMeteoBody = null

        feed.wake("tick", unitKey)

        assertTrue(unit.pushes.isEmpty())
        assertTrue(feed.status.value.note!!.contains("Open-Meteo"))
        // Nothing is cached from a failed fetch, so the next wake does not think it is fed.
        assertNull(store.rows[unitKey])

        // One minute later: still inside the back-off, so no second call.
        now = t0 + minute
        feed.wake("tick", unitKey)
        assertEquals(1, http.forecasts.size)

        // Past the back-off, and with the provider answering again, the feed recovers.
        http.openMeteoBody = OPEN_METEO_BODY
        now = t0 + WeatherPolicy.RETRY_AFTER_FAILURE_MS + minute
        feed.wake("tick", unitKey)
        assertEquals(2, http.forecasts.size)
        assertEquals(1, unit.pushes.size)
    }

    @Test
    fun `a unit writing firmware is left alone and asked again a minute later`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)
        unit.replies.add(BleReply.fail("ota_in_progress"))

        feed.wake("tick", unitKey)

        assertEquals(1, unit.pushes.size)
        // The fetch is remembered even though the unit refused, so the phone does not pay
        // Open-Meteo twice for one frame.
        assertEquals(t0, store.rows.getValue(unitKey).fetchEpochMs)

        now = t0 + 30_000L
        feed.wake("tick", unitKey)
        assertEquals(1, unit.pushes.size)

        now = t0 + WeatherPolicy.RETRY_AFTER_OTA_MS + 1_000L
        feed.wake("tick", unitKey)
        assertEquals(2, unit.pushes.size)
        assertEquals(t0 + WeatherPolicy.RETRY_AFTER_OTA_MS + 1_000L, store.rows.getValue(unitKey).lastPushEpochMs)
    }

    @Test
    fun `the unit's settings mutex is retried once without waiting`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)
        unit.replies.add(BleReply.fail("busy"))

        feed.wake("tick", unitKey)

        assertEquals(2, unit.pushes.size)
        assertNull(feed.status.value.note)
        assertEquals(t0, store.rows.getValue(unitKey).lastPushEpochMs)
    }

    @Test
    fun `a refused payload names the payload, because that is our bug`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)
        unit.replies.add(BleReply.fail("bad_param"))

        feed.wake("tick", unitKey)

        assertTrue(feed.status.value.note!!.contains("rejected"))
        assertTrue(unit.pushes.size == 1)
    }

    @Test
    fun `a frame the unit refused is sent again without paying for it twice`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)
        unit.replies.add(BleReply.fail("link_down"))

        feed.wake("tick", unitKey)
        val firstUrlCount = http.forecasts.size

        now = t0 + WeatherPolicy.RETRY_AFTER_FAILURE_MS + minute
        feed.wake("tick", unitKey)

        assertEquals(2, unit.pushes.size)
        assertEquals("the second push is the same reading", unit.pushes[0], unit.pushes[1])
        assertEquals("and no second fetch", firstUrlCount, http.forecasts.size)
    }

    // ---- staleness ------------------------------------------------------------------------

    @Test
    fun `a unit whose feed went three hours stale gets exactly one goodbye frame`() = runBlocking {
        store.rows[unitKey] = cacheRow(
            fetchedAt = t0 - 4 * hour,
            pushedAt = t0 - 4 * hour,
            label = "Munich",
        )

        feed.wake("tick", unitKey)

        val goodbye = unit.pushes.single()
        assertEquals(false, goodbye.valid)
        assertEquals("Munich", goodbye.city)
        assertTrue(store.rows.getValue(unitKey).offlineSent)
    }

    @Test
    fun `the goodbye is not repeated, and a recovered feed ends it`() = runBlocking {
        store.rows[unitKey] = cacheRow(
            fetchedAt = t0 - 4 * hour,
            pushedAt = t0 - 4 * hour,
            label = "Munich",
            offlineSent = true,
        )
        unit.fix = snapshotAt(48.13743, 11.57549)

        feed.wake("tick", unitKey)

        // No second offline frame; the schedule is overdue, so this is a live fetch.
        val push = unit.pushes.single()
        assertTrue(push.valid)
        assertEquals(false, store.rows.getValue(unitKey).offlineSent)
    }

    @Test
    fun `a unit that was never fed gets no goodbye frame`() = runBlocking {
        store.rows[unitKey] = cacheRow(
            fetchedAt = t0 - 4 * hour,
            pushedAt = WeatherSettings.NO_EPOCH,
            label = "Munich",
        )

        feed.wake("tick", unitKey)

        // There is no stale picture on the panel to correct, so nothing is sent to
        // "turn it offline"; the schedule just fetches.
        assertTrue(unit.pushes.all { it.valid })
    }

    // ---- the clock re-push -----------------------------------------------------------------

    @Test
    fun `the clock is not pushed twice at link-up`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)

        feed.wake("link", unitKey)

        assertTrue(unit.times.isEmpty())
    }

    @Test
    fun `six hours later the clock is pushed again`() = runBlocking {
        unit.fix = snapshotAt(48.13743, 11.57549)

        feed.wake("link", unitKey)
        now = t0 + WeatherPolicy.TIME_REPUSH_MS
        feed.wake("tick", unitKey)

        assertEquals(listOf((t0 + WeatherPolicy.TIME_REPUSH_MS) / 1000L), unit.times)
    }

    @Test
    fun `a new link restarts the clock timer`() = runBlocking {
        feed.wake("link", unitKey)
        now = t0 + WeatherPolicy.TIME_REPUSH_MS - minute
        feed.wake("tick", unitKey)
        assertTrue(unit.times.isEmpty())

        // A different unit comes up: the ViewModel pushes its clock, this timer must not
        // fire immediately on top of that.
        val other = "mac:112233445566"
        feed.wake("link", other)
        assertTrue(unit.times.isEmpty())
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun snapshotAt(lat: Double, lon: Double): Snapshot =
        Snapshot.parse("""{"up":420,"spd":12.0,"lat":$lat,"lon":$lon,"fix":1}""")!!

    private fun cacheRow(
        fetchedAt: Long,
        pushedAt: Long,
        label: String? = "Munich",
        offlineSent: Boolean = false,
    ) = WeatherCache(
        lat = 48.13743,
        lon = 11.57549,
        label = label,
        fetchEpochMs = fetchedAt,
        lastPushEpochMs = pushedAt,
        offlineSent = offlineSent,
    )

    private class FakeUnit : WeatherUnit {
        var fix: Snapshot? = null
        var snapshotCalls = 0
        val pushes = mutableListOf<com.alefinot.dashboardpp.ble.WeatherPush>()
        val times = mutableListOf<Long>()
        val replies = ArrayDeque<BleReply>()
        var reply: BleReply = BleReply(ok = true)

        override suspend fun snapshot(): Snapshot? {
            snapshotCalls++
            return fix
        }

        override suspend fun pushWeather(push: com.alefinot.dashboardpp.ble.WeatherPush): BleReply {
            pushes += push
            return replies.poll() ?: reply
        }

        override suspend fun pushTime(epochSeconds: Long): BleReply {
            times += epochSeconds
            return BleReply(ok = true)
        }
    }

    private class FakeStore(
        var enabled: Boolean = true,
        var city: CityFallback = CityFallback(null, null),
    ) : WeatherStore {
        val rows = mutableMapOf<String, WeatherCache>()

        override suspend fun enabledNow(): Boolean = enabled
        override suspend fun cityNow(): CityFallback = city
        override suspend fun cacheFor(unitKey: String): WeatherCache? = rows[unitKey]
        override suspend fun saveCache(unitKey: String, cache: WeatherCache) {
            rows[unitKey] = cache
        }
    }

    /** One fake for both providers, routed by URL so the calls stay countable. */
    private class RoutingHttp : WeatherHttp {
        val urls = mutableListOf<String>()
        var openMeteoBody: String? = OPEN_METEO_BODY
        var reverseBody: String? = null
        var searchBody: String? = null

        val forecasts: List<String> get() = urls.filter { it.startsWith(OpenMeteo.HOST) }
        val reverses: List<String> get() = urls.filter { it.startsWith(PlaceNames.REVERSE_URL) }

        override suspend fun get(url: String): String? {
            urls += url
            return when {
                url.startsWith(OpenMeteo.HOST) -> openMeteoBody
                url.startsWith(PlaceNames.SEARCH_URL) -> searchBody
                url.startsWith(PlaceNames.REVERSE_URL) -> reverseBody
                else -> null
            }
        }
    }

    private companion object {
        /** A real-shaped answer: current conditions plus the location's own two days. */
        const val OPEN_METEO_BODY =
            """{"current":{"time":1762000000,"temperature_2m":11.5,"relative_humidity_2m":72,""" +
                """"wind_speed_10m":14.4,"wind_direction_10m":210,"weather_code":3,"cloud_cover":68},""" +
                """"daily":{"time":["2025-11-01","2025-11-02"],"sunrise":[1761980400,1762066800],""" +
                """"sunset":[1762016400,1762102800]}}"""
    }
}
