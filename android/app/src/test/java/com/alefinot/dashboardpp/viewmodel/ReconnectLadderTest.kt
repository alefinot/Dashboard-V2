package com.alefinot.dashboardpp.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect rule the shell actually runs on: what the app does when a link drops,
 * when the phone's radio comes back, and when a finger presses CONNECT.
 *
 * [ReconnectPolicy] (the deleted LAN rule) was tested against a fake clock and a
 * callback source that could fire hundreds of times a minute. This one is tested against
 * the ladder itself, because the ladder is the whole behaviour: there is no timer, no
 * queue and no rate limit behind it — the view model reads one of these decisions per
 * event and nothing else decides when a dial is worth spending.
 */
class ReconnectLadderTest {

    private val paired = true
    private val radioOn = true

    @Test
    fun `a dropped link retries on the ladder and then gives up`() {
        for ((attempt, expected) in listOf(0 to 2_000L, 1 to 5_000L, 2 to 15_000L)) {
            val step = ReconnectLadder.step(LinkEvent.Dropped("gatt 133"), attempt, paired, radioOn)
            assertEquals("retry #${attempt + 1}", LadderStep.Retry(expected), step)
        }
        val over = ReconnectLadder.step(LinkEvent.Dropped("gatt 133"), 3, paired, radioOn)
        assertTrue(
            "the fourth drop must end the attempt, not extend it: $over",
            over is LadderStep.GiveUp,
        )
        // The reason is a line under the card, so it has to name the count and carry the
        // radio's own words when there are any.
        val reason = (over as LadderStep.GiveUp).reason
        assertTrue(reason, reason.contains("3 attempts"))
        assertTrue(reason, reason.contains("gatt 133"))
    }

    @Test
    fun `giving up says nothing invented when the radio gave no reason`() {
        val reason = (ReconnectLadder.step(LinkEvent.Dropped(null), 3, paired, radioOn)
            as LadderStep.GiveUp).reason
        assertTrue(reason, !reason.contains("()"))
        assertTrue(reason, reason.isNotBlank())
    }

    @Test
    fun `a link that came up ends the ladder`() {
        assertEquals(LadderStep.Linked, ReconnectLadder.step(LinkEvent.Linked, 0, paired, radioOn))
        // Even mid-ladder, and even with nothing to dial: an up link is the answer to
        // every question this object asks.
        assertEquals(LadderStep.Linked, ReconnectLadder.step(LinkEvent.Linked, 9, false, false))
    }

    @Test
    fun `the user's own ask never waits`() {
        // Pressing CONNECT is a statement that the user is watching. Putting their ask
        // behind the same 2 s floor as our own retry would be the app making them wait
        // for machinery they did not start.
        assertEquals(LadderStep.Retry(0), ReconnectLadder.step(LinkEvent.UserAsked, 0, paired, radioOn))
        assertEquals(LadderStep.Retry(0), ReconnectLadder.step(LinkEvent.UserAsked, 3, paired, radioOn))
    }

    @Test
    fun `an ask after giving up is still an ask`() {
        // The ladder's cap is about not hammering a radio after one failure. It is not a
        // verdict on the vehicle, and it must never turn into a lockout: the counter
        // resets because the view model restarts the attempt, and this guard says the
        // ladder will not refuse just because it is already counting.
        val step = ReconnectLadder.step(LinkEvent.UserAsked, 7, paired, radioOn)
        assertEquals(LadderStep.Retry(0), step)
    }

    @Test
    fun `the radio coming back restarts a ladder that was stopped, not one that finished`() {
        // The adapter being off explains every failure since, so its return is a dial in
        // its own right. But the attempt counter carries on where it was: a unit that has
        // already refused three dials does not become reachable because the phone's radio
        // was toggled, and a fourth and fifth dial are how a phone is drained in a car park.
        assertEquals(
            LadderStep.Retry(2_000),
            ReconnectLadder.step(LinkEvent.AdapterOn, 0, paired, radioOn),
        )
        assertEquals(
            LadderStep.Retry(15_000),
            ReconnectLadder.step(LinkEvent.AdapterOn, 2, paired, radioOn),
        )
        assertEquals(LadderStep.Stop, ReconnectLadder.step(LinkEvent.AdapterOn, 3, paired, radioOn))
    }

    @Test
    fun `an adapter that is off is not dialled at all`() {
        // No dial is possible, and a Failed line about a dashboard that never had a
        // chance to answer would blame the wrong thing.
        assertEquals(LadderStep.Stop, ReconnectLadder.step(LinkEvent.Dropped(null), 0, paired, false))
        assertEquals(LadderStep.Stop, ReconnectLadder.step(LinkEvent.AdapterOff, 0, paired, radioOn))
        assertEquals(LadderStep.Stop, ReconnectLadder.step(LinkEvent.AdapterOff, 2, paired, radioOn))
    }

    @Test
    fun `an unattended event for a card that cannot be dialled stays quiet`() {
        // A card with no pairing can never be reached by a reconnect: this phone holds no
        // bond to dial. The ladder says nothing (the user did not ask) and schedules
        // nothing, which is the difference between this and the pressed-CONNECT case below.
        for (event in listOf(LinkEvent.Dropped(null), LinkEvent.AdapterOn)) {
            assertEquals(event.toString(), LadderStep.Stop, ReconnectLadder.step(event, 0, false, radioOn))
        }
    }

    @Test
    fun `a pressed connect on a card that cannot be dialled says why`() {
        // The same blocked condition, reported: the user pressed CONNECT and is owed a
        // sentence rather than a spinning ring that never resolves.
        val step = ReconnectLadder.step(LinkEvent.UserAsked, 0, false, radioOn) as LadderStep.GiveUp
        assertTrue(step.reason, step.reason.contains("never been paired"))
        assertTrue(step.reason, step.reason.contains("ADD VEHICLE"))

        val radio = ReconnectLadder.step(LinkEvent.UserAsked, 0, paired, false) as LadderStep.GiveUp
        assertTrue(radio.reason, radio.reason.contains("Bluetooth is off"))
        assertNull(ReconnectLadder.blocked(paired, radioOn))
        assertNotNull(ReconnectLadder.blocked(false, radioOn))
    }

    /** The ladder's shape is part of the design, not an implementation detail. */
    @Test
    fun `the ladder is three escalating delays`() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L), ReconnectLadder.BACKOFF_MS)
        assertTrue(
            "escalating, or a rebooting unit and a unit out of range get the same patience",
            ReconnectLadder.BACKOFF_MS.zipWithNext().all { (a, b) -> a < b },
        )
    }
}
