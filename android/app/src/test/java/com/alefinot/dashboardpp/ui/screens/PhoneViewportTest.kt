package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's viewport is a phone, not a 1920 px browser window, and every one of these guards
 * is a decision that was measured on the bench (Mi 9 Lite: 1080x2340 at 440 dpi, so the app
 * window is 393x776 dp and the content box inside the 16 dp gutter is 361 dp — see
 * `Implementation plans/PHONE-LAYOUT-OPTIMIZATION-2026-11-08.md`). They are source-read tests
 * for the same reason [SettingsPageBenchFindingsTest] is: the failure is a composition or a
 * manifest attribute, and the module has no Compose test harness.
 *
 * What each one protects:
 *   1. portrait only — no screen in the app was ever designed for a landscape box, and the
 *      920 dp column plus a centred 26 sp header reads as a broken desktop page on a phone
 *      turned sideways;
 *   2. exactly one place pads for the system bars — a second `windowInsetsPadding` reserves the
 *      navigation bar twice, because that modifier does not consume the inset it used;
 *   3. a 48 dp floor on the controls the app draws itself — the Web UI's 9 px of padding around
 *      13 px of text is a mouse target, and this app is used at the handlebars;
 *   4. the spacing ladder is named — the page's four real numbers (4/8/12/16/24) had drifted
 *      into a dozen one-off literals across 20 files.
 */
class PhoneViewportTest {

    private val app = File("src/main")
    private val screens = File(app, "java/com/alefinot/dashboardpp/ui/screens")
    private val theme = File(app, "java/com/alefinot/dashboardpp/ui/theme")
    private val components = File(app, "java/com/alefinot/dashboardpp/ui/components")

    private fun code(dir: File, name: String): String {
        val f = File(dir, name)
        assertTrue("missing ${f.path}", f.exists())
        var s = f.readText()
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\n]*"), "")
    }

    @Test
    fun theAppIsPortraitOnly() {
        val manifest = File(app, "AndroidManifest.xml")
        assertTrue("missing AndroidManifest.xml", manifest.exists())
        val xml = manifest.readText()
        val activity = Regex("<activity\\b[^>]*android:name=\"\\.MainActivity\"[^>]*>")
            .find(xml)
        assertTrue("MainActivity is not declared with its attributes in one tag", activity != null)
        assertTrue(
            "MainActivity lost its portrait lock: nothing in this app adapts to a landscape " +
                "box (the page column is 920 dp and the header is centred), so a rotation " +
                "shows a layout nobody designed",
            activity!!.value.contains("android:screenOrientation=\"portrait\""),
        )
    }

    @Test
    fun onlyTheRootPadsForTheSystemBars() {
        val root = code(screens, "DashboardRoot.kt")
        assertTrue(
            "the root stopped taking the insets; with targetSdk 35 edge-to-edge is enforced " +
                "and the chrome draws under the status bar",
            root.contains("statusBarsPadding()") && root.contains("navigationBarsPadding()"),
        )

        // Compose's windowInsetsPadding does not consume, so any second use inside the rooted
        // tree double-counts. The bar is the surface that used to do it.
        val offenders = screens.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
            .orEmpty()
            .filter { code(screens, it.name).contains("windowInsetsPadding") }
            .map { it.name }
            .sorted()
        assertEquals(
            "a surface inside DashboardRoot pads for the system bars again, reserving the " +
                "navigation bar twice (48 dp of dead strip under the tab bar on a device with " +
                "three buttons): $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun theControlsTheAppDrawsReachTheTouchFloor() {
        val tokens = code(theme, "HudTokens.kt")
        assertTrue(
            "HudTouch.Min must be Android's 48 dp floor, not a smaller 'good enough'",
            Regex("object HudTouch\\s*\\{[^}]*val Min = 48\\.dp").containsMatchIn(tokens),
        )

        val hud = code(components, "Hud.kt")
        assertTrue(
            "HudButton lost its heightIn(min = HudTouch.Min): the page's 9 px of vertical " +
                "padding makes a ~31 dp button, which is a thumb miss at the handlebars",
            hud.contains("heightIn(min = HudTouch.Min)"),
        )
        assertTrue(
            "HudToggle's touch box stopped being HudTouch.Min; the 34x18 track is the page's " +
                "drawing and must stay, the box around it is what a thumb hits",
            Regex("Box\\(modifier\\.size\\(width = HudTouch\\.Min, height = HudTouch\\.Min\\)")
                .containsMatchIn(hud),
        )
        assertFalse(
            "the toggle track grew — the page draws .adv-switch at 34x18 px and the touch " +
                "target is supposed to come from the box around it, not the control",
            Regex("size\\(width = 44\\.dp, height = 44\\.dp\\)").containsMatchIn(hud),
        )
    }

    @Test
    fun theSpacingLadderIsNamedAndMatchesThePage() {
        val tokens = code(theme, "HudTokens.kt")
        val ladder = Regex("object HudSpace\\s*\\{([^}]*)\\}").find(tokens)
        assertTrue("HudSpace is gone; the page's 4/8/12/16/24 ladder went back to literals", ladder != null)
        val body = ladder!!.groupValues[1]
        for (step in listOf("4.dp", "8.dp", "12.dp", "16.dp", "24.dp")) {
            assertTrue("HudSpace is missing its $step step", body.contains(step))
        }
        for (name in listOf("Hair", "Gap", "Stack", "Tile", "Page", "Panel")) {
            assertTrue("HudSpace.$name is missing", body.contains("val $name ="))
        }
    }
}
