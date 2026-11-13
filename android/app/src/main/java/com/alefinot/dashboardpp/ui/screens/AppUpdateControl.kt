package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alefinot.dashboardpp.update.AppUpdate
import com.alefinot.dashboardpp.update.AppUpdateState
import com.alefinot.dashboardpp.update.FirmwareRelease
import com.alefinot.dashboardpp.update.appFileError
import com.alefinot.dashboardpp.update.listError
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import java.util.Locale

/**
 * The controls under the "APP" half of the page's Updates card — the phone updating the phone.
 *
 * It is the FIRMWARE half's partner inside the same card and does as little as that one
 * does: the check, the download and the install hand all live in `update/AppUpdate.kt`,
 * where they outlive this composable, for the same reason the transfer lives in
 * `OtaController` — a progress bar that disappears when you scroll is a progress bar
 * that makes people press the button twice, and an updater that dies with the tab is an
 * update that starts twice.
 *
 * Since 2026-11-13 the half answers from one wide bar: **CHECK FOR UPDATES**. The bar keeps
 * that identity in every idle state and changes *face* the moment the answer arrives — it is
 * the ask (press to re-ask GitHub), the finding (highlighted **UPDATE AVAILABLE**, press to
 * fetch), and the fetch itself (the strip fills at the percent on the phone). Re-checking is
 * the same press as the old round REFRESH glyph; it is just the only word the bar had.
 * Only the install stays its own action, because the system installer is a hand-off this
 * app cannot press for the owner.
 *
 * What the card may promise is one sentence and true: the system installer replaces the app
 * in place and keeps its data, and a refused install — wrong signing key included — changes
 * nothing on the phone. What it may not promise is silent updating: the installer always
 * asks, because Android says so.
 */
@Composable
internal fun AppUpdateControl(installed: String) {
    val context = LocalContext.current
    val state by AppUpdate.state.collectAsState()

    // Ask on first composition. The startup check has usually answered already and this
    // one is free; if it has not, the TTL or the in-flight gate makes the two one call.
    LaunchedEffect(Unit) { AppUpdate.check(context) }

    // Whether the owner has granted "install unknown apps" to this app. Re-read when the
    // page comes back to the foreground, because the grant is made on a system page this
    // card opens — the same reason the Storage card re-reads after the storage jump.
    var grantTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) grantTick++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val canInstall = remember(grantTick) { AppUpdate.canInstall(context) }

    // The installer's refusal is local: it is about this press, and the card's state never
    // changed. A fresh state change retires the sentence.
    var installLine by remember { mutableStateOf("") }
    LaunchedEffect(state) { installLine = "" }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(statusLine(state, installed), style = HudeCfgNote(), color = statusColor(state))

        Spacer(Modifier.height(8.dp))
        val d = state as? AppUpdateState.Downloading
        // The one bar this half answers from: the ask, the finding, and the fetch that
        // fills the strip itself while its percent rides in the word.
        UpdateWideBar(
            glyph = when {
                d != null || state is AppUpdateState.Available -> HudIcons.ImportFile
                else -> HudIcons.Reset
            },
            label = checkLabel(state),
            description = checkDescription(state),
            ink = checkInk(state),
            enabled = checkEnabled(state),
            progress = d?.let { updateFraction(it.release.size, it.got) },
            highlight = state is AppUpdateState.Available,
            onClick = {
                val s = state
                if (s is AppUpdateState.Available) AppUpdate.download(context, s.release)
                else AppUpdate.check(context, force = true)
            },
        )

        if (d != null) {
            Text(
                "downloaded ${mb(d.got)} of ${mb(d.release.size)}",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        when (val s = state) {
            // The file is on the phone. The installer is a hand-off, so this half stops
            // at the door: INSTALL (or, without the special access, the jump to the page
            // that can grant it — the word is the control here (D4), since a cog cannot
            // say which permission).
            is AppUpdateState.ReadyToInstall -> {
                Spacer(Modifier.height(8.dp))
                if (canInstall) {
                    UpdateWideBar(
                        glyph = HudIcons.Play,
                        label = "install ${s.release.title}",
                        description = "hand ${s.release.title} to the system installer; it asks " +
                            "first, and a refused install changes nothing on the phone",
                        ink = HudColors.Cyan,
                        enabled = true,
                    ) {
                        AppUpdate.install(context, s.release)?.let { installLine = appFileError(it) }
                    }
                } else {
                    HudButton(
                        label = "ALLOW INSTALLS",
                        style = HudButtonStyle.WARNING,
                        glyph = HudIcons.Cog,
                        onClick = { AppUpdate.openInstallGrant(context) },
                    )
                }
            }
            else -> Unit
        }

        if (installLine.isNotEmpty()) {
            Text(
                installLine,
                style = HudeCfgNote(),
                color = HudColors.Red,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Released builds are debug-signed: an APK built on another machine installs only " +
                "after an uninstall.",
            style = HudeCfgNote(),
            color = HudColors.TextDim,
        )
    }
}

/** The bar's word per state. Uppercased by the bar; it is one word or two, never a sentence. */
private fun checkLabel(state: AppUpdateState): String = when (state) {
    AppUpdateState.Idle, AppUpdateState.Checking -> "checking for updates"
    is AppUpdateState.Available -> "update available · ${state.release.title}"
    is AppUpdateState.Downloading -> "downloading ${state.release.title} · " +
        updatePercent(updateFraction(state.release.size, state.got))
    is AppUpdateState.ReadyToInstall -> "update downloaded"
    else -> "check for updates"
}

/** Which ink the bar wears: amber is the app waiting on GitHub or on bytes, cyan is pressable. */
private fun checkInk(state: AppUpdateState): Color = when (state) {
    AppUpdateState.Idle, AppUpdateState.Checking -> HudColors.Amber
    is AppUpdateState.Downloading -> HudColors.Amber
    is AppUpdateState.FailedList -> HudColors.Amber
    is AppUpdateState.ReadyToInstall -> HudColors.Green
    else -> HudColors.Cyan
}

/** Pressable whenever pressing does something: re-ask, or take the release. */
private fun checkEnabled(state: AppUpdateState): Boolean = when (state) {
    is AppUpdateState.Available, is AppUpdateState.UpToDate,
    is AppUpdateState.FailedList, is AppUpdateState.FailedFile -> true
    else -> false
}

private fun checkDescription(state: AppUpdateState): String = when (state) {
    is AppUpdateState.Available ->
        "press to fetch ${state.release.title} onto this phone; INSTALL follows when the file is here"
    is AppUpdateState.Downloading ->
        "the release is being fetched; this waits until the file is on the phone"
    is AppUpdateState.ReadyToInstall ->
        "${state.release.title} is on the phone; INSTALL below hands it to the system installer"
    AppUpdateState.Idle, AppUpdateState.Checking ->
        "this is asking GitHub whether a newer release of this app is out"
    else -> "ask GitHub again whether a newer release of this app is out"
}

/** The one line the card leads with, for every state. */
private fun statusLine(state: AppUpdateState, installed: String): String = when (state) {
    AppUpdateState.Idle, AppUpdateState.Checking -> "checking the released versions…"
    is AppUpdateState.UpToDate -> {
        // The newest release is named even when it equals the running one: "up to date"
        // alone could be a guess, the number proves GitHub answered and the comparison ran.
        val newest = state.latest?.let { " — newest release $it" }.orEmpty()
        "app ${state.installed} is up to date$newest"
    }
    is AppUpdateState.Available -> releaseLine(state.release, state.installed)
    is AppUpdateState.Downloading -> releaseLine(state.release, installed)
    is AppUpdateState.ReadyToInstall ->
        "${state.release.title} (${mb(state.release.size)}) is on the phone — INSTALL hands it " +
            "to the system installer"
    is AppUpdateState.FailedList -> listError(state.code)
    is AppUpdateState.FailedFile -> appFileError(state.code)
}

/** Which colour that line wears: green is settled, amber is askable, red is a dead end. */
private fun statusColor(state: AppUpdateState) = when (state) {
    is AppUpdateState.UpToDate -> HudColors.Green
    is AppUpdateState.Available, is AppUpdateState.ReadyToInstall -> HudColors.Cyan
    is AppUpdateState.FailedList -> HudColors.Amber
    is AppUpdateState.FailedFile -> HudColors.Red
    else -> HudColors.TextDim
}

private fun releaseLine(release: FirmwareRelease, installed: String): String {
    val size = if (release.size > 0) " · ${mb(release.size)}" else ""
    return "${release.title} ($size) is out — this app is $installed"
}

private fun mb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
