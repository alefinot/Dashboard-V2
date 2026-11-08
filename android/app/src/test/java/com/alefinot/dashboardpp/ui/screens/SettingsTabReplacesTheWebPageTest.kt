package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ESP settings slot used to be a `WebView` pointed at the unit's own page. Phase 1a made
 * it a page this app draws and edits over the Bluetooth link, and the WebView code is now
 * deleted along with the HTTP client and the Wi-Fi discovery that fed it. So this guard asks
 * two things: that the page which replaced the web one is honest about how it talks to the
 * unit, and that there is no *second* way to talk to it left anywhere in the app.
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
     * The whole app this time, not one page. There is one way left to reach a **unit** — GATT —
     * and the way to keep it that way is to fail the build the day a socket, a scanner or a
     * WebView comes back, rather than trusting that the next person will notice which of three
     * paths a number came from.
     *
     * The app does have an outbound HTTP client again, and it is still not a way to reach a
     * unit: a unit has no radio, so the phone fetches the forecast and the place name a panel
     * cannot fetch by itself and hands them over with `weather.set`. So the rule moved from "no
     * HTTP client anywhere" to "exactly one named file may hold one" — the dependency in
     * `build.gradle.kts` and the import in `weather/WeatherHttp.kt` are pinned to each other
     * here, and every other HTTP stack stays banned outright, because a second client would not
     * need to import the first one in order to become a second way to a unit.
     *
     * The `http` strings that are allowed to stay are not roads to a unit: one is the XML
     * namespace on the root element of a written GPX file, one is the contact URL in the user
     * agent the trip map sends with its OSM tile requests, one is the contact URL inside the
     * weather client's own user agent — Nominatim's usage policy demands an identifying one
     * ("stock User-Agents as set by http libraries will not do"), so the seam carries the
     * repository there — and one is the forecast endpoint the weather client is pointed at,
     * `api.open-meteo.com`, which is a keyless public forecast for the car's coordinates and not
     * an address anything in this repo controls. They are named file by file so the ban itself
     * stays absolute.
     */
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
        // The one HTTP client is allowed exactly one home. Checked in code rather than prose:
        // the thing that would grow a transport is an import, and the answer to a second file
        // importing it is "the unit is reached over GATT, like everything else", not "add a line
        // here". A provider's host may be named by a weather URL builder; what may not happen is
        // that builder opening a connection of its own.
        val httpClientFiles = sources.filter { codeOnly(it.readText()).contains("okhttp3") }
            .map { it.relativeTo(root).path.replace('\\', '/') }.sorted()
        assertEquals(
            "the HTTP client has one file - the weather seam - and a second file importing it " +
                "is how a transport grows back without anyone deleting it twice",
            listOf("weather/WeatherHttp.kt"),
            httpClientFiles,
        )
        // URLs are checked in the raw text, not the comment-stripped text: `codeOnly` removes
        // everything after `//`, which is exactly the half of a URL that would have to be the
        // evidence. The files allowed to hold one are named, and none of them sends a word to a
        // unit: one XML namespace, two user-agent contact URLs, and the weather providers'
        // endpoints - two hosts that only ever have a coordinate or a typed place-name sent to
        // them, and never a unit. A weather file that names a host is fine; the same file opening
        // a connection of its own is what the `okhttp3` list above forbids.
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
        // And the parts that only ever existed to carry HTTP: the files, the package, and the
        // dependency that makes an HTTP client possible at all.
        for (gone in listOf("api/EspApi.kt", "api/EspApiLane.kt", "discovery", "ui/screens/BleScreen.kt")) {
            assertFalse("$gone must not exist - it was the Wi-Fi transport", File(root, gone).exists())
        }
        val gradle = File(moduleDir(), "build.gradle.kts")
        assertTrue("cannot find $gradle", gradle.isFile)
        // Declared, but exactly once: there is one HTTP client on the classpath, and the file
        // list above is what limits who may hold it. A second entry here would mean a second
        // client bought for a second purpose, and that is the kind of change this guard exists
        // to catch while it is still one line.
        assertEquals(
            "com.squareup.okhttp3 belongs in the dependency list once, for the weather seam",
            1,
            Regex("com\\.squareup\\.okhttp3").findAll(gradle.readText()).count(),
        )
    }

    /**
     * A transport that is gone should not still be asked for, and a radio that is the whole
     * story should be asked for clearly. The three permissions dropped with the HTTP client
     * were read only by the discovery sweep and by the screens that explained why a LAN could
     * not be seen; the BLE block is what the app actually runs on. (The permissions themselves
     * are asserted in [com.alefinot.dashboardpp.vehicles.AppXmlWiringTest], which is where the
     * manifest is read; this half checks that the story written next to them stayed true.)
     */
    @Test
    fun theManifestDoesNotPromiseAWiFiPath() {
        val manifest = File(File(moduleDir(), "src/main"), "AndroidManifest.xml")
        assertTrue("cannot find the manifest", manifest.isFile)
        val prose = manifest.readText().lowercase()
        for (claim in listOf("still talks to pre-2.0 units over wi-fi", "http path is kept")) {
            assertFalse("the manifest still describes a Wi-Fi path ($claim)", prose.contains(claim))
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
     * This page used to hold a switch for the unit's `ADV_MODE`, and that switch kept going
     * missing on the bench because the gate was keyed on the schema having a *row* for the key,
     * which it deliberately never has. On 2026-11-08 the maintainer answered the other way: no
     * switch at all, and the tab draws every row the unit reports.
     *
     * So this asserts the new shape, and asserts it in the negative too — the page cannot drift
     * back to hiding 16 rows and a whole card behind a control that is no longer on the screen.
     * The route that is left for changing `ADV_MODE` is a config backup.
     */
    @Test
    fun theSettingsTabDrawsEveryRowIncludingTheAdvancedOnes() {
        val page = codeOf(moduleSource("DashboardSettingsScreen.kt"))
        assertFalse(
            "the page offers a way to hide rows again; the advanced switch was removed on " +
                "2026-11-08 and the tab shows the whole table",
            Regex("setAdvanced|HudToggle").containsMatchIn(page),
        )
        assertTrue(
            "the page no longer holds the advanced gate open, so an advanced row and an " +
                "advanced card are hidden behind a switch that does not exist",
            Regex("val advanced = true").containsMatchIn(page),
        )
        assertFalse(
            "the layout is gated on store state again, which is how a row went missing before",
            Regex("state\\.advanced").containsMatchIn(page),
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
