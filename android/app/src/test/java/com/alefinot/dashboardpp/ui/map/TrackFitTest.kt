package com.alefinot.dashboardpp.ui.map

import com.alefinot.dashboardpp.trips.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The card's square is 72 dp on every phone, so the fit rule has to hold for
 * shapes that are wide, tall, still, and split by the antimeridian — all of it
 * arithmetic, all of it testable without a canvas.
 */
class TrackFitTest {

    private fun p(lat: Double, lon: Double) = TrackPoint(lat, lon)

    @Test
    fun `north is up`() {
        val points = TrackFit.project(listOf(p(48.0, 11.0), p(48.1, 11.0)), 100f, 100f, 10f)
        assertTrue(points[1].y < points[0].y)
    }

    @Test
    fun `the shape keeps its aspect ratio`() {
        // 0.2 degrees of longitude against 0.1 of latitude in a square box.
        val points = TrackFit.project(listOf(p(0.0, 0.0), p(0.1, 0.2)), 200f, 200f, 0f)
        val drawnW = abs(points[1].x - points[0].x)
        val drawnH = abs(points[1].y - points[0].y)
        assertEquals(2f, drawnW / drawnH, 1e-3f)
    }

    @Test
    fun `the shape is centred inside the inset`() {
        val points = TrackFit.project(listOf(p(48.0, 11.0), p(48.1, 11.2)), 200f, 100f, 10f)
        for (point in points) {
            assertTrue(point.x in 10f..190f)
            assertTrue(point.y in 10f..90f)
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        assertEquals(100f, (minX + maxX) / 2f, 0.01f)
    }

    @Test
    fun `a stationary track sits in the middle of the box`() {
        val points = TrackFit.project(List(50) { p(48.136857, 11.576125) }, 100f, 100f, 10f)
        assertEquals(50f, points.first().x, 0.01f)
        assertEquals(50f, points.first().y, 0.01f)
    }

    @Test
    fun `a single position is a point in the middle`() {
        val points = TrackFit.project(listOf(p(48.0, 11.0)), 100f, 100f, 10f)
        assertEquals(1, points.size)
        assertEquals(50f, points[0].x, 0.01f)
        assertEquals(50f, points[0].y, 0.01f)
    }

    @Test
    fun `a drive across the antimeridian is not smeared across the world`() {
        // Unwrapped, the track spans 0.2 degrees of longitude and 0.1 of
        // latitude, so it should use about half the square's height. Taken
        // naively, its width would be ~359.8 degrees and every point would sit
        // on one horizontal line.
        val points = TrackFit.project(
            listOf(p(0.0, 179.90), p(0.1, 179.95), p(0.1, -179.90)),
            200f, 200f, 0f,
        )
        assertTrue(abs(points[1].y - points[0].y) > 50f)
    }

    @Test
    fun `nothing to draw without a box`() {
        assertTrue(TrackFit.project(emptyList(), 100f, 100f, 10f).isEmpty())
        assertTrue(TrackFit.project(listOf(p(48.0, 11.0)), 0f, 100f, 0f).isEmpty())
        assertTrue(TrackFit.project(listOf(p(48.0, 11.0)), 100f, 20f, 10f).isEmpty())
    }
}
