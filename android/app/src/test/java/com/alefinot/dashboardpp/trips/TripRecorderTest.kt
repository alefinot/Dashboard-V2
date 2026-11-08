package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {

    private var now = 1_730_000_000_000L
    private val writer = FakeWriter()
    private val recorder = TripRecorder(writer, { startMs -> "trip-$startMs.csv" }, { now })

    @Test
    fun firstSampleOpensATripAndWritesIt() {
        assertEquals(TripEvent.OPENED, recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0)))
        assertTrue(recorder.isRecording)
        assertEquals(1, writer.files.size)
        val path = writer.pathAt(0)
        assertEquals(1, writer.lines(path).size)
        assertEquals("trip-$now.csv", path)
        assertNotNull(recorder.summary())
    }

    @Test
    fun continuousSamplesAppendToOneFile() {
        for (i in 0 until 10) {
            recorder.onSnapshot("10.0.0.5", snap(up = 100L + i, spd = 20.0 + i, odo = 10.0 + i * 0.01))
            now += 1000L
        }
        assertEquals(1, writer.files.size)
        assertEquals(10, writer.lines(writer.pathAt(0)).size)
    }

    @Test
    fun silentDeviceSealsTheTrip() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        now += TripPolicy.SEAL_AFTER_MS + 1_000L
        assertEquals(TripEvent.SEALED, recorder.onTick())
        assertFalse(recorder.isRecording)
        assertEquals("device-silent", recorder.lastSealReason)
        assertTrue(writer.sealedPaths.isNotEmpty())
    }

    @Test
    fun aShortSilenceDoesNotSplitTheDrive() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        now += TripPolicy.SEAL_AFTER_MS - 20_000L
        assertEquals(TripEvent.NONE, recorder.onTick())
        recorder.onSnapshot("10.0.0.5", snap(up = 240, spd = 12.0))
        assertEquals(1, writer.files.size)
    }

    @Test
    fun uptimeGoingBackwardsMeansRebootAndSplitsTheTrip() {
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0))
        now += 1_000L
        assertEquals(
            TripEvent.SEALED_THEN_OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 12, spd = 5.0, max = 5.0)),
        )
        assertEquals(2, writer.files.size)
        assertEquals("esp-reboot", recorder.lastSealReason)
    }

    @Test
    fun sessionPeakResetWithRisingUptimeIsAlsoAReboot() {
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 90.0))
        now += 1_000L
        assertEquals(
            TripEvent.SEALED_THEN_OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 510, spd = 20.0, max = 5.0)),
        )
        assertEquals(2, writer.files.size)
    }

    @Test
    fun samePowerSessionAfterASealReopensTheSameFile() {
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0, odo = 10.0))
        now += TripPolicy.SEAL_AFTER_MS + 5_000L
        assertEquals(TripEvent.SEALED, recorder.onTick())
        val sealedPath = writer.pathAt(0)

        now += 600_000L
        assertEquals(
            TripEvent.REOPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 1110, spd = 30.0, max = 40.0, odo = 10.5)),
        )
        assertEquals(1, writer.files.size)
        assertEquals(sealedPath, recorder.openTrip?.path)
        assertEquals(2, recorder.summary()?.samples)
    }

    @Test
    fun aRebootAfterTheSealStartsANewFile() {
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0))
        now += TripPolicy.SEAL_AFTER_MS + 5_000L
        recorder.onTick()
        now += 60_000L
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 30, spd = 8.0, max = 8.0)),
        )
        assertEquals(2, writer.files.size)
    }

    @Test
    fun aDifferentIpNeverContinuesTheSameTrip() {
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0))
        now += 1_000L
        recorder.onSnapshot("10.0.0.9", snap(up = 501, spd = 20.0, max = 40.0))
        assertEquals(2, writer.files.size)
    }

    @Test
    fun stoppingTheServiceLeavesACompleteFile() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        assertEquals(TripEvent.SEALED, recorder.stop())
        assertFalse(recorder.isRecording)
        assertEquals(1, writer.sealedPaths.size)
    }

    @Test
    fun twelveHoursOfContinuousRecordingSplitsTheFile() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0, max = 10.0))
        now += TripPolicy.MAX_TRIP_MS + 1_000L
        assertEquals(
            TripEvent.SEALED_THEN_OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 100 + TripPolicy.MAX_TRIP_MS / 1000, spd = 10.0, max = 10.0)),
        )
        assertEquals(2, writer.files.size)
        assertEquals("session-cap-12h", recorder.lastSealReason)
    }

    @Test
    fun sessionRecordSurvivesEncodingForTheNextAppStart() {
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0))
        val record = recorder.sessionRecord()!!
        val back = SessionCodec.decode("10.0.0.5", SessionCodec.encode(record))
        assertNotNull(back)
        assertEquals(record.ip, back!!.ip)
        assertEquals(record.lastUp, back.lastUp)
        assertEquals(record.lastSampleMs, back.lastSampleMs)
        assertEquals(record.lastMaxSpeed, back.lastMaxSpeed, 1e-9)
        assertEquals(record.tripPath, back.tripPath)
        assertEquals(record.sealed, back.sealed)
    }

    @Test
    fun sessionCodecRejectsForeignOrTruncatedText() {
        assertEquals(null, SessionCodec.decode("10.0.0.5", ""))
        assertEquals(null, SessionCodec.decode("10.0.0.5", "garbage|nope"))
    }
}
