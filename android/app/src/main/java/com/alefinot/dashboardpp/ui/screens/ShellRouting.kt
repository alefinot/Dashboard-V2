package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.viewmodel.AttemptStage
import com.alefinot.dashboardpp.viewmodel.ConnectMode
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState

data class ShellView(
    val conn: ConnectionUiState.Connected?,
    val reason: String?,
    val findIsRetrySearch: Boolean,
)

object ShellRouting {
    fun view(state: ConnectionUiState): ShellView = when (state) {
        is ConnectionUiState.Idle -> ShellView(null, state.reason, true)
        is ConnectionUiState.Connecting -> ShellView(null, state.stageLine(), false)
        is ConnectionUiState.Connected -> ShellView(state, null, false)
        is ConnectionUiState.Failed -> ShellView(null, state.reason, false)
    }
}

fun ConnectionUiState.Connecting.stageLine(): String = when (stage) {
    AttemptStage.SCAN -> "Scanning for dashboards…"
    AttemptStage.PAIR -> "Pairing — type the code shown on the dashboard"
    // Which ladder, for the stage that needs nobody: dialing a pairing this phone holds.
    AttemptStage.DIAL -> when (mode) {
        ConnectMode.SILENT -> "Dialing the selected vehicle's pairing…"
        ConnectMode.PROBE -> "Dialing the pairing again…"
        ConnectMode.SEARCH -> "Looking for a Dashboard++…"
    }
}

object ShellRules {
    fun recorderWants(
        linked: Boolean,
        tripsAvailable: Boolean,
        recordingEnabled: Boolean,
    ): Boolean = linked && tripsAvailable && recordingEnabled

    fun glyphDimmed(tab: AppTab, connected: Boolean, tripsAvailable: Boolean): Boolean = when (tab) {
        AppTab.WEB -> !connected
        AppTab.TRIPS -> !tripsAvailable
        else -> false
    }

    fun pageReachable(tab: AppTab, connected: Boolean, tripsAvailable: Boolean): Boolean =
        tab != AppTab.TRIPS || tripsAvailable

    fun pageUnserved(tripsAvailable: Boolean, connected: Boolean): Boolean =
        connected && !tripsAvailable

    fun shouldWarm(connected: Boolean, seen: Boolean, warmed: Boolean): Boolean =
        connected && !seen && !warmed
}
