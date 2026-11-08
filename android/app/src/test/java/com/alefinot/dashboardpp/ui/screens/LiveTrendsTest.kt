package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.trips.snap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history the Live page draws its traces from.
 *
 * The buffer exists because a snapshot cannot answer the question a rider asks about heat and
 * voltage ("is it climbing?"), and because a Live page that remembered everything would grow
 * with the length of a ride. So the two properties that matter are that it *does* fill at the
 * cadence the cards claim (one point every 5 s, not one per frame — the page shows 1 Hz figures
 * in the same card and a trace that lies about its own resolution is worse than no trace) and
 * that it *stops* growing at the cap, dropping the oldest point.
 */
class LiveTrendsTest {

    private fun sample(trends: LiveTrends, index: Int, key: String? = "AA:BB:CC:DD:EE:FF") =
        trends.sample(
            nowMs = index * 1_000L,
            unitKey = key,
            s = snap(up = index.toLong(), spd = index.toDouble(), volts = 12.0 + index / 100.0, temp = 80.0 + index / 10.0),
        )

    @Test
    fun aPointLandsEveryFiveSecondsAndNotOncePerFrame() {
        val trends = LiveTrends()
        var stored = 0
        for (i in 0 until 100) if (sample(trends, i)) stored++
        // One hundred 1 Hz frames, i.e. the clock reads 0..99 s: a point lands at 0, 5, 10 … 95,
        // which is twenty. Anything denser is a trace that draws the same reading four times.
        assertEquals(20, stored)
        assertEquals(20, trends.values("spd").size)
    }

    @Test
    fun theWindowKeepsItsCapAndDropsTheOldestPoint() {
        val trends = LiveTrends(cap = 60, everyMs = 5_000L)
        for (i in 0 until 400) sample(trends, i)
        val spd = trends.values("spd")
        assertEquals("the window grew past its cap", 60, spd.size)
        // The clock reaches 399 s, so 80 points were taken (0, 5 … 395) and the cap kept the
        // last 60 of them: the oldest survivor was taken at 395 - 59×5 = 100 s.
        assertEquals(100.0, spd.first(), 0.001)
        assertEquals(395.0, spd.last(), 0.001)
        assertTrue("the trace must run oldest to newest so its right edge is now", spd.first() < spd.last())
    }

    @Test
    fun everyTracedFigureGetsItsPoint() {
        val trends = LiveTrends()
        sample(trends, 0)
        assertEquals(listOf(0.0), trends.values("spd"))
        assertEquals(12.0, trends.values("volts").first(), 0.001)
        assertEquals(80.0, trends.values("temp").first(), 0.001)
    }

    @Test
    fun pairingAnotherDashboardStartsTheHistoryEmpty() {
        val trends = LiveTrends()
        sample(trends, 0, key = "AA:AA:AA:AA:AA:AA")
        sample(trends, 5, key = "AA:AA:AA:AA:AA:AA")
        assertEquals(2, trends.values("spd").size)
        sample(trends, 10, key = "BB:BB:BB:BB:BB:BB")
        // The previous unit's warm-up is not this unit's history: showing it is how a cold
        // engine gets drawn with a hot five minutes behind it.
        assertEquals(listOf(10.0), trends.values("spd"))
    }

    @Test
    fun theNoteTheCardsQuoteMatchesTheBuffer() {
        val trends = LiveTrends()
        assertEquals("5 min", trends.windowLabel())
        assertEquals(5_000L, trends.everyMs)
        // The cards print "one point every ${everyMs / 1000} s" — that sentence is only true
        // while the window and the cadence still spell five minutes between them.
        assertEquals(300_000L, trends.cap.toLong() * trends.everyMs)
    }

    @Test
    fun anUnsampledFrameChangesNothing() {
        val trends = LiveTrends()
        sample(trends, 0)
        val before = trends.values("spd")
        assertFalse(sample(trends, 1))
        assertFalse(sample(trends, 4))
        assertEquals(before, trends.values("spd"))
        assertTrue(sample(trends, 5))
    }
}
