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

    val firstRun = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        firstRun.value = try {
            connection.vehicleCount() == 0
        } catch (e: Exception) {
            false
        }
    }

    var openTrip by remember { mutableStateOf<TripRow?>(null) }

    var editing by remember { mutableStateOf<Vehicle?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }
    var editorRefusal by remember { mutableStateOf<String?>(null) }

    val tripsAvailable = TripsEnabled.forFirmware(conn?.version)

    val settings = remember { RecorderSettings(context) }
    val recordingEnabled by settings.recordingEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val motionEnabled by settings.motionEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(motionEnabled) { HudMotion.setPrefEnabled(motionEnabled) }

    val pager = rememberPagerState(initialPage = BarLayout.homePage, pageCount = { BarLayout.pages.size })
    val scope = rememberCoroutineScope()
    val page = BarLayout.pages.getOrElse(pager.currentPage) { BarLayout.home }

    val openGarage: () -> Unit = {
        val index = BarLayout.pageOf(AppTab.VEHICLES)
        if (index >= 0) {
            scope.launch {
                if (HudMotion.enabled) pager.animateScrollToPage(index) else pager.scrollToPage(index)
            }
        }
    }
    val garageCards = remember(connection) { connection.vehicleCards() }
    val garageList by garageCards.collectAsStateWithLifecycle(initialValue = emptyList())
    val coverVehicleName = garageList.firstOrNull { it.id == connection.selectedVehicleId.value }?.name

    LaunchedEffect(address, conn?.vehicleId, tripsAvailable, recordingEnabled) {
        val owner = connection.recorderTarget()
        if (owner != null && ShellRules.recorderWants(true, tripsAvailable, recordingEnabled)) {
            TripLogService.start(
                context, owner.deviceKey, owner.vehicleId, owner.vehicleName, owner.eff,
            )
        } else {
            TripLogService.stop(context)
            if (ShellRules.pageUnserved(tripsAvailable, connected = address != null)) {
                val now = BarLayout.pages.getOrElse(pager.currentPage) { BarLayout.home }
                if (now == AppTab.TRIPS) pager.scrollToPage(BarLayout.pageOf(AppTab.LIVE))
            }
        }
    }

    val bounds = remember { mutableStateMapOf<AppTab, Rect>() }

    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                NotLinkedCover(
                                    title = "NO DASHBOARD IN RANGE",
                                    reason = reason,
                                    note = "Trips and App Settings work without a dashboard. " +
                                        "Speed, GNSS and the dashboard's own configuration " +
                                        "need one - the vehicle page is where a dashboard is " +
                                        "paired and connected.",
                                    vehicleName = coverVehicleName,
                                    onOpenVehicles = openGarage,
                                    permissionProblem = connection.permissionMissing.value,
                                    firstRun = firstRun.value == true,
                                )
                            } else {
                                LiveTab(active = page == AppTab.LIVE)
                            }
                        }
                        AppTab.WEB -> NativePage(inView) {
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
                                        timeNote = connection.timeNote.value,
                                        store = unit,
                                        schema = schema,
                                        link = link,
                                    )
                                }
                            }
                        }
                        AppTab.SETTINGS -> NativePage(inView) {
                            PageFade(Modifier.fillMaxSize()) {
                                SettingsScreen(
                                    vm = connection,
                                    unit = conn?.name ?: address,
                                    version = conn?.version,
                                    trips = trips,
                                    tripsAvailable = tripsAvailable,
                                )
                            }
                        }
                    }
                }
            }
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
                            if (HudMotion.enabled) pager.animateScrollToPage(index) else pager.scrollToPage(index)
                        }
                    }
                },
            )
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
                        editorRefusal = refusal
                        if (refusal == null) editing = null
                    }
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

@Composable
private fun NativePage(active: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Bg)
            .drawWithContent { if (active) drawContent() },
    ) {
        CompositionLocalProvider(LocalEnterActive provides active) { content() }
    }
}

@Composable
private fun PillSheet(pager: PagerState, bounds: Map<AppTab, Rect>, barOrigin: Offset) {
    val density = LocalDensity.current
    val settled = pager.currentPage.coerceIn(0, BarLayout.pages.size - 1)
    val rest = bounds[BarLayout.pages[settled]]
    val frac = (pager.currentPage + pager.currentPageOffsetFraction)
        .coerceIn(0f, (BarLayout.pages.size - 1).toFloat())
    val from = BarLayout.pages[frac.toInt()]
    val to = BarLayout.pages[(frac.toInt() + 1).coerceAtMost(BarLayout.pages.size - 1)]
    val f = frac - frac.toInt()
    val a = bounds[from]
    val b = bounds[to]
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
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { barOrigin = it.positionInRoot() }
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
