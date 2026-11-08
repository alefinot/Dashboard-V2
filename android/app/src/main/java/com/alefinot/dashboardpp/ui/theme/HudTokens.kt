package com.alefinot.dashboardpp.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object HudRadius {
    /** `.card`, `.perf-card`, `#msg`, `button`: border-radius 8px. */
    val Card = 8.dp
    /** `.confirm-dialog`, `details`: 12px. */
    val Overlay = 12.dp
    /** `#searchBar`, `.acc-collapser`: 10px. */
    val Row = 10.dp
    /** `input[type=text|number]`, `select`: 6px. */
    val Field = 6.dp
    /** `.ip-banner`: pill. */
    val Pill = 30.dp
    /** `.adv-switch` track: 18px tall, radius 9px. */
    val Toggle = 9.dp

    val CardShape = RoundedCornerShape(Card)
    val OverlayShape = RoundedCornerShape(Overlay)
    val RowShape = RoundedCornerShape(Row)
    val FieldShape = RoundedCornerShape(Field)
    val PillShape = RoundedCornerShape(Pill)
}

object HudMotion {
    /** `cubic-bezier(0.16, 1, 0.3, 1)` - used by every transition in the page. */
    val Standard = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** CSS `ease`, which is what the page uses for its plain property transitions. */
    val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** `animation: pageFadeIn 0.5s` (body, `.panel`, `#sel-panel`). */
    const val ENTRANCE_MS = 500

    /** `.confirm-dialog`, `button:hover/active`: 0.25 s. */
    const val SHORT_MS = 250

    /** border/colour transitions on inputs, selects, `.form-row button`: 0.2-0.3 s. */
    const val CONTROL_MS = 300

    /** `.adv-switch` and `.reset-btn`: 0.2 s. */
    const val SWITCH_MS = 200

    private val _prefEnabled = mutableStateOf(true)

    /** Motion the app may use: the user's switch. */
    val enabled: Boolean get() = _prefEnabled.value

    /** Written by the screen that collects the preference (see ConnectedTabs). */
    fun setPrefEnabled(on: Boolean) {
        if (_prefEnabled.value != on) _prefEnabled.value = on
    }

    const val TAB_MS = 250

    /** `pageFadeIn`: the 8px rise that comes with every entrance on the page. */
    const val RISE_DP = 8

    /** `.perf-bar-fill`: `transition: width 0.8s ease`. */
    const val BAR_MS = 800

    /** `#sel-panel`: `animation: selFadeIn 0.15s ease-out`. */
    const val REVEAL_MS = 150

    const val STAGGER_MS = 30

    const val GAUGE_MS = 400
}

object HudBrushes {
    /** `button` - the default action, `linear-gradient(135deg, rgba(0,229,255,.15), rgba(41,121,255,.25))`. */
    fun button(): Brush = Brush.linearGradient(
        listOf(Color(0x2600E5FF), Color(0x402979FF)),
    )

    /** `button:hover` - the brightened state, and the one a touch press uses. */
    fun buttonActive(): Brush = Brush.linearGradient(
        listOf(Color(0x4D00E5FF), Color(0x732979FF)),
    )

    /** `.btn-secondary` - a flat fill, `rgba(30,41,59,.7)`, not a gradient. */
    val secondary = Color(0xB31E293B)

    /** `.btn-warning`. */
    fun warning(): Brush = Brush.linearGradient(
        listOf(Color(0x33FF9100), Color(0x4DD84315)),
    )

    /** `.btn-danger`. */
    fun danger(): Brush = Brush.linearGradient(
        listOf(Color(0x33FF1744), Color(0x4CC62828)),
    )

    val title: Brush by lazy {
        Brush.linearGradient(
            0f to HudColors.White,
            0.3f to HudColors.White,
            1f to HudColors.Cyan,
        )
    }
}

@Composable
fun Modifier.hudGlass(
    shape: Shape = HudRadius.OverlayShape,
    fill: Color = HudColors.Panel,
    borderColor: Color = HudColors.Hairline,
    elevation: Dp = 12.dp,
    highlight: Float = 0.05f,
    wash: Brush? = null,
): Modifier = this
    .shadow(elevation, shape, clip = false, ambientColor = HudColors.Shadow, spotColor = HudColors.Shadow)
    .clip(shape)
    .background(fill)
    .then(if (wash != null) Modifier.background(wash) else Modifier)
    .then(
        if (highlight > 0f) {
            Modifier.background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = highlight),
                    0.35f to Color.Transparent,
                ),
            )
        } else {
            Modifier
        },
    )
    .border(1.dp, borderColor, shape)

@Composable
fun Modifier.hudCard(
    shape: Shape = HudRadius.CardShape,
    fill: Color = HudColors.CardFill,
    borderColor: Color = HudColors.CardHairline,
    wash: Brush? = null,
    highlight: Float = 0f,
): Modifier = this
    .clip(shape)
    .background(fill)
    .then(if (wash != null) Modifier.background(wash) else Modifier)
    .then(
        if (highlight > 0f) {
            Modifier.background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = highlight),
                    0.35f to Color.Transparent,
                ),
            )
        } else {
            Modifier
        },
    )
    .border(1.dp, borderColor, shape)
