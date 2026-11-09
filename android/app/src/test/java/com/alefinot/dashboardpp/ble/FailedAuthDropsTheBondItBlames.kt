package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failed authentication costs the unit's bond table, and what it must not cost.
 *
 * `btDropFailedBond()` in `src/bt.cpp` is the unit's one chance to repair a pairing that only one
 * side still believes in, so its two mistakes are both expensive and both happened on the bench:
 * deleting a bond on a failure that only said *the link died* (2026-11-09, morning — the app's own
 * 20 s connect timeout arriving as `reason 102`, every lap forgetting one more good phone), and
 * keeping a bond on a failure that did mean the keys (2026-11-09, evening —
 * `BT: bond kept - reason 85 says the link died, not the keys`, while the unit held four bonds, the
 * phone held none, and nothing about that link had died: it stayed up 52 s and refused nothing).
 *
 * The second one was a comment. The list said `ESP_AUTH_SMP_DHKEY_CHK_FAIL // 85`, so reason 85
 * looked covered. It is not: this enum starts at `ESP_AUTH_SMP_PASSKEY_FAIL = 78`, which makes 85
 * `ESP_AUTH_SMP_UNKNOWN_ERR` and 88 `DHKEY_CHK_FAIL`. So the numbers are pinned here as data,
 * taken from `esp_gap_ble_api.h` in the installed core, and a comment that drifts off them reddens
 * this test instead of quietly unlocking a door the policy was written to keep shut.
 */
class FailedAuthDropsTheBondItBlames {

    /** From `esp_gap_ble_api.h`, `esp_ble_auth_fail_rsn_t`, in declaration order from 78. */
    private val reasonValue = linkedMapOf(
        "ESP_AUTH_SMP_PASSKEY_FAIL" to 78,
        "ESP_AUTH_SMP_OOB_FAIL" to 79,
        "ESP_AUTH_SMP_PAIR_AUTH_FAIL" to 80,
        "ESP_AUTH_SMP_CONFIRM_VALUE_FAIL" to 81,
        "ESP_AUTH_SMP_PAIR_NOT_SUPPORT" to 82,
        "ESP_AUTH_SMP_ENC_KEY_SIZE" to 83,
        "ESP_AUTH_SMP_INVALID_CMD" to 84,
        "ESP_AUTH_SMP_UNKNOWN_ERR" to 85,
        "ESP_AUTH_SMP_REPEATED_ATTEMPT" to 86,
        "ESP_AUTH_SMP_INVALID_PARAMETERS" to 87,
        "ESP_AUTH_SMP_DHKEY_CHK_FAIL" to 88,
        "ESP_AUTH_SMP_NUM_COMP_FAIL" to 89,
        "ESP_AUTH_SMP_BR_PARING_IN_PROGR" to 90,
        "ESP_AUTH_SMP_XTRANS_DERIVE_NOT_ALLOW" to 91,
        "ESP_AUTH_SMP_INTERNAL_ERR" to 92,
        "ESP_AUTH_SMP_UNKNOWN_IO" to 93,
        "ESP_AUTH_SMP_INIT_FAIL" to 94,
        "ESP_AUTH_SMP_CONFIRM_FAIL" to 95,
        "ESP_AUTH_SMP_BUSY" to 96,
        "ESP_AUTH_SMP_ENC_FAIL" to 97,
        "ESP_AUTH_SMP_STARTED" to 98,
        "ESP_AUTH_SMP_RSP_TIMEOUT" to 99,
        "ESP_AUTH_SMP_DIV_NOT_AVAIL" to 100,
        "ESP_AUTH_SMP_UNSPEC_ERR" to 101,
        "ESP_AUTH_SMP_CONN_TOUT" to 102,
    )

    /** A failure that can only be about the pairing, so the bond for that peer goes with it. */
    private val dropsBond = listOf(
        "ESP_AUTH_SMP_PASSKEY_FAIL",     // wrong digits typed
        "ESP_AUTH_SMP_PAIR_AUTH_FAIL",   // the requirements cannot be met by this pairing
        "ESP_AUTH_SMP_CONFIRM_VALUE_FAIL",
        "ESP_AUTH_SMP_ENC_KEY_SIZE",
        "ESP_AUTH_SMP_UNKNOWN_ERR",      // 85: what the bench actually got, twice, on a stale bond
        "ESP_AUTH_SMP_REPEATED_ATTEMPT",
        "ESP_AUTH_SMP_DHKEY_CHK_FAIL",
        "ESP_AUTH_SMP_NUM_COMP_FAIL",
        "ESP_AUTH_SMP_CONFIRM_FAIL",
        "ESP_AUTH_SMP_ENC_FAIL",         // 97: keys we hold, a peer that cannot answer with them
        "ESP_AUTH_SMP_UNSPEC_ERR",
    )

    /** A failure that says something about the link or about this stack, never about a key. */
    private val keepsBond = listOf(
        "ESP_AUTH_SMP_RSP_TIMEOUT",   // 99: the peer went quiet
        "ESP_AUTH_SMP_CONN_TOUT",     // 102: the link died - the morning's mistake
        "ESP_AUTH_SMP_BUSY",          // 96: a procedure is still running
        "ESP_AUTH_SMP_STARTED",       // 98: not a failure at all
        "ESP_AUTH_SMP_INTERNAL_ERR",  // 92: our own host, not the peer's keys
        "ESP_AUTH_SMP_INIT_FAIL",     // 94: ditto
        "ESP_AUTH_SMP_PAIR_NOT_SUPPORT", // 82: a device that never pairs
    )

    @Test
    fun onlyAFailureAboutTheKeysCostsTheBond() {
        val body = btSource().substringAfter("static void btDropFailedBond(").substringBefore("\n}")
        val condition = body.substringAfter("keysRejected =").substringBefore("if (!keysRejected")
        for (r in dropsBond) {
            assertTrue("$r must drop the bond", condition.contains(r))
        }
        for (r in keepsBond) {
            assertFalse("$r must NOT drop the bond", condition.contains(r))
        }
    }

    /**
     * Every reason named in the list must carry the number it really has. This is the guard whose
     * absence let `// 85` stand in for `DHKEY_CHK_FAIL` and leave a real lockout unfixed.
     */
    @Test
    fun everyReasonInTheListIsNumberedCorrectly() {
        val condition = btSource()
            .substringAfter("static void btDropFailedBond(")
            .substringAfter("keysRejected =")
            .substringBefore("if (!keysRejected")
            .lines()
        var checked = 0
        for (line in condition) {
            val named = Regex("ESP_AUTH_SMP_[A-Z_]+").find(line) ?: continue
            val commented = Regex("//\\s*(\\d+)\\s*:").find(line) ?: continue
            val name = named.value
            val stated = commented.groupValues[1].toInt()
            val actual = reasonValue[name]
            assertTrue("$name is not in esp_ble_auth_fail_rsn_t at all", actual != null)
            assertTrue(
                "$name is $actual, but the comment says $stated",
                stated == actual,
            )
            checked++
        }
        assertTrue("the list should be annotated, and it is not", checked >= dropsBond.size)
    }

    /**
     * The verdict is taken inside a Bluedroid callback, which is not a place that may block the
     * stack - the same rule that makes pairing resume from the pump rather than the GAP callback.
     * So the drop raises a flag and `btTask` closes the link; if a future edit calls
     * `esp_ble_gap_disconnect()` from the callback, this reddens.
     */
    @Test
    fun theLinkIsClosedByThePumpAndNotByTheSecurityCallback() {
        val src = btSource()
        val dropBody = src.substringAfter("static void btDropFailedBond(").substringBefore("\n}")
        assertTrue(
            "the drop must hand the teardown to the pump",
            dropBody.contains("s_closeAfterBondDrop = true"),
        )
        assertFalse(
            "and must not disconnect from inside the callback",
            dropBody.contains("esp_ble_gap_disconnect"),
        )
        val task = src.substringAfter("static void btTask(")
        assertTrue("btTask must consume the verdict", task.contains("if (s_closeAfterBondDrop)"))
        assertTrue("and do the disconnect there", task.contains("esp_ble_gap_disconnect(s_peerAddr)"))
    }

    /** Per-link state must not outlive the link, or a stale verdict closes the next phone's link. */
    @Test
    fun theVerdictDiesWithTheLink() {
        val close = btSource().substringAfter("static void btCloseLink(void)").substringBefore("\n}")
        assertTrue("btCloseLink must clear the pending verdict", close.contains("s_closeAfterBondDrop = false"))
    }

    // ------------------------------------------------------------------ helpers

    private fun btSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = File(dir, "src/bt.cpp")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot reach src/bt.cpp walking up from " + System.getProperty("user.dir"))
    }
}
