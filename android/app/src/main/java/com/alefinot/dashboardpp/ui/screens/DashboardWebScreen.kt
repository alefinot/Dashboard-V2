package com.alefinot.dashboardpp.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeTitle
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.webview.createDashboardWebView

@Composable
fun DashboardWebScreen(
    activity: Activity,
    vm: ConnectionViewModel,
    ip: String,
    onSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        AndroidView(
            factory = { ctx -> createDashboardWebView(ctx, activity, ip, vm) },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .padding(12.dp)
                .align(Alignment.TopEnd)
                .clickable { onSettings() },
        ) {
            Text("⚙", color = HudColors.Cyan, style = HudeTitle)
        }
    }
}
