package com.alefinot.dashboardpp.vehicles

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehiclePhotoTest {

    private val dir = File(File(File.separator + "storage", "emulated"), "0/Android/data/com.alefinot.dashboardpp/files/vehicles")

    // ---- sampleSizeFor: the box, and the RAM it buys -------------------------

    @Test
    fun `a 1280 request on a phone-camera photo is 4, not 8 and not 2`() {
        assertEquals(4, VehiclePhotoStore.sampleSizeFor(4000, 3000, 1280))
    }

    @Test
    fun `the longest edge decides, not the width`() {
        // A rotated capture is the same pixels, so it must get the same factor...
        assertEquals(
            VehiclePhotoStore.sampleSizeFor(4000, 3000, 1280),
            VehiclePhotoStore.sampleSizeFor(3000, 4000, 1280),
        )
        assertEquals(4, VehiclePhotoStore.sampleSizeFor(2000, 4000, 1280))
    }

    @Test
    fun `a source already inside the box is decoded whole`() {
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(800, 600, 1280))
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(1280, 720, 1280))
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(100, 100, 1280))
    }

    @Test
    fun `a box one pixel smaller doubles the factor`() {
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(1280, 720, 1280))
        assertEquals(2, VehiclePhotoStore.sampleSizeFor(1280, 720, 1279))
    }

    @Test
    fun `a card thumbnail request on the same photo is 16`() {
        assertEquals(16, VehiclePhotoStore.sampleSizeFor(4000, 3000, 320))
        assertEquals(250, 4000 / 16)
        assertEquals(187, 3000 / 16)
    }

    @Test
    fun `a 160 dp box at the densities this app ships to stays a thumbnail`() {
        for (edge in listOf(160, 240, 320, 480, 560)) {
            val s = VehiclePhotoStore.sampleSizeFor(4000, 3000, edge)
            assertTrue("$edge px box -> $s", 4000 / s <= edge)
            assertTrue("$edge px box decodes only ${4000 / s} px", 4000 / s >= edge / 2)
        }
    }

    @Test
    fun `the factor always leaves the decoded image inside the box and no smaller than needed`() {
        val shapes = listOf(
            Triple(4000, 3000, 1280), Triple(3000, 4000, 1280), Triple(5000, 5000, 1280),
            Triple(1920, 1080, 1280), Triple(4000, 3000, 320), Triple(4000, 3000, 640),
            Triple(1280, 720, 1280), Triple(800, 600, 1280), Triple(640, 480, 160),
        )
        for ((w, h, edge) in shapes) {
            val s = VehiclePhotoStore.sampleSizeFor(w, h, edge)
            val longest = maxOf(w, h)
            assertTrue("$s for $w x $h at $edge", longest / s <= edge)
            if (s > 1) assertTrue("factor $s undershot $w x $h at $edge", longest / (s / 2) > edge)
        }
    }

    @Test
    fun `an unusable request answers 1 rather than 0 or a negative factor`() {
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(0, 0, 1280))
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(-4000, 3000, 1280))
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(4000, -3000, 1280))
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(4000, 3000, 0))
        assertEquals(1, VehiclePhotoStore.sampleSizeFor(4000, 3000, -1280))
    }

    @Test
    fun `the doubling stops at a positive factor whatever the dimensions claim`() {
        val huge = VehiclePhotoStore.sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, 1)
        assertEquals(1 shl 30, huge)
        assertEquals(2, VehiclePhotoStore.sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, 1 shl 30))
    }

    // ---- photoFileIn: a stored name is never trusted as a path ---------------

    @Test
    fun `a bare file name resolves inside the vehicles dir`() {
        val f = VehiclePhotoStore.photoFileIn(dir, "v1.jpg")
        assertNotNull(f)
        assertEquals("v1.jpg", f!!.name)
        assertEquals(dir, f.parentFile)
    }

    @Test
    fun `a name that walks out of the vehicles dir is refused`() {
        for (bad in listOf(
            "../etc/x",
            "../trips/trip-20240101-000000-001.csv",
            "a/../etc/x",
            "a/b",
            "vehicles/../../x.jpg",
            "..\\..\\windows\\x.jpg",
            "/sdcard/Download/photo.jpg",
            "C:\\Users\\someone\\photo.jpg",
        )) {
            assertNull("accepted \"$bad\"", VehiclePhotoStore.photoFileIn(dir, bad))
        }
    }

    @Test
    fun `an empty name or a bare dot is refused because it resolves to the directory itself`() {
        assertNull(VehiclePhotoStore.photoFileIn(dir, ""))
        assertNull(VehiclePhotoStore.photoFileIn(dir, "."))
    }

    @Test
    fun `a null photoFile resolves to no photo`() {
        assertNull(VehiclePhotoStore.photoFileIn(dir, null))
    }

    @Test
    fun `a name with a control character in it is refused`() {
        assertNull(VehiclePhotoStore.photoFileIn(dir, "v1.jpg\u0000.png"))
        assertNull(VehiclePhotoStore.photoFileIn(dir, "v1.jpg\n../../x"))
    }
}
