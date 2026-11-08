package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFormatTagTest {

    @Test
    fun idMacAndNameRoundTrip() {
        val line = TripFormat.tagLine("veh-7", "5E:FF:00:11:22:33", "Caminone")
        assertEquals("#vehicle=veh-7|5E:FF:00:11:22:33|Caminone", line)

        val tag = TripFormat.parseTag(line)
        assertNotNull(tag)
        assertEquals("veh-7", tag!!.vehicleId)
        assertEquals("5E:FF:00:11:22:33", tag.eff)
        assertEquals("Caminone", tag.name)
        assertEquals(tag.raw(), line.removePrefix(TripFormat.TAG_PREFIX))
        assertEquals(tag, TripTag.fromRaw(tag.raw()))
    }

    @Test
    fun noVehicleMeansNoLineAtAll() {
        assertEquals("", TripFormat.tagLine("", "5E:FF:00:11:22:33", "Caminone"))
        assertEquals("", TripFormat.tagLine("   ", "AA:BB", "X"))
    }

    @Test
    fun aDashboardThatNeverReportedAMacIsWrittenAsTheWordUnknown() {
        val tag = TripFormat.parseTag(TripFormat.tagLine("veh-1", "", "Truck"))!!
        assertEquals(TripFormat.UNKNOWN_EFF, tag.eff)
    }

    @Test
    fun separatorsInsideANameCannotForgeAnotherField() {
        val line = TripFormat.tagLine("veh-1", "AA:BB:CC", "A|B;C\tD")
        val tag = TripFormat.parseTag(line)!!
        assertEquals(3, tag.raw().split("|").size)
        assertEquals("veh-1", tag.vehicleId)
        assertEquals("AA:BB:CC", tag.eff)
        assertEquals("A B C D", tag.name)
    }

    @Test
    fun onlyTheTagLineIsATag() {
        assertNull(TripFormat.parseTag(TripFormat.HEADER))
        assertNull(TripFormat.parseTag("1730000000000,101,12.5,1,0,0"))
        assertNull(TripFormat.parseTag("# a note somebody added by hand"))
        assertNull(TripFormat.parseTag(""))
    }

    @Test
    fun aTagWithNoIdOrNoShapeIsNotATag() {
        assertNull(TripTag.fromRaw("|AA:BB|Caminone"))
        assertNull(TripTag.fromRaw("veh-1"))
        assertNull(TripTag.fromRaw(""))
        // An empty name is fine - it is only a label, and the id is what filters on.
        assertEquals("veh-1", TripTag.fromRaw("veh-1|AA:BB|")!!.vehicleId)
    }

    @Test
    fun theTagAndTheHeaderAreNeverDataLines() {
        assertTrue(TripFormat.skippable(TripFormat.tagLine("veh-1", "AA:BB", "N")))
        assertTrue(TripFormat.skippable(TripFormat.HEADER))
        assertTrue(TripFormat.skippable("# a note somebody added by hand"))
        assertTrue(TripFormat.skippable("   "))
        assertTrue(!TripFormat.skippable("1730000000000,101,12.5,1,0,0,0,0,0,0,0,0,0,0,12.6,88,20,9,1.2,40,45.5,9.2,3,0,0"))
    }

    @Test
    fun aNewFileStartsWithTheTagAndThenTheHeader() {
        val tag = TripFormat.tagLine("veh-1", "AA:BB:CC", "Caminone")
        assertEquals(listOf(tag, TripFormat.HEADER), TripFormat.fileHead(tag))
        assertEquals(listOf(TripFormat.HEADER), TripFormat.fileHead(""))
        // Line 1 is where the reader looks, so the tag must come first.
        assertEquals(tag, TripFormat.fileHead(tag).first())
    }
}
