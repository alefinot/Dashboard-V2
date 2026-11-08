package com.alefinot.dashboardpp.viewmodel

sealed interface LinkEvent {
    /** The ACL came down, or a dial refused: [reason] is what the radio said. */
    data class Dropped(val reason: String?) : LinkEvent

    /** `BluetoothAdapter.ACTION_STATE_CHANGED` → on. We never enable it ourselves. */
    data object AdapterOn : LinkEvent

    /** Adapter turned off: every link is gone with it, and dialing is pointless. */
    data object AdapterOff : LinkEvent

    /** The user asked — CONNECT pressed, or a card tapped. Restarts the ladder at 0. */
    data object UserAsked : LinkEvent

    /** A link is up and its identity has been read. Ends the ladder. */
    data object Linked : LinkEvent
}

/** What the ladder decided to do about one event. */
sealed interface LadderStep {
    /** Dial the same pairing again, after [delayMs] of not dialing. */
    data class Retry(val delayMs: Long) : LadderStep

    /** Do nothing, and do not schedule anything: the ladder is over until a finger moves. */
    data object Stop : LadderStep

    data class GiveUp(val reason: String) : LadderStep

    /** The link is up; the ladder has nothing left to decide. */
    data object Linked : LadderStep
}

object ReconnectLadder {

    /** The ladder, in milliseconds since the failure. Its length is the number of retries. */
    val BACKOFF_MS: List<Long> = listOf(2_000, 5_000, 15_000)

    fun blocked(hasPairing: Boolean, adapterOn: Boolean): String? = when {
        !hasPairing -> "That vehicle has never been paired on this phone; scan for it from " +
            "ADD VEHICLE."
        !adapterOn -> "Bluetooth is off on this phone. Turn it on, then press CONNECT."
        else -> null
    }

    fun step(
        event: LinkEvent,
        attempt: Int,
        hasPairing: Boolean,
        adapterOn: Boolean,
    ): LadderStep {
        if (event is LinkEvent.Linked) return LadderStep.Linked

        // A pair-less card is unreachable by definition, and a dead radio cannot dial.
        val blocked = blocked(hasPairing, adapterOn)
        if (blocked != null) {
            return if (event is LinkEvent.UserAsked) LadderStep.GiveUp(blocked) else LadderStep.Stop
        }

        return when (event) {
            LinkEvent.UserAsked -> LadderStep.Retry(0)

            LinkEvent.AdapterOn ->
                if (attempt < BACKOFF_MS.size) LadderStep.Retry(BACKOFF_MS[attempt])
                else LadderStep.Stop

            // Nothing to dial with, and no event that could change that.
            LinkEvent.AdapterOff -> LadderStep.Stop

            is LinkEvent.Dropped ->
                if (attempt < BACKOFF_MS.size) LadderStep.Retry(BACKOFF_MS[attempt])
                else LadderStep.GiveUp(
                    "Gave up reaching the dashboard after ${BACKOFF_MS.size} attempts over " +
                        "Bluetooth${event.reason?.let { " ($it)" } ?: ""}.",
                )

            LinkEvent.Linked -> LadderStep.Linked
        }
    }

    // ------------------------------------------------------------- the quiet restart

    const val REARM_FIRST_MS = 60_000L
    const val REARM_STEP_MS = 300_000L

    fun rearmDelayMs(rearmsDone: Int): Long =
        if (rearmsDone <= 0) REARM_FIRST_MS else REARM_STEP_MS

    fun rearmWanted(
        foreground: Boolean,
        hasPairing: Boolean,
        adapterOn: Boolean,
        linkBusy: Boolean,
        dialInFlight: Boolean,
    ): Boolean = foreground && hasPairing && adapterOn && !linkBusy && !dialInFlight
}
