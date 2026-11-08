package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An empty BLE scan has two opposite causes and the app used to report only one of them.
 *
 * Nothing in `ScanCallback` ever says "Android refused to deliver results". With the position
 * switch off on Android 11 or older, `startScan()` is accepted, the sweep burns its whole window,
 * and the list stays empty - byte for byte what a unit that is switched off looks like. On the
 * bench (SM-T590, Android 10, 2026-11-07) that read back as *"No dashboard answered, and the scan
 * found none. Check that the unit is powered and not already linked to another phone."*, and the
 * unit was fine: the phone lying next to it was talking to it, and the tablet's location switch
 * was simply off. The app told the maintainer to go look at a dashboard that was never asked.
 *
 * So the refusal needs its own code, and the sentence needs to keep the reader on the phone's side
 * of the problem. [scanFailureText] is a pure function, so this is a behaviour test rather than a
 * source read; the source read only has to prove the gate still stands in front of the radio, and
 * still does not stand there on Android 12+ where `BLUETOOTH_SCAN` with `neverForLocation` made the
 * switch irrelevant - the same line the manifest draws with `maxSdkVersion="30"` on
 * `ACCESS_FINE_LOCATION`.
 */
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
        // Five radio-shaped refusals, five different fixes. If two collapse into one line, the
        // premise the whole file is built on - a code must say what to do - is gone.
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
        // Failing open is the other half of the rule: a refusal that fires on a missing
        // LocationManager would hide a working scan behind a message about a switch.
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

    /**
     * Gradle runs a module's tests with that module as the working directory; the CLI and the IDE
     * disagree about it, so each level upward is tried. Same reasoning as
     * `ConnectActionsOnOnePageTest.moduleDir` - a guard that cannot find its own input is worse than
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
