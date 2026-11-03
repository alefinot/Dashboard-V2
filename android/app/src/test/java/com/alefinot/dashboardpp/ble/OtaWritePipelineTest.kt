package com.alefinot.dashboardpp.ble

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How deep the phone may keep its firmware-write pipe, and what stops it from digging the unit's
 * grave with that depth.
 *
 * Task A3 of `INSTALL-SPEED-PLAN-2026-11-03`: the bench measured the app putting one 496-byte
 * write on the wire every ~23 ms, while the unit's handoff ring peaked at 2-3 of its 7 usable
 * slots and its flash drain waited up to 125 ms between passes. The unit is starved, not
 * saturated — the ceiling is the phone waiting for each write's callback before issuing the next.
 * So the phone is allowed to keep several writes in the stack at once.
 *
 * The depth is not free. `src/bt_ota.cpp` refuses a frame when its ring is full and answers with
 * `bad_frame ring full`, which **ends the install** — the ring is 8 slots, 7 usable, and nothing
 * on the unit protects it from a phone that runs too far ahead (the unit's own comment says so:
 * "Nothing about the ACK cadence protects this ring"). So the rule this test holds is the one that
 * makes A3 safe on a phone-only change:
 *
 *  - the bytes the phone has sent but not been ACKed for must stay **under** the unit's ring, and
 *  - the pipe it fills in one pass must fit inside that same window.
 *
 * The pipe depth itself is a knob (`OtaPump.PIPE_FRAMES`); the invariant is that it can never ask
 * for more than the pace window allows.
 */
class OtaWritePipelineTest {

    @Test
    fun paceWindowStaysInsideTheUnitRing() {
        val chunk = 496
        val ringBytes = OtaPump.UNIT_RING_SLOTS * chunk
        val pace = OtaPump.paceLimitBytes(chunk)
        assertTrue(
            "unacked bytes ($pace) must be strictly less than the unit's whole ring ($ringBytes): " +
                "a frame the unit has not ACKed yet is either in its ring or still in the air, so a " +
                "window as wide as the ring can fill it and `bad_frame ring full` kills the install",
            pace < ringBytes,
        )
        assertEquals(
            "one slot of the ring is deliberately left empty as the wrap guard - the unit's feed " +
                "refuses the frame that would close the ring",
            (OtaPump.UNIT_RING_SLOTS - 1) * chunk,
            pace,
        )
    }

    @Test
    fun paceWindowStillClearsTheUnitsAckCadence() {
        // The unit ACKs every OTA_ACK_BYTES (4 KiB) or half its ring, whichever is smaller: at a
        // 496-byte chunk that is 1736 B. A pace window below the cadence would deadlock - the
        // phone waits for an ACK that only fires after bytes the phone is not allowed to send.
        val chunk = 496
        val unitAckCadence = minOf(4096, OtaPump.UNIT_RING_SLOTS * chunk / 2)
        assertTrue(
            "the pace window (${OtaPump.paceLimitBytes(chunk)}) must be at least the unit's ACK " +
                "cadence ($unitAckCadence B), or the two sides stop talking",
            OtaPump.paceLimitBytes(chunk) >= unitAckCadence,
        )
    }

    @Test
    fun oneFillPassCannotBlowPastTheWindow() {
        val chunk = 496
        val pace = OtaPump.paceLimitBytes(chunk)
        assertTrue(
            "a pipe of ${OtaPump.PIPE_FRAMES} x $chunk B must fit inside the pace window ($pace B), " +
                "otherwise a single fill pass runs past the unit's cushion before the pace check " +
                "gets a look in",
            OtaPump.PIPE_FRAMES * chunk <= pace,
        )
    }

    @Test
    fun thePipeIsFiniteAndSmall() {
        assertTrue(
            "PIPE_FRAMES = ${OtaPump.PIPE_FRAMES}: the unit's ring is ${OtaPump.UNIT_RING_SLOTS} " +
                "usable frames, so a pipe at or past that depth is an install waiting to die",
            OtaPump.PIPE_FRAMES in 2 until OtaPump.UNIT_RING_SLOTS,
        )
    }

    @Test
    fun issueStopsWhenThePipeIsFullOrTheUnitIsBehind() {
        val chunk = 496
        val pace = OtaPump.paceLimitBytes(chunk)
        assertTrue(OtaPump.canIssue(inFlight = 0, unacked = 0, chunk = chunk))
        assertFalse(
            "a full pipe means the next thing to do is wait for a write callback, not queue another",
            OtaPump.canIssue(inFlight = OtaPump.PIPE_FRAMES, unacked = 0, chunk = chunk),
        )
        assertFalse(
            "at the pace window the unit's flash is behind the phone's sends - wait for its ACK",
            OtaPump.canIssue(inFlight = 0, unacked = pace, chunk = chunk),
        )
        assertTrue(OtaPump.canIssue(inFlight = OtaPump.PIPE_FRAMES - 1, unacked = pace - 1, chunk = chunk))
    }

    // ------------------------------------------------------------------ structure guards

    @Test
    fun theChunkLoopFillsAPipeInsteadOfWritingOneAtATime() {
        val controller = codeOnly(mainSource("OtaController.kt"))
        assertTrue(
            "the transfer must hand frames to the stack with otaWriteStart and let completions come " +
                "back on their own - the sequential otaWrite() await is what A3 replaces",
            Regex("\\botaWriteStart\\(").containsMatchIn(controller),
        )
        assertFalse(
            "no sequential one-write-await left in the chunk loop: it re-serialises the pipe",
            Regex("\\blink\\.otaWrite\\(").containsMatchIn(controller),
        )
        assertTrue(
            "the pump has to ask how many writes are still in the stack (otaWritePending) or it has " +
                "no idea whether the pipe is full",
            Regex("\\botaWritePending\\(").containsMatchIn(controller),
        )
    }

    @Test
    fun thePipeIsEmptiedBeforeTheUnitIsToldTheImageIsOver() {
        val controller = codeOnly(mainSource("OtaController.kt"))
        val drained = Regex("otaWritePending\\(\\)\\s*==\\s*0").find(controller)
        val end = Regex("\\bapi\\.otaEnd\\(\\)").find(controller)
        assertTrue("the loop must wait for the pipe before ota.end", drained != null && end != null)
        assertTrue(
            "OTA writes and the RPC that ends the install go to different characteristics, so their " +
                "order on the link is not guaranteed: everything in the stack must have left it " +
                "before ota.end makes the unit count bytes",
            drained!!.range.first < end!!.range.first,
        )
    }

    @Test
    fun theLinkCountsWriteCompletionsInsteadOfOneDeferred() {
        val link = codeOnly(mainSource("BleLink.kt"))
        assertTrue(
            "onCharacteristicWrite must route an OTA-characteristic completion to the pipe counter, " +
                "not to a single armed deferred - with several writes in flight a lone deferred " +
                "completes the wrong one",
            Regex("if \\(characteristic\\.uuid == OTA_UUID\\)").containsMatchIn(link) ||
                Regex("characteristic\\.uuid\\s*==\\s*OTA_UUID").containsMatchIn(link),
        )
        assertTrue(
            "the link must refuse (not silently queue) writes past its own ceiling, so the pump " +
                "backs off instead of growing a queue nobody can flush",
            Regex("\\bMAX_OTA_IN_FLIGHT\\b").containsMatchIn(link),
        )
    }

    @Test
    fun thePipeChangesNothingOnTheWire() {
        // A3 is a phone-side change: no new OTA field, no rule 17 surface (AGENTS.md).
        val core = codeOnly(mainSource("OtaCore.kt"))
        val link = codeOnly(mainSource("BleLink.kt"))
        assertFalse(
            "the pipe is a phone-side pacing decision - do not tell the unit about it in a frame",
            Regex("\"(pipe|in_flight|inflight|pipeline|depth)\"").containsMatchIn(core + link),
        )
        assertEquals(
            "the frame shape is untouched: offset prefix plus payload, no extra header byte",
            OtaFrames.OFFSET_BYTES,
            OtaFrames.data(0, ByteArray(8), 0, 8).size - 8,
        )
    }

    // ---- settledOffset: the unit's offset is only trusted once it stops moving ----
    //
    // This is the bug A3's first bench run died of (2026-11-04, `offset_mismatch: write at 396304,
    // unit expects 396800`): the phone asked the unit where the image was, got an answer from before
    // the unit's handoff ring had drained, continued from it, and re-sent frames the unit had already
    // accepted. One write at a time can lag the unit's report by one frame; a pipe lags by its depth.

    @Test
    fun `the unit's offset is read again while it keeps climbing`() {
        val answers = ArrayDeque(listOf(100, 300, 792, 792, 792))
        var reads = 0
        val off = runBlocking { settledOffset(read = { answers.removeFirst().also { reads++ } }, settleMs = 1L) }
        assertEquals("the settled answer is the one that stopped moving", 792, off ?: -1)
        assertEquals("stops reading as soon as the number stands still", 4, reads)
    }

    @Test
    fun `a unit that never answers is a failure, not a convenient zero`() {
        var reads = 0
        val off = runBlocking { settledOffset(read = { reads++; null }, settleMs = 1L) }
        assertEquals(null, off)
        assertEquals("gives up on the first refusal so the card can pause the install", 1, reads)
    }

    @Test
    fun `an offset that never settles still answers with the newest reading`() {
        var n = 0
        val off = runBlocking { settledOffset(read = { (++n) * 100 }, attempts = 3, settleMs = 1L) }
        assertEquals(300, off ?: -1)
    }

    @Test
    fun `a settle wait outlasts the unit's ring drain`() {
        val src = codeOnly(mainSource("OtaCore.kt"))
        val wait = Regex("settleMs: Long = (\\d+)L").find(src)?.groupValues?.get(1)?.toLong()
        assertNotNull("settledOffset must say how long a settle wait is", wait)
        assertTrue(
            "the bench measures up to ~125 ms between ring drains, so a shorter wait only re-reads " +
                "the same stale number (saw $wait ms)",
            wait!! >= 250L,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun mainSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    private fun codeOnly(src: String): String = src
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    private fun moduleDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/ble"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (cand in listOf(File(dir, "android/app"), File(dir, "app"), dir)) {
                if (File(cand, marker).isDirectory) return cand
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "cannot reach the app module walking up from " + System.getProperty("user.dir"),
        )
    }
}
