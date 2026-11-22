package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.update.AppUpdate
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.viewmodel.TripsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The app's one screen, in every state.
 *
 * This used to be a `when` over the connection state, with six branches and three
 * screens of its own: a splash, a full-page scan ring, and the dead ends. Each of them
 * covered the whole window to say one sentence, and what they hid was the part of the app
 * that never needed a dashboard — the trips that are already recorded, and the settings
 * that belong to the phone.
 *
 * There is one screen now, and it opens on the vehicle page: the place where a
 * connection is made, unmade and named. [ShellRouting] decides what the shell *says* for
 * a state; this file only decides that it is always the same shell.
 */
@Composable
fun DashboardRoot(activity: android.app.Activity, vm: ConnectionViewModel) {
    // One TripsViewModel for the whole session: it owns the file index and the
    // summary cache, and outlives tab switches (trip recording plan §5).
    val trips = remember { TripsViewModel(activity.application) }
    LaunchedEffect(Unit) { vm.boot() }
    // The silent startup check for a newer released APK (`update/AppUpdate.kt`). Once per
    // process is what the object's freshness gate gives, and the answer is only ever a bar
    // dot and a card line — nothing on this path installs anything by itself.
    LaunchedEffect(Unit) { AppUpdate.check(activity.application) }
    // The view model is remembered inside `setContent`, so the composable is the only thing
    // in the app that knows whether the screen is in front of somebody. That fact is what
    // makes "no reconnects with the screen off" enforceable: `ON_START` / `ON_STOP` bound
    // the window in which the quiet restart of a given-up ladder may put a dial on the air,
    // and coming back to `ON_START` with a give-up in effect dials straight away instead of
    // waiting out another tick.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onForegroundChanged(true)
                Lifecycle.Event.ON_STOP -> vm.onForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val s = vm.uiState.value
    // The start tour. One read of `tutorial_hidden` per composition root —
    // DataStore has warmed by the time this runs (MainActivity.warmPreferences
    // opens the same store at launch) — and then the once-per-process gate
    // inside TutorialHolder decides. Coming back from recents or rotating
    // reaches this effect again; `shownThisProcess` is what makes those not
    // look like a startup.
    val settings = remember { RecorderSettings(activity.application) }
    LaunchedEffect(Unit) {
        val hidden = withContext(Dispatchers.IO) { settings.tutorialHidden.first() }
        if (TutorialScript.shouldShowAtStartup(hidden, TutorialHolder.shownThisProcess)) {
            TutorialHolder.requestStartup()
        }
    }
    val tourVisible by TutorialHolder.visible.collectAsStateWithLifecycle()
    // Keep content inside the safe area: with targetSdk 35 edge-to-edge is
    // enforced, so without these insets the HUD chrome draws behind the
    // status bar / nav bar.
    //
    // This Box is the one place that pads for the system bars. A surface inside it
    // must not pad for them again: the bottom bar used to add
    // `windowInsetsPadding(navigationBars)` on top of the inset already taken here,
    // which is invisible on a phone in gesture navigation (that inset is ~0) and
    // costs a real 48 dp strip under the tab bar on a device with three buttons.
    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val view = ShellRouting.view(s)
        AppShell(
            conn = view.conn,
            reason = view.reason,
            connection = vm,
            trips = trips,
        )
        // Last child of the root Box, so the whole app is the thing it dims —
        // same modality rule as `HudConfirmDialog`, which is also why the tour
        // can point at the bar behind its card.
        if (tourVisible) {
            TutorialOverlay(activity.application)
        }
    }
}
