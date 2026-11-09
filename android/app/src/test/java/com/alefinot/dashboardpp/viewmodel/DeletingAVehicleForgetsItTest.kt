package com.alefinot.dashboardpp.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting a vehicle is now the only way to remove one, and it has to do the whole job.
 *
 * The card used to carry a separate FORGET that cleared the pairing but kept the card. That
 * split read as two near-identical verbs on one page and offered a way to unpair a car nobody
 * was looking at, so it folded back into the delete: removing the card drops the pairing and
 * the identity it held, tears down the live session when the card owns it, and raises the
 * bond note when only Android can finish the job.
 *
 * This is a wiring guard rather than a unit test for the same reason
 * [TappingTheConnectedCardKeepsTheLinkTest] is: the decision itself is the pure
 * [ConnectionCore.forgetUnit], already unit-tested; what regressed once already is a call site
 * that clears the file and nothing else. So it reads the view model's source.
 */
class DeletingAVehicleForgetsItTest {

    @Test
    fun deleteVehicleAlsoForgetsThePairing() {
        val src = mainSource("ConnectionViewModel.kt").codeOnly()
        val body = src.section("fun deleteVehicle(")

        assertTrue(
            "a delete must ask the pure forget predicate whether this phone is left holding a bond",
            body.contains("ConnectionCore.forgetUnit("),
        )
        assertTrue(
            "and must ask it about the bond itself, which means reading the address before the " +
                "card is removed",
            body.contains("isBonded("),
        )
        assertTrue(
            "deleting the card that owns the session must drop the link, not just blank the state",
            body.contains("disconnect()"),
        )
        assertTrue(
            "the bond half the app cannot do has to reach the page, exactly as a FORGET raised it",
            body.contains("bondNote.value ="),
        )
        // Order matters: the pairings are only said to be gone once the file agrees, so the
        // note is published after the guarded write, not before it.
        assertTrue(
            "the bond note is published before the delete is confirmed - a refusal would then " +
                "leave a warning about a vehicle that was never deleted",
            body.indexOf("vehicles.delete(") < body.indexOf("bondNote.value ="),
        )
    }

    @Test
    fun thereIsNoLongerASeparateForgetColumnInViewModel() {
        val src = mainSource("ConnectionViewModel.kt").codeOnly()
        assertFalse(
            "the card's separate FORGET is gone; deleting a vehicle is the only removal",
            Regex("fun forgetUnit\\s*\\(").containsMatchIn(src),
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
