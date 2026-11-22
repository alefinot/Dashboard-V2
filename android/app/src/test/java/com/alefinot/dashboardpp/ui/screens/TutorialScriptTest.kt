package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.ui.codeOnly
import com.alefinot.dashboardpp.ui.components.HudGlyphLedger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The start tour, where it can be tested without a screen.
 *
 * Three halves, same split as the other pure-rule files: what the tour *says*
 * (its step list, its bar coverage, its badges being registered meanings),
 * when it *appears* ([TutorialScript.shouldShowAtStartup] and the process gate
 * inside [TutorialHolder]), and whether the two doors — the shell that hosts
 * it and the App Settings card that re-opens it — are still wired, pinned the
 * way `SettingsPageBenchFindingsTest` pins composition facts the JVM cannot
 * reach.
 */
class TutorialScriptTest {

    // The holder is process state; a test must not inherit another test's history.
    @Before
    fun freshProcess() {
        TutorialHolder.resetForTest()
    }

    // ── what the tour says ─────────────────────────────────────────────────────────

    @Test
    fun theTourWalksWelcomeTheFivePagesAndACloserInThatOrder() {
        assertEquals(
            listOf(
                "welcome", "vehicles", "live", "trips",
                "unit-settings", "app-settings", "ready",
            ),
            TutorialScript.steps.map { it.id },
        )
        assertTrue(
            "a tour card with no words is a progress dot that explains nothing",
            TutorialScript.steps.all { it.title.isNotBlank() && it.body.length > 40 },
        )
    }

    @Test
    fun theTourCoversEveryPageOfTheBarExactlyOnce() {
        val nav = TutorialScript.steps.filter { it.glyphId.startsWith("nav.") }
        assertEquals(
            "the bar has five slots and the tour must walk each of them once",
            listOf(
                "nav.vehicles", "nav.live", "nav.trips",
                "nav.unit-settings", "nav.app-settings",
            ),
            nav.map { it.glyphId },
        )
    }

    @Test
    fun everyTourBadgeIsARegisteredMeaning() {
        // The tour draws its marks from the ledger, so a step that named a
        // meaning nobody registered must fail here, not in `mark()`'s throw on
        // the bench phone.
        for (step in TutorialScript.steps) {
            assertTrue("${step.id} asks for ${step.glyphId}", HudGlyphLedger.glyphOf.containsKey(step.glyphId))
        }
    }

    @Test
    fun theTourBadgesMatchTheMarksTheBarItselfDraws() {
        // Each nav step promises to point at a page; if the bar redraws that
        // page with a different mark, the tour is lying with a badge.
        val bar = File("src/main/java/com/alefinot/dashboardpp/ui/screens/BarLayout.kt").codeOnly()
        for (step in TutorialScript.steps.filter { it.glyphId.startsWith("nav.") }) {
            val mark = HudGlyphLedger.mark(step.glyphId).name.removePrefix("Hud")
            assertTrue(
                "${step.id} wears $mark, which BarLayout does not draw for its slot",
                bar.contains("HudIcons.$mark"),
            )
        }
    }

    // ── when the tour appears ──────────────────────────────────────────────────────

    @Test
    fun theRuleShowItUnlessItWasRefusedAndOnlyOncePerProcess() {
        assertTrue(TutorialScript.shouldShowAtStartup(hidden = false, shownThisProcess = false))
        assertFalse(TutorialScript.shouldShowAtStartup(hidden = true, shownThisProcess = false))
        assertFalse(TutorialScript.shouldShowAtStartup(hidden = false, shownThisProcess = true))
        assertFalse(TutorialScript.shouldShowAtStartup(hidden = true, shownThisProcess = true))
    }

    @Test
    fun startupOpensTheTourOnceAndNeverAgainThatProcess() {
        assertFalse(TutorialHolder.visible.value)
        TutorialHolder.requestStartup()
        assertTrue("a first startup must open the tour", TutorialHolder.visible.value)

        // The owner backs out; a rotation re-runs the composition's effect.
        TutorialHolder.close()
        assertFalse(TutorialHolder.visible.value)
        TutorialHolder.requestStartup()
        assertFalse("a rotation is not a startup", TutorialHolder.visible.value)
    }

    @Test
    fun askingByHandOutlivesTheGateAndTheHiddenFlag() {
        // The App Settings button is an owner asking, not the app nagging:
        // neither the once-per-process gate nor `tutorial_hidden` may refuse it.
        TutorialHolder.requestStartup()
        TutorialHolder.close()
        TutorialHolder.request()
        assertTrue(TutorialHolder.visible.value)
        TutorialHolder.close()
        TutorialHolder.request()
        assertTrue("and again, and again — until the owner closes it", TutorialHolder.visible.value)
    }

    // ── the doors are wired ────────────────────────────────────────────────────────

    @Test
    fun theShellHostsTheTourOverTheWholeApp() {
        val root = File("src/main/java/com/alefinot/dashboardpp/ui/screens/DashboardRoot.kt").codeOnly()
        assertTrue("the shell no longer asks the rule at startup",
            root.contains("TutorialScript.shouldShowAtStartup"))
        assertTrue("the shell no longer opens the startup door",
            root.contains("TutorialHolder.requestStartup()"))
        assertTrue("the tour is no longer drawn as the root's overlay",
            root.contains("TutorialOverlay("))
        // The overlay is the Box's last child, so it dims AppShell, not the reverse.
        assertTrue(
            root.indexOf("AppShell(") in 1 until root.indexOf("TutorialOverlay("),
        )
    }

    @Test
    fun theCardWritesTheFlagTheMomentTheSwitchMoves() {
        val overlay = File("src/main/java/com/alefinot/dashboardpp/ui/screens/TutorialOverlay.kt").codeOnly()
        assertTrue("the tour's switch stopped saving the flag",
            overlay.contains("settings.setTutorialHidden(on)"))
        assertTrue("Back stopped putting the tour away",
            overlay.contains("BackHandler { TutorialHolder.close() }"))
        // Refusing *this time* must not be the same act as refusing *for good*:
        // SKIP closes without writing anything.
        val skipBody = overlay.substringAfter("HudButton(\"SKIP\"").substringBefore("}")
        assertFalse("SKIP started writing the flag", skipBody.contains("setTutorialHidden"))
    }

    @Test
    fun appSettingsCarriesBothDoors() {
        val page = File("src/main/java/com/alefinot/dashboardpp/ui/screens/SettingsScreen.kt").codeOnly()
        assertTrue("the GETTING STARTED card lost the button",
            page.contains("SHOW START TUTORIAL") && page.contains("TutorialHolder.request()"))
        assertTrue("the card lost the undo for a flagged-off tour",
            page.contains("SHOW AT STARTUP") && page.contains("settings.setTutorialHidden(!on)"))
        assertTrue("the card badge is no longer the generated cap",
            page.contains("R.drawable.ic_app_help"))
    }
}
