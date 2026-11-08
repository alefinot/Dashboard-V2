package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
