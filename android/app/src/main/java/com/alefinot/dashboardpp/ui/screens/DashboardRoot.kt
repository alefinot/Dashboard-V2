package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.ScanProgressRing
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeDisplayLarge
import com.alefinot.dashboardpp.ui.theme.HudeLabel
import com.alefinot.dashboardpp.ui.theme.HudeSmall
import com.alefinot.dashboardpp.ui.theme.HudeTitle
import com.alefinot.dashboardpp.viewmodel.CompanionViewModel
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel

@Composable
fun DashboardRoot(activity: android.app.Activity, vm: ConnectionViewModel) {
    val showSettings = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.boot() }
    val s = vm.uiState.value
    // Keep content inside the safe area: with targetSdk 35 edge-to-edge is
    // enforced, so without these insets the HUD chrome draws behind the
    // system bars and overlaps the status bar / nav bar.
    // The BLE companion (the ESP is the GATT server; the app is the central).
    // Created once (remember); boots when the connection is established.
    val companion = remember { CompanionViewModel(activity.application) }
    // 8-11: the BLE scan needs ACCESS_FINE_LOCATION (12+ uses BLUETOOTH_SCAN,
    // requested at launch in MainActivity). One ask per session; on grant
    // the scan re-enters from the BleLink.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) companion.link.onLocationPermissionGranted()
    }
    companion.link.onRequestLocation = {
        activity.runOnUiThread {
            locationLauncher.launch("android.permission.ACCESS_FINE_LOCATION")
        }
    }
    // The app root is rare to leave, but the BleLink + weather timer must
    // not outlive the screen: dispose on leave.
    DisposableEffect(Unit) { onDispose { companion.dispose() } }
    LaunchedEffect(s) {
        if (s is ConnectionUiState.Connected) {
            companion.setIp(s.ip)
            companion.boot()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        when (s) {
            is ConnectionUiState.Booting -> SplashScreen()
            is ConnectionUiState.CheckingCache -> DiscoveryScreen(vm, "Checking saved device…")
            is ConnectionUiState.CheckingSoftAp -> DiscoveryScreen(vm, "Checking SoftAP…")
            is ConnectionUiState.Discovering -> DiscoveryScreen(vm, "Locating Dashboard++…")
            is ConnectionUiState.ManualEntryNeeded -> ManualEntryScreen(vm)
            is ConnectionUiState.ConnectionLost -> ConnectionLostScreen(vm, s.reason)
            is ConnectionUiState.NoWifi -> NoWifiScreen(vm, s.detail)
            is ConnectionUiState.Connected -> DashboardWebScreen(
                activity,
                vm,
                s.ip,
                companion,
                onSettings = { showSettings.value = true },
            )
        }
        if (showSettings.value && s is ConnectionUiState.Connected) {
            SettingsScreen(
                vm,
                activity,
                s.ip,
                s.version,
                onDismiss = { showSettings.value = false },
            )
        }
    }
}

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "DASHBOARD++",
                color = HudColors.Cyan,
                style = HudeDisplayLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "ESP32 companion",
                color = HudColors.TextDim,
                style = HudeLabel,
            )
        }
    }
}

@Composable
fun DiscoveryScreen(vm: ConnectionViewModel, title: String) {
    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanProgressRing(vm.scanProgress.value, Modifier.size(56.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                color = HudColors.TextPrimary,
                style = HudeTitle,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Scanning local network…",
                color = HudColors.TextDim,
                style = HudeSmall,
            )
        }
    }
}
