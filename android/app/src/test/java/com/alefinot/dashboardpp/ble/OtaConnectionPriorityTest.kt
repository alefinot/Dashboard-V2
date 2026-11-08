package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the phone is allowed to ask for the fast BLE connection interval, and where it is obliged
 * to hand the ordinary one back.
 *
 * `CONNECTION_PRIORITY_HIGH` is the largest single multiplier on a firmware push (plan
 * `INSTALL-SPEED-PLAN-2026-11-03`, task A2): the ordinary `BALANCED` interval is 30-50 ms, the fast
 * one 11.25-15 ms, and the bench install ran one 496-byte write every 79 ms. It is also the change
 * with the nastiest failure mode in this app — there are field reports of OEM stacks tearing down a
 * link that is held on HIGH, and a link torn down mid-install costs the driver a RESUME press
 * inside the unit's silence window. So the rule this guard holds is narrow on purpose: **ask for it
 * for the chunk phase of an install, and nowhere else; give it back on every path out of that
 * phase.**
 *
 * Nothing at test time can hold a radio, and Android exposes no way to read back the interval that
 * was actually agreed, so the only honest check is structural: which files call it, and how often.
 * A guard that could see the negotiated interval would be better; there is no such API, and
 * pretending otherwise would produce a test that asserts a value this app can never observe.
 */
class OtaConnectionPriorityTest {

    /** The one place the stack may be asked for the fast interval. */
    private val highAsk = Regex("\\bCONNECTION_PRIORITY_HIGH\\b")

    /** The transfer's own request, counted where it is actually called. */
    private val highCall = Regex("\\bsetConnectionPriority\\(\\s*true\\s*\\)")

    /** Every way the ordinary interval gets handed back, from either side of the boundary. */
    private val balancedBack = Regex("(set|apply)ConnectionPriority\\(\\s*false\\s*\\)")

    @Test
    fun theFastIntervalIsAskedForInOnePlaceOnly() {
        val files = mainSources()
        val askers = files.filter { highAsk.containsMatchIn(codeOnly(it.readText())) }.map { it.name }
        assertEquals(
            "only the link may talk to the stack about connection priority — if a screen starts " +
                "asking for the fast interval, somebody is holding HIGH outside an install",
            listOf("BleLink.kt"),
            askers.sorted(),
        )

        val calls = files.flatMap { highCall.findAll(codeOnly(it.readText())).map { m -> it.name } }
        assertEquals(
            "exactly one call site may raise the interval, and it must be the install's chunk phase " +
                "in OtaController — an install that asks twice, or a screen that asks, is how HIGH " +
                "ends up held for the life of the link",
            listOf("OtaController.kt"),
            calls.sorted(),
        )
    }

    /**
     * The part that actually protects a driver: an install that ends by any route — accepted,
     * refused, paused, aborted, exception, or a link that simply fell over — has to leave the phone
     * back on the ordinary interval. Both sides of the boundary carry a net: the transfer's own
     * `finally`, and the link's teardown, which every disconnect runs through.
     */
    @Test
    fun theOrdinaryIntervalIsHandedBackFromBothSides() {
        val bleLink = codeOnly(mainSource("BleLink.kt"))
        val controller = codeOnly(mainSource("OtaController.kt"))

        assertTrue(
            "BleLink must give the ordinary interval back on teardown (closeGatt) — a link that " +
                "dies mid-install cannot leave the phone holding HIGH",
            balancedBack.containsMatchIn(bleLink),
        )
        assertTrue(
            "OtaController must give the ordinary interval back when the chunk phase ends, whatever " +
                "ended it (its `finally`), and when the driver aborts",
            balancedBack.findAll(controller).count() >= 2,
        )
    }

    /**
     * A2 is a phone-side change with no wire change. If any of this reaches the unit, the plan's
     * "no rule 17 surface" boundary is broken and the bench capture no longer matches the app.
     */
    @Test
    fun askingForAnIntervalSendsNothingToTheUnit() {
        val bleLink = codeOnly(mainSource("BleLink.kt"))
        assertFalse(
            "connection priority is a link parameter, not a protocol field — do not add it to a frame",
            Regex("\"(prio|priority|conn_interval|interval)\"").containsMatchIn(bleLink),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun mainSources(): List<File> {
        val dir = File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble")
        val kt = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
        assertTrue("no .kt files under $dir", !kt.isNullOrEmpty())
        return kt!!.toList()
    }

    private fun mainSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    /** Comments stripped: half of A2's reasoning about interval numbers lives in prose. */
    private fun codeOnly(src: String): String = src
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    /**
     * Gradle runs a module's tests with that module as the working directory; the CLI and the IDE
     * disagree about it, so each level upward is tried. Same reasoning as
     * `ConnectActionsOnOnePageTest.moduleDir` — a guard that cannot find its own input is worse than
     * no guard, because it teaches you to ignore a red run.
     */
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
