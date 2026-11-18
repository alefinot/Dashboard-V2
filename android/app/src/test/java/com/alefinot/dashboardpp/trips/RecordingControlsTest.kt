package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the trip controls offer, from the five facts the two pages read.
 *
 * The rule is shared rather than re-derived per screen because the Trips page and the Live page
 * sit one swipe apart and must never disagree: a PAUSE offered on one and missing on the other
 * is a drive lost to a swipe. The precedence is the subject here, not the enum.
 */
class RecordingControlsTest {

    @Test
    fun `a live service is never unavailable`() {
        // The window between START and the first sample: no file yet, but STOP is exactly what
        // backs out of it. Reporting UNAVAILABLE there would leave the owner with no way out.
        assertEquals(
            RecordingControlState.WAITING,
            RecordingControlState.of(recording = false, paused = false, polling = true, canRecord = false),
        )
    }

    @Test
    fun `a lane kept alive for the map is idle, not waiting`() {
        // 2.0.7/179: after a stop the frame lane goes on reading the unit so the live map keeps
        // moving while the app is open. That is the same polling flag, and read as WAITING its
        // one press was STOP - which the app owning that lane puts straight back down, leaving
        // a pill that answers nothing, ever (bench, 2026-11-18). What is true here is "nothing
        // is recording and a link is ready", so the press means START.
        val watching = RecordingControlState.of(
            recording = false, paused = false, polling = true, canRecord = true, watching = true,
        )
        assertEquals(RecordingControlState.START, watching)
        assertEquals(RecordingTap.START, watching.tap())
        assertEquals(false, watching.canHold())
    }

    @Test
    fun `the display-only lane cannot invent a drive`() {
        // `watching` offers START only when a drive is possible: with no link to record from it
        // stays what it is, and the pages say why instead of answering the press with a service
        // that has nowhere to write.
        assertEquals(
            RecordingControlState.UNAVAILABLE,
            RecordingControlState.of(
                recording = false, paused = false, polling = true, canRecord = false, watching = true,
            ),
        )
    }

    @Test
    fun `a real drive still outranks a watching lane`() {
        assertEquals(
            RecordingControlState.RECORDING,
            RecordingControlState.of(
                recording = true, paused = false, polling = true, canRecord = true, watching = true,
            ),
        )
        assertEquals(
            RecordingControlState.PAUSED,
            RecordingControlState.of(
                recording = false, paused = true, polling = true, canRecord = true, watching = true,
            ),
        )
    }

    @Test
    fun `idle with a link offers start`() {
        assertEquals(
            RecordingControlState.START,
            RecordingControlState.of(recording = false, paused = false, polling = false, canRecord = true),
        )
    }

    @Test
    fun `a running drive outranks the link`() {
        // The unit went out of range with a file open: PAUSE and STOP must stay reachable, or
        // the one moment the control is needed is the moment it disappears.
        assertEquals(
            RecordingControlState.RECORDING,
            RecordingControlState.of(recording = true, paused = false, polling = false, canRecord = false),
        )
        assertEquals(
            RecordingControlState.PAUSED,
            RecordingControlState.of(recording = false, paused = true, polling = false, canRecord = false),
        )
    }

    @Test
    fun `paused outranks recording and polling`() {
        // The service keeps polling while paused on purpose (the silence seal needs a live
        // baseline), so `paused` has to be read before `polling` and before `recording`.
        assertEquals(
            RecordingControlState.PAUSED,
            RecordingControlState.of(recording = true, paused = true, polling = true, canRecord = true),
        )
    }

    @Test
    fun `no link and no drive is unavailable`() {
        assertEquals(
            RecordingControlState.UNAVAILABLE,
            RecordingControlState.of(recording = false, paused = false, polling = false, canRecord = false),
        )
    }

    @Test
    fun `the matrix is exactly these five states`() {
        // Five booleans, thirty-two combinations, five answers. Every combination resolves to one
        // of them and only PAUSED/RECORDING/WAITING/START/UNAVAILABLE exist - a sixth state
        // added without a control would be a screen with nothing to draw.
        val seen = mutableSetOf<RecordingControlState>()
        for (mask in 0 until 32) {
            val recording = mask and 1 != 0
            val paused = mask and 2 != 0
            val polling = mask and 4 != 0
            val canRecord = mask and 8 != 0
            val watching = mask and 16 != 0
            seen += RecordingControlState.of(recording, paused, polling, canRecord, watching)
        }
        assertEquals(RecordingControlState.entries.toSet(), seen)
    }

    @Test
    fun `one press means what the state says`() {
        // The pill and the card are the controls now, so this is the whole gesture grammar:
        // idle starts, a live drive pauses, a paused drive resumes, the window before the
        // first sample backs out, and nothing is a state where the press is not offered.
        assertEquals(RecordingTap.START, RecordingControlState.START.tap())
        assertEquals(RecordingTap.PAUSE, RecordingControlState.RECORDING.tap())
        assertEquals(RecordingTap.RESUME, RecordingControlState.PAUSED.tap())
        assertEquals(RecordingTap.STOP, RecordingControlState.WAITING.tap())
        assertEquals(RecordingTap.NONE, RecordingControlState.UNAVAILABLE.tap())
    }

    @Test
    fun `only a drive that exists can be stopped by holding`() {
        // A three-second red fill that ends in "there was nothing to stop" is a control that
        // lied about its own state; START and the empty states get a plain press.
        assertEquals(
            setOf(RecordingControlState.RECORDING, RecordingControlState.PAUSED),
            RecordingControlState.entries.filter { it.canHold() }.toSet(),
        )
    }
}
