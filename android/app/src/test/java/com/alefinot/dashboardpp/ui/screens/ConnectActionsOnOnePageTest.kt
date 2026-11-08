package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that "connect / disconnect is reachable from exactly one place" is a rule about
 * where buttons **are**, and nothing that runs at test time can see a button that was never
 * composed. So this guard reads the sources: it checks which screen files call a connection
 * action, and fails the day one grows back somewhere else.
 *
 * It exists because the app used to have four ways to connect - a hunting screen, a scan
 * screen, two covers offering FIND DEVICE / ENTER IP / WI-FI SETTINGS, and App Settings with
 * ENTER IP MANUALLY / RE-SCAN NETWORK - and each had its own idea of what the app was looking
 * for. The plan's answer ([plan §5], [plan §7]) is one page: the vehicle page's cards and its
 * ADD VEHICLE card. A cover may now only say which vehicle it is waiting for and offer to take
 * you there.
 *
 * The IP-era names in the regex below are gone rather than kept as a courtesy. The manual
 * address panel is deleted, along with the two view-model actions it drove, so a guard that
 * still allowed "the panel the vehicle page raises" would be documenting a file that no
 * longer exists — and the day something grows back in its place, the honest failure is
 * "a third file calls these actions", not "the second file is missing". The BLE bench panel
 * that used to be on the allowlist is deleted too, so the list is now exactly one file: the
 * page that owns connecting.
 *
 * The scan looks for **calls to the view model's connection actions**, not for words: a comment
 * may talk about connecting all it likes, and App Settings legitimately still prints the name
 * of the linked unit. A call is the thing that can actually connect.
 */
class ConnectActionsOnOnePageTest {

    /** Everything the view model offers that starts, stops or chooses a connection. */
    private val actionCall =
        Regex("\\b(rescan|connectVehicle|disconnect|selectVehicle)\\s*\\(")

    /**
     * The vehicle page owns every connection action the app offers. It used to share that
     * permission with `BleScreen` — the bench panel that drove the raw link (scan, pair,
     * snapshot, config mirror) while the settings rebuild landed. That panel is deleted: the
     * settings page reads and writes over the same link, the vehicle page pairs, and
     * "FORGET THIS UNIT" on a card is how a binding is dropped. The allowlist is one file now,
     * so a second file calling a connection action is exactly the regression this guard was
     * written to catch.
     *
     * The shell's permission launcher calls `retryAfterRadio()`, and it is deliberately not in
     * the regex: the rule is about where a user can *choose* to connect. That call re-runs the
     * attempt the launch already made, once, in the one case where the phone itself was the
     * thing standing in the way — putting it in the list would force the shell onto this
     * allowlist and blur the distinction the guard exists to keep.
     */
    private val allowed = listOf("VehicleScreen.kt")

    @Test
    fun connectionActionsAreCalledFromTheVehiclePageAlone() {
        val found = screenFiles().filter { actionCall.containsMatchIn(codeOnly(it.readText())) }
            .map { it.name }.sorted()
        assertEquals(
            "a connection action may only be offered by the vehicle page; every other page " +
                "has to point at the vehicle page instead",
            allowed,
            found,
        )
        assertTrue(
            "the vehicle page must still be the one that owns connecting",
            found.contains("VehicleScreen.kt"),
        )
    }

    /** The covers' whole vocabulary: one action, and it is navigation, not connection. */
    @Test
    fun offlineCoverOffersOnlyTheWayToTheVehiclePage() {
        val src = moduleSource("NotLinkedCover.kt")
        val actions = Regex("HudButton\\(\\s*\"([^\"]+)\"").findAll(src)
            .map { it.groupValues[1] }.toList()
        assertEquals(
            "a cover must offer exactly one action, the one that opens the vehicle page; found: $actions",
            listOf("OPEN VEHICLE PAGE"),
            actions,
        )
        assertFalse("a cover must not reach for the view model", codeOnly(src).contains("ConnectionViewModel"))
        val body = codeOnly(src)
        for (gone in listOf("rescan", "ENTER IP", "FIND DEVICE", "WI-FI", "SSID", "onFind", "rememberedIp")) {
            assertFalse("NotLinkedCover grew connect furniture again ($gone)", body.contains(gone))
        }
        // And no address, in either transport's spelling. The cover is the page that says
        // "nothing is here"; the moment it prints a number or a MAC it becomes a second
        // place a user can read an identity from, and the two will disagree.
        for (word in listOf("192.168", "ip", "IP")) {
            assertFalse("NotLinkedCover mentions an address ($word)", Regex("\b$word\b").containsMatchIn(body))
        }
    }

    /** App Settings keeps its switches, its storage figures and the linked unit's name, and
     * it keeps no part of the old device block. */
    @Test
    fun appSettingsHasNoConnectionActions() {
        val src = moduleSource("SettingsScreen.kt")
        val offenders = actionCall.findAll(codeOnly(src)).map { it.value }.toList()
        assertEquals(
            "App Settings must not run a connection - a device belongs to a vehicle, so those " +
                "actions live on the vehicle page only",
            emptyList<String>(),
            offenders,
        )
        assertFalse("App Settings may not raise an address panel", src.contains("onEnterIp"))
        assertFalse("App Settings may not name a network", Regex("\b(SSID|Wi-Fi|wifi)\b").containsMatchIn(codeOnly(src)))
    }

    /** Every cover in the shell gets the way to the garage, and nothing else. */
    @Test
    fun everyCoverPointsAtTheVehiclePage() {
        val src = moduleSource("ConnectedTabs.kt")
        assertFalse(
            "the shell may not raise a manual-address panel: there is no address to enter",
            src.contains("ManualEntryPanel"),
        )
        // Counted against the covers that exist rather than a number kept by hand: the
        // ESP slot used to be one of them (a WebView with nothing to load), and it is a
        // settings page now, with a Bluetooth link header of its own. The live page's
        // cover is left, and a guard that pinned "2" would have failed for the right
        // reason but said the wrong thing about it.
        val covers = Regex("NotLinkedCover\\(").findAll(codeOnly(src)).count()
        assertTrue("the shell is expected to still have a cover", covers >= 1)
        assertEquals(
            "every cover in the shell must be handed the way to the vehicle page",
            covers,
            Regex("onOpenVehicles = openGarage").findAll(codeOnly(src)).count(),
        )
        // The covers' one remaining action is navigation. If a button that connects ever
        // reappears here, it will not be called a cover; that is why the count is checked
        // against the NotLinkedCover calls rather than against a list of button labels.
        assertEquals(
            "the shell itself may not call a connection action",
            emptyList<String>(),
            actionCall.findAll(codeOnly(src)).map { it.value }.toList(),
        )
    }

    /**
     * The transport this guard is written against: no HTTP, no network discovery, no WebView
     * of the dashboard's own page anywhere in the screens directory. Written as one test
     * because the failure that matters is a single file quietly reaching for a socket.
     */
    @Test
    fun noScreenReachesForTheRemovedTransport() {
        val banned = listOf(
            "EspApi", "HttpURLConnection", "http://", "android.webkit", "WebView",
            "NsdManager", "Nsd", "SubnetScanner", "DeviceVerifier", "rememberedIp", ".ips",
            "GroupStrip", "ToolsPage", "BleScreen",
        )
        for (f in screenFiles()) {
            val body = codeOnly(f.readText())
            for (b in banned) {
                assertFalse("${f.name} still mentions $b", body.contains(b))
            }
        }
    }

    /**
     * Comments stripped out. Half the point of this redesign is written down in doc comments
     * that name the controls they removed - [NotLinkedCover]'s says in prose why there is no FIND
     * DEVICE button - and a guard that read those would fail on the explanation of its own rule.
     */
    private fun codeOnly(src: String): String = src
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    private fun screenFiles(): List<File> {
        val kt = screensDir().listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
        assertTrue("no .kt files under ${screensDir()}", !kt.isNullOrEmpty())
        return kt!!.toList()
    }

    private fun screensDir() = File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ui/screens")

    private fun moduleSource(name: String): String {
        val f = File(screensDir(), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    /**
     * The app module's directory. Gradle runs a module's unit tests with that module as the
     * working directory, but the CLI and the IDE disagree about it, so each level upward is
     * tried with the plausible prefixes before giving up. Same reasoning as
     * `AppXmlWiringTest.moduleFile`: a guard test that fails because it could not find its own
     * input is worse than no guard test, because it teaches you to ignore it.
     */
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
