package com.alefinot.dashboardpp.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.storage.AppRoot
import com.alefinot.dashboardpp.storage.StorageCopy
import com.alefinot.dashboardpp.service.TripLogService
import com.alefinot.dashboardpp.trips.RecorderSettings
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.components.hudBytes
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgLive
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.viewmodel.TripsViewModel
import com.alefinot.dashboardpp.weather.CityFallback
import com.alefinot.dashboardpp.weather.Place
import com.alefinot.dashboardpp.weather.PositionSource
import com.alefinot.dashboardpp.weather.WeatherCoordinator
import com.alefinot.dashboardpp.weather.WeatherRuntime
import com.alefinot.dashboardpp.weather.WeatherSettings
import com.alefinot.dashboardpp.weather.WeatherStatus

@Composable
fun SettingsScreen(
    vm: ConnectionViewModel,
    unit: String?,
    version: String?,
    trips: TripsViewModel,
    tripsAvailable: Boolean,
) {
    val context = LocalContext.current
    val recording by TripRuntime.state.collectAsStateWithLifecycle()
    var storageLine by remember { mutableStateOf("") }
    var showConfirmClear by remember { mutableStateOf(false) }
    val settings = remember { RecorderSettings(context) }
    val scope = rememberCoroutineScope()
    val recordingEnabled by settings.recordingEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val motionOn by settings.motionEnabled.collectAsStateWithLifecycle(initialValue = true)
    val weatherSettings = remember { WeatherSettings(context) }
    val weatherFeed = remember { WeatherRuntime.coordinator(context) }
    val weatherOn by weatherSettings.enabled.collectAsStateWithLifecycle(initialValue = true)
    val weatherCity by weatherSettings.cityFallback
        .collectAsStateWithLifecycle(initialValue = CityFallback(null, null))
    val weatherStatus by weatherFeed.status.collectAsStateWithLifecycle()
    var cityEdit by remember { mutableStateOf<String?>(null) }
    var weatherLine by remember { mutableStateOf("") }
    val appVersion = remember {
        try {
            val app = context.applicationContext
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    Column(
        Modifier.hudPage()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HudPage.Gutter)
            .padding(top = 10.dp, bottom = 24.dp),
    ) {
        HudPageHeader(title = "App Settings") {
            HudBanner(
                "app $appVersion  ·  " + (unit ?: "no dashboard linked"),
            )
        }
        Spacer(Modifier.height(16.dp))

        // ── Trip recording ───────────────────────────────────────────────
        RecordingCard(
            recording = recording.recording,
            needsUpdate = !tripsAvailable,
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IdCard("UNIT", unit ?: "NOT LINKED", Modifier.weight(1f))
            IdCard("FIRMWARE", version ?: "—", Modifier.weight(1f))
            IdCard("APP", appVersion, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth().hudCard()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CardHead("Trip Recording")
            ToggleRow(
                label = "AUTO-START ON CONNECT",
                note = "Starts logging a drive as soon as the dashboard connects. " +
                    "Off, a drive is logged only while this app is open on the Live page.",
                on = recordingEnabled,
                toggleLabel = "auto-start on connect",
            ) { on ->
                scope.launch {
                    settings.setRecordingEnabled(on)
                    if (on) {
                        val owner = vm.recorderTarget()
                        if (owner != null) {
                            TripLogService.start(
                                context, owner.deviceKey, owner.vehicleId,
                                owner.vehicleName, owner.eff,
                            )
                        }
                    } else {
                        TripLogService.stop(context)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                label = "MOTION",
                note = "On, the app moves the way the dashboard does: panels arrive with " +
                    "the page's own fade, the tab marker travels to the tab you tap, the " +
                    "recorded trips come in one after another, and a live recording " +
                    "breathes. Off, everything is simply in place. A device that turns its " +
                    "own animation scale off stops the app too, without asking.",
                on = motionOn,
                toggleLabel = "motion",
            ) { on -> scope.launch { settings.setMotionEnabled(on) } }
        }
        Spacer(Modifier.height(12.dp))

        // ── Weather feed ─────────────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().hudCard()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CardHead("Weather Feed")
            Text(
                "The dashboard has no radio of its own, so this phone asks the weather " +
                    "service and sends the answer over Bluetooth: on connect, once a " +
                    "minute, and again when the car has moved 2 km. A reading older than " +
                    "three hours tells the dashboard to show Weather Offline.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                label = "PUSH WEATHER",
                note = "Off stops every call this app makes for weather — no forecast, " +
                    "no place-name lookups — and sends the dashboard nothing further.",
                on = weatherOn,
                toggleLabel = "push weather",
            ) { on -> scope.launch { weatherSettings.setEnabled(on) } }
            Spacer(Modifier.height(12.dp))
            Text("FALLBACK CITY", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(4.dp))
            HudInput(
                value = cityEdit ?: (weatherCity.typed ?: ""),
                onValueChange = { cityEdit = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Used when the unit has no GPS fix",
                onDone = {
                    val typed = cityEdit ?: weatherCity.typed
                    scope.launch { weatherLine = saveWeatherCity(typed, weatherFeed, weatherSettings) }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    "SAVE CITY",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.SECONDARY,
                ) {
                    val typed = cityEdit ?: weatherCity.typed
                    scope.launch { weatherLine = saveWeatherCity(typed, weatherFeed, weatherSettings) }
                }
                HudButton(
                    "PUSH NOW",
                    modifier = Modifier.weight(1f),
                    enabled = weatherStatus.unitKey != null,
                ) {
                    val key = weatherStatus.unitKey ?: return@HudButton
                    scope.launch { weatherFeed.pushNow(key) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(weatherStatusLine(weatherStatus), color = HudColors.TextMuted, style = HudeCfgLive())
            weatherStatus.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = HudColors.Amber, style = HudeCfgNote())
            }
            if (weatherLine.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(weatherLine, color = HudColors.TextMuted, style = HudeCfgNote())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Weather data by Open-Meteo.com \u00b7 place names by OpenStreetMap " +
                    "Nominatim contributors",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }
        Spacer(Modifier.height(12.dp))

        var storageTick by remember { mutableIntStateOf(0) }
        val lifecycle = LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) storageTick++
            }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        var storage by remember { mutableStateOf<StorageSnapshot?>(null) }
        LaunchedEffect(storageTick) { trips.refresh() }
        val tripCounts by trips.tripCounts.collectAsStateWithLifecycle()
        LaunchedEffect(storageTick, tripCounts) {
            storage = withContext(Dispatchers.IO) { StorageSnapshot.of(context, vm, trips) }
        }
        val legacyStorageAsk = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { storageTick++ }
        storage?.let { snap ->
            StorageCard(
                snapshot = snap,
                onGrant = {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        legacyStorageAsk.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        openStorageGrantScreen(context)
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        Column(
            Modifier.fillMaxWidth().hudCard()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CardHead("Recorded Drives")
            Text(
                "One CSV per drive in ${AppRoot.sharedRootLabel()}/${AppRoot.TRIPS}/, written " +
                    "by this app \u2014 open them in a spreadsheet, copy them off over USB, or " +
                    "delete them here. Without the storage grant the same files stay in the " +
                    "app's own folder, which the Storage card above says is happening.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    "STORAGE & FILES",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.SECONDARY,
                ) {
                    storageLine = hudBytes(trips.storageBytes.value) + " recorded · " +
                        trips.rows.value.size + " trip files"
                }
                HudButton(
                    "DELETE ALL TRIPS",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.DANGER,
                ) { showConfirmClear = true }
            }
            if (storageLine.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(storageLine, color = HudColors.TextMuted, style = HudeCfgLive())
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Auto-start is per vehicle: a card with recording enabled begins logging when " +
                    "its dashboard connects, and stops when the vehicle is turned off. A drive " +
                    "whose start you did not confirm was still recorded and kept; a drive you " +
                    "deleted is gone.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Dashboard++ by @ale.finot",
            color = HudColors.Cyan,
            style = HudeFormLabel(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }

    if (showConfirmClear) {
        HudConfirmDialog(
            message = "Delete every recorded trip? All CSV files the app has " +
                "written go away. A drive that is still being recorded is kept.",
            confirmLabel = "DELETE ALL",
            confirmStyle = HudButtonStyle.DANGER,
            onConfirm = {
                trips.deleteAll()
                storageLine = "All recorded trips removed."
                showConfirmClear = false
            },
            onDismiss = { showConfirmClear = false },
        )
}
    }

private class StorageSnapshot(
    val shared: Boolean,
    val tripFiles: Int,
    val otherTripFiles: Int,
    val otherTripFilesInOwnFolder: Boolean,
    val cars: Int,
    val freeBytes: Long,
) {
    companion object {
        /** Runs on the caller's dispatcher; the caller keeps it off the main thread. */
        suspend fun of(context: Context, vm: ConnectionViewModel, trips: TripsViewModel): StorageSnapshot {
            val shared = AppRoot.isSharedAvailable(context)
            val where = AppRoot.tripsDir(context)
            val counts = trips.tripCounts.value
            val strays = counts.elsewhere
            val free = try {
                val fs = StatFs(where.absolutePath)
                fs.availableBytes
            } catch (e: Exception) {
                -1L
            }
            return StorageSnapshot(
                shared = shared,
                tripFiles = counts.here,
                otherTripFiles = strays?.count ?: 0,
                otherTripFilesInOwnFolder = strays != null &&
                    AppRoot.sameFolder(strays.path, AppRoot.privateTripsDir(context).absolutePath),
                cars = try { vm.vehicleCards().first().size } catch (e: Exception) { 0 },
                freeBytes = free,
            )
        }
    }
}

@Composable
private fun StorageCard(snapshot: StorageSnapshot, onGrant: () -> Unit) {
    val counts = StorageCopy.cardCounts(
        snapshot.tripFiles, snapshot.cars, snapshot.otherTripFiles, snapshot.otherTripFilesInOwnFolder,
    ) + (if (snapshot.freeBytes >= 0) " \u00b7 ${hudBytes(snapshot.freeBytes)} free" else "")
    Column(
        Modifier.fillMaxWidth().hudCard()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        CardHead("Storage")
        Text(
            (if (snapshot.shared) AppRoot.sharedRootLabel() else "app-private folder") + " $counts",
            color = if (snapshot.shared) HudColors.TextPrimary else HudColors.Amber,
            style = HudeCfgNote(),
        )
        if (snapshot.shared) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${AppRoot.GARAGE_FILE} \u00b7 ${AppRoot.TRIPS}/ \u00b7 ${AppRoot.VEHICLES}/",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }
        Spacer(Modifier.height(10.dp))
        val grantPath = AppRoot.grantPath(Build.VERSION.SDK_INT)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.heightIn(min = 44.dp)) {
                HudButton(
                    StorageCopy.grantButton(snapshot.shared, grantPath),
                    style = HudButtonStyle.SECONDARY,
                    enabled = !snapshot.shared,
                ) { onGrant() }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            StorageCopy.storageNote(snapshot.shared, grantPath),
            color = HudColors.TextDim,
            style = HudeCfgNote(),
        )
    }
}

private fun openStorageGrantScreen(context: Context) {
    val pkg = Uri.parse("package:" + context.packageName)
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
            add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

@Composable
private fun IdCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.hudCard().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, color = HudColors.TextMuted, style = HudePerfLabel())
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = HudColors.TextPrimary,
            style = HudePerfValue(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    note: String,
    on: Boolean,
    toggleLabel: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(3.dp))
            Text(note, color = HudColors.TextDim, style = HudeCfgNote())
        }
        HudToggle(on = on, label = toggleLabel, onToggle = onToggle)
    }
}

@Composable
private fun RecordingCard(
    recording: Boolean,
    needsUpdate: Boolean,
) {
    val (status, color) = when {
        needsUpdate -> "NEEDS FIRMWARE UPDATE" to HudColors.Amber
        recording -> "RECORDING NOW" to HudColors.Green
        else -> "NOT RECORDING" to HudColors.TextDim
    }
    Box(
        Modifier.fillMaxWidth()
            .hudCard(
                fill = HudColors.Msg,
                borderColor = color.copy(alpha = 0.3f),
            )
            .background(color.copy(alpha = 0.08f), HudRadius.CardShape)
            .padding(12.dp),
    ) {
        Column {
            Text(status, color = color, style = HudeSectionTitle())
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    needsUpdate ->
                        "This dashboard's firmware is older than V1.4.6, so it does not " +
                            "send the frame the trip recorder reads. Speed, GNSS and trip " +
                            "figures stay unavailable and drives cannot be logged until the " +
                            "firmware is updated."
                    recording ->
                        "Following the dashboard's one-per-second stream. This Android " +
                            "version keeps reading with the screen off or the app " +
                            "backgrounded; battery optimization for this app is switched " +
                            "off so the drive does not fill with gaps at every screen " +
                            "timeout."
                    else ->
                        "Logging idle: connected, waiting for samples, or auto-start " +
                            "is off."
                },
                color = HudColors.TextMuted,
                style = HudeCfgHelp(),
            )
        }
    }
}

internal suspend fun saveWeatherCity(
    typed: String?,
    feed: WeatherCoordinator,
    settings: WeatherSettings,
): String {
    val query = typed?.trim().orEmpty()
    if (query.isEmpty()) {
        settings.setCity(null, null)
        return "Fallback city cleared. The feed will use the unit's own fix."
    }
    val place = feed.resolveCity(query)
    settings.setCity(query, place)
    return if (place == null) {
        "\"$query\" brought nothing back. The text is kept; a nearby town may do."
    } else {
        "Weather will fall back to ${placeLabel(place)}."
    }
}

/** "Munich, Bavaria, Germany" — which of the several Münchs this app picked, in full. */
internal fun placeLabel(place: Place): String =
    listOfNotNull(place.name, place.admin1, place.country).joinToString(", ")

internal fun weatherStatusLine(status: WeatherStatus, nowMs: Long = System.currentTimeMillis()): String {
    if (status.busy) return "asking the weather service now..."
    val pushed = status.lastPushAt
    if (pushed == null || pushed == WeatherSettings.NO_EPOCH) return "this app has not fed the dashboard yet"
    val ageMin = ((nowMs - pushed).coerceAtLeast(0L)) / 60_000L
    val source = when (status.source) {
        PositionSource.FIX -> "unit GNSS"
        PositionSource.CACHE -> "last known position"
        PositionSource.CITY -> "typed city"
        null -> "unknown position"
    }
    return "fed ${status.label?.takeIf { it.isNotBlank() } ?: "no name"} | $source | ${ageMin} min ago"
}
