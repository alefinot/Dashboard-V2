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
class AccelRunTracker(
    private val maxRuns: Int = DEFAULT_MAX_RUNS,
    /**
     * Which launches a full list spends its room on. The two lanes answer different questions:
     * a drive in progress wants the newest results (and its one best), a sealed file wants the
     * quickest the owner ever managed. See [RunSelection].
     */
    private val selection: RunSelection = RunSelection.RECENT_AND_BEST,
) {

    private val limit = maxRuns.coerceAtLeast(1)

    /** Completed runs, oldest first, never more than [limit] plus the one best they guarantee. */
    private val runs = ArrayList<AccelRun>(DEFAULT_MAX_RUNS + 1)

    /**
     * Launch results this drive has produced, whether or not they are still on the list.
     *
     * The map draws a bounded number of them, so the page has to be able to say *which* ones it
     * is drawing — "10 of 27" is the difference between a short list and a drive that lied about
     * how many launches it had.
     */
    var seen: Int = 0
        private set

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
        seen = 0
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
        seen++
        trim()
        view = null
        return true
    }

    /**
     * Spend the list's room the way [selection] asks.
     *
     * [RunSelection.RECENT_AND_BEST] keeps the drive's newest [limit] launches **and** its
     * quickest, which is one run more than [limit] whenever the best is older than the ten on
     * screen: the owner watching a launch happen should not lose it because the morning's best
     * is still the number to beat, and a best that scrolls off the map is the one result nobody
     * can go back and re-run.
     *
     * [RunSelection.QUICKEST] keeps the [limit] fastest and nothing else — a recorded drive is
     * read for its results, not for its recency, so the slow ones are the ones to let go.
     */
    private fun trim() {
        if (runs.size <= limit) return
        when (selection) {
            RunSelection.RECENT_AND_BEST -> {
                // Keep the newest `limit` by position and the quickest wherever it sits.
                val keep = HashSet<Long>(limit + 1)
                for (i in (runs.size - limit).coerceAtLeast(0) until runs.size) keep.add(runs[i].id)
                bestRun()?.let { keep.add(it.id) }
                runs.removeAll { it.id !in keep }
            }
            RunSelection.QUICKEST -> {
                while (runs.size > limit) {
                    val slowest = runs.maxWithOrNull(SLOWEST_LAST) ?: return
                    runs.remove(slowest)
                }
            }
        }
    }

    /**
     * The drive's quickest launch, or null when there is none to compare.
     *
     * A tie on seconds goes to the launch that started **first**: two identical results are one
     * measurement made twice, and the earlier one is the one the owner can still remember.
     */
    private fun bestRun(): AccelRun? =
        runs.minWithOrNull(compareBy<AccelRun>({ it.seconds }, { it.startedAtMs }))

    companion object {
        /**
         * Results a live drive wears before the oldest (unimportant) one is dropped.
         *
         * Ten launches is a lot of laps, and every one of them is a cased polyline on the map —
         * beyond that the picture is a yellow tangle rather than a record. The best run of the
         * drive is held on top of this number, so a drive in progress shows at most eleven lines.
         */
        const val DEFAULT_MAX_RUNS = 10

        /**
         * The same limit for a **sealed** drive, read back from its file (see [runsFrom]).
         *
         * A recorded drive is looked at for what it achieved, not for what just happened, so the
         * archive spends its ten slots on the quickest launches in the file instead of the newest
         * (maintainer, 2026-11-24: *only the best 10 runs of the accel timer should be kept*).
         * Ten lines is also what the detail map can draw without the yellow turning into a
         * tangle, and it bounds the hit-test the page runs on every tap.
         */
        const val ARCHIVE_MAX_RUNS = 10

        /**
         * Orders runs so the **slowest** sorts last, which is what [RunSelection.QUICKEST] drops.
         * A tie on seconds goes to the later start, so the earlier of two identical results is
         * the one that survives.
         */
        private val SLOWEST_LAST =
            compareBy<AccelRun>({ it.seconds }, { it.startedAtMs })

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

/**
 * The 0-50 runs a recorded drive contains, read back out of the rows it was written from.
 *
 * Nothing had to be added to the file to get them. Every row already carries `tmr_st` and `tmr_s`
 * beside the position, because the unit reports its acceleration timer in the same frame as its
 * fix — so a drive recorded before runs existed still holds all of its launches, and this recovers
 * them instead of migrating files or adding a column the old app would choke on. (A run list stored
 * in a sidecar or a header would have been faster to read and worse in every other way: it could
 * disagree with the rows underneath it, and it could only ever describe drives recorded after the
 * change.)
 *
 * It works by **replaying** the rows through the same [AccelRunTracker] the live frame lane feeds,
 * in file order. That is the whole point: one implementation of the transition rules, so an
 * archived run cannot disagree with the yellow line the live map drew a minute earlier, and a fix
 * to what counts as a run lands on both pages at once.
 *
 * Three things a replay cannot know, all of them honest rather than papered over:
 *
 * - **A launch that crossed while the file was frozen.** The recorder pauses when the link stalls,
 *   so a stretch of the run is simply not in the file. The run keeps the part that was written, and
 *   its line joins across the hole the same single polyline the drive's own route is drawn with on
 *   this page.
 * - **A file that begins mid-launch.** The first RUNNING row opens the run there, exactly as it
 *   does when a phone connects late: a partial path carrying the unit's whole time.
 * - **When the run happened.** [TripSample.tsMs] is the phone's receive time — the ESP's clock is
 *   usually unset — which is the same clock the live card printed, so the card's time means what it
 *   meant live.
 *
 * A cost worth stating: it is one pass over the rows and no allocation per row beyond the handful
 * of points a run holds, and it runs once per file open (see the detail page's `remember`), not per
 * recomposition.
 */
fun runsFrom(samples: List<TripSample>, maxRuns: Int = AccelRunTracker.ARCHIVE_MAX_RUNS): List<AccelRun> {
    if (samples.isEmpty()) return emptyList()
    val replay = RunReplay(maxRuns)
    for (sample in samples) replay.row(sample)
    return replay.runs()
}

/**
 * [runsFrom] as a lane the reader can feed while it parses, instead of a list it has to hold.
 *
 * A twelve-hour drive is 43 000 rows, and the detail page's one-pass reader has no reason to keep
 * a single [TripSample] alive after its speed, altitude and position have been taken: the runs are
 * closed by the same state machine that closes them live, and it only ever needs the frame in
 * front of it. Wrapping the tracker here keeps the replay policy in one place — [runsFrom] is this
 * class over a list, and the page is this class over a file.
 */
class RunReplay(maxRuns: Int = AccelRunTracker.ARCHIVE_MAX_RUNS) {
    private val tracker = AccelRunTracker(maxRuns, RunSelection.QUICKEST)

    /** One file row, in file order. */
    fun row(sample: TripSample) {
        tracker.onFrame(sample.snapshot, sample.tsMs)
    }

    /** The drive's quickest [maxRuns] launches, oldest first. */
    fun runs(): List<AccelRun> = tracker.snapshot()

    /** How many launches the file held, including the ones the archive did not keep. */
    val seen: Int get() = tracker.seen
}

/**
 * Where a tracker spends its room once it holds more launches than the map should draw.
 *
 * The live lane and a recorded file are not asking the same question. A drive in progress is read
 * from its newest end — the launch that just happened is the one the owner is looking at — while a
 * sealed file is read for what the drive achieved, and a slow scramble from a cold start is not
 * worth a line on a picture that is looked at months later.
 */
enum class RunSelection {
    /** The newest `maxRuns` launches, **plus** the drive's quickest wherever it sits. */
    RECENT_AND_BEST,

    /** The `maxRuns` quickest launches in the drive. */
    QUICKEST,
}
