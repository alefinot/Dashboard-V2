package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App Settings → Trips & Storage → **SYNC TRIP WITH DASHBOARD**, the recorder half
 * (maintainer, 2026-11-25: *"add a toggle in the app settings to sincronize the trip in the
 * app to the esp's trip"* — both directions, manual only, and a restart never resets).
 *
 * The dashboard's trip counter is monotonic between manual resets, so a lower `trp_km` is the
 * reset signal. What these tests pin down is the set of cases around it: the reboot that also
 * zeroes the counter (one split, not two), the re-open rule that must not undo the split, the
 * app's own START reset that must not split the file it just opened, and the restart that must
 * not split anything at all.
 */
class TripSyncTest {

    private var now = 1_730_000_000_000L
    private val writer = FakeWriter()

    private fun recorder(sync: Boolean = true) =
        TripRecorder(writer, { startMs -> "trip-$startMs.csv" }, { now }, tripSync = { sync })

    @Test
    fun aDashboardResetSealsTheOpenTripAndOpensANewFile() {
        val recorder = recorder()
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0, trpKm = 10.0)),
        )
        val first = writer.pathAt(0)
        now += 1_000L
        assertEquals(
            TripEvent.APPENDED,
            recorder.onSnapshot("10.0.0.5", snap(up = 101, spd = 20.0, trpKm = 10.5)),
        )
        now += 1_000L
        // The GPIO25 hold (or RESET TRIP) zeroed the dashboard's trip.
        assertEquals(
            TripEvent.SEALED_THEN_OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 102, spd = 20.0, trpKm = 0.0)),
        )
        assertEquals(EspTripSync.RESET_SEAL_REASON, recorder.lastSealReason)
        assertTrue(first in writer.sealedPaths)
        assertEquals(2, writer.files.size)
        // The new file starts with the frame that carried the reset, not with the next one.
        assertEquals(1, writer.lines(writer.pathAt(1)).size)
    }

    @Test
    fun theSameResetWithSyncOffKeepsOneFile() {
        val recorder = recorder(sync = false)
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0, trpKm = 10.0))
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, spd = 20.0, trpKm = 10.5))
        now += 1_000L
        assertEquals(
            TripEvent.APPENDED,
            recorder.onSnapshot("10.0.0.5", snap(up = 102, spd = 20.0, trpKm = 0.0)),
        )
        assertEquals(1, writer.files.size)
        assertEquals(3, writer.lines(writer.pathAt(0)).size)
    }

    @Test
    fun aRebootWithAZeroedTripIsOneRebootSplit() {
        val recorder = recorder()
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0, trpKm = 10.0))
        now += 1_000L
        assertEquals(
            TripEvent.SEALED_THEN_OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 12, spd = 5.0, max = 5.0, trpKm = 0.0)),
        )
        // The RAM-only trip zeroes on a reboot too, but that is the reboot's one split:
        // reading it again as a manual reset would close a file that never held a sample.
        assertEquals("esp-reboot", recorder.lastSealReason)
        assertEquals(2, writer.files.size)
    }

    @Test
    fun aFrameAfterAnAppRestartIsNotReadAsAReset() {
        // The app was killed mid-drive and rebuilt from the persisted session. The first
        // frame has no predecessor to compare against, so the lower counter cannot be a
        // reset - the drive continues in the file it left.
        val prior = SessionRecord(
            ip = "10.0.0.5",
            lastUp = 500,
            lastSampleMs = now - 60_000L,
            lastMaxSpeed = 40.0,
            tripPath = "trip-old.csv",
            tripStartMs = now - 600_000L,
            sealed = true,
        )
        writer.create("trip-old.csv", "")
        val recorder = TripRecorder(
            writer, { startMs -> "trip-$startMs.csv" }, { now }, prior, tripSync = { true },
        )
        now += 60_000L
        assertEquals(
            TripEvent.REOPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 560, spd = 10.0, max = 40.0, trpKm = 0.0)),
        )
        assertEquals(1, writer.files.size)
    }

    @Test
    fun aResetAfterASilentSealOpensANewFileInsteadOfReopening() {
        val recorder = recorder()
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0, trpKm = 10.0))
        now += TripPolicy.SEAL_AFTER_MS + 5_000L
        assertEquals(TripEvent.SEALED, recorder.onTick())
        now += 60_000L
        // Same boot, same peak: the 24 h rule would re-open the sealed file, but the
        // dashboard's trip ended in the gap and that file belongs to the old trip.
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 565, spd = 10.0, max = 40.0, trpKm = 0.0)),
        )
        assertEquals(2, writer.files.size)
    }

    @Test
    fun aResetWhilePausedSealsAndResumeOpensANewFile() {
        val recorder = recorder()
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0, trpKm = 10.0))
        recorder.pause()
        now += 1_000L
        assertEquals(
            TripEvent.SEALED,
            recorder.onSnapshot("10.0.0.5", snap(up = 101, spd = 10.0, trpKm = 0.0)),
        )
        assertEquals(EspTripSync.RESET_SEAL_REASON, recorder.lastSealReason)
        assertTrue(recorder.isPaused)
        assertFalse(recorder.isRecording)
        // The remembered session carries no path, so an app death here cannot re-open
        // the file the reset closed.
        assertNull(recorder.sessionRecord()!!.tripPath)
        recorder.resume()
        now += 1_000L
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 102, spd = 10.0, trpKm = 0.1)),
        )
        assertEquals(2, writer.files.size)
    }

    @Test
    fun aDifferentDevicesLowerCounterIsNotAReset() {
        val recorder = recorder()
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 10.0, trpKm = 10.0))
        now += 1_000L
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot("10.0.0.9", snap(up = 12, spd = 5.0, trpKm = 0.0)),
        )
        // Two dashboards are never one drive, and the new unit's counter is not the old
        // unit's reset: the seal is the device change, not the trip sync.
        assertEquals("device-changed", recorder.lastSealReason)
        assertEquals(2, writer.files.size)
    }

    @Test
    fun theAppsOwnStartResetDoesNotSplitTheNewFile() {
        val recorder = recorder()
        // A manual START with sync on: the service has just sent trip.reset.
        recorder.noteOwnTripReset()
        // The RPC raced the 1 Hz stream: this frame was made before the unit applied it.
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0, trpKm = 10.0)),
        )
        now += 1_000L
        // The counter is back at zero - the answer to the app's own reset, not a second,
        // manual one, so the file the START opened keeps being written.
        assertEquals(
            TripEvent.APPENDED,
            recorder.onSnapshot("10.0.0.5", snap(up = 101, spd = 10.0, trpKm = 0.0)),
        )
        assertEquals(1, writer.files.size)
        assertEquals(2, writer.lines(writer.pathAt(0)).size)
    }

    @Test
    fun theOwnResetMarkerExpiresSoALaterManualResetStillSplits() {
        val recorder = recorder()
        recorder.noteOwnTripReset()
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0, trpKm = 10.0))
        // The unit never applied the request (a firmware-update screen owns the sensor
        // pass). Ten seconds later the marker is gone, and a real reset must split.
        now += 11_000L
        assertEquals(
            TripEvent.SEALED_THEN_OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 111, spd = 10.0, trpKm = 0.0)),
        )
        assertEquals(2, writer.files.size)
    }

    @Test
    fun aForgottenPathSurvivesTheSessionCodec() {
        // The "do not re-open" marker is the empty path in the persisted session: losing it
        // in the codec would re-open a file the dashboard's reset had already ended.
        val r = SessionRecord(
            ip = "10.0.0.5",
            lastUp = 1,
            lastSampleMs = 2,
            lastMaxSpeed = 3.0,
            tripPath = null,
            tripStartMs = 4,
            sealed = true,
        )
        val back = SessionCodec.decode("10.0.0.5", SessionCodec.encode(r))
        assertNull(back!!.tripPath)
        assertTrue(back.sealed)
    }

    @Test
    fun theResetRuleIsADropBelowThePrintingStep() {
        assertTrue(EspTripSync.isReset(snap(up = 1, trpKm = 10.0), snap(up = 2, trpKm = 0.0)))
        assertTrue(EspTripSync.isReset(snap(up = 1, trpKm = 0.01), snap(up = 2, trpKm = 0.0)))
        assertFalse(EspTripSync.isReset(snap(up = 1, trpKm = 10.0), snap(up = 2, trpKm = 10.0)))
        assertFalse(EspTripSync.isReset(snap(up = 1, trpKm = 10.0), snap(up = 2, trpKm = 10.01)))
        // A re-sent frame carries the same figure; two decimals are the unit's resolution.
        assertFalse(EspTripSync.isReset(snap(up = 1, trpKm = 10.0), snap(up = 2, trpKm = 10.0)))
    }
}
