package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

        val offer = offered(state)
        if (offer != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (state) {
                    is AppUpdateState.ReadyToInstall -> {
                        if (canInstall) {
                            HudButton(
                                label = "INSTALL",
                                style = HudButtonStyle.PRIMARY,
                                onClick = {
                                    AppUpdate.install(context, offer)?.let { installLine = appFileError(it) }
                                },
                            )
                        } else {
                            // No dialog can grant a special access, so the primary button is
                            // the jump to the page that can, and INSTALL waits for the return.
                            HudButton(
                                label = "ALLOW INSTALLS",
                                style = HudButtonStyle.WARNING,
                                onClick = { AppUpdate.openInstallGrant(context) },
                            )
                        }
                    }
                    is AppUpdateState.Downloading -> HudButton(
                        label = "FETCHING…",
                        style = HudButtonStyle.PRIMARY,
                        enabled = false,
                        onClick = {},
                    )
                    else -> HudButton(
                        label = "DOWNLOAD",
                        style = HudButtonStyle.PRIMARY,
                        onClick = { AppUpdate.download(context, offer) },
                    )
                }
                HudButton(
                    label = "REFRESH",
                    style = HudButtonStyle.SECONDARY,
                    enabled = state !is AppUpdateState.Checking &&
                        state !is AppUpdateState.Downloading,
                    onClick = { AppUpdate.check(context, force = true) },
                )
            }
        } else if (state is AppUpdateState.UpToDate || state is AppUpdateState.FailedList) {
            Spacer(Modifier.height(8.dp))
            HudButton(
                label = "REFRESH",
                style = HudButtonStyle.SECONDARY,
                enabled = state !is AppUpdateState.Checking,
                onClick = { AppUpdate.check(context, force = true) },
            )
        }

        if (state is AppUpdateState.Downloading) {
            val d = state as AppUpdateState.Downloading
            Spacer(Modifier.height(8.dp))
            UpdateBar(d.release.let { if (it.size > 0) (d.got.toFloat() / it.size) else 0f })
            Text(
                "downloaded ${mb(d.got)} of ${mb(d.release.size)}",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
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

private fun offered(state: AppUpdateState): FirmwareRelease? = when (state) {
    is AppUpdateState.Available -> state.release
    is AppUpdateState.Downloading -> state.release
    is AppUpdateState.ReadyToInstall -> state.release
    else -> null
}

private fun mb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

/** The download bar. Real progress, no eased fill to lag behind it. */
@Composable
private fun UpdateBar(fraction: Float) {
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
                .background(HudColors.Cyan),
        )
    }
}
