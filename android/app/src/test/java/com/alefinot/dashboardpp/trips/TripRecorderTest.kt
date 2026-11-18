package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trip identity rules (trip recording plan §3), tested on the JVM with a
 * fake clock and a fake writer. These are the cases a driver actually hits —
 * the ESP restarting, the phone walking out of range, the app being killed —
 * and each one decides whether the drive ends up as one honest file or several.
 */
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
        // The ESP's `max` lives in RAM only: it returns to zero on a reboot and
        // nowhere else. A drop while uptime keeps climbing can only be a restart
        // that happened inside a gap we were not polling in.
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

        // The same boot comes back ten minutes later: `up` kept climbing and the
        // session peak did not drop, so this is the same drive, not a new one.
        now += 600_000L
        assertEquals(
            TripEvent.REOPENED,
            recorder.onSnapshot("10.0.0.5", snap(up = 1110, spd = 30.0, max = 40.0, odo = 10.5)),
        )
        assertEquals(1, writer.files.size)
        assertEquals(sealedPath, recorder.openTrip?.path)
        // The running summary has to include what was already on disk, or the
        // "Recording…" row would understate the drive after every gap.
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
        // Another dashboard answering: its uptime is unrelated to the first one,
        // so merging would invent a single drive out of two devices.
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

    // ---- the live route the maps draw (live route plan §1, 2.0.7/179) -----------
    //
    // Every fixture here carries `fix = 1`: a position without a fix is not a
    // position, and the recorder is allowed to forget it (Snapshot.hasPosition).

    @Test
    fun liveRouteFollowsTheOpenTrip() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, lat = 45.62, lon = 9.28, fix = 1))
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, lat = 45.6201, lon = 9.2801, fix = 1))
        val route = recorder.liveRoute()
        assertEquals(1, route.segments.size)
        val pts = route.segments.single()
        assertEquals(2, pts.size)
        assertEquals(45.6201, pts.last().lat, 1e-9)
        assertEquals(9.2801, pts.last().lon, 1e-9)
        // The map's identity is the open trip's start, and nothing has been paused yet.
        assertEquals(recorder.openTrip?.startMs, route.driveId)
        assertNull(route.endAt)
        assertTrue(route.pauses.isEmpty())
    }

    @Test
    fun liveRouteIgnoresSamplesWithoutAFix() {
        // 0,0 is the unit declining a position, not a place in the Gulf of Guinea.
        recorder.onSnapshot("10.0.0.5", snap(up = 100))
        assertTrue(recorder.liveRoute().recordedPoints.isEmpty())
        // And a box full of coordinates with `fix` still 0 is the same decline.
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, lat = 45.62, lon = 9.28, fix = 0))
        assertTrue(recorder.liveRoute().recordedPoints.isEmpty())
    }

    @Test
    fun aStoppedTripKeepsItsRouteAndEndsAtTheLastWrittenPoint() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, lat = 45.62, lon = 9.28, fix = 1))
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, lat = 45.6201, lon = 9.2801, fix = 1))
        now += TripPolicy.SEAL_AFTER_MS + 1_000L
        assertEquals(TripEvent.SEALED, recorder.onTick())
        // The file is closed, the picture is not (the owner asked for exactly this):
        // the shape stays with a red END dot at the last written position.
        val route = recorder.liveRoute()
        assertTrue(route.hasTrack)
        assertTrue(route.closed)
        assertEquals(2, route.recordedPoints.size)
        assertEquals(TrackPoint(45.6201, 9.2801), route.endAt)
        assertTrue(route.pauses.isEmpty())
    }

    @Test
    fun reopeningSeedsTheLiveRouteFromTheFile() {
        recorder.onSnapshot(
            "10.0.0.5",
            snap(up = 500, spd = 20.0, max = 40.0, lat = 45.62, lon = 9.28, fix = 1),
        )
        now += 1_000L
        recorder.onSnapshot(
            "10.0.0.5",
            snap(up = 501, spd = 21.0, max = 41.0, lat = 45.621, lon = 9.281, fix = 1),
        )
        now += TripPolicy.SEAL_AFTER_MS + 5_000L
        assertEquals(TripEvent.SEALED, recorder.onTick())
        // Same boot comes back: the map on return must show the whole drive, not
        // just the minute after the gap - the file holds the rest, so seed from it.
        now += 600_000L
        assertEquals(
            TripEvent.REOPENED,
            recorder.onSnapshot(
                "10.0.0.5",
                snap(up = 1112, spd = 30.0, max = 41.0, lat = 45.622, lon = 9.282, fix = 1),
            ),
        )
        val route = recorder.liveRoute()
        assertEquals(1, route.segments.size)
        val pts = route.recordedPoints
        assertEquals(3, pts.size)
        assertEquals(45.62, pts.first().lat, 1e-9)
        assertEquals(45.622, pts.last().lat, 1e-9)
        assertNull(route.endAt)
    }

    @Test
    fun aRebootSplitStartsAFreshLiveRoute() {
        recorder.onSnapshot(
            "10.0.0.5",
            snap(up = 500, spd = 20.0, max = 40.0, lat = 45.62, lon = 9.28, fix = 1),
        )
        val firstDrive = recorder.liveRoute().driveId
        now += 1_000L
        assertEquals(
            TripEvent.SEALED_THEN_OPENED,
            recorder.onSnapshot(
                "10.0.0.5",
                snap(up = 12, spd = 5.0, max = 5.0, lat = 45.70, lon = 9.20, fix = 1),
            ),
        )
        // Two devices' histories do not merge into one route (same rule as the files).
        val route = recorder.liveRoute()
        assertEquals(1, route.segments.size)
        val pts = route.recordedPoints
        assertEquals(1, pts.size)
        assertEquals(45.70, pts.first().lat, 1e-9)
        assertTrue(route.driveId > firstDrive)
    }

    @Test
    fun aFinishedRouteIsReplacedOnlyByTheNextDrive() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, lat = 45.62, lon = 9.28, fix = 1))
        recorder.stop()
        val ended = recorder.liveRoute()
        assertTrue(ended.closed)
        // The route survives until a *new* drive starts, not just until the seal.
        assertEquals(ended.endAt, recorder.liveRoute().endAt)
        now += 60_000L
        assertEquals(
            TripEvent.OPENED,
            recorder.onSnapshot(
                "10.0.0.5",
                snap(up = 12, spd = 4.0, max = 4.0, lat = 45.70, lon = 9.20, fix = 1),
            ),
        )
        val fresh = recorder.liveRoute()
        assertFalse(fresh.closed)
        assertNull(fresh.endAt)
        assertEquals(1, fresh.recordedPoints.size)
        assertEquals(TrackPoint(45.70, 9.20), fresh.recordedPoints.single())
        assertTrue(fresh.driveId > ended.driveId)
    }

    @Test
    fun aPausedDriveFreezesTheRouteButNotTheArrow() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, lat = 45.62, lon = 9.28, fix = 1))
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, lat = 45.6201, lon = 9.2801, fix = 1))
        recorder.pause()
        now += 1_000L
        // The link stays live: the pause sees the frame but writes nothing.
        assertEquals(
            TripEvent.NONE,
            recorder.onSnapshot("10.0.0.5", snap(up = 102, lat = 45.6215, lon = 9.2815, fix = 1)),
        )
        val route = recorder.liveRoute()
        assertEquals(1, route.segments.size)
        assertEquals(2, route.segments.single().size)
        // The recorded line stops at the amber dot, and the live fix that came after the
        // pause belongs to the arrow, not to the drive's shape.
        assertEquals(listOf(TrackPoint(45.6201, 9.2801)), route.pauses)
        assertTrue(route.recordedPoints.none { it.lat == 45.6215 })
        assertNull(route.endAt)
    }

    @Test
    fun aResumeStartsASecondSegmentThatIsNotLinkedToTheFirst() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, lat = 45.62, lon = 9.28, fix = 1))
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, lat = 45.6201, lon = 9.2801, fix = 1))
        recorder.pause()
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 102, lat = 45.6215, lon = 9.2815, fix = 1))
        recorder.resume()
        now += 1_000L
        assertEquals(
            TripEvent.APPENDED,
            recorder.onSnapshot(
                "10.0.0.5",
                snap(up = 103, spd = 10.0, max = 10.0, lat = 45.623, lon = 9.283, fix = 1),
            ),
        )
        val route = recorder.liveRoute()
        assertEquals(2, route.segments.size)
        assertEquals(2, route.segments[0].size)
        assertEquals(1, route.segments[1].size)
        assertEquals(TrackPoint(45.623, 9.283), route.segments[1].single())
        // A resume does not erase the pause it ends. The orange dot is the record of where
        // the owner stopped writing, and it stays for the life of the drive (2026-11-18).
        assertEquals(listOf(TrackPoint(45.6201, 9.2801)), route.pauses)
        // No line joins the pieces: the resume starts where the first fix after it is,
        // not where the old piece ended.
        assertTrue(route.segments[0].last() != route.segments[1].first())
    }

    @Test
    fun everyPauseOfADriveKeepsItsOwnDot() {
        // Three stops, three orange dots, three pieces of route: a single dot that the next
        // resume deleted left a drive that stopped twice reading as a drive that stopped once.
        recorder.onSnapshot("10.0.0.5", snap(up = 100, lat = 45.62, lon = 9.28, fix = 1))
        recorder.pause()
        recorder.resume()
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, lat = 45.621, lon = 9.281, fix = 1))
        recorder.pause()
        recorder.resume()
        now += 1_000L
        assertEquals(
            TripEvent.APPENDED,
            recorder.onSnapshot(
                "10.0.0.5",
                snap(up = 102, spd = 10.0, max = 10.0, lat = 45.622, lon = 9.282, fix = 1),
            ),
        )
        val route = recorder.liveRoute()
        assertEquals(3, route.segments.size)
        assertEquals(
            listOf(TrackPoint(45.62, 9.28), TrackPoint(45.621, 9.281)),
            route.pauses,
        )
        assertNull(route.endAt)
    }

    @Test
    fun aStopWhilePausedTurnsThePauseDotIntoTheEndDot() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, lat = 45.62, lon = 9.28, fix = 1))
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, lat = 45.6201, lon = 9.2801, fix = 1))
        recorder.pause()
        assertEquals(TripEvent.SEALED, recorder.stop())
        val route = recorder.liveRoute()
        assertEquals(1, route.segments.size)
        assertTrue(route.closed)
        assertEquals(TrackPoint(45.6201, 9.2801), route.endAt)
        // The pause command is over: its dot has become the drive's END dot.
        assertTrue(route.pauses.isEmpty())
        assertFalse(recorder.isPaused)
    }

    // ─── pause / resume (owner controls, 2026-11-17) ─────────────────────────────

    @Test
    fun pauseStopsAppendingButKeepsTheFileOpen() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        val path = writer.pathAt(0)
        recorder.pause()
        now += 1_000L
        // The link is still up: the frame is seen (a fresh silence baseline) but the
        // file, the accumulator and the live route are untouched.
        assertEquals(TripEvent.NONE, recorder.onSnapshot("10.0.0.5", snap(up = 101, spd = 30.0)))
        assertTrue(recorder.isPaused)
        assertTrue(recorder.isRecording)
        assertEquals(path, recorder.openTrip?.path)
        assertEquals(1, writer.lines(path).size)
        assertEquals(1, recorder.summary()?.samples)
    }

    @Test
    fun resumeContinuesTheSameFile() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        val path = writer.pathAt(0)
        recorder.pause()
        now += 1_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 101, spd = 30.0))
        recorder.resume()
        now += 1_000L
        // `max` is the unit's session peak and never drops; a fixture that let it fall would
        // be read as a reboot, which is a different test.
        assertEquals(
            TripEvent.APPENDED,
            recorder.onSnapshot("10.0.0.5", snap(up = 102, spd = 12.0, max = 30.0)),
        )
        // One drive is one file: the pause is a hole in it, not a split.
        assertEquals(1, writer.files.size)
        assertEquals(2, writer.lines(path).size)
        assertEquals(2, recorder.summary()?.samples)
    }

    @Test
    fun aLiveLinkDuringALongPauseDoesNotSealTheTrip() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        recorder.pause()
        // Ten minutes of frames, each refreshing the silence baseline: the 120 s seal is
        // about a dead unit, not about a pause the owner asked for.
        for (i in 1..10) {
            now += 60_000L
            assertEquals(TripEvent.NONE, recorder.onSnapshot("10.0.0.5", snap(up = 100L + i, spd = 20.0)))
            assertEquals(TripEvent.NONE, recorder.onTick())
        }
        assertTrue(recorder.isRecording)
        assertTrue(writer.sealedPaths.isEmpty())
    }

    @Test
    fun aGenuinelySilentPausedRecorderStillSeals() {
        recorder.onSnapshot(
            "10.0.0.5",
            snap(up = 100, spd = 10.0, lat = 45.62, lon = 9.28, fix = 1),
        )
        recorder.pause()
        now += TripPolicy.SEAL_AFTER_MS + 1_000L
        assertEquals(TripEvent.SEALED, recorder.onTick())
        assertEquals("device-silent", recorder.lastSealReason)
        assertFalse(recorder.isRecording)
        // The pause survives the seal: RESUME is what starts the next file, not a frame.
        assertTrue(recorder.isPaused)
        // But the route's orange dot does not survive it: a closed drive wears END at the
        // position it last wrote, even though the owner's pause is still remembered.
        val route = recorder.liveRoute()
        assertTrue(route.pauses.isEmpty())
        assertEquals(TrackPoint(45.62, 9.28), route.endAt)
    }

    @Test
    fun resumeAfterASealOpensANewFile() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        recorder.pause()
        now += TripPolicy.SEAL_AFTER_MS + 1_000L
        recorder.onTick()
        recorder.resume()
        now += 1_000L
        assertEquals(TripEvent.OPENED, recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 10.0)))
        assertEquals(2, writer.files.size)
    }

    @Test
    fun pauseBeforeTheFirstSampleOpensNoFile() {
        // The service's pause can land before the unit sends anything; an ignored pause
        // would log exactly the samples the owner asked it not to.
        recorder.pause()
        assertEquals(TripEvent.NONE, recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0)))
        assertTrue(writer.files.isEmpty())
        assertFalse(recorder.isRecording)
        // No file, no recorded point, no orange dot - the pause has nothing to mark yet.
        assertTrue(recorder.liveRoute().pauses.isEmpty())
        recorder.resume()
        now += 1_000L
        assertEquals(TripEvent.OPENED, recorder.onSnapshot("10.0.0.5", snap(up = 101, spd = 10.0)))
        assertEquals(1, writer.files.size)
    }

    @Test
    fun stopWhilePausedSealsAndClearsThePause() {
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0))
        recorder.pause()
        assertEquals(TripEvent.SEALED, recorder.stop())
        assertFalse(recorder.isPaused)
        assertFalse(recorder.isRecording)
        assertTrue(writer.sealedPaths.isNotEmpty())
    }

    @Test
    fun aRebootWhilePausedSealsWithoutWritingASample() {
        recorder.onSnapshot("10.0.0.5", snap(up = 500, spd = 20.0, max = 40.0))
        recorder.pause()
        now += 1_000L
        // The reboot check runs before the pause guard: a pause must not mask a dead unit.
        assertEquals(
            TripEvent.NONE,
            recorder.onSnapshot("10.0.0.5", snap(up = 12, spd = 5.0, max = 5.0)),
        )
        assertEquals("esp-reboot", recorder.lastSealReason)
        assertFalse(recorder.isRecording)
        assertEquals(1, writer.files.size)
    }

    @Test
    fun distanceIncludesTheOdometerMovementLoggedWhilePaused() {
        // The pause drops samples, not kilometres: the drive happened while the file was
        // closed to appends, and the odometer delta is what the summary reports.
        recorder.onSnapshot("10.0.0.5", snap(up = 100, spd = 10.0, odo = 10.0))
        recorder.pause()
        now += 60_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 160, spd = 40.0, odo = 12.0))
        recorder.resume()
        now += 60_000L
        recorder.onSnapshot("10.0.0.5", snap(up = 220, spd = 30.0, max = 40.0, odo = 15.0))
        assertEquals(5.0, recorder.summary()!!.distanceKm, 1e-9)
        // Only the two written samples are in the file; the paused one is a gap.
        assertEquals(2, writer.lines(writer.pathAt(0)).size)
        assertEquals(1, recorder.summary()!!.gaps)
    }

    @Test
    fun theLiveRouteKeepsEveryFixOfADriveShortOfItsFirstThinning() {
        for (i in 0 until 600) {
            recorder.onSnapshot(
                "10.0.0.5",
                snap(
                    up = 100L + i, spd = 40.0, max = 40.0,
                    lat = 45.6 + i * 1e-5, lon = 9.2 + i * 1e-5, fix = 1,
                ),
            )
            now += 1_000L
        }
        recorder.pause()
        recorder.resume()
        for (i in 600 until 1200) {
            recorder.onSnapshot(
                "10.0.0.5",
                snap(
                    up = 100L + i, spd = 40.0, max = 40.0,
                    lat = 45.6 + i * 1e-5, lon = 9.2 + i * 1e-5, fix = 1,
                ),
            )
            now += 1_000L
        }
        val route = recorder.liveRoute()
        assertEquals(2, route.segments.size)
        // Twenty minutes of driving is kept fix for fix. The expectation used to be "at most
        // 257 per piece" - four minutes, after which every other position was thrown away and
        // the line on the map started cutting corners the drive did not cut. Thinning is now a
        // question of geometry, not of count: see LiveRouteTest.
        assertEquals(600, route.segments[0].size)
        assertEquals(600, route.segments[1].size)
        // And the piece's newest position is the drive's newest position: the map's tip and
        // the recorder's file agree about where the car is.
        assertEquals(
            TrackPoint(45.6 + 1199 * 1e-5, 9.2 + 1199 * 1e-5),
            route.segments[1].last(),
        )
    }
}
