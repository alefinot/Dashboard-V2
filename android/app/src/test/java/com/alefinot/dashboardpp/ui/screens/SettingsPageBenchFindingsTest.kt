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
 *       "nothing matches", with no lead to the group that held it;
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
    private fun codeOnly(name: String): String {
        var s = source(name)
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\n]*"), "")
    }

    @Test
    fun aSearchNamesTheGroupThatHoldsTheMatch() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        assertTrue(
            "an empty search page no longer leads to the group that holds the match",
            page.contains("otherGroupsMatching"),
        )
        assertTrue(
            "the hint still uses the plural-of-plural wording the user read as a variable dump",
            page.contains("if (hits == 1) \"match\" else \"matches\""),
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

    /**
     * The cleared-note effect has to stay in composition while the link panel is up. This
     * guard compares offsets in the file: the effect must appear before the early return.
     */
    @Test
    fun theRestartNoteIsClearedWhenTheLinkComesBack() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        val clearAt = page.indexOf("wasLinked = linked")
        val panelAt = page.indexOf("BleScreen(")
        assertTrue("the restart note is no longer cleared on a link change", clearAt > 0)
        assertTrue(
            "the effect that clears the note sits below the early return that draws the link " +
                "panel, so opening the panel destroys it and the note outlives the reboot",
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

    /** The Tools page must not carry a second, unexplained copy of that problem. */
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
