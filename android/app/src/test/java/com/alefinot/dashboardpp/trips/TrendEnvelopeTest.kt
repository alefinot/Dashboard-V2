package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-view reducer behind the trip page's trend chart
 * (`Implementation plans/TRIP-FIGURES-AND-ZOOMABLE-CHARTS-2026-11-24.md` §5).
 *
 * The owner asked for the chart to "approximate while zoomed out to guarantee a certain visibility
 * standard". These tests are that standard as numbers: a spike and a dip both survive any amount of
 * zoomed-out reduction, samples outside the window never leak into it, and once the window holds no
 * more samples than the plot has pixels the reduction stops — a zoomed-in chart must show the raw
 * drive, never less of it.
 */
class TrendEnvelopeTest {

    private fun at(seconds: IntArray, values: FloatArray) = TrendChannel(values, seconds)

    private fun steady(n: Int, value: Float, perSampleSec: Int = 1) =
        TrendChannel(FloatArray(n) { value }, IntArray(n) { it * perSampleSec })

    @Test
    fun `an empty channel answers zero buckets`() {
        val env = EnvelopeSink(16)
        val filled = trendEnvelope(TrendChannel.EMPTY, 0, 100, 8, env)
        assertEquals(0, filled)
        assertEquals(0, env.count)
    }

    @Test
    fun `one sample in the window is one bucket holding that sample`() {
        val ch = at(intArrayOf(7), floatArrayOf(42f))
        val env = EnvelopeSink(8)
        assertEquals(1, trendEnvelope(ch, 0, 100, 8, env))
        assertEquals(42f, env.lo[0], 0f)
        assertEquals(42f, env.hi[0], 0f)
        assertEquals(7, env.midTime[0])
    }

    @Test
    fun `a single-sample spike survives being zoomed out`() {
        val values = FloatArray(400) { 60f }
        values[200] = 180f
        val ch = at(IntArray(400) { it }, values)
        val env = EnvelopeSink(400)
        val filled = trendEnvelope(ch, 0, 400, 20, env)
        assertEquals(20, filled)
        assertEquals("the peak must reach the top of the envelope, not the bucket average",
            180f, env.hi.maxOver(0, filled), 0f)
        assertEquals(60f, env.lo.minOver(0, filled), 0f)
    }

    @Test
    fun `a single-sample dip survives too, which the max-only bucketing could not show`() {
        val values = FloatArray(400) { 100f }
        values[37] = 3f
        val ch = at(IntArray(400) { it }, values)
        val env = EnvelopeSink(400)
        val filled = trendEnvelope(ch, 0, 400, 20, env)
        assertEquals(3f, env.lo.minOver(0, filled), 0f)
    }

    @Test
    fun `samples outside the window never leak in`() {
        val values = FloatArray(100) { 50f }
        values[0] = 9999f
        values[99] = -9999f
        val ch = at(IntArray(100) { it }, values)
        val env = EnvelopeSink(100)
        val filled = trendEnvelope(ch, 10, 90, 1, env)
        assertEquals(1, filled)
        assertEquals(50f, env.lo[0], 0f)
        assertEquals(50f, env.hi[0], 0f)
        assertEquals("the bucket's mean is the window's mean", 50f, env.mean[0], 0f)
    }

    @Test
    fun `window bounds are half-open, so neighbouring windows share no sample`() {
        val values = FloatArray(10) { it.toFloat() }
        val numbered = at(IntArray(10) { it }, values)
        val left = EnvelopeSink(16)
        trendEnvelope(numbered, 0, 5, 1, left)
        assertEquals("window [0,5) holds 0..4", 4f, left.hi[0], 0f)
        val right = EnvelopeSink(16)
        trendEnvelope(numbered, 5, 10, 1, right)
        assertEquals("window [5,10) starts at 5, so 5 is counted once", 5f, right.lo[0], 0f)
        assertEquals(9f, right.hi[0], 0f)
    }

    @Test
    fun `below the pixel budget the reduction stops and every sample is drawn`() {
        val values = FloatArray(8) { it.toFloat() }
        val numbered = at(IntArray(8) { it }, values)
        val env = EnvelopeSink(32)
        val filled = trendEnvelope(numbered, 0, 8, 40, env)
        assertEquals("one bucket per sample — no approximation at all", 8, filled)
        for (i in 0 until filled) {
            assertEquals("bucket $i must be the raw sample", values[i], env.lo[i], 0f)
            assertEquals(values[i], env.hi[i], 0f)
            assertEquals(i, env.midTime[i])
        }
    }

    @Test
    fun `fewer samples than buckets are drawn natively, not padded`() {
        // Two samples in a window the size of four buckets: the pixel budget says stop approximating,
        // so the painter gets exactly two buckets and nothing to skip.
        val ch = at(intArrayOf(900, 980), floatArrayOf(7f, 9f))
        val env = EnvelopeSink(8)
        val filled = trendEnvelope(ch, 0, 1000, 4, env)
        assertEquals(2, filled)
        assertEquals(7f, env.lo[0], 0f)
        assertEquals(9f, env.hi[1], 0f)
        assertEquals(900, env.midTime[0])
        assertEquals(980, env.midTime[1])
    }

    @Test
    fun `buckets past the last sample are marked empty rather than left stale`() {
        // Samples only in the first fifth of the window: the buckets after them must not repeat the
        // last value across the rest of the plot.
        val ch = steady(20, 5f)
        val env = EnvelopeSink(8)
        env.lo[7] = 5f; env.hi[7] = 5f
        val filled = trendEnvelope(ch, 0, 1000, 8, env)
        assertTrue("only the buckets up to the last sample count: $filled", filled <= 2)
        for (b in filled until 8) {
            assertTrue("bucket $b must be marked empty", env.hi[b] < env.lo[b])
        }
    }

    @Test
    fun `bucket mid-times keep the drive's order`() {
        val ch = steady(500, 3f, perSampleSec = 4)
        val env = EnvelopeSink(500)
        val filled = trendEnvelope(ch, 0, 2000, 12, env)
        assertEquals(12, filled)
        for (i in 1 until filled) {
            assertTrue("midTime[$i]=${env.midTime[i]} after ${env.midTime[i - 1]}", env.midTime[i] >= env.midTime[i - 1])
        }
        assertTrue(env.midTime[0] >= 0)
        assertTrue(env.midTime[filled - 1] < 2000)
    }

    @Test
    fun `the sink is reused between calls`() {
        val env = EnvelopeSink(16)
        trendEnvelope(steady(100, 5f), 0, 100, 8, env)
        val lo = env.lo
        trendEnvelope(steady(100, 6f), 0, 100, 4, env)
        assertSame("a pan or zoom frame must not allocate a new sink", lo, env.lo)
        assertEquals(4, env.count)
        assertEquals(6f, env.lo[0], 0f)
    }

    private fun FloatArray.maxOver(from: Int, to: Int): Float {
        var m = -Float.MAX_VALUE
        for (i in from until to) if (this[i] > m) m = this[i]
        return m
    }

    private fun FloatArray.minOver(from: Int, to: Int): Float {
        var m = Float.MAX_VALUE
        for (i in from until to) if (this[i] < m) m = this[i]
        return m
    }
}
