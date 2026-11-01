package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live picture the recorder publishes for the UI (trip recording plan §5).
 * A single process-wide holder because the foreground service owns the only reader
 * of the unit's stream: the Live tab renders these values instead of starting its own
 * connection, which is what keeps the device at one frame per second.
 */
data class TripRuntimeState(
    /**
     * Which dashboard these readings came from, as [com.alefinot.dashboardpp.vehicles
     * .UnitIdentity.key] spells it — the unit's MAC when it reported one, otherwise the
     * BLE address the link is on.
     *
     * The field used to be `ip`, and that was not only a stale name: an IP identified a
     * *path* to a device, which changes every time the phone joins a different network,
     * while a trip file has to outlive all of them. A device key is the same value the
     * vehicle cards use, so a file written on the road and a card at home agree about
     * who the readings belong to.
     */
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

/**
 * The owner the app hands the recorder when it starts it: which dashboard is being
 * sampled (by device key, not by address — see [TripRuntimeState.deviceKey]), which
 * card that dashboard belongs to, that card's name at this moment, and what the
 * dashboard reported as its eFuse identity. A pushed frame carries none of it, and a
 * trip file outlives the connection that created it, so the app says this once, at the
 * only moment it knows it [plan §6].
 */
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

/**
 * Whether this device can be recorded from.
 *
 * The cheap first pass is the firmware version the app read while naming the unit. It
 * used to have a second half — the endpoint itself answering 404 while `/api/live`
 * answered — and that half is gone: a unit this app can pair with answers the `snapshot`
 * RPC, so there is no second opinion to gather and a wrong version label is the only way
 * the gate can be wrong. An unparseable label therefore stays capable, exactly as before.
 */
object TripsEnabled {
    /** First firmware with the snapshot frame. */
    const val MIN_VERSION = "1.4.6"

    /**
     * Numeric comparison rather than lexicographic ("1.10.2" is older than
     * "1.4.6" to a string compare). Unknown or oddly shaped version strings are
     * treated as capable, so a version label the parser does not recognise never
     * disables a feature the device has.
     */
    fun forFirmware(version: String?): Boolean =
        com.alefinot.dashboardpp.vehicles.firmwareAtLeast(version, MIN_VERSION)
}
