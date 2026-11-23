package com.alefinot.dashboardpp.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The start tour, as data.
 *
 * One card per fact the app wants a first-timer to keep: what the whole thing
 * is, then one card per page of the bar in the order the tour walks them, then
 * a closer. The copy lives here and nowhere else; [TutorialOverlay] only draws
 * what this list says, which is what lets the tour be tested without a screen —
 * the same split as `SessionKeysTest` and the other pure-rule files.
 *
 * A step names its mark by the ledger id (`nav.live`, …), never by the drawing:
 * the tour's badges are the same marks the bar itself draws, and a step that
 * invented its own icon would be a second vocabulary nobody registered. The
 * two ends of the tour — welcome and ready — borrow marks the ledger already
 * owns: the launcher's own reading for hello, the accept mark for done.
 */
data class TutorialStep(
    /** Stable identity of the card, e.g. for tests and future deep links. */
    val id: String,
    /** `HudGlyphLedger` id for the card's badge. */
    val glyphId: String,
    val title: String,
    val body: String,
)

object TutorialScript {

    /** The whole tour, in tour order. The bar order is LIVE · TRIPS · GARAGE · UNIT · APP. */
    val steps: List<TutorialStep> = listOf(
        TutorialStep(
            id = "welcome",
            glyphId = "readout.speed",
            title = "Welcome to Dashboard++",
            body = "This app is the cockpit of your Dashboard++. It links " +
                "over Bluetooth, shows live speed and engine data on gauges and a " +
                "map, records every drive, tunes the unit\u2019s own settings, and " +
                "keeps both firmwares updated. Here is the bar, page by page.",
        ),
        TutorialStep(
            id = "vehicles",
            glyphId = "nav.vehicles",
            title = "Garage \u2014 the middle",
            body = "Everything starts here: scan for your Dashboard++, pair it, give " +
                "it a name and a photo, and keep one card per vehicle. The app " +
                "always opens on this page, whether or not a unit is connected.",
        ),
        TutorialStep(
            id = "live",
            glyphId = "nav.live",
            title = "Live",
            body = "Real-time gauges, five-minute traces and your route on a " +
                "live map, following Dashboard++ while it is connected. A drive " +
                "can be started or stopped here.",
        ),
        TutorialStep(
            id = "trips",
            glyphId = "nav.trips",
            title = "Trips",
            body = "Every drive the app recorded, as files on this phone. Open " +
                "one for its route, charts and stats, tag it, or export GPX and " +
                "CSV.",
        ),
        TutorialStep(
            id = "unit-settings",
            glyphId = "nav.unit-settings",
            title = "Dashboard++ settings",
            body = "The unit\u2019s own page, drawn on the phone: every firmware " +
                "parameter read and written over Bluetooth \u2014 units, alarms, " +
                "brightness, demo mode \u2014 plus unit diagnostics and config " +
                "backup.",
        ),
        TutorialStep(
            id = "app-settings",
            glyphId = "nav.app-settings",
            title = "App settings",
            body = "The phone\u2019s page: app and firmware updates from GitHub " +
                "releases, recording and storage, the live-map ground, the weather " +
                "feed, motion. The tour itself is re-shown from here, under " +
                "GETTING STARTED.",
        ),
        TutorialStep(
            id = "ready",
            glyphId = "action.accept",
            title = "Ready",
            body = "Swipe between the pages, or tap the bar to jump. Add your " +
                "Dashboard++ on the Garage page to begin. Happy driving!",
        ),
    )

    /**
     * The startup rule, in one line: show unless the owner has flagged the
     * tour off, and show once per process — a rotation or a return from
     * recents is not a startup.
     */
    fun shouldShowAtStartup(hidden: Boolean, shownThisProcess: Boolean): Boolean =
        !hidden && !shownThisProcess
}

/**
 * The tour's visibility, mirrored out of the composition like `MapStylePref`
 * and `HudMotion` mirror their preferences: App Settings raises it without a
 * route or a prop drilled through the pager, and [DashboardRoot] — the one
 * place that draws the overlay — is the only reader.
 *
 * [requestStartup] is the cold-start door and holds the once-per-process gate
 * inside itself, so every caller states its intent and the gate cannot be
 * bypassed or forgotten by one of them. [request] is the owner asking for the
 * tour by hand; the hidden flag does not stand in its way — the App Settings
 * button works whether or not startup is switched off.
 */
object TutorialHolder {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** True from the first startup request until the process dies. */
    var shownThisProcess = false
        private set

    fun requestStartup() {
        if (shownThisProcess) return
        shownThisProcess = true
        _visible.value = true
    }

    fun request() {
        _visible.value = true
    }

    fun close() {
        _visible.value = false
    }

    /** Back to a never-run process. For the unit tests, which own the object's history. */
    internal fun resetForTest() {
        shownThisProcess = false
        _visible.value = false
    }
}
