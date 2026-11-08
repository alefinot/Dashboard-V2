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
        ConfigStore.SettingsState(advanced = advanced, unknown = unknown),
        query,
        advanced,
    ).filterIsInstance<RenderRow>()

    private fun keys(rows: List<RenderRow>) = rows.map { it.item.key }

    @Test
    fun advancedRowsAppearOnlyWhenTheMasterSwitchIsOn() {
        val plain = keys(drawn())
        val open = keys(drawn(advanced = true))
        assertTrue("an advanced row leaked: $plain", !plain.any { it in setOf("CALIB_OFFSET", "BOOT_TIME_MS") })
        assertTrue("advanced rows never arrived", open.containsAll(listOf("CALIB_OFFSET", "BOOT_TIME_MS")))
        assertTrue("the switch that opens advanced settings is itself hidden", plain.contains("ADV_MODE"))
    }

    /** A whole advanced card is out until the switch is on, and in after. */
    @Test
    fun anAdvancedCardIsGatedAsACard() {
        val cards = renderablesFor(group, ConfigStore.SettingsState(), "", false)
            .filterIsInstance<RenderCard>()
        assertTrue(
            "the advanced card was drawn with the switch off: ${cards.map { it.title }}",
            cards.none { it.title == "Advanced card" },
        )
        val open = renderablesFor(group, ConfigStore.SettingsState(advanced = true), "", true)
            .filterIsInstance<RenderCard>()
        assertTrue(open.any { it.title == "Advanced card" })
    }

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

    @Test
    fun everySchemaRowIsDrawnExactlyOnceAcrossAllGroups() {
        val real = SettingsFixtures.asset
        val drawn = real.groups.flatMap {
            renderablesFor(it, ConfigStore.SettingsState(advanced = true), "", true)
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
                val hits = renderablesFor(group, ConfigStore.SettingsState(advanced = true), key, true)
                    .filterIsInstance<RenderRow>()
                    .map { it.item.key }
                assertTrue("$key appeared in ${group.title}", hits.none { it == key })
            }
        }
    }

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

    @Test
    fun onlyTheFirmwareCardSaysSomethingAboutItself() {
        val g = SchemaGroup(
            "g4",
            "Group",
            listOf(
                SchemaCard("Plain", listOf(row("PLAIN_ROW"))),
                SchemaCard("Trip", listOf(row("TRIP_AUTO_RESET")), widget = "tripReset"),
                SchemaCard("Firmware", emptyList(), widget = "firmwareUpdate"),
            ),
        )
        val blocks = blocksFor(g, ConfigStore.SettingsState(), "", false)
        assertEquals(3, blocks.size)
        assertNull("an ordinary card claims there is something elsewhere", blocks[0].card.hint)
        assertNull("a widget card points somewhere else", blocks[1].card.hint)
        assertTrue(
            "the firmware card does not say what an install risks: the image goes to the spare " +
                "flash slot and only takes charge after the unit's own checks pass, and the card " +
                "has to say that before anybody presses START",
            (blocks[2].card.hint ?: "").contains("spare flash slot"),
        )
        // A card that is only an action shows on its own title - it has no other text.
        assertEquals(
            "searching for the firmware card lost it",
            listOf("Firmware"),
            blocksFor(g, ConfigStore.SettingsState(), "firmware", false).map { it.card.title },
        )
        assertEquals(
            "a rowless action card survived a query that names neither it nor a row",
            emptyList<String>(),
            blocksFor(g, ConfigStore.SettingsState(), "speed", false).map { it.card.title },
        )
    }

    @Test
    fun theCountsSayWhatTheQueryFound() {
        val g = SchemaGroup(
            "sys", "System & General",
            listOf(
                SchemaCard("General", listOf(row("ADV_MODE"))),
                SchemaCard("Firmware", emptyList(), widget = "firmwareUpdate"),
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
            SettingsLayout.groupMeta(g, "firmware", true),
        )
        assertEquals(
            "the summary counted a rowless card as a row, or counted a whole group instead of its hits",
            "1 card matches \"firmware\"",
            SettingsLayout.searchSummary(listOf(g), "firmware", true),
        )
        assertEquals(
            "1 of 1 settings match \"adv\"",
            SettingsLayout.searchSummary(listOf(g), "adv", true),
        )
    }

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

    @Test
    fun aGroupWithNoHitsIsHiddenAndTheOtherGroupsHintIsGone() {
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
