package com.alefinot.dashboardpp.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.alefinot.dashboardpp.viewmodel.CompanionViewModel
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.webview.FileChooser
import com.alefinot.dashboardpp.webview.createDashboardWebView
import com.alefinot.dashboardpp.webview.deliverFilePick

@Composable
fun DashboardWebScreen(
    vm: ConnectionViewModel,
    ip: String,
    companion: CompanionViewModel,
    onSettings: () -> Unit,
) {
    // The ESP UI backup-import file chooser: the launcher is registered in
    // the composition (a WebView can't get the picker result itself), and
    // deliverFilePick hands it to the pending ValueCallback.
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val uri =
            if (res.resultCode == Activity.RESULT_OK) res.data?.data else null
        deliverFilePick(uri)
    }
    FileChooser.launcher = fileChooserLauncher

    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        AndroidView(
            factory = { ctx -> createDashboardWebView(ctx, ip, vm) },
            onRelease = { wv ->
                // Per the Android WebView destruction docs: stop loading,
                // navigate to about:blank, drop the download listener, then
                // destroy - otherwise each exit from the Connected state
                // leaks a live renderer (Chromium process, JS heap, cookies).
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.setDownloadListener(null)
                wv.removeAllViews()
                wv.destroy()
            },
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
        // The BLE link chip (the ESP is the GATT server; the app is the
        // central). Shows the current link state + a "push weather now" action.
        Box(
            Modifier
                .padding(12.dp)
                .align(Alignment.TopStart)
                .clickable { companion.pushWeatherNow() },
        ) {
            val linkState = companion.state()
            Text(
                when (linkState) {
                    is CompanionViewModel.LinkUiState.Idle -> "BLE: idle"
                    is CompanionViewModel.LinkUiState.Scanning -> "BLE: scanning…"
                    is CompanionViewModel.LinkUiState.Connecting -> "BLE: connecting…"
                    is CompanionViewModel.LinkUiState.Connected -> "BLE: linked (${linkState.status.ver})"
                    is CompanionViewModel.LinkUiState.Failed -> "BLE: failed"
                },
                color = HudColors.Cyan,
                style = HudeTitle,
            )
        }
    }
}
