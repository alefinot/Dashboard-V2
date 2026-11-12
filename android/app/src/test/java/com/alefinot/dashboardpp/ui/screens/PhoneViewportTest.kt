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
            "HudButton's floor went back to HudTouch.Min: that 48 dp of button is what the " +
                "maintainer had taken off again on 2026-11-08 (\"the buttons are too tall\"), " +
                "and it is what pushed the ESP setup action bar and a vehicle card's three " +
                "actions off a phone screenful",
            hud.contains("heightIn(min = HudTouch.Button)"),
        )
        assertTrue(
            "HudTouch.Button drifted to or past Material's floor — buttons are the one control " +
                "whose height is also their drawing, so the token has to stay near the page's " +
                "own ~31 dp button",
            Regex("val Button = 3[2-9]\\.dp").containsMatchIn(tokens),
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
     * The vehicle card is the connect control now, not a row of buttons. The maintainer asked for
     * it on 2026-11-08: tapping the card connects it or drops its session, EDIT moved to a corner
     * glyph, and a small link mark in the other corner shows the state — so the whole rectangle is
     * one decision ("get in this car") instead of three words to read.
     *
     * Pinned as source rather than a layout snapshot, for the same reason every guard here is: the
     * module has no Compose harness. What is checked is that the interaction is where the user looks
     * for it — the card body carries the tap, the outline says "connected" in colour, and the two
     * secondary affordances are icons in the corners, not words back in a row that would push the
     * card's own text off a phone.
     */
    @Test
    fun theVehicleCardIsTheConnectControl() {
        val card = code(screens, "VehicleCardLines.kt")
        val screen = code(screens, "VehicleScreen.kt")

        // The connected card is decided by which card owns the session — the flag that both the
        // tap and the green outline read.
        assertTrue(
            "connectedCard must key off the session's vehicle id; that is what lets a card that is " +
                "not the selected one still end its own link",
            card.contains("state is ConnectionUiState.Connected && state.vehicleId == v.id"),
        )

        // A tap on the card body toggles connect/disconnect, not select.
        assertTrue(
            "the card's own clickable no longer decides connect vs disconnect from `connected`",
            Regex("if \\(connected\\) onDisconnect\\(\\) else onConnect\\(\\)").containsMatchIn(screen),
        )
        assertFalse(
            "the card went back to a select-only tap; the body has to be the connect control",
            Regex("onClickLabel = \"Select ").containsMatchIn(screen),
        )

        // The outline is green on the connected card, and the name follows it.
        assertTrue(
            "the card outline no longer turns green for the vehicle the phone is connected to",
            Regex("connected -> HudColors\\.Green").containsMatchIn(screen),
        )

        // EDIT and the status mark are corner icons, not word-buttons in a row.
        assertTrue(
            "EDIT is no longer a corner glyph",
            Regex("HudIcons\\.Edit").containsMatchIn(screen) &&
                Regex("Alignment\\.TopEnd").containsMatchIn(screen),
        )
        assertTrue(
            "the connected/disconnected mark is no longer in the lower corner",
            Regex("HudIcons\\.LinkOn else HudIcons\\.LinkOff").containsMatchIn(screen) &&
                Regex("Alignment\\.BottomEnd").containsMatchIn(screen),
        )

        // The cover is square: the editor frames the photo 1:1, and the old 76x54 panel showed
        // only a cropped band of it.
        assertFalse(
            "the cover went back to the 76x54 landscape box; the photo it shows is square",
            Regex("size\\(width = 76\\.dp, height = 54\\.dp\\)").containsMatchIn(screen),
        )
        assertTrue(
            "CardCover no longer draws a 76.dp square panel",
            Regex("CardCover\\([\\s\\S]{0,400}?\\.size\\(76\\.dp\\)").containsMatchIn(screen),
        )

        // The card used to close its text column with "TAP TO CONNECT" / "TAP TO DISCONNECT".
        // That line is gone (2026-11-12, the icon grammar): the corner mark, the outline and the
        // body's own onClickLabel already say what the tap does, and a fourth copy of the fact is
        // text doing an icon's job. Pinned from both sides now: the hint may not come back, and
        // the semantics that replaced it may not leave with it.
        assertFalse(
            "the TAP TO hint came back; the corner glyph, the outline and the onClickLabel " +
                "already say it, which is why it was deleted",
            Regex("TAP TO (CONNECT|DISCONNECT)").containsMatchIn(screen),
        )
        assertTrue(
            "the tap no longer carries its meaning where a screen reader can hear it - the " +
                "onClickLabel is what makes the card body a connect control",
            Regex("onClickLabel = \\(if \\(connected\\) \"Disconnect \" else \"Connect \"\\)")
                .containsMatchIn(screen),
        )

        // No word-buttons left over on the card: the whole point was to fold them into the tap.
        for (word in listOf("CONNECT", "DISCONNECT", "FORGET")) {
            assertFalse(
                "the card grew a \"$word\" button back; the card body itself is the $word control",
                Regex("HudButton\\(\\s*\"$word\"").containsMatchIn(screen),
            )
        }
        assertFalse(
            "the action-button row's widthIn(min =) came back on this page — the card has no button " +
                "row to fit on one line any more",
            Regex("widthIn\\(min =").containsMatchIn(screen),
        )
    }

    /**
     * The Dashboard++ Setup header cost 239 dp of a 748 dp scroll viewport before the first
     * group appeared: title, identity pill, an Advanced Mode card of its own, a link line, the
     * search row, and a sync line. Measured on the bench phone after the first pass, that was
     * 171 dp; after the maintainer took the pill and the two note lines out on 2026-11-11 it is
     * 133 dp — title, search row, the five action glyphs — and every group is on screen with room
     * to spare.
     *
     * What is pinned is the shape of that header, because every one of those lines came back once
     * already when a change needed somewhere to say a thing: the page draws no banner and no status
     * or count line at rest, its header is title → search row → action glyphs in that order, and it
     * carries no control (the Advanced Mode card is gone, so nothing can reappear there and take a
     * line back).
     */
    @Test
    fun theSetupHeaderSpendsItsChromeOnTheSettingsNotOnItself() {
        val page = code(screens, "DashboardSettingsScreen.kt")
        assertFalse(
            "a status pill is back over the search box; the maintainer had it taken out on " +
                "2026-11-11 and the page says what it is talking to nowhere else — which is the " +
                "trade that was made, not an oversight to fix by putting the pill back",
            page.contains("HudBanner("),
        )
        for (line in listOf("linkLine(", "syncLine(", "tap a group to open it")) {
            assertFalse(
                "the header grew the note line $line back; at rest it is title, search row and " +
                    "the five glyphs, and only what just happened gets a line of its own",
                page.contains(line),
            )
        }
        // The header's one control: a re-read, drawn only for the state the page cannot get out of
        // by itself. A standing `SYNC` is what the maintainer took out on 2026-11-11 — every other
        // read the page needs it for, it already runs on its own, and the word promised a commit the
        // page deliberately does not make.
        assertFalse(
            "a standing SYNC button is back in the search row; the page re-reads on its own and " +
                "this control is now the failed read's RETRY and nothing else",
            Regex("label = \"SYNC\"").containsMatchIn(page),
        )
        assertTrue(
            "the re-read button lost its condition and is drawn at rest again",
            Regex("if \\(state\\.sync is SyncPhase\\.Failed\\)\\s*\\{\\s*HudButton\\(\\s*" +
                "label = \"RETRY\"").containsMatchIn(page),
        )
        assertFalse(
            "a control came back into the page header; the maintainer had the Advanced Mode " +
                "card removed on 2026-11-08 and the tab shows every row",
            page.contains("Advanced Mode"),
        )
        // The three lines the header does own, in order: the page's name, then the search row,
        // then the action glyphs. Everything else on the page is in the scroll below them.
        val titleAt = page.indexOf("HudPageHeader(title = \"Dashboard++ Setup\")")
        val searchAt = page.indexOf("HudInputStyle.SEARCH")
        val actionsAt = page.indexOf("onFactoryReset = { factoryAsk = true }")
        val listAt = page.indexOf("LazyColumn(")
        assertTrue(
            "the header's own lines are no longer title, search row, action glyphs, in that order",
            titleAt in 1 until searchAt && searchAt < actionsAt && actionsAt < listAt,
        )
    }

    /**
     * The settings row is the page with the most rows in the app (158 of them) and the one the
     * desktop column was designed around. Two things were wrong on the bench phone, and both are
     * about the row's *shape*, not its wording: the SELECT control's chip strip was
     * `fillMaxWidth()` inside a `Row` that also held a `weight(1f)` label, which left the label
     * no width at all, and the `*_X` / `*_Y` pair measured ~430 dp inside a 317 dp card.
     *
     * The row's answer is two shapes across a measured breakpoint, and the shape list is the
     * point of this test: the value boxes stay on the setting name's line (the maintainer's
     * call, 2026-11-08), and only the controls that cannot fit beside a label move below it.
     */
    @Test
    fun aSettingsRowKeepsItsValueBoxBesideItsName() {
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
        val exempt = Regex("NeedsItsOwnLine = setOf\\(([\\s\\S]*?)\\n\\)").find(row)
        assertTrue(
            "the stacking rule no longer consults its exemption set, so something is back on " +
                "the label's line that cannot fit there (or a value box has been pushed below " +
                "its name)",
            exempt != null,
        )
        val body = exempt!!.groupValues[1]
        for (needs in listOf("SELECT", "SLIDER", "PAIR", "FUEL_TABLE")) {
            assertTrue("RowControl.$needs must get a line of its own", body.contains("RowControl.$needs"))
        }
        for (beside in listOf("NUMBER", "TEXT", "SECRET", "TOGGLE", "SWATCH")) {
            assertFalse(
                "RowControl.$beside is in the exemption set: that is a value box (or a toggle) " +
                    "sent below its setting's name, and the maintainer asked for it on the line",
                body.contains("RowControl.$beside"),
            )
        }
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
    }

    /**
     * `reset` was a 12 sp word with 10 dp of padding — a 17 dp tall target — and it is the only
     * way to reach the unit's own `config.reset` for a key, so it is not decoration that can be
     * ignored: it had to become hittable.
     *
     * The stepper deliberately does *not* appear in the touch-floor half of this test. It was
     * widened to the same 48 dp box in that pass and the maintainer asked for the small buttons back
     * (2026-11-08), so the stepper cluster keeps the page's 30 dp squares and its tight gaps; the
     * field beside them is the primary input. Since the icons were unified the stepper lives in
     * [HudStepButton] rather than in `SettingsRow`, so both halves of that decision are asserted
     * where the box is now drawn.
     */
    @Test
    fun theSettingsRowReachesTheTouchFloorWhereItStillHasTo() {
        val row = code(File(screens, "settings"), "SettingsRow.kt")
        assertTrue(
            "the reset target lost its heightIn(min = HudTouch.Min)",
            Regex("heightIn\\(min = HudTouch\\.Min\\)").containsMatchIn(row),
        )

        val stepper = code(components, "HudActions.kt")
        // The stepper's own function, so the 48 dp guard below is about the stepper and not about
        // some other control in the same file that is allowed — and sized — by HudTouch.Min.
        val body = Regex("fun HudStepButton\\(([\\s\\S]*?)\\n\\}").find(stepper)
        assertTrue("HudStepButton is gone, so nothing is holding the stepper's size any more",
            body != null)
        val box = body!!.value
        assertFalse(
            "the stepper grew into a 48 dp box again — the maintainer asked for the small " +
                "buttons, and this is the assertion that notices them being re-grown",
            Regex("size\\(HudTouch\\.Min\\)[\\s\\S]{0,80}(clickable|hudPressable)\\(").containsMatchIn(box),
        )
        assertTrue(
            "the stepper stopped being the page's 30 dp square",
            // The press is the shared `hudPressable` (the whole app's 0.97 scale, applied
            // before the fill it has to take down with it), and it has to sit on the 30 dp
            // box itself, not on a wrapper that grew around it.
            Regex("size\\(30\\.dp\\)[\\s\\S]{0,200}hudPressable\\(").containsMatchIn(box),
        )
        // A drawn step needs a sentence for the screen reader: the ± it replaced were readable
        // text, a mark is not, and the description is the only thing that says which way is up.
        assertTrue(
            "HudStepButton stopped naming itself to the screen reader (role / onClickLabel)",
            Regex("role = Role\\.Button[\\s\\S]{0,80}onClickLabel = description")
                .containsMatchIn(box),
        )
        assertFalse(
            "a stepper came back as a typed character instead of a drawn mark",
            Regex("Text\\(\\s*\"[+\u2212\uFF0B]\"").containsMatchIn(row),
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
