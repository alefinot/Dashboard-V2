package com.alefinot.dashboardpp.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.trips.TripRuntimeState
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudGauge
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.StatCell
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudeBody
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.hudCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos

private const val CHIP_PULSE_MS = 2000L

private const val CHIP_PULSE_STEP_MS = 60L

@Composable
fun LiveTab(active: Boolean) {
    var state by remember { mutableStateOf(TripRuntime.state.value) }

    LaunchedEffect(active) {
        if (active) {
            TripRuntime.state.collect { state = it }
        }
    }


    Column(
        Modifier.hudPage()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HudPage.Gutter)
            .padding(top = 10.dp, bottom = 24.dp),
    ) {
        HudEnter(modifier = Modifier.fillMaxWidth()) {
            HudPageHeader(
                title = "Live",
                trailing = { StatusChip(state, active) },
            )
        }
        Spacer(Modifier.height(16.dp))

        val snapshot = state.snapshot
        if (snapshot != null) {
            HudEnter(
                modifier = Modifier.fillMaxWidth(),
                delayMs = HudMotion.STAGGER_MS,
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .hudCard()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HudGauge(
                        value = snapshot.spd,
                        fullScale = speedScale(snapshot),
                        label = "SPEED",
                        unit = "km/h · ${sourceLabel(snapshot.src)}",
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        accent = if (snapshot.spd > 0.0) HudColors.Cyan else HudColors.CyanDim,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        HudEnter(modifier = Modifier.fillMaxWidth(), delayMs = 2 * HudMotion.STAGGER_MS) {
            Column(Modifier.fillMaxWidth()) {
                if (snapshot != null) {
                    SnapshotCells(snapshot)
                } else {
                    Text(
                        "Waiting for the dashboard…",
                        color = HudColors.TextMuted,
                        style = HudeCfgHelp(),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Gauge scale: quiet drive gets 60, highway gets 180, anything past that gets 240. */
private fun speedScale(s: Snapshot): Double = when {
    s.max > 200.0 || s.spd > 200.0 -> 240.0
    s.max > 120.0 || s.spd > 120.0 -> 180.0
    s.max > 60.0 || s.spd > 60.0 -> 120.0
    else -> 60.0
}

private fun sourceLabel(src: Int): String = when (src) {
    0 -> "HALL"
    1 -> "GPS"
    2 -> "FUSED"
    else -> "SRC$src"
}

private fun fuelStateLabel(st: Int): String = when (st) {
    0 -> "OFF"
    1 -> "OK"
    2 -> "OPEN"
    3 -> "SHORT"
    else -> "ST$st"
}

private fun fmt(value: Double, places: Int = 1): String =
    String.format(java.util.Locale.US, "%.${places}f", value)

@Composable
private fun StatusChip(state: TripRuntimeState, active: Boolean) {
    val (label, target) = when {
        state.recording -> "RECORDING" to HudColors.Green
        state.lastOkMs == 0L -> "CONNECTING" to HudColors.TextDim
        state.polling && state.stale -> "NO DATA" to HudColors.Red
        state.polling -> "LOGGING IDLE" to HudColors.Cyan
        else -> "NOT RECORDING" to HudColors.TextDim
    }
    val color by animateColorAsState(
        target,
        tween(HudMotion.SWITCH_MS, easing = HudMotion.Ease),
        label = "chipColor",
    )
    val glow = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.recording, active) {
        if (!state.recording || !active) {
            glow.floatValue = 0f
            return@LaunchedEffect
        }
        val start = android.os.SystemClock.elapsedRealtime()
        while (isActive) {
            val p = (android.os.SystemClock.elapsedRealtime() - start) % CHIP_PULSE_MS
            glow.floatValue = 0.5f - 0.5f * cos(2f * PI.toFloat() * p / CHIP_PULSE_MS)
            delay(CHIP_PULSE_STEP_MS)
        }
    }
    Box(
        Modifier
            .hudCard(
                shape = HudRadius.PillShape,
                fill = HudColors.Panel,
                borderColor = color.copy(alpha = 0.3f),
            )
            .background(color.copy(alpha = 0.08f), HudRadius.PillShape),
    ) {
        Crossfade(label, animationSpec = tween(HudMotion.SWITCH_MS), label = "chipText") {
            Text(
                it,
                color = color,
                style = HudeBody(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Canvas(Modifier.matchParentSize()) {
            val g = glow.floatValue
            if (g > 0.01f) {
                val h = size.height
                val path = Path()
                path.addRoundRect(
                    RoundRect(Rect(0f, 0f, size.width, h), CornerRadius(h / 2f, h / 2f)),
                )
                drawPath(
                    path,
                    color.copy(alpha = 0.05f + 0.07f * g),
                    style = Stroke(width = 7.dp.toPx()),
                )
                drawPath(
                    path,
                    color.copy(alpha = 0.07f + 0.11f * g),
                    style = Stroke(width = 3.5.dp.toPx()),
                )
                drawPath(
                    path,
                    color.copy(alpha = 0.18f + 0.30f * g),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun RowOf(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { first() }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { second() }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SnapshotCells(s: Snapshot) {
    RowOf(
        { StatCell("TRIP", fmt(s.trpKm, 2), "km", modifier = Modifier.fillMaxWidth()) },
        { StatCell("TRIP FUEL", fmt(s.trpL, 2), "L", modifier = Modifier.fillMaxWidth()) },
    )
    RowOf(
        { StatCell("INST ECO", fmt(s.ikml, 1), "km/L", modifier = Modifier.fillMaxWidth()) },
        { StatCell("AVG ECO", fmt(s.akml, 1), "km/L", modifier = Modifier.fillMaxWidth()) },
    )
    RowOf(
        { StatCell("MAX", fmt(s.max, 1), "km/h", modifier = Modifier.fillMaxWidth()) },
        { StatCell("AVG", fmt(s.avg, 1), "km/h", modifier = Modifier.fillMaxWidth()) },
    )
    RowOf(
        { StatCell("ODO", fmt(s.odo, 1), "km", modifier = Modifier.fillMaxWidth()) },
        {
            StatCell(
                "FUEL", "${s.fuelPct}%",
                unit = "${fmt(s.fuelL, 2)} L",
                accent = if (s.fuelSt == 1) HudColors.Cyan else HudColors.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
    RowOf(
        {
            StatCell(
                "FUEL INPUT", fuelStateLabel(s.fuelSt),
                accent = when (s.fuelSt) { 1 -> HudColors.Green; 0 -> HudColors.TextDim; else -> HudColors.Red },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        {
            StatCell(
                "BATTERY", fmt(s.volts, 2), "V",
                accent = if (s.volts in 11.5..15.0) HudColors.Cyan else HudColors.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
    RowOf(
        {
            StatCell(
                "COOLANT", fmt(s.temp, 1), "°C",
                accent = if (s.temp > 105.0) HudColors.Red else HudColors.Cyan,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        { StatCell("LIGHT", s.amb.toString(), modifier = Modifier.fillMaxWidth()) },
    )
    RowOf(
        {
            StatCell(
                "SATS", s.sats.toString(),
                accent = if (s.fix == 1) HudColors.Green else HudColors.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        {
            StatCell(
                "HDOP", if (s.hdop >= 0) fmt(s.hdop, 1) else "—",
                modifier = Modifier.fillMaxWidth(), dim = s.hdop < 0,
            )
        },
    )
    RowOf(
        {
            StatCell(
                "ALTITUDE", if (s.alt >= 0) fmt(s.alt, 0) else "—", "m",
                modifier = Modifier.fillMaxWidth(), dim = s.alt < 0,
            )
        },
        {
            StatCell(
                "TIMER", if (s.tmrSt == 0) "READY" else fmt(s.tmrS, 2) + " s",
                accent = when (s.tmrSt) { 1 -> HudColors.Amber; 2 -> HudColors.Green; else -> HudColors.TextDim },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
    StatCell(
        "POSITION",
        if (s.hasPosition) "${fmt(s.lat, 5)}, ${fmt(s.lon, 5)}" else "NO FIX",
        accent = if (s.hasPosition) HudColors.Green else HudColors.Amber,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    RowOf(
        { StatCell("DISPLAY", fmt(s.fps, 0), "fps", modifier = Modifier.fillMaxWidth()) },
        {
            StatCell(
                "FREE HEAP", (s.heap / 1024L).toString(), "kB",
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
