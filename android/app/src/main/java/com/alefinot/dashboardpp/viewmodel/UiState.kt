package com.alefinot.dashboardpp.viewmodel

sealed interface ConnectionUiState {
    object Booting : ConnectionUiState
    object CheckingCache : ConnectionUiState
    object CheckingSoftAp : ConnectionUiState
    object Discovering : ConnectionUiState
    data class Connected(val ip: String, val version: String?) : ConnectionUiState
    object ManualEntryNeeded : ConnectionUiState
    data class ConnectionLost(val reason: String) : ConnectionUiState
    data class NoWifi(val detail: String) : ConnectionUiState
}
