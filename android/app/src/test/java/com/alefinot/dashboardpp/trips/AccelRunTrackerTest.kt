package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror of the firmware's 0-50 timer (`updateAccelTimer()` in `src/sensors.cpp`).
 *
 * Every case is a frame of the state the unit reported, because that is all the app is ever
 * handed: it never sees the timer, only `tmr_st` / `tmr_s` once a second beside the position. So
 * what these tests fix is which transitions become a drawn run, and which ones the app is right to
 * refuse to draw.
 */
class AccelRunTrackerTest {

    private val tracker = AccelRunTracker()

    /** A frame at one degree of arc per fix north, so a run's shape is checkable by eye. */
    private fun frame(second: Int, tmrSt: Int, tmrS: Double, spd: Double) = snap(
        up = 100L + second,
        spd = spd,
        lat = 45.0 + second * 0.0001,
        lon = 9.0,
        fix = 1,
    ).copy(tmrSt = tmrSt, tmrS = tmrS)

    /** READY → RUNNING → RUNNING → FINISHED: a launch the app watched all the way. */
    @Test
    fun aRunHeardFromTheStartKeepsEveryFixItWasMadeOf() {
        assertFalse(tracker.onFrame(frame(0, tmrSt = 0, tmrS = 0.0, spd = 0.4), 1_000))
        assertFalse(tracker.onFrame(frame(1, tmrSt = 1, tmrS = 0.9, spd = 18.0), 2_000))
        assertFalse(tracker.onFrame(frame(2, tmrSt = 1, tmrS = 1.9, spd = 31.0), 3_000))
        assertFalse(tracker.onFrame(frame(3, tmrSt = 1, tmrS = 2.9, spd = 44.0), 4_000))
        assertTrue(tracker.onFrame(frame(4, tmrSt = 2, tmrS = 3.64, spd = 51.2), 5_000))

        val runs = tracker.snapshot()
        assertEquals(1, runs.size)
        val run = runs[0]
        // The unit's number, not arithmetic over two fixes ~1 s apart.
        assertEquals(3.64, run.seconds, 0.0)
        // The first RUNNING frame is the run's start; the READY frame before it is not part of it.
        assertEquals(2_000L, run.startedAtMs)
        assertEquals(51.2, run.finishSpeedKmh, 0.0)
        // Fixes 1 through 4 - the READY frame is not part of the run, and the finish frame is.
        assertEquals(4, run.points.size)
        assertEquals(45.0 + 0.0001, run.points.first().lat, 1e-9)
        assertEquals(45.0 + 0.0004, run.points.last().lat, 1e-9)
    }

    /** The next launch after a FINISHED opens a second run; the first is still on the map. */
    @Test
    fun aSecondLaunchIsASecondRun() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 1.0, spd = 20.0), 1_000)
        tracker.onFrame(frame(1, tmrSt = 2, tmrS = 2.0, spd = 55.0), 2_000)
        tracker.onFrame(frame(2, tmrSt = 2, tmrS = 2.0, spd = 55.0), 3_000)
        tracker.onFrame(frame(3, tmrSt = 0, tmrS = 0.0, spd = 4.0), 4_000)
        tracker.onFrame(frame(4, tmrSt = 1, tmrS = 0.7, spd = 15.0), 5_000)
        assertTrue(tracker.onFrame(frame(5, tmrSt = 2, tmrS = 5.5, spd = 50.4), 6_000))

        val runs = tracker.snapshot()
        assertEquals(2, runs.size)
        assertEquals(2.0, runs[0].seconds, 0.0)
        assertEquals(5.5, runs[1].seconds, 0.0)
        assertEquals(5_000L, runs[1].startedAtMs)
        assertEquals(1L, runs[0].id)
        assertEquals(2L, runs[1].id)
    }

    /** A run that reached the target at the cap is still a run: the speed answers, not the clock. */
    @Test
    fun aFinishAtTheCapButAtTheTargetSpeedIsKept() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 5.0, spd = 30.0), 1_000)
        assertTrue(tracker.onFrame(frame(9, tmrSt = 2, tmrS = 9.99, spd = 52.0), 2_000))
        assertEquals(1, tracker.snapshot().size)
    }

    /** The clock running out under the target is not a 0-50, and there is no line to draw for it. */
    @Test
    fun aRunThatExpiredAtTheCapWithoutTheTargetIsDropped() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 5.0, spd = 22.0), 1_000)
        assertFalse(tracker.onFrame(frame(9, tmrSt = 2, tmrS = 9.99, spd = 41.0), 2_000))
        assertTrue(tracker.snapshot().isEmpty())
    }

    /** A short time under the target keeps its run: the unit voted FINISHED for a reason. */
    @Test
    fun aShortFinishUnderTheTargetIsStillTheUnitsResult() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 1.0, spd = 20.0), 1_000)
        assertTrue(tracker.onFrame(frame(1, tmrSt = 2, tmrS = 2.1, spd = 47.0), 2_000))
        val runs = tracker.snapshot()
        assertEquals(1, runs.size)
        assertEquals(2.1, runs[0].seconds, 0.0)
    }

    /** RUNNING → READY is an aborted launch: no result, and the half-open path goes with it. */
    @Test
    fun anAbortedLaunchLeavesNothingToDraw() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 0.8, spd = 12.0), 1_000)
        tracker.onFrame(frame(1, tmrSt = 1, tmrS = 1.8, spd = 9.0), 2_000)
        assertFalse(tracker.onFrame(frame(2, tmrSt = 0, tmrS = 0.0, spd = 0.4), 3_000))
        assertTrue(tracker.snapshot().isEmpty())
    }

    /**
     * RUNNING → READY → RUNNING opens a fresh run at the new frame: the aborted fixes do not
     * become the next run's start, which would draw the line back onto ground the launch never used.
     */
    @Test
    fun aRelaunchAfterAnAbortStartsWhereTheNewLaunchDid() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 0.8, spd = 12.0), 1_000)
        tracker.onFrame(frame(1, tmrSt = 0, tmrS = 0.0, spd = 0.3), 2_000)
        tracker.onFrame(frame(2, tmrSt = 1, tmrS = 0.6, spd = 14.0), 3_000)
        assertTrue(tracker.onFrame(frame(3, tmrSt = 2, tmrS = 4.2, spd = 53.0), 4_000))

        val run = tracker.snapshot().single()
        assertEquals(3_000L, run.startedAtMs)
        assertEquals(listOf(45.0 + 0.0002, 45.0 + 0.0003), run.points.map { it.lat })
    }

    /**
     * A first frame that is already RUNNING is a launch the app arrived in the middle of: the run
     * keeps the part that was seen and the unit's whole time, because the time was never the app's
     * to measure.
     */
    @Test
    fun joiningARunMidFlightDrawsThePartThatWasSeen() {
        assertFalse(tracker.onFrame(frame(3, tmrSt = 1, tmrS = 3.1, spd = 40.0), 4_000))
        assertTrue(tracker.onFrame(frame(4, tmrSt = 2, tmrS = 4.0, spd = 51.0), 5_000))

        val run = tracker.snapshot().single()
        assertEquals(4.0, run.seconds, 0.0)
        assertEquals(2, run.points.size)
        assertEquals(4_000L, run.startedAtMs)
    }

    /**
     * A run already FINISHED when the app looked is a run it saw no part of: no geometry, so
     * nothing to tap, and a line over ground it cannot name would be a guess.
     */
    @Test
    fun aResultThatWasAlreadyOnTheScreenPublishesNothing() {
        assertFalse(tracker.onFrame(frame(0, tmrSt = 2, tmrS = 7.31, spd = 55.0), 1_000))
        assertFalse(tracker.onFrame(frame(1, tmrSt = 2, tmrS = 7.31, spd = 54.0), 2_000))
        assertFalse(tracker.onFrame(frame(2, tmrSt = 2, tmrS = 7.31, spd = 30.0), 3_000))
        assertTrue(tracker.snapshot().isEmpty())
    }

    /**
     * A FINISHED frame the app never saw a RUNNING one for, arriving after an aborted launch, is
     * still not a run: only the frame that *reaches* FINISHED closes one.
     */
    @Test
    fun aFinishedFrameWithoutAnOpenRunIsNotAResult() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 0.5, spd = 10.0), 1_000)
        tracker.onFrame(frame(1, tmrSt = 0, tmrS = 0.0, spd = 0.2), 2_000)
        assertFalse(tracker.onFrame(frame(2, tmrSt = 2, tmrS = 3.0, spd = 60.0), 3_000))
        assertTrue(tracker.snapshot().isEmpty())
    }

    /** A run with no fix in it at all has no line: it is not published as a place-less stripe. */
    @Test
    fun aRunWithNoPositionIsNotPublished() {
        val noFix = snap(up = 100, spd = 30.0, lat = 0.0, lon = 0.0, fix = 0)
            .copy(tmrSt = 1, tmrS = 1.0)
        assertFalse(tracker.onFrame(noFix, 1_000))
        val finish = snap(up = 101, spd = 52.0, lat = 0.0, lon = 0.0, fix = 0)
            .copy(tmrSt = 2, tmrS = 4.0)
        assertFalse(tracker.onFrame(finish, 2_000))
        assertTrue(tracker.snapshot().isEmpty())
    }

    /**
     * The identity contract the map's redraw filter rests on: a run in progress changes nothing,
     * and the list object only ever changes when a run is added or dropped.
     */
    @Test
    fun thePublishedListHoldsItsIdentityThroughARun() {
        val before = tracker.snapshot()
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 0.9, spd = 18.0), 1_000)
        tracker.onFrame(frame(1, tmrSt = 1, tmrS = 1.9, spd = 33.0), 2_000)
        assertSame("a run in progress must not hand out a new list", before, tracker.snapshot())

        tracker.onFrame(frame(2, tmrSt = 2, tmrS = 2.9, spd = 51.0), 3_000)
        val after = tracker.snapshot()
        assertNotSame(before, after)
        tracker.onFrame(frame(3, tmrSt = 2, tmrS = 2.9, spd = 50.0), 4_000)
        assertSame(after, tracker.snapshot())
    }

    /** Beyond the cap the oldest launch falls off: twenty lines is the picture, twenty-one is a tangle. */
    @Test
    fun theOldestRunLeavesWhenTheCapIsReached() {
        val small = AccelRunTracker(maxRuns = 2)
        for (launch in 0..2) {
            small.onFrame(frame(launch * 2, tmrSt = 1, tmrS = 1.0, spd = 20.0), 1_000L * launch)
            small.onFrame(
                frame(launch * 2 + 1, tmrSt = 2, tmrS = 3.0 + launch, spd = 55.0),
                1_000L * launch,
            )
        }
        val runs = small.snapshot()
        assertEquals(2, runs.size)
        assertEquals(4.0, runs[0].seconds, 0.0)
        assertEquals(5.0, runs[1].seconds, 0.0)
    }

    /** A drive ending takes its runs with it: no map wears a launch of a drive it is not drawing. */
    @Test
    fun clearTakesTheRunsAndTheRunInProgress() {
        tracker.onFrame(frame(0, tmrSt = 1, tmrS = 1.0, spd = 20.0), 1_000)
        tracker.onFrame(frame(1, tmrSt = 2, tmrS = 2.0, spd = 55.0), 2_000)
        assertEquals(1, tracker.snapshot().size)

        tracker.clear()
        assertTrue(tracker.snapshot().isEmpty())
        // The abandoned run's fixes must not become the next drive's first points either.
        assertFalse(tracker.onFrame(frame(2, tmrSt = 2, tmrS = 2.0, spd = 55.0), 3_000))
        assertTrue(tracker.snapshot().isEmpty())
    }

    /** A fix that arrived during the launch is still drawn: the finish frame's position is the target speed's place. */
    @Test
    fun aLaunchThatStartedOutsideAFixStillEndsOnTheMap() {
        val noFix = snap(up = 100, spd = 12.0, lat = 0.0, lon = 0.0, fix = 0)
            .copy(tmrSt = 1, tmrS = 1.0)
        tracker.onFrame(noFix, 1_000)
        tracker.onFrame(frame(2, tmrSt = 1, tmrS = 2.0, spd = 30.0), 2_000)
        assertTrue(tracker.onFrame(frame(3, tmrSt = 2, tmrS = 3.0, spd = 51.0), 3_000))

        val run = tracker.snapshot().single()
        assertEquals(listOf(45.0 + 0.0002, 45.0 + 0.0003), run.points.map { it.lat })
    }
}
