package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCropTest {

    @Test
    fun cropSampleLandsJustAboveTheEdgeInsteadOfBelowIt() {
        assertEquals(2, VehiclePhotoStore.sampleForCrop(3000, 1280))
        assertEquals(2, VehiclePhotoStore.sampleForCrop(4000, 1280))
        // The boundary case: exactly at the edge, halving would fall under it.
        assertEquals(1, VehiclePhotoStore.sampleForCrop(1280, 1280))
        assertEquals(1, VehiclePhotoStore.sampleForCrop(900, 1280))
        // A crop twice the edge still only needs one halving, because the shrink finishes it.
        assertEquals(2, VehiclePhotoStore.sampleForCrop(2560, 1280))
        assertEquals(2, VehiclePhotoStore.sampleForCrop(2561, 1280))
        // Unusable requests answer 1 rather than guessing.
        assertEquals(1, VehiclePhotoStore.sampleForCrop(0, 1280))
        assertEquals(1, VehiclePhotoStore.sampleForCrop(3000, 0))
        // And the whole-frame rule is unchanged where it is right: at or below the box.
        assertEquals(4, VehiclePhotoStore.sampleSizeFor(4000, 3000, 1280))
    }

    @Test
    fun `a wide photo at no zoom takes the middle square`() {
        val r = PhotoCrop.rectFor(imgW = 4000, imgH = 3000, boxPx = 1000, zoom = 1f, offsetX = 0f, offsetY = 0f)!!
        assertEquals(3000, r.size)
        assertEquals(500, r.x)
        assertEquals(0, r.y)
    }

    @Test
    fun `a tall photo crops on the other axis`() {
        val r = PhotoCrop.rectFor(3000, 4000, 1000, 1f, 0f, 0f)!!
        assertEquals(3000, r.size)
        assertEquals(0, r.x)
        assertEquals(500, r.y)
    }

    @Test
    fun `a square photo at no zoom keeps the whole of it`() {
        val r = PhotoCrop.rectFor(2000, 2000, 1000, 1f, 0f, 0f)!!
        assertEquals(2000, r.size)
        assertEquals(0, r.x)
        assertEquals(0, r.y)
    }

    @Test
    fun `dragging the photo right moves the crop left`() {
        val r = PhotoCrop.rectFor(4000, 3000, 1000, 1f, 100f, 0f)!!
        assertEquals(200, r.x)
        assertEquals(0, r.y)
    }

    @Test
    fun `dragging past the edge of the photo stops at the edge`() {
        val left = PhotoCrop.rectFor(4000, 3000, 1000, 1f, 100_000f, 0f)!!
        assertEquals(0, left.x)
        val right = PhotoCrop.rectFor(4000, 3000, 1000, 1f, -100_000f, 0f)!!
        assertEquals(4000 - 3000, right.x)
        // The axis that only just fits has nowhere to go at all, and must not move.
        assertEquals(0, right.y)
    }

    @Test
    fun `zooming in halves the crop and keeps the centre`() {
        val r = PhotoCrop.rectFor(4000, 3000, 1000, 2f, 0f, 0f)!!
        assertEquals(1500, r.size)
        assertEquals(1250, r.x)
        assertEquals(750, r.y)
    }

    @Test
    fun `zoom and a hard drag together land in a corner, not outside`() {
        val r = PhotoCrop.rectFor(4000, 3000, 1000, 4f, -5000f, -5000f)!!
        assertEquals(750, r.size)
        assertEquals(4000 - 750, r.x)
        assertEquals(3000 - 750, r.y)
    }

    @Test
    fun `pinching out below the fit is treated as the fit`() {
        val zoomedOut = PhotoCrop.rectFor(4000, 3000, 1000, 0.2f, 0f, 0f)!!
        val fitted = PhotoCrop.rectFor(4000, 3000, 1000, 1f, 0f, 0f)!!
        assertEquals(fitted, zoomedOut)
    }

    @Test
    fun `nothing sensible comes out of nonsense measurements`() {
        assertNull(PhotoCrop.rectFor(0, 3000, 1000, 1f, 0f, 0f))
        assertNull(PhotoCrop.rectFor(4000, 0, 1000, 1f, 0f, 0f))
        assertNull(PhotoCrop.rectFor(4000, 3000, 0, 1f, 0f, 0f))
        assertNull(PhotoCrop.rectFor(4000, 3000, 1000, Float.NaN, 0f, 0f))
        assertNull(PhotoCrop.rectFor(4000, 3000, 1000, 1f, Float.POSITIVE_INFINITY, 0f))
    }

    @Test
    fun `every answer is a whole square that lies inside the photo`() {
        val sizes = listOf(4000 to 3000, 3000 to 4000, 1600 to 1200, 640 to 480, 200 to 200, 300 to 2000)
        val boxes = listOf(320, 640, 1000)
        val zooms = listOf(1f, 1.5f, 3f, 8f)
        val pans = listOf(0f, 120f, -120f, 99_000f, -99_000f)
        for (img in sizes) for (box in boxes) for (z in zooms) for (p in pans) {
            val r = PhotoCrop.rectFor(img.first, img.second, box, z, p, p)!!
            assertTrue("$img $box $z $p -> $r", r.size >= 1)
            assertTrue("$img $box $z $p -> $r", r.x >= 0 && r.x + r.size <= img.first)
            assertTrue("$img $box $z $p -> $r", r.y >= 0 && r.y + r.size <= img.second)
        }
    }

    // ── the gesture clamp the same screen needs ──────────────────────────────
    @Test
    fun `at the fit there is room to drag only along the long axis`() {
        val (x, y) = PhotoCrop.maxOffsetPx(4000, 3000, 1000, 1f)
        assertEquals(166.67f, x, 0.02f)
        assertEquals(0f, y, 0.02f)
    }

    @Test
    fun `zooming in opens up slack on both axes`() {
        val (x, y) = PhotoCrop.maxOffsetPx(4000, 3000, 1000, 2f)
        assertEquals(833.33f, x, 0.02f)
        assertEquals(500f, y, 0.02f)
    }

    @Test
    fun `a square photo at the fit cannot be dragged at all`() {
        val (x, y) = PhotoCrop.maxOffsetPx(2000, 2000, 1000, 1f)
        assertEquals(0f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    // ── the same crop, in the pixels of a smaller decode ─────────────────────
    @Test
    fun `a crop scales into the decoded copy at the same proportions`() {
        val r = PhotoCrop.scaleRectInto(CropRect(500, 0, 3000), fullW = 4000, fullH = 3000, smallW = 1000, smallH = 750)!!
        assertEquals(CropRect(125, 0, 750), r)
    }

    @Test
    fun `a crop at the far edge lands at the same place in the small copy`() {
        val r = PhotoCrop.scaleRectInto(CropRect(3250, 0, 750), 4000, 3000, 1000, 750)!!
        assertEquals(187, r.size) // 750 * 0.25, rounded down
        assertEquals(812, r.x)    // 3250 * 0.25, rounded down
        assertEquals(0, r.y)
        assertTrue(r.x + r.size <= 1000)
        assertTrue(r.y + r.size <= 750)
    }

    @Test
    fun `scaling keeps every rect inside the copy it is meant for`() {
        for (full in listOf(4000 to 3000, 3000 to 4000, 640 to 480)) {
            for (sample in listOf(1, 2, 4, 8)) {
                val smallW = full.first / sample
                val smallH = full.second / sample
                for (frac in listOf(0.0, 0.25, 0.5, 0.75)) {
                    val size = minOf(full.first, full.second)
                    val rect = CropRect(((full.first - size) * frac).toInt(), ((full.second - size) * frac).toInt(), size)
                    val r = PhotoCrop.scaleRectInto(rect, full.first, full.second, smallW, smallH)!!
                    assertTrue("$full /$sample $rect -> $r", r.size >= 1)
                    assertTrue("$full /$sample $rect -> $r", r.x >= 0 && r.x + r.size <= smallW)
                    assertTrue("$full /$sample $rect -> $r", r.y >= 0 && r.y + r.size <= smallH)
                }
            }
        }
    }

    @Test
    fun `a rect or a decode that does not exist has no scaled answer`() {
        assertNull(PhotoCrop.scaleRectInto(CropRect(0, 0, 0), 4000, 3000, 1000, 750))
        assertNull(PhotoCrop.scaleRectInto(CropRect(0, 0, 100), 0, 3000, 1000, 750))
        assertNull(PhotoCrop.scaleRectInto(CropRect(0, 0, 100), 4000, 3000, 0, 0))
    }
}
