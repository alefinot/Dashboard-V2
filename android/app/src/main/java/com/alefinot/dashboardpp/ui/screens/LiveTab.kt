package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.map.LiveRouteMap
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
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
 * recorder pill be pressed and a swipe across the trip ribbon page the tabs. The rail over
 * the open middle of the map - the eye that puts both shelves away, and the two zoom steps
 * that travel ([LiveHud]'s [HudRail]) - hands its bounds to the same list, for the same
 * reason, and it is the one glass that stays when the shelves are gone.
 */
@Composable
fun LiveTab(active: Boolean, actions: TripRecordingActions) {
    var state by remember { mutableStateOf(TripRuntime.state.value) }

    // One history for the page, kept across recompositions and filled by the collector
    // below, not by a timer of its own: the flow is already the unit's one-frame-a-second
    // clock, and a second timer would be a second opinion about when a sample happened.
    val trends = remember { LiveTrends() }

    LaunchedEffect(active) {
        if (active) {
            TripRuntime.state.collect { next ->
                state = next
                val frame = next.snapshot
                if (frame != null) {
                    trends.sample(
                        android.os.SystemClock.elapsedRealtime(),
                        next.deviceKey,
                        frame,
                    )
                }
            }
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
    // the recorder.
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

    val snapshot = state.snapshot
    val route = state.liveRoute
    val trail = state.liveTrail
    // A map with no position has nothing to put a basemap under: 0,0 is a place in the
    // Gulf of Guinea, not "where the car is", and the old card held its 150 dp with a
    // sentence for exactly that reason. Here the sentence holds the whole page, so the
    // map that arrives at the first fix does not shift anything when it does.
    val hasPosition = trail.isNotEmpty() || route.hasTrack

    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
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
        HudEnter(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = LiveShelfInset)) {
                if (hudVisible) {
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
                // The open map: everything the shelves do not cover. The rail floats in it,
                // on the right and midway down, because that is where a thumb finds it
                // without standing over the car - and because a control that lives in the
                // map's slot has to be handed the touch by the map, so it reports its bounds
                // to the same list the shelves do. With the shelves put away this row is the
                // whole page, so the rail stays exactly where the thumb left it.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    if (hasPosition) {
                        HudRail(
                            hudVisible = hudVisible,
                            onToggleHud = { hudVisible = !hudVisible },
                            onZoomIn = { zoomIn++ },
                            onZoomOut = { zoomOut++ },
                            modifier = Modifier.onGloballyPositioned { layout ->
                                val next = layout.boundsInRoot()
                                if (hudRail != next) hudRail = next
                            },
                        )
                    }
                }
                if (hudVisible && snapshot != null) {
                    BottomShelf(
                        s = snapshot,
                        trends = trends,
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
}

/**
 * The page before its first position: the map's slot, held at full size.
 *
 * Two different waits, said differently because they are different facts. With no frame
 * at all the page is waiting for the dashboard the tab bar already names; with a frame in
 * hand and no coordinate in it, the unit is talking and the receiver is the thing that has
 * not settled. The second sentence is the one that keeps the map honest: the firmware
 * sends -1/0 rather than inventing a position, and a compass rose over the Gulf of Guinea
 * is the picture this page refuses to draw.
 */
@Composable
private fun WaitingForPosition(hasFrame: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (hasFrame) "WAITING FOR GNSS FIX" else "Waiting for the dashboard…",
                style = HudeSectionCaps(),
                color = if (hasFrame) HudColors.Amber else HudColors.TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hasFrame) {
                    "The map opens at the first position."
                } else {
                    "The live view follows the dashboard's own one-a-second stream."
                },
                style = if (hasFrame) HudeCfgNote() else HudeCfgHelp(),
                color = HudColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/** The page's inset from the window edge: 10 dp across, 6 dp above and below. */
private val LiveShelfInset = 10.dp
private val LiveEdge = 6.dp
