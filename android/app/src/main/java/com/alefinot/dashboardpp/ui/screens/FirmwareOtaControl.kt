package com.alefinot.dashboardpp.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import java.util.Locale

/**
 * The firmware card's control: put a firmware file on the unit over Bluetooth.
 *
 * What this screen is allowed to promise is bounded by what the unit can do, so it says three
 * things out loud instead of hiding them behind a progress wheel:
 *
 *  - **which file it is about to send**, by size, by CRC-32, and by the version the image
 *    declares inside itself (`DPPVER=`, `src/config.cpp`). A build folder calls every image
 *    `firmware.bin`, so the name is worth nothing and the marker is worth a lot.
 *  - **which version the unit is running now**, next to it, because "1.5.1 onto a unit already
 *    running 1.5.1" is a question worth asking before 1.5 MB goes over the air — and because a
 *    unit that answers `not_implemented` (a build from before the Bluetooth installer exists)
 *    has to be told as plainly as it can be.
 *  - **that a failed install changes nothing.** The image goes into the unit's spare flash slot
 *    and the unit only switches to it after its own checks pass, so every failure sentence this
 *    card can print ends the same way: the unit is still running what it was.
 *
 * The transfer itself lives in [OtaController], not here — it outlives this composable, and a
 * progress bar that disappears when you scroll is a progress bar that makes people press the
 * button twice.
 */
@Composable
internal fun FirmwareUpdateControl(
    store: ConfigStore,
    enabled: Boolean,
    onNote: (String) -> Unit,
) {
    val context = LocalContext.current
    val view by OtaController.view.collectAsState()
    // The second press of START. Needed whenever the card cannot do the judging itself: the same
    // version as the unit's, or a file with no marker to compare. One flag, cleared as soon as
    // it is acted on, so the button cannot fire twice afterwards.
    var armed by remember { mutableStateOf(false) }

    val unitVersion = store.buildVersion

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

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            HudButton(
                label = "CHOOSE FILE",
                style = HudButtonStyle.SECONDARY,
                enabled = !OtaController.busy(),
                onClick = {
                    armed = false
                    picker.launch(arrayOf("*/*"))
                },
            )
            when (view.stage) {
                OtaStage.Paused -> HudButton(
                    label = "RESUME",
                    style = HudButtonStyle.PRIMARY,
                    enabled = enabled,
                    onClick = { OtaController.start(context) },
                )
                OtaStage.Sending, OtaStage.Handshaking, OtaStage.Verifying, OtaStage.Reading -> HudButton(
                    label = "WORKING…",
                    style = HudButtonStyle.PRIMARY,
                    enabled = false,
                    onClick = {},
                )
                else -> HudButton(
                    label = if (armed) "CONFIRM FLASH" else "START",
                    style = if (armed) HudButtonStyle.WARNING else HudButtonStyle.PRIMARY,
                    enabled = enabled && OtaController.canStart(),
                    onClick = {
                        if (!armed) {
                            // Ask once when the card cannot answer the only question that
                            // matters: is this the right file for this unit?
                            if (needsConfirmation(view, unitVersion)) {
                                armed = true
                                onNote(confirmReason(view, unitVersion))
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
                HudButton(
                    label = if (OtaController.busy()) "STOP" else "CLEAR",
                    style = HudButtonStyle.WARNING,
                    enabled = true,
                    onClick = {
                        armed = false
                        // After a successful install there is nothing to abort and nothing to
                        // apologise for — the card just goes back to waiting for the next file.
                        if (view.stage == OtaStage.Installed) OtaController.reset()
                        else OtaController.drop(context)
                    },
                )
            }
        }

        if (view.stage != OtaStage.Idle) {
            Text(
                summaryLine(view),
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "the unit is running " + (unitVersion.ifEmpty { "a version it has not reported" }),
                style = HudeCfgNote(),
                color = if (unitVersion.isNotEmpty() && view.image?.versionTag == unitVersion) {
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
 * Two cases, both honest: the image declares the version the unit is already running (so the
 * transfer will change nothing and probably means the wrong file was picked), or it declares no
 * version at all (so the card cannot warn about anything, and the driver is flying blind).
 */
private fun needsConfirmation(view: OtaView, unitVersion: String): Boolean {
    val tag = view.image?.versionTag ?: return true
    return unitVersion.isNotEmpty() && tag == unitVersion
}

private fun confirmReason(view: OtaView, unitVersion: String): String {
    val tag = view.image?.versionTag
    return if (tag == null) {
        "that file carries no version marker"
    } else {
        "that file says it is $tag and the unit is already running $tag"
    }
}

/** The picked file, in one line. */
private fun summaryLine(view: OtaView): String {
    val img: OtaImage? = view.image
    val size = if (img != null) mb(img.size) + " · " + img.size.toString() + " bytes" else "size unknown"
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
    return "$stage · ${view.percent()}% · ${mb(view.acked)} of ${mb(view.total)}" +
        " · ${String.format(Locale.US, "%.1f", view.bytesPerSecond / 1024.0)} KB/s" +
        " · ${String.format(Locale.US, "%.1f", view.writesPerSecond)} writes/s" +
        " · ${String.format(Locale.US, "%.1f", view.elapsedSeconds)}s elapsed$writeCount$tail"
}

private fun mb(bytes: Int): String =
    String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))

/** The transfer bar. The card's own fill, the accent's own colour, nothing animated. */
@Composable
private fun OtaBar(fraction: Float, fill: Color) {
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
                .background(fill),
        )
    }
}
