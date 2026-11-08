package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudMotion
import kotlinx.coroutines.delay

val LocalEnterActive = staticCompositionLocalOf { true }

@Composable
fun HudEnter(
    modifier: Modifier = Modifier,
    durationMs: Int = HudMotion.ENTRANCE_MS,
    delayMs: Int = 0,
    riseDp: Int = 8,
    scaleFrom: Float? = null,
    content: @Composable () -> Unit,
) {
    if (!HudMotion.enabled) {
        Box(modifier) { content() }
        return
    }
    val inView = LocalEnterActive.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(inView) {
        if (!inView) return@LaunchedEffect
        shown = false
        if (delayMs > 0) delay(delayMs.toLong())
        shown = true
    }
    val spec = tween<IntOffset>(durationMs, easing = HudMotion.Standard)
    val rise = with(LocalDensity.current) { riseDp.dp.roundToPx() }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(durationMs, easing = HudMotion.Standard)) +
            slideIn(spec, initialOffset = { IntOffset(0, rise) }) +
            if (scaleFrom != null) {
                scaleIn(tween(durationMs, easing = HudMotion.Standard), initialScale = scaleFrom)
            } else {
                androidx.compose.animation.EnterTransition.None
            },
        modifier = modifier,
    ) { content() }
}

@Composable
fun PageFade(
    modifier: Modifier = Modifier,
    durationMs: Int = HudMotion.ENTRANCE_MS,
    delayMs: Int = 0,
    riseDp: Int = 8,
    content: @Composable () -> Unit,
) {
    // Motion off, as in [HudEnter]: the content is simply there.
    if (!HudMotion.enabled) {
        Box(modifier) { content() }
        return
    }
    val inView = LocalEnterActive.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(inView) {
        if (!inView) return@LaunchedEffect
        shown = false
        if (delayMs > 0) delay(delayMs.toLong())
        shown = true
    }
    val spec = tween<Float>(durationMs, easing = HudMotion.Standard)
    val fade by animateFloatAsState(if (shown) 1f else 0f, spec, label = "pageFade")
    val rise = with(LocalDensity.current) { riseDp.dp.toPx() }
    val shift by animateFloatAsState(if (shown) 0f else rise, spec, label = "pageRise")
    Box(
        modifier.graphicsLayer {
            alpha = fade
            translationY = shift
        },
    ) { content() }
}
