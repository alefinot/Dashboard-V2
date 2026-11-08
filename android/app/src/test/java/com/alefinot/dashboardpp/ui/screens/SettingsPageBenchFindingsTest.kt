package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageBenchFindingsTest {

    private val root = File("src/main/java/com/alefinot/dashboardpp/ui/screens")

    private fun source(name: String): String {
        val f = File(root, name)
        assertTrue("missing $name", f.exists())
        return f.readText()
    }

    @Test
    fun aCardWithNoActionAvailableSaysSoInsteadOfInventingOne() {
        val layout = codeOnly("settings/SettingsLayout.kt")
        assertTrue(
            "the firmware update card stopped saying where the new image goes: it must state the " +
                "spare-slot guarantee, because that is what makes START safe to press",
            layout.contains("spare flash slot"),
        )
        val widgets = codeOnly("SettingsWidgets.kt")
        assertTrue(
            "a 'match to reference' control was invented without the live reading it needs",
            widgets.contains("match to reference"),
        )
        val offenders = root.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
            .orEmpty()
            .filter { codeOnly(it.name).contains("action(\"ota") }
            .map { it.name }
            .sorted()
        assertEquals(
            "a settings screen started an install on its own; the transfer belongs to " +
                "OtaController: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    private fun codeOnly(name: String): String {
        var s = source(name)
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\n]*"), "")
    }

    @Test
    fun aSearchOpensTheGroupsItFindsInsteadOfApologising() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        assertTrue(
            "the page no longer asks the layout which groups a query reaches, so it cannot " +
                "open them",
            page.contains("SettingsLayout.groupsShown") && page.contains("SettingsLayout.openGroups"),
        )
        assertTrue(
            "the stranded-search hint is back: with one accordion there is no 'current group' " +
                "to be stuck in, and a count that mixed label matches with key matches was " +
                "never something the user could predict",
            !page.contains("otherGroupsMatching"),
        )
        assertTrue(
            "the empty message still says the words the user read as a lie",
            !page.contains("nothing matches"),
        )
    }

    @Test
    fun aRestoreSaysWhichKeysItLeftOutAndWhy() {
        val store = codeOnly("settings/ConfigStore.kt")
        assertTrue(
            "a skipped hidden credential is no longer reported as useless: " +
                "the wording has to separate it from a reading",
            store.contains("does not show"),
        )
        assertTrue(
            "a compiled-in value has its own sentence",
            store.contains("not settings"),
        )
    }

    @Test
    fun everySwitchOnThePageDescribesItself() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        assertTrue(
            "the header advanced switch has no label, so TalkBack reads its help text as its " +
                "name and cannot tell it from a row switch",
            Regex("label\\s*=\\s*\"advanced mode\"").containsMatchIn(page),
        )
    }

    @Test
    fun theRestartNoteIsClearedWhenTheLinkComesBack() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        val clearAt = page.indexOf("wasLinked = linked")
        val panelAt = page.indexOf("PerfMonitorScreen(")
        assertTrue("the restart note is no longer cleared on a link change", clearAt > 0)
        assertTrue(
            "the effect that clears the note sits below the early return that draws the monitor " +
                "page, so opening it destroys the effect and the note outlives the reboot",
            clearAt in 0 until panelAt,
        )
    }

    @Test
    fun theSettingsPageSaysSoBeforeItHasAnythingToShow() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        assertTrue(
            "with no link the page draws its rows with empty boxes instead of saying there is " +
                "nothing read from the unit yet",
            page.contains("Nothing to show yet"),
        )
    }

    /** The readings entry must not carry a second, unexplained copy of that problem. */
    @Test
    fun readingsFromTheUnitAreMarkedAsReadings() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        assertTrue(
            "a reading drawn among the settings reads like a setting that refuses to change",
            page.contains("not a setting"),
        )
        assertEquals(
            "the page must say which editable extras the unit has that the schema does not know",
            1,
            Regex("editable extras").findAll(page).count(),
        )
    }
}
