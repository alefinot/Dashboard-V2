package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.service.TripLogService
import com.alefinot.dashboardpp.trips.RecorderSettings
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
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

        // The page's switches live in cards with the sentence that explains them, not on
        // bare rows whose label is at the left edge of the screen and whose switch is at the
        // right one. `label { color: var(--accent-cyan) }` is still the page's own colour.
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

        Column(
            Modifier.fillMaxWidth().hudCard()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CardHead("Recorded Drives")
            Text(
                "One CSV per ESP power session, written to the app's own storage " +
                    "(no SD-card permission). The recorder samples once a second and " +
                    "stands down while the Web UI is on screen. Open drives are never " +
                    "deleted. Share a trip as CSV or GPX from the Trips tab.",
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

/**
 * One label/value pair as its own `.card`: the page's `.perf-label` over `.perf-value`.
 *
 * The three of them used to be stacked inside one card, so "UNIT" sat above "MOTOSBU" above
 * "FIRMWARE" above the version, and reading the app's own number meant scrolling past two
 * other facts. Across the column they are one line each.
 */
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
