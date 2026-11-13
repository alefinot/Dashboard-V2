package com.alefinot.dashboardpp.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alefinot.dashboardpp.ble.OtaController
import com.alefinot.dashboardpp.ble.OtaErrors
import com.alefinot.dashboardpp.ble.OtaImage
import com.alefinot.dashboardpp.ble.OtaStage
import com.alefinot.dashboardpp.ble.OtaView
import com.alefinot.dashboardpp.update.FirmwareFileState
import com.alefinot.dashboardpp.update.FirmwareRelease
import com.alefinot.dashboardpp.update.FirmwareReleases
import com.alefinot.dashboardpp.update.ReleaseList
import com.alefinot.dashboardpp.update.fileError
import com.alefinot.dashboardpp.update.listError
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudSelectMenu
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import java.util.Locale

/**
 * The firmware card: put a firmware file on the unit over Bluetooth.
 *
 * It lives on App Settings since 2026-11-09 — it is the one control that *changes* the
 * unit rather than adjusting it, and it works (as a file fetcher) whether or not a
 * dashboard is linked. The settings page next door kept the settings.
 *
 * The card is two bars and a dropdown since 2026-11-13, at the maintainer's request —
 * "merge all the firmware update features into 2 buttons". Each bar is one door a
 * firmware file comes through, and each bar carries its file all the way:
 *
 *  - the **release door** fetches the dropdown's version from GitHub (phone to GitHub
 *    over HTTPS, `update/FirmwareReleases.kt`), then turns green and says INSTALL;
 *  - the **file door** reads a `.bin` the phone already holds through the document
 *    picker, then turns green and says INSTALL.
 *
 * Pressing INSTALL runs the safety once — the same version as the unit's, no version
 * marker, or a downloaded file whose own `DPPVER=` marker disagrees with the release it
 * was fetched as (rule 3: that marker and the CRC are all an unsigned image can be
 * checked by) — and the second press starts the flash. While bytes move, the bar *is*
 * the progress: its fill is the fraction and its word carries the stats; a long press
 * stops the install, and the spare-slot promise below the buttons says what that costs
 * (nothing: the unit keeps running what it was).
 *
 * Which bar is holding the file is read off the controller, not off a card-local guess:
 * `FirmwareReleases` hands a downloaded file in under `"<asset> · <release>"`
 * ([DOWNLOAD_TAG]), so the attribution survives leaving the screen and coming back.
 * The transfer itself still lives in [OtaController] — it outlives this composable, and
 * a progress bar that disappears when you scroll is a progress bar that makes people
 * press the button twice. The list fetch and the download live there for the same
 * reason; this file draws state and presses no clocks of its own
 * (`OtaStatusPollStaysQuietTest` keeps it that way).
 */

/** What a downloaded file is handed to the controller as: `"<asset> · <release title>"`.
 * A bar's held file is a download's iff its name starts with this. */
private val DOWNLOAD_TAG = "${FirmwareReleases.ASSET_NAME} · "

@Composable
internal fun FirmwareUpdateControl(
    /** The version the unit reported over the link, null before it answers. */
    unitVersion: String?,
    /** True while a Bluetooth link is up — without one the card fetches but cannot install. */
    linked: Boolean,
    onNote: (String) -> Unit,
) {
    val context = LocalContext.current
    val view by OtaController.view.collectAsState()
    val list by FirmwareReleases.list.collectAsState()
    val fileState by FirmwareReleases.file.collectAsState()

    // The second press of INSTALL, for whenever the card cannot do the judging itself:
    // the same version as the unit's, no marker to compare, or a downloaded file whose
    // own marker disagrees with the release it was fetched as. One flag, cleared as soon
    // as it is acted on, so the bar cannot fire twice afterwards.
    var armed by remember { mutableStateOf(false) }

    /** The dropdown's highlighted release — a choice, not yet a file. */
    var chosen by remember { mutableStateOf<FirmwareRelease?>(null) }

    // Ask for the list the first time the card is drawn; the object's TTL decides
    // whether that call actually leaves the phone.
    LaunchedEffect(Unit) { FirmwareReleases.refresh() }

    // Default the dropdown to the newest release once the list lands.
    LaunchedEffect(list) {
        val ready = list as? ReleaseList.Ready ?: return@LaunchedEffect
        if (chosen == null) chosen = ready.releases.firstOrNull()
    }

    val unitRunning = unitVersion.orEmpty()

    // Whether the phone's battery manager is free to kill this app while it is in the
    // background — the bench-measured way an install dies at 40 %: INSTALL, pocket the
    // phone, and a power-managed Android trims the process mid-transfer (MIUI most of
    // all; the 2026-11-13 bench has the `has died: prev LAST` line to prove it). The
    // answer changes on a system page this card opens, so it is re-read on every resume
    // — the same trick the app-update card uses for the install-unknown-apps grant.
    var grantTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) grantTick++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val batteryExempt = remember(grantTick) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            onNote("no file picked")
            return@rememberLauncherForActivityResult
        }
        // The pick grants temporary read access. The transfer can run for minutes and survive
        // this tab closing, so ask for the persistable grant while it is available; where the
        // provider will not give one (some cloud documents), the read that starts immediately
        // still works and only a later resume could be left without access.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        armed = false
        OtaController.pick(context, uri, pickedName(context, uri))
    }

    // ── Whose file the card is holding, read off the controller's state ──────────────
    val fetching = fileState as? FirmwareFileState.Downloading
    val busy = OtaController.busy() || fetching != null
    val staged = view.stage != OtaStage.Idle
    val fromDownload = view.fileName.startsWith(DOWNLOAD_TAG)
    val heldRelease = (fileState as? FirmwareFileState.Ready)?.release?.takeIf { fromDownload }
    val downloadHolds = staged && fromDownload
    val canStart = OtaController.canStart()
    val needsLink = "the file is on the phone - installing needs the Bluetooth link"

    fun clearCard() {
        armed = false
        FirmwareReleases.resetFile()
        OtaController.reset()
    }

    // A successful update must not leave the card wearing its work clothes (maintainer,
    // 2026-11-13: "the app is stuck on RESUME when an update is successful - the state
    // of the button should reset"). The proof that the work is over is the link itself:
    // once the unit answers with the very version the staged file declares, whatever
    // the stage last said, the flash has landed and the unit rebooted into it - so the
    // card hands its file back and returns to waiting by itself. A transfer that
    // paused mid-image cannot end that way unless it completed, since a unit still in
    // its install answers nothing but `ping` and `ota.*` until the window closes.
    // `Ready` is excluded on purpose: a file merely staged has earned no assumption,
    // and a same-version reflash stays a deliberate CONFIRM away, not a vanished file.
    LaunchedEffect(unitVersion, view.stage, view.image?.versionTag) {
        val tag = view.image?.versionTag
        val attempted = when (view.stage) {
            OtaStage.Paused, OtaStage.Failed, OtaStage.Installed -> true
            else -> false
        }
        if (attempted && !OtaController.busy() && tag != null && tag == unitVersion) {
            clearCard()
        }
    }

    /** End what is open on the unit (and say so), or hand back bytes not yet sent. */
    fun discardHeld() {
        armed = false
        FirmwareReleases.resetFile()
        OtaController.drop(context)
    }

    fun tapInstall() {
        if (view.stage == OtaStage.Failed && !canStart) {
            clearCard()
            return
        }
        if (!linked) {
            onNote(needsLink)
            return
        }
        if (armed) {
            armed = false
            OtaController.start(context)
            return
        }
        if (needsConfirmation(view, unitRunning, heldRelease)) {
            armed = true
            onNote(confirmReason(view, unitRunning, heldRelease))
        } else {
            OtaController.start(context)
        }
    }

    /** The held bar's tap: whatever the stage means — stats are not a verb, INSTALL and CLEAR are. */
    fun tapHeld() {
        when (view.stage) {
            OtaStage.Handshaking, OtaStage.Sending, OtaStage.Verifying ->
                onNote("hold the bar down to stop the install")
            OtaStage.Paused ->
                if (linked) OtaController.start(context) else onNote(needsLink)
            OtaStage.Ready, OtaStage.Failed -> tapInstall()
            OtaStage.Installed -> clearCard()
            else -> Unit
        }
    }

    /** The held bar's long press: stop what is running, or clear what finished. */
    fun longHeld() {
        when (view.stage) {
            OtaStage.Reading, OtaStage.Handshaking, OtaStage.Sending,
            OtaStage.Verifying, OtaStage.Paused -> discardHeld()
            else -> clearCard()
        }
    }

    val heldUi = heldDoorUi(view, armed, canStart, linked, heldRelease?.title)
    val heldLong = when (view.stage) {
        OtaStage.Reading -> null
        OtaStage.Handshaking, OtaStage.Sending, OtaStage.Verifying -> "stop the install"
        OtaStage.Paused -> "give the file back and go back to waiting"
        OtaStage.Ready -> "give the file back and go back to waiting"
        else -> "put this card back to waiting"
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // ── The GitHub dropdown: which released version the first bar is about ────────
        val releases = (list as? ReleaseList.Ready)?.releases.orEmpty()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            VersionMenu(
                chosen = chosen,
                releases = releases,
                unitVersion = unitRunning,
                placeholder = when (list) {
                    ReleaseList.Loading -> "reading the release list…"
                    is ReleaseList.Failed -> "release list failed"
                    else -> "no released firmware"
                },
                modifier = Modifier.weight(1f),
                onPick = { chosen = it },
            )
            UpdateReloadAction(
                description = "ask GitHub again for the released firmware versions",
                enabled = list !is ReleaseList.Loading,
                onClick = { FirmwareReleases.refresh(force = true) },
            )
        }

        when (val l = list) {
            is ReleaseList.Failed -> Text(
                listError(l.code),
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            is ReleaseList.Ready -> if (l.releases.isEmpty()) {
                Text(
                    "the repository has no released firmware yet.",
                    style = HudeCfgNote(),
                    color = HudColors.TextDim,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            else -> Unit
        }

        chosen?.let { release ->
            val upToDate = release.version == unitRunning
            Text(
                if (upToDate) "${release.title} - Up to date" else releaseLine(release),
                style = HudeCfgNote(),
                color = if (upToDate) HudColors.Green else HudColors.TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        // ── Door 1: a released version. Fetch it, then this same bar installs it ──────
        Spacer(Modifier.height(8.dp))
        val fetchFrac = fetching?.let { updateFraction(it.release.size, it.got) }
        val pickRel = chosen
        if (downloadHolds) {
            UpdateDoorBar(
                glyph = heldUi.glyph,
                label = heldUi.label(heldRelease?.title ?: "the released file"),
                description = heldUi.description,
                ink = heldUi.ink,
                enabled = heldUi.enabled,
                progress = heldUi.progress,
                wash = heldUi.wash,
                longDescription = heldLong,
                onLongPress = { longHeld() },
                onClick = { tapHeld() },
            )
        } else {
            UpdateDoorBar(
                glyph = HudIcons.ImportFile,
                label = when {
                    fetching != null ->
                        "downloading ${fetching.release.title} · ${updatePercent(fetchFrac ?: 0f)}"
                    pickRel != null -> "download ${pickRel.title}"
                    else -> "download"
                },
                description = when {
                    fetching != null ->
                        "fetching ${fetching.release.title} out of its GitHub release, into this phone"
                    pickRel != null ->
                        "fetch ${pickRel.title} out of its GitHub release and hold it on this phone" +
                            if (staged) " - it replaces the file the other bar holds" else ""
                    else -> "pick a released version above first"
                },
                ink = if (fetching != null) HudColors.Amber
                else if (pickRel != null && !busy) HudColors.Cyan else HudColors.TextDim,
                enabled = pickRel != null && !busy,
                progress = fetchFrac,
                onClick = {
                    pickRel?.let {
                        armed = false
                        FirmwareReleases.fetch(context, it)
                    }
                },
            )
        }
        if (fetching != null) {
            Text(
                "downloading ${fetching.release.title} · ${mb(fetching.got)} of " +
                    "${mb(fetching.release.size)}",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        when (val f = fileState) {
            is FirmwareFileState.Failed -> Text(
                fileError(f.code),
                style = HudeCfgNote(),
                color = HudColors.Red,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            else -> Unit
        }

        // ── Door 2: a file this phone already holds. Pick it, then this bar installs it ──
        Spacer(Modifier.height(8.dp))
        if (view.stage != OtaStage.Idle && !fromDownload) {
            UpdateDoorBar(
                glyph = heldUi.glyph,
                label = heldUi.label(view.fileName.take(24)),
                description = heldUi.description,
                ink = heldUi.ink,
                enabled = heldUi.enabled,
                progress = heldUi.progress,
                wash = heldUi.wash,
                longDescription = heldLong,
                onLongPress = { longHeld() },
                onClick = { tapHeld() },
            )
        } else {
            UpdateDoorBar(
                glyph = HudIcons.File,
                label = "choose file",
                description = "use a firmware file this phone already holds" +
                    if (staged) " - it replaces the file the card is holding" else "",
                ink = if (busy) HudColors.TextDim else HudColors.Cyan,
                enabled = !busy,
                onClick = {
                    armed = false
                    picker.launch(arrayOf("*/*"))
                },
            )
        }

        // ── What the card is true about, beside the two bars ──────────────────────────
        if (!batteryExempt) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "phone battery management can stop an install that goes to the background.",
                    style = HudeCfgNote(),
                    color = HudColors.Amber,
                    modifier = Modifier.weight(1f),
                )
                UpdateBeginAction(
                    caption = "ALLOW",
                    description = "ask the phone to stop killing this app while it works in the background",
                    enabled = true,
                ) { askBatteryExemption(context) }
            }
        }

        if (!linked && staged) {
            Text(
                needsLink + ".",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        if (staged) {
            Text(
                summaryLine(view),
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "the unit is running " + (unitRunning.ifEmpty { "a version it has not reported" }),
                style = HudeCfgNote(),
                color = if (unitRunning.isNotEmpty() && view.image?.versionTag == unitRunning) {
                    HudColors.Amber
                } else {
                    HudColors.TextDim
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (view.stage == OtaStage.Sending || view.stage == OtaStage.Verifying ||
            view.stage == OtaStage.Paused || view.stage == OtaStage.Installed
        ) {
            // The held bar's fill *is* the transfer bar now; this line carries the rest of
            // the numbers a bench or a driveway diagnosis reads.
            Text(
                progressLine(view),
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        // The honest cost of a 1.5 MB transfer: about a minute of airtime the app has to
        // survive. `OtaGuardService` keeps the process and the CPU alive; the sentence is
        // what keeps the person from being the one who ends it.
        if (view.stage == OtaStage.Sending || view.stage == OtaStage.Handshaking ||
            view.stage == OtaStage.Verifying
        ) {
            Text(
                "the phone is being kept awake for the install - do not close the app until the unit restarts.",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        val error = view.error
        val note = view.note
        when {
            error != null -> Text(
                OtaErrors.explain(error),
                style = HudeCfgNote(),
                color = if (OtaErrors.needsAction(error)) HudColors.Red else HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            note != null -> Text(
                note,
                style = HudeCfgNote(),
                color = if (view.stage == OtaStage.Installed) HudColors.Green else HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            armed -> Text(
                "press INSTALL again to flash anyway",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/**
 * What the bar holding the staged file looks like right now — one `when` over the
 * controller's stage, shared by both doors (only the title word differs).
 *
 * The ink is the state, in the card's existing language: amber while something waits or
 * works, green when the file is ready to write or has been written, red only when the
 * file itself was the problem. `wash` marks the two green moments, so the bar that
 * "changed colour" is the bar a finger should press next.
 */
private class HeldDoorUi(
    /** Fills the bar's own word; gets the staged file's title (`V2.0.3`, `dpp.bin`). */
    val label: (String) -> String,
    val description: String,
    val ink: Color,
    val enabled: Boolean,
    val progress: Float?,
    val glyph: ImageVector,
    val wash: Color?,
)

private fun heldDoorUi(
    view: OtaView,
    armed: Boolean,
    canStart: Boolean,
    linked: Boolean,
    heldTitle: String?,
): HeldDoorUi = when (view.stage) {
    OtaStage.Reading -> HeldDoorUi(
        label = { "reading the file" },
        description = "the phone is reading the file - nothing has reached the unit yet",
        ink = HudColors.Amber,
        enabled = false,
        progress = null,
        glyph = HudIcons.ImportFile,
        wash = null,
    )
    OtaStage.Handshaking -> HeldDoorUi(
        label = { "opening the unit's slot" },
        description = "the unit is opening its spare flash slot; hold the bar down to stop",
        ink = HudColors.Amber,
        enabled = true,
        progress = null,
        glyph = HudIcons.Stop,
        wash = null,
    )
    OtaStage.Sending -> HeldDoorUi(
        label = { "sending ${view.percent()}% · ${rateText(view)}" },
        description = "the image is on its way to the unit; hold the bar down to stop",
        ink = HudColors.Amber,
        enabled = true,
        progress = view.fraction(),
        glyph = HudIcons.Stop,
        wash = null,
    )
    OtaStage.Verifying -> HeldDoorUi(
        label = { "the unit is checking the image" },
        description = "the unit is counting the bytes and checking its own gates; hold to stop",
        ink = HudColors.Amber,
        enabled = true,
        progress = view.fraction(),
        glyph = HudIcons.Stop,
        wash = null,
    )
    OtaStage.Paused -> HeldDoorUi(
        label = { if (linked) "resume ${view.percent()}%" else "paused · needs the link" },
        description = "continue the install from the offset the unit confirmed",
        ink = HudColors.Amber,
        enabled = true,
        progress = view.fraction(),
        glyph = HudIcons.Play,
        wash = null,
    )
    OtaStage.Installed -> HeldDoorUi(
        label = { "installed · tap to clear" },
        description = "the unit restarted into the new image; tap to put this card back to waiting",
        ink = HudColors.Green,
        enabled = true,
        progress = 1f,
        glyph = HudIcons.Check,
        wash = HudColors.Green.copy(alpha = 0.12f),
    )
    OtaStage.Failed -> if (canStart) HeldDoorUi(
        label = { "retry the flash" },
        description = "the last attempt died in the air, not in the file - press again to send it again",
        ink = HudColors.Amber,
        enabled = true,
        progress = view.fraction(),
        glyph = HudIcons.Play,
        wash = null,
    ) else HeldDoorUi(
        label = { if (view.error == "cancelled") "stopped · tap to clear" else "failed · tap to clear" },
        description = "tap to put this card back to waiting; the unit is unchanged",
        ink = if (view.error == "cancelled") HudColors.Amber else HudColors.Red,
        enabled = true,
        progress = null,
        glyph = HudIcons.Close,
        wash = null,
    )
    else -> if (armed) HeldDoorUi(
        label = { "confirm flash" },
        description = "the card could not prove this file is right for this unit - press again to flash it anyway",
        ink = HudColors.Amber,
        enabled = true,
        progress = null,
        glyph = HudIcons.Play,
        wash = HudColors.Amber.copy(alpha = 0.14f),
    ) else HeldDoorUi(
        label = { title -> "install $title" },
        description = "flash ${heldTitle?.plus(" into") ?: "this file into"} the unit's spare flash slot" +
            " - a failed install leaves the running firmware untouched" +
            if (!linked) " · INSTALL needs the Bluetooth link" else "",
        ink = HudColors.Green,
        enabled = true,
        progress = null,
        glyph = HudIcons.Play,
        wash = HudColors.Green.copy(alpha = 0.12f),
    )
}

/**
 * The version dropdown. The maintainer asked for the menu by name on 2026-11-09, and the
 * firmware list now uses the same HUD select as the vehicle-type control: a field-shaped
 * trigger and a three-row scrollable sheet rather than Material's default popup.
 *
 * The row the unit is already running keeps its amber "on the unit" wording; the chosen
 * row is marked with the shared cyan check.
 */
@Composable
private fun VersionMenu(
    chosen: FirmwareRelease?,
    releases: List<FirmwareRelease>,
    unitVersion: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onPick: (FirmwareRelease) -> Unit,
) {
    HudSelectMenu(
        triggerLabel = chosen?.title ?: placeholder,
        items = releases,
        label = { release ->
            release.title + if (release.version == unitVersion) " · on the unit" else ""
        },
        isSelected = { release -> release == chosen },
        onPick = onPick,
        modifier = modifier,
        enabled = releases.isNotEmpty(),
        menuModifier = Modifier.widthIn(min = 240.dp, max = 320.dp),
        labelColor = { release ->
            when {
                release.version == unitVersion -> HudColors.Amber
                release == chosen -> HudColors.Cyan
                else -> HudColors.TextPrimary
            }
        },
        showsCheck = { release -> release == chosen },
    )
}

/** The chosen release in one line, with its size. "Already on the unit" gets its own state line. */
private fun releaseLine(release: FirmwareRelease): String {
    val size = if (release.size > 0) " · " + mb(release.size) else ""
    return "${release.title} ($size)".replace("( · ", "(")
}

/**
 * The picked file's own name, the way the provider that handed it over spells it.
 *
 * `uri.lastPathSegment` is a document id, not a name. For a file picked out of Downloads on the
 * test tablet it came back as `msf:152` (a MediaStore download id), and that is what sat in the
 * card's summary line, where a person is supposed to be able to see which file they chose. The
 * provider knows the display name, so it is asked first; the path segment is only the fallback
 * for a provider that will not answer the query.
 */
private fun pickedName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment ?: "firmware.bin"
}

/**
 * Whether this file deserves a second press before it is sent.
 *
 * Three cases, all honest: the image declares the version the unit is already running
 * (so the transfer will change nothing and probably means the wrong release was
 * picked), it declares no version at all (so the card cannot warn about anything, and
 * the driver is flying blind), or it was downloaded as one release and says it is
 * another (a stale CDN copy, a mis-uploaded asset — the one machine-to-machine check
 * this app can run on an unsigned image, rule 3).
 */
private fun needsConfirmation(
    view: OtaView,
    unitVersion: String,
    expected: FirmwareRelease?,
): Boolean {
    val tag = view.image?.versionTag ?: return true
    if (unitVersion.isNotEmpty() && tag == unitVersion) return true
    return expected != null && tag != expected.version
}

private fun confirmReason(view: OtaView, unitVersion: String, expected: FirmwareRelease?): String {
    val tag = view.image?.versionTag
    return when {
        tag == null -> "that file carries no version marker"
        unitVersion.isNotEmpty() && tag == unitVersion ->
            "that file says it is $tag and the unit is already running $tag"
        expected != null && tag != expected.version ->
            "GitHub says ${expected.title} and the file says it is $tag"
        else -> "that file is not what this card was asked to send"
    }
}

/** The picked file, in one line. */
private fun summaryLine(view: OtaView): String {
    val img: OtaImage? = view.image
    val size = if (img != null) mb(img.size.toLong()) + " · " + img.size.toString() + " bytes" else "size unknown"
    val crc = img?.crc32?.let { String.format(Locale.US, "CRC-32 %08X", it) } ?: "crc —"
    val tag = img?.versionTag?.let { "says it is $it" } ?: "no version marker"
    return "${view.fileName} · $size · $crc · $tag"
}

/** What the transfer is doing, with the numbers a person actually watches. */
private fun progressLine(view: OtaView): String {
    val stage = when (view.stage) {
        OtaStage.Verifying -> "verifying"
        OtaStage.Installed -> "installed — restarting"
        OtaStage.Paused -> "paused"
        else -> "sending"
    }
    val eta = view.etaSeconds()
    val tail = when {
        view.stage != OtaStage.Sending -> ""
        eta == null -> ""
        else -> " · about ${eta}s left"
    }
    // A finished install has to carry the numbers too: this line is the only place a field install
    // leaves a duration, a rate and a write count behind, and there is no bench log to read after.
    val finished = view.stage == OtaStage.Installed || view.stage == OtaStage.Verifying
    val writeCount = if (finished) " · ${view.writes} writes" else ""
    return "$stage · ${view.percent()}% · ${mb(view.acked.toLong())} of ${mb(view.total.toLong())}" +
        " · ${rateText(view)}" +
        " · ${String.format(Locale.US, "%.1f", view.writesPerSecond)} writes/s" +
        " · ${String.format(Locale.US, "%.1f", view.elapsedSeconds)}s elapsed$writeCount$tail"
}

/** The install's rolling rate, the number the bar's own word carries mid-transfer. */
private fun rateText(view: OtaView): String =
    "${String.format(Locale.US, "%.1f", view.bytesPerSecond / 1024.0)} KB/s"

private fun mb(bytes: Long): String =
    String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))

/**
 * Opens the system page that exempts this app from background battery management
 * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, with a `package:` URI — the form Google
 * recommends, and the one MIUI's app-battery-saver screen hooks into). Not every ROM will
 * open that exact intent for a third-party app, so the fallback is the app's own details
 * page, where the same restriction can be lifted by hand.
 */
private fun askBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isFailure) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(details) }
    }
}
