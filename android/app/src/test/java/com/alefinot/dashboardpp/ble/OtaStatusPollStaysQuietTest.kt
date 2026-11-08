package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaStatusPollStaysQuietTest {

    /** Every way the app can ask the unit where an install stands (the definition itself excluded). */
    private val statusCall = Regex("\\.otaStatus\\s*\\(")

    /** The two conditions that justify asking: a write that failed, or an ACK stream gone quiet. */
    private val justified = listOf("writeFailures", "STALL_SILENCE_MS")

    @Test
    fun theUnitIsAskedForItsOffsetOnlyWhenSomethingWentQuiet() {
        val body = codeOnly(mainSource("ble/OtaController.kt"))
        val lines = body.lines()

        val callLines = lines.indices.filter { statusCall.containsMatchIn(lines[it]) }
        assertEquals(
            "expected exactly the two known ota.status call sites (write-failure recovery, and the " +
                "silence path); a third means a poll grew somewhere: " + callLines.map { it + 1 },
            2,
            callLines.size,
        )
        for (line in callLines) {
            val window = lines.drop(maxOf(0, line - 12)).take(12 + 1)
            assertTrue(
                "an ota.status request must sit behind a write failure or an ACK-silence check, " +
                    "not behind a timer. Line ${line + 1}: ${lines[line].trim()}",
                window.any { l -> justified.any { l.contains(it) } },
            )
        }
    }

    @Test
    fun theFirmwareCardAsksNothing() {
        val card = codeOnly(mainSource("ui/screens/FirmwareOtaControl.kt"))
        assertFalse(
            "the firmware card must not ask the unit for its offset - it reads OtaController.view",
            statusCall.containsMatchIn(card),
        )
        assertFalse(
            "and it must not run a timer of its own against the transfer",
            Regex("\\b(while\\s*\\(|delay\\s*\\(|flowWithLatestValue)").containsMatchIn(card),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun mainSource(relative: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp"), relative)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    private fun codeOnly(src: String): String = src
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    /** Same walking-upward search as `ConnectActionsOnOnePageTest.moduleDir`. */
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
