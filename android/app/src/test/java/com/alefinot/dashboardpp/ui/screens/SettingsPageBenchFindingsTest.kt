package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five things the bench showed the native settings page doing wrong, each pinned here because
 * none of them are reachable from a JVM test of the store: they live in the composition, and
 * the module has no Compose test harness. Same method as [ConnectActionsOnOnePageTest] and
 * [SettingsTabReplacesTheWebPageTest] — read the source, assert the fix is in it, so the next
 * edit that quietly removes one fails a test instead of a tablet.
 *
 * What the bench found, in the order the user found it:
 *   1. typing a word that matched a setting in another group left an empty page saying
 *       "nothing matches", with no lead to the group that held it. (The tab strip was replaced
 *       by the accordion for exactly this reason: a search now opens the groups it finds and
 *       hides the ones it does not, so there is no empty page to lead anyone out of.);
 *   2. a restore refused fourteen keys with one sentence saying they had "no use", which read
 *      as fourteen failed writes;
 *   3. the advanced switch in the header announced itself as a sentence ("show advanced
 *      settings") and every row switch as the row's label, so TalkBack had no way to tell them
 *      apart;
 *   4. after RESTART the page said "restarting, the link will drop…" forever, because the
 *      effect that clears the note was below the early return that draws the link panel, and
 *      opening the panel destroyed it;
 *   5. with no link at all the page drew every row with an empty box — a wall of controls that
 *      looked broken and refused every tap.
 */
class SettingsPageBenchFindingsTest {

    private val root = File("src/main/java/com/alefinot/dashboardpp/ui/screens")

    private fun source(name: String): String {
        val f = File(root, name)
        assertTrue("missing $name", f.exists())
        return f.readText()
    }

    /** Comment-free source, so a remembered description in a comment cannot satisfy a guard. */
    /**
     * A card whose action this page cannot carry on its own, and one calibration the page cannot
     * do at all. The firmware card's work belongs to `OtaController` and the link, not to a
     * settings row, so the guard is (a) the card states what an install risks, and (b) no settings
     * screen starts an install by calling an RPC directly — an install that skipped the controller
     * would own a 1.5 MB transfer nobody is tracking, and could not resume after a dropped link.
     * The "match to reference" calibrations still cannot be invented: they need a live reading
     * this page does not hold.
     */
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

    /**
     * Bench finding 3, still standing: a switch has to say what it switches, or TalkBack reads
     * its help text as its name and a page full of switches cannot be told apart.
     *
     * The header switch this finding was written about is gone — the maintainer had the Advanced
     * Mode card removed on 2026-11-08 and the settings tab draws every row the unit reports. So
     * the rule is asserted on the switches that remain, and the header is pinned empty: a control
     * added back up there has no label to inherit, which is exactly how this finding was found.
     */
    @Test
    fun everySwitchOnThePageDescribesItself() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        val row = codeOnly("settings/SettingsRow.kt")
        assertTrue(
            "a row toggle no longer passes its setting's name, so a page of switches is " +
                "indistinguishable to TalkBack",
            Regex("label\\s*=\\s*item\\.label").containsMatchIn(row),
        )
        assertTrue(
            "a switch is back in the header; a header control has no setting to name itself " +
                "after, which is the bug this test was written for",
            !Regex("HudToggle\\(").containsMatchIn(page),
        )
    }

    /**
     * The cleared-note effect has to stay in composition while another page is up. This guard
     * compares offsets in the file: the effect must appear before the early return.
     */
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
            page.contains("Nothing read from the unit yet"),
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
