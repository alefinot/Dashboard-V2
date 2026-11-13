package com.alefinot.dashboardpp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * The parts of the Bluetooth installer that can be wrong without anybody noticing.
 *
 * Everything here is arithmetic that runs while a 1.5 MB image is pushed over ATT: how big a chunk
 * may be, which offsets a transfer covers, where the unit thinks it is, and what a frame that
 * arrives out of order does to the progress bar. A bug in any of it does not show up as an
 * exception — it shows up as an install that dies with `offset_mismatch` after four minutes, a bar
 * that reads 100 % while the unit is at a third, or a resume that rewinds the unit's CRC and kills
 * a transfer that was nearly finished. Those are the failures these tests are for.
 */
class OtaCoreTest {

    // ------------------------------------------------------------------
    // Chunk sizing
    // ------------------------------------------------------------------

    /**
     * A chunk that does not fit the ATT payload does not fail cleanly — the stack rejects the
     * write, or on some phones fragments it, and either way the transfer stalls. Four of the
     * payload bytes belong to the offset that opens the frame, so the ceiling is `MTU - 7`
     * rather than `MTU - 3`. 23 bytes is what a link is worth before negotiation and 517 is what
     * it is worth after, so both ends of that range are worth pinning.
     */
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

    /**
     * Alignment is a throughput nicety; staying inside the ATT payload is not. The offset prefix
     * must be counted in the byte that goes over the air, which is the arithmetic this whole
     * test exists to keep honest.
     */
    @Test
    fun chunkSizeNeverRoundsPastTheLinkOrBelowOneByte() {
        assertEquals(11, OtaFrames.maxChunk(18))
        assertEquals(12, OtaFrames.maxChunk(19))
        // The frame on the wire is the chunk plus its four offset bytes, and that whole thing
        // must fit an ATT write at every MTU a link can actually negotiate.
        for (mtu in intArrayOf(23, 27, 65, 185, 247, 251, 512, 517)) {
            val chunk = OtaFrames.maxChunk(mtu)
            assertTrue("chunk $chunk does not fit an ATT write at MTU $mtu",
                chunk + OtaFrames.OFFSET_BYTES <= mtu - 3)
            assertTrue("chunk $chunk must advance the transfer", chunk >= 1)
        }
    }

    // ------------------------------------------------------------------
    // Frames
    // ------------------------------------------------------------------

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

    /**
     * A slice taken from the wrong place in the file is the quietest way to break an install: the
     * unit accepts it, the CRC matches nothing, and the failure surfaces as `crc` at the very end.
     * So the slice arithmetic is asserted directly, including the tail that does not fill a chunk.
     */
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
        // A chunk that divides exactly must not add an empty write at the end, and a chunk bigger
        // than the file is one short write, not a refusal.
        assertEquals(OtaSegment(100, 100), nextSegment(total = 200, chunk = 100, off = 100))
        assertEquals(OtaSegment(0, 200), nextSegment(total = 200, chunk = 500, off = 0))
        assertNull(nextSegment(total = 200, chunk = 100, off = 200))
        assertNull(nextSegment(total = 200, chunk = 100, off = 250))
        assertNull(nextSegment(total = 200, chunk = 100, off = -1))
    }

    /**
     * A resume starts where the unit confirmed, not where the phone stopped sending: writes can be
     * in flight when the link drops, and re-sending bytes the unit already has trips its offset
     * check and aborts a transfer that was nearly done. The segment at a resumed offset is
     * computed exactly as the interrupted one would have been.
     */
    @Test
    fun aResumeStartsAtTheConfirmedOffset() {
        assertEquals(OtaSegment(600, 300), nextSegment(total = 1000, chunk = 300, off = 600))
        assertEquals(OtaSegment(900, 100), nextSegment(total = 1000, chunk = 300, off = 900))
        assertNull(nextSegment(total = 1000, chunk = 300, off = 1000))
        assertNull(nextSegment(total = 1000, chunk = 300, off = 1200))
    }

    /**
     * A second `begin_ok` must not pull the phone back onto bytes the unit already has.
     *
     * The unit answers `ota.begin` in the RPC reply and again as an event, and the event lands after
     * the loop has already moved past the first write. On the bench unit on 2026-11-02 a duplicate
     * `begin_ok` at offset 0 arrived 0.4 s into a 1.5 MB install, the phone re-sent the first frame,
     * and the unit ended the install at 496 bytes with `offset_mismatch`. Before the first write the
     * event is the resume answer and is taken; after it, the phone's position wins and the unit's
     * real position is recovered from ACKs / `ota.status` instead.
     */
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
        // The standard CRC-32 check value: ASCII "123456789" is 0xCBF43926, which is what
        // Arduino-esp32's own CRC32 class documents. Compared as an unsigned 32-bit value.
        val probe = OtaImages.probe("123456789".toByteArray())
        assertEquals(0xCBF43926L, probe.crc32)
        assertEquals(9, probe.size)
        assertNull(probe.versionTag)
        assertEquals(CRC32().apply { update("123456789".toByteArray()) }.value, probe.crc32)
        assertEquals(probe.crc32, OtaImages.crc32Of("123456789".byteInputStream()))
    }

    // ------------------------------------------------------------------
    // The DPPVER marker
    // ------------------------------------------------------------------

    /**
     * The marker is placed deliberately by `src/config.cpp` so a binary can be identified without a
     * manifest, and `scripts/fix_upload_layout.py` reads the same bytes on a wired flash. The app
     * reads it for the same reason: `firmware.bin` is what every build folder calls its output, so
     * the name is worth nothing and the marker is worth a lot.
     */
    @Test
    fun versionMarkerIsFoundWhereFirmwarePutsIt() {
        val image = ByteArray(4096)
        for (i in image.indices) image[i] = (i % 251).toByte()
        "DPPVER=1.5.1".toByteArray().copyInto(image, 1024)
        val probe = OtaImages.probe(image)
        assertEquals("1.5.1", probe.versionTag)
        assertEquals(4096, probe.size)
    }

    /**
     * The tag ends at the first byte that cannot belong to a version, so a stray `DPPVER=` sitting
     * in the middle of a rodata block cannot read across that block and print nonsense on the
     * firmware card next to somebody's finger.
     */
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

    // ------------------------------------------------------------------
    // Unit -> phone frames
    // ------------------------------------------------------------------

    /**
     * These four shapes are the whole conversation between the unit and this app while bytes are
     * moving. A frame the app cannot read is dropped, and a dropped ACK looks like a stalled
     * transfer — so parsing is asserted frame by frame.
     */
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

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /**
     * The bar is the only thing telling the driver an install is alive. It advances on the unit's
     * confirmation, never on the phone's hope — a bar that runs ahead of the unit is a bar that has
     * to be seen going backwards when the unit finally answers where it really is.
     */
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

    /**
     * A resume re-runs `begin_ok` carrying the unit's confirmed offset. The tracker has to take that
     * offset as the truth — it is the unit that holds the CRC state — otherwise a resumed transfer
     * reads as if it had lost every byte it already sent.
     */
    @Test
    fun aResumeAdoptsTheUnitsOffset() {
        val t = OtaTracker(total = 1000)
        t.apply(OtaEvent.BeginOk(chunk = 300, off = 0))
        t.wrote(900)
        t.apply(OtaEvent.Ack(off = 600))
        assertEquals(600, t.acked)
        t.apply(OtaEvent.BeginOk(chunk = 300, off = 600))
        assertEquals(600, t.acked)
        // A unit answering an offset ahead of our last ACK is not a contradiction: it means ACK
        // frames lagged behind writes, and the unit is further along than we knew.
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
        // The unit's own offset is the honest distance: it is ahead of the last ACK whenever the
        // failure landed between two ACK windows.
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
        // 1000 bytes over the 1500 ms since the transfer began — not the 1000 B/s the last ACK
        // window alone would read. A BLE link breathes with the connection interval, and an ETA
        // built from two ACK frames jumps around enough to be useless next to a driver's finger.
        assertEquals(666.667, t.bytesPerSecond(), 0.01)

        // An ETA only answers when there is a rate to project from.
        val view = OtaView(stage = OtaStage.Sending, total = 1000, acked = 1000, bytesPerSecond = 1000.0)
        assertEquals(0, view.etaSeconds())
        val half = OtaView(stage = OtaStage.Sending, total = 1000, acked = 500, bytesPerSecond = 250.0)
        assertEquals(2, half.etaSeconds())
        assertNull(OtaView(total = 1000, acked = 500).etaSeconds())
        assertNull(OtaView(total = 0, acked = 0, bytesPerSecond = 100.0).etaSeconds())
    }

    /**
     * The install's own duration and write count, measured on the same window as the byte rate.
     *
     * These are the numbers a firmware install is diagnosed from when there is no bench attached:
     * the 4 min 16 s / 3 214 write baseline that started `INSTALL-SPEED-PLAN-2026-11-03` was worked
     * out by dividing a duration by a write count (79 ms per write), and that division is only
     * honest if the app itself kept both numbers. A re-send after a hiccup counts as a write
     * because it costs the link a write; the progress figure already covering that ground does not
     * make the airtime free.
     */
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
        // The window opens at the begin_ok, not at the first write: the handshake is part of what
        // a driver waits for, and the unit's own pacing lives inside it.
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

        // A re-send is a write. It is the number the plan's "time per write" figure is built from,
        // and it is exactly what the write-pipeline task (A3) is trying to lower.
        val r = OtaTracker(total = 1000, clock = { nowMs })
        r.apply(OtaEvent.BeginOk(chunk = 100, off = 0))
        r.wrote(100)
        r.wrote(100)
        assertEquals(100, r.sent)
        assertEquals(2, r.writes)
    }

    /**
     * A resumed transfer has a window too, and it must not read as a division by zero or a
     * negative duration: the card keeps printing a rate next to "press RESUME", and a person
     * decides whether to bother resuming on the strength of it.
     */
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

    // ------------------------------------------------------------------
    // Explanations
    // ------------------------------------------------------------------

    /**
     * Every code the unit can send has to have a sentence that says what happened in the driver's
     * terms. A code that falls through to the generic line means somebody added a code on the
     * firmware side and nobody translated it for the person standing at the car — which is exactly
     * the kind of change that ships when the firmware and the app are edited in different sittings.
     */
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

    /**
     * The sentence a driver is waiting for is "is my dashboard all right?". Every code that leaves
     * the running firmware in place says so — and the codes that mean the transfer has to be done
     * again say that too, because "try again" beats staring at a dead progress bar.
     */
    @Test
    fun failuresSayWhatHappenedToTheUnit() {
        for (code in listOf("crc", "no_space", "bad_magic", "end_failed", "stalled", "aborted")) {
            assertTrue("'$code' does not say the unit is safe", OtaErrors.explain(code).contains("Unit unchanged"))
        }
        assertTrue(OtaErrors.needsAction("crc"))
        assertTrue(OtaErrors.needsAction("bad_magic"))
        assertTrue(OtaErrors.needsAction("offset_mismatch"))
        assertTrue(!OtaErrors.needsAction("already_running"))
        assertTrue(!OtaErrors.needsAction("aborted"))
        // The resume window the sentence promises is checked against the firmware's own number in
        // `resumeWindowAndPacingMatchTheFirmware`, which is the only place it can be verified.
    }

    /**
     * The two sides of the resume promise have to be the same number.
     *
     * The card tells a person the unit holds an install open for a while, and RESUME is offered on
     * the strength of that sentence. The number that decides it lives in the firmware as
     * `OTA_SILENCE_MS`, so this reads it out of `src/bt_ota.cpp` and compares: changing one side
     * without the other fails here rather than stranding a resumable install (on the bench on
     * 2026-11-02 the unit closed an install at 60 s while the phone still showed it as paused).
     * The second half is the pacing ceiling against the unit's handoff ring: the phone may never
     * run more frames ahead of its ACKs than the ring can hold — the 32 KiB ceiling that predates
     * this test was 65 frames against a 7-frame ring, and the bench got the resulting
     * `bad_frame ring full` abort on 2026-11-13 — while still staying above the unit's ACK
     * cadence (half the usable ring), or a well-behaved transfer would wait forever on an ACK
     * that is never sent.
     */
    @Test
    fun resumeWindowAndPacingMatchTheFirmware() {
        val firmware = firmwareSource("src/bt_ota.cpp")
        val silence = Regex("""OTA_SILENCE_MS\s*=\s*(\d+)""").find(firmware)
            ?: error("OTA_SILENCE_MS not found in src/bt_ota.cpp")
        assertEquals("the app's resume window must be the unit's silence window",
            silence.groupValues[1].toLong(), OtaController.RESUME_WINDOW_MS)
        // The same promise, in words, in the sentence a driver reads.
        assertTrue(OtaErrors.explain("link_lost").contains("two minutes"))

        val ring = Regex("""OTA_RING_SLOTS\s*=\s*(\d+)""").find(firmware)
            ?: error("OTA_RING_SLOTS not found in src/bt_ota.cpp")
        val slots = ring.groupValues[1].toInt()
        assertTrue(
            "the phone paces at ${OtaController.RING_PACE_FRAMES} unacked frames, but the unit's ring " +
                "holds ${slots - 1} — one stalled drain pass would overflow it",
            OtaController.RING_PACE_FRAMES < slots - 1,
        )
        assertTrue(
            "the unit acks every ${(slots - 1) / 2} frames at the smallest, the phone waits for " +
                "${OtaController.RING_PACE_FRAMES} — the transfer would stall waiting for an ACK",
            (slots - 1) / 2 <= OtaController.RING_PACE_FRAMES,
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
