package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.graphics.Color
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.viewmodel.ConnectMode
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState
import com.alefinot.dashboardpp.ui.theme.HudColors
import kotlin.math.roundToInt

/** How much weight the status line deserves, decided apart from the colour it is drawn in. */
enum class CardTone {
    /** A unit is connected and reporting. */
    GOOD,

    /** A ladder the user asked for is running. */
    LIVE,

    /** Nothing is connected, and the reason is the user's to act on. */
    WARN,

    /** Something was tried and did not work. */
    BAD,

    /** A fact about the past: saved, seen once, not here now. */
    MUTED,

    /** The app has never reached this vehicle at all. */
    DIM,
}

fun statusColor(tone: CardTone): Color = when (tone) {
    CardTone.GOOD -> HudColors.Green
    CardTone.LIVE -> HudColors.Cyan
    CardTone.WARN -> HudColors.Amber
    CardTone.BAD -> HudColors.Red
    CardTone.MUTED -> HudColors.TextMuted
    CardTone.DIM -> HudColors.TextDim
}

/** One status line: what the card says about whether this vehicle is reachable. */
data class CardStatusLine(val text: String, val tone: CardTone)

/** Trim a field for display; blank becomes absent, so nothing dangles. */
private fun fieldOf(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

fun specLine(v: Vehicle): String {
    val makerModel = listOfNotNull(fieldOf(v.maker), fieldOf(v.model))
        .joinToString(" ")
    val what = listOfNotNull(
        v.type.name,
        makerModel.takeIf { it.isNotEmpty() }?.uppercase(),
        fieldOf(v.year),
    )
    return what.joinToString(" · ")
}

/** The plate, cleaned, or `null` when there is none — the caller draws no line at all. */
fun plateLine(v: Vehicle): String? = fieldOf(v.plate)?.uppercase()

fun lastSeenLine(lastConnectedAtMs: Long?, nowMs: Long): String {
    val then = lastConnectedAtMs ?: return "NEVER CONNECTED"
    if (nowMs - then < MINUTE) return "LAST SEEN JUST NOW"
    val days = daysBetweenUtc(then, nowMs)
    if (days == 0L) return "LAST SEEN TODAY"
    if (days == 1L) return "LAST SEEN YESTERDAY"
    if (days <= 21L) return "LAST SEEN $days DAYS AGO"
    val years = (days / 365.25).roundToInt()
    if (days >= 320L && years >= 1) return "LAST SEEN ${counted(years, "YEAR")}"
    val months = (days / 30.44).roundToInt().coerceAtLeast(1)
    return "LAST SEEN ${counted(months, "MONTH")}"
}

/** One or two, in English: "1 MONTH AGO", "2 MONTHS AGO". */
private fun counted(n: Int, unit: String) =
    if (n == 1) "$n $unit AGO" else "$n ${unit}S AGO"

/** Whole calendar days between two instants, counted in UTC. */
private fun daysBetweenUtc(fromMs: Long, toMs: Long): Long =
    toMs.floorDiv(DAY_MS) - fromMs.floorDiv(DAY_MS)

private const val MINUTE = 60_000L
private const val DAY_MS = 24L * 60 * 60 * 1000

fun cardStatusLine(v: Vehicle, state: ConnectionUiState?, nowMs: Long): CardStatusLine = when (state) {
    is ConnectionUiState.Connected ->
        CardStatusLine(
            state.version?.let { "CONNECTED · V$it" } ?: "CONNECTED",
            CardTone.GOOD,
        )

    is ConnectionUiState.Connecting ->
        CardStatusLine(state.stageLine().uppercase(), CardTone.LIVE)

    is ConnectionUiState.Failed -> CardStatusLine(state.reason, CardTone.BAD)

    is ConnectionUiState.Idle ->
        state.reason?.takeIf { it.isNotBlank() }
            ?.let { CardStatusLine(it, CardTone.WARN) }
            ?: fallback(v, nowMs)

    null -> fallback(v, nowMs)
}

private const val NO_RADIO = "NO BLUETOOTH ON THIS UNIT — UPDATE IT FROM THE UNIT'S OWN PAGE"

private fun fallback(v: Vehicle, nowMs: Long): CardStatusLine = when {
    v.preBleFirmware -> CardStatusLine(NO_RADIO, CardTone.BAD)
    v.lastConnectedAtMs == null -> CardStatusLine("NEVER CONNECTED", CardTone.DIM)
    else -> CardStatusLine(lastSeenLine(v.lastConnectedAtMs, nowMs), CardTone.MUTED)
}

fun connectedCard(v: Vehicle, state: ConnectionUiState?): Boolean =
    state is ConnectionUiState.Connected && state.vehicleId == v.id

fun showScanRing(state: ConnectionUiState?): Boolean =
    state is ConnectionUiState.Connecting && state.mode == ConnectMode.SEARCH
