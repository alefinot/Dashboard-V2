package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeBody
import com.alefinot.dashboardpp.ui.theme.HudeCfgLive
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.hudGlass

@Composable
fun NotLinkedCover(
    title: String,
    reason: String?,
    note: String,
    vehicleName: String?,
    onOpenVehicles: () -> Unit,
    permissionProblem: Boolean = false,
    firstRun: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(HudColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        HudEnter(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 24.dp),
        ) {
            Column(Modifier.hudGlass().padding(22.dp)) {
                Text(title, style = HudeHeading().copy(brush = HudBrushes.title))
                Spacer(Modifier.height(12.dp))
                if (!reason.isNullOrBlank()) {
                    Text(reason, color = HudColors.TextMuted, style = HudeBody())
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    when {
                        !vehicleName.isNullOrBlank() -> "WAITING FOR: $vehicleName"
                        firstRun -> "NO DASHBOARD PAIRED YET"
                        else -> "NO VEHICLE SELECTED"
                    },
                    color = HudColors.Cyan,
                    style = HudeCfgLive(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        permissionProblem ->
                            "Android has not let this app use Bluetooth, so it cannot see " +
                                "a dashboard that is switched on right in front of it. The " +
                                "vehicle page asks for the permission and pairs from there."
                        firstRun ->
                            "This app has never met a dashboard. There is nothing to look up " +
                                "and no address to type: switch the dashboard on, open the " +
                                "vehicle page and let it find this phone. The dashboard will " +
                                "show a code, Android will ask you to confirm it, and that is " +
                                "the whole of it."
                        else -> note
                    },
                    color = HudColors.TextDim,
                    style = HudeCfgNote(),
                )
                Spacer(Modifier.height(18.dp))
                HudButton("OPEN VEHICLE PAGE", style = HudButtonStyle.PRIMARY, onClick = onOpenVehicles)
            }
        }
    }
}
