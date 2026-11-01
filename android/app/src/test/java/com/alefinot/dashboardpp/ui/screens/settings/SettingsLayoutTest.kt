package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ui.screens.renderablesFor
import com.alefinot.dashboardpp.ui.screens.RenderCard
import com.alefinot.dashboardpp.ui.screens.RenderRow
import com.alefinot.dashboardpp.ui.screens.RenderSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows the settings page is allowed to draw, and in what order.
 *
 * The unit's form map has 242 rows; the generated schema describes 198 of them; the
 * page draws a subset of those depending on the master switch and the search box.
 * Every one of those reductions is a setting the owner cannot reach, so each has to
 * be written down with its reason — and this is the check that a row never disappears
 * twice.
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
        // The switch that opens the advanced rows has to stay reachable with them off,
        // or the gate is locked from the outside.
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

    /**
     * A widget card is a button plus its rows, not a button instead of them. The unit's
     * own page drew the capture button above the same settings; hiding the rows behind
     * the action would put values in a place nobody looks.
     */
    @Test
    fun aWidgetCardStillCarriesItsOwnRows() {
        val rows = keys(drawn())
        assertTrue("the widget card's settings vanished", rows.contains("ENABLE_AUTO_BRIGHTNESS"))
        val card = renderablesFor(group, ConfigStore.SettingsState(), "", false)
            .filterIsInstance<RenderCard>()
            .first { it.title == "Auto brightness" }
        assertEquals("its button is on the Tools page", card.hint)
    }

    /** Search matches the label and the firmware key, because owners read one and
     *  the changelog, docs and error strings say the other. */
    @Test
    fun searchSeesLabelsAndKeys() {
        assertEquals(listOf("GPS_RATE"), keys(drawn(query = "gps rate")))
        assertEquals(listOf("CALIB_OFFSET"), keys(drawn(query = "calib_", advanced = true)))
        assertEquals(
            "search matched a label that is not there",
            emptyList<String>(),
            keys(drawn(query = "gearbox")),
        )
    }

    /** An advanced row found by search still has to be inside the advanced gate. */
    @Test
    fun searchDoesNotWalkThroughTheAdvancedGate() {
        assertTrue("search reached an advanced row", keys(drawn(query = "calib")).isEmpty())
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
                }
            },
        )
    }

    /**
     * With the switch on and no search, every row in the real schema is drawn exactly
     * once across all four groups. This is the assertion that catches a card that moved
     * somewhere the renderer skips — the failure mode that hides a setting instead of
     * showing it wrong.
     */
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

    /**
     * A key the unit streams that this app's schema has no row for — a setting a newer
     * firmware grew — has to be reachable. Dropping it would make the setting stop
     * existing on the phone with nothing on screen to say why.
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
        val rows = drawn(query = "new", unknown = listOf(guessed))
        assertTrue("the unlabelled key is not drawn", guessed in rows.map { it.item })
    }

    /** Only a card that grew a button on the unit's page says where that button is. */
    @Test
    fun onlyAWidgetCardPointsSomewhereElse() {
        val g = SchemaGroup(
            "g3",
            "Group",
            listOf(
                SchemaCard("Plain", listOf(row("PLAIN_ROW"))),
                SchemaCard("Has a button", listOf(row("BUTTON_ROW")), widget = "tripReset"),
            ),
        )
        val cards = renderablesFor(g, ConfigStore.SettingsState(), "", false)
            .filterIsInstance<RenderCard>()
        assertEquals(2, cards.size)
        assertNull("an ordinary card claims there is something elsewhere", cards.first().hint)
        assertTrue(
            "the widget card does not say where its action is",
            (cards.last().hint ?: "").contains("Tools"),
        )
    }
}
