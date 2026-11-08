package com.alefinot.dashboardpp.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

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
