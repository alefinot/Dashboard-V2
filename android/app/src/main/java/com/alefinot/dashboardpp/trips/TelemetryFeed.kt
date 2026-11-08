package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot

/**
 * The two rules that decide what the dashboard's 1 Hz stream is worth.
 *
 * The unit pushes a snapshot every second on its own characteristic - the same flat
 * JSON the HTTP transport used to fetch - so the app no longer asks. That inverts one
 * assumption the recorder was built on: a poll that runs once a second can trust that
 * every response is a new reading, while a stream cannot. Two things go wrong without
 * a rule, and both are cheap to get wrong silently:
 *
 *  - **Replays.** The link subscribes once and the firmware re-sends a frame when a
 *    notification is re-issued after a reconnect, and the app also reads the snapshot
 *    RPC itself when the stream goes quiet (below). A duplicated frame appended to a
 *    trip file is a sample that never happened: it inflates the distance, the sample
 *    count and the average, and it is invisible afterwards because the CSV carries no
 *    marker of where it came from.
 *  - **A silent link that still says it is up.** A `Ready` link with nothing arriving
 *    is either a unit mid-reboot or a notification subscription the peripheral dropped.
 *    Nothing in the state distinguishes them, so the app prods once with a `snapshot`
 *    request and lets the answer decide: frames resume, or the existing stale display
 *    says what it knows.
 *
 * Both rules are pure here, over the values rather than over a radio, because "is this
 * frame new" is exactly the kind of rule that gets quietly wrong at 3 a.m. in a car.
 */
object TelemetryFeed {

    /** How long a `Ready` link may stay quiet before the app asks once. */
    const val SILENT_MS = 5_000L

    /**
     * True when a frame with `up` = [frameUp] is worth recording, given the last frame
     * accepted carried `up` = [lastUp].
     *
     * `up` is the ESP's own uptime counter, so it moves by about one every frame and it
     * only ever goes back to a small number when the unit restarts - which is exactly
     * the case that must be accepted, because a restart is a fresh session on the same
     * link and the recorder splits the trip on it. So the whole rule is "a different
     * reading": equal means the same frame twice (a re-issued notification after a
     * reconnect, or the app's own prod coming back after a pushed frame already
     * landed), and a sample that was never measured should not reach a trip file.
     */
    fun accepts(lastUp: Long, frameUp: Long): Boolean = lastUp < 0L || frameUp != lastUp

    /**
     * True when the app should spend one `snapshot` request because the stream has been
     * quiet.
     *
     * [lastFrameMs] is 0 until the first frame lands, which is the case where prodding
     * is also right: a link that reports itself ready and has never pushed a frame is a
     * subscription that did not take. [pollInFlight] keeps this to one request at a
     * time - a page that asked twice a second would turn a 1 Hz push into a fight.
     */
    fun dueForPoll(lastFrameMs: Long, nowMs: Long, pollInFlight: Boolean): Boolean =
        !pollInFlight && (lastFrameMs == 0L || nowMs - lastFrameMs >= SILENT_MS)

    /**
     * The frame the app got from asking, when the stream went quiet - or null when the
     * answer is not worth a sample.
     *
     * The guard is the same "a different reading" rule as [accepts], applied to the reply:
     * a prod that comes back with the frame already accepted is a duplicate, and a
     * duplicated sample in a trip file is invisible afterwards.
     *
     * What this function does **not** claim is the ability to tell a stale reply from a
     * fresh one by `up`: a reply whose `up` is smaller may be a rebooted unit, and may be
     * an old answer, and the number cannot say which. That is why the caller only asks
     * when the wait produced nothing at all - a prod is never in flight while a pushed
     * frame is being accepted, so "the pushed frame wins" is decided by arrival, where it
     * is actually knowable, and not inferred from a counter here.
     */
    fun mergeProd(lastUp: Long, reply: Snapshot?): Snapshot? =
        reply?.takeIf { accepts(lastUp, it.up) }
}
