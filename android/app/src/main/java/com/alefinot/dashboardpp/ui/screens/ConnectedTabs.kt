package com.alefinot.dashboardpp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.service.TripLogService
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.TripsEnabled
import com.alefinot.dashboardpp.update.AppUpdate
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudGlyphAction
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.LocalEnterActive
import com.alefinot.dashboardpp.ui.components.PageFade
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.screens.settings.BleConfigGateway
import com.alefinot.dashboardpp.ui.screens.settings.ConfigSchema
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore
import com.alefinot.dashboardpp.ui.screens.settings.loadConfigSchema
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.viewmodel.TripRow
import com.alefinot.dashboardpp.viewmodel.TripsViewModel
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.hudGlass
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The app's only screen once it is up: the five-slot bar and the pages it swipes
 * between. There is no other route to a settings screen, no overlay, no back handler,
 * and no second version of the bar; the trip detail is a pushed route, the only one.
 *
 * It opens on the vehicle page, and it is the same screen whether or not a dashboard is
 * connected. With no device, `conn` is null: the bar keeps all five slots, Trips and App
 * Settings work in full, Live and the ESP slot answer with a cover that quotes the reason
 * the view model gave, and the vehicle page is where a connection is actually made. A
 * lost connection is not an exit from the app - it is a page with nothing to show right
 * now. See [ShellRouting] for how a state becomes a line on that page and [ShellRules]
 * for what the bar and the recorder do about it.
 *
 * The ESP slot is a page like the others now: the unit's own configuration, drawn in
 * Compose and read over Bluetooth (`ConfigStore` here, `BleRuntime` for the link). The
 * WebView it replaced, the holder that used to keep its page per connection, and the
 * gesture mirror that fed touches to that View are gone from the app, along with the
 * HTTP client and the Wi-Fi discovery that used to carry a phone to a unit's page.
 */
@Composable
fun AppShell(
    conn: ConnectionUiState.Connected?,
    reason: String?,
    connection: ConnectionViewModel,
    trips: TripsViewModel,
) {
    val context = LocalContext.current
    val connected = conn != null
    val address = conn?.address

    // The unit's configuration lives on the unit, and a V2 unit reaches this phone over
    // Bluetooth and nothing else. The link is the process-wide one (`BleRuntime`),
    // because a GATT connection belongs to the phone rather than to a page: swiping away
    // from the settings tab must not drop a pairing the user spent a minute setting up.
    // The store hangs off that link and lives as long as this shell, so a write in flight
    // outlives a tab switch. The schema is read off the UI thread — 198 rows parsed on
    // the frame that carries a tab switch is the same mistake this file has already been
    // taught twice (see the pager's beat below).
    val link = remember { BleRuntime.link(context) }
    val storeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(storeScope) { onDispose { storeScope.cancel() } }
    var schema by remember { mutableStateOf<ConfigSchema?>(null) }
    LaunchedEffect(Unit) {
        schema = withContext(Dispatchers.Default) { loadConfigSchema(context) }
    }
    val store = remember(link, schema) {
        schema?.let { ConfigStore(BleConfigGateway(BleApi(link), link), it, storeScope) }
    }

    // Android's permission is the first reason a dashboard can be out of range, and it is
    // the only one this phone decides rather than the dashboard's switch. It is asked for
    // here, once, at the moment the shell comes up: the alternative (which is what this app
    // used to do with Wi-Fi permissions) is to reach for the radio, fail, and explain the
    // failure afterwards. The launcher lives at the shell rather than on the page that
    // asks, because scan and connect both need it and a page that asks per action is a page
    // that asks twice. Nothing is drawn for it - the grant or the refusal changes one
    // boolean, and the page that was already on screen says what it means.
    val askRadio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        connection.notePermissionsResult(granted)
        if (granted.values.all { it }) connection.retryAfterRadio()
    }
    LaunchedEffect(Unit) {
        val wanted = connection.blePermissionRequest()
        if (wanted.isNotEmpty()) askRadio.launch(wanted.toTypedArray())
    }

    // A phone that has never met a dashboard gets different words on the covers: there is
    // no "waiting for X" to say, and the old first-run instructions ("open the access
    // point name, find the IP printed on the label") have no subject left.
    val firstRun = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        firstRun.value = try {
            connection.vehicleCount() == 0
        } catch (e: Exception) {
            false
        }
    }

    var openTrip by remember { mutableStateOf<TripRow?>(null) }

    // The editor is state in the shell, not a route, for the same reason the manual-address
    // panel is: closing it returns the user to the garage exactly where they were. The draft
    // is the record being edited, `editorIsNew` says whether it has a row yet (a card adopted
    // from an unclaimed unit has none, and its form has no DELETE), and `editorRefusal` holds
    // the reason a SAVE was turned down so the form can show it instead of vanishing.
    var editing by remember { mutableStateOf<Vehicle?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }
    var editorRefusal by remember { mutableStateOf<String?>(null) }

    // Firmware with no snapshot endpoint has no trip store; with no device there is no
    // version to check, and the trip files are the phone's own, so the trips pages
    // stay pages (`forFirmware(null)` is true).
    val tripsAvailable = TripsEnabled.forFirmware(conn?.version)

    // The user's recording switch, collected here so starting and stopping the
    // service follows it immediately (the service checks it again on its side).
    val settings = remember { RecorderSettings(context) }
    val recordingEnabled by settings.recordingEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    // The motion switch, collected once here (this composable owns the bar, the pill
    // and both lists) and mirrored into the theme, so the pill, the entrances and the
    // list stagger all read one flag and change together.
    val motionEnabled by settings.motionEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(motionEnabled) { HudMotion.setPrefEnabled(motionEnabled) }

    val pager = rememberPagerState(initialPage = BarLayout.homePage, pageCount = { BarLayout.pages.size })
    // The settled page. The bar's selection and which screen is `active` both follow
    // this one value.
    val page = BarLayout.pages.getOrElse(pager.currentPage) { BarLayout.home }

    // The "a newer release is out" mark on the Settings slot, and the moment it is
    // forgiven. The flag belongs to the process-wide updater (`update/AppUpdate.kt`), so the
    // bar never asks GitHub anything of its own; it clears when the *settled* page is the one
    // that draws the update card — every page is composed from startup, so "the card was
    // composed" proves nobody saw it, while "the page settled on it" proves somebody did.
    val updateDot by AppUpdate.showDot.collectAsStateWithLifecycle()
    LaunchedEffect(page) {
        if (page == AppTab.SETTINGS) AppUpdate.markSeen()
    }

    // The page area's alpha, and the bar sheet's position when the pager itself is not
    // the thing moving. Both are Animatables: the first is read inside a graphics
    // layer and the second inside PillSheet, so neither recomposes the shell while it
    // moves.
    val pageFade = remember { Animatable(1f) }
    val cutPill = remember { Animatable(0f) }
    var cutPillActive by remember { mutableStateOf(false) }

    // Where the user asked to be. A tap writes the target, and one effect carries it
    // out; keying that effect on the target means a second tap interrupts the first at
    // whatever point it had reached instead of queueing behind it.
    //
    // One page away is the same slide a swipe makes. Anything further fades through:
    // the page area leaves first, because the pager's own `animateScrollToPage`
    // pre-jumps to within three pages of the target before it animates (so a five-slot
    // bar used to flash a page nobody asked for and then slide three at once), and
    // three screenfuls in 240 ms is a blur, not motion. With nothing painting, the cut
    // itself is invisible; the arriving page's entrance is armed by the viewport flag
    // on that same state change, and carries the content back. The bar's sheet cannot
    // follow the pager through a cut, so it travels on its own clock at the same time,
    // starting on the tap's first frame rather than after the page arrives.
    var destination by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(destination) {
        val target = destination ?: return@LaunchedEffect
        val from = pager.currentPage
        if (target == from) {
            // A tap on the page already on screen ends a transition that was
            // interrupted between leaving and coming back.
            if (cutPillActive) {
                cutPill.animateTo(
                    target.toFloat(),
                    tween(HudMotion.TAB_MS, easing = HudMotion.Standard),
                )
                cutPillActive = false
            }
            if (pageFade.value < 1f) {
                pageFade.animateTo(1f, tween(HudMotion.SHORT_MS, easing = HudMotion.Standard))
            }
            destination = null
            return@LaunchedEffect
        }
        if (!HudMotion.enabled) {
            // The switch means no transition at all, not a shorter one.
            pager.scrollToPage(target)
            pageFade.snapTo(1f)
            cutPillActive = false
            destination = null
            return@LaunchedEffect
        }
        if (abs(target - from) <= 1) {
            coroutineScope {
                // An interrupted cut may have left the page area part-way out; bring
                // it back while the slide runs instead of jumping to full brightness.
                if (pageFade.value < 1f) {
                    launch {
                        pageFade.animateTo(
                            1f,
                            tween(HudMotion.SHORT_MS, easing = HudMotion.Standard),
                        )
                    }
                }
                // A sheet still travelling from an interrupted cut reaches the same
                // target on the same clock; otherwise it follows the pager as always.
                if (cutPillActive) {
                    launch {
                        cutPill.animateTo(
                            target.toFloat(),
                            tween(HudMotion.TAB_MS, easing = HudMotion.Standard),
                        )
                    }
                }
                pager.animateScrollToPage(
                    target,
                    animationSpec = tween(HudMotion.TAB_MS, easing = HudMotion.Standard),
                )
            }
            if (cutPillActive) cutPillActive = false
            destination = null
            return@LaunchedEffect
        }
        // A cut too far for the pager to show the way.
        if (!cutPillActive) {
            cutPill.snapTo(
                (pager.currentPage + pager.currentPageOffsetFraction)
                    .coerceIn(0f, (BarLayout.pages.size - 1).toFloat()),
            )
            cutPillActive = true
        }
        coroutineScope {
            launch {
                cutPill.animateTo(
                    target.toFloat(),
                    tween(HudMotion.TAB_MS, easing = HudMotion.Standard),
                )
            }
            pageFade.animateTo(0f, tween(HudMotion.FADE_OUT_MS, easing = HudMotion.Ease))
            // Nothing is painting right now. The arriving page re-arms its entrance
            // on the same state change, so restoring the layer shows its first frame
            // at zero alpha, not a settled page flashing in.
            pager.scrollToPage(target)
            pageFade.snapTo(1f)
        }
        cutPillActive = false
        destination = null
    }

    val goToPage: (Int) -> Unit = { index -> if (index >= 0) destination = index }

    // A cover's one action: go to the page that can do something about it. It moves
    // the pager exactly the way a bar tap does, because the cover is not a screen to
    // leave, it is this screen with a different page in front of you.
    val openGarage: () -> Unit = { goToPage(BarLayout.pageOf(AppTab.VEHICLES)) }
    // Which vehicle a cover is waiting for. The garage's list is collected here too, not
    // only on the vehicle page, because a cover has to name the vehicle it cannot reach -
    // and the store is DataStore-backed, so there is no synchronous read to borrow. The
    // collection is keyed to the view model and the shell only recomposes when a card is
    // added or edited, which is rare, cheap (the pager keeps its state), and always
    // something the user did on the vehicle page a moment earlier.
    val garageCards = remember(connection) { connection.vehicleCards() }
    val garageList by garageCards.collectAsStateWithLifecycle(initialValue = emptyList())
    val coverVehicleName = garageList.firstOrNull { it.id == connection.selectedVehicleId.value }?.name

    // Recording needs a device to sample, firmware that answers the snapshot RPC, and the
    // user's switch - all three, and the first of them is why the service never runs
    // in the offline shell (see ShellRules.recorderWants). It also needs to know whose
    // drive this is, which is what the recorder writes as the file's `#vehicle=` line:
    // a telemetry frame says nothing about who sent it, so the app has to say it.
    LaunchedEffect(address, conn?.vehicleId, tripsAvailable, recordingEnabled) {
        // The recorder's owner is resolved once, here, and handed over whole: the service
        // cannot ask a pushed frame who sent it, and a trip file outlives the link that
        // produced it. `owner` is null exactly when nothing is linked, so one null check
        // carries both halves of that.
        val owner = connection.recorderTarget()
        if (owner != null && ShellRules.recorderWants(true, tripsAvailable, recordingEnabled)) {
            TripLogService.start(
                context, owner.deviceKey, owner.vehicleId, owner.vehicleName, owner.eff,
            )
        } else {
            TripLogService.stop(context)
            // A page the firmware cannot serve is snapped out of, not animated to: the
            // user did not ask to go anywhere. With no device the trips page is the
            // phone's own, so the offline shell leaves the user on it.
            if (ShellRules.pageUnserved(tripsAvailable, connected = address != null)) {
                val now = BarLayout.pages.getOrElse(pager.currentPage) { BarLayout.home }
                if (now == AppTab.TRIPS) pager.scrollToPage(BarLayout.pageOf(AppTab.LIVE))
            }
        }
    }

    // Two different questions, and collapsing them is what made the app sluggish.
    // "Is the page inside the pager's viewport" decides whether it may paint and take
    // touches; a swipe uncovers it long before the pager commits, so waiting for the
    // settled page shows a half-drawn page sliding in. "Is it the settled page" decides
    // who may put requests on the one link and which page's own 1 s tick runs - that one
    // has to wait for the settle, or two pages would be reading the same GATT connection
    // at once (the settings page asks for its mirror only while it is the page on screen,
    // and the performance monitor reads diagnostics the same way). The viewport answer
    // is derived: recomputed as the pager moves, but it only changes the moment it flips,
    // so a swipe does not recompose this screen.
    // The bar: the selected slot's glass sheet is measured from the icons, and it
    // follows the pager rather than a separate selection - which is what makes it
    // slide during a swipe instead of only after one.
    // The sheet's composable reads this map and the pager; the screen body does not,
    // so a swipe recomposes the sheet and nothing else.
    val bounds = remember { mutableStateMapOf<AppTab, Rect>() }

    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The leaving half of a distant tab change; see `destination`
                    // above. Read in the layer so the fade invalidates drawing only.
                    .graphicsLayer { alpha = pageFade.value },
            ) {
                // Nothing is retained underneath the pages any more. The ESP slot used
                // to be a hole in them: a WebView kept composed behind the pager and
                // uncovered by a swipe, because letting Compose remove that View cost
                // the user 0.6-1.1 s while Chromium rebuilt its rendering surface. The
                // slot is a page now, so there is no surface to keep warm, no gesture
                // stream to mirror onto a View under the pager, and no opaque page to
                // hide a second copy of the same settings.
                // Pages beyond the visible one are composed as well - in fact all of
                // them, so no tab is ever built under the user's finger: that cold
                // build was the 100 ms-plus stall at the start of a tab change, and
                // it landed exactly when the pager was already moving. Composing the
                // four off-screen pages in one frame made the first paint of the
                // shell 1.3 s slower, so they are still built one per frame after
                // the shell has drawn and animated in.
                //
                // It is only affordable because everything a page *does* is keyed to
                // the viewport flag computed below rather than to its composition: the
                // HTTP lane, the record chip's breath, the trips list's reveal and the
                // page's own drawing all wait for the page to be on screen, and the trip
                // list re-reads itself on arrival instead of on composition.
                //
                // And the building is timed so it does not land on the user: the pages
                // are composed a beat after the shell has drawn and animated in, one
                // page per frame. Pre-composing them all in the shell's first
                // composition measured 4.4 s to the bar against 3.1 s without it, and
                // that is the first thing the driver ever sees.
                var extraPages by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    delay(600)
                    while (extraPages < BarLayout.pages.size - 1) {
                        withFrameNanos { }
                        extraPages++
                    }
                }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = extraPages,
                ) { p ->
                    // Is this page on screen right now? Its own derived flag, read
                    // rather than computed in the body: the pager hands over a new
                    // offset every frame of a drag, and only the flip is worth
                    // reacting to. This is the same rule that says whether the web
                    // view may paint, so a page paints, is mirrored and animates in on
                    // one and the same event (see PagerFocus).
                    val inView by remember(pager, p) {
                        derivedStateOf {
                            PagerFocus.inViewport(
                                pager.currentPage,
                                pager.currentPageOffsetFraction,
                                p,
                            )
                        }
                    }
                    when (BarLayout.pages.getOrElse(p) { BarLayout.home }) {
                        // The app's home, and the only page whose job is to make the other
                        // pages possible. It holds every connect action the app has - the
                        // cards' CONNECT/DISCONNECT, the search, and the address box this
                        // shell raises over it - and the covers below have none of their own.
                        AppTab.VEHICLES -> NativePage(inView) {
                            VehicleScreen(
                                connection,
                                onEdit = { id ->
                                    editorRefusal = null
                                    editorIsNew = false
                                    connection.editDraft(id) { v -> editing = v }
                                },
                                onNameUnclaimed = {
                                    editorRefusal = null
                                    editorIsNew = true
                                    connection.adoptDraft { v -> editing = v }
                                },
                            )
                        }
                        AppTab.TRIPS -> NativePage(inView) { TripsTab(trips) { row -> openTrip = row } }
                        AppTab.LIVE -> NativePage(inView) {
                            if (conn == null) {
                                // The page stays, the live view does not: this is what
                                // "limited features, not a limited app" looks like on the
                                // page that needs a device most.
                                NotLinkedCover(
                                    title = "NO DASHBOARD IN RANGE",
                                    reason = reason,
                                    note = "Trips and App Settings work without a dashboard.",
                                    vehicleName = coverVehicleName,
                                    onOpenVehicles = openGarage,
                                    permissionProblem = connection.permissionMissing.value,
                                    firstRun = firstRun.value == true,
                                )
                            } else {
                                // One stream, one reader: the recording service follows the
                                // unit's frames whether this page is on screen or not, and
                                // the page renders what the service published rather than
                                // opening a second reader of the same link.
                                LiveTab(active = page == AppTab.LIVE)
                            }
                        }
                        AppTab.WEB -> NativePage(inView) {
                            // Block fade, like App settings: this is a stack of cards, and
                            // the page it sits beside fades its body in as one piece.
                            PageFade(Modifier.fillMaxSize()) {
                                val unit = store
                                when {
                                    unit == null && schema == null -> Text(
                                        "reading the settings schema from the app…",
                                        style = HudeFormLabel(),
                                        color = HudColors.TextDim,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                    unit == null -> Text(
                                        "Settings schema missing from the app package.",
                                        style = HudeFormLabel(),
                                        color = HudColors.Amber,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                    else -> DashboardSettingsScreen(
                                        timeNote = connection.timeNote.value,
                                        store = unit,
                                        schema = schema,
                                        link = link,
                                    )
                                }
                            }
                        }
                        AppTab.SETTINGS -> NativePage(inView) {
                            // A block fade rather than per-card stagger: this page is a
                            // stack of settings cards, and the page it mirrors fades its
                            // body in as one block too.
                            PageFade(Modifier.fillMaxSize()) {
                                SettingsScreen(
                                    vm = connection,
                                    unit = conn?.name ?: address,
                                    version = conn?.version,
                                    connected = connected,
                                    trips = trips,
                                    tripsAvailable = tripsAvailable,
                                )
                            }
                        }
                    }
                }
            }
            // One hairline above the bar, the way the page separates its header from
            // the content (`header::after` is a 2px cyan fade). It used to sit under
            // the strip at the top; the bar moved the separation to the bottom.
            Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
            BottomBar(
                selected = page,
                pager = pager,
                bounds = bounds,
                cutPill = cutPill,
                cutPillActive = cutPillActive,
                tripsAvailable = tripsAvailable,
                connected = connected,
                updateDot = updateDot,
                onBounds = { slot, r -> bounds[slot] = r },
                onSelect = { slot ->
                    val index = BarLayout.pageOf(slot)
                    if (index >= 0) goToPage(index)
                },
            )
        }

        editing?.let { draft ->
            VehicleEditorScreen(
                vm = connection,
                draft = draft,
                isNew = editorIsNew,
                refusal = editorRefusal,
                onSave = { typed, photoEdit ->
                    // The card and its staged photo decision land in one write, which is
                    // what makes CANCEL revert the picture too (vehicle editor fixes,
                    // 2026-11-10). A refusal keeps the form open with the reason in it;
                    // the edits and the framing are still on screen because the card
                    // they were fixing is the one thing the user must not lose.
                    connection.saveVehicleWithPhoto(typed, photoEdit) { refusal ->
                        editorRefusal = refusal
                        if (refusal == null) {
                            editing = null
                            editorRefusal = null
                        }
                    }
                },
                onDismiss = {
                    editing = null
                    editorRefusal = null
                },
                onDelete = {
                    connection.deleteVehicle(draft.id) { refusal ->
                        // A refused delete means the card is still in the garage file, so the
                        // editor stays open with the reason in it instead of closing on a
                        // forget that did not happen.
                        editorRefusal = refusal
                        if (refusal == null) editing = null
                    }
                },
            )
        }
    }

    // The trip detail is the app's one pushed route. It is kept composed through its
    // exit, so CLOSE and DELETE both leave on a fade instead of a snap; the content's
    // own `remember` holds the row while `openTrip` is already null, because a route
    // that is leaving still has to draw what it was showing.
    AnimatedVisibility(
        visible = openTrip != null,
        enter = fadeIn(tween(HudMotion.ENTRANCE_MS, easing = HudMotion.Standard)),
        exit = fadeOut(tween(HudMotion.SHORT_MS, easing = HudMotion.Standard)),
    ) {
        val row = remember { openTrip }
        if (row != null) {
            TripDetailScreen(row = row, vm = trips, onDismiss = { openTrip = null })
        }
    }
}

/**
 * A native page, opaque on purpose: it slides over the retained Web view, and a
 * transparent page would let that view show through mid-swipe.
 *
 * It also hands its panels the pager's answer about visibility, so their entrance
 * [HudEnter] runs when the page comes in rather than when it happens to be composed.
 */
@Composable
private fun NativePage(active: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Bg)
            // A page that is not on screen does not paint. With every page kept
            // composed, the frame pass would otherwise replay four pages of draw
            // commands for every frame the shell draws: the RECORDING breath on the
            // Live page measured 13 ms a frame with nothing kept composed and 17-26 ms
            // with the pages composed and still painting. The gate rides the same
            // viewport flag as everything else, and that flag flips at the first pixel
            // of movement (see PagerFocus), so a page never misses a frame it could be
            // seen in.
            .drawWithContent { if (active) drawContent() },
    ) {
        CompositionLocalProvider(LocalEnterActive provides active) { content() }
    }
}

/**
 * The glass sheet under the selected slot, and the only thing that reads the pager
 * while it moves.
 *
 * Its own composable on purpose. How far a pager has travelled changes every frame of
 * a swipe and every frame of a tap's animation; read out in the body of
 * [AppShell], that one value recomposes the whole screen - both native pages, the
 * gauges, the lists, the bar - sixty times a second, and on this tablet that was
 * enough to drop most of the frames it was drawing. Read here, it recomposes a Box.
 */
@Composable
private fun PillSheet(
    pager: PagerState,
    bounds: Map<AppTab, Rect>,
    barOrigin: Offset,
    cutPill: Animatable<Float, AnimationVector1D>,
    cutPillActive: Boolean,
) {
    val density = LocalDensity.current
    val settled = pager.currentPage.coerceIn(0, BarLayout.pages.size - 1)
    val rest = bounds[BarLayout.pages[settled]]
    // A fraction of the way between two pages: integer at rest, and sweeping from
    // one integer to the next while the user drags or the bar animates a tap. While
    // a distant tap fades through, the pager cuts instead of travelling, so the
    // sheet follows the value the shell is animating for it instead - one sheet,
    // still moving, while the page behind it is out.
    val raw = if (cutPillActive) {
        cutPill.value
    } else {
        pager.currentPage + pager.currentPageOffsetFraction
    }
    val frac = raw.coerceIn(0f, (BarLayout.pages.size - 1).toFloat())
    val from = BarLayout.pages[frac.toInt()]
    val to = BarLayout.pages[(frac.toInt() + 1).coerceAtMost(BarLayout.pages.size - 1)]
    val f = frac - frac.toInt()
    val a = bounds[from]
    val b = bounds[to]
    // Motion on, the sheet is interpolated where the swipe actually is, so it travels
    // with the pages and stops where they stop. Motion off, it is simply under the
    // settled slot - and the page change itself is a snap, not a scroll.
    val pill = if (HudMotion.enabled && a != null && b != null && f > 0.001f) {
        Rect(
            a.left + (b.left - a.left) * f,
            a.top + (b.top - a.top) * f,
            a.right + (b.right - a.right) * f,
            a.bottom + (b.bottom - a.bottom) * f,
        )
    } else {
        rest ?: a
    } ?: return
    if (pill.width <= 0f) return
    with(density) {
        Box(
            Modifier
                .offset((pill.left - barOrigin.x).toDp(), (pill.top - barOrigin.y).toDp())
                .size(pill.width.toDp(), pill.height.toDp())
                // `.conn-pill.active`: the glass sheet with the cyan hairline.
                .hudGlass(
                    shape = HudRadius.CardShape,
                    borderColor = HudColors.CyanDim,
                    elevation = 8.dp,
                    highlight = 0.05f,
                ),
        )
    }
}

/**
 * The bottom bar: five slots, the vehicle page in the middle and larger, the current
 * one under a glass sheet that slides between them (`.conn-pill.active`). The sheet is
 * not five fills that swap in place - it is one sheet, because every state change on
 * the page is transitioned (0.2-0.3 s) and a tab that changes is a state change.
 *
 * Which glyph goes in which slot, how big it is and whether it keeps its own colours is
 * [BarLayout]'s decision, not this loop's - so the centre treatment is one line in a
 * test instead of five branches here, and a slot cannot be drawn as one thing and paged
 * as another.
 */
@Composable
private fun BottomBar(
    selected: AppTab,
    pager: PagerState,
    bounds: Map<AppTab, Rect>,
    cutPill: Animatable<Float, AnimationVector1D>,
    cutPillActive: Boolean,
    tripsAvailable: Boolean,
    connected: Boolean,
    /** The Settings slot's "newer release" mark; the dot never sits on any other slot. */
    updateDot: Boolean,
    onBounds: (AppTab, Rect) -> Unit,
    onSelect: (AppTab) -> Unit,
) {
    // Where the bar itself starts on screen. The slot bounds are measured in root
    // coordinates (that is what onGloballyPositioned reports), and Modifier.offset is
    // relative to this Box, so the two have to be reconciled - without that, the sheet
    // is drawn a bar-height away from where it was measured and lands off screen.
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { barOrigin = it.positionInRoot() }
            // No inset padding here on purpose. `DashboardRoot` already pads the whole
            // app for the system bars, and a Compose `windowInsetsPadding` does not
            // consume the inset it used, so a second one on the bar's own bottom edge
            // reserved the navigation bar twice - 48 dp of dead strip under the tab bar
            // wherever the device draws three buttons.
            ,
    ) {
        PillSheet(pager, bounds, barOrigin, cutPill, cutPillActive)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            for (tab in BarLayout.pages) {
                val spec = BarLayout.glyph(tab)
                Box(Modifier.weight(spec.weight), contentAlignment = Alignment.Center) {
                    BarGlyph(
                        icon = BarLayout.icon(tab),
                        desc = spec.desc,
                        selected = selected == tab,
                        // Dimmed rather than gone: the slot is a page even when there is
                        // nothing to put in it (ShellRules.glyphDimmed). A firmware with
                        // no trip store keeps the pin and dims it - the strip used to
                        // write "TRIPS x" for the same fact. The vehicle slot is never
                        // dimmed: it is the page that works with nothing connected.
                        dim = ShellRules.glyphDimmed(tab, connected, tripsAvailable),
                        colored = spec.colored,
                        size = spec.size,
                        box = spec.box,
                        badge = tab == AppTab.SETTINGS && updateDot,
                        onBounds = { onBounds(tab, it) },
                        enabled = tab != AppTab.TRIPS || tripsAvailable,
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
    }
}

/**
 * One icon in the bar: the three states a slot can be in, mapped onto the app's one glyph control.
 *
 * The bar used to draw its own glyph — its own tint animation, its own alpha dim, its own badge —
 * while the settings row's reset and the unit page's action line each hand-rolled the same thing
 * differently. [HudGlyphAction] is now that control, and this is only the translation from the
 * slot's three words to the control's three arguments:
 *
 *  - `dim` (`--text-dim`) and unselected (`--text-muted`) are inks; selected takes the accent cyan.
 *  - unselected slots recede to 62% so the selected one leads without moving; the selected slot is
 *    full strength.
 *  - `colored` is the exception path: paint the artwork as the theme drew it and let it recede by
 *    alpha instead. Only the vehicle glyph takes it now. The gauge on the LIVE slot is still the
 *    launcher's own geometry, but it is tinted like its neighbours — a full-colour mark in a row of
 *    tinted ones reads as a different kind of control, and an alpha-only dim is indistinguishable
 *    next to a selected cyan one.
 */
@Composable
private fun BarGlyph(
    icon: ImageVector,
    desc: String,
    selected: Boolean,
    dim: Boolean = false,
    colored: Boolean = false,
    size: HudGlyphSize,
    box: Dp,
    /** A small amber mark on the slot's corner — the one thing the bar may shout about. */
    badge: Boolean = false,
    enabled: Boolean = true,
    onBounds: (Rect) -> Unit = {},
    onClick: () -> Unit,
) {
    val ink = when {
        dim -> HudColors.TextDim
        selected -> HudColors.Cyan
        else -> HudColors.TextMuted
    }
    HudGlyphAction(
        glyph = icon,
        description = desc,
        size = size,
        box = box,
        ink = ink,
        enabled = enabled,
        badge = badge,
        emphasis = if (selected) 1f else 0.62f,
        asDrawn = colored,
        onBounds = onBounds,
        onClick = onClick,
    )
}
