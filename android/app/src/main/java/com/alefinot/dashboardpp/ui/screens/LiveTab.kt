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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import com.alefinot.dashboardpp.trips.AccelRun
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.map.LiveRouteMap
import com.alefinot.dashboardpp.ui.map.MapCredit
import com.alefinot.dashboardpp.ui.map.MapStylePref
import com.alefinot.dashboardpp.ui.map.MapStyles
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.hudCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * the eye that puts both shelves away, the two zoom steps that travel, and the mark that lays a
 * different ground under the map ([LiveHud]'s [HudRail]) - hands its bounds to the same list,
 * for the same reason, and it is the one
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

    // The 0-50 whose result is on screen, after a tap on its yellow line (2026-11-22).
    //
    // It lives here, over the map, and not in a shelf: the line is the question and this is its
    // answer, and an answer about a place belongs where the place is. It is display-only and
    // deliberately NOT in the refusal list — a swipe that starts on it moves the map, exactly as
    // one that starts on the ground-name chip above does; there is nothing to press.
    //
    // Six seconds, then it goes on its own: about as long as the ground it belongs to stays on
    // screen while the camera follows anyway. The next tap takes it away sooner (or replaces it
    // with the run that tap landed on), and a result the owner wants again is a line they can tap
    // again. It is not remembered between pages or drives: it answers a question just asked.
    var shownRun by remember { mutableStateOf<AccelRun?>(null) }
    // The card's words, which outlive the flag that shows them by exactly the fade's length: the
    // frame that is fading out still has to have something to say. The same pair of variables the
    // ground-name chip below is (`groundName`, `groundNamed`), for the same reason — and written
    // only where a run arrives, so the card's first frame already has its number in it.
    var runCard by remember { mutableStateOf<AccelRun?>(null) }
    LaunchedEffect(shownRun) {
        val run = shownRun ?: return@LaunchedEffect
        delay(RUN_RESULT_MS)
        if (shownRun === run) shownRun = null
    }

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
    // The run result card hangs under the top shelf for the same reason RE-CENTER sits above the
    // bottom one: the glass is painted over the map, so "clear of the shelf" is an inset only the
    // page can measure. With the glass away it keeps a 6 dp gap from the map's own edge.
    val runCardTop = if (hudVisible && topShelf.height > 0f) {
        with(density) { topShelf.height.toDp() } + LiveEdge + 8.dp
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

    // The rail's ground cycler (maintainer, 2026-11-22: "add a map icon in the live map that
    // allows the user to change map style in realtime"). One press lays the next ground from the
    // catalogue under the map, in place, with the car moving under it.
    //
    // It writes through the picker's door and no other: the same `trip_settings` row App
    // Settings' MAP GROUND writes, and the same `MapStylePref` mirror every map surface reads.
    // Both, not just the row — the shell's collector is what tells the *other* surfaces
    // eventually, and a DataStore round-trip is not something a driver should wait on to see the
    // tiles change under a finger. And the row is what makes the choice survive the drive: a
    // ground picked at a traffic light is the one the app wakes up on, and App Settings shows it.
    val groundSettings = remember { RecorderSettings(context) }
    val writes = rememberCoroutineScope()
    // The ground that the rail last laid down, and whether its name is on the page. The glyph
    // can say "ground", it cannot say "OpenTopoMap"; the name is not stored anywhere, because a
    // chip that outlived the press would be a claim about a map somebody else has since changed.
    var groundName by remember { mutableStateOf("") }
    var groundNamed by remember { mutableStateOf(false) }
    LaunchedEffect(groundName) {
        if (groundName.isEmpty()) return@LaunchedEffect
        delay(GROUND_NAME_MS)
        groundNamed = false
    }
    // The name's inset from the page edge: clear of the rail's own width, so it never sits on
    // top of the mark whose press wrote it. The rail is measured before any press can happen, so
    // the unmeasured case is only the frame before the map appears.
    val groundNameEnd = if (hudRail.width > 0f) {
        with(density) { hudRail.width.toDp() } + LiveShelfInset + 8.dp
    } else {
        LiveShelfInset
    }

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
                    // The drive's 0-50 runs, in the map's yellow, and the one whose number this
                    // page is holding. The map finds the tap; this page only decides what to say
                    // about it — a line again closes the card, open ground closes it too.
                    runs = state.accelRuns,
                    selectedRun = shownRun,
                    onRunTap = { hit ->
                        shownRun = when {
                            hit == null -> null
                            // The same line twice is the owner changing their mind about looking at
                            // it, which is the only way to close this card without tapping some
                            // other thing on the map.
                            hit.id == shownRun?.id -> null
                            else -> {
                                runCard = hit
                                hit
                            }
                        }
                    },
                )
            } else {
                WaitingForPosition(hasFrame = snapshot != null)
            }
            // The provider's credit, on the map's own ground. The wash that used to sit here
            // — a flat 18 % black plus a vignette to 55 %, painted over the whole slot — has
            // moved inside the map view as an overlay (ui/map/MapChrome.kt). Painted here it
            // dimmed the drive, its dots and the arrow exactly as much as the tiles under
            // them, because Compose can only paint over a platform view as one piece; and one
            // global pair of numbers could not suit a pale street map and aerial imagery at
            // the same time. Both now belong to the style, and sit under the vectors.
            //
            // It rides at the map's bottom-left corner, lifted by the same amount as the
            // RE-CENTER label at the other one, so both keep clear of the bottom shelf
            // without moving when the eye puts the glass away.
            if (hasPosition) {
                MapCredit(
                    MapStylePref.current,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = LiveShelfInset, bottom = recenterBottom),
                )
                // The rail's news, beside the rail: the ground that press just laid down. The
                // glyph can say "ground", it cannot say "OpenTopoMap" — and the press can happen
                // with the shelves hidden, so this is the only place the change is written out.
                //
                // It hangs in the map's own slot, under the shelves' stacking order, beside the
                // mark that wrote it, and it is deliberately NOT in the map's refusal list: it is
                // not a control, so a swipe that starts on it moves the map, exactly as one that
                // starts on the credit chip above does.
                //
                // It fades on the page's own reveal/fade timings, so it also stays a moment longer
                // than the timer alone: `groundName` outlives `groundNamed` and the fade-out frame
                // still has something to say.
                //
                // How to prove this chip exists from a bench, since it is a 2.4 s thing and this
                // page defeats the obvious probe: `uiautomator dump` waits for a window that never
                // goes idle here (the map moves the arrow every fix), so a dump costs 2-3 s and its
                // snapshot lands whenever it lands. A dump fired "450 ms" after a press is usually
                // reading a tree two seconds on, and reports nothing — which once looked like the
                // fade hiding the node from accessibility. It was not: the same burst of twelve
                // dumps after presses caught the name once on the faded build and exactly once, in
                // the same position of the sequence, on the build without any transition
                // (bench, 2026-11-22 — `text="Street Atlas" bounds="[724,1098][876,1127]"`). The
                // name is exposed on both; the only reliable probe is a burst across the window,
                // and the thing to read next to it is the rail's own `content-desc`, which names
                // the same ground on every press and never races the timer.
                AnimatedVisibility(
                    visible = groundNamed,
                    enter = if (HudMotion.enabled) {
                        fadeIn(tween(HudMotion.REVEAL_MS, easing = HudMotion.Standard))
                    } else {
                        EnterTransition.None
                    },
                    exit = if (HudMotion.enabled) {
                        fadeOut(tween(HudMotion.FADE_OUT_MS, easing = HudMotion.Standard))
                    } else {
                        ExitTransition.None
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = groundNameEnd),
                ) {
                    Box(Modifier.hudCard()) {
                        Text(
                            groundName,
                            style = HudeSectionCaps(),
                            color = HudColors.Cyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                // The run a tap just picked out, in the same ink as the line it names. It is the
                // map's answer, so it hangs in the map's slot: top-centre, under the shelf when the
                // glass is up, where it cannot be mistaken for one of the shelf's own figures and
                // cannot cover the line it is about either - that line is under the arrow's ground,
                // and the camera is on it.
                //
                // Not a control: no click, no refusal rect. It is a thing the owner just asked for,
                // on the ground it happened, and it goes away by itself.
                AnimatedVisibility(
                    visible = shownRun != null,
                    enter = if (HudMotion.enabled) {
                        fadeIn(tween(HudMotion.REVEAL_MS, easing = HudMotion.Standard))
                    } else {
                        EnterTransition.None
                    },
                    exit = if (HudMotion.enabled) {
                        fadeOut(tween(HudMotion.FADE_OUT_MS, easing = HudMotion.Standard))
                    } else {
                        ExitTransition.None
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = runCardTop, start = LiveShelfInset, end = LiveShelfInset),
                ) {
                    runCard?.let { RunResultCard(it) }
                }
            }
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
                groundLabel = MapStylePref.current.label,
                onCycleGround = {
                    val next = MapStyles.nextAfter(MapStylePref.current)
                    MapStylePref.set(next.id)
                    groundName = next.label
                    groundNamed = true
                    writes.launch { groundSettings.setMapStyle(next.id) }
                },
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
            if (hasFrame) "WAITING FOR GNSS FIX" else "Waiting for Dashboard++…",
            style = HudeSectionCaps(),
            color = if (hasFrame) HudColors.Amber else HudColors.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** The page's inset from the window edge: 10 dp across, 6 dp above and below. */
private val LiveShelfInset = 10.dp
private val LiveEdge = 6.dp

/**
 * How long a tapped run's result stays on the map: long enough to read at a glance while the
 * camera is still on the ground it happened across, short enough that it is gone before it becomes
 * a claim about where the car is now. A tap takes it away — or replaces it — sooner.
 */
private const val RUN_RESULT_MS = 6_000L

/**
 * How long the ground's name stays beside the rail after the press that laid it down: long
 * enough to read once at a glance while driving, short enough that it is not a second label
 * competing with the credit chip when the owner has stopped thinking about the map.
 */
private const val GROUND_NAME_MS = 2_400L
