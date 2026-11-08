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

    /**
     * The settings row is the page with the most rows in the app (158 of them) and the one the
     * desktop column was designed around. Measured on the bench phone, a number row gave its
     * label 105 dp: "Backlight PWM Frequency (kHz)" arrived broken mid-word over two lines
     * inside a row 42.5 dp tall, and the SELECT control's `fillMaxWidth` chip strip starved the
     * `weight(1f)` label beside it completely. These guards keep the row honest about which
     * shape it is drawing.
     */
    @Test
    fun aSettingsRowOnAPhoneGivesItsLabelTheWholeWidth() {
        val row = code(File(screens, "settings"), "SettingsRow.kt")
        assertTrue(
            "SettingsRow stopped measuring the width it was given; the row cannot know whether " +
                "it is on a phone or on a tablet, so it goes back to one shape that only fits " +
                "the desktop column",
            row.contains("BoxWithConstraints"),
        )
        assertTrue(
            "the row no longer branches on a measured width",
            Regex("maxWidth\\s*<\\s*WideFormRow").containsMatchIn(row),
        )
        assertTrue(
            "the breakpoint grew: a phone in portrait is 393 dp, so anything at or above this " +
                "number means the phone gets the desktop row again",
            Regex("val WideFormRow = (3[5-9]\\d|4\\d\\d)\\.dp").containsMatchIn(row),
        )
        assertTrue(
            "the starving pattern is back — an unweighted fillMaxWidth control in the row beside " +
                "a weight(1f) label leaves the label zero width (that is how a picker drew its " +
                "chips and dropped its label)",
            !Regex("fillMaxWidth\\(\\)\\.padding\\(start = 12\\.dp\\)").containsMatchIn(row),
        )
        // Three of the wide controls must be capped in the wide shape rather than filling it.
        assertTrue(
            "the wide controls lost their cap, so they compete with the label for the row again",
            Regex("widthIn\\(max = if \\(wide\\) 420\\.dp").findAll(row).count() >= 3,
        )
        assertTrue(
            "a toggle row is being stacked: a toggle needs 48 dp, not a line, and stacking the " +
                "toggle rows costs ~30 dp on each of the dozens of switches in the schema for " +
                "nothing",
            Regex("item\\.control != RowControl\\.TOGGLE").containsMatchIn(row),
        )
    }

    /**
     * `reset` was a 12 sp word with 10 dp of padding — a 17 dp tall target — and it is the only
     * way to reach the unit's own `config.reset` for a key, so it is not decoration that can be
     * ignored: it had to become hittable.
     *
     * The ± steppers deliberately do *not* appear in this test. They were widened to the same
     * 48 dp box in this pass and the maintainer asked for the small buttons back (2026-11-08), so
     * the stepper cluster keeps the page's 30 dp squares and its tight gaps; the field beside
     * them is the primary input.
     */
    @Test
    fun theSettingsRowReachesTheTouchFloorWhereItStillHasTo() {
        val row = code(File(screens, "settings"), "SettingsRow.kt")
        assertTrue(
            "the reset target lost its heightIn(min = HudTouch.Min)",
            Regex("heightIn\\(min = HudTouch\\.Min\\)").containsMatchIn(row),
        )
        assertFalse(
            "the ± steppers grew into a 48 dp box again — the maintainer asked for the small " +
                "buttons, and this is the assertion that notices them being re-grown",
            Regex("size\\(HudTouch\\.Min\\)\\.clickable").containsMatchIn(row),
        )
        assertTrue(
            "the stepper stopped being the page's 30 dp square",
            Regex("size\\(30\\.dp\\)\\.clip\\(shape\\)[\\s\\S]{0,160}clickable\\(enabled = enabled, onClick = onClick\\)")
                .containsMatchIn(row),
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
