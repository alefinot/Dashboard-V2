package com.alefinot.dashboardpp.trips

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing or killing the app is not a trip boundary (maintainer, 2026-11-25: *"it is
 * still creating a new trip file at every app relaunch"*). The recorder persists a
 * session row with every open file; a row that never got sealed means the process died
 * mid-drive, and the file is append-only - so the next launch continues it. Only a
 * drive the owner stopped is different, and that is the service's job: STOP drops the
 * row, so the next automatic start is a new drive.
 */
class TripRelaunchTest {

    private var now = 1_800_000_000_000L
    private val writer = FakeWriter()

    private fun prior(sealed: Boolean) = SessionRecord(
        ip = "10.0.0.5",
        lastUp = 500,
        lastSampleMs = now - 60_000L,
        lastMaxSpeed = 40.0,
        tripPath = "trip-old.csv",
        tripStartMs = now - 600_000L,
        sealed = sealed,
    )

    private fun recorder(session: SessionRecord) =
        TripRecorder(writer, { startMs -> "trip-$startMs.csv" }, { now }, session)

    @Test
    fun anUnsealedRowFromAKilledAppReopensTheSameFile() {
        writer.create("trip-old.csv", "")
        val recorder = recorder(prior(sealed = false))
        assertEquals(
            TripEvent.REOPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 540, spd = 20.0, max = 40.0)),
        )
        assertEquals(1, writer.files.size)
        assertEquals("trip-old.csv", recorder.openTrip?.path)
        assertEquals(1, writer.lines("trip-old.csv").size)
    }

    @Test
    fun aSealedRowStillReopensTheSameFile() {
        writer.create("trip-old.csv", "")
        val recorder = recorder(prior(sealed = true))
        assertEquals(
            TripEvent.REOPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 540, spd = 20.0, max = 40.0)),
        )
        assertEquals(1, writer.files.size)
    }

    @Test
    fun aRebootAcrossTheGapStillStartsANewFile() {
        writer.create("trip-old.csv", "")
        val recorder = recorder(prior(sealed = false))
        // Uptime is below the stored one: the unit restarted while the app was away, so
        // this is another power session even though the row had no seal.
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 30, spd = 5.0, max = 5.0)),
        )
        assertEquals(2, writer.files.size)
    }

    @Test
    fun stopDropsTheContinuityNoteAndTheUnsealedGateIsGone() {
        val service = moduleFile("src/main/java/com/alefinot/dashboardpp/service/TripLogService.kt")
        assertTrue(
            "STOP no longer records that the owner ended the drive - a relaunch would " +
                "resume a stopped one",
            Regex("shutdown\\(keepDisplay = keep, ownerStop = recorder != null\\)")
                .containsMatchIn(service),
        )
        assertTrue(
            "the service does not clear the session row on a stop",
            Regex("clearSessions\\(listOf\\(key\\)\\)").containsMatchIn(service),
        )
        val recorder = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/TripRecorder.kt")
        assertFalse(
            "an unsealed session is refused again - every app relaunch would start a new file",
            Regex("if \\(!r\\.sealed\\) return null").containsMatchIn(recorder),
        )
    }

    private fun moduleFile(relative: String): String {
        val marker = "src/main/java/com/alefinot/dashboardpp/trips"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (cand in listOf(File(dir, "android/app"), File(dir, "app"), dir)) {
                if (File(cand, marker).isDirectory) {
                    val f = File(cand, relative)
                    assertTrue("cannot find $f", f.isFile)
                    return f.readText()
                }
            }
            dir = dir.parentFile
        }
        error("no app module found above ${System.getProperty("user.dir") ?: "?"}")
    }
}
