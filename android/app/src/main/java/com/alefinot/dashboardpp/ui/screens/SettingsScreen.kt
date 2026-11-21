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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.alefinot.dashboardpp.R
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
import java.util.Locale
import com.alefinot.dashboardpp.ui.components.HudAccordion
import com.alefinot.dashboardpp.ui.components.HudAccordionBody
import com.alefinot.dashboardpp.ui.components.HudAccordionHeader
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.HudSelectMenu
import com.alefinot.dashboardpp.ui.components.HudSlider
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.components.hudBytes
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudeCfgLive
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.viewmodel.TripsViewModel
import com.alefinot.dashboardpp.weather.CityFallback
import com.alefinot.dashboardpp.weather.Place
import com.alefinot.dashboardpp.weather.PlaceLanguages
import com.alefinot.dashboardpp.weather.PlaceNames
import com.alefinot.dashboardpp.weather.PositionSource
import com.alefinot.dashboardpp.weather.WeatherCoordinator
import com.alefinot.dashboardpp.weather.WeatherRuntime
import com.alefinot.dashboardpp.weather.WeatherSettings
import com.alefinot.dashboardpp.weather.WeatherStatus
import com.alefinot.dashboardpp.weather.WeatherWriter

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
 *
 * Since 2026-11-15 the page is organised like the dashboard settings page next door:
 * the three blocks below the identity cards - Updates, Trips & Storage, Weather Feed -
 * are `details` accordions with badges and chevrons, and [SettingsLayout.tapOpen]'s
 * rule applies: one open at a time, tapping the open header closes it. That rule is
 * the whole reason the settings page reads well on a phone, and it earns the same
 * keep here - three full cards stacked made this page a long scroll to reach the one
 * switch the owner came for.
 */
@Composable
fun SettingsScreen(
    vm: ConnectionViewModel,
    unit: String?,
    version: String?,
    /** True while a Bluetooth link is up — the firmware card installs only over one. */
    connected: Boolean,
    trips: TripsViewModel,
) {
    val context = LocalContext.current
    var storageLine by remember { mutableStateOf("") }
    var showConfirmClear by remember { mutableStateOf(false) }
    val settings = remember { RecorderSettings(context) }
    val scope = rememberCoroutineScope()
    val recordingEnabled by settings.recordingEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val motionOn by settings.motionEnabled.collectAsStateWithLifecycle(initialValue = true)
    // The route quality slider. `routeDrag` holds the figure mid-drag: the store is only
    // written when the thumb is released, so a drag cannot rewrite the DataStore on every
    // frame, while the label beside the rail still follows the finger.
    val routeQuality by settings.routeQuality.collectAsStateWithLifecycle(initialValue = 100)
    var routeDrag by remember { mutableStateOf<Int?>(null) }
    // The weather feed has its own store and its own coordinator: the switch and the
    // typed city are the owner's, the schedule belongs to the process-wide feed that the
    // link and the trip service are also waking (one schedule, or the unit gets two).
    val weatherSettings = remember { WeatherSettings(context) }
    val weatherFeed = remember { WeatherRuntime.coordinator(context) }
    val weatherOn by weatherSettings.enabled.collectAsStateWithLifecycle(initialValue = true)
    val weatherCity by weatherSettings.cityFallback
        .collectAsStateWithLifecycle(initialValue = CityFallback(null, null))
    val placeLang by weatherSettings.language
        .collectAsStateWithLifecycle(initialValue = PlaceLanguages.FALLBACK_TAG)
    val placeOptions = remember { PlaceLanguages.ALL }
    val weatherStatus by weatherFeed.status.collectAsStateWithLifecycle()
    // null means "not edited yet", which is what lets the field show the stored city
    // once the store has answered instead of starting out empty and staying that way.
    var cityEdit by remember { mutableStateOf<String?>(null) }
    var weatherLine by remember { mutableStateOf("") }
    /**
     * The places the last city search found, when it found more than one. Empty is the
     * normal state: it means the search settled the question (one answer, or none) and
     * there is nothing to choose. It holds **no** saved city — a search that comes back
     * ambiguous stores nothing, so the fallback the owner had before keeps feeding the
     * ladder while they decide.
     */
    var cityChoices by remember { mutableStateOf<List<Place>>(emptyList()) }
    // Hoisted out of the Updates body on 2026-11-15: an accordion body is only composed
    // while its group is open, so a note that lives inside one is wiped every time the
    // owner opens the group next door. The lines that report what a press did belong to
    // the page, not to the fold they happen to be printed under.
    var firmwareLine by remember { mutableStateOf("") }
    /** The one group this page holds open — the dashboard settings' tap rule. */
    var openGroup by remember { mutableStateOf<String?>(null) }
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
        // The page's h2 in its gradient, centred. The `.ip-banner` pill that used to sit under
        // it ("app 1.2.0 · <unit name>") is gone — the same two facts already stand in the
        // UNIT / FIRMWARE / APP cards a hand's width below, and the owner asked for the
        // duplicate off the header.
        HudPageHeader(title = "App Settings")
        Spacer(Modifier.height(16.dp))

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
        // One dropdown, two targets: the unit's firmware and this app's own APK. They
        // merged on 2026-11-11 because they answer one question - *what is newer than
        // what I am running, and how does it get in?* - and every half kept its own
        // honesty sentence: the spare-slot promise above the firmware buttons, the
        // in-place-and-keeps-data promise above the app ones. A failed press on either
        // half changes nothing on either device.
        GroupCard(
            title = "Updates",
            badge = R.drawable.ic_app_updates,
            open = openGroup == "updates",
            onToggle = { openGroup = SettingsLayout.tapOpen(openGroup, "updates") },
        ) {
            Text("FIRMWARE", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(6.dp))
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
            AppUpdateControl(installed = appVersion)
        }

        // ── Trips & Storage ──────────────────────────────────────────────
        // One dropdown, because the three questions are one story: is the drive being
        // recorded, where does it go, and what is already in there. They used to be
        // three cards - the switches, the folder, and the actions over the files - and
        // the folder answer, the one that decides whether a drive can be found in a
        // file manager at all, sat under its own heading at the far end of the page.
        // The page's switches live in cards with the sentence that explains them, not on
        // bare rows whose label is at the left edge of the screen and whose switch is at the
        // right one. `label { color: var(--accent-cyan) }` is still the page's own colour.
        GroupCard(
            title = "Trips & Storage",
            badge = R.drawable.ic_app_trips,
            open = openGroup == "trips",
            onToggle = { openGroup = SettingsLayout.tapOpen(openGroup, "trips") },
        ) {
            // Only START writes this switch (2.0.7/180): ending a drive is a decision about
            // the one that just finished, while this row decides what the next connection
            // does - so the STOP that turned it off was switching off the next ride.
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
            // How much of the drive's line the LIVE map keeps thinning away on a long ride
            // (maintainer, 2026-11-19: "make it go full quality up to 10000km at max
            // quality"). It touches nothing that is written down: every trip file holds
            // every fix, and a sealed drive's detail page draws from the file. The store
            // is written on release, not per frame - the drag lives in `routeDrag` until
            // the thumb lifts.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ROUTE QUALITY",
                    color = HudColors.Cyan,
                    style = HudeFormLabel(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${routeDrag ?: routeQuality} %",
                    color = HudColors.TextPrimary,
                    style = HudePerfValue(),
                )
            }
            HudSlider(
                value = (routeDrag ?: routeQuality).toFloat(),
                onValueChange = { routeDrag = it.toInt() },
                valueRange = 0f..100f,
                steps = 19,
                onValueChangeFinished = {
                    val dragged = routeDrag
                    if (dragged != null && dragged != routeQuality) {
                        scope.launch { settings.setRouteQuality(dragged) }
                    }
                    routeDrag = null
                },
            )

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
                    glyph = HudIcons.Cog,
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
                    glyph = HudIcons.Bin,
                ) { showConfirmClear = true }
            }
            if (storageLine.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(storageLine, color = HudColors.TextMuted, style = HudeCfgLive())
            }
        }

        // ── Weather feed ─────────────────────────────────────────────────
        GroupCard(
            title = "Weather Feed",
            badge = R.drawable.ic_app_weather,
            open = openGroup == "weather",
            onToggle = { openGroup = SettingsLayout.tapOpen(openGroup, "weather") },
        ) {
            ToggleRow(
                label = "PUSH WEATHER",
                on = weatherOn,
                toggleLabel = "push weather",
            ) { on -> scope.launch { weatherSettings.setEnabled(on) } }
            Spacer(Modifier.height(12.dp))
            // The card's two forced pushes, split by which position they fetch for:
            // REFRESH asks the unit where the car is, PUSH NOW (below) uses the city field
            // above it. Neither waits for the 5-minute schedule, and neither falls back to
            // the other's position — a button that names a source delivers that source or
            // says plainly why it could not.
            HudButton(
                "REFRESH",
                modifier = Modifier.fillMaxWidth(),
                style = HudButtonStyle.SECONDARY,
                enabled = weatherStatus.unitKey != null,
                glyph = HudIcons.Reset,
            ) {
                val key = weatherStatus.unitKey ?: return@HudButton
                scope.launch { weatherFeed.refresh(key) }
            }
            Spacer(Modifier.height(12.dp))
            // Place names, both providers, in one choice. The language is what makes the
            // gazetteer *find* the place the owner typed — "Roma" in English answers with
            // the country Romania, in Italian it answers with Rome — and it also renames
            // the label the unit's panel prints, because the reverse lookup is asked in the
            // same tongue. Only languages whose names the panel's font can draw are offered
            // (see PlaceLanguages), so this row cannot produce a name the dash cannot show.
            Text("PLACE LANGUAGE", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(4.dp))
            HudSelectMenu(
                triggerLabel = PlaceLanguages.endonymOf(placeLang),
                items = placeOptions,
                label = { it.endonym },
                isSelected = { it.tag == placeLang },
                onPick = { picked ->
                    scope.launch {
                        weatherLine = applyPlaceLanguage(
                            picked.tag, weatherFeed, weatherSettings, weatherCity,
                        )
                        cityChoices = emptyList()
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Text("FALLBACK CITY", color = HudColors.Cyan, style = HudeFormLabel())
            Spacer(Modifier.height(4.dp))
            HudInput(
                value = cityEdit ?: (weatherCity.typed ?: ""),
                onValueChange = { cityEdit = it },
                modifier = Modifier.fillMaxWidth(),
                style = HudInputStyle.BARE,
                placeholder = "Used when the unit has no GPS fix",
                onDone = {
                    val typed = cityEdit ?: weatherCity.typed
                    scope.launch {
                        val found = searchWeatherCity(typed, weatherFeed, weatherSettings)
                        weatherLine = found.line
                        cityChoices = found.choices
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    "SAVE CITY",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.SECONDARY,
                    glyph = HudIcons.Check,
                ) {
                    val typed = cityEdit ?: weatherCity.typed
                    scope.launch {
                        val found = searchWeatherCity(typed, weatherFeed, weatherSettings)
                        weatherLine = found.line
                        cityChoices = found.choices
                    }
                }
                HudButton(
                    "PUSH NOW",
                    modifier = Modifier.weight(1f),
                    enabled = weatherStatus.unitKey != null,
                    glyph = HudIcons.Flush,
                ) {
                    val key = weatherStatus.unitKey ?: return@HudButton
                    scope.launch { weatherFeed.pushNow(key) }
                }
            }
            // The gazetteer's answer to a name is a list, and for an ambiguous one the app
            // used to take the top row and store it — which is how the maintainer's phone
            // came to hold the centre of Romania under the word "Roma". Nothing is stored
            // from here until the owner taps a row.
            if (cityChoices.size > 1) {
                Spacer(Modifier.height(8.dp))
                HudSelectMenu(
                    triggerLabel = "PICK THE ONE YOU MEAN",
                    items = cityChoices,
                    label = { placeChoiceLabel(it) },
                    isSelected = { it == weatherCity.place },
                    onPick = { chosen ->
                        val typed = cityEdit ?: weatherCity.typed
                        scope.launch {
                            weatherSettings.setCity(typed?.trim(), chosen)
                            weatherLine = "Fallback: ${placeLabel(chosen)}"
                            cityChoices = emptyList()
                        }
                    },
                    menuModifier = Modifier.fillMaxWidth(),
                )
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

        Spacer(Modifier.height(8.dp))
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
            // Rule 7 holds inside the dialog too: destroy wears the bin.
            confirmGlyph = HudIcons.Bin,
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
                // D4: the button opens a phone screen this app cannot draw, so the cog
                // says "errand into the phone's own settings" and the label stays exact.
                glyph = HudIcons.Cog,
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

/**
 * One dropdown group of the App Settings page — the same `details` sheet the dashboard
 * settings page groups its rows with: [HudAccordion] with a [HudAccordionHeader] band
 * and a [HudAccordionBody]. The badge is drawn into `ic_app_*.xml` by the same script
 * that draws the schema groups' badges, `scripts/icons_to_drawables.py`, so a badge
 * here and its sibling on the unit's page cannot drift apart.
 *
 * The caller owns the open state; the page passes [SettingsLayout.tapOpen]'s rule —
 * one group open at a time, tapping the open header closes it — as `onToggle`. The
 * body is only composed while its group is open, which is why every line of feedback
 * a group prints is remembered above the groups, on the page itself.
 */
@Composable
private fun GroupCard(
    title: String,
    badge: Int,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HudAccordion(open = open, modifier = Modifier.padding(bottom = 12.dp)) {
        HudAccordionHeader(
            title = title,
            badge = badge,
            open = open,
            onClick = onToggle,
        )
        HudAccordionBody(open = open) {
            Column(Modifier.fillMaxWidth().padding(12.dp), content = content)
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
 * What a city search left for the card to do with: the sentence to print under the field,
 * and the rows to offer when the answer is not one place.
 */
internal data class CitySearch(val line: String, val choices: List<Place>)

/**
 * Search the owner's typed city, and store whatever the search settles.
 *
 * The lookup runs here, at save time, and never once per feed: a typed city is one line
 * of text the owner changes once a year, while the schedule asks about it on every pass.
 *
 * It no longer stores the gazetteer's first answer. One answer is stored at once; several
 * come back as [CitySearch.choices] for the owner to choose between, and **nothing is
 * stored in that case** — a working fallback must not be wiped because a search was
 * ambiguous. Zero answers keeps the text with no position behind it, which is what lets the
 * field show what was typed while PUSH NOW knows there is nothing to fetch for it.
 */
internal suspend fun searchWeatherCity(
    typed: String?,
    feed: WeatherCoordinator,
    settings: WeatherWriter,
): CitySearch {
    val query = typed?.trim().orEmpty()
    if (query.isEmpty()) {
        settings.setCity(null, null)
        return CitySearch("Fallback city cleared.", emptyList())
    }
    val found = feed.searchCity(query)
    return when (found.size) {
        0 -> {
            settings.setCity(query, null)
            CitySearch("\"$query\" not found.", emptyList())
        }
        1 -> {
            val place = found[0]
            settings.setCity(query, place)
            CitySearch("Fallback: ${placeLabel(place)}", emptyList())
        }
        else -> CitySearch(
            "\"$query\" is ${found.size} places \u2014 tap the one you mean.",
            found,
        )
    }
}

/**
 * Change the language place names come back in, and bring the saved city with it.
 *
 * The saved city is re-read by its gazetteer id, so the card and the panel both move to the
 * new language instead of the card reading `Monachium` while the unit is being fed
 * `Monaco di Baviera` — the owner does not have to re-type and re-pick a town they already
 * saved just to change the spelling of its name. A row saved before ids were stored cannot
 * be re-named, and the returned line says so rather than failing silently.
 */
internal suspend fun applyPlaceLanguage(
    tag: String,
    feed: WeatherCoordinator,
    settings: WeatherWriter,
    current: CityFallback,
): String {
    val chosen = PlaceLanguages.byTag(tag)?.tag ?: return "That language is not offered."
    settings.setLanguage(chosen)
    val saved = current.place
        ?: return "Place names will be in ${PlaceLanguages.endonymOf(chosen)}."
    if (saved.id == null) {
        return "Place names will be in ${PlaceLanguages.endonymOf(chosen)} \u2014 " +
            "save ${saved.name} once more to re-name it."
    }
    val renamed = feed.renamePlace(saved, chosen)
        ?: return "Place names will be in ${PlaceLanguages.endonymOf(chosen)} \u2014 " +
            "${saved.name} could not be re-read, so it stays as it is."
    settings.setCity(current.typed, renamed)
    // Both providers hand back the *native* name when they have no translation for a place,
    // so a single odd row can still arrive in a script the panel has no glyphs for. Saying
    // it now, at the moment the owner is looking at the card, beats them finding the
    // coordinates on the dash and hunting for the reason.
    val onPanel = PlaceNames.panelLabel(renamed.name) != null
    return "Fallback: ${placeLabel(renamed)}" +
        if (onPanel) "" else " \u00b7 the unit shows its coordinates for that name"
}

/** "Munich, Bavaria, Germany" — which of the several Münchs this app picked, in full. */
internal fun placeLabel(place: Place): String =
    listOfNotNull(place.name, place.admin1, place.country).joinToString(", ")

/**
 * One candidate row, and the part that tells it apart from its neighbour.
 *
 * The number is not decoration: a search for `Roma` answers with two rows that both read
 * "Roma, Veneto, Italy" — one of 33 people, one of 13 — so without the count the owner is
 * asked to choose between identical labels. A row that is not a settlement at all (the
 * kind that only appears when a name has no settlement in the gazetteer) says so instead.
 */
internal fun placeChoiceLabel(place: Place): String {
    val people = place.population
    val hint = when {
        people != null -> String.format(Locale.US, "%,d people", people)
        !place.isSettlement -> "not a town"
        else -> null
    }
    return listOfNotNull(placeLabel(place), hint).joinToString(" \u00b7 ")
}

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
