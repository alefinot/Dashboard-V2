package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot

/**
 * One acceleration run the unit completed, and the ground it happened across.
 *
 * The measurement is the dashboard's, never the app's: `updateAccelTimer()` in `src/sensors.cpp`
 * opens a run when the filtered speed crosses `ACCEL_START_SPEED` and closes it at
 * `ACCEL_TARGET_SPEED` — or at the `ACCEL_MAX_TIME` cap — and reports that machine once a second
 * as `tmr_st` / `tmr_s` in the same BLE frame that carries the position. So the fix that reports
 * a transition *is* a fix at the instant of that transition, and a run's shape is simply the
 * string of fixes from the one that first said RUNNING through the one that said FINISHED.
 *
 * At the unit's 1 Hz that is five or six points for a fast bike — the same resolution the drive's
 * own line is made of, and no finer, because nothing finer exists. [seconds] is the unit's number
 * rather than arithmetic over two fixes: the two clocks never agree, and a duration measured from
 * fixes ~1 s apart is wrong by about that much. The app adds geometry, never a result.
 */
data class AccelRun(
    /** Distinct within a tracker, and stable: the live map keys a run's polyline on it. */
    val id: Long,
    /** Wall clock at the run's first fix — the card's "at 18:42:07" line. */
    val startedAtMs: Long,
    /** Seconds the unit measured, read off `tmr_s` in the frame that closed the run. */
    val seconds: Double,
    /**
     * The positions the run covered, oldest first.
     *
     * A run the app saw from its first RUNNING frame spans the whole launch. A run joined
     * mid-flight — the app connected, or a new drive opened, while the unit was already
     * launched — holds only the part that was seen, and keeps the unit's whole [seconds]: a line
     * from partway down the run, labelled with the real result, is the honest pair.
     */
    val points: List<TrackPoint>,
    /** km/h at the run's first fix, and at its last. */
    val startSpeedKmh: Double,
    val finishSpeedKmh: Double,
)

/**
 * Reads the unit's acceleration timer out of the frame stream and keeps the completed runs.
 *
 * The state machine is the firmware's; this mirrors it only as far as a picture needs it:
 *
 * - **not RUNNING → RUNNING** opens a run at this position. It arrives from READY (a launch) or
 *   from FINISHED (the next launch, which the firmware only arms once the car has dropped below
 *   `ACCEL_START_SPEED` and pulled away again) — either way a run starts here, and the unit has
 *   just zeroed its clock, so this frame's `tmr_s` is near enough zero to be the start.
 * - **RUNNING → RUNNING** is the run continuing: another position.
 * - **RUNNING → FINISHED** closes it, and this is the *only* frame worth reading the result from.
 *   A finished run holds its `tmr_s` on every later frame until the car stops again, so a frame
 *   that merely *reports* FINISHED says nothing new — while the frame that *arrives* at FINISHED
 *   carries the true finish instant and the position the target speed was crossed at.
 * - **RUNNING → READY** is an aborted launch: the firmware's own `currentSpeed <
 *   ACCEL_START_SPEED` branch, which zeroes `accelResultTime` and keeps no result. There is no
 *   run to draw and the half-open path is thrown away with it.
 * - **FINISHED → FINISHED**, or a first frame that is already FINISHED, is a run this app saw no
 *   part of: no geometry, nothing to tap, so nothing is published.
 *
 * A run the timer did not finish is dropped too: at the cap, `tmr_s` has nothing behind it but
 * elapsed time and the target was never reached, so calling the result a 0-50 would be a lie. See
 * [publish] for how the two signals — the expired clock and the speed the run ended at — are
 * weighed against each other.
 *
 * [onFrame] is called once per accepted frame, which is the whole contract: the unit re-sends
 * nothing that was already counted ([com.alefinot.dashboardpp.service.TelemetryFeed] dedups on
 * uptime), and a frame the lane rejects never reaches a tracker.
 */
class AccelRunTracker(private val maxRuns: Int = DEFAULT_MAX_RUNS) {

    private val limit = maxRuns.coerceAtLeast(1)

    /** Completed runs, oldest first, never more than [limit]. */
    private val runs = ArrayList<AccelRun>(DEFAULT_MAX_RUNS)

    /** The immutable view of [runs], dropped whenever one is added or thrown out. */
    private var view: List<AccelRun>? = null

    /**
     * The run in progress: the positions heard since `tmr_st` last became RUNNING.
     *
     * Held here rather than read back out of [com.alefinot.dashboardpp.trips.LiveTrack] because
     * that list throws points away on purpose — a position is dropped as soon as a straight line
     * between its neighbours covers it — and the redundancy test may rewrite a piece's middle at
     * any time. A run is at most a handful of fixes long, so it can afford to keep every one, and
     * a run's line has to be exactly the fixes the launch was made of even when the drive's own
     * line thinned underneath it.
     */
    private val openPoints = ArrayList<TrackPoint>(MAX_OPEN_POINTS)
    private var openStartMs = 0L
    private var openStartSpeed = 0.0

    /** True while a run is open: set by a RUNNING frame, cleared by any other state. */
    private var open = false

    private var nextId = 1L

    /**
     * One frame of the stream. Returns true when the published list changed — which is only ever
     * when a run closed, so the identity of [snapshot] holds through a whole run in progress and
     * a map redraws its run lines once per result, not once a second.
     */
    fun onFrame(s: Snapshot, atMs: Long): Boolean {
        when (s.tmrSt) {
            STATE_RUNNING -> {
                if (!open) {
                    openPoints.clear()
                    openStartMs = atMs
                    openStartSpeed = s.spd
                    open = true
                }
                // A cap on an open run's length is arithmetic insurance, not a policy: the cap is
                // ACCEL_MAX_TIME seconds of fixes, and a run that long never reaches the target
                // and is dropped anyway. Refusing further points is all it does — the run's own
                // start stays where it was.
                if (openPoints.size < MAX_OPEN_POINTS && isRealPosition(s.lat, s.lon)) {
                    openPoints.add(TrackPoint(s.lat, s.lon))
                }
                return false
            }
            STATE_FINISHED -> if (open) return publish(s)
            else -> open = false
        }
        return false
    }

    /** The runs to draw, by identity: the same list object until a run is added or dropped. */
    fun snapshot(): List<AccelRun> {
        val cached = view
        if (cached != null) return cached
        val made = runs.toList()
        view = made
        return made
    }

    /** Forget everything, including a run in progress. Called when the drive changes. */
    fun clear() {
        runs.clear()
        openPoints.clear()
        open = false
        view = null
    }

    /**
     * Close the open run and keep it, or refuse it.
     *
     * Two signals say whether this run was really a 0-50, and neither is enough alone. The speed
     * the finish frame reported answers it directly when it is at or over the target — the same
     * filtered number the firmware compared against `ACCEL_TARGET_SPEED`. Below the target, the
     * clock is all there is: at the cap `tmr_s` reaches `ACCEL_MAX_TIME` (9.99 s by default), so a
     * run that ended at the cap with a sub-target speed is the timer expiring and nothing more.
     * A short time under a sub-target speed keeps its run: the unit voted FINISHED for a reason
     * the app cannot see from here (a fix that landed a moment after the speed dipped is the
     * ordinary cause), and dropping a real result to be tidy is the worse mistake.
     */
    private fun publish(s: Snapshot): Boolean {
        open = false
        val made = ArrayList<TrackPoint>(openPoints.size + 1)
        made.addAll(openPoints)
        // The finish frame's position is the target speed's place on the ground, so it belongs to
        // the run even when the launch itself was outside a fix.
        if (isRealPosition(s.lat, s.lon)) made.add(TrackPoint(s.lat, s.lon))
        openPoints.clear()
        if (made.isEmpty()) return false
        if (s.tmrS >= CAP_SECONDS && s.spd < TARGET_KMH) return false
        runs.add(
            AccelRun(
                id = nextId++,
                startedAtMs = openStartMs,
                seconds = s.tmrS,
                points = made,
                startSpeedKmh = openStartSpeed,
                finishSpeedKmh = s.spd,
            ),
        )
        while (runs.size > limit) runs.removeAt(0)
        view = null
        return true
    }

    companion object {
        /**
         * Results a drive may wear before the oldest is dropped. Twenty launches is a lot of
         * laps, and every one of them is a cased polyline on the map — beyond that the picture is
         * a yellow tangle rather than a record.
         */
        const val DEFAULT_MAX_RUNS = 20

        /** Ceiling on one open run's positions; see the note where it is enforced. */
        private const val MAX_OPEN_POINTS = 64

        /** `TimerState` in `src/dashboard.h`: READY, RUNNING, FINISHED. */
        private const val STATE_RUNNING = 1
        private const val STATE_FINISHED = 2

        /**
         * A result this far along is the firmware's `ACCEL_MAX_TIME` cap, not a reached target:
         * the default cap is 9.99 s and the firmware writes `tmr_s` with `%.2f`, so anything at or
         * over 9.9 can only be the clock running out. An owner who sets a different cap gets this
         * same reading of the default, which is what the pair of signals in [publish] is for.
         */
        private const val CAP_SECONDS = 9.9

        /** The target the badge's "0-50" wording — and the map's yellow line — is named for. */
        private const val TARGET_KMH = 50.0
    }
}
