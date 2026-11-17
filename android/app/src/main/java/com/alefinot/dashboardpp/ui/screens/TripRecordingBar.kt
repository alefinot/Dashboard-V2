package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.trips.RecordingControlState
import com.alefinot.dashboardpp.trips.TripRuntimeState
import com.alefinot.dashboardpp.ui.components.HudCaptionedAction
import com.alefinot.dashboardpp.ui.components.HudGlyphLedger
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote

/**
 * What the two pages can ask of the recorder. Built once in the shell so the Trips page and the
 * Live page cannot disagree about what a control does, and so neither page has to know how the
 * service is started (it needs a suspend lookup of whose drives these are).
 *
 * [blocked] is why START is not offered, in the words the page will show. It is built where the
 * facts are - the link and the unit's firmware - rather than guessed by the bar from nulls.
 */
class TripRecordingActions(
    val canRecord: Boolean,
    val blocked: String,
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onStop: () -> Unit,
)

/**
 * The trip recorder's controls: START on an idle page, PAUSE and STOP while a drive is being
 * written, RESUME and STOP while it is paused, STOP alone in the window between START and the
 * first sample.
 *
 * It is one line of [HudCaptionedAction]s - a glyph plus its word - because that is the app's
 * answer for a fixed action line (the unit settings page, the update cards, the editor footers).
 * A driver reads the shape first and the word confirms it; a word-only button is not allowed any
 * more (`GlyphLedgerTest` pins the count at zero).
 *
 * The line is deliberately never hidden. On a page with no link it is a disabled START and the
 * reason underneath, which is how the owner learns that recording exists here at all; a control
 * that vanishes when it cannot act is a control nobody finds on the day it can.
 */
@Composable
fun TripRecordingBar(
    state: TripRuntimeState,
    actions: TripRecordingActions,
    modifier: Modifier = Modifier,
) {
    val control = RecordingControlState.of(
        recording = state.recording,
        paused = state.paused,
        polling = state.polling,
        canRecord = actions.canRecord,
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (control) {
                RecordingControlState.UNAVAILABLE -> HudCaptionedAction(
                    glyph = HudGlyphLedger.mark("action.start"),
                    caption = "START",
                    description = "start recording a drive",
                    ink = HudColors.TextDim,
                    enabled = false,
                    onClick = {},
                )
                RecordingControlState.START -> HudCaptionedAction(
                    glyph = HudGlyphLedger.mark("action.start"),
                    caption = "START",
                    description = "start recording a drive now",
                    ink = HudColors.Green,
                    enabled = true,
                    onClick = actions.onStart,
                )
                RecordingControlState.WAITING -> HudCaptionedAction(
                    glyph = HudGlyphLedger.mark("action.stop"),
                    caption = "STOP",
                    description = "stop recording before the first sample arrives",
                    ink = HudColors.Cyan,
                    enabled = true,
                    onClick = actions.onStop,
                )
                RecordingControlState.RECORDING -> {
                    HudCaptionedAction(
                        glyph = HudGlyphLedger.mark("action.pause"),
                        caption = "PAUSE",
                        description = "pause recording — the drive stays one file",
                        ink = HudColors.Amber,
                        enabled = true,
                        onClick = actions.onPause,
                    )
                    HudCaptionedAction(
                        glyph = HudGlyphLedger.mark("action.stop"),
                        caption = "STOP",
                        description = "stop recording and seal this drive",
                        ink = HudColors.Cyan,
                        enabled = true,
                        onClick = actions.onStop,
                    )
                }
                RecordingControlState.PAUSED -> {
                    HudCaptionedAction(
                        glyph = HudGlyphLedger.mark("action.start"),
                        caption = "RESUME",
                        description = "resume recording this drive",
                        ink = HudColors.Green,
                        enabled = true,
                        onClick = actions.onResume,
                    )
                    HudCaptionedAction(
                        glyph = HudGlyphLedger.mark("action.stop"),
                        caption = "STOP",
                        description = "stop recording and seal this drive",
                        ink = HudColors.Cyan,
                        enabled = true,
                        onClick = actions.onStop,
                    )
                }
            }
        }
        val note = when (control) {
            RecordingControlState.UNAVAILABLE -> actions.blocked
            RecordingControlState.WAITING -> "Waiting for the dashboard's first sample."
            RecordingControlState.PAUSED ->
                "Paused — nothing is being logged. RESUME continues the same file."
            else -> ""
        }
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                note,
                color = if (control == RecordingControlState.PAUSED) HudColors.Amber
                else HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }
    }
}
