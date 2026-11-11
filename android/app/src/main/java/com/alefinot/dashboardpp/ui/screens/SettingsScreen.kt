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

/**
 * The "APP SETTINGS" tab — controls that apply to the Android app itself. The unit's own
 * configuration (its rows, save / reboot / factory reset) is on the dashboard settings page
 * next door, and is not duplicated here.
 *
 * This is a tab, not an overlay: nothing to dismiss and no back handler to
 * install. It is also the settings page when there is no device, which is the point
 * of the nullable [unit]: the trip files, the motion switch and the storage figures
 * belong to the phone, so they stay readable and editable offline. Only the lines
 * that describe a device go quiet - the unit's name and the firmware. The vehicle page is
 * where a device is forgotten now: a card is a vehicle, not an address, and deleting
 * one needs the name on the screen to confirm it.
 */
@Composable
fun SettingsScreen(
    vm: ConnectionViewModel,
    unit: String?,
    version: String?,
    /** True while a Bluetooth link is up — the firmware card installs only over one. */
    connected: Boolean,
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
    // The weather feed has its own store and its own coordinator: the switch and the
    // typed city are the owner's, the schedule belongs to the process-wide feed that the
    // link and the trip service are also waking (one schedule, or the unit gets two).
    val weatherSettings = remember { WeatherSettings(context) }
    val weatherFeed = remember { WeatherRuntime.coordinator(context) }
    val weatherOn by weatherSettings.enabled.collectAsStateWithLifecycle(initialValue = true)
    val weatherCity by weatherSettings.cityFallback
        .collectAsStateWithLifecycle(initialValue = CityFallback(null, null))
    val weatherStatus by weatherFeed.status.collectAsStateWithLifecycle()
    // null means "not edited yet", which is what lets the field show the stored city
    // once the store has answered instead of starting out empty and staying that way.
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
    // The Storage half of the Trips & Storage card reads the volume, and the answer has
    // to be re-read whenever the page comes back to the foreground: the grant is made on
    // a system page the card opens, and a card that latched the answer from its first
    // composition would go on telling the owner the folder is private after they granted
    // it, which is the one thing this card exists to tell them.
    var storageTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageTick++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // Null until the first read finishes, so the storage block arrives a frame late rather
    // than promising a folder the app has not confirmed. The read touches the volume (it
    // creates the folder it reports on, and asks the volume for free space) and so stays
    // off the main thread.
    var storage by remember { mutableStateOf<StorageSnapshot?>(null) }
    // The card counts with the Trips index, so the index is the thing that has to be fresh.
    // Two effects, because they answer different questions: this one asks the volume again the
    // moment the page is opened or the app comes back to it - the owner may have spent that
    // time deleting a drive in a file manager, or granting storage, which moves the files -
    // and the one below rebuilds the card from whatever that look publishes.
    LaunchedEffect(storageTick) { trips.refresh() }
    val tripCounts by trips.tripCounts.collectAsStateWithLifecycle()
    LaunchedEffect(storageTick, tripCounts) {
        storage = withContext(Dispatchers.IO) { StorageSnapshot.of(context, vm, trips) }
    }
    // Android 8.1 through 10: there, the ordinary runtime permission is what makes Documents
    // writable at all, and this is the page the owner is standing on if the dialog at launch
    // went unanswered or was declined. From Android 11 nothing like it can be asked - All
    // files access is a special access and no code can bring its page up for them, only hand
    // them the page.
    val legacyStorageAsk = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { storageTick++ }
    Column(
        Modifier.hudPage()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HudPage.Gutter)
            .padding(top = 10.dp, bottom = 24.dp),
    ) {
        // `.header`: the page's h2 in its gradient, centred, over an `.ip-banner` pill that
        // says what this page is about in one line. The old heading was a left-aligned
        // "APP SETTINGS" and the switches it introduced sat at the opposite edge of a
        // 1920 px screen from their labels.
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

        // What the app is talking to, as three cards across the page's grid. They were one
        // card with three stacked label/value pairs, which put the value under the label it
        // belongs to only after a scroll; across the column they read at a glance.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IdCard("UNIT", unit ?: "NOT LINKED", Modifier.weight(1f))
            IdCard("FIRMWARE", version ?: "—", Modifier.weight(1f))
            IdCard("APP", appVersion, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        // ── Updates ──────────────────────────────────────────────────────
        // One card, two targets: the unit's firmware and this app's own APK. They
        // merged on 2026-11-11 because they answer one question - *what is newer than
        // what I am running, and how does it get in?* - and every half kept its own
        // honesty sentence: the spare-slot promise above the firmware buttons, the
        // in-place-and-keeps-data promise above the app ones. A failed press on either
        // half changes nothing on either device.
        var firmwareLine by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().hudCard()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CardHead("Updates")
            Spacer(Modifier.height(10.dp))
            Text("FIRMWARE", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(6.dp))
            Text(
                "Writes to the spare flash slot. A refused or interrupted install " +
                    "leaves the old firmware running.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
            FirmwareUpdateControl(
                unitVersion = version,
                linked = connected,
                onNote = { firmwareLine = it },
            )
            if (firmwareLine.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(firmwareLine, color = HudColors.TextMuted, style = HudeCfgNote())
            }

            Spacer(Modifier.height(16.dp))
            // The phone updating the phone. Same GitHub lane as the half above,
            // different receiver: the installer runs on this device, so this half works
            // with nothing linked, and the newest release is all it offers — the
            // installer refuses downgrades, so offering them would be a button built
            // to fail.
            Text("APP", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(6.dp))
            Text(
                "The installer replaces this app in place and keeps its data. A refused " +
                    "or cancelled install changes nothing.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
            AppUpdateControl(installed = appVersion)
        }
        Spacer(Modifier.height(12.dp))

        // ── Trips & Storage ──────────────────────────────────────────────
        // One card, because the three questions are one story: is the drive being
        // recorded, where does it go, and what is already in there. They used to be
        // three cards - the switches, the folder, and the actions over the files - and
        // the folder answer, the one that decides whether a drive can be found in a
        // file manager at all, sat under its own heading at the far end of the page.
        // The page's switches live in cards with the sentence that explains them, not on
        // bare rows whose label is at the left edge of the screen and whose switch is at the
        // right one. `label { color: var(--accent-cyan) }` is still the page's own colour.
        Column(
            Modifier.fillMaxWidth().hudCard()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CardHead("Trips & Storage")
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                label = "AUTO-START ON CONNECT",
                on = recordingEnabled,
                toggleLabel = "auto-start on connect",
            ) { on ->
                scope.launch {
                    settings.setRecordingEnabled(on)
                    // The switch is a preference first and a service second: offline
                    // there is nothing to sample, so turning it on saves the intent for
                    // the next connection instead of starting a recorder with no device
                    // behind it. Whose drives they are travels with the start command,
                    // because the recorder cannot ask the dashboard that.
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
                on = motionOn,
                toggleLabel = "motion",
            ) { on -> scope.launch { settings.setMotionEnabled(on) } }

            Spacer(Modifier.height(16.dp))
            Text("STORAGE", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(8.dp))
            val snap = storage
            if (snap == null) {
                // Not a guess and not a spinner: this block's whole job is naming a
                // folder, and the folder has not finished being confirmed yet.
                Text(
                    "reading the storage folder\u2026",
                    color = HudColors.TextDim,
                    style = HudeCfgNote(),
                )
            } else {
                StorageSection(
                    snapshot = snap,
                    onGrant = {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                            legacyStorageAsk.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            openStorageGrantScreen(context)
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("RECORDED DRIVES", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    "STORAGE & FILES",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.SECONDARY,
                ) {
                    storageLine = hudBytes(trips.storageBytes.value) + " recorded · " +
                        trips.rows.value.size + " trip files"
                }
                // The page never asks by turning one button into two: `confirmDialog()`
                // puts a modal box over the screen, and the only ways out are its two
                // buttons or Escape. Same here - and deleting every recorded drive is
                // exactly the kind of action that should not be one careless tap deep.
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
        }
        Spacer(Modifier.height(12.dp))

        // ── Weather feed ─────────────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().hudCard()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CardHead("Weather Feed")
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                label = "PUSH WEATHER",
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
            // Both providers' terms ask for credit and for the app to say who it is, and
            // BigDataCloud's free reverse endpoint refuses a call made from a server or a
            // non-browser client, so the names here are the two that are actually called.
            Text(
                "Weather: Open-Meteo.com \u00b7 places: OpenStreetMap",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }
        Spacer(Modifier.height(12.dp))

        Spacer(Modifier.height(20.dp))
        Text(
            "Dashboard++ by @ale.finot",
            color = HudColors.Cyan,
            style = HudeFormLabel(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        // No connect, no search, no address box, no forget: a device belongs to a
        // vehicle, so every one of those actions sits on the vehicle page's card and
        // nowhere else. What is left here are the app's own switches, which work
        // whether or not a dashboard is on the network.
    }

    if (showConfirmClear) {
        HudConfirmDialog(
            message = "Delete every recorded trip? A drive still being recorded is kept.",
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

/**
 * One label/value pair as its own `.card`: the page's `.perf-label` over `.perf-value`.
 *
 * The three of them used to be stacked inside one card, so "UNIT" sat above "MOTOSBU" above
 * "FIRMWARE" above the version, and reading the app's own number meant scrolling past two
 * other facts. Across the column they are one line each.
 */
/**
 * What the Storage card reports, read off the volume in one pass.
 *
 * A snapshot rather than four calls made from the composition: the folder check
 * creates the folder it reports on, `StatFs` asks the volume for its free space, and
 * the car count is a first read of the garage — none of which belongs on the UI
 * thread of a page that recomposes every time a switch on it moves.
 *
 * [tripFiles] counts the files inside the folder the card names, and [otherTripFiles] the
 * drives the Trips list shows from the other root. They are kept apart because the card's
 * headline names exactly one folder: an install that has been through a grant holds files in
 * both, and one number spanning both sat under a path that held none of them (bench,
 * 2026-11-06, a tablet whose shared folder had just been created: `Documents/Dashboard++ ·
 * 5 trip files` over an empty folder).
 */
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
                // A volume that cannot answer for its free space still tells the owner
                // where the files are; the number is the part that is dropped.
                -1L
            }
            return StorageSnapshot(
                shared = shared,
                // The split comes from the Trips index pass - the same walk that produced the
                // list the owner is looking at - so the card and that list cannot disagree about
                // which folder holds what. `trips.rows` is deliberately not counted here: it
                // spans every folder the app has recorded into.
                tripFiles = counts.here,
                otherTripFiles = strays?.count ?: 0,
                // "the app's own folder" is only true of the app's own folder. After a revoked
                // grant the remainder sits in Documents instead, and only a path describes that.
                otherTripFilesInOwnFolder = strays != null &&
                    AppRoot.sameFolder(strays.path, AppRoot.privateTripsDir(context).absolutePath),
                cars = try { vm.vehicleCards().first().size } catch (e: Exception) { 0 },
                freeBytes = free,
            )
        }
    }
}

/**
 * Where the user's files are, in the words they can act on — the **STORAGE** block of the
 * page's Trips & Storage card, so it carries no card of its own.
 *
 * This block exists because the answer used to be "inside the app's private folder",
 * which a user could not see in a file manager, could not copy over USB, and could
 * not clean up by hand — and because the answer now depends on a grant no dialog can
 * request, so the page has to say which of the two it is *and* hand them the way to
 * change it. Both states are stated plainly: granted says the path, ungranted says
 * the fallback in the warning colour rather than leaving the files somewhere the
 * owner assumes is the folder named on the card.
 */
@Composable
private fun StorageSection(snapshot: StorageSnapshot, onGrant: () -> Unit) {
    val counts = StorageCopy.cardCounts(
        snapshot.tripFiles, snapshot.cars, snapshot.otherTripFiles, snapshot.otherTripFilesInOwnFolder,
    ) + (if (snapshot.freeBytes >= 0) " \u00b7 ${hudBytes(snapshot.freeBytes)} free" else "")
    Text(
        (if (snapshot.shared) AppRoot.sharedRootLabel() else "app-private folder") + " $counts",
        color = if (snapshot.shared) HudColors.TextPrimary else HudColors.Amber,
        style = HudeCfgNote(),
    )
    if (snapshot.shared) {
        // Named file by file, because this line is also what a user follows after the editor
        // tells them the garage could not be read: the folder is easy to find, the file inside
        // it is the thing they have to open.
        Spacer(Modifier.height(6.dp))
        Text(
            "${AppRoot.GARAGE_FILE} \u00b7 ${AppRoot.TRIPS}/ \u00b7 ${AppRoot.VEHICLES}/",
            color = HudColors.TextDim,
            style = HudeCfgNote(),
        )
    }
    Spacer(Modifier.height(10.dp))
    // Every Android version gets the button, because every one has a way in
    // (`AppRoot.GrantPath`); the version decides what it does. Hiding it below API 30 - as
    // this card did, on the theory that Android 10 has nothing to offer - hid the only control
    // that works there, while the sentence above it went on describing a folder the owner
    // could have had for one tap.
    val grantPath = AppRoot.grantPath(Build.VERSION.SDK_INT)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.heightIn(min = 44.dp)) {
            // Already granted is said, not offered: a button that does nothing looks
            // like a button that is broken, and the honest state is the one thing this
            // card can prove.
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

/**
 * Hand the owner to the system page that grants All files access.
 *
 * There is no dialog for this: `MANAGE_EXTERNAL_STORAGE` is a special access the
 * platform only hands over from a settings screen, so the app's job ends at getting
 * them there. The per-app page is the one that lands them on this app's switch, but
 * some devices route that action nowhere; the all-files page is the same decision one
 * level up, and the app's details page is where a user can always get to. A chain of
 * three rather than one, for the same reason [openPhoneBluetoothSettings] in
 * `VehicleScreen.kt` has two: a button that throws is a button the owner never uses
 * again.
 */
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

/**
 * A switch with its own label and the sentence that explains it.
 *
 * The label and the switch used to be the two ends of a full-width row, which on this tablet
 * is 1800 px apart. Here the pair is one block inside a card: the words say what the switch
 * does, and the switch is next to them.
 */
@Composable
private fun ToggleRow(
    label: String,
    on: Boolean,
    toggleLabel: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = HudColors.Cyan, style = HudeFormLabel())
        }
        HudToggle(on = on, label = toggleLabel, onToggle = onToggle)
    }
}

/**
 * Recording status, and the reason when it is not running. "Needs update" is for
 * firmware with no /api/snapshot endpoint: the app found the device on
 * /api/live, so the honest message is that the firmware is too old, not that the
 * device is missing.
 */
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
    // The page's status strip (`#msg` plus its `.msg-*` variants): 8px radius, a
    // hairline in the status colour at 30%, that colour at 8% as the fill over the
    // status strip's own `rgba(15,23,42,.6)`, and the status colour in the label.
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
                    needsUpdate -> "Needs firmware V1.4.6 or newer."
                    recording -> "Following the unit's stream."
                    else -> "Waiting for samples."
                },
                color = HudColors.TextMuted,
                style = HudeCfgHelp(),
            )
        }
    }
}

/**
 * Store the owner's fallback city together with the coordinates the gazetteer gave it.
 *
 * The lookup runs here, at save time, and never once per feed: a typed city is one line
 * of text the owner changes once a year, while the schedule asks about it on every pass.
 * The answer returned is the sentence to show under the field, including the honest one
 * for a name no gazetteer knows — the text is kept so it can be edited, but there is no
 * position behind it, so the ladder will not use it.
 */
internal suspend fun saveWeatherCity(
    typed: String?,
    feed: WeatherCoordinator,
    settings: WeatherSettings,
): String {
    val query = typed?.trim().orEmpty()
    if (query.isEmpty()) {
        settings.setCity(null, null)
        return "Fallback city cleared."
    }
    val place = feed.resolveCity(query)
    settings.setCity(query, place)
    return if (place == null) {
        "\"$query\" not found."
    } else {
        "Fallback: ${placeLabel(place)}"
    }
}

/** "Munich, Bavaria, Germany" — which of the several Münchs this app picked, in full. */
internal fun placeLabel(place: Place): String =
    listOfNotNull(place.name, place.admin1, place.country).joinToString(", ")

/**
 * The feed in one line: when it last reached the unit, which position it used, and what
 * it called that place. A blank is worse than the truth, because the dashboard next to
 * this screen is showing weather the phone sent and the owner cannot tell how old it is.
 */
internal fun weatherStatusLine(status: WeatherStatus, nowMs: Long = System.currentTimeMillis()): String {
    if (status.busy) return "asking the weather service now..."
    val pushed = status.lastPushAt
    if (pushed == null || pushed == WeatherSettings.NO_EPOCH) return "not fed yet"
    val ageMin = ((nowMs - pushed).coerceAtLeast(0L)) / 60_000L
    val source = when (status.source) {
        PositionSource.FIX -> "unit GNSS"
        PositionSource.CACHE -> "last known position"
        PositionSource.CITY -> "typed city"
        null -> "unknown position"
    }
    return "fed ${status.label?.takeIf { it.isNotBlank() } ?: "no name"} | $source | ${ageMin} min ago"
}
