package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectActionsOnOnePageTest {

    /** Everything the view model offers that starts, stops or chooses a connection. */
    private val actionCall =
        Regex("\\b(rescan|connectVehicle|disconnect|selectVehicle)\\s*\\(")

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
        for (word in listOf("192.168", "ip", "IP")) {
            assertFalse("NotLinkedCover mentions an address ($word)", Regex("\b$word\b").containsMatchIn(body))
        }
    }

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
        val covers = Regex("NotLinkedCover\\(").findAll(codeOnly(src)).count()
        assertTrue("the shell is expected to still have a cover", covers >= 1)
        assertEquals(
            "every cover in the shell must be handed the way to the vehicle page",
            covers,
            Regex("onOpenVehicles = openGarage").findAll(codeOnly(src)).count(),
        )
        assertEquals(
            "the shell itself may not call a connection action",
            emptyList<String>(),
            actionCall.findAll(codeOnly(src)).map { it.value }.toList(),
        )
    }

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
