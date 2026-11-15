package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The element grouping the UI Layout list draws, and the hints it prints.
 *
 * The list is only honest if the grouping *is* the schema's grouping: every row of the
 * group in exactly one element, in the order the presentation lists them, nothing
 * reachable only by deleting something. Those are the rules [LayoutElements] holds, so
 * they are the rules tested — against the real generated asset, because a hand-typed
 * fixture of what the asset says would be testing the fixture.
 */
class LayoutElementsTest {

    private val uiGroup: SchemaGroup
        get() = SettingsFixtures.asset.groups.first { it.id == "ui_layout" }

    private fun toggle(key: String, element: String? = null) = SchemaItem(
        key = key,
        kind = ValueKind.BOOL,
        control = RowControl.TOGGLE,
        label = "Show $key",
        elementId = element,
        elementLabel = element,
        defaultValue = true,
    )

    private fun number(key: String, element: String? = null, min: Double? = null, max: Double? = null) =
        SchemaItem(
            key = key,
            kind = ValueKind.INT,
            control = RowControl.NUMBER,
            label = key,
            elementId = element,
            elementLabel = element,
            min = min,
            max = max,
            defaultValue = 0,
        )

    private fun xy(element: String) = SchemaItem(
        key = "OFFSET_${element.uppercase()}_X",
        keyY = "OFFSET_${element.uppercase()}_Y",
        kind = ValueKind.XY,
        control = RowControl.PAIR,
        label = "Position",
        elementId = element,
        elementLabel = element,
        min = -4096.0,
        max = 4096.0,
        minY = -4096.0,
        maxY = 4096.0,
        defaultValue = 0,
        defaultY = 0,
    )

    private fun state(vararg rows: Pair<String, Any?>) =
        SettingsState(rows = rows.toMap().mapValues { RowView(it.value) })

    /**
     * A state with one `xy` row carrying both halves, the way the store always draws a
     * position row — [RowView.yValue] is the second key and [state] cannot set it.
     */
    private fun stateAt(positionKey: String, x: Any?, y: Any?, vararg rows: Pair<String, Any?>) =
        SettingsState(
            rows = rows.toMap().mapValues { RowView(it.value) } + (positionKey to RowView(x, y)),
        )

    // ---- the asset's own shape --------------------------------------------------

    @Test
    fun theUiLayoutGroupIsElementDrivenAndEveryOtherGroupIsNot() {
        assertTrue(LayoutElements.isElementGroup(uiGroup))
        for (group in SettingsFixtures.asset.groups.filter { it.id != "ui_layout" }) {
            assertFalse("${group.id} renders cards", LayoutElements.isElementGroup(group))
        }
    }

    @Test
    fun everyUiLayoutRowLandsInExactlyOneElement() {
        val elements = LayoutElements.elementsOf(uiGroup)
        val schemaKeys = uiGroup.cards.flatMap { card ->
            card.items.flatMap { listOf(it.key) + listOfNotNull(it.keyY) }
        }
        val elementKeys = elements.flatMap { e ->
            e.items.flatMap { listOf(it.key) + listOfNotNull(it.keyY) }
        }
        assertEquals("an element lost or gained a row against the asset", schemaKeys.sorted(), elementKeys.sorted())
        assertEquals("a row split across two elements", elementKeys.size, elementKeys.toSet().size)
    }

    @Test
    fun noUiLayoutRowIsLeftUnassigned() {
        val elements = LayoutElements.elementsOf(uiGroup)
        assertTrue(
            "the presentation forgot to place: " +
                elements.filter { it.id == LayoutElements.UNASSIGNED_ID }.flatMap { e -> e.items.map { it.key } },
            elements.none { it.id == LayoutElements.UNASSIGNED_ID },
        )
    }

    @Test
    fun elementOrderIsFirstAppearanceOrder() {
        val elements = LayoutElements.elementsOf(uiGroup)
        assertEquals(
            listOf("center", "speed", "speedUnit", "speedSource", "clock"),
            elements.map { it.id }.take(5),
        )
        assertEquals("center", elements.first().id)
        assertTrue(elements.any { it.id == "barRight" })
    }

    @Test
    fun elementIdAndLabelAreParsedFromTheAsset() {
        val clock = SettingsFixtures.asset.allItems.first { it.key == "OFFSET_BIG_TIME_X" }
        assertEquals("clock", clock.elementId)
        assertEquals("Clock", clock.elementLabel)
        // A row outside the group carries no element: the stamp is the group's device.
        val fuel = SettingsFixtures.asset.allItems.first { it.key == "FUEL_EXC_RES_OHM" }
        assertNull(fuel.elementId)
    }

    // ---- the pure rules ----------------------------------------------------------

    @Test
    fun unassignedRowsGetTheirTrailingBucket() {
        val group = SchemaGroup(
            id = "g",
            title = "Test",
            cards = listOf(
                SchemaCard(
                    "UI Layout",
                    listOf(
                        toggle("SHOW_ORPHAN"), // placed before any element_header
                        toggle("SHOW_ELEMENT_CLOCK", "clock"),
                    ),
                ),
            ),
        )
        val elements = LayoutElements.elementsOf(group)
        assertEquals(2, elements.size)
        assertEquals("clock", elements[0].id)
        assertEquals(LayoutElements.UNASSIGNED_ID, elements[1].id)
        assertEquals("Other rows", elements[1].label)
        assertEquals(listOf("SHOW_ORPHAN"), elements[1].items.map { it.key })
    }

    @Test
    fun theToggleOfAnElementIsItsFirstSwitchRow() {
        val clock = LayoutElements.elementsOf(uiGroup).first { it.id == "clock" }
        assertEquals("SHOW_ELEMENT_TIME", LayoutElements.toggleOf(clock)?.key)
        // Ghost Digits has a toggle; an element with none gets null, so the list draws
        // no switch rather than wiring one to the wrong row.
        val viewport = LayoutElements.elementsOf(uiGroup).first { it.id == "center" }
        assertNull(LayoutElements.toggleOf(viewport))
    }

    @Test
    fun hiddenFollowsTheElementToggle() {
        val clock = LayoutElements.elementsOf(uiGroup).first { it.id == "clock" }
        val on = state("SHOW_ELEMENT_TIME" to true)
        val off = state("SHOW_ELEMENT_TIME" to false)
        val unknown = state()
        assertEquals(false, LayoutElements.hiddenNow(clock, on))
        assertEquals(true, LayoutElements.hiddenNow(clock, off))
        assertNull(LayoutElements.hiddenNow(clock, unknown))
    }

    @Test
    fun summarySaysWhereItIsHowManyDigitsAndWhatSize() {
        val speed = LayoutElements.elementsOf(uiGroup).first { it.id == "speed" }
        val speedState = stateAt(
            "OFFSET_BIG_SPEED_NUM_X", 107, -91,
            "SHOW_ELEMENT_SPEED" to true,
            "SPEED_DIGITS" to 2L,
        )
        assertEquals("offset 107 / -91 · 2 digits", LayoutElements.summary(speed, speedState))

        // Off says so first — the one fact the list owes the eye before any number.
        val hidden = LayoutElements.summary(speed, stateAt(
            "OFFSET_BIG_SPEED_NUM_X", 107, -91,
            "SHOW_ELEMENT_SPEED" to false,
            "SPEED_DIGITS" to 2L,
        ))
        assertTrue(hidden, hidden!!.startsWith("hidden"))

        // An int/dec digit pair is one part, not two.
        val odo = LayoutElements.elementsOf(uiGroup).first { it.id == "odo" }
        val odoState = stateAt(
            "OFFSET_BIG_ODO_X", 0, 0,
            "SHOW_ELEMENT_ODO" to true,
            "ODO_INT_DIGITS" to 5L,
            "ODO_DEC_DIGITS" to 1L,
        )
        assertEquals("offset 0 / 0 · 5+1 digits", LayoutElements.summary(odo, odoState))

        // The viewport centre is not an offset — the word is the fact that it is absolute.
        val center = LayoutElements.elementsOf(uiGroup).first { it.id == "center" }
        val centerState = stateAt("BIG_CENTER_X", 240, 160)
        assertEquals("center 240 / 160", LayoutElements.summary(center, centerState))

        // The weather widget: position plus its box.
        val weather = LayoutElements.elementsOf(uiGroup).first { it.id == "weather" }
        val weatherState = stateAt(
            "OFFSET_WEATHER_X", 400, 12,
            "SHOW_ELEMENT_WEATHER" to true,
            "WEATHER_WIDTH" to 96L,
            "WEATHER_HEIGHT" to 48L,
        )
        assertEquals("offset 400 / 12 · 96x48", LayoutElements.summary(weather, weatherState))
    }

    @Test
    fun searchKeepsElementsByNameAndCountsTheirRows() {
        val elements = LayoutElements.elementsOf(uiGroup)
        val clock = elements.first { it.id == "clock" }
        // The element's own name matches the element, with zero rows found by content:
        // "clock" is the whole element's name, not one of its rows.
        assertTrue(LayoutElements.elementShown(clock, "clock"))
        assertEquals(0, LayoutElements.matchCount(clock, "xyzzy", true))
        // A row word counts, and finds exactly the rows it names.
        assertEquals(1, LayoutElements.matchCount(clock, "offset", true))
        // Nothing in the element survives a word it has never spoken.
        assertFalse(LayoutElements.elementShown(clock, "fuel"))
        // No query is every row.
        assertEquals(clock.items.size, LayoutElements.matchCount(clock, "", true))
    }

    @Test
    fun sliderWindowsSpanTheScreenButNeverLeaveTheFirmwareBand() {
        val elements = LayoutElements.elementsOf(uiGroup)
        val clock = elements.first { it.id == "clock" }
        val pos = clock.items.first { it.kind == ValueKind.XY }

        // Default panel 480x320: an offset can travel half a screen either way.
        var window = LayoutElements.axisWindow("clock", pos, state(), xAxis = true)
        assertEquals(-240.0..240.0, window)
        window = LayoutElements.axisWindow("clock", pos, state(), xAxis = false)
        assertEquals(-160.0..160.0, window)

        // The centre element ranges over the screen instead of around its middle.
        val center = elements.first { it.id == "center" }
        val centerPos = center.items.first { it.kind == ValueKind.XY }
        window = LayoutElements.axisWindow("center", centerPos, state(), xAxis = true)
        assertEquals(0.0..480.0, window)

        // A read that says the panel is 320x480 moves the window with it.
        val portrait = state("DISPLAY_WIDTH" to 320, "DISPLAY_HEIGHT" to 480)
        window = LayoutElements.axisWindow("clock", pos, portrait, xAxis = true)
        assertEquals(-160.0..160.0, window)
        window = LayoutElements.axisWindow("center", centerPos, portrait, xAxis = false)
        assertEquals(0.0..480.0, window)

        // A narrow firmware band wins over the screen: the slider must not be able to
        // ask for what the unit would clamp. The keyboard box is where the band's rest
        // lives, not the slider's business.
        val banded = pos.copy(min = -100.0, max = 100.0)
        window = LayoutElements.axisWindow("clock", banded, state(), xAxis = true)
        assertEquals(-100.0..100.0, window)
    }

    @Test
    fun truthyReadsWhatTheUnitActuallySends() {
        assertTrue(LayoutElements.truthy(true))
        assertTrue(LayoutElements.truthy(1))
        assertTrue(LayoutElements.truthy("1"))
        assertFalse(LayoutElements.truthy(false))
        assertFalse(LayoutElements.truthy(0))
        assertFalse(LayoutElements.truthy(null))
    }
}
