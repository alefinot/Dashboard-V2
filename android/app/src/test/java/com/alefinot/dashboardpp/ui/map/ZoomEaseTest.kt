package com.alefinot.dashboardpp.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The shape a zoom step travels through.
 *
 * The maintainer asked, twice, of the same control: *"make the zoom smooth"* (2026-11-19, and the
 * build shipped a one-sided ease-out), then *"add an easy ease effect to the zoom animation"*.
 * The second request is a statement about the first curve: an ease-out launches at full speed on
 * the frame the button goes down, which reads as a shove. An ease-in-out has a wind-up — the
 * level is slow where it starts, fastest in the middle, and caught where it lands.
 *
 * That is a property of one function, so it is measured on the function rather than eyeballed on
 * a moving map. The properties worth keeping are these: the two ends are exact (a step that lands
 * a hundredth of a level short is a step that never finishes), the halves mirror each other, the
 * curve never overshoots — a zoom level past the requested one is a bounce, and the level the
 * next press re-aims from would be a lie — and the ends really are slow.
 *
 * What this file does NOT assert is duration. `ZOOM_RAMP_MS` is a taste call pinned by
 * `LivePageReadoutsTest` where the ramp is built; a shape is not a length.
 */
class ZoomEaseTest {

    @Test
    fun `the step starts and lands exactly where it is told`() {
        assertTrue("a step that does not start at 0 shifts the level the instant it is asked",
            abs(easeEase(0.0)) < 1e-9)
        assertTrue("a step that does not land on 1 leaves the level short and the next press " +
                "re-aims from somewhere that is not the target",
            abs(easeEase(1.0) - 1.0) < 1e-9)
        assertTrue("the middle of the step is the middle of the level",
            abs(easeEase(0.5) - 0.5) < 1e-9)
    }

    @Test
    fun `the two halves of the travel mirror each other`() {
        // Ease-IN-out, or it is not ease-in-out: whatever the curve has taken by a tenth it gives
        // back in the last tenth.
        for (i in 0..20) {
            val t = i / 20.0
            val d = abs(easeEase(t) - (1.0 - easeEase(1.0 - t)))
            assertTrue("t=$t is $d off its mirror image", d < 1e-9)
        }
    }

    @Test
    fun `the level climbs and never overshoots`() {
        var previous = -1.0
        for (i in 0..200) {
            val t = i / 200.0
            val k = easeEase(t)
            assertTrue("t=$t leaves the 0..1 box ($k): a level past the target is a bounce, and " +
                    "a press during a travel re-aims from the target, so a bounce is a wrong " +
                    "number handed to the next step", k in 0.0..1.0)
            assertTrue("t=$t goes backwards ($previous -> $k)", k >= previous)
            previous = k
        }
    }

    /**
     * The two ends are slow and the middle is fast — the whole difference between the curve that
     * shipped in 2.1.0/183 and the one asked for on 2026-11-19. An ease-out cubic takes 0.578 of
     * the step in its first quarter; this one may not.
     */
    @Test
    fun `the step winds up instead of launching`() {
        val firstTenth = easeEase(0.1)
        val lastTenth = 1.0 - easeEase(0.9)
        val middleTenth = easeEase(0.6) - easeEase(0.5)

        assertTrue("the first tenth covers $firstTenth of the step: an ease-out launches at full " +
            "speed and that is the shove the maintainer rejected", firstTenth < 0.02)
        assertTrue("the ends are not symmetric: $firstTenth in, $lastTenth out",
            abs(firstTenth - lastTenth) < 1e-9)
        assertTrue("the first tenth ($firstTenth) is not slower than the middle ($middleTenth)",
            firstTenth < middleTenth / 10.0)
        assertTrue("the middle tenth covers only $middleTenth: a curve that is slow everywhere is " +
            "a longer ramp, not an ease-in-out", middleTenth > 0.2)

        // Against the curve it replaced, at the point where they differ most.
        val easeOutQuarter = 1.0 - Math.pow(0.75, 3.0)
        assertTrue("the first quarter covers ${easeEase(0.25)} — that is the old ease-out " +
            "($easeOutQuarter) still in the build", easeEase(0.25) < easeOutQuarter / 4.0)
    }
}
