package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.viewmodel.AttemptStage
import com.alefinot.dashboardpp.viewmodel.ConnectMode
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState

/**
 * What the shell shows for a connection state — the decision that used to live in
 * `DashboardRoot`'s `when`, drawn there as six branches and three separate screens.
 *
 * The vehicle page made this decision small on purpose. There used to be a screen per
 * state: a splash while the app hunted, a full-page scan ring while it swept the subnet,
 * and three dead ends ("no device found", "connection lost", "no Wi-Fi") that each had
 * one job — get you back to a connection — and in doing so hid the trips and the
 * settings that have nothing to do with the ESP. Every one of them is a line on the page
 * that was already there now, because the page the app opens on is the one where the
 * connection is made: the garage.
 *
 * [reason] is what that line says: why there is nothing connected, or which ladder is
 * running. With the scan ring deleted it is the only place a wait can be explained, and
 * the three modes say different things because a sweep of the whole network and a retry
 * of two remembered addresses are not the same wait.
 */
data class ShellView(
    val conn: ConnectionUiState.Connected?,
    val reason: String?,
    /**
     * Whether the garage's search button has to check for a network before it hunts: when the
     * app has given up with the radio idle, or has nothing to reach for, and what has to be
     * retried is whether there is a pairing and a switch turned on at all. It drives the one
     * label the ADD VEHICLE card uses (SEARCH FOR DASHBOARD++ / SEARCH AGAIN) so the button
     * says what pressing it will cost: a dial of something remembered, or fifteen seconds of
     * sweeping the air for something never met.
     */
    val findIsRetrySearch: Boolean,
)

object ShellRouting {
    fun view(state: ConnectionUiState): ShellView = when (state) {
        // The garage is the first frame, so the state a cold start draws is a shell with
        // nothing connected yet and nothing said about it yet either.
        is ConnectionUiState.Idle -> ShellView(null, state.reason, true)
        is ConnectionUiState.Connecting -> ShellView(null, state.stageLine(), false)
        is ConnectionUiState.Connected -> ShellView(state, null, false)
        is ConnectionUiState.Failed -> ShellView(null, state.reason, false)
    }
}

/**
 * The line a running attempt shows under the card it is working on.
 *
 * The view model publishes a mode and a stage, not a sentence: what the words are is a
 * page decision, and they belong next to the page's other rules rather than inside the
 * loop that decides the ladder.
 *
 * **The stage outranks the mode**, because the stage is the part the user can act on. The
 * three modes differ in how hard the app is trying, which matters to how long a wait
 * should feel; only one stage matters to what someone should be *doing* - the dashboard
 * is showing six digits and asking to be confirmed, and a line about which ladder is
 * running would be a line about the app while the phone is waiting for a finger.
 */
fun ConnectionUiState.Connecting.stageLine(): String = when (stage) {
    // The phone is listening for dashboards it has never met. This is the slow stage, and
    // the one that needs a permission, so the line says what is happening in air terms -
    // "on this network" used to be the honest phrase and is now a lie about the radio.
    AttemptStage.SCAN -> "Scanning for dashboards…"
    // The one stage with a task in it. Named for the thing on the dashboard's screen,
    // because that is where the code is, and Android's own dialog does not say whose
    // number it is asking about.
    AttemptStage.PAIR -> "Pairing — type the code shown on the dashboard"
    // Which ladder, for the stage that needs nobody: dialing a pairing this phone holds.
    AttemptStage.DIAL -> when (mode) {
        // A launch-time reconnect: the app is dialing the pairing this vehicle's card
        // holds, and the user has not asked for anything.
        ConnectMode.SILENT -> "Dialing the selected vehicle's pairing…"
        // A retry at a press: the line says what it relies on, which is also why it is
        // fast. There is no sweep in this mode, and the line must not promise one.
        ConnectMode.PROBE -> "Dialing the pairing again…"
        // A card press whose remembered pairing did not answer: the radio is about to be
        // swept for a unit this phone has never met. The wait belongs to that card.
        ConnectMode.SEARCH -> "Looking for a Dashboard++…"
        // The ADD VEHICLE card's button: the same sweep, but the question is about the
        // air around the phone rather than a vehicle already in the garage, so the line
        // says "new" and the page keeps it under the button that asked.
        ConnectMode.DISCOVER -> "Looking for a new Dashboard++…"
    }
}

/**
 * The three rules the offline shell and the connected one share, separated from the
 * composables so they can be argued about in a test instead of on a tablet.
 */
object ShellRules {
    /**
     * The recorder needs a device to sample: with no link it does not run.
     *
     * The argument used to be an address, which asked this rule to care about *how* the
     * device is reached. It does not: what matters is that a link is up and being
     * followed, which is a boolean and not a string that can be wrong in three ways.
     */
    fun recorderWants(
        linked: Boolean,
        tripsAvailable: Boolean,
        recordingEnabled: Boolean,
    ): Boolean = linked && tripsAvailable && recordingEnabled

    /**
     * A dimmed glyph, not a missing one: the bar keeps five slots in the same order
     * whatever happens, so the swipe never passes through a hole and every page stays
     * reachable. The ESP slot dims for no device; the trips pin dims for firmware with
     * no trip store — both say "nothing here right now", neither says "gone". The
     * vehicle slot is never dimmed: it works with nothing connected, and that is the
     * whole reason it is the home page.
     */
    fun glyphDimmed(tab: AppTab, connected: Boolean, tripsAvailable: Boolean): Boolean = when (tab) {
        AppTab.WEB -> !connected
        AppTab.TRIPS -> !tripsAvailable
        else -> false
    }

    /**
     * Which pages are still pages. Everything is, offline — Live and the ESP slot
     * answer with a cover — and the one rule that predates the offline shell stays:
     * firmware with no trip store does not get a trips page.
     */
    fun pageReachable(tab: AppTab, connected: Boolean, tripsAvailable: Boolean): Boolean =
        tab != AppTab.TRIPS || tripsAvailable

    /**
     * The one case in which the shell moves the user off a page they are standing on.
     * It exists for firmware with no trip store, where the trips page would show an
     * error instead of a list. It is *not* the case of a missing device: offline the
     * trip files are the phone's own, so the page is real and a connection dropping
     * while someone reads a trip leaves them where they were. Getting this wrong turns
     * the offline shell into the dead-end screen it replaced, one swipe at a time.
     */
    fun pageUnserved(tripsAvailable: Boolean, connected: Boolean): Boolean =
        connected && !tripsAvailable

    /**
     * Whether the ESP page may be warmed (built and fetched while the tab is shut).
     * It is Chromium work spent on a page the user is not looking at, so it is allowed
     * once per connection, only when there is a device to fetch from — offline there is
     * nothing to fetch and the offline shell's whole point is that it holds no WebView
     * process — and never once the user has opened the slot themselves.
     */
    fun shouldWarm(connected: Boolean, seen: Boolean, warmed: Boolean): Boolean =
        connected && !seen && !warmed
}
