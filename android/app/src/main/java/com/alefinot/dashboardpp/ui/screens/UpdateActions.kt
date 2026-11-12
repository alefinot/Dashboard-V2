package com.alefinot.dashboardpp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.alefinot.dashboardpp.ui.components.HudCaptionedAction
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.theme.HudColors

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
 * These are the glyph-plus-caption slots of a fixed action line (D2), not `HudButton`s: the shape is
 * what a finger finds, the 11 px word under it confirms the shape was the right one. The two
 * exceptions are deliberate and stay word buttons wearing a mark — `CONFIRM FLASH`, because a risky
 * verb is never reduced to a caption (D3), and `ALLOW INSTALLS`, because a sentence about a system
 * permission is not drawable (D4).
 *
 * Every one of them is [HudCaptionedAction], so the caption is inside the pressed box, in the mark's
 * own ink, and a description is not optional.
 */

/**
 * The fraction of a fetch already on the phone, for the bar and for the ring that replaced
 * `FETCHING…` on both halves of the Updates card.
 *
 * A size the release never reported still has to show *something* running, so it reads as a ring that
 * has not started (`0f`) rather than as an idle mark — and a ring is only ever drawn while bytes are
 * actually moving.
 */
internal fun updateFraction(size: Long, got: Long): Float =
    if (size > 0) (got.toFloat() / size).coerceIn(0f, 1f) else 0f

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
 * Ask GitHub again — `list.reload`, the same circular arrow a row's default and a trip counter wear.
 * The four share one drawing because all four mean "back to where this came from"; the [description]
 * is what says which of them this is (see `HudGlyphLedger.SHARED`).
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
 * Bring the released file onto this phone — `file.in`, the same mark the settings line's `RESTORE`
 * wears, because a file entering this system is one act whoever the file is for.
 *
 * [progress] is the fraction already fetched, and passing it is what replaces `FETCHING…`: the mark's
 * place holds the ring, and the caption says FETCHING instead of DOWNLOAD so the same slot reads as
 * the same act either way.
 */
@Composable
internal fun UpdateFetchAction(
    description: String,
    enabled: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    HudCaptionedAction(
        glyph = HudIcons.ImportFile,
        caption = if (progress == null) "DOWNLOAD" else "FETCHING",
        description = description,
        ink = updateInk(enabled, busy = progress != null),
        enabled = enabled,
        progress = progress,
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
