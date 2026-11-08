package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTagTest {

    private var now = 1_730_000_000_000L
    private val writer = FakeWriter()
    private val recorder = TripRecorder(writer, { startMs -> "trip-$startMs.csv" }, { now })

    private fun drive(up: Long, spd: Double = 20.0, ip: String = "10.0.0.5") {
        recorder.onSnapshot(ip, snap(up = up, spd = spd, odo = 10.0 + up * 0.01))
        now += 1000L
    }

    @Test
    fun aBoundRecorderWritesWhoseDriveItIs() {
        recorder.bindVehicle("veh-7", "5E:FF:00:11:22:33", "Caminone")
        drive(up = 100)

        assertEquals(
            "#vehicle=veh-7|5E:FF:00:11:22:33|Caminone",
            writer.tags[writer.pathAt(0)],
        )
        // The tag is not the only thing in the file: the samples are still there.
        assertEquals(1, writer.lines(writer.pathAt(0)).size)
    }

    @Test
    fun anUnboundRecorderStillRecordsAndWritesNoOwner() {
        assertEquals(TripEvent.OPENED, recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 12.0)))
        assertEquals("", writer.tags[writer.pathAt(0)])
        assertTrue(writer.files[writer.pathAt(0)]!!.startsWith(TripFormat.HEADER))
    }

    @Test
    fun bindingAfterAFileIsOpenLeavesThatFileAlone() {
        drive(up = 100)
        recorder.bindVehicle("veh-9", "AA:BB:CC:DD", "Renamed Midway")
        drive(up = 101)

        assertEquals(1, writer.files.size)
        assertEquals("", writer.tags[writer.pathAt(0)])
        assertEquals(2, writer.lines(writer.pathAt(0)).size)
    }

    @Test
    fun theNextFileCarriesTheBindingOfTheMomentItOpens() {
        recorder.bindVehicle("veh-1", "AA:BB:CC:00:00:01", "One")
        drive(up = 500)
        recorder.bindVehicle("veh-2", "AA:BB:CC:00:00:02", "Two")
        drive(up = 5)

        assertEquals(2, writer.files.size)
        assertEquals("#vehicle=veh-1|AA:BB:CC:00:00:01|One", writer.tags[writer.pathAt(0)])
        assertEquals("#vehicle=veh-2|AA:BB:CC:00:00:02|Two", writer.tags[writer.pathAt(1)])
    }

    @Test
    fun aDriveOnAnotherDeviceIsNotSilentlyReOwned() {
        recorder.bindVehicle("veh-1", "AA:BB:CC:00:00:01", "One")
        drive(up = 100)
        recorder.bindVehicle("veh-2", "AA:BB:CC:00:00:02", "Two")
        now += 60_000L
        drive(up = 100, ip = "192.168.4.1")

        assertEquals(2, writer.files.size)
        assertEquals("#vehicle=veh-1|AA:BB:CC:00:00:01|One", writer.tags[writer.pathAt(0)])
        assertEquals("#vehicle=veh-2|AA:BB:CC:00:00:02|Two", writer.tags[writer.pathAt(1)])
    }
}
