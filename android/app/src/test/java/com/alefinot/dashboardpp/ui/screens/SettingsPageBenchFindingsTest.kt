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
        // The spare-slot sentence rode with the card: the firmware push left the unit's
        // settings page for App Settings on 2026-11-09, and this guard follows the promise,
        // not the file it happened to sit in. It is still stated above the buttons, wherever
        // the buttons are.
        val card = codeOnly("SettingsScreen.kt")
        assertTrue(
            "the firmware update card stopped saying where the new image goes: it must state the " +
                "spare-slot guarantee, because that is what makes START safe to press",
            card.contains("spare flash slot"),
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
     * The cleared-note effect has to stay in composition while the monitor is up. It used to have a
     * second reason to sit where it does: the monitor was a second page, and the early return that
     * drew it disposed everything below. The monitor is a card in the scroll now, so this page has
     * no route that can dispose the effect — and that is what this guard keeps true.
     */
    @Test
    fun theRestartNoteIsClearedWhenTheLinkComesBack() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        val clearAt = page.indexOf("wasLinked = linked")
        val cardAt = page.indexOf("PerfMonitorCard(")
        assertTrue("the restart note is no longer cleared on a link change", clearAt > 0)
        assertTrue(
            "the monitor is no longer the scroll's own card, drawn below the effect that clears " +
                "the note",
            cardAt > clearAt,
        )
        assertTrue(
            "the settings page has a second route again: returning from it disposes the effect " +
                "that clears the note, which is the bug this test was written for",
            !Regex("PageFade\\s*\\(").containsMatchIn(page),
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

    /**
     * The page's five actions, as five glyphs on one line under the search box (2026-11-11).
     *
     * A word button fails silently when it becomes a glyph: nothing on the screen says which
     * action was lost, or that it ever had a name. So each of the things the words carried is
     * pinned here as its own rule — that there are five on one line, that the line sits under the
     * search box in the header that does not scroll, that each glyph still names itself, that each
     * is pressable by a finger, and that the one action that cannot be undone asks before it acts.
     */
    @Test
    fun thePageDrawsItsFiveActionIconsUnderTheSearchBox() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        val start = page.indexOf("private fun ActionBar(")
        val end = page.indexOf("private fun RowScope.ActionSlot(")
        assertTrue("the page has no action line of its own", start in 1 until end)
        val bar = page.substring(start, end)

        assertEquals(
            "the action line is no longer exactly five slots",
            5, Regex("ActionSlot\\(").findAll(bar).count(),
        )
        // Where the line is drawn: after the search field, before the list. The header is the part
        // of the page that does not scroll, and five glyphs tapped by position are worth more
        // holding one y than moving as the page's note lines come and go.
        val callAt = page.indexOf("onFactoryReset = { factoryAsk = true }")
        val searchAt = page.indexOf("HudInputStyle.SEARCH")
        val listAt = page.indexOf("LazyColumn(")
        assertTrue(
            "the action line left the space under the search box: it is drawn after the search " +
                "field and before the list, in the header that stays put",
            searchAt in 1 until callAt && callAt < listAt,
        )
        // One line, and the list still flexes below it — the bug this line was born from is the
        // list taking the whole column and the actions being left zero pixels tall.
        assertTrue("the five slots are no longer one Row", Regex("Row\\(").containsMatchIn(bar))
        assertTrue(
            "the list below the action line took back the whole height",
            Regex("LazyColumn\\(\\s*Modifier\\.weight\\(1f\\)").containsMatchIn(page),
        )
        // The glyphs are the app's own set; nothing here is a Material icon or a drawable.
        for (glyph in listOf(
            "HudIcons.Flush", "HudIcons.Power", "HudIcons.ExportFile",
            "HudIcons.ImportFile", "HudIcons.Bin",
        )) {
            assertTrue("the action line lost its $glyph", bar.contains(glyph))
        }
        assertEquals(
            "a word button is back on the action line; the page's five actions are glyphs",
            0, Regex("HudButton\\(").findAll(bar).count(),
        )
    }

    /**
     * A glyph has no label, so the name has to live where a screen reader looks. `uiautomator`
     * wrote the same complaint about this page's switches on 2026-11-08: five unnamed boxes
     * read as "button, button, button, button, button".
     */
    @Test
    fun everyIconOnTheActionLineSaysWhatItDoesAndIsBigEnoughToHit() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        val bar = page.substring(
            page.indexOf("private fun ActionBar("),
            page.indexOf("private fun noteOfFlush("),
        )
        assertEquals(
            "an action slot lost its spoken name",
            5, Regex("description = ").findAll(bar).count(),
        )
        assertTrue(
            "the slot no longer passes its name to the glyph, or to the action a double-tap takes",
            Regex("contentDescription = description").containsMatchIn(bar) &&
                Regex("onClickLabel = description").containsMatchIn(bar),
        )
        assertTrue(
            "the glyph's target dropped below the app's 48 dp floor — a labelled button may be " +
                "36 dp because the label says what it is, a lone glyph may not",
            Regex("heightIn\\(min = HudTouch\\.Min\\)").containsMatchIn(bar) &&
                Regex("hudPressable\\(").containsMatchIn(bar),
        )
        // The count the old button spelled out ("SAVE 3") is now a colour, and only amber can
        // say it: a number painted back onto the glyph would put text on a line the maintainer
        // asked to have drawn.
        assertTrue(
            "the save slot went back to spelling its pending count onto the bar",
            !Regex("\"SAVE \\$\\{").containsMatchIn(bar) &&
                Regex("pending > 0 -> HudColors\\.Amber|pending > 0 && ready -> HudColors\\.Amber")
                    .containsMatchIn(bar),
        )
    }

    /**
     * FACTORY RESET came up into the icon line, where the unit's page deliberately kept it away
     * from the thumb that taps SAVE. What makes that safe is that it cannot fire on a tap: it
     * opens the app's own confirm box, and the RPC exists only inside that box's answer.
     */
    @Test
    fun theFifthIconWipesNothingUntilTheConfirmBoxIsAnswered() {
        val page = codeOnly("DashboardSettingsScreen.kt")
        assertTrue(
            "the bin went back to the two-press button, which on a phone is one press a driver " +
                "takes without looking at the label",
            !page.contains("press again to confirm"),
        )
        assertTrue(
            "the factory-reset RPC is no longer inside the confirm box's onConfirm",
            Regex("factoryAsk = false[\\s\\S]{0,400}store\\.action\\(\"factory\\.reset\"\\)")
                .containsMatchIn(page),
        )
        assertEquals(
            "a factory.reset is reachable without the box being up",
            1,
            Regex("store\\.action\\(\"factory\\.reset\"\\)").findAll(page).count(),
        )
        assertTrue(
            "the box stopped saying what it does in the button that does it",
            Regex("confirmLabel = \"FACTORY RESET\"").containsMatchIn(page) &&
                Regex("confirmStyle = HudButtonStyle\\.DANGER").containsMatchIn(page),
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
