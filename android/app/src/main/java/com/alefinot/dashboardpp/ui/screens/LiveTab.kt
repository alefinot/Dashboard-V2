package com.alefinot.dashboardpp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.map.LiveRouteMap
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import kotlin.math.hypot

/**
 * The Live page: a full-screen live map, with every reading in the app's glass over it.
 *
 * The page used to be a header and four cards in a scroll, with the drive's map inside the
 * third one (phone-layout plan §4a). It is a map now, at the maintainer's request: the
 * shape is the subject - where the car is, where it has been, where it is going - and the
 * figures are what a cockpit puts *around* the map rather than under it. Nothing the cards
 * drew is dropped ([LiveHud] carries all of it); what went is the 52 dp title, the four
 * card headers, the card gaps and the scroll, which together were most of a screen spent
 * on chrome.
 *
 * The map is not a thumbnail any more, either. It pans and zooms (it always could), a drag
 * takes the camera and stops the follow, and a tap gives the car back. It is mounted at
 * the first position rather than at the first *recorded* one: since the shell keeps a
 * display-only frame lane open whenever a dashboard is linked and the app is on screen
 * (2.0.7/179), the page is live before anybody presses START.
 *
 * The one piece of engineering this layout needs is the touch door. A Compose control
 * painted over an osmdroid `AndroidView` receives nothing - the platform view owns the
 * slot's touches at the ViewGroup level, and an interop filter never sees a consumed DOWN
 * (bench-proven, 2026-11-15). So the shelves hand the map their bounds and the map
 * refuses a DOWN inside them ([LiveRouteMap]'s `touchRefusals`), which is what lets the
 * recorder pill be pressed and a swipe across the trip ribbon page the tabs. The rail -
 * the eye that puts both shelves away, and the two zoom steps that travel ([LiveHud]'s
 * [HudRail]) - hands its bounds to the same list, for the same reason, and it is the one
 * glass that stays when the shelves slide off through the page edges. It is pinned to the
 * vertical middle of the whole page rather than to the space between the shelves, so the
 * eye it hides them with never moves (maintainer, 2026-11-19).
 */
@Composable
fun LiveTab(active: Boolean, actions: TripRecordingActions) {
    var state by remember { mutableStateOf(TripRuntime.state.value) }

    LaunchedEffect(active) {
        if (active) {
            TripRuntime.state.collect { next -> state = next }
        }
    }

    // The HUD shelves' rectangles, in root pixels, and the map's own root origin. The
    // refusal list the map wants is view-local, so the difference is all the conversion
    // there is - both surfaces live in the same root and the map fills its slot.
    var topShelf by remember { mutableStateOf(Rect.Zero) }
    var bottomShelf by remember { mutableStateOf(Rect.Zero) }
    var hudRail by remember { mutableStateOf(Rect.Zero) }
    var mapOrigin by remember { mutableStateOf(Offset.Zero) }

    // The zoom rail's two presses, as counts: the map only ever acts on the difference
    // since its last pass (LiveRouteMap.zoom), so nothing zooms when a card recomposes for
    // a gliding figure and the rail asked for nothing.
    var zoomIn by remember { mutableIntStateOf(0) }
    var zoomOut by remember { mutableIntStateOf(0) }

    // Whether the two glass shelves are on the page. The rail's eye is the only way to
    // change it, and it is deliberately not remembered (2026-11-19): the readings are the
    // page's reason, so a clean map is something the owner asks for on a particular drive,
    // not a preference the app wakes up in. Nothing is written to storage, and the trip
    // keeps recording while the glass is away - hiding the numbers hides the numbers, not
    // the recorder. The shelves take the news with a slide: each one leaves through the
    // page edge it is parked at, and comes back the same way (maintainer, 2026-11-19).
    var hudVisible by remember { mutableStateOf(true) }

    val density = LocalDensity.current
    val refusals = remember(topShelf, bottomShelf, hudRail, mapOrigin, hudVisible) {
        // A shelf that is not on the page must not keep refusing touches with the rectangle
        // it had the last time it was: the state holds its last measured bounds, and the map
        // would go on declining a DOWN over ground the shelf does not cover.
        val onScreen = listOf(hudRail) + listOfNotNull(
            topShelf.takeIf { hudVisible },
            bottomShelf.takeIf { hudVisible },
        )
        onScreen.filter { it.width > 0f && it.height > 0f }
            .map {
                Rect(
                    it.left - mapOrigin.x,
                    it.top - mapOrigin.y,
                    it.right - mapOrigin.x,
                    it.bottom - mapOrigin.y,
                )
            }
    }
    // The RE-CENTER label is drawn by the map, so "above the bottom shelf" is an inset the
    // page has to hand it: the shelf's measured height, the page's own bottom inset, and
    // the gap the label keeps from the glass. Before the shelf is measured - or once it is
    // put away - it sits at the map's own default, because there is nothing under it.
    val recenterBottom = if (hudVisible && bottomShelf.height > 0f) {
        with(density) { bottomShelf.height.toDp() } + LiveEdge + 8.dp
    } else {
        6.dp
    }

    // The two power bars are drawn on the unit's scales, and the unit keeps those scales in
    // its own flash (TEMP_BAR_MIN/MAX, TEMP_WARN_LOW/YEL/RED and the ramp's three inks
    // COLOR_TEMP_NORM/WARN/CRIT - the same settings its sidebar paints itself with). They are
    // asked for once per connection, as soon as the shell has named the dashboard, and held
    // for as long as that dashboard is the one on screen: the bars draw the firmware's
    // compiled defaults until the answer lands, and a unit that never answers is not a reason
    // to stop drawing them (maintainer, 2026-11-20: "change the scale dynamically based on the
    // scales in the ESP settings"). A forgotten unit goes back to the defaults with everything
    // else, so a new bike never inherits the last one's bar.
    val context = LocalContext.current
    val api = remember { BleApi(BleRuntime.link(context)) }
    var scales by remember { mutableStateOf(PowerScales.Default) }
    LaunchedEffect(state.deviceKey) {
        if (state.deviceKey == null) {
            scales = PowerScales.Default
            return@LaunchedEffect
        }
        // 10 s, not the link's own 30: this read sits in the same queue as the display lane's
        // snapshots, so a unit that stops answering must give the frame lane back quickly.
        val mirror = runCatching { api.config(timeoutMs = 10_000) }.getOrNull()
        if (mirror != null) scales = PowerScales.from(mirror.values)
    }

    // The readings freeze when the drive ends (maintainer, 2026-11-20): STOP seals the file
    // and the figures stop with it, so the page keeps the last frame of that drive on screen
    // instead of following the display lane, which goes on hearing the unit. START lets go -
    // the new drive's first frame takes the place of the frozen one. The map is not held:
    // where the car is belongs to the map, not to the file, and a finished drive's map has
    // kept moving since 2.0.7/179. A forgotten unit clears the hold with everything else,
    // which is why the device key is part of the key: a null key is a blank page, not a
    // still of somebody else's ride.
    val live = state.snapshot
    var shown by remember { mutableStateOf<Snapshot?>(null) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(state.deviceKey, state.recording, live) {
        when {
            state.deviceKey == null -> {
                shown = null
                holding = false
            }
            state.recording -> {
                holding = true
                shown = live ?: shown
            }
            holding -> Unit
            else -> shown = live
        }
    }
    val snapshot = shown
    val route = state.liveRoute
    val trail = state.liveTrail
    // A map with no position has nothing to put a basemap under: 0,0 is a place in the
    // Gulf of Guinea, not "where the car is", and the old card held its 150 dp with a
    // sentence for exactly that reason. Here the sentence holds the whole page, so the
    // map that arrives at the first fix does not shift anything when it does.
    val hasPosition = trail.isNotEmpty() || route.hasTrack

    // The page clips to itself. The shelves' slide is a graphicsLayer translation, and
    // nothing between them and the window clipped at the page edge - so on the way out the
    // glass was painted over the tab bar below instead of under it (maintainer, 2026-11-19:
    // "fix the bottom ui panel animation, it should go under the 5 icons menu bar"). With
    // the page edge as the clip line, the bottom shelf slides down and vanishes behind the
    // bar's hairline and rises back out from under it; the top one does the same at the
    // status-bar edge. Scoped here, so nothing outside the Live page can lose an overdraw.
    Box(Modifier.fillMaxSize().clipToBounds().background(HudColors.Bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val next = it.boundsInRoot().topLeft
                    if (mapOrigin != next) mapOrigin = next
                },
        ) {
            if (hasPosition) {
                LiveRouteMap(
                    route = route,
                    trail = trail,
                    // Only the follow map grows its line under the arrow; a finished drive's
                    // shape is not extended by the display lane. `!route.closed` matters in the
                    // second after a START: the finished drive's shape is still what is on
                    // screen while the new one waits for its first fix.
                    writing = state.recording && !state.paused && !route.closed,
                    modifier = Modifier.fillMaxSize(),
                    touchRefusals = refusals,
                    recenterBottom = recenterBottom,
                    zoomInTicks = zoomIn,
                    zoomOutTicks = zoomOut,
                )
            } else {
                WaitingForPosition(hasFrame = snapshot != null)
            }
            // The cockpit wash: a flat tint and a vignette over the tiles, so the map reads
            // as the ground the glass floats on rather than as a second bright surface
            // competing with the figures. Deliberately cheap - two full-screen rects, no
            // shader, no per-frame work - and deliberately static: a wash that moved with
            // the camera would be a second thing to keep in step with the glide.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(Color.Black.copy(alpha = 0.18f))
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            center = center,
                            radius = hypot(size.width, size.height) / 2f,
                        ),
                    )
                },
            )
        }

        // The HUD, one entrance for the glass rather than a stagger per block: the cards
        // that used to arrive one step apart are one shelf at the top and one at the
        // bottom, and two halves of one overlay fading in sequence would read as a stutter.
        // Each shelf answers the eye with a slide: it leaves through the page edge it is
        // parked against and comes back through the same one (maintainer, 2026-11-19).
        HudEnter(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = LiveShelfInset)) {
                AnimatedVisibility(
                    visible = hudVisible,
                    enter = shelfEnter(top = true),
                    exit = shelfExit(top = true),
                ) {
                    TopShelf(
                        state = state,
                        active = active,
                        actions = actions,
                        s = snapshot,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LiveEdge)
                            .onGloballyPositioned { layout ->
                                val next = layout.boundsInRoot()
                                if (topShelf != next) topShelf = next
                            },
                    )
                }
                // The open map: everything the shelves do not cover.
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = hudVisible && snapshot != null,
                    enter = shelfEnter(top = false),
                    exit = shelfExit(top = false),
                ) {
                    snapshot?.let { snap ->
                        BottomShelf(
                            s = snap,
                            scales = scales,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = LiveEdge)
                                .onGloballyPositioned { layout ->
                                    val next = layout.boundsInRoot()
                                    if (bottomShelf != next) bottomShelf = next
                                },
                        )
                    }
                }
            }
        }

        // The rail is pinned to the page, not to the space between the shelves: it floats
        // at the vertical middle of the whole page, the one spot it cannot move when the
        // eye puts the glass away - a control that travels when the owner hides the
        // readings makes them hunt for their own handle (maintainer, 2026-11-19). It is
        // still glass in the map's slot, so it reports its bounds to the same refusal list
        // the shelves do.
        if (hasPosition) {
            HudRail(
                hudVisible = hudVisible,
                onToggleHud = { hudVisible = !hudVisible },
                onZoomIn = { zoomIn++ },
                onZoomOut = { zoomOut++ },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = LiveShelfInset)
                    .onGloballyPositioned { layout ->
                        val next = layout.boundsInRoot()
                        if (hudRail != next) hudRail = next
                    },
            )
        }
    }
}

/**
 * The shelves' entrance: a slide in from the page edge the shelf is parked against - the
 * top shelf from the top edge, the bottom shelf from the bottom one - under the page's own
 * entrance timing. One motion for the pair, so the eye's press reads as the glass going
 * home rather than as a switch flipped (maintainer, 2026-11-19). Motion off means no
 * transition at all, the same promise [HudEnter] makes.
 */
@Composable
private fun shelfEnter(top: Boolean): EnterTransition =
    if (HudMotion.enabled) {
        val slideSpec = tween<IntOffset>(HudMotion.ENTRANCE_MS, easing = HudMotion.Standard)
        fadeIn(tween(HudMotion.ENTRANCE_MS, easing = HudMotion.Standard)) +
            slideInVertically(slideSpec) { if (top) -it else it }
    } else {
        EnterTransition.None
    }

/** The way out: the same travel reversed - the glass slides back through its page edge. */
@Composable
private fun shelfExit(top: Boolean): ExitTransition =
    if (HudMotion.enabled) {
        val slideSpec = tween<IntOffset>(HudMotion.ENTRANCE_MS, easing = HudMotion.Standard)
        fadeOut(tween(HudMotion.ENTRANCE_MS, easing = HudMotion.Standard)) +
            slideOutVertically(slideSpec) { if (top) -it else it }
    } else {
        ExitTransition.None
    }

/**
 * The page before its first position: the map's slot, held at full size.
 *
 * Two different waits, said differently because they are different facts. With no frame
 * at all the page is waiting for the dashboard the tab bar already names; with a frame in
 * hand and no coordinate in it, the unit is talking and the receiver is the thing that has
 * not settled. A compass rose over the Gulf of Guinea is the picture this page refuses to
 * draw — the firmware sends -1/0 rather than inventing a position.
 */
@Composable
private fun WaitingForPosition(hasFrame: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (hasFrame) "WAITING FOR GNSS FIX" else "Waiting for the dashboard…",
            style = HudeSectionCaps(),
            color = if (hasFrame) HudColors.Amber else HudColors.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** The page's inset from the window edge: 10 dp across, 6 dp above and below. */
private val LiveShelfInset = 10.dp
private val LiveEdge = 6.dp
