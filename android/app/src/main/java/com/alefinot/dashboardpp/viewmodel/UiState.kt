package com.alefinot.dashboardpp.viewmodel

sealed interface ConnectionUiState {
    /**
     * Nothing is being reached for, and nothing is connected. [reason] is what the
     * vehicle page shows: why there is nothing (no card selected, the card has never
     * connected), or null when the app simply has not tried yet — which is the state a
     * cold start draws, so the garage is on screen before any network request runs.
     */
    data class Idle(val reason: String?) : ConnectionUiState

    /**
     * An attempt is running. [mode] says how far it is going, which is what tells the
     * page whether to show a scan ring: a [ConnectMode.SILENT] reconnect must not put
     * any screen in front of the garage, while a search the user asked for shows its
     * progress inside the card. [stage] says where in the attempt we are, because the
     * honest line under a card changes mid-attempt: dialing a pairing the phone already
     * holds, sweeping the radio for units we have never met, and being mid-pairing with
     * the code showing on the dashboard are three different things to tell the user.
     */
    data class Connecting(
        val mode: ConnectMode,
        val stage: AttemptStage = AttemptStage.DIAL,
        val vehicleId: String? = null,
    ) : ConnectionUiState

    /**
     * A unit is connected.
     *
     * [address] is the Bluetooth address of the link — this phone's name for the pairing,
     * which is why it is never shown to the user as if it were an identity, and why
     * [mac] (the unit's own eFuse identity, null on firmware that predates it) is what
     * the trip key is built from. [vehicleId] is the card it belongs to: the two together
     * are what the trip log tags, so a drive records which vehicle it was in rather than
     * which link answered. [mtu] is what the negotiated ATT MTU turned out to be, which
     * the settings page shows as a write-budget number.
     */
    data class Connected(
        val address: String,
        val name: String,
        val version: String?,
        val mac: String? = null,
        val vehicleId: String,
        val mtu: Int = 0,
    ) : ConnectionUiState

    /**
     * An attempt finished and nothing verified. [reason] is the line the page shows —
     * it absorbs what used to be three separate screens (`NoWifi`,
     * `ManualEntryNeeded`, `ConnectionLost`), all of which are now a line under the
     * card that was being reached for, with the rest of the app still on screen.
     */
    data class Failed(val reason: String) : ConnectionUiState
}

/** Where in an attempt a [ConnectionUiState.Connecting] is. */
enum class AttemptStage {
    /** Dialing a pairing this phone already holds. No user action needed. */
    DIAL,

    /** Sweeping the radio for dashboards this phone has never met. */
    SCAN,

    /**
     * Bringing a newly found unit up, which is the one stage that may need a finger:
     * the dashboard shows a six-digit code and the system pairing sheet asks for it.
     */
    PAIR,
}
