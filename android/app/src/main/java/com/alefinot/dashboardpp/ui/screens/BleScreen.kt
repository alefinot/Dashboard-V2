package com.alefinot.dashboardpp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleDevice
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.ble.ConfigMirror
import com.alefinot.dashboardpp.ble.WeatherPush
import com.alefinot.dashboardpp.ble.linkFailureText
import com.alefinot.dashboardpp.ble.scanFailureText
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import com.alefinot.dashboardpp.ui.theme.HudeParamValue
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.hudCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The phone's side of the BLE link - the panel the settings screen opens.
 *
 * It is deliberately the smallest thing that can prove the whole transport: scan
 * for the unit, bond (the code on the dashboard is typed here), then show the
 * 1 Hz snapshot and the merged `config.get` mirror. Every action is one RPC, so
 * a failure names the method that failed rather than a generic "offline".
 *
 * The link itself lives in [BleRuntime], not here: closing the panel must not
 * drop the connection that the trip recorder and the live tab will share.
 */
@Composable
fun BleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val link = remember { BleRuntime.link(context) }
    val api = remember(link) { BleApi(link) }
    val state by link.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<BleDevice>>(emptyList()) }
    // The peer the link last came up with is remembered by the link, not here: this
    // panel is opened and closed around the settings tab, and a `remember` that drops
    // the address turns every "the unit restarted" into a fresh scan.
    var lastDevice by remember {
        mutableStateOf(link.lastPeer?.let { BleDevice(it.name, it.address, 0) })
    }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var config by remember { mutableStateOf<ConfigMirror?>(null) }
    var query by remember { mutableStateOf("") }

    // Filtering happens in the composable body, not inside the lazy list's scope:
    // the list only decides which rows are on screen.
    val rows = remember(config, query) {
        config?.let { filterConfig(it, query) } ?: emptyList()
    }

    // The unit pushes one snapshot a second on its own characteristic; the same
    // flat JSON the HTTP transport parsed, so the parser is shared.
    LaunchedEffect(link) {
        link.telemetry.collect { text -> Snapshot.parse(text)?.let { snapshot = it } }
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val startScan: () -> Unit = {
        scanJob?.cancel()
        devices = emptyList()
        scanError = null
        scanning = true
        scanJob = scope.launch {
            try {
                // A burst long enough to catch a unit that is advertising between
                // connections, short enough that the radio is not left on.
                withTimeoutOrNull(15_000) {
                    link.scan().collect { devices = it }
                }
            } catch (e: Exception) {
                scanError = scanError ?: scanFailureText(e.message)
            } finally {
                scanning = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) startScan()
        else scanError = "Bluetooth permission denied. The link needs it to scan and connect."
    }

    val scanWithPermission: () -> Unit = {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    val connect: (BleDevice) -> Unit = { device ->
        scanJob?.cancel()
        scanning = false
        lastDevice = device
        notice = null
        scope.launch {
            val ok = link.connect(device.address, device.name)
            if (ok) notice = "Link ready. Pairing is done once; the unit remembers the phone."
        }
    }

    val action: (String, suspend () -> Unit) -> Unit = { name, block ->
        scope.launch {
            busy = name
            try {
                block()
            } finally {
                busy = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanJob?.cancel() }
    }

    val ready = state as? BleLinkState.Ready

    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("DASHBOARD LINK", style = HudeHeading(), color = HudColors.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    HudButton("BACK", style = HudButtonStyle.SECONDARY, onClick = onBack)
                }
            }

            item { StatusCard(state, scanning) }

            if (ready == null) {
                item {
                    Text(
                        "Scan for the dashboard, then tap its name. The unit shows a " +
                            "6-digit code while pairing: type that code on this phone when " +
                            "Android asks for it.",
                        color = HudColors.TextDim,
                        style = HudeCfgNote(),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HudButton(
                            if (scanning) "SCANNING…" else "SCAN FOR DASHBOARD",
                            enabled = !scanning && busy == null,
                            onClick = scanWithPermission,
                        )
                        if (lastDevice != null) {
                            Spacer(Modifier.width(8.dp))
                            HudButton(
                                "RETRY ${lastDevice!!.name}",
                                style = HudButtonStyle.SECONDARY,
                                enabled = !scanning,
                                onClick = { connect(lastDevice!!) },
                            )
                        }
                    }
                }

                val error = scanError ?: (state as? BleLinkState.Failed)?.let { linkFailureText(it.reason) }
                if (error != null) {
                    item {
                        Text(error, color = HudColors.Red, style = HudeCfgNote())
                    }
                }

                if (devices.isEmpty() && !scanning && scanError == null) {
                    item {
                        Text(
                            "No dashboard found yet. Check that the unit is powered, in " +
                                "range, and not connected to another phone.",
                            color = HudColors.TextMuted,
                            style = HudeCfgNote(),
                        )
                    }
                }

                items(devices, key = { it.address }) { device ->
                    DeviceRow(device) { connect(device) }
                }
            } else {
                item { LinkCard(ready, snapshot) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudButton(
                            if (busy == "PING") "PING…" else "PING",
                            enabled = busy == null,
                            onClick = {
                                action("PING") {
                                    val reply = api.ping()
                                    notice = if (reply.ok) {
                                        "Pong: up ${number(reply.fields["up"])} s, " +
                                            "heap ${number(reply.fields["heap"])} B."
                                    } else {
                                        "Ping failed: ${reply.error}"
                                    }
                                }
                            },
                        )
                        HudButton(
                            if (busy == "CONFIG") "PULLING…" else "PULL CONFIG",
                            style = HudButtonStyle.SECONDARY,
                            enabled = busy == null,
                            onClick = {
                                action("CONFIG") {
                                    val mirror = api.config()
                                    config = mirror
                                    notice = if (mirror == null) {
                                        "Config pull failed."
                                    } else {
                                        "${mirror.keys} keys in ${mirror.chunks} chunks " +
                                            "(${mirror.rows} rows)."
                                    }
                                }
                            },
                        )
                        HudButton(
                            if (busy == "TIME") "PUSHING…" else "PUSH TIME",
                            style = HudButtonStyle.SECONDARY,
                            enabled = busy == null,
                            onClick = {
                                action("TIME") {
                                    val reply = api.pushTime()
                                    notice = if (reply.ok) {
                                        "Clock pushed."
                                    } else {
                                        "Clock push failed: ${reply.error}"
                                    }
                                }
                            },
                        )
                        HudButton(
                            "DISCONNECT",
                            style = HudButtonStyle.DANGER,
                            enabled = busy == null,
                            onClick = {
                                link.disconnect()
                                snapshot = null
                                config = null
                                notice = null
                            },
                        )
                    }
                }

                // The weather path is part of the transport, not a screen of its
                // own yet: this sends the shape a real forecast will fill, so the
                // firmware's ingest can be exercised before Open-Meteo is wired up.
                item {
                    HudButton(
                        if (busy == "WEATHER") "PUSHING…" else "PUSH TEST WEATHER",
                        style = HudButtonStyle.SECONDARY,
                        enabled = busy == null,
                        onClick = {
                            action("WEATHER") {
                                val now = System.currentTimeMillis() / 1000
                                val reply = api.pushWeather(
                                    WeatherPush(
                                        temperature = 18.5,
                                        humidity = 55,
                                        windSpeed = 12.0,
                                        windDirection = 210.0,
                                        weatherCode = 2,
                                        cloudCover = 35,
                                        sunrise = now - 6 * 3600,
                                        sunset = now + 4 * 3600,
                                        city = "Bench",
                                        valid = true,
                                    ),
                                )
                                notice = if (reply.ok) {
                                    "Weather accepted: sun " +
                                        "${BleJson.string(reply.fields, "sunrise")}–" +
                                        "${BleJson.string(reply.fields, "sunset")}."
                                } else {
                                    "Weather push failed: ${reply.error}"
                                }
                            }
                        },
                    )
                }

                if (notice != null) {
                    item { Text(notice!!, color = HudColors.Cyan, style = HudeCfgNote()) }
                }

                if (config == null) {
                    item {
                        Text(
                            "Tap PULL CONFIG to mirror the unit's settings. This reads every " +
                                "key; nothing is written until a settings screen sends a change.",
                            color = HudColors.TextDim,
                            style = HudeCfgNote(),
                        )
                    }
                } else {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HudInput(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                style = HudInputStyle.SEARCH,
                                placeholder = "Filter ${config!!.values.size} settings",
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "${rows.size} shown",
                                color = HudColors.TextMuted,
                                style = HudePerfLabel(),
                            )
                        }
                    }
                    items(rows, key = { it.first }) { (key, value) ->
                        ConfigRow(key, value)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: BleLinkState, scanning: Boolean) {
    val (title, color) = when (state) {
        BleLinkState.Idle ->
            (if (scanning) "SCANNING" else "NO LINK") to
                (if (scanning) HudColors.Cyan else HudColors.TextDim)
        is BleLinkState.Connecting -> "CONNECTING TO ${state.name}" to HudColors.Cyan
        is BleLinkState.Pairing ->
            "PAIRING ${state.name}" to HudColors.Amber
        is BleLinkState.Ready ->
            "LINK UP · ${state.name}" to HudColors.Green
        is BleLinkState.Failed -> "LINK FAILED" to HudColors.Red
    }
    val detail = when (state) {
        BleLinkState.Idle ->
            if (scanning) "Looking for units advertising the dashboard service…"
            else "Not connected to a dashboard."
        is BleLinkState.Connecting -> "Opening the GATT connection and exchanging MTU…"
        is BleLinkState.Pairing ->
            "Type the 6-digit code shown on the dashboard into Android's pairing dialog."
        is BleLinkState.Ready ->
            "${state.address} · MTU ${state.mtu} B · encrypted"
        is BleLinkState.Failed -> linkFailureText(state.reason)
    }
    Box(
        Modifier.fillMaxWidth()
            .hudCard(fill = HudColors.Msg, borderColor = color.copy(alpha = 0.3f))
            .background(color.copy(alpha = 0.08f))
            .padding(12.dp),
    ) {
        Column {
            Text(title, color = color, style = HudeSectionTitle())
            Spacer(Modifier.height(6.dp))
            Text(detail, color = HudColors.TextMuted, style = HudeCfgNote())
        }
    }
}

@Composable
private fun LinkCard(state: BleLinkState.Ready, snapshot: Snapshot?) {
    Column(
        Modifier.fillMaxWidth()
            .hudCard(borderColor = HudColors.HairlineActive)
            .padding(14.dp),
    ) {
        Text("LIVE FROM THE UNIT", color = HudColors.Cyan, style = HudePerfLabel())
        Spacer(Modifier.height(10.dp))
        if (snapshot == null) {
            Text(
                "Waiting for the first 1 Hz snapshot…",
                color = HudColors.TextMuted,
                style = HudeCfgNote(),
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth()) {
            Metric("SPEED", String.format(Locale.US, "%.1f km/h", snapshot.spd))
            Metric("ODOMETER", String.format(Locale.US, "%.1f km", snapshot.odo))
            Metric("FIX", if (snapshot.hasFix) "${snapshot.sats} sats" else "none")
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("FRAME RATE", String.format(Locale.US, "%.1f fps", snapshot.fps))
            Metric("HEAP", "${snapshot.heap} B")
            Metric("UPTIME", "${snapshot.up} s")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Firmware ${snapshot.version.ifEmpty { "?" }} · ${state.name} at ${state.address}",
            color = HudColors.TextDim,
            style = HudeCfgNote(),
        )
    }
}

@Composable
private fun RowScope.Metric(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, color = HudColors.TextMuted, style = HudePerfLabel())
        Text(value, color = HudColors.TextPrimary, style = HudePerfValue())
    }
}

@Composable
private fun DeviceRow(device: BleDevice, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .hudCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, color = HudColors.TextPrimary, style = HudeParamName())
            Text(device.address, color = HudColors.TextDim, style = HudeCfgNote())
        }
        Text("${device.rssi} dBm", color = HudColors.Cyan, style = HudeParamValue())
    }
}

@Composable
private fun ConfigRow(key: String, value: Any?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)) {
        Text(
            key,
            modifier = Modifier.weight(1f),
            color = HudColors.TextMuted,
            style = HudeParamName(),
        )
        Text(
            BleJson.display(value),
            color = HudColors.TextPrimary,
            style = HudeParamValue(),
        )
    }
}

private fun filterConfig(config: ConfigMirror, query: String): List<Pair<String, Any?>> {
    val needle = query.trim().lowercase(Locale.US)
    val all = config.values.toList()
    if (needle.isEmpty()) return all
    return all.filter { (key, value) ->
        key.lowercase(Locale.US).contains(needle) ||
            BleJson.display(value).lowercase(Locale.US).contains(needle)
    }
}

private fun number(value: Any?): String = when (value) {
    is Double -> BleJson.display(value)
    is Number -> value.toString()
    null -> "?"
    else -> value.toString()
}

