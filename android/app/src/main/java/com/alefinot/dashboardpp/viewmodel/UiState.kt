package com.alefinot.dashboardpp.viewmodel

sealed interface ConnectionUiState {
    data class Idle(val reason: String?) : ConnectionUiState

    data class Connecting(
        val mode: ConnectMode,
        val stage: AttemptStage = AttemptStage.DIAL,
    ) : ConnectionUiState

    data class Connected(
        val address: String,
        val name: String,
        val version: String?,
        val mac: String? = null,
        val vehicleId: String,
        val mtu: Int = 0,
    ) : ConnectionUiState

    data class Failed(val reason: String) : ConnectionUiState
}

/** Where in an attempt a [ConnectionUiState.Connecting] is. */
enum class AttemptStage {
    /** Dialing a pairing this phone already holds. No user action needed. */
    DIAL,

    /** Sweeping the radio for dashboards this phone has never met. */
    SCAN,

    PAIR,
}
