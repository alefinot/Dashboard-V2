package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app asks the unit "where are you?" only when something has gone quiet — never on a timer.
 *
 * An `ota.status` request is a 34-byte RPC round trip on the same ATT opportunity the chunk stream
 * is using, and the unit ACKs every `OTA_ACK_BYTES` (4 KiB, `src/bt_ota.cpp`) whether or not the
 * phone asks. Polling during a healthy transfer therefore buys a second, redundant answer to a
 * question already answered, and takes the slot a chunk could have had: task A4 of
 * `INSTALL-SPEED-PLAN-2026-11-03` exists to stop that.
 *
 * On the tree this guard was written against, the behaviour is already right: `api.otaStatus()` is
 * called from exactly two places, and both sit behind a condition that says the ACK stream or a
 * write went quiet (the retry branch after a write the stack did not complete, and the
 * `STALL_SILENCE_MS` branch after silence). The plan described a timer that does not exist here.
 * So the value of this test is the rule, not a behaviour change: the day somebody adds a
 * "refresh progress every second" poll to the install, or a status timer in the card, the run goes
 * red and names the file.
 *
 * It reads the sources because nothing at test time holds a radio or a frame counter, and counting
 * frames on a bench capture is a manual step (A5), not a unit test.
 */
class OtaStatusPollStaysQuietTest {

    /** Every way the app can ask the unit where an install stands (the definition itself excluded). */
    private val statusCall = Regex("\\.otaStatus\\s*\\(")

    /** The two conditions that justify asking: a write that failed, or an ACK stream gone quiet. */
    private val justified = listOf("writeFailures", "STALL_SILENCE_MS")

    @Test
    fun theUnitIsAskedForItsOffsetOnlyWhenSomethingWentQuiet() {
        val body = codeOnly(mainSource("ble/OtaController.kt"))
        val lines = body.lines()

        // Split on the call itself: `body` keeps line structure because codeOnly blanks comments
        // rather than removing them.
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

    /**
     * The card draws progress from the controller's StateFlow. If it started asking the unit for
     * it, the poll would be running on the UI's clock with no idea that a chunk phase is in
     * progress — exactly the self-competition A4 is about.
     *
     * Since 2026-11-09 the card also has a GitHub version dropdown, and the same discipline
     * applies to the new half: the fetch lives in `update/FirmwareReleases.kt`, not here, under
     * that object's rate-limit rules. So the card is checked twice over — it starts no clocks,
     * and it holds no network. An `https://` or an okhttp import in this file would mean a
     * second fetcher no discipline governs, drawing its own progress on somebody else's clock.
     */
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
        // And no network either: the card draws what FirmwareReleases publishes.
        assertFalse(
            "the firmware card names a URL; the release fetch belongs to update/FirmwareReleases.kt",
            card.contains("https://"),
        )
        assertFalse(
            "the firmware card holds an HTTP client of its own",
            card.contains("okhttp3"),
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
