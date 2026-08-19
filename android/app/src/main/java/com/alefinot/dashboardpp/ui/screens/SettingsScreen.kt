package com.alefinot.dashboardpp.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMonoText
import com.alefinot.dashboardpp.ui.theme.HudeDisplaySmall
import com.alefinot.dashboardpp.ui.theme.HudeSmall
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel

/**
 * App settings — controls that apply to the Android app itself. The ESP's
 * own configuration (network, save / reboot / reset) lives in the Web UI's
 * bottom toolbar and is not duplicated here.
 */
@Composable
fun SettingsScreen(
    vm: ConnectionViewModel,
    activity: Activity,
    ip: String,
    version: String?,
    onDismiss: () -> Unit,
) {
    val appVersion = remember {
        try {
            activity.packageManager
                .getPackageInfo(activity.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    // Close the settings overlay from the system back button and the
    // predictive back edge-swipe gesture (Android 14+) — both are routed
    // through OnBackPressedDispatcher, which BackHandler hooks. It's active
    // only while this screen is composed (settings overlay is showing).
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(Modifier.padding(24.dp)) {
            Text(
                "APP SETTINGS",
                color = HudColors.Cyan,
                style = HudeDisplaySmall,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, HudColors.CyanDim)
                    .background(HudColors.Panel)
                    .padding(16.dp),
            ) {
                Column {
                    Text(
                        "DEVICE IP",
                        color = HudColors.TextDim,
                        style = HudeSmall,
                    )
                    Text(ip, color = HudColors.TextPrimary, style = HudMonoText)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "FIRMWARE",
                        color = HudColors.TextDim,
                        style = HudeSmall,
                    )
                    Text(
                        version ?: "UNKNOWN",
                        color = HudColors.TextPrimary,
                        style = HudMonoText,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "APP",
                        color = HudColors.TextDim,
                        style = HudeSmall,
                    )
                    Text(
                        appVersion,
                        color = HudColors.TextPrimary,
                        style = HudMonoText,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Dashboard++ by @ale.finot",
                        color = HudColors.Cyan,
                        style = HudeSmall,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            HudButton("RE-SCAN NETWORK") { onDismiss(); vm.rescan() }
            Spacer(Modifier.height(8.dp))
            HudButton("FORGET THIS DEVICE") { vm.forgetDevice(ip); onDismiss() }
            Spacer(Modifier.height(8.dp))
            HudButton("CLOSE", onClick = onDismiss)
        }
    }
}
