package com.alefinot.dashboardpp.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudSelectMenu
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import java.util.Locale

/**
 * The firmware card: put a firmware file on the unit over Bluetooth.
 *
 * It lives on App Settings since 2026-11-09 — it is the one control that *changes* the
 * unit rather than adjusting it, and it works (as a file fetcher) whether or not a
 * dashboard is linked. The settings page next door kept the settings.
 *
 * What this card is allowed to promise is bounded by what the unit can do, so it says
 * its rules out loud instead of hiding them behind a progress wheel:
 *
 *  - **which file it is about to send**, by size, by CRC-32, and by the version the
 *    image declares inside itself (`DPPVER=`, `src/config.cpp`). A build folder calls
 *    every image `firmware.bin` and so does every GitHub release, so the name is worth
 *    nothing and the marker is worth a lot — which is also what lets the card check a
 *    downloaded image against the release it came from.
 *  - **which version the unit is running now**, next to it, because "2.0.3 onto a unit
 *    already running 2.0.3" is a question worth asking before 1.5 MB goes over the
 *    air — and because a unit that answers `not_implemented` (a build from before the
 *    Bluetooth installer exists) has to be told as plainly as it can be.
 *  - **that a failed install changes nothing.** The image goes into the unit's spare
 *    flash slot and the unit only switches to it after its own checks pass, so every
 *    failure sentence this card can print ends the same way: the unit is still running
 *    what it was.
 *
 * The file arrives by one of two doors, and the card treats them as one: the dropdown
 * fetches a released image from GitHub (`update/FirmwareReleases.kt` — phone to GitHub
 * over HTTPS, and the only transport to the unit stays GATT), and CHOOSE FILE reads a
 * `.bin` the phone already holds. From the pick onward — probe, gate, transfer — both
 * doors run through [OtaController] unchanged.
 *
 * The transfer itself lives in [OtaController], not here — it outlives this composable,
 * and a progress bar that disappears when you scroll is a progress bar that makes
 * people press the button twice. The list fetch and the download live there for the
 * same reason; this file draws state and presses no clocks of its own
 * (`OtaStatusPollStaysQuietTest` keeps it that way).
 */
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

    // The second press of START. Needed whenever the card cannot do the judging itself:
    // the same version as the unit's, no marker to compare, or a downloaded file whose
    // own marker disagrees with the release it was fetched as. One flag, cleared as soon
    // as it is acted on, so the button cannot fire twice afterwards.
    var armed by remember { mutableStateOf(false) }

    /** The dropdown's highlighted release — a choice, not yet a file. */
    var chosen by remember { mutableStateOf<FirmwareRelease?>(null) }

    /** The release the current file was fetched as, null for a hand-picked file. The
     * cross-check ("GitHub says V2.0.3, the file says it is 2.0.1") compares against this. */
    var expected by remember { mutableStateOf<FirmwareRelease?>(null) }

    // Ask for the list the first time the card is drawn; the object's TTL decides
    // whether that call actually leaves the phone.
    LaunchedEffect(Unit) { FirmwareReleases.refresh() }

    // Default the dropdown to the newest release once the list lands.
    LaunchedEffect(list) {
        val ready = list as? ReleaseList.Ready ?: return@LaunchedEffect
        if (chosen == null) chosen = ready.releases.firstOrNull()
    }

    val unitRunning = unitVersion.orEmpty()

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
        expected = null
        OtaController.pick(context, uri, pickedName(context, uri))
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // ── The GitHub section: pick a version, fetch it, and the file appears below ──
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

        // The fetch door is a full-width bar since 2026-11-13: the strip itself fills with the
        // bytes as they land, so the percent is the control and not a second sentence somewhere
        // under it.
        Spacer(Modifier.height(8.dp))
        val downloading = fileState is FirmwareFileState.Downloading
        val fetching = fileState as? FirmwareFileState.Downloading
        val fetchFrac = fetching?.let { updateFraction(it.release.size, it.got) }
        val pick = chosen
        UpdateFetchBar(
            label = when {
                fetching != null ->
                    "downloading ${fetching.release.title} · ${updatePercent(fetchFrac ?: 0f)}"
                pick != null -> "download ${pick.title}"
                else -> "download"
            },
            description = pick?.let {
                if (downloading) "fetching ${it.title} out of its GitHub release, into this phone"
                else "fetch ${it.title} out of its GitHub release and hold it on this phone"
            } ?: "pick a released version above first",
            enabled = pick != null && !downloading && !OtaController.busy(),
            progress = fetchFrac,
            onClick = {
                pick?.let {
                    armed = false
                    expected = it
                    FirmwareReleases.fetch(context, it)
                }
            },
        )
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

        Spacer(Modifier.height(10.dp))

        // ── The shared controls: whichever door the file came through ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            UpdateFileAction(
                description = "use a firmware file this phone already holds, instead of a released one",
                enabled = !OtaController.busy(),
                onClick = {
                    armed = false
                    picker.launch(arrayOf("*/*"))
                },
            )
            when (view.stage) {
                OtaStage.Paused -> UpdateBeginAction(
                    caption = "RESUME",
                    description = "continue the paused install from the offset the unit confirmed",
                    enabled = linked,
                ) { OtaController.start(context) }
                OtaStage.Sending, OtaStage.Handshaking, OtaStage.Verifying, OtaStage.Reading ->
                    UpdateBeginAction(
                        caption = "WORKING",
                        description = "the install is running; this waits for the unit",
                        enabled = false,
                        // The transfer's own fraction, in the mark's place — the same number the bar
                        // and the sentence below carry, and the reason `WORKING…` never needed to be
                        // spelled out with a character.
                        progress = view.fraction(),
                        onClick = {},
                    )
                else -> HudButton(
                    label = if (armed) "CONFIRM FLASH" else "START",
                    style = if (armed) HudButtonStyle.WARNING else HudButtonStyle.PRIMARY,
                    // The risky verb of the app (D3): this one writes a device, so it keeps its word
                    // next to the mark and is never reduced to a caption under one.
                    glyph = HudIcons.Play,
                    enabled = linked && OtaController.canStart(),
                    onClick = {
                        if (!armed) {
                            // Ask once when the card cannot answer the only question that
                            // matters: is this the right file for this unit?
                            if (needsConfirmation(view, unitRunning, expected)) {
                                armed = true
                                onNote(confirmReason(view, unitRunning, expected))
                            } else {
                                OtaController.start(context)
                            }
                        } else {
                            armed = false
                            OtaController.start(context)
                        }
                    },
                )
            }
            if (view.stage != OtaStage.Idle) {
                UpdateStopAction(
                    caption = if (OtaController.busy()) "STOP" else "CLEAR",
                    description = if (OtaController.busy()) {
                        "abort the install in progress; the unit keeps running the firmware it has now"
                    } else {
                        "put this card back to waiting for the next file"
                    },
                    enabled = true,
                    onClick = {
                        armed = false
                        expected = null
                        FirmwareReleases.resetFile()
                        // After a successful install there is nothing to abort and nothing to
                        // apologise for — the card just goes back to waiting for the next file.
                        if (view.stage == OtaStage.Installed) OtaController.reset()
                        else OtaController.drop(context)
                    },
                )
            }
        }

        if (!linked && view.stage != OtaStage.Idle) {
            Text(
                "the file is on the phone — installing needs the Bluetooth link.",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        if (view.stage != OtaStage.Idle) {
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
            OtaBar(view.fraction(), if (view.stage == OtaStage.Installed) HudColors.Green else HudColors.Cyan)
            Text(
                progressLine(view),
                style = HudeCfgNote(),
                color = HudColors.TextDim,
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
                "press START again to flash anyway",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
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
        " · ${String.format(Locale.US, "%.1f", view.bytesPerSecond / 1024.0)} KB/s" +
        " · ${String.format(Locale.US, "%.1f", view.writesPerSecond)} writes/s" +
        " · ${String.format(Locale.US, "%.1f", view.elapsedSeconds)}s elapsed$writeCount$tail"
}

private fun mb(bytes: Long): String =
    String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))

/** The transfer bar. The bar itself is real progress and never lags it; the colour it
 * lands on when the install completes is a state change, so that part eases. */
@Composable
private fun OtaBar(fraction: Float, fill: Color) {
    val ink by animateColorAsState(
        fill,
        tween(if (HudMotion.enabled) HudMotion.CONTROL_MS else 0, easing = HudMotion.Ease),
        label = "otaBarInk",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(HudColors.CardInner),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(10.dp)
                .background(ink),
        )
    }
}
