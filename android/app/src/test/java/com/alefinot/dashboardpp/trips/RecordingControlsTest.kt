package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the trip controls offer, from the four facts the two pages read.
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
        // Four booleans, sixteen combinations, five answers. Every combination resolves to one
        // of them and only PAUSED/RECORDING/WAITING/START/UNAVAILABLE exist - a sixth state
        // added without a control would be a screen with nothing to draw.
        val seen = mutableSetOf<RecordingControlState>()
        for (mask in 0 until 16) {
            val recording = mask and 1 != 0
            val paused = mask and 2 != 0
            val polling = mask and 4 != 0
            val canRecord = mask and 8 != 0
            seen += RecordingControlState.of(recording, paused, polling, canRecord)
        }
        assertEquals(RecordingControlState.entries.toSet(), seen)
    }
}
