package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudMotion
import kotlinx.coroutines.delay

/**
 * Whether the page an entrance belongs to is on screen right now.
 *
 * Default true: a panel outside the pager (a dialog, a note, the first page at start
 * up) animates when it is composed, as it always has. `NativePage` in the shell
 * overrides it with the pager's own answer, so a page that is only sitting there
 * waiting to be swiped to does not spend its entrance before the user reaches it.
 *
 * Keyed to visibility rather than to composition because the pages of the shell are
 * kept composed between visits so that arriving costs a paint and not a rebuild - and
 * an entrance that runs on composition would be over and gone before the user got
 * there. It is keyed to the page *coming in* rather than to the pager settling, for
 * the reason app 21 measured: waiting for the settle shows a half-drawn page.
 */
val LocalEnterActive = staticCompositionLocalOf { true }

/**
 * The page's entrance, `pageFadeIn`: opacity 0 → 1 with an 8dp rise, on
 * `cubic-bezier(.16, 1, .3, 1)`.
 *
 * The content is *always composed*. The first version of this used
 * `AnimatedVisibility`, which removes its content while the entrance is not running -
 * so every arrival on the Live page rebuilt six cards (and every gauge, chart and
 * canvas in them) on the first frame of the animation, and a page that was supposed
 * to cost a paint cost a composition instead. Here the content is measured once and
 * the animation moves only its layer: alpha, translation and an optional scale. That
 * is also what makes the re-arm safe: a returning page starts again at nothing
 * without ever tearing the content down.
 *
 * It runs once per visit. A list that re-composes items while scrolling has to gate it
 * (see TripsTab), or the same rows animate again on every pass.
 */
@Composable
fun HudEnter(
    modifier: Modifier = Modifier,
    durationMs: Int = HudMotion.ENTRANCE_MS,
    delayMs: Int = 0,
    riseDp: Int = HudMotion.RISE_DP,
    scaleFrom: Float? = null,
    content: @Composable () -> Unit,
) {
    // Motion off: the content is there, with no transition at all.
    if (!HudMotion.enabled) {
        Box(modifier) { content() }
        return
    }
    val inView = LocalEnterActive.current
    // Re-armed by the arrival itself, not by an effect: `remember(inView)` hands back
    // a fresh Animatable at 0 on the very frame the page comes in, so the first frame
    // that could show it is already at zero alpha. Snapping from inside the effect
    // would draw the page once at the value it had on its last visit (1) before the
    // effect got to run - a one-frame flash of the settled page, then the fade.
    val progress = remember(inView) { Animatable(if (inView) 0f else 1f) }
    LaunchedEffect(progress) {
        // Out of view is left alone: what the page showed on its way out is what it
        // shows again as it comes in.
        if (!inView) return@LaunchedEffect
        // A wall of panels arriving one after another (the Live screen: heading,
        // then the readout, then the figures) is one page entrance split into
        // slices, not a queue the user waits through - hence a short offset per
        // block, so a whole wall settles inside one entrance.
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, tween(durationMs, easing = HudMotion.Standard))
    }
    val rise = with(LocalDensity.current) { riseDp.dp.toPx() }
    Box(
        modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = (1f - p) * rise
            if (scaleFrom != null) {
                val s = scaleFrom + (1f - scaleFrom) * p
                scaleX = s
                scaleY = s
            }
        },
    ) { content() }
}

/**
 * The same `pageFadeIn` for content that has to stay composed while it fades: the
 * trips list, which would otherwise lose its scroll position on every visit, and the
 * pager, which cannot be torn down and rebuilt without losing its frame (the black
 * hole app 21 removed).
 *
 * One motion, one curve, one duration with [HudEnter], and both are armed by the page
 * coming into view ([LocalEnterActive]). The only difference is that this one never
 * scales: a whole page that grows by 2% reads as a zoom, not as an arrival.
 */
@Composable
fun PageFade(
    modifier: Modifier = Modifier,
    durationMs: Int = HudMotion.ENTRANCE_MS,
    delayMs: Int = 0,
    riseDp: Int = HudMotion.RISE_DP,
    content: @Composable () -> Unit,
) {
    if (!HudMotion.enabled) {
        Box(modifier) { content() }
        return
    }
    val inView = LocalEnterActive.current
    // Armed by the arrival, as in [HudEnter]: a fresh Animatable at 0 on the frame
    // the page comes in, so a returning page cannot flash into place for one frame.
    val progress = remember(inView) { Animatable(if (inView) 0f else 1f) }
    LaunchedEffect(progress) {
        // Out of view is left alone: what the page showed on its way out is what it
        // shows again as it comes in, until the arrival re-arms the entrance.
        if (!inView) return@LaunchedEffect
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, tween(durationMs, easing = HudMotion.Standard))
    }
    val rise = with(LocalDensity.current) { riseDp.dp.toPx() }
    Box(
        modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = (1f - p) * rise
        },
    ) { content() }
}
