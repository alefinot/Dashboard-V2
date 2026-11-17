package com.alefinot.dashboardpp.ui.map

import com.alefinot.dashboardpp.trips.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrow's direction is arithmetic over the positions the recorder already has, so every
 * case that decides how the arrow *looks* on the bench is testable here: a drive that has not
 * moved yet, a drive standing still while its receiver jitters, and a corner.
 *
 * Distances at the latitude these fixtures use (48° N): 0.001° of latitude ≈ 111 m, 0.001° of
 * longitude ≈ 75 m.
 */
class LiveHeadingTest {

    private val start = TrackPoint(48.0, 11.0)

    @Test
    fun `a bearing is degrees clockwise from north`() {
        assertEquals(0.0, LiveHeading.bearingDeg(start, TrackPoint(48.001, 11.0)), 0.01)
        assertEquals(90.0, LiveHeading.bearingDeg(start, TrackPoint(48.0, 11.001)), 0.01)
        assertEquals(180.0, LiveHeading.bearingDeg(start, TrackPoint(47.999, 11.0)), 0.01)
        assertEquals(270.0, LiveHeading.bearingDeg(start, TrackPoint(48.0, 10.999)), 0.01)
    }

    @Test
    fun `a drive across the antimeridian still points east`() {
        // The longitude delta is the one place a naive `atan2(lon2 - lon1)` flips sign.
        val east = LiveHeading.bearingDeg(TrackPoint(48.0, 179.999), TrackPoint(48.0, -179.999))
        assertEquals(90.0, east, 0.05)
    }

    @Test
    fun `no direction before there is a drive`() {
        assertNull(LiveHeading.of(emptyList()))
        assertNull(LiveHeading.of(listOf(start)))
        // Two fixes 2.2 m apart: under the baseline, so nothing is claimed about direction.
        assertNull(LiveHeading.of(listOf(start, TrackPoint(48.00002, 11.0))))
    }

    @Test
    fun `a vehicle that has not moved does not get a direction`() {
        // Twenty parked fixes drifting 0.2 m each: 4 m of wander in one line, which is more
        // than a real receiver's noise but stays inside the baseline on purpose - the whole
        // point of the baseline is that jitter this small never clears it and never steers.
        val parked = List(20) { TrackPoint(48.0 + it * 0.0000018, 11.0) }
        assertNull(LiveHeading.of(parked))
    }

    @Test
    fun `one jittery fix cannot steer the arrow`() {
        // Straight north for 11 m, then the newest fix wanders 1.5 m east. The bearing across
        // the last two fixes - the naive rule - is dead east; the bearing across the baseline
        // is still north, and that is what the owner sees.
        val track = listOf(
            TrackPoint(48.00000, 11.00000),
            TrackPoint(48.00010, 11.00000),
            TrackPoint(48.00010, 11.00002),
        )
        val heading = LiveHeading.of(track)!!
        assertTrue("expected roughly north, got $heading", heading < 20.0 || heading > 340.0)
    }

    @Test
    fun `a corner turns the arrow as soon as the drive has turned`() {
        // North for 24 m, then a hard right. The walk back stops at the newest fix six metres
        // behind, which is on the new leg, so the arrow reads the corner rather than an average
        // of the two legs it has already left.
        val track = listOf(
            TrackPoint(48.00000, 11.00000),
            TrackPoint(48.00020, 11.00000),
            TrackPoint(48.00022, 11.00000),
            TrackPoint(48.00022, 11.00010),
        )
        assertEquals(90.0, LiveHeading.of(track)!!, 0.5)
    }

    @Test
    fun `the turn between two headings goes the short way round`() {
        assertEquals(20f, LiveHeading.shortestArcDeg(350f, 10f), 1e-4f)
        assertEquals(-20f, LiveHeading.shortestArcDeg(10f, 350f), 1e-4f)
        assertEquals(90f, LiveHeading.shortestArcDeg(0f, 90f), 1e-4f)
        assertEquals(-90f, LiveHeading.shortestArcDeg(90f, 0f), 1e-4f)
        assertEquals(0f, LiveHeading.shortestArcDeg(123f, 123f), 1e-4f)
    }

    @Test
    fun `a heading is always expressed as nought to three-sixty`() {
        assertEquals(10f, LiveHeading.normalizeDeg(370f), 1e-4f)
        assertEquals(350f, LiveHeading.normalizeDeg(-10f), 1e-4f)
        assertEquals(0f, LiveHeading.normalizeDeg(0f), 1e-4f)
        assertEquals(270f, LiveHeading.normalizeDeg(-90f), 1e-4f)
    }
}
