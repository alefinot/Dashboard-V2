package com.alefinot.dashboardpp.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.storage.AppRoot
import com.alefinot.dashboardpp.storage.StorageCopy
import com.alefinot.dashboardpp.trips.TripRuntime
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.hudBytes
import com.alefinot.dashboardpp.ui.components.LocalEnterActive
import com.alefinot.dashboardpp.ui.components.hudDuration
import com.alefinot.dashboardpp.ui.components.hudNum
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.trips.TripRuntimeState
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudeBody
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgLive
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudeTable
import com.alefinot.dashboardpp.viewmodel.TripFilterOption
import com.alefinot.dashboardpp.viewmodel.TripRow
import com.alefinot.dashboardpp.viewmodel.TripsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsTab(vm: TripsViewModel, onOpen: (TripRow) -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val visible by vm.visibleRows.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val options by vm.filterOptions.collectAsStateWithLifecycle()
    val runtime by TripRuntime.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val storage by vm.storageBytes.collectAsStateWithLifecycle()
    val sharedStorage by vm.shared.collectAsStateWithLifecycle()
    val elsewhere by vm.elsewhere.collectAsStateWithLifecycle()
    var refreshKey by remember { mutableStateOf(0) }

    val inView = LocalEnterActive.current
    var visits by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(inView, refreshKey, runtime.recording) {
        if (!inView) return@LaunchedEffect
        visits++
        vm.refresh()
    }

    Column(
        Modifier.hudPage()
            .padding(horizontal = HudPage.Gutter)
            .padding(top = 10.dp),
    ) {
        HudPageHeader(title = "Trips") {
            HudBanner(
                if (visible.size == rows.size) {
                    "${StorageCopy.drives(rows.size)}  ·  ${formatBytes(storage)}"
                } else {
                    "${visible.size} of ${StorageCopy.drives(rows.size)}  ·  ${formatBytes(storage)}"
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                StorageCopy.tripsNote(
                    sharedStorage, AppRoot.grantPath(android.os.Build.VERSION.SDK_INT),
                    AppRoot.sharedRootLabel(), AppRoot.TRIPS,
                ),
                color = HudColors.TextDim,
                style = HudeCfgNote(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            elsewhere?.let { strays ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "+ ${StorageCopy.drives(strays.count)} from " +
                        "another folder: ${strays.path}",
                    color = HudColors.TextDim,
                    style = HudeCfgNote(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (options.size > 1) {
            TripFilterChips(options, filter) { vm.setFilter(it) }
            Spacer(Modifier.height(10.dp))
        }

        if (message != null) {
            Text(message.orEmpty(), color = HudColors.Amber, style = HudeCfgHelp())
            Spacer(Modifier.height(8.dp))
        }

        if (runtime.recording) {
            LiveTripRow(runtime)
            Spacer(Modifier.height(10.dp))
        }

        if (visible.isEmpty() && !runtime.recording) {
            Column(
                Modifier.fillMaxWidth()
                    .hudCard()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (rows.isEmpty()) {
                    Text("No trips recorded yet.", color = HudColors.TextPrimary, style = HudeBody())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Drives are logged automatically while the app is open and the " +
                            "dashboard is linked.",
                        color = HudColors.TextDim,
                        style = HudeCfgNote(),
                    )
                } else {
                    val chip = options.firstOrNull { it.value == filter }
                    Text(
                        if (chip == null) "No drives match the current filter."
                        else "No drives filed under ${chip.label}.",
                        color = HudColors.TextPrimary,
                        style = HudeBody(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${rows.size} drive${if (rows.size == 1) "" else "s"} recorded in " +
                            "total - pick ALL to see them.",
                        color = HudColors.TextDim,
                        style = HudeCfgNote(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        TripList(visible, visits, onOpen, Modifier.weight(1f))
    }
}

@Composable
private fun TripFilterChips(options: List<TripFilterOption>, current: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            val on = opt.value == current
            Box(
                Modifier
                    .hudCard(
                        shape = HudRadius.FieldShape,
                        fill = if (on) HudColors.Cyan.copy(alpha = 0.12f) else HudColors.CardFill,
                        borderColor = if (on) HudColors.CyanDim else HudColors.CardHairline,
                    )
                    .clickable { onSelect(opt.value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    opt.label,
                    color = if (on) HudColors.Cyan else HudColors.TextMuted,
                    style = HudeSectionCaps(),
                )
            }
        }
    }
}

@Composable
private fun TripList(
    rows: List<TripRow>,
    visits: Int,
    onOpen: (TripRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by remember(visits) { mutableStateOf(visits > 0 && !HudMotion.enabled) }
    LaunchedEffect(visits, rows.size) {
        if (visits > 0 && rows.isNotEmpty()) revealed = true
    }
    val staggered = minOf(rows.size, 8)
    val rampMs = (HudMotion.ENTRANCE_MS + HudMotion.STAGGER_MS * maxOf(staggered - 1, 0)).toFloat()
    val ramp by animateFloatAsState(
        if (revealed) 1f else 0f,
        tween(rampMs.toInt(), easing = LinearEasing),
        label = "tripsReveal",
    )
    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.file.absolutePath }) { i, row ->
            val start = (minOf(i, staggered - 1) * HudMotion.STAGGER_MS)
            TripCard(
                row,
                onOpen,
                reveal = ((ramp * rampMs - start) / HudMotion.ENTRANCE_MS).coerceIn(0f, 1f),
            )
        }
    }
}

/** The open trip, straight from the recorder's running totals. */
@Composable
private fun LiveTripRow(state: TripRuntimeState) {
    val snap = state.snapshot
    val summary = state.summary
    Box(
        Modifier.fillMaxWidth()
            .hudCard(
                fill = HudColors.Msg,
                borderColor = HudColors.Green.copy(alpha = 0.3f),
            )
            .background(HudColors.Green.copy(alpha = 0.08f), HudRadius.CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("RECORDING", color = HudColors.Green, style = HudeSectionTitle(0f))
                Spacer(Modifier.width(10.dp))
                Text(
                    summary?.let { formatDuration(it.durationMs) } ?: "0:00:00",
                    color = HudColors.TextPrimary,
                    style = HudePerfValue(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(fmtOrDash(summary?.distanceKm, 2))
                    append(" km · ")
                    append(snap?.let { it.spd.toInt().toString() } ?: "—")
                    append(" km/h · ")
                    append(fmtOrDash(summary?.fuelUsedL, 2))
                    append(" L")
                },
                color = HudColors.TextMuted,
                style = HudeCfgHelp(),
            )
        }
    }
}

@Composable
private fun TripCard(row: TripRow, onOpen: (TripRow) -> Unit, reveal: Float = 1f) {
    Box(
        Modifier.fillMaxWidth()
            .alpha(reveal)
            .offset { IntOffset(0, ((1f - reveal) * 8f).dp.roundToPx()) }
            .hudCard()
            .clickable { onOpen(row) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(row.startMs)),
                    color = HudColors.TextPrimary,
                    style = HudeTable(),
                )
                Spacer(Modifier.weight(1f))
                VehicleBadge(row)
            }
            Spacer(Modifier.height(6.dp))
            val s = row.summary
            if (s == null) {
                Text("…", color = HudColors.TextMuted, style = HudeCfgHelp())
            } else {
                Text(
                    "${fmtOrDash(s.distanceKm, 2)} km · ${formatDuration(s.durationMs)}",
                    color = HudColors.Cyan,
                    style = HudeTable(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("max ").append(fmtOrDash(s.maxSpeed, 0)).append(" km/h")
                        append(" · ").append(fmtOrDash(s.fuelUsedL, 2)).append(" L")
                        append(" · ").append(s.fixPct).append("% fix")
                        if (s.gaps > 0) {
                            append(" · ").append(s.gaps).append(" gaps")
                        }
                    },
                    color = HudColors.TextMuted,
                    style = HudeCfgHelp(),
                )
            }
        }
    }
}

@Composable
private fun VehicleBadge(row: TripRow) {
    val unknown = row.vehicleId == null
    val label = if (unknown) "NO VEHICLE" else row.vehicleName ?: "UNNAMED"
    Box(
        Modifier
            .hudCard(
                shape = HudRadius.FieldShape,
                fill = if (unknown) HudColors.Msg else HudColors.Cyan.copy(alpha = 0.08f),
                borderColor = if (unknown) HudColors.Amber.copy(alpha = 0.3f) else HudColors.CyanDim,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            color = if (unknown) HudColors.Amber else HudColors.Cyan,
            style = HudeSectionCaps(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatBytes(bytes: Long): String = hudBytes(bytes)

private fun formatDuration(ms: Long): String = hudDuration(ms)

private fun fmtOrDash(value: Double?, places: Int): String = hudNum(value, places)
