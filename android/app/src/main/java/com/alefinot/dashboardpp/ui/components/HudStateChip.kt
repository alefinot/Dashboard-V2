package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeBody
import com.alefinot.dashboardpp.ui.theme.hudCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos

/**
 * The breathing half of a live state. The page defined `pulseGlow`
 * (`src/webui.html:70` - `box-shadow: 0 0 12px rgba(0,229,255,.2)` at the ends of
 * the keyframe, `0 0 24px rgba(0,229,255,.5)` in the middle) and, on the last
 * reading of that file before it was deleted, never attached it to a selector: the
 * page had no recording indicator to animate. The app has that state, so the chip
 * borrows the page's own keyframe instead of inventing a motion. The page set no
 * duration for it, because nothing used it; 2 s is the app's number, chosen so one
 * breath spans two 1 Hz samples - slow enough to read as a pulse and not as a blink.
 */
private const val CHIP_PULSE_MS = 2000L

/**
 * The breath is recomputed ~16 times a second instead of on every frame: a glow
 * that takes a second to reach its bright stop has nothing to show between those
 * steps, and a per-frame loop measured 48 repaints a second on the test tablet
 * (SM-T590) for as long as the recorder ran.
 */
private const val CHIP_PULSE_STEP_MS = 60L

/**
 * A run state as one pill: the mark of the state, its word, and its ink.
 *
 * It was the Live page's `StatusChip` - word only, state carried in tint alone. The
 * unified grammar (icon plan P8, D2) says a state is said twice: the tint it has
 * always worn, and the mark the ledger owns for that state - `Record` while it
 * records, the link marks while it does not, `Warn` when data went stale. The word
 * stays because a pill is not a control: it is read, never pressed, and the sentence
 * is what the a11y node already carries.
 *
 * The pill keeps the page's `.ip-banner` shape and `.msg-*` tint recipe: [ink] at 8%
 * behind the glass, the same ink at 30% for the hairline, and in both the mark and
 * the word - one ink for every channel (see [HudGlyphAction]). [pulse] breathes the
 * page's `pulseGlow` keyframe while the state is live (see [CHIP_PULSE_MS]).
 */
@Composable
fun HudStateChip(
    label: String,
    glyph: ImageVector,
    ink: Color,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
    active: Boolean = true,
) {
    // The state changes without the user touching anything (the recorder starts,
    // the page takes the connection, data goes stale), so both halves of the pill
    // move: the colour over the page's 0.2s control easing, the word crossfading
    // into its place. The mark rides inside that crossfade, so the pill never
    // shows one state's glyph in the next state's ink.
    val color by animateColorAsState(
        ink,
        tween(HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "chipColor",
    )
    // The breath, held by the pill while the state pulses. The fraction is a state
    // object read *inside* the draw lambda below: a step of the breath invalidates
    // the chip's own layer, not the screen behind it. When the state stops pulsing
    // the breath is left at its dim stop, and the pill settles back to the plain
    // tint the CSS gives a `.msg-*` pill.
    val glow = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pulse, active) {
        // `active` is not decoration: the page is now composed whether or not the
        // user is looking at it, and a breath that runs off-screen invalidates that
        // chip's layer ~30 times a second for nobody. It restarts from the dim stop
        // when the page comes back, which is where a breath starting from rest
        // belongs anyway.
        if (!pulse || !active) {
            glow.floatValue = 0f
            return@LaunchedEffect
        }
        val start = android.os.SystemClock.elapsedRealtime()
        while (isActive) {
            val p = (android.os.SystemClock.elapsedRealtime() - start) % CHIP_PULSE_MS
            // The keyframe sits at one stop for 0% and 100% and the other for 50%,
            // which is a cosine and not a triangle: it eases at both ends, so there
            // is no snap at the bottom of the breath.
            glow.floatValue = 0.5f - 0.5f * cos(2f * PI.toFloat() * p / CHIP_PULSE_MS)
            delay(CHIP_PULSE_STEP_MS)
        }
    }
    Box(
        modifier
            // `.ip-banner` for the shape (--bg-card pill, hairline, 4x12 padding),
            // `.msg-success`/`.msg-error` for the state: that colour as a 30%
            // hairline and an 8% wash, and in the mark and the label. The padding
            // sits on the row rather than the box, so the layer that draws the
            // breath spans the whole pill and its ring lands on the hairline.
            .hudCard(
                shape = HudRadius.PillShape,
                fill = HudColors.Panel,
                borderColor = color.copy(alpha = 0.3f),
            )
            .background(color.copy(alpha = 0.08f), HudRadius.PillShape),
    ) {
        Crossfade(label, animationSpec = tween(HudMotion.SWITCH_MS), label = "chipText") { word ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // The word next to it names the state, so the mark is the picture of
                // that word, not a second claim: null description, and no separate
                // a11y sentence to keep honest.
                Image(
                    glyph,
                    null,
                    modifier = Modifier.size(HudGlyphSize.Row.dp).padding(end = 6.dp),
                    colorFilter = ColorFilter.tint(color),
                )
                // `#ip-banner` is 13px with no weight of its own, and `#msg`'s variants put
                // the colour in the text: same here.
                Text(word, color = color, style = HudeBody())
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val g = glow.floatValue
            if (g > 0.01f) {
                val h = size.height
                val path = Path()
                path.addRoundRect(
                    RoundRect(Rect(0f, 0f, size.width, h), CornerRadius(h / 2f, h / 2f)),
                )
                // The page's shadow is a blur outside the pill, and this layer has no
                // blur to give it (blurring what is behind needs API 31, the app
                // supports 26), so the halo is three strokes growing inward from the
                // hairline - a stroke is centred on the path, so the outer half of
                // each is cut off by the layer. Their alphas peak under the page's
                // bright stop (.5) so the stack never reads heavier than the single
                // shadow it stands in for, and all three ride the same cosine the
                // keyframe does.
                drawPath(
                    path,
                    color.copy(alpha = 0.05f + 0.07f * g),
                    style = Stroke(width = 7.dp.toPx()),
                )
                drawPath(
                    path,
                    color.copy(alpha = 0.07f + 0.11f * g),
                    style = Stroke(width = 3.5.dp.toPx()),
                )
                drawPath(
                    path,
                    color.copy(alpha = 0.18f + 0.30f * g),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}
