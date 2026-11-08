package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val present = screenFiles().filter {
            it.name in listOf("DashboardWebScreen.kt", "WebUiGestureMirror.kt")
        }.map { it.name }
        assertEquals("these files must not exist: $present", emptyList<String>(), present)
        assertFalse(
            "the WebView package must not survive the transport it needed",
            File(File(moduleDir(), "src/main/java/com/alefinot/dashboardpp"), "webview").isDirectory,
        )
    }

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

    @Test
    fun thereIsNoSecondWayToReachAUnitLeftInTheApp() {
        val root = appPackageDir()
        val sources = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("no Kotlin sources under $root", sources.isNotEmpty())
        for (banned in listOf(
            "EspApi", "HttpURLConnection", "retrofit", "ktor", "volley", "cronet",
            "java.net.http", "android.webkit", "WebView",
            "Nsd", "NsdManager", "SubnetScanner", "DeviceVerifier", "BleScreen", "GroupStrip",
            "ToolsPage", "WifiManager", "ConnectivityManager", "MulticastLock", "V1Routes",
        )) {
            for (f in sources) {
                assertFalse(
                    "${f.relativeTo(root)} reaches the transport that was deleted ($banned)",
                    codeOnly(f.readText()).contains(banned),
                )
            }
        }
        val httpClientFiles = sources.filter { codeOnly(it.readText()).contains("okhttp3") }
            .map { it.relativeTo(root).path.replace('\\', '/') }.sorted()
        assertEquals(
            "the HTTP client has one file - the weather seam - and a second file importing it " +
                "is how a transport grows back without anyone deleting it twice",
            listOf("weather/WeatherHttp.kt"),
            httpClientFiles,
        )
        val urlFiles = sources.filter {
            val raw = it.readText()
            raw.contains("http://") || raw.contains("https://")
        }.map { it.relativeTo(root).path.replace('\\', '/') }.sorted()
        assertEquals(
            "a URL appeared in code again - if it is a legitimate one (a namespace, a tile " +
                "user agent, a provider endpoint), add it here and say what it is", listOf(
                "trips/GpxWriter.kt",
                "ui/map/RouteMap.kt",
                "weather/OpenMeteo.kt",
                "weather/PlaceNames.kt",
                "weather/WeatherHttp.kt",
            ), urlFiles,
        )
        for (gone in listOf("api/EspApi.kt", "api/EspApiLane.kt", "discovery", "ui/screens/BleScreen.kt")) {
            assertFalse("$gone must not exist - it was the Wi-Fi transport", File(root, gone).exists())
        }
        val gradle = File(moduleDir(), "build.gradle.kts")
        assertTrue("cannot find $gradle", gradle.isFile)
        assertEquals(
            "com.squareup.okhttp3 belongs in the dependency list once, for the weather seam",
            1,
            Regex("com\\.squareup\\.okhttp3").findAll(gradle.readText()).count(),
        )
    }

    @Test
    fun theManifestDoesNotPromiseAWiFiPath() {
        val manifest = File(File(moduleDir(), "src/main"), "AndroidManifest.xml")
        assertTrue("cannot find the manifest", manifest.isFile)
        val prose = manifest.readText().lowercase()
        for (claim in listOf("still talks to pre-2.0 units over wi-fi", "http path is kept")) {
            assertFalse("the manifest still describes a Wi-Fi path ($claim)", prose.contains(claim))
        }
    }

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

    private fun appPackageDir() = File(moduleDir(), "src/main/java/com/alefinot/dashboardpp")

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
