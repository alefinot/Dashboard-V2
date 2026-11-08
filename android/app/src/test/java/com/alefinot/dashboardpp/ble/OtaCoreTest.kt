package com.alefinot.dashboardpp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class OtaCoreTest {

@Test
    fun chunkSizeFollowsTheMtu() {
        assertEquals(496, OtaFrames.maxChunk(517))
        assertEquals(240, OtaFrames.maxChunk(247))
        assertEquals(16, OtaFrames.maxChunk(23))
        // A link that never negotiated: keep the frame legal rather than refuse to send.
        assertEquals(1, OtaFrames.maxChunk(5))
        // Never zero, or a transfer would spin without advancing.
        assertTrue(OtaFrames.maxChunk(0) >= 1)
    }

    @Test
    fun chunkSizeNeverRoundsPastTheLinkOrBelowOneByte() {
        assertEquals(11, OtaFrames.maxChunk(18))
        assertEquals(12, OtaFrames.maxChunk(19))
        for (mtu in intArrayOf(23, 27, 65, 185, 247, 251, 512, 517)) {
            val chunk = OtaFrames.maxChunk(mtu)
            assertTrue("chunk $chunk does not fit an ATT write at MTU $mtu",
                chunk + OtaFrames.OFFSET_BYTES <= mtu - 3)
            assertTrue("chunk $chunk must advance the transfer", chunk >= 1)
        }
    }

/** The four offset bytes are little-endian, and the unit reads them that way. */
    @Test
    fun offsetIsLittleEndian() {
        val frame = OtaFrames.data(0x12345678, byteArrayOf(0x01, 0x02), 0, 2)
        assertEquals(4, OtaFrames.OFFSET_BYTES)
        assertEquals(6, frame.size)
        assertEquals(0x78.toByte(), frame[0])
        assertEquals(0x56.toByte(), frame[1])
        assertEquals(0x34.toByte(), frame[2])
        assertEquals(0x12.toByte(), frame[3])
        assertEquals(0x01.toByte(), frame[4])
        assertEquals(0x02.toByte(), frame[5])
        assertEquals(0x12345678, OtaFrames.offsetOf(frame))
    }

    @Test
    fun offsetRoundTrips() {
        for (off in listOf(0, 1, 255, 256, 65535, 65536, 1_900_543, Int.MAX_VALUE)) {
            val frame = OtaFrames.data(off, byteArrayOf(0x7F.toByte()), 0, 1)
            assertEquals("offset $off came back wrong", off, OtaFrames.offsetOf(frame))
        }
        assertEquals(-1, OtaFrames.offsetOf(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun slicesComeOutOfTheFileAtTheRightPlace() {
        val file = ByteArray(1000) { (it % 251).toByte() }
        var off = 0
        while (off < file.size) {
            val len = minOf(300, file.size - off)
            val frame = OtaFrames.data(off, file, off, len)
            assertEquals(off, OtaFrames.offsetOf(frame))
            assertEquals(4 + len, frame.size)
            for (i in 0 until len) {
                assertEquals("byte $i of the slice at $off", file[off + i], frame[4 + i])
            }
            off += len
        }
    }

    @Test
    fun segmentsTileTheImageWithNoGapAndNoOverlap() {
        val seen = mutableListOf<OtaSegment>()
        var off = 0
        while (true) {
            val seg = nextSegment(total = 1000, chunk = 300, off = off) ?: break
            seen += seg
            off = seg.offset + seg.length
        }
        assertEquals(
            listOf(OtaSegment(0, 300), OtaSegment(300, 300), OtaSegment(600, 300), OtaSegment(900, 100)),
            seen,
        )
        assertEquals(OtaSegment(100, 100), nextSegment(total = 200, chunk = 100, off = 100))
        assertEquals(OtaSegment(0, 200), nextSegment(total = 200, chunk = 500, off = 0))
        assertNull(nextSegment(total = 200, chunk = 100, off = 200))
        assertNull(nextSegment(total = 200, chunk = 100, off = 250))
        assertNull(nextSegment(total = 200, chunk = 100, off = -1))
    }

    @Test
    fun aResumeStartsAtTheConfirmedOffset() {
        assertEquals(OtaSegment(600, 300), nextSegment(total = 1000, chunk = 300, off = 600))
        assertEquals(OtaSegment(900, 100), nextSegment(total = 1000, chunk = 300, off = 900))
        assertNull(nextSegment(total = 1000, chunk = 300, off = 1000))
        assertNull(nextSegment(total = 1000, chunk = 300, off = 1200))
    }

    @Test
    fun aLateBeginOkDoesNotRewindTheTransfer() {
        // Nothing sent yet: the event is the resume answer, take it.
        assertEquals(786432, adoptBeginOkOffset(sentTo = 0, eventOff = 786432))
        assertEquals(0, adoptBeginOkOffset(sentTo = 0, eventOff = 0))
        // Bytes already sent: the echo of an answer already applied is ignored.
        assertEquals(496, adoptBeginOkOffset(sentTo = 496, eventOff = 0))
        assertEquals(1048576, adoptBeginOkOffset(sentTo = 1048576, eventOff = 0))
        // A duplicate that agrees with the phone changes nothing either way.
        assertEquals(496, adoptBeginOkOffset(sentTo = 496, eventOff = 496))
    }

    /** The phone's checksum has to be the same number the unit computes, or the check is theatre. */
    @Test
    fun crcIsTheSameNumberTheUnitComputes() {
        val probe = OtaImages.probe("123456789".toByteArray())
        assertEquals(0xCBF43926L, probe.crc32)
        assertEquals(9, probe.size)
        assertNull(probe.versionTag)
        assertEquals(CRC32().apply { update("123456789".toByteArray()) }.value, probe.crc32)
        assertEquals(probe.crc32, OtaImages.crc32Of("123456789".byteInputStream()))
    }

@Test
    fun versionMarkerIsFoundWhereFirmwarePutsIt() {
        val image = ByteArray(4096)
        for (i in image.indices) image[i] = (i % 251).toByte()
        "DPPVER=1.5.1".toByteArray().copyInto(image, 1024)
        val probe = OtaImages.probe(image)
        assertEquals("1.5.1", probe.versionTag)
        assertEquals(4096, probe.size)
    }

    @Test
    fun versionMarkerStopsAtTheFirstNonTagByte() {
        assertEquals("1.5.1", OtaImages.scanVersionTag("DPPVER=1.5.1\u0000more".toByteArray()))
        assertEquals("1.5.1", OtaImages.scanVersionTag("DPPVER=1.5.1 more".toByteArray()))
        assertEquals("1.5.1", OtaImages.scanVersionTag("DPPVER=1.5.1\n".toByteArray()))
        assertEquals("1.3.2-rc1", OtaImages.scanVersionTag("DPPVER=1.3.2-rc1\u0000".toByteArray()))
        assertNull(OtaImages.scanVersionTag("DPPVER=".toByteArray()))
        assertNull(OtaImages.scanVersionTag("no marker here".toByteArray()))
        assertNull(OtaImages.scanVersionTag(ByteArray(0)))
    }

    /** The tag is short by construction; a longer reading means the scan ran past the marker. */
    @Test
    fun versionMarkerIsBounded() {
        val raw = "DPPVER=".toByteArray() + ByteArray(64) { 'a'.code.toByte() }
        val tag = OtaImages.scanVersionTag(raw)
        assertEquals(OtaImages.TAG_MAX, tag!!.length)
    }

    /** A marker at the very end of the file, or one cut short by it, must not run off the array. */
    @Test
    fun scanWindowIsClampedToTheBuffer() {
        val image = ByteArray(128)
        val marker = "DPPVER=2.0.0-rc1".toByteArray()
        marker.copyInto(image, image.size - marker.size)
        assertEquals("2.0.0-rc1", OtaImages.probe(image).versionTag)

        val short = ByteArray(64)
        "DPPVER=9.9".toByteArray().copyInto(short, short.size - "DPPVER=9.9".length)
        assertEquals("9.9", OtaImages.probe(short).versionTag)
    }

@Test
    fun unitFramesParse() {
        assertEquals(OtaEvent.BeginOk(chunk = 512, off = 0), parseOtaEvent("""{"ev":"begin_ok","chunk":512,"off":0}"""))
        assertEquals(OtaEvent.BeginOk(chunk = 240, off = 1024), parseOtaEvent("""{"ev":"begin_ok","chunk":240,"off":1024}"""))
        assertEquals(OtaEvent.Ack(off = 4096), parseOtaEvent("""{"ev":"ack","off":4096}"""))
        assertEquals(OtaEvent.Done, parseOtaEvent("""{"ev":"done"}"""))
        assertEquals(OtaEvent.Failure(reason = "crc", off = 900_000), parseOtaEvent("""{"ev":"error","reason":"crc","off":900000}"""))
    }

    /** Garbage on the notify channel is not a reason to abandon a transfer that is going fine. */
    @Test
    fun unparseableFramesAreDroppedRatherThanGuessed() {
        assertNull(parseOtaEvent(""))
        assertNull(parseOtaEvent("   "))
        assertNull(parseOtaEvent("not json at all"))
        assertNull(parseOtaEvent("""{"chunk":512}"""))
        assertNull(parseOtaEvent("""{"ev":"shrug","chunk":512}"""))
        assertNull(parseOtaEvent("""{"ev":"ack"}"""))
    }

    /** A frame's `off`/`reason` are optional in the wire shape; the app supplies neutral defaults. */
    @Test
    fun optionalFieldsFallBackToNeutralValues() {
        assertEquals(OtaEvent.BeginOk(chunk = 0, off = 0), parseOtaEvent("""{"ev":"begin_ok"}"""))
        assertEquals(OtaEvent.Ack(off = 0), parseOtaEvent("""{"ev":"ack","off":0}"""))
        assertEquals(OtaEvent.Failure(reason = "unknown", off = 0), parseOtaEvent("""{"ev":"error"}"""))
    }

@Test
    fun progressFollowsTheUnitNotThePhone() {
        val t = OtaTracker(total = 1000)
        assertFalseTerminal(t.apply(OtaEvent.BeginOk(chunk = 300, off = 0)))
        assertEquals(300, t.chunk)
        t.wrote(300)
        assertEquals(300, t.sent)
        assertEquals(0, t.acked)
        assertEquals(OtaStage.Sending, t.stage)

        t.apply(OtaEvent.Ack(off = 300))
        assertEquals(300, t.acked)

        // A late or duplicated ACK of a smaller offset must not walk the bar backwards.
        t.apply(OtaEvent.Ack(off = 100))
        assertEquals(300, t.acked)

        t.wrote(1000)
        t.apply(OtaEvent.Ack(off = 1000))
        assertEquals(1000, t.acked)
        assertEquals(1f, OtaView(total = 1000, acked = t.acked).fraction())
    }

    /** `sent` is a floor the unit cannot contradict; `acked` is the unit's word. */
    @Test
    fun sentOnlyMovesForward() {
        val t = OtaTracker(total = 1000)
        t.wrote(400)
        t.wrote(200)
        assertEquals(400, t.sent)
        t.apply(OtaEvent.Ack(off = 500))
        assertEquals(400, t.sent)
    }

    @Test
    fun aResumeAdoptsTheUnitsOffset() {
        val t = OtaTracker(total = 1000)
        t.apply(OtaEvent.BeginOk(chunk = 300, off = 0))
        t.wrote(900)
        t.apply(OtaEvent.Ack(off = 600))
        assertEquals(600, t.acked)
        t.apply(OtaEvent.BeginOk(chunk = 300, off = 600))
        assertEquals(600, t.acked)
        t.apply(OtaEvent.BeginOk(chunk = 300, off = 800))
        assertEquals(800, t.acked)
    }

    @Test
    fun terminalFramesEndTheTracker() {
        val t = OtaTracker(total = 1000)
        t.apply(OtaEvent.BeginOk(chunk = 300, off = 0))
        t.wrote(1000)
        assertTrue(t.apply(OtaEvent.Done))
        assertEquals(OtaStage.Installed, t.stage)
        assertEquals(1000, t.acked)
        assertNull(t.error)

        val u = OtaTracker(total = 1000)
        u.apply(OtaEvent.BeginOk(chunk = 300, off = 0))
        u.wrote(700)
        assertTrue(u.apply(OtaEvent.Failure(reason = "crc", off = 640)))
        assertEquals(OtaStage.Failed, u.stage)
        assertEquals("crc", u.error)
        assertEquals(640, u.acked)
    }

    /** A failure frame with no offset must not invent one. */
    @Test
    fun aFailureWithoutAnOffsetLeavesTheProgressAlone() {
        val t = OtaTracker(total = 1000)
        t.apply(OtaEvent.BeginOk(chunk = 300, off = 0))
        t.apply(OtaEvent.Ack(off = 300))
        t.apply(OtaEvent.Failure(reason = "not_open", off = 0))
        assertEquals(300, t.acked)
        assertEquals("not_open", t.error)
    }

    /** A rate is only worth printing once there is an ACK to measure, and the clock is injectable. */
    @Test
    fun rateIsMeasuredOverTheWholeTransfer() {
        var nowMs = 0L
        val t = OtaTracker(total = 1000, clock = { nowMs })
        assertEquals(0.0, t.bytesPerSecond(), 0.0)

        nowMs = 1_000L
        t.apply(OtaEvent.BeginOk(chunk = 100, off = 0))
        nowMs = 2_000L
        t.apply(OtaEvent.Ack(off = 500))
        // 500 bytes in the 1000 ms since the transfer began, not since the last ACK.
        assertEquals(500.0, t.bytesPerSecond(), 0.001)

        nowMs = 2_500L
        t.apply(OtaEvent.Ack(off = 1000))
        assertEquals(666.667, t.bytesPerSecond(), 0.01)

        // An ETA only answers when there is a rate to project from.
        val view = OtaView(stage = OtaStage.Sending, total = 1000, acked = 1000, bytesPerSecond = 1000.0)
        assertEquals(0, view.etaSeconds())
        val half = OtaView(stage = OtaStage.Sending, total = 1000, acked = 500, bytesPerSecond = 250.0)
        assertEquals(2, half.etaSeconds())
        assertNull(OtaView(total = 1000, acked = 500).etaSeconds())
        assertNull(OtaView(total = 0, acked = 0, bytesPerSecond = 100.0).etaSeconds())
    }

    @Test
    fun anInstallRemembersItsOwnSecondsAndWrites() {
        var nowMs = 0L
        val t = OtaTracker(total = 1000, clock = { nowMs })
        assertEquals(0, t.writes)
        assertEquals(0.0, t.elapsedSeconds(), 0.0)
        assertEquals(0.0, t.writesPerSecond(), 0.0)

        nowMs = 1_000L
        t.apply(OtaEvent.BeginOk(chunk = 100, off = 0))
        nowMs = 2_000L
        for (i in 1..10) t.wrote(i * 100)
        nowMs = 3_000L
        assertEquals(2.0, t.elapsedSeconds(), 0.001)
        assertEquals(10, t.writes)
        assertEquals(5.0, t.writesPerSecond(), 0.001)

        // Same whole-transfer rule as the byte rate: a later write does not restart the clock.
        nowMs = 5_000L
        t.wrote(1000)
        t.apply(OtaEvent.Ack(off = 1000))
        assertEquals(4.0, t.elapsedSeconds(), 0.001)
        assertEquals(11, t.writes)
        assertEquals(2.75, t.writesPerSecond(), 0.001)

        val r = OtaTracker(total = 1000, clock = { nowMs })
        r.apply(OtaEvent.BeginOk(chunk = 100, off = 0))
        r.wrote(100)
        r.wrote(100)
        assertEquals(100, r.sent)
        assertEquals(2, r.writes)
    }

    @Test
    fun aResumedInstallStillReportsSecondsAndRate() {
        var nowMs = 100_000L
        val t = OtaTracker(total = 1000, clock = { nowMs })
        t.apply(OtaEvent.BeginOk(chunk = 100, off = 700))
        t.wrote(800)
        nowMs = 100_400L
        t.apply(OtaEvent.Ack(off = 800))
        assertEquals(0.4, t.elapsedSeconds(), 0.001)
        assertEquals(2000.0, t.bytesPerSecond(), 0.001)
        assertEquals(2.5, t.writesPerSecond(), 0.001)

        // A clock that does not move (or goes backwards in a rebooted phone) cannot divide by zero.
        val flat = OtaTracker(total = 1000, clock = { 5_000L })
        flat.apply(OtaEvent.BeginOk(chunk = 100, off = 0))
        flat.wrote(100)
        assertEquals(0.0, flat.elapsedSeconds(), 0.0)
        assertEquals(0.0, flat.writesPerSecond(), 0.0)
        assertEquals(0.0, flat.bytesPerSecond(), 0.0)
    }

    /** A fresh view carries zeros, not nulls, so the card can print it before anything happened. */
    @Test
    fun aFreshViewCarriesZeroSecondsAndZeroWrites() {
        val v = OtaView()
        assertEquals(0.0, v.elapsedSeconds, 0.0)
        assertEquals(0.0, v.writesPerSecond, 0.0)
        assertEquals(0, v.writes)
    }

    @Test
    fun progressNeverGoesPastTheImageOrBelowZero() {
        assertEquals(1f, OtaView(total = 1000, acked = 1000).fraction())
        assertEquals(100, OtaView(total = 1000, acked = 1000).percent())
        assertEquals(1f, OtaView(total = 1000, acked = 4000).fraction())
        assertEquals(0f, OtaView(total = 0).fraction())
        assertEquals(0f, OtaView(total = 1000, acked = -5).fraction())
        assertEquals(42, OtaView(total = 1000, acked = 424).percent())
    }

@Test
    fun everyUnitCodeHasItsOwnExplanation() {
        val codes = listOf(
            // src/bt_ota.cpp
            "already_running", "already_active", "not_connected", "no_client", "busy",
            "ota_in_progress", "too_many", "no_space", "size_declined", "bad_magic", "bad_length",
            "no_chunk_size", "bad_state", "update_begin_failed", "write_failed", "not_open",
            "no_session", "offset_mismatch", "crc", "link_lost", "stalled", "aborted", "end_failed",
            "bad_frame", "too_small",
            "not_implemented",
            // src/bt_rpc.cpp
            "no_permission", "unknown_method", "too_many_frames", "parse_error",
            // this app, about the file itself
            "file_unreadable", "file_empty", "too_small", "too_big", "cancelled", "timeout", "unknown",
        )
        val fallback = OtaErrors.explain("zzz-definitely-not-a-code")
        for (code in codes) {
            assertTrue("no sentence of its own for the code '$code'", OtaErrors.explain(code) != fallback)
        }
    }

    @Test
    fun failuresSayWhatHappenedToTheUnit() {
        for (code in listOf("crc", "no_space", "bad_magic", "end_failed", "stalled", "aborted")) {
            assertTrue("'$code' does not say the unit is safe", OtaErrors.explain(code).contains("still running"))
        }
        assertTrue(OtaErrors.needsAction("crc"))
        assertTrue(OtaErrors.needsAction("bad_magic"))
        assertTrue(OtaErrors.needsAction("offset_mismatch"))
        assertTrue(!OtaErrors.needsAction("already_running"))
        assertTrue(!OtaErrors.needsAction("aborted"))
    }

    @Test
    fun resumeWindowAndPacingMatchTheFirmware() {
        val firmware = firmwareSource("src/bt_ota.cpp")
        val silence = Regex("""OTA_SILENCE_MS\s*=\s*(\d+)""").find(firmware)
            ?: error("OTA_SILENCE_MS not found in src/bt_ota.cpp")
        assertEquals("the app's resume window must be the unit's silence window",
            silence.groupValues[1].toLong(), OtaController.RESUME_WINDOW_MS)
        // The same promise, in words, in the sentence a driver reads.
        assertTrue(OtaErrors.explain("link_lost").contains("two minutes"))

        val ack = Regex("""OTA_ACK_BYTES\s*=\s*(\d+)""").find(firmware)
            ?: error("OTA_ACK_BYTES not found in src/bt_ota.cpp")
        assertTrue(
            "the phone waits for ${OtaController.OUTSTANDING_BYTES} unconfirmed bytes, but the unit " +
                "acks every ${ack.groupValues[1]} — the transfer would stall waiting for an ACK",
            ack.groupValues[1].toInt() < OtaController.OUTSTANDING_BYTES,
        )
    }

    /** Finds the firmware source from wherever the tests happen to run (module dir or repo root). */
    private fun firmwareSource(relative: String): String {
        val parts = relative.split('/')
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            var candidate = dir
            for (p in parts) candidate = java.io.File(candidate, p)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("could not find $relative walking up from ${System.getProperty("user.dir")}")
    }

    private fun assertFalseTerminal(result: Boolean) {
        assertTrue("that event should not end the transfer", !result)
    }
}
