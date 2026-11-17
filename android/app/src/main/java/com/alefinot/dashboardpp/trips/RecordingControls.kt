package com.alefinot.dashboardpp.trips

/**
 * What the trip-recording controls offer, from the four facts the pages already have.
 *
 * The rule lives here rather than in either composable because the two pages must offer
 * the same thing in the same state — the Trips page and the Live page sit one swipe
 * apart, and a PAUSE that exists on one and not the other is a bug the owner finds by
 * losing a drive.
 *
 * The order matters:
 *
 *  - **A running drive outranks the link.** A recording (or a pause) is something the
 *    owner can still stop after the unit goes out of range; hiding STOP behind
 *    "connect a dashboard" would be the one moment the control is needed most.
 *  - **A live service is never "unavailable".** `polling` with no open file is the
 *    window between START and the first sample; STOP is what backs out of it.
 *  - **`canRecord` only decides START vs unavailable**, because it is the one state that
 *    needs a link to act on.
 */
enum class RecordingControlState {
    /** Nothing to record from (no link, or firmware with no trip store); START is disabled. */
    UNAVAILABLE,

    /** Idle and able to record. */
    START,

    /** The service is alive but no trip file is open yet. */
    WAITING,

    /** Samples are being appended; PAUSE and STOP are offered. */
    RECORDING,

    /** The open trip is paused; RESUME and STOP are offered. */
    PAUSED,
    ;

    companion object {
        fun of(
            recording: Boolean,
            paused: Boolean,
            polling: Boolean,
            canRecord: Boolean,
        ): RecordingControlState = when {
            paused -> PAUSED
            recording -> RECORDING
            polling -> WAITING
            canRecord -> START
            else -> UNAVAILABLE
        }
    }
}
