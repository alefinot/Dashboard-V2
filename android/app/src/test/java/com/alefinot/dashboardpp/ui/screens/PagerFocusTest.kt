package com.alefinot.dashboardpp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerFocusTest {

    @Test
    fun `the settled page is in view`() {
        assertTrue(PagerFocus.inViewport(current = 1, offset = 0f, index = 1))
    }

    @Test
    fun `a page a full page away is not in view`() {
        assertFalse(PagerFocus.inViewport(current = 1, offset = 0f, index = 2))
        assertFalse(PagerFocus.inViewport(current = 1, offset = 0f, index = 0))
        assertFalse(PagerFocus.inViewport(current = 1, offset = 0f, index = 3))
    }

    @Test
    fun `the first sliver of a swipe brings the next page in`() {
        assertTrue(PagerFocus.inViewport(current = 1, offset = 0.05f, index = 2))
    }

    @Test
    fun `a swipe the other way brings the page behind it in`() {
        assertTrue(PagerFocus.inViewport(current = 1, offset = -0.05f, index = 0))
    }

    @Test
    fun `every slot of the bar is one pager index away from its neighbour`() {
        assertEquals(AppTab.values().size, BarLayout.pages.size)
        for ((i, tab) in BarLayout.pages.withIndex()) {
            assertEquals("$tab sits at $i", i, BarLayout.pageOf(tab))
        }
    }

    @Test
    fun `from the vehicle page a swipe uncovers its neighbours and nothing past them`() {
        val home = BarLayout.homePage
        assertEquals(2, home)
        assertTrue(PagerFocus.inViewport(current = home, offset = 0f, index = home))
        assertTrue(PagerFocus.inViewport(current = home, offset = 0.05f, index = home + 1))
        assertTrue(PagerFocus.inViewport(current = home, offset = -0.05f, index = home - 1))
        assertFalse(PagerFocus.inViewport(current = home, offset = 0f, index = home + 1))
        assertFalse(PagerFocus.inViewport(current = home, offset = 0f, index = home - 1))
        assertFalse(PagerFocus.inViewport(current = home, offset = 0f, index = 0))
        assertFalse(PagerFocus.inViewport(current = home, offset = 0f, index = 4))
    }
}
