package com.alefinot.dashboardpp.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.LocalEnterActive
import com.alefinot.dashboardpp.ui.components.PageFade
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * gesture mirror that fed touches to that View are out of this shell. The classes stay
 * until the WebView itself is deleted (the plan's Phase 2c); nothing here reaches them.
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
    val ip = conn?.ip

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

    var openTrip by remember { mutableStateOf<TripRow?>(null) }
    // Typing an address by hand is a panel over the shell. It is raised from the vehicle
    // page and nowhere else, and it closes by itself the moment a device turns up.
    var manualOpen by remember { mutableStateOf(false) }
    LaunchedEffect(connected) { if (connected) manualOpen = false }

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
    val scope = rememberCoroutineScope()
    // The settled page. The bar's selection, which screen is `active`, and whether
    // the Web UI may poll all follow this one value.
    val page = BarLayout.pages.getOrElse(pager.currentPage) { BarLayout.home }

    // A cover's one action: go to the page that can do something about it. It moves the
    // pager exactly the way a bar tap does - animated while motion is on, a snap when it
    // is off - because the cover is not a screen to leave, it is this screen with a
    // different page in front of you.
    val openGarage: () -> Unit = {
        val index = BarLayout.pageOf(AppTab.VEHICLES)
        if (index >= 0) {
            scope.launch {
                if (HudMotion.enabled) pager.animateScrollToPage(index) else pager.scrollToPage(index)
            }
        }
    }
    // Which vehicle a cover is waiting for. The garage's list is collected here too, not
    // only on the vehicle page, because a cover has to name the vehicle it cannot reach -
    // and the store is DataStore-backed, so there is no synchronous read to borrow. The
    // collection is keyed to the view model and the shell only recomposes when a card is
    // added or edited, which is rare, cheap (the retained Web view and the pager keep their
    // state), and always something the user did on the vehicle page a moment earlier.
    val garageCards = remember(connection) { connection.vehicleCards() }
    val garageList by garageCards.collectAsStateWithLifecycle(initialValue = emptyList())
    val coverVehicleName = garageList.firstOrNull { it.id == connection.selectedVehicleId.value }?.name

    // Recording needs a device to sample, firmware with the snapshot endpoint, and the
    // user's switch - all three, and the first of them is why the service never runs
    // in the offline shell (see ShellRules.recorderWants). It also needs to know whose
    // drive this is, which is what the recorder writes as the file's `#vehicle=` line:
    // the snapshot endpoint says nothing about who answered, so the app has to say it.
    LaunchedEffect(ip, conn?.vehicleId, tripsAvailable, recordingEnabled) {
        val target = ip?.takeIf { ShellRules.recorderWants(ip, tripsAvailable, recordingEnabled) }
        if (target != null) {
            val owner = connection.recorderTarget()
            TripLogService.start(
                context, target,
                owner?.vehicleId.orEmpty(), owner?.vehicleName.orEmpty(), owner?.eff.orEmpty(),
            )
        } else {
            TripLogService.stop(context)
            // A page the firmware cannot serve is snapped out of, not animated to: the
            // user did not ask to go anywhere. With no device the trips page is the
            // phone's own, so the offline shell leaves the user on it.
            if (ShellRules.pageUnserved(tripsAvailable, connected = ip != null)) {
                val now = BarLayout.pages.getOrElse(pager.currentPage) { BarLayout.home }
                if (now == AppTab.TRIPS) pager.scrollToPage(BarLayout.pageOf(AppTab.LIVE))
            }
        }
    }

    // Two different questions, and collapsing them is what made the app sluggish.
    // "Is the page inside the pager's viewport" decides whether it may paint and take
    // touches; a swipe uncovers it long before the pager commits, so waiting for the
    // settled page shows a half-drawn page sliding in. "Is it the settled page" decides
    // who owns the HTTP lane and which page's own 1 s tick runs - that one has to wait
    // for the settle, or two clients would poll the dashboard at once (the recorder
    // stands down while the live page samples, see `EspApiLane`). The viewport answer is
    // derived: recomputed as the pager moves, but it only changes the moment it flips,
    // so a swipe does not recompose this screen.
    // The bar: the selected slot's glass sheet is measured from the icons, and it
    // follows the pager rather than a separate selection - which is what makes it
    // slide during a swipe instead of only after one.
    // The sheet's composable reads this map and the pager; the screen body does not,
    // so a swipe recomposes the sheet and nothing else.
    val bounds = remember { mutableStateMapOf<AppTab, Rect>() }

    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Nothing is retained underneath the pages any more. The ESP slot used
                // to be a hole in them: a WebView kept composed behind the pager and
                // uncovered by a swipe, because letting Compose remove that View cost
                // the user 0.6-1.1 s while Chromium rebuilt its rendering surface. The
                // slot is a page now, so there is no surface to keep warm, no gesture
                // stream to mirror onto a View under the pager, and no opaque page to
                // hide a second copy of the same settings.
                // Pages beyond the visible one are composed as well, so arriving is a
                // paint and not a build: a cold page used to be composed while the
                // pager was already moving, and the Trips page costs ~650 ms of UI
                // thread on this tablet (measured - see the plan's baseline). That is
                // the one long frame in a swipe.
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
                    while (extraPages < BarLayout.pages.size - 2) {
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
                                onEnterIp = { manualOpen = true },
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
                                OfflineCover(
                                    title = "NO DASHBOARD ON THE NETWORK",
                                    reason = reason,
                                    note = "Trips and App Settings work without a device. " +
                                        "Speed, GNSS and the dashboard's own configuration " +
                                        "need one - the vehicle page is where a dashboard is " +
                                        "found and connected.",
                                    vehicleName = coverVehicleName,
                                    onOpenVehicles = openGarage,
                                )
                            } else {
                                // Off-screen pages are not the live view: their polling has
                                // to stay down or two clients would hit /api/snapshot at once.
                                LiveTab(ip = conn.ip, active = page == AppTab.LIVE)
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
                                        "The settings schema is missing from the app package. " +
                                            "It is generated from the firmware's own tables " +
                                            "(scripts/gen_config_schema.py); without it this " +
                                            "page has no idea what the unit's settings are.",
                                        style = HudeFormLabel(),
                                        color = HudColors.Amber,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                    else -> DashboardSettingsScreen(
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
                                    ip = conn?.ip,
                                    version = conn?.version,
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
                tripsAvailable = tripsAvailable,
                connected = connected,
                onBounds = { slot, r -> bounds[slot] = r },
                onSelect = { slot ->
                    val index = BarLayout.pageOf(slot)
                    if (index >= 0) {
                        scope.launch {
                            // The bar animates the same move a swipe would make, so a
                            // tap and a drag look the same and the sheet has one source
                            // of motion. With motion off it is a snap.
                            if (HudMotion.enabled) pager.animateScrollToPage(index) else pager.scrollToPage(index)
                        }
                    }
                },
            )
        }

        if (manualOpen) {
            // Over the shell, not instead of it: cancelling returns the user to the page
            // they were on, and a successful entry recomposes the same shell with a
            // device in it.
            ManualEntryPanel(vm = connection) { manualOpen = false }
        }

        editing?.let { draft ->
            VehicleEditorScreen(
                vm = connection,
                draft = draft,
                isNew = editorIsNew,
                refusal = editorRefusal,
                onSave = { typed ->
                    connection.saveVehicle(typed) { refusal ->
                        editorRefusal = refusal
                        // A refused write leaves the form open with the reason in it; the
                        // user's edits are still on screen, because the card they were
                        // trying to fix is the one thing they must not lose.
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
                    connection.deleteVehicle(draft.id)
                    editing = null
                    editorRefusal = null
                },
            )
        }
    }

    openTrip?.let { row ->
        Box(Modifier.fillMaxSize()) {
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
private fun PillSheet(pager: PagerState, bounds: Map<AppTab, Rect>, barOrigin: Offset) {
    val density = LocalDensity.current
    val settled = pager.currentPage.coerceIn(0, BarLayout.pages.size - 1)
    val rest = bounds[BarLayout.pages[settled]]
    // A fraction of the way between two pages: integer at rest, and sweeping from
    // one integer to the next while the user drags or the bar animates a tap.
    val frac = (pager.currentPage + pager.currentPageOffsetFraction)
        .coerceIn(0f, (BarLayout.pages.size - 1).toFloat())
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
    tripsAvailable: Boolean,
    connected: Boolean,
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
            // The bar is the app's bottom edge, so it has to clear the system
            // navigation bar rather than sit under it. On a window that already fits
            // the system bars these insets read as zero.
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        PillSheet(pager, bounds, barOrigin)
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
 * One icon in the bar. Selected ones take the page's accent cyan and sit under the
 * travelling sheet; the rest are `--text-muted`, and dimmed ones `--text-dim`.
 *
 * `colored` is the exception path: paint the artwork as the theme drew it and let it
 * recede by alpha instead. Only the vehicle glyph takes it now. The gauge on the LIVE
 * slot is still the launcher's own geometry, but it is tinted like its neighbours - a
 * full-colour mark in a row of tinted ones reads as a different kind of control, and an
 * alpha-only dim is indistinguishable next to a selected cyan one.
 */
@Composable
private fun BarGlyph(
    icon: ImageVector,
    desc: String,
    selected: Boolean,
    dim: Boolean = false,
    colored: Boolean = false,
    size: Dp,
    box: Dp,
    enabled: Boolean = true,
    onBounds: (Rect) -> Unit = {},
    onClick: () -> Unit,
) {
    val ms = if (HudMotion.enabled) HudMotion.CONTROL_MS else 0
    val tint by animateColorAsState(
        when {
            dim -> HudColors.TextDim
            selected -> HudColors.Cyan
            else -> HudColors.TextMuted
        },
        tween(ms, easing = HudMotion.Ease),
        label = "glyphTint",
    )
    val fade by animateFloatAsState(
        targetValue = if (selected) 1f else 0.62f,
        tween(ms, easing = HudMotion.Ease),
        label = "glyphFade",
    )
    Box(
        Modifier
            .size(box)
            .clip(HudRadius.CardShape)
            // Measured as the icon's own box, so the sheet that slides under it is
            // that box and not the slot's whole weight.
            .onGloballyPositioned { c -> onBounds(c.boundsInRoot()) }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (colored) {
            Image(icon, desc, modifier = Modifier.size(size).alpha(fade))
        } else {
            Image(icon, desc, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size).alpha(fade))
        }
    }
}
