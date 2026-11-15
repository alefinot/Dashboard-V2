package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card's square is drawn from a bounded decimation of the file, so the two
 * properties that matter are that the list never grows past its budget and that
 * the shape still begins and ends where the drive did. The codec has one job
 * beyond the round trip: an empty route and a route that was never scanned must
 * not decode to the same thing.
 */
class TrackPreviewTest {

    private fun drive(n: Int): TrackPreview {
        val preview = TrackPreview()
        repeat(n) { i ->
            preview.add(48.0 + i * 0.0001, 11.0 + i * 0.0001)
        }
        return preview
    }

    @Test
    fun `a long drive never exceeds the point budget`() {
        val points = drive(1000).points()
        assertTrue(points.size in 2..TrackPreview.MAX_POINTS)
    }

    @Test
    fun `the first and the newest sample survive decimation`() {
        val preview = TrackPreview()
        repeat(500) { i -> preview.add(48.0 + i * 0.001, 11.0 + i * 0.001) }
        val points = preview.points()
        assertEquals(TrackPoint(48.0, 11.0), points.first())
        assertEquals(TrackPoint(48.0 + 499 * 0.001, 11.0 + 499 * 0.001), points.last())
    }

    @Test
    fun `a short drive is kept whole`() {
        assertEquals(3, drive(3).points().size)
    }

    @Test
    fun `coordinates a map cannot project are dropped`() {
        val preview = TrackPreview()
        preview.add(Double.NaN, 11.0)
        preview.add(91.0, 11.0)
        preview.add(48.0, 181.0)
        preview.add(48.0, Double.POSITIVE_INFINITY)
        // 0,0 is what a unit reports before its first fix; it is not a place.
        preview.add(0.0, 0.0)
        preview.add(48.13, 11.57)
        assertEquals(listOf(TrackPoint(48.13, 11.57)), preview.points())
    }

    @Test
    fun `the codec round-trips a track`() {
        val original = drive(40).points()
        val decoded = TrackPreviewCodec.decode(TrackPreviewCodec.encode(original))
        assertNotNull(decoded)
        assertEquals(original.size, decoded!!.size)
        // 5 decimals is about a metre, and the codec is allowed exactly that
        // much: the shape must survive, the bit pattern need not.
        original.zip(decoded).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-5)
            assertEquals(a.lon, b.lon, 1e-5)
        }
    }

    @Test
    fun `an empty route and a missing route are different answers`() {
        // Scanned, no GPS: a real answer that stops the list re-reading the file.
        assertEquals(emptyList<TrackPoint>(), TrackPreviewCodec.decode(TrackPreviewCodec.V))
        // Not a preview at all: the card keeps waiting for its scan.
        assertNull(TrackPreviewCodec.decode(""))
        assertNull(TrackPreviewCodec.decode("v2;48.0,11.0"))
        assertNull(TrackPreviewCodec.decode("v1;48.0"))
        assertNull(TrackPreviewCodec.decode("v1;48.0,x"))
    }
}
