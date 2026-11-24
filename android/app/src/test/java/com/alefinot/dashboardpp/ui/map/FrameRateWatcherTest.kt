package com.alefinot.dashboardpp.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a detail page may step its route down a rung.
 *
 * The rule is deliberately conservative — a page has to be slow for **two whole seconds in a row**
 * after a two-second warm-up — because the alternative is a route that loses detail to a tile
 * decode or a garbage collection. These tests drive the watcher with timestamps, so what is being
 * checked is the arithmetic and not a phone's mood: one slow second is forgiven, a sustained slow
 * page pays one rung per pair of slow seconds, and an opening page is never measured while it is
 * still opening.
 */
class FrameRateWatcherTest {

    /** Feed frames every [stepMs] from [fromMs] to [toMs]; return the times a step was asked for. */
    private fun run(
        watcher: FrameRateWatcher,
        fromMs: Long,
        toMs: Long,
        stepMs: Long,
    ): List<Long> {
        val steps = ArrayList<Long>()
        var t = fromMs
        while (t <= toMs) {
            if (watcher.onFrame(t * NANOS_PER_MS)) steps.add(t)
            t += stepMs
        }
        return steps
    }

    @Test
    fun aPageThatHoldsSixtyFramesPerSecondNeverSteps() {
        val watcher = FrameRateWatcher()

        val steps = run(watcher, fromMs = 0, toMs = 20_000, stepMs = 16)

        assertTrue("a 60 fps page asked for a coarser route: $steps", steps.isEmpty())
    }

    @Test
    fun theOpeningRampIsNotMeasured() {
        // A page whose first second and a half is a 10 fps slideshow — tiles decoding, the map
        // framing, the entrance animation — and then settles into 60 fps. That opening is not the
        // page's cost and must not cost it a rung.
        val watcher = FrameRateWatcher()

        val opening = run(watcher, fromMs = 0, toMs = 1_500, stepMs = 100)
        val settled = run(watcher, fromMs = 1_600, toMs = 20_000, stepMs = 16)

        assertTrue("the warm-up asked for a step: $opening", opening.isEmpty())
        assertTrue("a page that settled asked for a coarser route: $settled", settled.isEmpty())
    }

    @Test
    fun aSustainedTenFramesPerSecondStepsOncePerPairOfSlowSeconds() {
        val watcher = FrameRateWatcher()

        val steps = run(watcher, fromMs = 0, toMs = 12_000, stepMs = 100)

        // Two seconds of warm-up, then two slow seconds per step: 4 s, 6 s, 8 s. Not one rung per
        // slow second — the page already answered that second, and answering it twice would empty
        // the ladder on the strength of one stall.
        assertEquals(listOf(4_000L, 6_000L, 8_000L, 10_000L, 12_000L), steps)
    }

    @Test
    fun oneSlowSecondIsForgiven() {
        val watcher = FrameRateWatcher()

        val slow = run(watcher, fromMs = 0, toMs = 3_000, stepMs = 100)
        val recovered = run(watcher, fromMs = 3_100, toMs = 20_000, stepMs = 16)

        assertTrue("the first slow window already asked for a step: $slow", slow.isEmpty())
        assertTrue("one slow second cost the route a rung anyway: $recovered", recovered.isEmpty())
    }

    @Test
    fun aPageThatIsSlowAtExactlyTheFloorIsNotPunished() {
        // 20 fps is the expected floor, not a failure: the step is for pages *below* it.
        val watcher = FrameRateWatcher()

        val steps = run(watcher, fromMs = 0, toMs = 12_000, stepMs = 50)

        assertTrue("a page holding exactly 20 fps was stepped down: $steps", steps.isEmpty())
    }

    @Test
    fun theWindowIsRemeasuredAfterEachStep() {
        // A page that keeps crawling keeps stepping, but only on fresh evidence: the counter is
        // cleared when it pays, so the stall that caused a step cannot cause the next one too.
        val watcher = FrameRateWatcher()

        val steps = run(watcher, fromMs = 0, toMs = 12_000, stepMs = 100)

        for (i in 1 until steps.size) {
            assertEquals(
                "two steps ${steps[i] - steps[i - 1]} ms apart — one stall was charged twice",
                2_000L,
                steps[i] - steps[i - 1],
            )
        }
    }

    @Test
    fun aSingleLongFrameIsNotASlowSecond() {
        // A 400 ms garbage collection inside an otherwise 60 fps second leaves that second at
        // ~42 fps: unpleasant, not "too slow to read".
        val watcher = FrameRateWatcher()
        val steps = ArrayList<Long>()
        var t = 0L
        var stalled = false
        while (t <= 10_000) {
            if (watcher.onFrame(t * NANOS_PER_MS)) steps.add(t)
            if (!stalled && t >= 5_000L) {
                stalled = true
                t += 400
            } else {
                t += 16
            }
        }

        assertTrue("a single long frame cost the route a rung: $steps", steps.isEmpty())
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
