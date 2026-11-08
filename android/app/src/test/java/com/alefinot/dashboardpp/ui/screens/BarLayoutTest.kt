package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarLayoutTest {

    @Test
    fun `the bar and the swipe order are one list, and nothing is missing from it`() {
        assertEquals(
            listOf(AppTab.LIVE, AppTab.TRIPS, AppTab.VEHICLES, AppTab.WEB, AppTab.SETTINGS),
            BarLayout.pages,
        )
        assertEquals(BarLayout.pages.size, AppTab.values().size)
        for (tab in AppTab.values()) {
            assertTrue("$tab", BarLayout.pages.contains(tab))
        }
    }

    @Test
    fun `the vehicle page is the centre slot and the page the app opens on`() {
        assertEquals(AppTab.VEHICLES, BarLayout.centre)
        assertEquals(2, BarLayout.pages.indexOf(BarLayout.centre))
        assertEquals(BarLayout.centre, BarLayout.home)
        assertEquals(2, BarLayout.homePage)
    }

    @Test
    fun `the vehicle glyph takes the centre treatment and nothing else does`() {
        val car = BarLayout.glyph(AppTab.VEHICLES)
        assertEquals(1.4f, car.weight, 0f)
        assertEquals(30.dp, car.size)
        assertEquals(52.dp, car.box)
        assertTrue(car.colored)
        for (tab in BarLayout.pages.filter { it != AppTab.VEHICLES }) {
            val g = BarLayout.glyph(tab)
            assertEquals("$tab weight", 1f, g.weight, 0f)
            assertEquals("$tab size", 22.dp, g.size)
            assertEquals("$tab box", 48.dp, g.box)
            assertFalse("$tab colored", g.colored)
        }
    }

    @Test
    fun `every slot keeps its name as the accessibility description`() {
        for (tab in AppTab.values()) {
            assertTrue("$tab", BarLayout.glyph(tab).desc.isNotBlank())
        }
    }

    @Test
    fun `the car glyph is drawn on the same grid as the others`() {
        assertEquals(24f, HudIcons.Car.viewportWidth, 0f)
        assertEquals(24f, HudIcons.Car.viewportHeight, 0f)
    }
}
