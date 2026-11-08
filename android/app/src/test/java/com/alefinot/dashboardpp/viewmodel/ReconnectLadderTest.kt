package com.alefinot.dashboardpp.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val step = ReconnectLadder.step(LinkEvent.UserAsked, 7, paired, radioOn)
        assertEquals(LadderStep.Retry(0), step)
    }

    @Test
    fun `the radio coming back restarts a ladder that was stopped, not one that finished`() {
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
        assertEquals(LadderStep.Stop, ReconnectLadder.step(LinkEvent.Dropped(null), 0, paired, false))
        assertEquals(LadderStep.Stop, ReconnectLadder.step(LinkEvent.AdapterOff, 0, paired, radioOn))
        assertEquals(LadderStep.Stop, ReconnectLadder.step(LinkEvent.AdapterOff, 2, paired, radioOn))
    }

    @Test
    fun `an unattended event for a card that cannot be dialled stays quiet`() {
        for (event in listOf(LinkEvent.Dropped(null), LinkEvent.AdapterOn)) {
            assertEquals(event.toString(), LadderStep.Stop, ReconnectLadder.step(event, 0, false, radioOn))
        }
    }

    @Test
    fun `a pressed connect on a card that cannot be dialled says why`() {
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

    // ------------------------------------------------------------------ the quiet restart

    @Test
    fun `a given-up ladder is restarted at one minute then every five`() {
        assertEquals(60_000L, ReconnectLadder.rearmDelayMs(0))
        assertEquals(300_000L, ReconnectLadder.rearmDelayMs(1))
        assertEquals(300_000L, ReconnectLadder.rearmDelayMs(2))
        assertEquals(300_000L, ReconnectLadder.rearmDelayMs(99))
        // A negative counter is the same question as a first dial, not a dial in the past.
        assertEquals(ReconnectLadder.REARM_FIRST_MS, ReconnectLadder.rearmDelayMs(-1))
        assertEquals(60_000L, ReconnectLadder.REARM_FIRST_MS)
        assertEquals(300_000L, ReconnectLadder.REARM_STEP_MS)
        assertTrue(
            "the first restart must come before the steady cadence, or a unit that rebooted " +
                "while the page was open waits five minutes to be believed",
            ReconnectLadder.REARM_FIRST_MS < ReconnectLadder.REARM_STEP_MS,
        )
    }

    @Test
    fun `a restart wants every guard clear`() {
        assertTrue(ReconnectLadder.rearmWanted(true, true, true, false, false))

        val cases = listOf(
            "screen off" to listOf(false, true, true, false, false),
            "card has no pairing" to listOf(true, false, true, false, false),
            "radio off" to listOf(true, true, false, false, false),
            "link already up" to listOf(true, true, true, true, false),
            "link being built" to listOf(true, true, true, true, false),
            "a dial is in flight" to listOf(true, true, true, false, true),
        )
        for ((what, args) in cases) {
            assertFalse(
                "$what must not start a quiet dial",
                ReconnectLadder.rearmWanted(args[0], args[1], args[2], args[3], args[4]),
            )
        }
    }

    @Test
    fun `the restart rule is not the ladder's rule`() {
        // Two separate decisions, and collapsing them is how the 2026-11-05 bench ended up
        // silent: `step` is still terminal, so nothing about the ladder got more patient —
        // the fourth drop still gives up, and it is the restart below that dials again.
        assertTrue(
            ReconnectLadder.step(LinkEvent.Dropped("gatt 133"), 3, paired, radioOn)
                is LadderStep.GiveUp,
        )
        // And a restart is not an excuse to ignore a blocked card: the same two facts the
        // ladder words with `blocked` are guards here, in Boolean form.
        assertFalse(ReconnectLadder.rearmWanted(true, false, true, false, false))
        assertFalse(ReconnectLadder.rearmWanted(true, true, false, false, false))
        assertNotNull(ReconnectLadder.blocked(false, radioOn))
    }
}
