package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ESP settings slot used to be a `WebView` pointed at the unit's own page. Phase 1a made
 * it a page this app draws and edits over the Bluetooth link; the WebView code itself outlives
 * that by two more phases (the plan deletes it in 2c, once the last HTTP path is gone), so this
 * guard does not ask whether the file still exists — it asks whether anything still *reaches*
 * it, and whether the page that replaced it is honest about how it talks to the unit.
 *
 * Like [ConnectActionsOnOnePageTest], this reads sources rather than composing them: the module
 * has no Compose test harness, and a guard that only ran on a phone would never run in CI.
 */
class SettingsTabReplacesTheWebPageTest {

    /** The shell composes the new page, and nothing composes the old one. */
    @Test
    fun theShellDrawsTheSettingsPageRatherThanTheWebPage() {
        val shell = codeOnly(moduleSource("ConnectedTabs.kt"))
        assertTrue(
            "the ESP slot no longer draws the native settings page",
            Regex("DashboardSettingsScreen\\s*\\(").containsMatchIn(shell),
        )
        val composers = screenFiles().filter {
            Regex("DashboardWebScreen\\s*\\(").containsMatchIn(codeOnly(it.readText()))
        }.map { it.name }
        assertEquals(
            "the WebView page may only be defined, never composed: $composers",
            listOf("DashboardWebScreen.kt"),
            composers.sorted(),
        )
    }

    /**
     * The bar calls the slot what it now is. The enum case is still `WEB` — renaming it means
     * touching every `when` that switches on it, which is Phase 2's job — and `AppTab.SETTINGS`
     * already belongs to App Settings, so the two must not be confused.
     */
    @Test
    fun theBarNamesTheSlotAfterTheNewPage() {
        val bar = moduleSource("BarLayout.kt")
        assertTrue(
            "the ESP slot is still labelled as the unit's web page",
            Regex("AppTab\\.WEB\\s*->\\s*Glyph\\(\\s*\"Dashboard settings\"").containsMatchIn(codeOnly(bar)),
        )
        assertTrue(
            "App Settings has its own slot and must keep it",
            Regex("AppTab\\.SETTINGS\\s*->").containsMatchIn(codeOnly(bar)),
        )
    }

    /**
     * A page that reads its settings over Bluetooth must not hold a URL, a socket or the old
     * HTTP lane: those are how the app used to reach the unit, and one left behind means two
     * sources of truth for the same number.
     */
    @Test
    fun theSettingsPageHasNoWayToReachTheUnitButTheLink() {
        val files = listOf(moduleSource("DashboardSettingsScreen.kt")) + settingsDir().listFiles()
            .orEmpty().filter { it.name.endsWith(".kt") }.map { it.readText() }
        for (banned in listOf("android.webkit", "http://", "https://", "EspApiLane", "EspApi(", "HttpURLConnection", "V1Routes")) {
            for (src in files) {
                assertFalse("the settings page still mentions $banned", codeOnly(src).contains(banned))
            }
        }
    }

    /**
     * The row controls have to cover every control the schema can name, or a row that parses
     * correctly has no way to be drawn and quietly disappears from the form. `when` over
     * [com.alefinot.dashboardpp.ui.screens.settings.RowControl] with an `else` would hide the
     * gap, so the branch names are counted here instead.
     */
    @Test
    fun everyRowControlIsDrawnSomewhere() {
        val rows = moduleSource("settings/SettingsRow.kt")
        for (control in listOf(
            "TOGGLE", "NUMBER", "TEXT", "SECRET", "SELECT", "SLIDER", "SWATCH", "PAIR", "FUEL_TABLE",
        )) {
            assertTrue(
                "no branch draws the $control control",
                Regex("RowControl\\.$control|\\b$control\\b").containsMatchIn(rows),
            )
        }
    }

    private fun codeOnly(src: String): String = src
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    private fun screenFiles(): List<File> {
        val kt = screensDir().listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
        assertTrue("no .kt files under ${screensDir()}", !kt.isNullOrEmpty())
        return kt!!.toList()
    }

    private fun screensDir() = File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ui/screens")

    private fun settingsDir() = File(screensDir(), "settings")

    private fun moduleSource(name: String): String {
        val f = File(screensDir(), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    /** Same walking-up search as [ConnectActionsOnOnePageTest]: a guard that cannot find its own input is worse than none. */
    private fun moduleDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/ui/screens"
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
