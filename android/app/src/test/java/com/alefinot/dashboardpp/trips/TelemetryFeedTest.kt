package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFeedTest {

    @Test
    fun `the first frame is always worth taking`() {
        assertTrue(TelemetryFeed.accepts(lastUp = -1L, frameUp = 0L))
        assertTrue(TelemetryFeed.accepts(lastUp = -1L, frameUp = 41_000L))
    }

    @Test
    fun `the same reading twice is not a sample`() {
        assertFalse(TelemetryFeed.accepts(lastUp = 100L, frameUp = 100L))
        assertFalse(TelemetryFeed.accepts(lastUp = 41_000L, frameUp = 41_000L))
    }

    @Test
    fun `a frame from a unit that restarted is taken`() {
        assertTrue(TelemetryFeed.accepts(lastUp = 41_000L, frameUp = 3L))
        assertTrue(TelemetryFeed.accepts(lastUp = 41_000L, frameUp = 41_001L))
    }

    @Test
    fun `a quiet stream is prodded once, not repeatedly`() {
        assertTrue(TelemetryFeed.dueForPoll(lastFrameMs = 0L, nowMs = 1_000L, pollInFlight = false))
        // Under the window: the unit is simply between frames.
        assertFalse(
            TelemetryFeed.dueForPoll(lastFrameMs = 10_000L, nowMs = 10_000L + TelemetryFeed.SILENT_MS - 1,
                pollInFlight = false),
        )
        // Over the window, and the prod is worth spending.
        assertTrue(
            TelemetryFeed.dueForPoll(lastFrameMs = 10_000L, nowMs = 10_000L + TelemetryFeed.SILENT_MS,
                pollInFlight = false),
        )
        // One request at a time, whatever the clock says.
        for (t in listOf(0L, 10_000L, 10_000L + TelemetryFeed.SILENT_MS)) {
            assertFalse(TelemetryFeed.dueForPoll(t, t, pollInFlight = true))
        }
        assertEquals(5_000L, TelemetryFeed.SILENT_MS)
    }

    @Test
    fun `the prod answer is taken once and not twice`() {
        val reply = snapshot(up = 210L)
        // Nothing accepted yet: the answer is the fresh reading.
        assertEquals(210L, TelemetryFeed.mergeProd(lastUp = -1L, reply = reply)?.up)
        assertNull(TelemetryFeed.mergeProd(lastUp = 210L, reply = reply))
        assertEquals(210L, TelemetryFeed.mergeProd(lastUp = 999L, reply = reply)?.up)
        // No answer at all (link dropped, refused, or unparseable) is "nothing new".
        assertNull(TelemetryFeed.mergeProd(lastUp = -1L, reply = null))
    }

    /** A frame shaped exactly as the firmware prints it, with only `up` varying. */
    private fun snapshot(up: Long): Snapshot = Snapshot.parse(
        """{"ver":"1.5.0","up":$up,"spd":63.5,"src":1,"avg":48.2,"max":91.2,"trp_km":18.40,""" +
            """"trp_l":1.35,"ikml":14.2,"akml":13.6,"odo":12480.25,"fuel_l":41.20,"fuel_pct":62,""" +
            """"fuel_st":1,"volts":13.92,"temp":94.5,"amb":21,"sats":9,"hdop":1.20,"alt":743,""" +
            """"lat":-6.914532,"lon":107.641239,"fix":1,"tmr_st":0,"tmr_s":0.00,"fps":28.4,"heap":118432}""",
    )!!
}
