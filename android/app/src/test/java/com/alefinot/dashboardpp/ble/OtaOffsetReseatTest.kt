package com.alefinot.dashboardpp.ble

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaOffsetReseatTest {

    // ---- settledOffset: only a reading that stopped moving is a position ----

    @Test
    fun `the unit's offset is read again while it keeps climbing`() {
        val answers = ArrayDeque(listOf(100, 300, 792, 792, 792))
        var reads = 0
        val off = runBlocking { settledOffset(read = { answers.removeFirst().also { reads++ } }, settleMs = 1L) }
        assertEquals("the settled answer is the reading that stopped moving", 792, off ?: -1)
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
        val off = runBlocking {
            settledOffset(read = { (++n) * 100 }, attempts = 3, settleMs = 1L)
        }
        assertEquals("the newest reading is the one that re-sends the least", 300, off ?: -1)
    }

    @Test
    fun `a settle wait outlasts the unit's ring drain`() {
        val wait = Regex("settleMs: Long = (\\d+)L")
            .find(mainSource("OtaCore.kt"))?.groupValues?.get(1)?.toLong()
        assertNotNull("settledOffset must say how long a settle wait is", wait)
        assertTrue(
            "the bench measures up to ~125 ms between ring drains, so a shorter wait only re-reads " +
                "the same stale number (saw $wait ms)",
            wait!! >= 250L,
        )
    }

    // ---- both recovery paths go through it, and neither writes below the unit's answer ----

    @Test
    fun `both stall recoveries ask the unit the same way`() {
        val src = mainSource("OtaController.kt")
        assertEquals(
            "the write-failure recovery and the no-ACK recovery are the two places that reseat the " +
                "offset, and both must read until the unit's number settles",
            2,
            Regex("\\bsettledOffset\\(").findAll(src).count(),
        )
    }

    @Test
    fun `no recovery feeds a single status reading straight into the write offset`() {
        val src = mainSource("OtaController.kt")
        assertFalse(
            "a one-shot `off` from ota.status may be behind frames still in the unit's handoff ring",
            Regex("""nextOff = \(BleJson\.int\(status\.fields, "off"\)""").containsMatchIn(src),
        )
        assertFalse(
            "the no-ACK path must not reseat from a single reading either",
            Regex("""val off = \(BleJson\.int\(status\.fields, "off"\) \?: tracker\.acked\)""").containsMatchIn(src),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun mainSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    private fun moduleDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/ble"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
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
