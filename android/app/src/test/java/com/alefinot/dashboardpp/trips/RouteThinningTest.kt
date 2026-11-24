package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line a sealed drive is *drawn* with, as opposed to the one that was recorded.
 *
 * osmdroid rebuilds a polyline's projected path on every draw, so a 43 000-position file is a
 * 43 000-segment paint on every pan and pinch of the detail page. The page therefore draws a
 * thinned copy of the same line, at a bounded budget. These tests fix the three things that
 * matters: the shape survives (corners stay, ends stay), the ceiling holds, and the coarser rungs
 * are the same drive rather than a worse one.
 */
class RouteThinningTest {

    /** One metre of latitude, in degrees, near enough for a shape test at 45°N. */
    private fun north(metres: Double): Double = metres / 111_320.0

    @Test
    fun aStraightLineKeepsOnlyItsEnds() {
        val points = (0..500).map { TrackPoint(45.0 + north(it.toDouble()), 9.0) }

        val thinned = RouteThinning.thin(points)

        // Every middle position sits on the line joining its neighbours, so none of them is
        // information: the drawn line is the two positions the drive started and stopped at.
        assertEquals(listOf(points.first(), points.last()), thinned)
    }

    @Test
    fun aCornerIsNotDropped() {
        val corner = listOf(
            TrackPoint(45.0, 9.0),
            TrackPoint(45.0, 9.001),
            TrackPoint(45.001, 9.001),
        )

        // The middle position is ~111 m off the line joining the two ends: it is the corner, and
        // a line that lost it would draw a road that does not exist.
        assertEquals(corner, RouteThinning.thin(corner))
    }

    @Test
    fun theNoiseFloorIsUnderTwoMetres() {
        // A wiggle 1.5 m peak to peak — 0.75 m either side of a straight line — is GPS noise, not
        // a shape: at the 2 m rule every one of these positions is redundant.
        val noisy = (0..200).map { i ->
            TrackPoint(45.0 + north(i * 5.0), 9.0 + if (i % 2 == 0) north(0.75) else north(-0.75))
        }

        val thinned = RouteThinning.thin(noisy)

        assertEquals(2, thinned.size)
        assertEquals(noisy.first(), thinned.first())
        assertEquals(noisy.last(), thinned.last())
    }

    @Test
    fun aListTooShortToThinIsReturnedUnchanged() {
        val pair = listOf(TrackPoint(45.0, 9.0), TrackPoint(45.0, 9.001))

        // The map's overlay guard redraws on list identity, and there is nothing to redraw here.
        assertSame(pair, RouteThinning.thin(pair))
        val empty = emptyList<TrackPoint>()
        assertSame(empty, RouteThinning.thin(empty))
    }

    @Test
    fun theFinestRungIsTheTwoMetreLineAndItIsEnoughForMostDrives() {
        // A gentle arc: 5 000 positions of a curve, every one of them on the curve to well under
        // two metres, so the deviation pass alone brings it in and the page has one rung to draw.
        val arc = (0..4_999).map { i ->
            val angle = i * 0.0002
            TrackPoint(45.0 + 0.05 * Math.sin(angle), 9.0 + 0.05 * Math.cos(angle))
        }

        val ladder = RouteThinning.ladder(arc)

        assertEquals(1, ladder.size)
        assertTrue("the 2 m pass should have shrunk a 5 000-point arc", ladder[0].size < 100)
        assertEquals(arc.first(), ladder[0].first())
        assertEquals(arc.last(), ladder[0].last())
    }

    @Test
    fun everyRungFitsItsBudgetAndNeverGrowsOnTheWayDown() {
        // A shape no tolerance can shrink: the positions alternate between two places 111 m
        // apart, so every one of them is a real deviation and the pass keeps all of them. Only
        // the even walk at the end can bring this in — which is exactly what it is for.
        val zigzag = (0 until 20_000).map { i ->
            TrackPoint(45.0 + if (i % 2 == 0) 0.0 else north(111.0), 9.0)
        }

        val ladder = RouteThinning.ladder(zigzag)

        assertEquals(RouteThinning.BUDGETS.toList(), ladder.map { it.size })
        for (i in ladder.indices) {
            assertTrue(
                "rung $i drew ${ladder[i].size} points, over its ${RouteThinning.BUDGETS[i]} budget",
                ladder[i].size <= RouteThinning.BUDGETS[i],
            )
            assertEquals(zigzag.first(), ladder[i].first())
            assertEquals(zigzag.last(), ladder[i].last())
            if (i > 0) {
                assertTrue(
                    "rung $i is finer than the rung above it — quality must only ever step down",
                    ladder[i].size <= ladder[i - 1].size,
                )
            }
        }
    }

    @Test
    fun aCoarserToleranceIsPreferredToAnEvenWalk() {
        // A city road with 40 m of fix scatter either way: no 2 m pass can call these positions
        // redundant, and the budget is smaller than the file. The tolerance climb answers it — at
        // 64 m the scatter is redundant and the drawn line is the two ends of the leg. The even
        // walk would have answered with 3 000 evenly-spaced samples of the same scatter, so a
        // short rung is the proof that the climb ran first.
        val scatter = (0 until 20_000).map { i ->
            TrackPoint(45.0 + north(i * 5.0), 9.0 + if (i % 2 == 0) north(20.0) else north(-20.0))
        }

        val rung = RouteThinning.ladder(scatter, intArrayOf(3_000)).single()

        assertTrue(
            "the 64 m climb should have collapsed 20 000 scattered fixes, not walked them evenly",
            rung.size < 3_000,
        )
        assertEquals(scatter.first(), rung.first())
        assertEquals(scatter.last(), rung.last())
    }

    @Test
    fun anEmptyOrSinglePointDriveStillHasARungToDraw() {
        val empty = emptyList<TrackPoint>()
        val one = listOf(TrackPoint(45.0, 9.0))

        assertEquals(listOf(empty), RouteThinning.ladder(empty))
        assertEquals(listOf(one), RouteThinning.ladder(one))
    }
}
