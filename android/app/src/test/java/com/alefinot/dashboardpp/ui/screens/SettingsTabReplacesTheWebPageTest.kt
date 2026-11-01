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
        assertEquals("nothing may compose a WebView page: $composers",
            emptyList<String>(),
            composers.sorted(),
        )
        // The file itself is gone. An uncomposed-but-present page is what let the WebView
        // era survive its own replacement for two phases, and it cost a delete, a rename
        // and a doc rewrite every time someone read a reference to it.
        val present = screenFiles().filter {
            it.name in listOf("DashboardWebScreen.kt", "WebUiGestureMirror.kt")
        }.map { it.name }
        assertEquals("these files must not exist: $present", emptyList<String>(), present)
        assertFalse(
            "the WebView package must not survive the transport it needed",
            File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp"), "webview").isDirectory,
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

    /**
     * Advanced mode is a firmware row the schema keeps out of the cards on purpose, so
     * asking the card list for it finds nothing — and the switch duly went missing from the
     * header on a bench unit, leaving advanced mode at whatever the unit last happened to
     * have, with no way to change it. The gate is the schema naming the key, not a row for
     * it existing.
     */
    @Test
    fun theAdvancedSwitchIsOfferedWheneverTheSchemaNamesTheKey() {
        val page = codeOf(moduleSource("DashboardSettingsScreen.kt"))
        assertTrue(
            "the advanced switch is gated on something other than the schema naming its key",
            Regex("schema\\.advancedKey\\.isNotEmpty\\(\\)").containsMatchIn(page),
        )
        assertFalse(
            "the switch is gated on the advanced key having a card row, which it never has",
            Regex("item\\(\\s*schema\\.advancedKey\\s*\\)\\s*!=\\s*null").containsMatchIn(page),
        )
    }

    /**
     * A switch drawn from scratch is a box to a screen reader. `uiautomator` reported every
     * toggle in the settings tab as an unnamed clickable view with no checked state — which
     * is also why none of the switch behaviour on the bench could be read off a dump. The
     * control has to carry its name and its on/off state on the node.
     */
    @Test
    fun theSwitchesSayWhatTheyAreAndWhetherTheyAreOn() {
        val hud = codeOf(componentSource("Hud.kt"))
        assertTrue(
            "HudToggle is clickable rather than toggleable, so it carries no state",
            Regex("\\.toggleable\\(").containsMatchIn(hud),
        )
        assertTrue("the switch never says whether it is on", Regex("stateDescription").containsMatchIn(hud))
        assertTrue(
            "the switch has no way of being given the name of the thing it switches",
            Regex("contentDescription\\s*=\\s*label").containsMatchIn(hud),
        )
        val rows = codeOf(moduleSource("settings/SettingsRow.kt"))
        assertTrue(
            "a row's switch is drawn without the row's label next to it in the tree",
            Regex("HudToggle\\([\\s\\S]{0,400}?label\\s*=\\s*item\\.label").containsMatchIn(rows),
        )
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

    /** Strips comments too: a guard that passes because of prose is not a guard. */
    private fun codeOf(src: String): String =
        codeOnly(src).replace(Regex("//[^\\n]*"), " ")

    private fun componentSource(name: String): String {
        val f = File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ui/components"), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

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
