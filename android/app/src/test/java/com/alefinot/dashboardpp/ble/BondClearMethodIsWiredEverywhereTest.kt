package com.alefinot.dashboardpp.ble

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `bond.clear` (2026-11-09) is a BLE RPC method, and AGENTS.md rule 17 says every method
 * lives in all of its homes at once or it does not exist: the unit's dispatcher, the app's
 * typed API, and the README's method table. A method the README does not know is a method
 * the next contributor re-invents; a method the app does not know is dead firmware weight —
 * both are how the REST table drifted into being historical.
 *
 * The homes are read as source, not restated, so a rename in one place reddens this test
 * instead of quietly splitting the protocol in two.
 */
class BondClearMethodIsWiredEverywhereTest {

    @Test
    fun theUnitDispatchesItAndAnswersWithTheCount() {
        val src = read(File(rootDir(), "src/bt_rpc.cpp"))
        assertTrue(
            "the dispatcher must serve bond.clear",
            src.contains("""strcmp(m, "bond.clear")"""),
        )
        // And answers, because an unanswered RPC reads to the app exactly like a dead link.
        val handler = src.substringAfter("""strcmp(m, "bond.clear")""").substringBefore("if (!strcmp")
        assertTrue("bond.clear must answer through rpcSend", handler.contains("rpcSend("))
        assertTrue("the answer must carry what was cleared", handler.contains("cleared"))
    }

    @Test
    fun theAppWrapsItWithARecoveryShapedName() {
        val src = read(File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ble"), "BleApi.kt"))
        assertTrue(
            "BleApi must expose bondClear() against the bond.clear method",
            Regex("""fun bondClear\(\).*"bond\.clear"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(src),
        )
    }

    @Test
    fun theReadmeTablesIt() {
        val readme = read(File(rootDir(), "README.md"))
        val row = readme.lines().firstOrNull { it.contains("`bond.clear`") && it.startsWith("|") }
        assertTrue("the method table must carry a bond.clear row", row != null)
        assertTrue(
            "the row must say what it does, not just that it exists",
            row!!.contains("bond", true) && row.contains("pairing", true),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Same walk-up-the-tree discipline as the other source guards. */
    private fun rootDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "src/bt_rpc.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot reach the repo root from " + System.getProperty("user.dir"))
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
        throw IllegalStateException("cannot reach the app module from " + System.getProperty("user.dir"))
    }

    private fun read(f: File): String {
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }
}
