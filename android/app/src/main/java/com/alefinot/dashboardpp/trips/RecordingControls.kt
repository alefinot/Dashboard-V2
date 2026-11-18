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
 *    window between START and the first sample; STOP is what backs out of it. The one
 *    exception is a lane kept alive for the display alone (`watching`): that drive is
 *    over, the picture is still moving, and the honest offer is START again.
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
            /**
             * True when the frame lane is running for the display alone (2.0.7/179): a drive
             * has finished, the app is open, and the lane is kept alive only so the live map
             * goes on moving. See [TripRuntimeState.watching].
             */
            watching: Boolean = false,
        ): RecordingControlState = when {
            paused -> PAUSED
            recording -> RECORDING
            // A watching lane is idle, not waiting. WAITING's press is STOP, and on this lane
            // a STOP is put straight back down again by the app that owns it, which leaves the
            // owner pressing a pill that answers nothing (bench, 2026-11-18). What is actually
            // true here is "nothing is recording, and a link is ready" - which is START.
            polling && !watching -> WAITING
            canRecord -> START
            else -> UNAVAILABLE
        }
    }
}

/**
 * What one press of the recording pill (or the Trips recording card) means in a state.
 *
 * The controls are no longer a row of buttons: the pill that names the state *is* the control,
 * and the card that shows the drive *is* the control. That makes the meaning of a press part of
 * the state rather than part of a layout, so it lives here where both screens read it and where
 * it can be tested without a screen - the same reason [RecordingControlState] lives here.
 *
 * [NONE] is not a disabled button: it is a pill with nothing to offer (no link, or a firmware
 * with no trip store), and a press must not do anything at all, least of all start a service
 * that has nowhere to write.
 */
enum class RecordingTap {
    /** Open a new file and turn auto-start on (`START`). */
    START,

    /** Hold the open file at this instant (`RECORDING`). */
    PAUSE,

    /** Carry on appending to the same file (`PAUSED`). */
    RESUME,

    /** Back out of a service that has not opened a file yet (`WAITING`). */
    STOP,

    /** Nothing to do. */
    NONE,
}

/** What a single press of the control does in this state. */
fun RecordingControlState.tap(): RecordingTap = when (this) {
    RecordingControlState.START -> RecordingTap.START
    RecordingControlState.RECORDING -> RecordingTap.PAUSE
    RecordingControlState.PAUSED -> RecordingTap.RESUME
    RecordingControlState.WAITING -> RecordingTap.STOP
    RecordingControlState.UNAVAILABLE -> RecordingTap.NONE
}

/**
 * Whether this state has anything to stop, and therefore whether the press has a second,
 * longer meaning. A drive that is not being written has nothing to seal: filling a control red
 * over three seconds to conclude "nothing happened" is a control that lied about its own state.
 */
fun RecordingControlState.canHold(): Boolean =
    this == RecordingControlState.RECORDING || this == RecordingControlState.PAUSED
