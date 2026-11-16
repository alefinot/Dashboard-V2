package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudePerfRowValue

/**
 * The app's one slider, drawn in the HUD's own vocabulary instead of Material's.
 *
 * Material's slider is a pill track with a 20 dp thumb, a 48 dp layout box and a tick
 * row; on the settings page it arrived as the tallest control in a row that already
 * spends a line on its label, and it looked like a piece of another app. This component
 * keeps Material's gesture and semantics (drag, tap-anywhere, keyboard and TalkBack all
 * still work) and replaces what it paints:
 *
 *  - the rail is an 8 dp [HudColors.Field] groove with the page's hairline, the same
 *    recessed surface as the number boxes and the stepper buttons beside it;
 *  - the travelled part is one flat cyan run - the colour every other fill in the app
 *    uses for "this far" (`.perf-bar`, the fuel bar, the gauge arc);
 *  - the thumb is the toggle's 14 dp knob in a 2 dp black ring, so a fader, a switch and
 *    a stepper read as one family;
 *  - the current value sits on the slider's own line in a mono readout at the trailing
 *    end. The old row printed `0 – 100` under the track on a 17 dp line of its own, which
 *    is the line that made a slider 65 dp tall while every other control was 30.
 *
 * The whole control is 30 dp tall (the stepper's height) instead of a 48 dp interactive
 * box plus a caption: a slider row now costs a label line and one control line, exactly
 * like a number row. The small rail is still a 30 dp finger target, and the value readout
 * beside it keeps the number visible while the thumb moves.
 *
 * The rail's two ends carry no figures of their own. The number is the readout, and the
 * band's ends are the store's business (`SchemaItem.sliderMin/sliderMax` still clamp every
 * write) - printing them at the rail put two more numbers on a row whose job is to show
 * one, and every slider on the page had the same pair to read.
 *
 * [valueText] is the live readout, not an input: the number rows' editable box is a
 * `HudInput`, while this is the figure the drag is producing. It is deliberately styled
 * as a readout chip ([HudColors.Field] + hairline, mono) instead of a field so it cannot
 * be mistaken for a second keyboard target. Null hides it, which the UI Layout sheet does
 * because its own number box is the value display there.
 *
 * [valueWidth] reserves a fixed width for the readout. Without it the rail would grow and
 * shrink by a digit's width while a thumb crosses 99 -> 100, which makes a drag feel like
 * it is fighting the layout; callers pass the width the row's endpoints can reach. A
 * `—` (the unit never reported the key) fits in every band.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    valueText: String? = null,
    valueWidth: Dp = 56.dp,
) {
    val ink by animateColorAsState(
        if (enabled) HudColors.Cyan else HudColors.TextDim,
        tween(
            if (HudMotion.enabled) HudMotion.CONTROL_MS else 0,
            easing = HudMotion.Ease,
        ),
        label = "sliderInk",
    )
    // Material's 48 dp layout floor is what made this control taller than its neighbours;
    // the rail below is the target instead. The slider still carries its own semantics, so
    // setting the local does not make it unreachable to a screen reader or a keyboard.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier.weight(1f).height(HudSliderSpec.Rail),
                thumb = { HudSliderThumb(ink) },
                track = { state -> HudSliderTrack(state, ink) },
            )
            if (valueText != null) {
                Box(
                    Modifier
                        .padding(start = HudSliderSpec.ValueGap)
                        .width(valueWidth)
                        .height(HudSliderSpec.Readout)
                        .clip(HudRadius.FieldShape)
                        .background(HudColors.Field)
                        .border(1.dp, HudColors.Hairline, HudRadius.FieldShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        valueText,
                        style = HudePerfRowValue(),
                        color = if (enabled) HudColors.TextPrimary else HudColors.TextDim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A fader's measurements, named so the composables below and the call sites cannot
 * disagree about them. They are chosen against the app's existing controls, not against
 * Material's slider: 30 dp is `HudStepButton` and `HudTouch.Button`, the 8 dp rail is the
 * `.perf-bar` band rounded, and the 18 dp thumb holds the toggle's 14 dp knob with the
 * 2 dp ring that keeps it legible over the cyan run.
 */
private object HudSliderSpec {
    /** The control's own line and finger target; the stepper's 30 dp square. */
    val Rail = 30.dp

    /** The readout chip's height: a little shorter than the rail, so the rail stays dominant. */
    val Readout = 26.dp

    /** Between the rail and the readout; the chip separates from the track's end. */
    val ValueGap = 8.dp

    /** The groove's visible thickness. */
    val Groove = 8.dp

    /** The thumb's full diameter, ring included; 14 dp of ink once the 2 dp ring is inset. */
    val Thumb = 18.dp

    /** The black ring that keeps the thumb readable while it sits on the cyan run. */
    val Ring = 2.dp
}

/**
 * The rail. Drawn rather than composed because the active run has to stop under the
 * thumb's centre and the groove's hairline has to stay one pixel at every width; two
 * nested `Box`es would round both ends independently and show a seam at the join.
 *
 * The slider hands this composable the track-visible width (the layout reserves one
 * thumb width for the handle), so the run's fraction is measured against the same span
 * the gesture maps to. Material's tick row is not drawn: a 100-step brightness band
 * would paint ninety-nine dashes into 200 dp, and the rail's ends plus the readout
 * already say what the control can do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HudSliderTrack(state: SliderState, ink: Color) {
    Canvas(Modifier.fillMaxWidth().height(HudSliderSpec.Rail)) {
        val groove = HudSliderSpec.Groove.toPx()
        val corner = CornerRadius(groove / 2f, groove / 2f)
        val top = (size.height - groove) / 2f
        // Inset half a pixel so the one-pixel stroke sits inside the canvas instead of
        // being clipped along the top and bottom edges.
        val inset = 0.5.dp.toPx()
        val span = state.valueRange.endInclusive - state.valueRange.start
        val fraction = if (span <= 0f) {
            0f
        } else {
            ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
        }

        drawRoundRect(
            color = HudColors.Field,
            topLeft = Offset(inset, top + inset),
            size = Size(size.width - inset * 2f, groove - inset * 2f),
            cornerRadius = corner,
        )
        if (fraction > 0f) {
            drawRoundRect(
                color = ink,
                topLeft = Offset(inset, top + inset),
                size = Size((size.width - inset * 2f) * fraction, groove - inset * 2f),
                cornerRadius = corner,
            )
        }
        drawRoundRect(
            color = HudColors.Hairline,
            topLeft = Offset(inset, top + inset),
            size = Size(size.width - inset * 2f, groove - inset * 2f),
            cornerRadius = corner,
            style = Stroke(1.dp.toPx()),
        )
    }
}

/**
 * The thumb: the toggle's knob, one ring heavier. The ring is the page background, so the
 * cyan face does not bleed into the cyan run it is sitting on; the fill is the same ink
 * the rail's travelled part uses, which is what makes the thumb look like the leading end
 * of the fill rather than an object dropped on it.
 */
@Composable
private fun HudSliderThumb(ink: Color) {
    Box(
        Modifier
            .size(HudSliderSpec.Thumb)
            .clip(CircleShape)
            .background(ink)
            .border(HudSliderSpec.Ring, HudColors.Bg, CircleShape),
    )
}
