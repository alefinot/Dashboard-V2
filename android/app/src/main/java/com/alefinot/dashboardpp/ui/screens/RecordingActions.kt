package com.alefinot.dashboardpp.ui.screens

/**
 * What the two pages can ask of the recorder. Built once in the shell so the Trips page and the
 * Live page cannot disagree about what a press does, and so neither page has to know how the
 * service is started (it needs a suspend lookup of whose drives these are).
 *
 * The actions are no longer a row of buttons: since 2.0.7/178 the Live page's state pill and
 * the Trips page's recording card *are* the controls, and they read their gesture from
 * `trips/RecordingControls.kt` - a press means `tap()`, a three-second press means stop.
 *
 * [blocked] is why the control is not offered, in the words the page will show. It is built
 * where the facts are - the link and the unit's firmware - rather than guessed from nulls.
 */
class TripRecordingActions(
    val canRecord: Boolean,
    val blocked: String,
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onStop: () -> Unit,
)
