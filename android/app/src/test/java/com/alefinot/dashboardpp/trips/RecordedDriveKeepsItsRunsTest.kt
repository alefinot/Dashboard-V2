package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runs a sealed drive still contains, read back out of the rows it was written from.
 *
 * A recorded file carries `tmr_st` / `tmr_s` beside its position every second — the unit reports its
 * acceleration timer in the same frame as its fix — so a drive recorded before the app drew any
 * yellow lines still holds every launch it had. These tests fix what the replay gets back, and what
 * it is right not to invent.
 */
class RecordedDriveKeepsItsRunsTest {

    /**
     * One row of a recorded drive, driving north at one arc per fix so a run's shape is checkable.
     *
     * A row written without a fix carries `0,0`, because that is what a unit without a fix reports
     * (trips/LiveRoute.kt) — the fixture keeps that, so a no-fix row here declines a position the
     * same way the file does instead of smuggling in a coordinate the recorder could not have written.
     */
    private fun row(second: Int, tmrSt: Int, tmrS: Double, spd: Double, fix: Int = 1) = TripSample(
        tsMs = 10_000L + second * 1_000L,
        snapshot = snap(
            up = 500L + second,
            spd = spd,
            lat = if (fix == 0) 0.0 else 45.0 + second * 0.0001,
            lon = if (fix == 0) 0.0 else 9.0,
            fix = fix,
        ).copy(tmrSt = tmrSt, tmrS = tmrS),
    )

    /** Two launches in one drive: both come back, oldest first, with the unit's own numbers. */
    @Test
    fun aDriveWithTwoLaunchesComesBackWithBoth() {
        val rows = listOf(
            row(0, tmrSt = 0, tmrS = 0.0, spd = 0.4),
            row(1, tmrSt = 1, tmrS = 0.9, spd = 18.0),
            row(2, tmrSt = 1, tmrS = 1.9, spd = 31.0),
            row(3, tmrSt = 2, tmrS = 3.64, spd = 51.2),
            // The finished run holds its number on every later frame until the car stops again, and
            // a logged drive is full of these repeats: only the transition counts.
            row(4, tmrSt = 2, tmrS = 3.64, spd = 48.0),
            row(5, tmrSt = 0, tmrS = 0.0, spd = 3.0),
            row(6, tmrSt = 1, tmrS = 0.7, spd = 16.0),
            row(7, tmrSt = 2, tmrS = 5.50, spd = 50.4),
        )

        val runs = runsFrom(rows)
        assertEquals(2, runs.size)
        assertEquals(3.64, runs[0].seconds, 0.0)
        assertEquals(5.50, runs[1].seconds, 0.0)
        // The row's own clock, which is the phone's receive time — the same stamp the card printed
        // while the drive was live.
        assertEquals(11_000L, runs[0].startedAtMs)
        assertEquals(16_000L, runs[1].startedAtMs)
        assertEquals(listOf(45.0 + 0.0001, 45.0 + 0.0002, 45.0 + 0.0003), runs[0].points.map { it.lat })
    }

    /** A file that begins while the timer was already running is a launch seen from partway. */
    @Test
    fun aFileThatStartsMidLaunchKeepsThePartItHolds() {
        val rows = listOf(
            row(3, tmrSt = 1, tmrS = 3.1, spd = 40.0),
            row(4, tmrSt = 2, tmrS = 4.0, spd = 51.0),
        )

        val run = runsFrom(rows).single()
        assertEquals(4.0, run.seconds, 0.0)
        assertEquals(2, run.points.size)
        assertEquals(13_000L, run.startedAtMs)
    }

    /** The clock running out under the target is not a 0-50, in a file or on the live map. */
    @Test
    fun anExpiredLaunchIsStillNotAResultWhenItIsReadBack() {
        val rows = listOf(
            row(0, tmrSt = 1, tmrS = 5.0, spd = 22.0),
            row(1, tmrSt = 2, tmrS = 9.99, spd = 41.0),
        )
        assertTrue(runsFrom(rows).isEmpty())
    }

    /** A drive with no launches, and a drive with no rows, both come back with nothing to draw. */
    @Test
    fun aQuietDriveHasNoRuns() {
        assertTrue(runsFrom(emptyList()).isEmpty())
        val rows = (0..9).map { row(it, tmrSt = 0, tmrS = 0.0, spd = 40.0) }
        assertTrue(runsFrom(rows).isEmpty())
    }

    /**
     * The recorder pauses when the link stalls, so a stretch of a launch can be missing from the
     * file. The run keeps the rows that were written — a line over ground the file never saw would
     * be a guess, and the drive's own route on this page joins across the same hole.
     */
    @Test
    fun aPausedRecorderTakesNothingThatWasNotWritten() {
        val rows = listOf(
            row(0, tmrSt = 1, tmrS = 0.9, spd = 15.0),
            row(1, tmrSt = 1, tmrS = 1.9, spd = 28.0),
            // 2..5 are not in the file: the link was down.
            row(6, tmrSt = 1, tmrS = 6.9, spd = 47.0),
            row(7, tmrSt = 2, tmrS = 7.8, spd = 52.0),
        )

        val run = runsFrom(rows).single()
        assertEquals(7.8, run.seconds, 0.0)
        assertEquals(listOf(45.0 + 0.0000, 45.0 + 0.0001, 45.0 + 0.0006, 45.0 + 0.0007), run.points.map { it.lat })
    }

    /** A row that had no fix gives the run no point, exactly as it gives the drive none. */
    @Test
    fun aRowWithoutAFixAddsNoPointToTheRun() {
        val rows = listOf(
            row(0, tmrSt = 1, tmrS = 0.9, spd = 15.0, fix = 0),
            row(1, tmrSt = 1, tmrS = 1.9, spd = 30.0),
            row(2, tmrSt = 2, tmrS = 2.8, spd = 51.0, fix = 0),
        )

        val run = runsFrom(rows).single()
        assertEquals(listOf(45.0 + 0.0001), run.points.map { it.lat })
    }

    /**
     * A sealed drive keeps its **ten quickest** launches, not all thirty: the detail page is read
     * for what the drive achieved, and the map has room for ten yellow lines before they turn into
     * a tangle (maintainer, 2026-11-24: *only the best 10 runs of the accel timer should be kept,
     * no need for more*).
     */
    @Test
    fun anArchiveKeepsItsTenQuickestLaunches() {
        val rows = mutableListOf<TripSample>()
        for (launch in 0 until 30) {
            rows.add(row(launch * 2, tmrSt = 1, tmrS = 1.0, spd = 20.0))
            rows.add(row(launch * 2 + 1, tmrSt = 2, tmrS = 3.0 + launch, spd = 55.0))
        }

        val runs = runsFrom(rows)
        assertEquals(10, runs.size)
        // 3.0 s through 12.0 s: the ten quickest, still in the order the drive made them.
        assertEquals((3..12).map { it.toDouble() }, runs.map { it.seconds })
        // The same rule at any size the caller asks for.
        assertEquals(
            listOf(3.0, 4.0, 5.0, 6.0),
            runsFrom(rows, maxRuns = 4).map { it.seconds },
        )
    }

    /**
     * The property the whole approach rests on: a file replayed is the same list of runs the live
     * map drew while the drive happened, because it is the same code over the same rows. If this
     * ever fails, the two maps have started disagreeing about what a run is.
     */
    @Test
    fun theArchiveAndTheLiveMapAgreeOnTheSameDrive() {
        val rows = listOf(
            row(0, tmrSt = 1, tmrS = 0.9, spd = 18.0),
            row(1, tmrSt = 1, tmrS = 1.9, spd = 31.0),
            row(2, tmrSt = 2, tmrS = 2.9, spd = 51.0),
            row(3, tmrSt = 2, tmrS = 2.9, spd = 44.0),
            row(4, tmrSt = 0, tmrS = 0.0, spd = 2.0),
            row(5, tmrSt = 1, tmrS = 0.6, spd = 14.0),
            row(6, tmrSt = 2, tmrS = 4.2, spd = 53.0),
        )

        val live = AccelRunTracker()
        for (sample in rows) live.onFrame(sample.snapshot, sample.tsMs)

        val archived = runsFrom(rows)
        val fromLive = live.snapshot()
        assertEquals(fromLive.size, archived.size)
        for (i in fromLive.indices) {
            assertEquals(fromLive[i].seconds, archived[i].seconds, 0.0)
            assertEquals(fromLive[i].startedAtMs, archived[i].startedAtMs)
            assertEquals(fromLive[i].points, archived[i].points)
            assertEquals(fromLive[i].finishSpeedKmh, archived[i].finishSpeedKmh, 0.0)
        }
    }
}
