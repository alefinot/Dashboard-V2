package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRefusalNamesTheRightSideTest {

    @Test
    fun aScanAndroidWouldNotAnswerSaysSoAndGivesTheFix() {
        val text = scanFailureText("location_off")
        assertTrue("the refusal must name the switch: $text", text.contains("location switch", true))
        assertTrue("the refusal must give the fix: $text", text.contains("turn location on", true))
        // The whole point of the sentence: it must not walk the reader over to the unit.
        for (away in listOf("powered", "out of range", "restart", "re-pair", "another phone")) {
            assertFalse(
                "the location refusal sends the reader to the unit ($away): $text",
                text.contains(away, true),
            )
        }
    }

    @Test
    fun eachRadioRefusalKeepsItsOwnFix() {
        val codes = listOf("bluetooth_off", "no_bluetooth", "permission", "no_scanner", "location_off")
        val texts = codes.map { scanFailureText(it) }
        assertTrue("refusals must stay distinguishable: $texts", texts.distinct().size == codes.size)
        // And a code nobody wrote a sentence for must still show itself, not masquerade as one.
        assertTrue(
            "an unknown code must read as unknown",
            scanFailureText("brand_new_code").contains("brand_new_code"),
        )
    }

    @Test
    fun theGateStandsBeforeTheRadioAndOnlyOnAndroidsThatNeedIt() {
        val src = codeOnly(mainSource("BleLink.kt"))
        assertTrue(
            "BleLink.scan must close the flow with location_off when the position switch is off",
            Regex("IOException\\(\"location_off\"\\)").containsMatchIn(src),
        )
        val gate = src.indexOf("locationServiceOn()")
        val radio = src.indexOf("scanner.startScan(")
        assertTrue("the switch must be checked before startScan, not after it", gate in 0 until radio)
        assertTrue(
            "Android 12+ must not be gated: BLUETOOTH_SCAN is declared with neverForLocation",
            Regex("VERSION_CODES\\.S\\)\\s+return true").containsMatchIn(src),
        )
        assertTrue(
            "an unreadable LocationManager must not stop a scan",
            Regex("getOrDefault\\(true\\)").containsMatchIn(src),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Comments stripped: this guard is about the code path, and the reasoning is in prose. */
    private fun codeOnly(src: String): String = src
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    private fun mainSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

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
