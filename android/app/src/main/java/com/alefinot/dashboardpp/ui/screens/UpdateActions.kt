package com.alefinot.dashboardpp.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudCaptionedAction
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudSpace
import com.alefinot.dashboardpp.ui.theme.HudTouch
import com.alefinot.dashboardpp.ui.theme.HudeButtonLabel
import kotlin.math.roundToInt

/**
 * The slots both halves of the Updates card need, drawn once.
 *
 * The card held the same pair twice: `REFRESH` and `DOWNLOAD` on the FIRMWARE half, and `REFRESH` and
 * `DOWNLOAD` on the APP half, written out independently in two files with nothing shared between them.
 * Two of everything then had to be drawn, and the two copies were free to drift — which is exactly how
 * they ended up as two word buttons that happened to match. Both halves do three things and no more:
 * ask GitHub again, bring a released file onto this phone, and end or clear what is running. Those
 * three live here, and each card passes the sentence that says *which* list, *which* file.
 *
 * The fetch verb left the captioned-glyph line on 2026-11-13, at the maintainer's request: a download
 * deserves the whole width, because the bar that used to sit under it and the ring that used to sit in
 * the glyph were the same number drawn twice, small. [UpdateWideBar] is that shape — glyph and word
 * inside a full-width pressable bar that fills as the bytes land — and [UpdateFetchBar] is the fetch
 * slot wearing it. The reload glyph on the FIRMWARE half stays captioned and small: it lives in the
 * version dropdown's row, where a full-width bar cannot sit beside the field it reloads.
 *
 * The captioned slots are [HudCaptionedAction], so the caption is inside the pressed box, in the
 * mark's own ink, and a description is not optional. The wide bar keeps the same rules: a mandatory
 * sentence, one ink for glyph, word and fill, and the page's own press.
 */

/**
 * The fraction of a fetch already on the phone, for the bar's fill and for its word.
 *
 * A size the release never reported still has to show *something* running, so it reads as a bar that
 * has not started (`0f`) rather than as an idle mark — and the fill is only ever drawn while bytes are
 * actually moving.
 */
internal fun updateFraction(size: Long, got: Long): Float =
    if (size > 0) (got.toFloat() / size).coerceIn(0f, 1f) else 0f

/** The same fraction spelled as a whole percent, for the fetch bar's own label. */
internal fun updatePercent(fraction: Float): String =
    "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"

/**
 * The ink an update action wears, for both cards: unavailable is dim, in progress is amber (the app's
 * one ink for *this is waiting on something*), ready is cyan. Rule 3, said once instead of twice.
 */
private fun updateInk(enabled: Boolean, busy: Boolean): Color = when {
    !enabled -> HudColors.TextDim
    busy -> HudColors.Amber
    else -> HudColors.Cyan
}

/**
 * The full-width action bar: a glyph and its word inside one pressable strip, and — while the act
 * the word names is running — a fill across the strip at that fraction of its width.
 *
 * It is a [HudButton]'s shape stretched to the card's width and stripped of its gradient, because a
 * download is the one act on this card whose *size* a person is waiting on: the progress used to be
 * printed twice in two small places (a 10 dp bar under the buttons, a 22 dp ring inside one of them)
 * and once as a sentence of bytes. Here the control itself is the bar — the same recessed track the
 * Performance Monitor's `.perf-bar` draws — so the percent lands where the finger already aims.
 *
 * [highlight] is the one state the caller may shout about: the release this bar just found is newer
 * than what the phone runs, so the strip wears the page's primary-button gradient instead of the
 * field fill. The fill itself is real progress and never eases — a bar that lags the bytes it claims
 * is a lie with an animation. The ink eases, so a state change is still a state change.
 *
 * [description] is the sentence [HudCaptionedAction] made mandatory, and it works the same way here:
 * a screen reader gets it as the press action's name, and the visible word is what a finger reads.
 */
@Composable
internal fun UpdateWideBar(
    glyph: ImageVector,
    label: String,
    description: String,
    ink: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** The fraction already transferred, or null while the act is not running. */
    progress: Float? = null,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val ms = if (HudMotion.enabled) HudMotion.CONTROL_MS else 0
    val tint by animateColorAsState(ink, tween(ms, easing = HudMotion.Ease), label = "wideBarInk")
    val shape = HudRadius.CardShape
    Box(
        modifier
            .fillMaxWidth()
            .hudPressable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .clip(shape)
            .then(
                if (highlight) Modifier.background(HudBrushes.button())
                else Modifier.background(HudColors.Field),
            )
            .border(1.dp, if (highlight) HudColors.CyanDim else HudColors.Hairline, shape)
            .height(HudTouch.Min),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(tint.copy(alpha = 0.28f)),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HudSpace.Gap),
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Image(
                glyph,
                contentDescription = null, // the word is the name; the sentence rides on the press
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(HudGlyphSize.Row.dp),
            )
            Text(
                label.uppercase(),
                color = tint,
                style = HudeButtonLabel(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Bring the released file onto this phone — [UpdateWideBar] wearing the `file.in` mark, the same mark
 * the settings line's `RESTORE` wears, because a file entering this system is one act whoever the
 * file is for.
 *
 * [progress] is the fraction already fetched: passing it is what turns the strip into the transfer —
 * an amber ink, a fill at that fraction, and the percent in the word beside the mark.
 */
@Composable
internal fun UpdateFetchBar(
    label: String,
    description: String,
    enabled: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    UpdateWideBar(
        glyph = HudIcons.ImportFile,
        label = label,
        description = description,
        ink = if (progress != null) HudColors.Amber else updateInk(enabled, busy = false),
        enabled = enabled,
        progress = progress,
        onClick = onClick,
    )
}

/**
 * Ask GitHub again — `list.reload`, the same circular arrow a row's default and a trip counter wear.
 * It stays a captioned glyph on the FIRMWARE half, where it sits in the version dropdown's row; the
 * APP half's check became its own [UpdateWideBar] on 2026-11-13. The description is what says which
 * of these reloads this is (see `HudGlyphLedger.SHARED`).
 */
@Composable
internal fun UpdateReloadAction(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    HudCaptionedAction(
        glyph = HudIcons.Reset,
        caption = "REFRESH",
        description = description,
        ink = updateInk(enabled, busy = false),
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Begin, or continue, what the card offers — `action.start`, the filled triangle every "press once and
 * then it is working" in the app wears. The firmware card's `START` is a word button (D3); this is the
 * half of the family that is not a risky verb, like the trip card's `RUN`.
 */
@Composable
internal fun UpdateBeginAction(
    caption: String,
    description: String,
    enabled: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    HudCaptionedAction(
        glyph = HudIcons.Play,
        caption = caption,
        description = description,
        ink = updateInk(enabled, busy = progress != null),
        enabled = enabled,
        progress = progress,
        onClick = onClick,
    )
}

/**
 * The second door a firmware file comes through: the phone's own document picker — `action.file-choose`.
 * The caption is one word because the glyph is a page: the sentence, which has to say that this is
 * the door for a file the phone already holds, is the description.
 */
@Composable
internal fun UpdateFileAction(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    HudCaptionedAction(
        glyph = HudIcons.File,
        caption = "FILE",
        description = description,
        ink = updateInk(enabled, busy = false),
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * End what is running, or clear what it left behind — `action.stop`, the filled square.
 *
 * Amber rather than red on purpose: this stops a transfer, it destroys nothing. The unit keeps what it
 * has already written, and a paused install stays resumable, which is the sentence the description
 * tells and the red would contradict.
 */
@Composable
internal fun UpdateStopAction(
    caption: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    HudCaptionedAction(
        glyph = HudIcons.Stop,
        caption = caption,
        description = description,
        ink = if (enabled) HudColors.Amber else HudColors.TextDim,
        enabled = enabled,
        onClick = onClick,
    )
}
