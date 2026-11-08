package com.alefinot.dashboardpp.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.ScanRing
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.viewmodel.ConnectMode
import com.alefinot.dashboardpp.viewmodel.Found
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import kotlinx.coroutines.delay

@Composable
fun VehicleScreen(
    connection: ConnectionViewModel,
    onEdit: (String) -> Unit,
    onNameUnclaimed: () -> Unit,
) {
    val context = LocalContext.current
    val cards = remember(connection) { connection.vehicleCards() }
    val vehicles by cards.collectAsStateWithLifecycle(initialValue = emptyList())
    val garageDamage by produceState<String?>(initialValue = null, vehicles) {
        value = connection.garageDamage()
    }
    val selectedId = connection.selectedVehicleId.value
    val state = connection.uiState.value
    val notice by connection.notice
    val unclaimed by connection.unclaimed
    val findIsRetrySearch = ShellRouting.view(state).findIsRetrySearch

    val found = connection.scanResults.value
    val scanWhy = connection.scanError.value
    val radioMissing = connection.adapterOn.value == false
    val bondNote = connection.bondNote.value

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        Modifier
            .hudPage()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HudPage.Gutter)
            .padding(top = 10.dp, bottom = 24.dp),
    ) {
        val selectedName = vehicles.firstOrNull { it.id == selectedId }?.name?.ifBlank { null }
        HudPageHeader(title = "Vehicles") {
            HudBanner(
                buildString {
                    append(if (vehicles.size == 1) "1 vehicle" else "${vehicles.size} vehicles")
                    if (selectedName != null) append("  ·  $selectedName selected")
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A card is a vehicle, not an address. Pick one and the app reaches for " +
                    "that dashboard; a vehicle that has never been seen by a scan " +
                    "is still a vehicle.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        if (vehicles.isEmpty()) {
            Text(
                garageDamage?.let {
                    "There is a garage file on disk, but this build cannot read it ($it), so no " +
                        "card is shown. The app will not write over a garage it cannot read: " +
                        "repair or move that file and the cards come back."
                }
                    ?: "Nothing has been met yet: this app has not paired with a dashboard yet.",
                color = if (garageDamage == null) HudColors.TextMuted else HudColors.Amber,
                style = HudeCfgNote(),
            )
            Spacer(Modifier.height(14.dp))
        } else {
            for (v in vehicles) {
                VehicleCard(
                    vehicle = v,
                    selected = v.id == selectedId,
                    state = if (v.id == selectedId) state else null,
                    connected = connectedCard(v, state),
                    nowMs = nowMs,
                    ringProgress = 0f,
                    photo = cardPhoto(connection, v.id),
                    onClick = { connection.selectVehicle(v.id) },
                    onConnect = { connection.connectVehicle(v.id, ConnectMode.SEARCH) },
                    onDisconnect = { connection.disconnect() },
                    onForgetUnit = { connection.forgetUnit(v.id) },
                    onEdit = { onEdit(v.id) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        notice?.let {
            NoticeCard("THE APP AND THE CARD DISAGREE", it, HudColors.Amber)
            Spacer(Modifier.height(10.dp))
        }
        unclaimed?.let {
            NoticeCard(
                "A DASHBOARD ANSWERED THAT NO CARD CLAIMS",
                "${it.name} (firmware ${it.version ?: "unknown"}, " +
                    "MAC ${it.mac.uppercase()}) is not on this list yet. " +
                    "Name it and it becomes a card of its own.",
                HudColors.Cyan,
                actionLabel = "NAME IT",
                onAction = onNameUnclaimed,
            )
            Spacer(Modifier.height(10.dp))
        }

        bondNote?.let {
            NoticeCard(
                "THIS PHONE IS STILL BONDED TO THAT UNIT",
                it,
                HudColors.Amber,
                actionLabel = "PHONE BLUETOOTH SETTINGS",
                onAction = { openPhoneBluetoothSettings(context) },
            )
            Spacer(Modifier.height(10.dp))
        }

        AddVehicleCard(
            onSearch = { connection.rescan() },
            found = found,
            scanWhy = scanWhy,
            radioMissing = radioMissing,
            retry = findIsRetrySearch,
            onPair = { connection.pairWith(it) },
        )
    }
}

@Composable
private fun cardPhoto(connection: ConnectionViewModel, vehicleId: String): Bitmap? {
    val edgePx = with(LocalDensity.current) { 76.dp.toPx().toInt() * 2 }
    val version = connection.photoVersion.intValue
    val photo by produceState<Bitmap?>(initialValue = null, vehicleId, version) {
        connection.photo(vehicleId, edgePx) { value = it }
    }
    return photo
}

@Composable
private fun CardCover(
    photo: Bitmap?,
    selected: Boolean,
    scanning: Boolean,
    ringProgress: Float,
) {
    Box(
        Modifier
            .size(width = 76.dp, height = 54.dp)
            .clip(HudRadius.CardShape)
            .background(HudColors.Panel)
            .border(1.dp, HudColors.Hairline, HudRadius.CardShape),
        contentAlignment = Alignment.Center,
    ) {
        photo?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "photo of this vehicle",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 76.dp, height = 54.dp),
            )
        }
        if (photo == null) {
            if (scanning) {
                ScanRing(ringProgress, Modifier.size(28.dp))
            } else {
                Icon(
                    HudIcons.Car,
                    contentDescription = null,
                    tint = if (selected) HudColors.CyanDim else HudColors.TextDim,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    selected: Boolean,
    state: ConnectionUiState?,
    connected: Boolean,
    nowMs: Long,
    ringProgress: Float,
    photo: Bitmap?,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForgetUnit: () -> Unit,
    onEdit: () -> Unit,
) {
    val status = cardStatusLine(vehicle, state, nowMs)
    Column(
        Modifier
            .fillMaxWidth()
            .hudCard(
                borderColor = if (selected) HudColors.Cyan else HudColors.CardHairline,
                highlight = if (selected) 0.05f else 0f,
            )
            .clickable(onClickLabel = "Select ${vehicle.name}") { onClick() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardCover(photo, selected, showScanRing(state), ringProgress)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    vehicle.name.ifBlank { "UNNAMED VEHICLE" },
                    color = if (selected) HudColors.Cyan else HudColors.TextPrimary,
                    style = HudeHeading().copy(fontSize = 17.sp),
                )
                plateLine(vehicle)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = HudColors.TextDim, style = HudePerfLabel())
                }
                specLine(vehicle).takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = HudColors.TextMuted, style = HudeCfgNote())
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    status.text,
                    color = statusColor(status.tone),
                    style = HudePerfLabel(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!connected) {
                    vehicle.identityLabel().takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = HudColors.TextDim, style = HudePerfValue())
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) {
                HudButton(
                    "DISCONNECT",
                    modifier = Modifier.widthIn(min = 120.dp),
                    style = HudButtonStyle.SECONDARY,
                ) { onDisconnect() }
            } else if (selected) {
                HudButton(
                    "CONNECT",
                    modifier = Modifier.widthIn(min = 108.dp),
                    style = HudButtonStyle.PRIMARY,
                ) { onConnect() }
            }
            HudButton(
                "EDIT",
                modifier = Modifier.widthIn(min = 72.dp),
                style = HudButtonStyle.SECONDARY,
            ) { onEdit() }
        }

        if (selected && vehicle.hasPairing) {
            Spacer(Modifier.height(8.dp))
            HudButton(
                "FORGET THIS UNIT",
                modifier = Modifier.widthIn(min = 168.dp),
                style = HudButtonStyle.SECONDARY,
            ) { onForgetUnit() }
            Spacer(Modifier.height(4.dp))
            Text(
                "Clears this card's pairing on this phone. The card, its name and its " +
                    "photo stay - DELETE THE CARD in the editor is the one that removes " +
                    "the vehicle.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
        }
    }
}

@Composable
private fun SweepRow(unit: Found, onPair: (Found) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudCard()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(unit.name, color = HudColors.TextPrimary, style = HudePerfLabel())
            Spacer(Modifier.height(2.dp))
            Text("signal ${unit.rssi} dBm", color = HudColors.TextDim, style = HudeCfgNote())
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.heightIn(min = 44.dp)) {
            HudButton("PAIR", style = HudButtonStyle.PRIMARY) { onPair(unit) }
        }
    }
}

private fun openPhoneBluetoothSettings(context: android.content.Context) {
    val intents = listOf(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
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
private fun NoticeCard(
    title: String,
    text: String,
    accent: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudCard(borderColor = accent.copy(alpha = 0.35f))
            .padding(14.dp),
    ) {
        Text(title, color = accent, style = HudeSectionTitle())
        Spacer(Modifier.height(8.dp))
        Text(text, color = HudColors.TextMuted, style = HudeCfgNote())
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.heightIn(min = 44.dp)) {
                    HudButton(actionLabel, style = HudButtonStyle.PRIMARY) { onAction() }
                }
            }
        }
    }
}

@Composable
private fun AddVehicleCard(
    onSearch: () -> Unit,
    found: List<Found>,
    scanWhy: String?,
    radioMissing: Boolean,
    retry: Boolean,
    onPair: (Found) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudCard()
            .padding(14.dp),
    ) {
        Text("ADD VEHICLE", color = HudColors.TextDim, style = HudeSectionTitle())
        Spacer(Modifier.height(10.dp))
        HudButton(
            if (retry) "SEARCH AGAIN" else "SEARCH FOR DASHBOARD++",
            modifier = Modifier.fillMaxWidth(),
            style = HudButtonStyle.PRIMARY,
        ) { onSearch() }
        Spacer(Modifier.height(10.dp))

        if (found.isEmpty()) {
            scanWhy?.let {
                Text(it, color = HudColors.Amber, style = HudeCfgNote())
                Spacer(Modifier.height(10.dp))
            }
            if (radioMissing) {
                Text(
                    "Bluetooth is off on this phone. Turn it on, then search.",
                    color = HudColors.Amber,
                    style = HudeCfgNote(),
                )
                Spacer(Modifier.height(10.dp))
            }
        } else {
            Text("IN RANGE", color = HudColors.TextDim, style = HudeSectionTitle())
            Spacer(Modifier.height(8.dp))
            for (unit in found) {
                SweepRow(unit, onPair)
                Spacer(Modifier.height(8.dp))
            }
        }
        Text(
            "A dashboard that answers becomes a card. Name it, and the app remembers the " +
                "vehicle rather than the radio address - which is why coming back to a car " +
                "you drive every day takes a couple of seconds and a first hunt does not.",
            color = HudColors.TextDim,
            style = HudeCfgNote(),
        )
    }
}
