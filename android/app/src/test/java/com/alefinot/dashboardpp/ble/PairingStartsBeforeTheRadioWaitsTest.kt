package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two bench findings from 2026-11-09, both about the same pairing.
 *
 * First: the unit gives the peer three seconds to start SMP (`BT_PAIR_GRACE_MS`) and then
 * sends a security request of its own. A phone pairing request that landed after that was
 * never answered on this bench - twice the app sat in `BOND_BONDING` for 30 s, showed
 * `PAIRING — TYPE THE CODE SHOWN ON THE DASHBOARD`, and no code ever reached the panel.
 * The MTU exchange is refused often enough on this link that waiting for it (or its 5 s
 * timeout) before `createBond()` reliably pushed the pairing request outside the window:
 * the sequence order *was* the fresh-pairing failure. `src/bt.cpp` states the contract in
 * words — "createBond() about a second after the link is up" — so the order is guarded
 * here rather than left to taste.
 *
 * Second: with the two halves of a bond disagreeing, Android reports `BOND_BONDED` and the
 * Security Manager stays silent - protected writes simply time out, and the attempt died
 * as `did not answer — check power and range` about a unit that was answering the whole
 * time. The fix is one question to the Security Manager (`kickSecurity`) before blaming
 * the pairing, and a sentence that keeps the reader on the pairing.
 */
class PairingStartsBeforeTheRadioWaitsTest {

    @Test
    fun pairingIsDialedBeforeTheMtuWaitSoItLandsInsideTheUnitsGraceWindow() {
        val src = codeOnly(mainSource("BleLink.kt"))
        val connect = connectBody(src)
        val bond = connect.indexOf("bond(device)")
        val mtu = connect.indexOf("requestMtu(")
        assertTrue("connect() must bond", bond >= 0)
        assertTrue("connect() must negotiate the MTU", mtu >= 0)
        assertTrue("createBond must go out before the MTU exchange waits 5 s: bond=$bond mtu=$mtu", bond < mtu)
        // The state the screen reads while the person types the code - it moved with the call.
        val pairing = connect.indexOf("BleLinkState.Pairing(name)")
        assertTrue("the Pairing state must bracket the bond call", pairing in 0 until bond)
    }

    @Test
    fun aStalledSecureLinkIsAskedOnceAndThenNamedHonestly() {
        val src = codeOnly(mainSource("BleLink.kt"))
        val connect = connectBody(src)
        // Both protected subscriptions get the kick-and-retry treatment.
        assertTrue(
            "telemetry subscribe must retry after a security kick",
            Regex("if \\(!subscribe\\(client, telem\\)\\)\\s*\\{\\s*kickSecurity\\(device\\)").containsMatchIn(connect),
        )
        assertTrue(
            "rpc subscribe must retry after a security kick",
            Regex("if \\(!subscribe\\(client, rpc\\)\\)\\s*\\{\\s*kickSecurity\\(device\\)").containsMatchIn(connect),
        )
        // And the failure that survives the retry is the pairing, not a phantom range problem.
        assertTrue(
            "the surviving failure must be security_stalled",
            Regex("IOException\\(\"security_stalled\"\\)").containsMatchIn(connect),
        )
        // The kick is one public call, not a hidden-API negotiation.
        val kick = src.substringAfter("private fun kickSecurity(")
        assertTrue("kickSecurity must go through createBond", kick.contains("device.createBond()"))
    }

    @Test
    fun theStalledPairingSentenceKeepsTheReaderOnThePairing() {
        val text = linkFailureText("security_stalled")
        assertTrue("the line must name pairing: $text", text.contains("pairing", true))
        assertTrue("the line must give the fix: $text", text.contains("clear", true))
        // The whole point: this failure is a unit that ANSWERED. Sending the reader to the
        // power cable or the driveway is the misdiagnosis this test exists to stop.
        for (away in listOf("power", "range", "closer", "move", "restart", "off")) {
            assertFalse("the pairing sentence sends the reader away ($away): $text", text.contains(away, true))
        }
        // An unknown code must still read as unknown, never borrow this sentence.
        assertTrue(
            "an unknown code must read as unknown",
            linkFailureText("brand_new_code").contains("brand_new_code"),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Just the connect sequence - guards must not match prose in a sibling method. */
    private fun connectBody(src: String): String =
        src.substringAfter("suspend fun connect(").substringBefore("suspend fun request(")

    /** Comments stripped: this guard is about the code path, and the reasoning is in prose. */
    private fun codeOnly(src: String): String = src
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    private fun mainSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    /** Same walk-up-the-tree helper the other source guards use, for the same reason. */
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
