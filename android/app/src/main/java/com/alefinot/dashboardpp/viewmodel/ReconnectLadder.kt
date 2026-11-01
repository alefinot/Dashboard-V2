package com.alefinot.dashboardpp.viewmodel

/**
 * The four things that can make the app want a Bluetooth link, and nothing else.
 *
 * `ConnectivityManager` used to be the source of these events, and its vocabulary was
 * the wrong shape: a network callback says "a network appeared", which says nothing
 * about whether the dashboard is switched on, in range, or already bonded to this
 * phone. Every event acted on was therefore a connect attempt, and the design question
 * was "which events are allowed to start one". Over Bluetooth the question moves: the
 * events are narrow and unambiguous, and what matters is **how hard we try after one
 * fails**, which is [ReconnectLadder].
 *
 * Drawn as a sum type rather than as callbacks because the ladder is only testable if
 * its inputs are values — a dropped link and a Bluetooth switch turning on produce the
 * same intent in different orders, and the interesting case (the switch coming back on
 * after the ladder already gave up) is a race you cannot otherwise write a test for.
 */
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

    /**
     * Give up, and say [reason] under the card. Distinct from [Stop] because the page
     * has to say something in one case and nothing in the other: "gave up after three
     * tries" is information, while "not trying, Bluetooth is off" is a fact the user
     * can already see on their own status bar.
     */
    data class GiveUp(val reason: String) : LadderStep

    /** The link is up; the ladder has nothing left to decide. */
    data object Linked : LadderStep
}

/**
 * The reconnect rule: one ladder of fixed delays per failure, never a timer loop.
 *
 * The old policy was a per-network *rate limit* (a two-second floor, a five-strikes
 * breaker) built for a callback source that could fire dozens of times a minute. A
 * Bluetooth link has no equivalent: the ACL drop arrives once per connection, the
 * adapter's on/off events are rare and user-visible, and a dial that fails has already
 * spent several seconds of radio time. A rate limiter for that would be machinery
 * without a job, so this is instead what actually decides the behaviour — a bounded
 * ladder of escalating delays, per failure:
 *
 * 2 s → 5 s → 15 s → give up. Three dials, escalating because the two common real
 * causes want different patience: a unit that has just rebooted (an ESP32 restarts in a
 * few seconds, and a `reboot` RPC answers before it pulls its own link down) needs the
 * first retry; a unit that has driven out of range needs the last one, and nobody
 * benefits from a fourth. A drive is one session across reconnects — the identity that
 * makes that true is [UnitIdentity], which is deliberately not part of this decision,
 * so a reconnect can never split or re-attribute a trip.
 *
 * Two things are deliberately *not* here, and their absence is the point:
 *
 *  - **No background retries with the screen off.** Nothing in the app needs a link
 *    while the phone is asleep: trips are recorded by `TripLogService` while it runs,
 *    and a link that comes up unseen then drops again only produces a log line nobody
 *    reads. The ladder lives in the view model, which is alive when the screen is.
 *  - **No re-pairing.** A dropped ACL is not a lost bond, so the ladder dials the
 *    existing pairing. Re-running a pairing ceremony on a link failure would be the
 *    same wrong move as the old code's silent fallback to scanning: it reaches for
 *    *some* dashboard when the user asked for *this* one.
 *
 * [attempt] is how many dials this failure has already caused (0-based) and resets on
 * [LinkEvent.Linked] and on [LinkEvent.UserAsked].
 */
object ReconnectLadder {

    /** The ladder, in milliseconds since the failure. Its length is the number of retries. */
    val BACKOFF_MS: List<Long> = listOf(2_000, 5_000, 15_000)

    /**
     * Why the ladder refuses to dial a card it cannot reach. [hasPairing] is
     * [com.alefinot.dashboardpp.vehicles.Vehicle.hasPairing] — false means this phone has
     * no bond to dial, and no amount of waiting produces one — and [adapterOn] is the
     * last state we saw from the radio, which we never change ourselves.
     */
    fun blocked(hasPairing: Boolean, adapterOn: Boolean): String? = when {
        !hasPairing -> "That vehicle has never been paired on this phone; scan for it from " +
            "ADD VEHICLE."
        !adapterOn -> "Bluetooth is off on this phone. Turn it on, then press CONNECT."
        else -> null
    }

    /**
     * One decision, from the event, how hard we have already tried, and whether there is
     * anything to dial with. The order of the guards is the design: a link that is up
     * ends the argument, and an unreachable card is unreachable whatever the event was —
     * the only difference is whether the page says so (the user asked) or stays quiet
     * (an unattended radio event). Only then does the backoff ladder get a turn.
     *
     * A [Retry] means "dial the same pairing again", and a delay of 0 means now: the
     * user's own ask is never made to wait, while a failure's first retry waits two
     * seconds so a unit that has just rebooted is not dialed before it is back.
     */
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
            // The user pressed something, so they are owed the reason; an unattended
            // event has nothing to report about a condition the user did not cause.
            return if (event is LinkEvent.UserAsked) LadderStep.GiveUp(blocked) else LadderStep.Stop
        }

        return when (event) {
            // An ask restarts the ladder from the top, whatever the counter says: the
            // user is watching the screen and is allowed to try again after we gave up.
            LinkEvent.UserAsked -> LadderStep.Retry(0)

            // The adapter came back on. If the ladder was stopped mid-ladder this is the
            // only thing that can restart it; if it had already given up, a fresh dial is
            // still what the user expects (the radio being off was the whole reason), so
            // the attempt counter continues where it was rather than starting over.
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
}
