package com.alefinot.dashboardpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two meanings of a press on the recording control, at the boundaries.
 *
 * The gesture is three seconds of red on one side and a split-second tap on the other, and the
 * only thing standing between "pause my drive" and "stop my drive" is this arithmetic - so it
 * is pinned without a screen, where the millisecond matters and the animation cannot hide it.
 */
class HoldToActTest {

    @Test
    fun `the ui promises three seconds and a 450 ms tap`() {
        assertEquals(3000L, HoldRules.HOLD_MS)
        assertEquals(450L, HoldRules.TAP_MS)
    }

    @Test
    fun `a quick press is a tap`() {
        assertEquals(HoldRules.Result.TAP, HoldRules.result(0, cancelled = false))
        assertEquals(HoldRules.Result.TAP, HoldRules.result(200, cancelled = false))
        assertEquals(HoldRules.Result.TAP, HoldRules.result(449, cancelled = false))
    }

    @Test
    fun `one millisecond past the tap is no longer a tap`() {
        assertEquals(HoldRules.Result.NOTHING, HoldRules.result(450, cancelled = false))
    }

    @Test
    fun `letting go before the window does nothing at all`() {
        // The important one: a thumb that rested on the pill for a second and a half must not
        // pause a recording on its way out. Half a gesture is not half an action.
        assertEquals(HoldRules.Result.NOTHING, HoldRules.result(1000, cancelled = false))
        assertEquals(HoldRules.Result.NOTHING, HoldRules.result(2999, cancelled = false))
    }

    @Test
    fun `the full window stops`() {
        assertEquals(HoldRules.Result.STOP, HoldRules.result(3000, cancelled = false))
        // Held past the fill: the stop already fired, and staying down changes nothing.
        assertEquals(HoldRules.Result.STOP, HoldRules.result(9000, cancelled = false))
    }

    @Test
    fun `a cancelled press is never a stop`() {
        // Scrolling the page starts as a press and ends as a drag; the gesture has to be able
        // to say "this was never about you" no matter how long the finger was down.
        assertEquals(HoldRules.Result.NOTHING, HoldRules.result(120, cancelled = true))
        assertEquals(HoldRules.Result.NOTHING, HoldRules.result(10_000, cancelled = true))
    }
}
