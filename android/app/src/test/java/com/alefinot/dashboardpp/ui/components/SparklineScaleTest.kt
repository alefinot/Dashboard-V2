package com.alefinot.dashboardpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vertical scale a sparkline paints into.
 *
 * This is the one rule a chart can get wrong without anyone being able to say which number is
 * missing: the trace is drawn either way, so the page still looks alive. The bench found it on a
 * trip driven at a steady altitude — the ALTITUDE card drew what looked like an empty box,
 * because a series with no span has its line parked on the bottom edge of the box by a scale of
 * one. Two figures of the same height, one flat and one absent, have to look different.
 */
class SparklineScaleTest {

    @Test
    fun anAutoscaledSeriesIsPaintedOverItsOwnEnds() {
        val scale = sparkScale(listOf(3.0, 9.0, 5.0), domain = null)
        assertEquals(3.0, scale.start, 1e-9)
        assertEquals(9.0, scale.endInclusive, 1e-9)
    }

    @Test
    fun aSeriesThatDoesNotMoveIsCentredRatherThanDroppedOnTheFloor() {
        val scale = sparkScale(List(40) { 150.0 }, domain = null)
        assertTrue("the scale must straddle the value, or its line sits on one edge", scale.start < 150.0)
        assertTrue("the scale must straddle the value, or its line sits on one edge", scale.endInclusive > 150.0)
        // Symmetry is the whole point: 150 has to land in the middle of the box, not at 4 % or
        // 96 % of its height.
        val span = scale.endInclusive - scale.start
        val fraction = (150.0 - scale.start) / span
        assertEquals(0.5, fraction, 1e-9)
    }

    @Test
    fun aPinnedDomainIsUsedExactlyAsGiven() {
        // The Live page pins 10-16 V so that a 0.3 V sag stays small next to the 11.5 V rule.
        // Autoscaling it back would undo the reason for passing one.
        val scale = sparkScale(listOf(12.4, 12.7), domain = 10.0..16.0)
        assertEquals(10.0, scale.start, 1e-9)
        assertEquals(16.0, scale.endInclusive, 1e-9)
    }

    @Test
    fun aPinnedDomainNeverCollapsesToAZeroSpan() {
        // A caller that pins a degenerate band would otherwise divide by it. Sparkline keeps its
        // own unit-span guard; the scale function must not hand it a zero to guard.
        val scale = sparkScale(listOf(4.0, 4.0), domain = 2.0..2.0)
        assertEquals(2.0, scale.start, 1e-9)
        assertEquals(2.0, scale.endInclusive, 1e-9)
    }

    @Test
    fun aMarkIsOnlyMeaningfulInsideThePaintedScale() {
        // Sparkline draws the rule when it falls inside the scale and declines when it does not.
        // These two are the two halves of that decision, expressed against the same function that
        // decides it, so a scale that changed shape cannot silently move the rule.
        val battery = sparkScale(List(30) { 13.4 }, domain = 10.0..16.0)
        assertTrue("the 11.5 V floor has to be drawable on the battery trace", 11.5 in battery)
        val coolant = sparkScale(listOf(80.0, 82.0), domain = 0.0..130.0)
        assertTrue("the 105 °C ceiling has to be drawable on the coolant trace", 105.0 in coolant)
        assertFalse(
            "a rule outside the painted scale would be clipped to an edge and read as \"at the " +
                "limit\", which is the opposite of the truth",
            200.0 in coolant,
        )
    }
}
