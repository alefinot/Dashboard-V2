package com.alefinot.dashboardpp.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudeBody
import com.alefinot.dashboardpp.ui.theme.hudGlass

@Composable
fun HudConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "CONFIRM",
    cancelLabel: String = "CANCEL",
    confirmStyle: HudButtonStyle = HudButtonStyle.PRIMARY,
) {
    // Escape cancels, per `confirmDialog()`'s keydown handler.
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Scrim)
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        HudEnter(
            modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp).padding(horizontal = 24.dp),
            durationMs = HudMotion.SHORT_MS,
            riseDp = 8,
            scaleFrom = 0.98f,
        ) {
            Column(Modifier.hudGlass().padding(22.dp)) {
                Text(message, color = HudColors.TextPrimary, style = HudeBody())
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        HudButton(confirmLabel, style = confirmStyle) {
                            onConfirm()
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        HudButton(cancelLabel, style = HudButtonStyle.SECONDARY, onClick = onDismiss)
                    }
                }
            }
        }
    }
}
