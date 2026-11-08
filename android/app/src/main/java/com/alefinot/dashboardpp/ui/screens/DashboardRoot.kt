package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.viewmodel.TripsViewModel

@Composable
fun DashboardRoot(activity: android.app.Activity, vm: ConnectionViewModel) {
    val trips = remember { TripsViewModel(activity.application) }
    LaunchedEffect(Unit) { vm.boot() }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onForegroundChanged(true)
                Lifecycle.Event.ON_STOP -> vm.onForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val s = vm.uiState.value
    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val view = ShellRouting.view(s)
        AppShell(
            conn = view.conn,
            reason = view.reason,
            connection = vm,
            trips = trips,
        )
    }
}
