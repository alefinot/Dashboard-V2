package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.storage.AppRoot
import com.alefinot.dashboardpp.storage.StorageCopy
import com.alefinot.dashboardpp.trips.AccelRun
import com.alefinot.dashboardpp.trips.TripSummary
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudGlyphAction
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.Sparkline
import com.alefinot.dashboardpp.ui.components.bucketMax
import com.alefinot.dashboardpp.ui.components.hudDuration
import com.alefinot.dashboardpp.ui.components.hudNum
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.components.StatCell
import com.alefinot.dashboardpp.ui.map.RouteMap
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudeMetric
import com.alefinot.dashboardpp.ui.theme.HudeMetricUnit
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudeTable
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.ui.theme.hudGlass
import com.alefinot.dashboardpp.viewmodel.TripRow
import com.alefinot.dashboardpp.viewmodel.TripsViewModel
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recorded drive, arranged the way the owner actually reads it.
 *
 * The drive itself is the page: the map is pinned as a hero over the top ~40% of
 * the screen and the figures the owner came for — distance first, then duration,
 * average and peak — are worn at its bottom edge, over a scrim that darkens the
 * ground without hiding it. Everything else scrolls *under* that hero, so the map
 * never leaves while the numbers are being read, and a pan or pinch on the map is
 * never stolen by the list's scroll (the map is not a row in the scroll column).
 * The bar floats over the hero in the same glass the dialogs use: back at the
 * leading end, and the rarer acts — hand the CSV away, hand the GPX away — behind
 * a kebab at the trailing end, because a windscreen glance needs a route and a
 * distance, not two share buttons.
 *
 * Below the hero: the drive's 0-50 runs as rows that answer to the same yellow
 * lines on the map above them, the series charts, and two groups of figures —
 * what the vehicle used, and how trustworthy the log is. Charts are drawn by hand
 * from the CSV — a charting library would be another dependency and a worse fit
 * for a fixed HUD look — and every series is bucketed to a bounded point count so
 * a 12 h trip (43k samples) costs the same paint as a 5 minute one.
 */
@Composable
fun TripDetailScreen(row: TripRow, vm: TripsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val detail = remember(row.file.absolutePath) { vm.detail(row) }
    val sharedStorage by vm.shared.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // The kebab closes on Back before the page does — a sheet that swallows the
    // gesture it hangs over, and then leaves, is two accidents for one press.
    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = !menuOpen) { onDismiss() }

    if (detail == null) {
        Column(
            Modifier.fillMaxSize().background(HudColors.Bg).padding(16.dp),
        ) {
            Text("TRIP UNREADABLE", color = HudColors.Red, style = HudeSectionTitle())
            Spacer(Modifier.height(12.dp))
            Text(
                "File unreadable. It is still on disk until you delete it.",
                color = HudColors.TextDim,
                style = HudeCfgNote(),
            )
            Spacer(Modifier.height(16.dp))
            HudButton("DELETE FILE", style = HudButtonStyle.DANGER, glyph = HudIcons.Bin) {
                vm.delete(row); onDismiss()
            }
            Spacer(Modifier.height(8.dp))
            HudButton("CLOSE", style = HudButtonStyle.SECONDARY, glyph = HudIcons.Close, onClick = onDismiss)
        }
        return
    }

    val s = detail.summary
    val samples = detail.samples
    // The drive's 0-50 runs, recovered from the timer column the file has always carried
    // (trips/runsFrom). Nothing in the file changed to get them, so a drive recorded months ago
    // wears the same yellow it did on the live map while it happened.
    val runs = detail.runs
    // The run whose result this page is holding. Sticky, unlike the live map's card: the route
    // under it is not going anywhere, so the number has no reason to leave before the owner does.
    // One state answers to two doors — a tap on the map's yellow line and a tap on the run's row
    // below set the same value — so the highlighted row and the dressed line can never disagree.
    // Keyed on the file for the same reason the detail itself is — if this composable is ever handed
    // a different drive without leaving the screen, a number from the drive before it must not stay
    // on the page.
    var shownRun by remember(row.file.absolutePath) { mutableStateOf<AccelRun?>(null) }

    val points = remember(samples) {
        samples.filter { it.snapshot.hasPosition }
            .map { GeoPoint(it.snapshot.lat, it.snapshot.lon) }
    }

    // The screen sits on the page's flat black. Two panes, fixed: the hero keeps its
    // ground and the scroll column gets whatever is left.
    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── The hero: the drive's ground, pinned. Not inside the scroll column,
            // so a map pan is a map pan and never a flung list. ──
            Box(Modifier.fillMaxWidth().weight(2f)) {
                if (points.size >= 2) {
                    RouteMap(
                        points = points,
                        modifier = Modifier.fillMaxSize(),
                        // The drive's launches, in the live map's yellow, over the route the same
                        // file drew. Tapping one reads its result out; tapping it again puts the
                        // answer away — and so does tapping the card, and the run's row below.
                        runs = runs,
                        selectedRun = shownRun,
                        onRunTap = { hit ->
                            shownRun = if (hit.id == shownRun?.id) null else hit
                        },
                    )
                } else {
                    // The same pane, holding the honest answer instead of a route: a drive
                    // with no fixes still has every figure on this page, it just has no shape.
                    Box(
                        Modifier.fillMaxSize().hudCard(fill = HudColors.Msg).padding(12.dp),
                    ) {
                        Text(
                            if (points.isEmpty()) {
                                "No GNSS positions in this trip."
                            } else {
                                "Only one position."
                            },
                            color = HudColors.TextMuted,
                            style = HudeCfgNote(),
                        )
                    }
                }

                // The figures the owner came for, worn at the map's bottom edge: a scrim from
                // nothing to near-black carries them off the ground without hiding it, and the
                // distance is the one figure allowed to be big.
                HeroBand(s, Modifier.align(Alignment.BottomCenter))

                shownRun?.let { run ->
                    // The same card the live page floats over the drive, for the same question. No
                    // fade-out here: on the live map the number leaves because the camera is about
                    // to leave the ground it happened on, and a recorded route is not going anywhere.
                    // The tap is the card's own because this glass lands at the map's edge and a
                    // launch that happened near that edge ends up underneath its own answer: a
                    // number that could only be put away by poking the few pixels of line still
                    // showing would be a number with no way back off the page. Kept under the
                    // floating bar so a launch near the start of the drive is not hidden by it.
                    Box(
                        Modifier.align(Alignment.TopCenter).statusBarsPadding()
                            .padding(top = 64.dp)
                            .clickable { shownRun = null },
                    ) {
                        RunResultCard(run)
                    }
                }
            }

            // ── Everything else, scrolling under the hero. ──
            Column(
                Modifier.weight(3f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                if (runs.isNotEmpty()) {
                    HudEnter(delayMs = HudMotion.STAGGER_MS * 2) {
                        Column(Modifier.fillMaxWidth()) {
                            RunsCard(runs, shownRun) { run ->
                                shownRun = if (run.id == shownRun?.id) null else run
                            }
                        }
                    }
                }

                HudEnter(delayMs = HudMotion.STAGGER_MS * 3) {
                    Column(Modifier.fillMaxWidth()) {
                        ChartRow(
                            title = "SPEED",
                            unit = "km/h",
                            values = bucketMax(samples.map { it.snapshot.spd }, CHART_POINTS),
                            accent = HudColors.Cyan,
                        )
                    }
                }
                val altitude = samples.filter { it.snapshot.alt >= 0.0 }
                if (altitude.size >= 2) {
                    HudEnter(delayMs = HudMotion.STAGGER_MS * 4) {
                        Column(Modifier.fillMaxWidth()) {
                            ChartRow(
                                title = "ALTITUDE",
                                unit = "m",
                                values = bucketMax(altitude.map { it.snapshot.alt }, CHART_POINTS),
                                accent = HudColors.Amber,
                            )
                        }
                    }
                }
                val eco = samples.filter { it.snapshot.ikml > 0.0 }
                if (eco.size >= 2) {
                    HudEnter(delayMs = HudMotion.STAGGER_MS * 5) {
                        Column(Modifier.fillMaxWidth()) {
                            ChartRow(
                                title = "INSTANT ECONOMY",
                                unit = "km/L",
                                values = bucketMax(eco.map { it.snapshot.ikml }, CHART_POINTS),
                                accent = HudColors.Green,
                            )
                        }
                    }
                }

                HudEnter(delayMs = HudMotion.STAGGER_MS * 6) {
                    Column(Modifier.fillMaxWidth()) {
                        VehicleGroup(s)
                    }
                }
                HudEnter(delayMs = HudMotion.STAGGER_MS * 7) {
                    Column(Modifier.fillMaxWidth()) {
                        LogQualityGroup(s)
                    }
                }

                Spacer(Modifier.height(10.dp))
                // Where the file the kebab just handed over actually lives, in the same words the
                // Trips header and the settings page use. Said here because this is the moment a user
                // wonders where a drive went, and because the CSV is no longer copied anywhere to be
                // shared: the share sheet opens the file in that folder, so the folder is the thing
                // worth knowing. Only the CSV — the GPX is built for the occasion and lives in the cache.
                Text(
                    StorageCopy.tripFileNote(
                        sharedStorage, AppRoot.grantPath(android.os.Build.VERSION.SDK_INT),
                        AppRoot.sharedRootLabel(), AppRoot.TRIPS,
                    ),
                    color = HudColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                HudButton("DELETE THIS TRIP", style = HudButtonStyle.DANGER, glyph = HudIcons.Bin) {
                    confirmDelete = true
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // ── The bar: floating glass over the hero. It owns its own status-bar
        // padding because nothing above this route applies one — the page is pushed
        // over the tabs, full-bleed, and the map wants the whole hero. ──
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)
                .hudGlass(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudGlyphAction(
                glyph = HudIcons.Chevron,
                description = "Close this trip",
                modifier = Modifier.rotate(180f),
                onClick = onDismiss,
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.startMs)),
                    color = HudColors.TextPrimary,
                    style = HudeHeading().copy(fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Whose drive this was. The answer comes from the file's own first line, not
                // from the app: it was written when the drive started, and the garage may
                // have been renamed or emptied since then [plan §6].
                Text(
                    vehicleLine(row),
                    color = HudColors.TextMuted,
                    style = HudeCfgNote(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HudGlyphAction(
                glyph = HudIcons.More,
                description = "Share this trip",
                onClick = { menuOpen = !menuOpen },
            )
        }

        // ── The kebab's sheet. Its own dim: like the confirm box, the only ways out
        // are one of the two rows, the kebab itself, and Back. ──
        if (menuOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { menuOpen = false },
                    ),
            )
            Column(
                Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 62.dp, end = 16.dp)
                    .hudGlass(),
            ) {
                // One mark for both shares: a file leaving this system is one act whoever the
                // file is for, and the word says which format the sheet is being handed.
                MenuRow(HudIcons.ExportFile, "SHARE CSV") {
                    menuOpen = false
                    context.startActivity(vm.shareCsv(row))
                }
                MenuRow(HudIcons.ExportFile, "SHARE GPX") {
                    menuOpen = false
                    context.startActivity(vm.shareGpx(row))
                }
            }
        }
    }

    if (confirmDelete) {
        HudConfirmDialog(
            message = "Delete this trip? The CSV goes away.",
            confirmLabel = "DELETE TRIP",
            confirmStyle = HudButtonStyle.DANGER,
            // Rule 7 holds inside the dialog too: destroy wears the bin.
            confirmGlyph = HudIcons.Bin,
            onConfirm = {
                confirmDelete = false
                vm.delete(row)
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * The hero's figures: the distance big enough to read through a windscreen, and
 * the three figures that frame it at the page's perf size. It is a scrim rather
 * than a card — the drive under it is the subject, and a framed card on the map
 * would be a window over the thing the owner came to look at.
 */
@Composable
private fun HeroBand(s: TripSummary, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(112.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Black.copy(alpha = 0.62f),
                    1f to Color.Black.copy(alpha = 0.94f),
                ),
            )
            .padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text("DISTANCE", color = HudColors.TextMuted, style = HudeFormLabel())
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        hudNum(s.distanceKm, 2),
                        color = HudColors.TextPrimary,
                        style = HudeMetric().copy(brush = HudBrushes.title),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("km", color = HudColors.TextMuted, style = HudeMetricUnit())
                }
            }
            Spacer(Modifier.weight(1f))
            HeroFig("DURATION", hudDuration(s.durationMs))
            HeroFig("AVG", hudNum(s.avgSpeed, 1), "km/h")
            HeroFig("MAX", hudNum(s.maxSpeed, 1), "km/h")
        }
    }
}

/** One secondary figure in the hero band: the caption over the number, right after its siblings. */
@Composable
private fun HeroFig(label: String, value: String, unit: String = "") {
    Column(
        Modifier.padding(start = 18.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(label, color = HudColors.TextMuted, style = HudeFormLabel())
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = HudColors.TextPrimary, style = HudePerfValue())
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = HudColors.TextDim, style = HudePerfLabel())
            }
        }
    }
}

/**
 * The drive's 0-50 launches, oldest first — the list under the map that says which
 * yellow line was which. Tapping a row does what tapping the line does: the number
 * floats over the route and the line dresses for it. The quickest run earns the
 * BEST mark; there is no prize for the slowest, so it is only drawn where a
 * quickest actually exists.
 */
@Composable
private fun RunsCard(runs: List<AccelRun>, shownRun: AccelRun?, onRun: (AccelRun) -> Unit) {
    SectionCaps("0-50 RUNS")
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    val best = if (runs.size > 1) runs.minByOrNull { it.seconds } else null
    runs.forEachIndexed { i, run ->
        val selected = run.id == shownRun?.id
        Row(
            // The press before the card, so the whole tile takes the scale down with it.
            Modifier.fillMaxWidth().padding(bottom = 6.dp)
                .hudPressable(role = Role.Button, onClick = { onRun(run) })
                .hudCard(
                    fill = if (selected) HudColors.CardInner else HudColors.Msg,
                    borderColor = if (selected) HudColors.Cyan else HudColors.CardHairline,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${i + 1}",
                color = HudColors.TextDim,
                style = HudePerfLabel(),
                modifier = Modifier.width(22.dp),
            )
            Text(clock.format(Date(run.startedAtMs)), color = HudColors.TextMuted, style = HudeTable())
            Spacer(Modifier.weight(1f))
            if (run.id == best?.id) {
                Text("BEST", color = HudColors.Green, style = HudeSectionTitle(0.2f / 13f))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                "${hudNum(run.finishSpeedKmh, 0)} km/h",
                color = HudColors.TextDim,
                style = HudePerfLabel(),
            )
            Spacer(Modifier.width(12.dp))
            // The number the unit timed, in the same yellow the line on the map is drawn in:
            // row and line are one measurement wearing two ink.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hudNum(run.seconds, 2), color = HudColors.Yellow, style = HudePerfValue())
                Spacer(Modifier.width(3.dp))
                Text("s", color = HudColors.TextDim, style = HudePerfLabel())
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** A group's name, in the page's `.section-title` caps. */
@Composable
private fun SectionCaps(text: String) {
    Text(text, color = HudColors.Cyan, style = HudeSectionCaps())
    Spacer(Modifier.height(8.dp))
}

/** One row of a glass sheet: the mark, the word, and nothing else it has to say. */
@Composable
private fun MenuRow(glyph: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().hudPressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            glyph,
            contentDescription = null, // the word beside it is the label; a description would read twice
            modifier = Modifier.size(HudGlyphSize.Action.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = HudColors.TextPrimary, style = HudeFormLabel())
    }
}

/**
 * Point budget for the hand-drawn charts. A bucketed 600-point polyline already
 * exceeds the pixel width of the widest phone screen, so anything past this only
 * costs paint time; the bucketing keeps peaks rather than averaging them away.
 */
private const val CHART_POINTS = 600

@Composable
private fun ChartRow(title: String, unit: String, values: List<Double>, accent: Color) {
    // Each chart sits on a `.perf-card` tile: rgba(18,25,38,.8), 8px radius, hairline.
    Column(
        Modifier.fillMaxWidth()
            .hudCard(fill = HudColors.CardInner)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = HudColors.Cyan, style = HudeSectionTitle(0.2f / 13f))
            Spacer(Modifier.width(8.dp))
            // Peak and average, because a spike and a trend read the same shape apart and
            // the trace alone makes the owner guess which one they just saw.
            Text(
                "peak ${hudNum(values.maxOrNull(), if (unit == "m") 0 else 1)}" +
                    " · avg ${hudNum(values.average(), if (unit == "m") 0 else 1)} $unit",
                color = accent,
                style = HudePerfValue(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Sparkline(values, Modifier.fillMaxWidth().height(96.dp), line = accent, fill = accent)
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * The one line naming whose drive this is. A file with no tag says so in words
 * rather than showing a blank: a blank reads like a bug, and the honest answer is
 * that the file does not know. The MAC is shown when the file has it - it is the
 * only identity left when the card it was filed under has since been deleted.
 */
private fun vehicleLine(row: TripRow): String = when {
    row.vehicleId == null ->
        "No vehicle recorded."
    else -> buildString {
        append("Vehicle: ").append(row.vehicleName ?: row.vehicleId)
        if (!row.inGarage) append(" (removed from garage)")
        row.eff?.let { append(" - Dashboard++ ").append(it) }
    }
}

@Composable
private fun GridRow(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { first() }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { second() }
    }
    Spacer(Modifier.height(8.dp))
}

/** What the drive cost: fuel, economy, and the two things worth watching under the hood. */
@Composable
private fun VehicleGroup(s: TripSummary) {
    SectionCaps("VEHICLE")
    GridRow(
        { StatCell("FUEL USED", hudNum(s.fuelUsedL, 2), "L", modifier = Modifier.fillMaxWidth()) },
        { StatCell("ECONOMY", hudNum(s.economyKml, 2), "km/L", modifier = Modifier.fillMaxWidth()) },
    )
    GridRow(
        {
            StatCell(
                "BATTERY", "${hudNum(s.minVolts, 1)}–${hudNum(s.maxVolts, 1)}", "V",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        {
            StatCell(
                "COOLANT", "${hudNum(s.minTemp, 0)}–${hudNum(s.maxTemp, 0)}", "°C",
                modifier = Modifier.fillMaxWidth(), dim = s.maxTemp <= 0.0,
            )
        },
    )
    Spacer(Modifier.height(10.dp))
}

/** How much of this drive the log can actually vouch for. */
@Composable
private fun LogQualityGroup(s: TripSummary) {
    SectionCaps("LOG QUALITY")
    GridRow(
        {
            StatCell(
                "GNSS FIX", "${s.fixPct}%",
                accent = if (s.fixPct > 60) HudColors.Green else HudColors.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        {
            StatCell(
                "GNSS TRACK", hudNum(s.gnssKm, 2), "km",
                modifier = Modifier.fillMaxWidth(), dim = s.gnssKm <= 0.0,
            )
        },
    )
    GridRow(
        { StatCell("DASHBOARD++ TRIP", hudNum(s.espTripKm, 2), "km", modifier = Modifier.fillMaxWidth()) },
        {
            StatCell(
                "LOG GAPS", s.gaps.toString(),
                unit = if (s.gapSeconds > 0) "${s.gapSeconds} s lost" else "",
                accent = if (s.gaps == 0) HudColors.Green else HudColors.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
    GridRow(
        { StatCell("SAMPLES", s.samples.toString(), modifier = Modifier.fillMaxWidth()) },
        // No partner cell: a lone sample count would have to make up a figure to sit beside.
        {},
    )
}
