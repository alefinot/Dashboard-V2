package com.alefinot.dashboardpp.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive

/**
 * Where a page draws the line between "fine" and "too slow to read".
 *
 * Twenty frames per second is the point where a pan stops reading as movement and starts reading
 * as steps; a page that cannot hold it is drawing more line than its phone can afford. Below it,
 * the route steps down one rung of [com.alefinot.dashboardpp.trips.RouteThinning.ladder] — and
 * only the route, because the figures, the charts and the runs cost the same at any rung.
 */
object AdaptiveQuality {

    /** The floor the page is expected to hold. */
    const val LOW_FPS = 20.0

    /**
     * The open of a detail page is not the page's fault: tiles are being fetched and decoded, the
     * map is being framed, and the entrance ramp is running. Two seconds is long enough for that
     * to settle and short enough that a phone that really cannot draw the route is caught while
     * the owner is still looking at it.
     */
    const val WARMUP_MS = 2_000L

    /** How long one FPS reading covers. */
    const val WINDOW_MS = 1_000L

    /**
     * Consecutive slow windows before a step. One slow second can be a tile decode, a GC, or the
     * page being scrolled while it settles; two in a row is the page's own cost.
     */
    const val STRIKES = 2
}

/**
 * Turns a frame clock into the one answer the page needs: "step the route down now?".
 *
 * Kept apart from the composable that feeds it so the arithmetic — warm-up, windows, strikes,
 * the reading that is a single slow frame and the one that is a whole slow second — can be tested
 * with timestamps instead of a phone. [onFrame] is called once per frame with the frame's time in
 * nanoseconds and returns true on exactly one frame per step, never twice for the same stall.
 */
class FrameRateWatcher(
    private val lowFps: Double = AdaptiveQuality.LOW_FPS,
    private val warmupMs: Long = AdaptiveQuality.WARMUP_MS,
    private val windowMs: Long = AdaptiveQuality.WINDOW_MS,
    private val strikesToDrop: Int = AdaptiveQuality.STRIKES,
) {
    private var firstFrameNs = Long.MIN_VALUE
    private var windowStartNs = 0L
    private var frames = 0
    private var slowWindows = 0

    /**
     * @return true when the window that just closed counted and held [lowFps] or slower for
     *   [strikesToDrop] windows running. One step per stall, and at most one per window, so a
     *   caller can act on `true` without throttling itself.
     */
    fun onFrame(nowNs: Long): Boolean {
        if (firstFrameNs == Long.MIN_VALUE) {
            firstFrameNs = nowNs
            windowStartNs = nowNs
            frames = 0
            return false
        }
        frames++
        val windowMsElapsed = (nowNs - windowStartNs) / NANOS_PER_MS
        if (windowMsElapsed < windowMs) return false

        // A window that opens inside the warm-up is thrown away whole rather than measured from
        // a moment nothing had settled yet: the test is on the window's *start*, so the reading
        // that straddles the end of the warm-up is the first one counted from a settled page.
        val counting = windowStartNs - firstFrameNs >= warmupMs * NANOS_PER_MS
        val fps = frames * 1_000.0 / windowMsElapsed
        frames = 0
        windowStartNs = nowNs
        if (!counting) return false

        if (fps < lowFps) slowWindows++ else slowWindows = 0
        if (slowWindows < strikesToDrop) return false
        // One step per stall: a page that keeps crawling pays one rung per pair of slow seconds,
        // not its whole ladder on the strength of the same jank it already answered.
        slowWindows = 0
        return true
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * The route rung the page should draw, stepping down while the UI thread cannot hold
 * [AdaptiveQuality.LOW_FPS].
 *
 * [rungs] is the length of the drive's ladder — one rung means the line is already small enough
 * for any phone, and this watches nothing. [enabled] is App Settings → Trips & Storage →
 * AUTO QUALITY; the owner's switch does not reset a rung the page has already spent, it only
 * stops any further stepping.
 *
 * The loop reads the frame clock rather than a timer because the frame clock is what a dropped
 * frame *is*: a slow draw delays the next callback, so a second of wall-clock with nineteen
 * callbacks in it is a page drawing at nineteen frames per second, whatever it was drawing.
 */
@Composable
fun rememberAdaptiveRouteLevel(
    driveKey: Any,
    enabled: Boolean,
    rungs: Int,
): Int {
    var level by remember(driveKey) { mutableIntStateOf(0) }
    val rungCount by rememberUpdatedState(rungs)
    LaunchedEffect(driveKey, enabled, rungs) {
        if (!enabled || rungCount <= 1) return@LaunchedEffect
        val watcher = FrameRateWatcher()
        // The first callback only starts the clock: it is not a frame the page drew.
        watcher.onFrame(withFrameNanos { it })
        while (isActive) {
            val frame = withFrameNanos { it }
            if (!watcher.onFrame(frame)) continue
            if (level >= rungCount - 1) break
            level++
        }
    }
    return level
}
