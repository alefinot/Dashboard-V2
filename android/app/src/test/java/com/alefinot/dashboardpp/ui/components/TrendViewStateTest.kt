package com.alefinot.dashboardpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window arithmetic of the trip page's trend chart (`TrendChart.kt`'s [TrendViewState]).
 *
 * Pinch and pan are the two gestures a bench phone cannot be driven through over ADB on Android 11 —
 * there is no multi-touch `input` there — so the arithmetic a pinch runs is held here instead, and
 * the fingers are verified against this on the phone. A window is the one piece of this chart that a
 * user can put somewhere it should not go: wider than the drive (inventing minutes the car did not
 * log), narrower than the plot can resolve, or off the end of the file.
 */
class TrendViewStateTest {

    /** A view state whose drive of [driveSec] whole seconds has just been read. */
    private fun opened(driveSec: Int = 1000): TrendViewState = TrendViewState().apply { openAt(driveSec) }

    @Test
    fun `a drive opens whole, and is not zoomed`() {
        val v = opened(1000)
        assertEquals(1000, v.endSec)
        assertEquals(0, v.fromSec)
        assertEquals(1000, v.toSec)
        assertEquals(1000, v.spanSec)
        assertFalse("a page showing the whole drive must not offer a refit", v.isZoomed)
    }

    @Test
    fun `zooming out never invents time the car did not log`() {
        val v = opened(1000)
        v.zoomBy(0.1f, 500)
        assertEquals("the widest window is the drive itself", 1000, v.spanSec)
        assertEquals(0, v.fromSec)
        assertFalse(v.isZoomed)
    }

    @Test
    fun `zooming in stops where the plot stops being able to show anything`() {
        val v = opened(1000)
        v.zoomBy(1000f, 500)
        assertEquals(TrendViewState.MIN_WINDOW_SEC, v.spanSec)
        assertTrue("a committed pinch must not park off the end of the file", v.toSec <= v.endSec)
    }

    @Test
    fun `the instant being pinched stays under the fingers`() {
        val v = opened(1000)
        // Half way through a 1000 s window, pinched to a quarter of it: the focus must still sit
        // about half way through the *new* window, not jump to an edge.
        v.zoomBy(4f, 500)
        assertEquals(250, v.spanSec)
        val focus = (v.fromSec until v.toSec).first { kotlin.math.abs(it - 500) <= 1 }
        val fraction = (focus - v.fromSec).toFloat() / v.spanSec
        assertTrue("focus drifted to $fraction of the window", fraction in 0.4f..0.6f)
    }

    @Test
    fun `a zoom always stays inside the drive`() {
        val v = opened(1000)
        for (factor in floatArrayOf(2f, 3f, 7f, 2f, 1.5f)) {
            for (focus in intArrayOf(0, 7, 499, 993, 999)) {
                v.zoomBy(factor, focus)
                assertTrue("from ${v.fromSec} below zero", v.fromSec >= 0)
                assertTrue("to ${v.toSec} past the end", v.toSec <= v.endSec)
                assertTrue("span ${v.spanSec} below the floor", v.spanSec >= TrendViewState.MIN_WINDOW_SEC)
            }
        }
    }

    @Test
    fun `a sub-second drag still creeps instead of sticking at zero`() {
        val v = opened(1000)
        v.zoomBy(100f, 500)                     // a 10 s window: a pixel is a hundredth of a second
        val from = v.fromSec
        repeat(200) {
            v.panBy(0.05f)                      // 10 s of fractional drag, each piece under a second
            assertEquals("a pan step must not change the window's width", 10, v.spanSec)
        }
        assertTrue("200 fractional pans moved nothing", v.fromSec > from)
    }

    @Test
    fun `a pan slides the window and never eats it`() {
        val v = opened(1000)
        v.zoomBy(100f, 500)
        for (step in 0 until 40) {
            v.panBy(3f)
            assertEquals("step $step narrowed the window", 10, v.spanSec)
            assertTrue("step $step ran off the end", v.toSec <= v.endSec)
        }
        for (step in 0 until 60) {
            v.panBy(-7f)
            assertEquals("step $step narrowed the window going back", 10, v.spanSec)
            assertTrue("step $step ran before the start", v.fromSec >= 0)
        }
    }

    @Test
    fun `a pan cannot leave the drive at either end`() {
        val v = opened(1000)
        v.panBy(1_000_000f)
        assertEquals(v.endSec, v.toSec)
        assertEquals(1000 - v.spanSec, v.fromSec)
        v.panBy(-1_000_000f)
        assertEquals(0, v.fromSec)
        assertEquals(v.spanSec, v.toSec)
    }

    @Test
    fun `fit puts the whole drive back and takes the cursor away`() {
        val v = opened(1000)
        v.zoomBy(4f, 500)
        v.cursorSec = 400
        assertTrue(v.isZoomed)
        v.fit()
        assertEquals(0, v.fromSec)
        assertEquals(1000, v.toSec)
        assertEquals("a double-tap means 'I am looking at everything again'", -1, v.cursorSec)
        assertFalse(v.isZoomed)
    }

    @Test
    fun `a pointer maps to a second the drive actually has`() {
        val v = opened(1000)
        // 900 px of plot behind a 100 px gutter: the two edges and the middle.
        assertEquals(v.fromSec, v.secAt(0f, 100f, 1000))
        assertEquals(v.toSec - 1, v.secAt(2000f, 100f, 1000))
        val mid = v.secAt(550f, 100f, 1000)
        assertTrue("mid landed at $mid", mid in 495..505)
        v.zoomBy(10f, 500)
        for (x in intArrayOf(-500, 0, 100, 550, 1000, 5000)) {
            val sec = v.secAt(x.toFloat(), 100f, 1000)
            assertTrue("x=$x gave second $sec outside the window", sec in v.fromSec until v.toSec)
        }
    }

    @Test
    fun `nothing the chart can hand it moves an unopened window`() {
        val v = TrendViewState()
        v.panBy(100f)
        v.zoomBy(4f, 10)
        assertEquals("a window with no drive behind it stays put", 0, v.fromSec)
        assertEquals(1, v.toSec)
        assertFalse(v.isZoomed)
    }
}
