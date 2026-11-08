package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules over the dashboard's own 1 Hz push, written down because a recorder that
 * mis-applies them produces trip files that look fine.
 *
 * The unit sends a snapshot frame on its own; the app stopped asking. A poll could assume
 * novelty, a stream cannot: the same frame can arrive twice (a notification re-issued
 * after a reconnect, or the app's own prod answering after a pushed frame already
 * landed), and a link can say `Ready` while nothing arrives at all. Both failures are
 * silent in the CSV — a duplicated sample lengthens the distance and drags the average
 * with no trace of where it came from — so the rules are pure functions here and the
 * recorder calls them.
 */
class TelemetryFeedTest {

    @Test
    fun `the first frame is always worth taking`() {
        // -1 is "nothing accepted yet", and a trip that starts mid-drive must not be
        // gated on a reading from before it began.
        assertTrue(TelemetryFeed.accepts(lastUp = -1L, frameUp = 0L))
        assertTrue(TelemetryFeed.accepts(lastUp = -1L, frameUp = 41_000L))
    }

    @Test
    fun `the same reading twice is not a sample`() {
        assertFalse(TelemetryFeed.accepts(lastUp = 100L, frameUp = 100L))
        // The re-issued notification after a reconnect is the case this exists for: the
        // link comes back, the peripheral re-sends its last frame, and the trip file
        // would gain a second of driving that the unit never measured.
        assertFalse(TelemetryFeed.accepts(lastUp = 41_000L, frameUp = 41_000L))
    }

    @Test
    fun `a frame from a unit that restarted is taken`() {
        // `up` is the ESP's uptime: it only falls when the unit reboots, and a reboot
        // mid-drive is a real event the recorder splits the trip on. Refusing a smaller
        // `up` would drop the first frames of the new session — the ones that say the
        // dashboard restarted.
        assertTrue(TelemetryFeed.accepts(lastUp = 41_000L, frameUp = 3L))
        assertTrue(TelemetryFeed.accepts(lastUp = 41_000L, frameUp = 41_001L))
    }

    @Test
    fun `a quiet stream is prodded once, not repeatedly`() {
        // Never a frame on a link that reports itself ready: the subscription did not
        // take, and asking is the only way to find that out.
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
        // The window is five seconds of a 1 Hz stream: about five frames of grace for a
        // unit that is mid-reboot, and short enough that a dead subscription is noticed
        // while the driver is still looking at the page.
        assertEquals(5_000L, TelemetryFeed.SILENT_MS)
    }

    /**
     * The prod's answer is a sample only if it is a **different reading**.
     *
     * The other half of "the prod never overwrites a newer frame" - the case where a
     * pushed frame landed while the RPC was in flight - is not decided here, and the
     * reason is worth writing down: `up` cannot tell a stale reply from a rebooted unit,
     * both being a number that went down. The caller decides it by arrival instead, by
     * only ever asking when the wait produced nothing at all. So this rule is the
     * duplicate case, which is the one these values can actually see.
     */
    @Test
    fun `the prod answer is taken once and not twice`() {
        val reply = snapshot(up = 210L)
        // Nothing accepted yet: the answer is the fresh reading.
        assertEquals(210L, TelemetryFeed.mergeProd(lastUp = -1L, reply = reply)?.up)
        // The same frame twice - a notification re-issued after a reconnect, or the app
        // answering itself - is not a second sample.
        assertNull(TelemetryFeed.mergeProd(lastUp = 210L, reply = reply))
        // A smaller `up` is kept, because it might be a unit that restarted and the
        // alternative is throwing away the first frames after a reboot.
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
