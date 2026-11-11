package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout.blocksFor
import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout.columns
import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout.openGroups
import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout.pack
import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout.tapOpen
import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout.unlabelledBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows the settings page is allowed to draw, in what order, and where the cards land.
 *
 * The unit's form map has 242 rows; the generated schema covers 178 of them; the page
 * draws a subset of those depending on the master switch and the search box. Every one of
 * those reductions is a setting the owner cannot reach, so each has to be written down with
 * its reason — and this is the check that a row never disappears twice.
 *
 * The rules themselves are the unit page's rules, and the tests are written against that
 * page's behaviour rather than against whatever the Kotlin happens to do first: the driver
 * who has learned where a setting is on the unit's screen has to land in the same place on
 * the phone, and the day the two disagree is the day the search box stops being reliable on
 * either.
 *
 * [renderablesFor] works one group at a time, which is how the page shows them, so
 * "everything" means every group in the schema.
 */
class SettingsLayoutTest {

    private val group = SchemaGroup(
        id = "g",
        title = "Test group",
        cards = listOf(
            SchemaCard(
                title = "Ordinary",
                items = listOf(
                    row("SPEED_LIMIT_KMH", unit = "km/h"),
                    row("GPS_RATE", section = "GPS"),
                    row("ADV_MODE"),
                ),
            ),
            SchemaCard(
                title = "Advanced card",
                items = listOf(row("CALIB_OFFSET", advanced = true), row("BOOT_TIME_MS", advanced = true)),
                advanced = true,
            ),
            SchemaCard(
                title = "Auto brightness",
                items = listOf(row("ENABLE_AUTO_BRIGHTNESS")),
                widget = "ambientCalibrate",
            ),
        ),
    )

    private fun row(
        key: String,
        unit: String? = null,
        section: String? = null,
        advanced: Boolean = false,
    ) = SchemaItem(
        key = key,
        kind = ValueKind.BOOL,
        control = RowControl.TOGGLE,
        label = ConfigStore.humanize(key),
        unit = unit,
        section = section,
        advanced = advanced,
    )

    private fun drawn(
        query: String = "",
        advanced: Boolean = false,
        unknown: List<SchemaItem> = emptyList(),
    ): List<RenderRow> = renderablesFor(
        group,
        ConfigStore.SettingsState(unknown = unknown),
        query,
        advanced,
    ).filterIsInstance<RenderRow>()

    private fun keys(rows: List<RenderRow>) = rows.map { it.item.key }

    /**
     * The layout layer still knows what an advanced row is, and still refuses to draw one
     * unless its caller says the gate is open. What changed on 2026-11-08 is who answers: the
     * settings page has no switch any more and passes `true`, so today's only caller always
     * opens it. The rule stays ruleable because the unit still has an `ADV_MODE`, and a surface
     * that ever wants the gate back should find it here rather than reinvent it.
     */
    @Test
    fun advancedRowsAppearOnlyWhenTheGateIsOpen() {
        val plain = keys(drawn())
        val open = keys(drawn(advanced = true))
        assertTrue("an advanced row leaked: $plain", !plain.any { it in setOf("CALIB_OFFSET", "BOOT_TIME_MS") })
        assertTrue("advanced rows never arrived", open.containsAll(listOf("CALIB_OFFSET", "BOOT_TIME_MS")))
        // ADV_MODE is an ordinary row in this fixture, and an ordinary row is drawn either way:
        // the gate is about the rows behind it, not about itself.
        assertTrue("a plain row was hidden by the gate", plain.contains("ADV_MODE"))
    }

    /** A whole advanced card is out with the gate shut, and in with it open. */
    @Test
    fun anAdvancedCardIsGatedAsACard() {
        val cards = renderablesFor(group, ConfigStore.SettingsState(), "", false)
            .filterIsInstance<RenderCard>()
        assertTrue(
            "the advanced card was drawn with the gate shut: ${cards.map { it.title }}",
            cards.none { it.title == "Advanced card" },
        )
        val open = renderablesFor(group, ConfigStore.SettingsState(), "", true)
            .filterIsInstance<RenderCard>()
        assertTrue(open.any { it.title == "Advanced card" })
    }

    /**
     * A widget card is a control plus its rows, not a control instead of them, and the
     * control is inside the card rather than on a page of its own. The unit's page draws its
     * button above the settings of the same card; an app that hides those rows behind a
     * button into another screen puts values where nobody looks.
     */
    @Test
    fun aWidgetCardCarriesItsControlAndItsOwnRows() {
        val rows = keys(drawn())
        assertTrue("the widget card's settings vanished", rows.contains("ENABLE_AUTO_BRIGHTNESS"))
        val body = blocksFor(group, ConfigStore.SettingsState(), "", false)
            .first { it.card.title == "Auto brightness" }
        assertEquals(
            "the card's own action is not drawn with it",
            listOf<Renderable>(RenderWidget("ambientCalibrate"), RenderRow(row("ENABLE_AUTO_BRIGHTNESS"))),
            body.body,
        )
        assertTrue("a card with a control that needs its width was packed into a column", body.wide)
    }

    /**
     * Search finds a row by its label and by the firmware key, because owners read one off
     * the row and the other off the changelog, the docs and the unit's error strings.
     *
     * Word-prefix, per word, exactly like the unit page's `match()`: `exc` finds
     * `EXC_RES_OHM`. A substring matcher would find different rows for the same typing on
     * the phone than on the unit.
     */
    @Test
    fun searchSeesLabelsAndKeysByWordPrefix() {
        assertEquals(listOf("GPS_RATE"), keys(drawn(query = "gps rate")))
        assertEquals(listOf("CALIB_OFFSET"), keys(drawn(query = "calib_", advanced = true)))
        assertEquals(
            "search matched a label that is not there",
            emptyList<String>(),
            keys(drawn(query = "gearbox")),
        )
        // A prefix of a word, not a fragment inside one: "cu" must not find "fuel".
        val g = SchemaGroup(
            "gc", "Group",
            listOf(SchemaCard(
                "Fuel",
                listOf(SchemaItem("FUEL_SENSOR", ValueKind.BOOL, RowControl.TOGGLE, "Fuel level sensor")),
            )),
        )
        assertEquals(
            "a query matched inside a word the row never starts",
            emptyList<String>(),
            renderablesFor(g, ConfigStore.SettingsState(), "cu", false).filterIsInstance<RenderRow>()
                .map { it.item.key },
        )
    }

    /**
     * A query that names a card shows the whole card, and one that names a section shows
     * the whole section — the unit page's rule, and the reason "noise" finds the noise bench
     * even though nobody remembers which rows belong to it.
     */
    @Test
    fun aTitleMatchCarriesEverythingUnderIt() {
        val cards = listOf(
            SchemaCard(
                "Noise bench",
                listOf(row("NOISE_WINDOW_MS"), row("NOISE_MIN_HZ")),
            ),
            SchemaCard(
                "Layout",
                listOf(row("HUD_SPEED", section = "Speedometer"), row("HUD_RPM", section = "Tachometer")),
            ),
        )
        val g = SchemaGroup("gt", "Group", cards)
        assertEquals(
            "a card by name did not bring its rows",
            listOf("NOISE_WINDOW_MS", "NOISE_MIN_HZ"),
            renderablesFor(g, ConfigStore.SettingsState(), "noise bench", false)
                .filterIsInstance<RenderRow>().map { it.item.key },
        )
        assertEquals(
            "a section by name did not bring its rows",
            listOf("HUD_RPM"),
            renderablesFor(g, ConfigStore.SettingsState(), "tacho", false)
                .filterIsInstance<RenderRow>().map { it.item.key },
        )
    }

    /**
     * A group's own title is not searched — the unit page skips it on purpose. Typing a
     * group name is not a search for a setting, and treating it as one opens a group whose
     * cards are all empty.
     */
    @Test
    fun aGroupTitleIsNotSearched() {
        val g = SchemaGroup("gg", "Sensors Tuning", listOf(SchemaCard("GNSS", listOf(row("GPS_RATE")))))
        assertEquals(
            "the group name was searched, and its rows came with it",
            emptyList<String>(),
            renderablesFor(g, ConfigStore.SettingsState(), "sensors tuning", false)
                .filterIsInstance<RenderRow>().map { it.item.key },
        )
    }

    /** An advanced row found by search still has to be inside the advanced gate. */
    @Test
    fun searchDoesNotWalkThroughTheAdvancedGate() {
        assertTrue("search reached an advanced row", keys(drawn(query = "calib")).isEmpty())
        assertTrue(
            "search reached an advanced card",
            renderablesFor(group, ConfigStore.SettingsState(), "advanced card", false)
                .filterIsInstance<RenderCard>().none { it.title == "Advanced card" },
        )
    }

    @Test
    fun sectionsKeepTheFormMapOrderUnderTheCard() {
        val out = renderablesFor(
            SchemaGroup(
                "g2", "Group",
                listOf(
                    SchemaCard(
                        "Card",
                        listOf(row("A_FIRST"), row("B_GPS", section = "GPS"), row("C_GPS", section = "GPS")),
                    ),
                ),
            ),
            ConfigStore.SettingsState(),
            "",
            false,
        )
        assertEquals(
            listOf("Card", "A_FIRST", "GPS", "B_GPS", "C_GPS"),
            out.map {
                when (it) {
                    is RenderCard -> it.title
                    is RenderSection -> it.title
                    is RenderRow -> it.item.key
                    is RenderWidget -> it.widget
                }
            },
        )
    }

    /**
     * A section heading with nothing under it is dropped, on the unit's page and here. A
     * bare heading above an empty stretch of card is the failure mode that makes a filtered
     * page look broken rather than filtered.
     */
    @Test
    fun aSectionHeadingSurvivesOnlyWithRowsUnderIt() {
        val g = SchemaGroup(
            "g3", "Group",
            listOf(
                SchemaCard(
                    "Card",
                    listOf(row("A_GPS", section = "GPS"), row("B_TACHO", section = "Tachometer")),
                ),
            ),
        )
        val out = renderablesFor(g, ConfigStore.SettingsState(), "a", false).filter {
            it is RenderSection || it is RenderRow
        }
        assertEquals(listOf("GPS", "A_GPS"), out.map {
            when (it) {
                is RenderSection -> it.title
                else -> (it as RenderRow).item.key
            }
        })
    }

    /**
     * With the switch on and no search, every row in the real schema is drawn exactly once
     * across all groups. This is the assertion that catches a card that moved somewhere the
     * renderer skips — the failure mode that hides a setting instead of showing it wrong.
     */
    @Test
    fun everySchemaRowIsDrawnExactlyOnceAcrossAllGroups() {
        val real = SettingsFixtures.asset
        val drawn = real.groups.flatMap {
            renderablesFor(it, ConfigStore.SettingsState(), "", true)
        }.filterIsInstance<RenderRow>()
        assertEquals("a row was drawn twice", drawn.size, drawn.map { it.item.key }.toSet().size)
        assertEquals(
            "a row is unreachable in the page",
            real.allItems.map { it.key }.sorted(),
            drawn.map { it.item.key }.sorted(),
        )
    }

    /** Hidden keys (Wi-Fi credentials, NTP) never reach the page. */
    @Test
    fun hiddenKeysAreNotDrawnEvenWhenSearched() {
        val real = SettingsFixtures.asset
        for (group in real.groups) {
            for (key in real.hidden) {
                val hits = renderablesFor(group, ConfigStore.SettingsState(), key, true)
                    .filterIsInstance<RenderRow>()
                    .map { it.item.key }
                assertTrue("$key appeared in ${group.title}", hits.none { it == key })
            }
        }
    }

    /**
     * A key the unit streams that this app's schema has no row for — a setting a newer
     * firmware grew — has to be reachable. It cannot be placed in a group (the schema is
     * what does the placing), so it forms a block of its own, and dropping it would make the
     * setting stop existing on the phone with nothing on screen to say why.
     */
    @Test
    fun aKeyTheSchemaDoesNotKnowIsReachableBySearch() {
        val guessed = SchemaItem(
            key = "NEW_THING",
            kind = ValueKind.INT,
            control = RowControl.NUMBER,
            label = "New thing",
            labelIsGuessed = true,
        )
        val state = ConfigStore.SettingsState(unknown = listOf(guessed))
        val found = unlabelledBlock(state, "new")
        assertEquals("the unlabelled key is not drawn", listOf(guessed), found?.body?.map { (it as RenderRow).item })
        assertNull("an empty unlabelled set made an empty block", unlabelledBlock(ConfigStore.SettingsState(), "new"))
        assertEquals(
            "the unlabelled key survived a query that does not mention it",
            null,
            unlabelledBlock(state, "gearbox"),
        )
    }

    /**
     * No widget card claims anything about itself. The one card that ever needed a line of
     * warning — the firmware installer — moved to App Settings on 2026-11-09 and carries its
     * sentence there, so `cardHint` is plumbing with nothing plumbed into it: kept for the next
     * widget card, empty today, and asserted empty so the page cannot grow a warning somewhere
     * a person will not look for one. A widget's action is still drawn inside its card — there
     * is no Tools page left to point at.
     */
    @Test
    fun noWidgetCardClaimsALineOfItsOwn() {
        val g = SchemaGroup(
            "g4",
            "Group",
            listOf(
                SchemaCard("Plain", listOf(row("PLAIN_ROW"))),
                SchemaCard("Trip", listOf(row("TRIP_AUTO_RESET")), widget = "tripReset"),
                SchemaCard("Bench", emptyList(), widget = "noiseBench"),
            ),
        )
        val blocks = blocksFor(g, ConfigStore.SettingsState(), "", false)
        assertEquals(3, blocks.size)
        assertNull("an ordinary card claims there is something elsewhere", blocks[0].card.hint)
        assertNull("a widget card points somewhere else", blocks[1].card.hint)
        assertNull(
            "a widget card grew a hint; the firmware card's warning moved to App Settings on " +
                "2026-11-09, and the next one that needs a sentence says it inside its own control",
            blocks[2].card.hint,
        )
        // A card that is only an action shows on its own title - it has no other text.
        assertEquals(
            "searching for the action card lost it",
            listOf("Bench"),
            blocksFor(g, ConfigStore.SettingsState(), "bench", false).map { it.card.title },
        )
        assertEquals(
            "a rowless action card survived a query that names neither it nor a row",
            emptyList<String>(),
            blocksFor(g, ConfigStore.SettingsState(), "speed", false).map { it.card.title },
        )
    }

    /**
     * The counts say what the box found. A card with no rows of its own (a widget card) is not
     * a setting and is not counted as one, but a group whose only hit is that card must not
     * read "0 of 18" above it either - the card the query named is open right there.
     */
    @Test
    fun theCountsSayWhatTheQueryFound() {
        val g = SchemaGroup(
            "sys", "System & General",
            listOf(
                SchemaCard("General", listOf(row("ADV_MODE"))),
                SchemaCard("Bench", emptyList(), widget = "noiseBench"),
            ),
        )
        assertEquals(
            "browsing lost the group's row count",
            "1 of 1",
            SettingsLayout.groupMeta(g, "", true),
        )
        assertEquals("1 of 1", SettingsLayout.groupMeta(g, "adv", true))
        assertEquals(
            "a title-only card was counted as zero settings",
            "1 card",
            SettingsLayout.groupMeta(g, "bench", true),
        )
        assertEquals(
            "the summary counted a rowless card as a row, or counted a whole group instead of its hits",
            "1 card matches \"bench\"",
            SettingsLayout.searchSummary(listOf(g), "bench", true),
        )
        assertEquals(
            "1 of 1 settings match \"adv\"",
            SettingsLayout.searchSummary(listOf(g), "adv", true),
        )
    }

    // -----------------------------------------------------------------------
    // The accordion: one group open, and a search that opens what it finds
    // -----------------------------------------------------------------------

    private val system = SchemaGroup(
        id = "system",
        title = "System & General",
        icon = "system",
        cards = listOf(SchemaCard("General", listOf(row("ADV_MODE")))),
    )
    private val sensors = SchemaGroup(
        id = "sensors",
        title = "Sensors Tuning",
        icon = "sensors",
        cards = listOf(
            SchemaCard(
                "GNSS",
                listOf(
                    SchemaItem("GPS_RATE", ValueKind.INT, RowControl.NUMBER, "Fix rate (Hz)"),
                    SchemaItem(
                        "GPS_BAUD", ValueKind.INT, RowControl.NUMBER, "GNSS baud rate",
                        advanced = true,
                    ),
                ),
            ),
        ),
    )
    private val display = SchemaGroup(
        id = "display",
        title = "Display",
        icon = "display",
        cards = listOf(
            SchemaCard(
                "Brightness",
                listOf(row("BRIGHT_DAY", section = "Daytime"), row("DAYTIME_GPS")),
            ),
        ),
    )
    private val groups = listOf(system, sensors, display)

    /**
     * Opening a group closes the one that was open, and tapping an open header closes it.
     * The unit page's `initDetailsAnim` in one line — a 179-row page that opens everything
     * has no structure left.
     */
    @Test
    fun theAccordionHoldsOneGroupOpenAtATime() {
        assertEquals("sensors", tapOpen(null, "sensors"))
        assertEquals("display", tapOpen("sensors", "display"))
        assertNull("tapping the open header did not close it", tapOpen("display", "display"))
    }

    /** Browsing (no query): exactly the tapped group, or none. */
    @Test
    fun noQueryMeansNothingIsOpenUntilATap() {
        assertEquals(emptySet<String>(), openGroups(null, "", groups, true))
        assertEquals(setOf("sensors"), openGroups("sensors", "  ", groups, true))
    }

    /**
     * A query opens every group that has a hit and closes the rest — the unit page sets
     * `open` on matching groups and takes it off the others. A search that has to be
     * expanded by hand looks broken: it finds rows and shows none of them.
     */
    @Test
    fun aQueryOpensEveryGroupItFinds() {
        assertEquals(
            "the search left a group with hits closed",
            setOf("sensors", "display"),
            openGroups("system", "gps", groups, true),
        )
        assertEquals(
            "an advanced-only hit opened a group that cannot show it",
            emptySet<String>(),
            openGroups("system", "baud", groups, false),
        )
        assertEquals(
            "a one-word hit in one group opened the others too",
            setOf("display"),
            openGroups("system", "daytime", groups, false),
        )
    }

    /**
     * A group with no hits is hidden while a query runs, and every group is back the moment
     * the box is empty. The old page needed a "2 matches in another group" hint because a
     * tab could leave someone stranded on an empty filter; the accordion cannot, so the hint
     * is gone — and with it the question of whether the count was a label match or a key
     * match, which nobody could predict.
     */
    @Test
    fun aGroupWithNoHitsIsHiddenAndTheOtherGroupsHintIsGone() {
        // "gps" hits Fix rate in Sensors and DAYTIME_GPS in Display; System's one row is an
        // advanced switch and stays hidden, which is the whole point of hiding.
        assertEquals(
            listOf("sensors", "display"),
            SettingsLayout.groupsShown(groups, "gps", true).map { it.id },
        )
        assertEquals(
            "a group with no hits is still on screen",
            listOf("display"),
            SettingsLayout.groupsShown(groups, "daytime", true).map { it.id },
        )
        assertEquals(
            "clearing the box did not bring the groups back",
            groups,
            SettingsLayout.groupsShown(groups, "", true),
        )
        assertEquals(
            "there is no other-groups hint any more",
            emptyList<Pair<String, Int>>(),
            SettingsLayout.otherGroupsMatching(groups, 0, "gps", true),
        )
    }

    // -----------------------------------------------------------------------
    // Columns: the unit page's grid, on the phone and on a tablet
    // -----------------------------------------------------------------------

    /** The unit page's own breakpoints — one card on a phone, two from 600 dp, three from 900. */
    @Test
    fun theColumnCountFollowsTheWidth() {
        assertEquals(1, columns(360))
        assertEquals(1, columns(599))
        assertEquals(2, columns(600))
        assertEquals(2, columns(899))
        assertEquals(3, columns(900))
        assertEquals(3, columns(1280))
    }

    /**
     * Cards pack across the columns in the schema's order, and a block that needs the width
     * (a calibration capture, a noise readout, a fuel table) takes a row of its own instead
     * of being squeezed into a third of a tablet.
     */
    @Test
    fun cardsPackRowWiseAndWideBlocksTakeTheirOwnRow() {
        val blocks = (1..5).map {
            RenderBlock(RenderCard("C$it"), listOf(RenderRow(row("KEY_$it"))), wide = it == 3)
        }
        assertEquals(
            listOf(listOf("C1", "C2"), listOf("C3"), listOf("C4", "C5")),
            pack(blocks, 2).map { row -> row.map { it.card.title } },
        )
        val plain = (1..5).map { RenderBlock(RenderCard("C$it"), listOf(RenderRow(row("KEY_$it")))) }
        assertEquals(
            listOf(listOf("C1", "C2", "C3"), listOf("C4", "C5")),
            pack(plain, 3).map { row -> row.map { it.card.title } },
        )
        // On a phone every card is its own row, wide or not.
        assertEquals(
            listOf(listOf("C1"), listOf("C2"), listOf("C3")),
            pack(blocks.take(3), 1).map { row -> row.map { it.card.title } },
        )
    }
}
