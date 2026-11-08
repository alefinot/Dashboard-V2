package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TripRuntimeState(
    val deviceKey: String? = null,
    /** True while the service is alive and following the stream. */
    val polling: Boolean = false,
    /** True while samples are being appended to a trip file. */
    val recording: Boolean = false,
    val tripPath: String? = null,
    val tripStartMs: Long = 0L,
    val snapshot: Snapshot? = null,
    /** Running summary of the open trip; null until the first sample lands. */
    val summary: TripSummary? = null,
    val lastOkMs: Long = 0L,
    val lastError: String? = null,
) {
    /** Samples are stale if nothing arrived for this long. */
    val stale: Boolean
        get() = polling && (System.currentTimeMillis() - lastOkMs) > 5_000L
}

data class RecorderTarget(
    val deviceKey: String,
    val vehicleId: String,
    val vehicleName: String,
    val eff: String,
)

object TripRuntime {
    private val _state = MutableStateFlow(TripRuntimeState())
    val state: StateFlow<TripRuntimeState> = _state

    fun update(block: (TripRuntimeState) -> TripRuntimeState) {
        _state.value = block(_state.value)
    }

    /** Called when the device is forgotten (disconnect) so nothing leaks over. */
    fun clearConnection() {
        _state.value = TripRuntimeState()
    }
}

object TripsEnabled {
    /** First firmware with the snapshot frame. */
    const val MIN_VERSION = "1.4.6"

    fun forFirmware(version: String?): Boolean =
        com.alefinot.dashboardpp.vehicles.firmwareAtLeast(version, MIN_VERSION)
}
