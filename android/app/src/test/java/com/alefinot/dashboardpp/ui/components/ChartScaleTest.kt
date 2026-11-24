package com.alefinot.dashboardpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference-point arithmetic the trip page's trend chart draws its axes from
 * (`Implementation plans/TRIP-FIGURES-AND-ZOOMABLE-CHARTS-2026-11-24.md` §5).
 *
 * The owner's complaint was not that the old sparklines were ugly: it was that a trace with no axis
 * cannot be read — *"the graphs are useless without reference points"*. A chart's usual bug is
 * therefore not a crash but a picture that lies, and every lie in that family is arithmetic: labels
 * that do not cover the data, a step landing on 3.7, a trace welded to the ceiling because the scale
 * had no headroom, a time axis whose last label is not where the drive ended. Those are the
 * assertions below. The painter has no arithmetic left to lie with, which is the point of this file.
 */
class ChartScaleTest {

    // ------------------------------------------------------------------ the value axis

    @Test
    fun `the scale always covers the data, so a trace can never leave the plot`() {
        for ((lo, hi) in listOf(
                0.0 to 87.0,            // a drive that touched 87 km/h
                0.0 to 49.0,
                0.0 to 14.2,            // instantaneous economy
                310.0 to 316.0,         // altitude in a hilly region, nowhere near zero
                -3.0 to 2.4,
                0.0 to 0.7,
            )) {
            val s = niceScale(lo, hi)
            assertTrue("[$lo..$hi] bottom ${s.min} is above the data", s.min <= minOf(lo, hi))
            assertTrue("[$lo..$hi] top ${s.max} is below the data", s.max >= maxOf(lo, hi))
            assertTrue("[$lo..$hi] empty tick list", s.ticks.isNotEmpty())
            assertTrue("[$lo..$hi] step must be positive: ${s.step}", s.step > 0.0)
            // Everything the drive actually logged lands inside the drawn band, and a bigger value
            // always lands higher — the two things a painter needs before it maps anything to pixels.
            val fLo = s.fraction(lo)
            val fHi = s.fraction(hi)
            assertTrue("[$lo..$hi] bottom escaped the plot: $fLo", fLo in 0f..1f)
            assertTrue("[$lo..$hi] top escaped the plot: $fHi", fHi in 0f..1f)
            assertTrue("[$lo..$hi] inverted axis: $fLo vs $fHi", fLo > fHi)
        }
    }

    @Test
    fun `the step comes from the 1-2-5 ladder, never from dividing the span`() {
        // The reason a gauge face uses 1/2/5 is that those are the figures the eye already knows.
        // A label of `7.3` is a reference point you have to work out.
        for (hi in listOf(0.7, 3.0, 12.0, 49.0, 87.0, 143.0, 610.0, 1400.0, 0.041)) {
            val s = niceScale(0.0, hi)
            val mag = Math.pow(10.0, Math.floor(Math.log10(s.step)))
            val norm = s.step / mag
            assertTrue("step ${s.step} is off the ladder (normalised $norm)",
                norm in 0.999..1.001 || norm in 1.999..2.001 || norm in 4.999..5.001)
        }
    }

    @Test
    fun `ticks are evenly spaced by the step and run the whole band`() {
        val s = niceScale(0.0, 87.0)
        assertEquals(s.min, s.ticks.first(), 1e-9)
        assertEquals(s.max, s.ticks.last(), 1e-9)
        for (i in 1 until s.ticks.size) {
            assertEquals("tick $i is not one step after the last", s.step, s.ticks[i] - s.ticks[i - 1], 1e-9)
        }
    }

    @Test
    fun `labels do not wobble`() {
        // A 0.2 ladder walked by `v += step` is where `1.7999999999999998` comes from, and one such
        // label on an axis makes the whole chart look broken.
        val s = niceScale(0.0, 1.4)
        for (v in s.ticks) {
            val text = formatTick(v, s.step)
            assertTrue("wobbling label `$text`", Regex("^\\d+\\.?\\d*$").matches(text))
        }
    }

    @Test
    fun `a flat window gets a band instead of dividing by itself`() {
        // A twelve-hour motorway drive holds one speed for hour after hour. On a zero-height scale
        // `fraction` is 0/0 and the trace lands wherever floating point feels like putting it.
        val s = niceScale(62.0, 62.0)
        assertTrue("min ${s.min} must sit under the value", s.min < 62.0)
        assertTrue("max ${s.max} must sit over the value", s.max > 62.0)
        assertEquals("the flat trace must be dead centre", 0.5f, s.fraction(62.0), 1e-4f)
        assertTrue(s.ticks.isNotEmpty())
    }

    @Test
    fun `a window with nothing in it still gets an axis`() {
        val s = niceScale(Double.NaN, Double.POSITIVE_INFINITY)
        assertTrue("an empty axis is a blank chart", s.ticks.isNotEmpty())
        assertTrue(s.min < s.max)
        for (v in s.ticks) assertTrue("non-finite label", v.isFinite())
    }

    @Test
    fun `fraction runs top to bottom and never escapes the plot`() {
        val s = niceScale(10.0, 50.0)
        assertEquals(0f, s.fraction(50.0), 1e-4f)
        assertEquals(1f, s.fraction(10.0), 1e-4f)
        val mid = s.fraction(30.0)
        assertTrue("mid must be between the ends: $mid", mid in 0.4f..0.6f)
        // A figure outside the band is clamped, not drawn above the header.
        assertEquals(0f, s.fraction(900.0), 0f)
        assertEquals(1f, s.fraction(-900.0), 0f)
    }

    @Test
    fun `the axis never invents a sign the drive never logged`() {
        // The bench build padded the window by 8 % and then rounded the padding out to the step, so
        // a drive that logged 0..94.8 km/h was drawn on an axis of -20..120: a speed the wheel never
        // turned, under a trace squeezed into two thirds of its own plot.
        for ((lo, hi) in listOf(0.0 to 94.8, 0.0 to 10.0, 0.0 to 0.7, 0.0 to 87.0, 0.0 to 49.0)) {
            val s = niceScale(lo, hi)
            assertEquals("[$lo..$hi] invented a bottom: ${s.min}", 0.0, s.min, 1e-9)
        }
        // And the mirror case: a window whose top is zero must not grow a positive ceiling either.
        assertEquals(0.0, niceScale(-4.0, 0.0).max, 1e-9)
        // A window that really does cross zero keeps both sides.
        val s = niceScale(-3.0, 2.4)
        assertTrue("crossing data lost its negative side: ${s.min}", s.min < 0.0)
        assertTrue("crossing data lost its positive side: ${s.max}", s.max > 0.0)
    }

    @Test
    fun `the scale uses its plot instead of parking the trace in the middle of it`() {
        // Rounding out to the step is the headroom; it must not cost a whole gridline at each end.
        for ((lo, hi) in listOf(0.0 to 94.8, 0.0 to 10.0, 0.0 to 0.7, 12.0 to 61.0, -3.0 to 2.4)) {
            val s = niceScale(lo, hi)
            assertTrue("[$lo..$hi] ${s.max - hi} of air above the data on a step of ${s.step}",
                s.max - hi <= s.step + 1e-9)
            assertTrue("[$lo..$hi] ${lo - s.min} of air below the data on a step of ${s.step}",
                lo - s.min <= s.step + 1e-9)
            // Four target gaps may round out to five; a sixth label means the axis is the story.
            assertTrue("[$lo..$hi] gave ${s.ticks.size} labels", s.ticks.size in 2..6)
        }
    }

    // ------------------------------------------------------------------ the time axis

    @Test
    fun `the window's own end is always labelled, and nothing beyond it`() {
        for ((from, to) in listOf(0 to 70, 95 to 105, 0 to 1200, 0 to 43200, 3599 to 3601)) {
            val t = timeTicks(from, to)
            assertTrue("empty time axis for [$from..$to)", t.isNotEmpty())
            assertEquals("the right edge of every zoom must be anchored", to, t.last())
            for (v in t) {
                assertTrue("label $v lands before the window opens", v >= from)
                assertTrue("label $v at or past the window's end: $v", v <= to)
            }
            for (i in 1 until t.size) {
                assertTrue("time labels out of order: $t", t[i] > t[i - 1])
            }
        }
    }

    @Test
    fun `the label count stays near the target at every zoom`() {
        for (span in intArrayOf(10, 30, 70, 125, 600, 1200, 3600, 10_000, 43_200)) {
            val t = timeTicks(0, span, 4)
            assertTrue("$span s gave ${t.size} labels", t.size in 1..5)
        }
    }

    @Test
    fun `time steps are whole minutes or hours, not a division of the span`() {
        // A 90 s window is 1:30, and a step of 75 s would label every quarter-past-something.
        val ladder = setOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200)
        for (span in intArrayOf(60, 240, 1200, 4000, 20_000, 43_200)) {
            val t = timeTicks(0, span)
            if (t.size < 2) continue
            // The **first** hop is the step. The last one is the jump to the window's own end, which
            // is deliberately short and deliberately off the ladder.
            val step = t[1] - t[0]
            assertTrue("step $step for a $span s window is not a readable time", ladder.contains(step))
        }
    }

    @Test
    fun `seconds on the axis are decided by the window, not by the caller's mood`() {
        assertTrue("a 10 minute window is about minutes and seconds", showSecondsOnAxis(600))
        assertTrue(showSecondsOnAxis(10))
        assertTrue("an hour-wide axis spells seconds nobody can use", !showSecondsOnAxis(601))
        assertTrue(!showSecondsOnAxis(43_200))
    }

    // ------------------------------------------------------------------ labels

    @Test
    fun `clock labels that would be laid on top of each other are dropped, and the end survives`() {
        // The bench build of a 4 h 47 m drive printed `15:32` and `16:20` **touching** at the right
        // edge — uiautomator gave their bounds as [798..860] and [860..1003] — and the same thing came
        // back at `12:22` / `12:29` one pan later. Nice steps are arithmetic, and arithmetic does not
        // know a label is 52 dp wide, nor that the label at the edge is pulled inside the plot.
        //
        // These are the Mi 9 Lite's real numbers: a 805 px plot behind a 121 px gutter, a 52 dp label
        // with 12 dp of air beside it at 2.625 px/dp.
        val plotW = 805f
        val gutter = 121f
        val label = 64f * 2.625f

        // The whole drive, as the page opens it.
        val drive = timeTicks(0, 17256)
        assertEquals("this test is about the bench's own axis", listOf(0, 7200, 14400, 17256), drive)
        val whole = fitTimeTicks(drive, 0, 17256, label / plotW, gutter / plotW)
        assertEquals("the end is the label the owner is reading", 17256, whole.last())
        assertEquals("the crowded neighbour goes, the ones with room stay", listOf(0, 7200, 17256), whole)

        // The same drive one pan later, which is where the second collision showed up.
        val panned = timeTicks(1251, 3408)
        assertEquals("the bench's panned window", listOf(1800, 2400, 3000, 3408), panned)
        val fitted = fitTimeTicks(panned, 1251, 3408, label / plotW, gutter / plotW)
        assertEquals(listOf(1800, 2400, 3408), fitted)
        assertTrue("earlier labels that fit are kept: $fitted", fitted.containsAll(listOf(1800, 2400)))
        assertNoOverlap(fitted, 1251, 3408, plotW, gutter, label)
        assertNoOverlap(whole, 0, 17256, plotW, gutter, label)
    }

    @Test
    fun `fitting the time axis never reorders it, invents a label, or empties it`() {
        val plotW = 805f
        val gutter = 121f
        // Every label width from "no room needed" to wider than the plot itself, and windows opened at
        // several zooms, so the edge clamp is met from many directions, not just at the drive's start.
        val widths = floatArrayOf(0f, 1f, 40f, 136.5f, 168f, 300f, 900f)
        for (to in intArrayOf(70, 105, 1200, 17256, 43_200, 10)) {
            for (from in intArrayOf(0, 1, to / 3, to - to / 8)) {
                if (to - from < 2) continue
                val stepped = timeTicks(from, to)
                for (labelPx in widths) {
                    val fitted = fitTimeTicks(stepped, from, to, labelPx / plotW, gutter / plotW)
                    assertTrue("an empty axis for $from..$to at $labelPx px", fitted.isNotEmpty())
                    assertEquals(
                        "the end must survive a $labelPx px label on a $from..$to window",
                        to, fitted.last(),
                    )
                    assertTrue("invented a label: $fitted", stepped.containsAll(fitted))
                    assertTrue("reordered: $fitted", fitted == fitted.sorted())
                    for (i in 1 until fitted.size) assertTrue("not increasing: $fitted", fitted[i] > fitted[i - 1])
                    assertNoOverlap(fitted, from, to, plotW, gutter, labelPx)
                }
                assertEquals(
                    "room to spare must change nothing",
                    stepped, fitTimeTicks(stepped, from, to, 1f / plotW, gutter / plotW),
                )
            }
        }
    }

    /**
     * The verdict, measured the way the painter lays labels out — centred on the tick, the pair at the
     * edges pulled inside the plot. Deliberately an independent restatement of that geometry in pixels:
     * if [fitTimeTicks] and the painter ever disagree, this is what says so.
     */
    private fun assertNoOverlap(
        kept: List<Int>,
        fromSec: Int,
        toSec: Int,
        plotW: Float,
        gutter: Float,
        labelPx: Float,
    ) {
        val span = (toSec - fromSec).coerceAtLeast(1).toFloat()
        fun left(sec: Int): Float =
            (gutter + plotW * ((sec - fromSec) / span) - labelPx / 2f)
                .coerceIn(0f, (gutter + plotW - labelPx).coerceAtLeast(0f))
        for (i in 1 until kept.size) {
            val a = left(kept[i - 1])
            val b = left(kept[i])
            assertTrue(
                "kept pair ${kept[i - 1]}..${kept[i]} collides: $a + $labelPx > $b " +
                    "on $fromSec..$toSec",
                b - a >= labelPx - 0.001f,
            )
        }
    }

    @Test
    fun `a value label carries only the decimals its step can justify`() {
        assertEquals("60", formatTick(60.0, 20.0))
        assertEquals("0.0", formatTick(0.0, 0.2))
        // 0.5 km/L of instantaneous economy is a real figure: rounding it to `1` loses the reading.
        assertEquals("12.5", formatTick(12.5, 0.5))
        assertEquals("0.05", formatTick(0.05, 0.01))
    }
}
