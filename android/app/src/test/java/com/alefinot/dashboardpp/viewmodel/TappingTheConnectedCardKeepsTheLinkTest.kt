package com.alefinot.dashboardpp.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule for "does this tap end the connection?" lives in [CardSelection], and this pins that the
 * view model actually asks it instead of deciding inline.
 *
 * The reason for a wiring guard rather than only a unit test on the predicate: the predicate is
 * three lines and nobody gets it wrong twice. What goes wrong is a call site that reads the session
 * itself and compares nothing — which is exactly what shipped until 2026-11-07:
 *
 * ```kotlin
 * if (uiState.value is ConnectionUiState.Connected) uiState.value = ConnectionUiState.Idle(null)
 * ```
 *
 * for any selection at all, including the connected card's own. The comment above that line said
 * "selecting a different card"; the code did not check for different. On the bench that left the
 * app showing `NO DASHBOARD IN RANGE` over a GATT link it was still holding — the stack still had
 * the client registered and a second phone dialling the same unit timed out after 20 s — and no
 * timer repaired it, because the reconnect guards see a live link and see no reason to dial, and
 * the adoption path only fires on a link state that *changed*.
 */
class TappingTheConnectedCardKeepsTheLinkTest {

    @Test
    fun selectingACardAsksTheRuleInsteadOfReadingItsOwnMind() {
        val src = mainSource("ConnectionViewModel.kt").codeOnly()
        val body = src.section("fun selectVehicle(")
        assertTrue(
            "selectVehicle must decide through CardSelection.endsLiveSession",
            body.contains("CardSelection.endsLiveSession("),
        )
        assertTrue(
            "and must hand it the card the session belongs to, not just \"is something connected\"",
            Regex("sessionVehicleId\\s*=").containsMatchIn(body),
        )
        assertTrue(
            "the clear must sit behind the decision, not beside it",
            body.indexOf("CardSelection.endsLiveSession(") < body.indexOf("ConnectionUiState.Idle"),
        )
        assertTrue(
            "ending a session on a tap must be on the capture - \"the card stopped saying " +
                "CONNECTED and I did not press DISCONNECT\" is only answerable from the log",
            body.contains("session ended cause="),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun String.codeOnly(): String = this
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    private fun String.section(openingSignature: String): String {
        val start = indexOf(openingSignature)
        assertTrue("no $openingSignature in the source", start >= 0)
        // `}` alone is not enough: a `} else {` line is also indented four, and cutting there would
        // hide exactly the arm this guard exists to check.
        val end = indexOf("\n    }\n", start)
        assertTrue("unterminated $openingSignature", end > start)
        return substring(start, end)
    }

    private fun mainSource(name: String): String {
        val dir = "src/main/java/com/alefinot/dashboardpp/viewmodel"
        val f = File(File(moduleDir(), dir), name)
        assertTrue("cannot find $f", f.isFile)
        // The sources on disk are CRLF and this helper matches line-anchored braces.
        return f.readText().replace("\r\n", "\n")
    }

    private fun moduleDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/viewmodel"
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
