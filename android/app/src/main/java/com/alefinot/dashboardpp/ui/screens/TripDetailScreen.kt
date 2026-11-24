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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.alefinot.dashboardpp.trips.TripDetail
import com.alefinot.dashboardpp.trips.TripSummary
import com.alefinot.dashboardpp.ui.components.Channel
import com.alefinot.dashboardpp.ui.components.HudAccordion
import com.alefinot.dashboardpp.ui.components.HudAccordionBody
import com.alefinot.dashboardpp.ui.components.HudAccordionHeader
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudGlyphAction
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.TrendCard
import com.alefinot.dashboardpp.ui.components.TrendViewState
import com.alefinot.dashboardpp.ui.components.hudDuration
import com.alefinot.dashboardpp.ui.components.hudNum
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.map.RouteMap
import com.alefinot.dashboardpp.ui.map.rememberAdaptiveRouteLevel
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
 * Below the hero: the drive's 0-50 runs behind a dropdown (the ten quickest, not
 * every launch the file ever had — see `RunSelection.QUICKEST`), one trend chart that zooms, and two
 * ledger groups of figures — what the vehicle used, and how trustworthy the log is.
 *
 * The page is **painted before it is read** (maintainer, 2026-11-24). A twelve-hour
 * file is ~43 000 rows, and parsing it inside composition meant the first frame was
 * a full parse of the drive; the loading state is now a real state of this screen,
 * painted on the same frame the page opens, and the figures arrive when the reader
 * finishes on the IO dispatcher. Charts and the route are built in that same pass,
 * so nothing here ever walks the samples again — a recomposition costs a repaint,
 * not a re-parse.
 */
@Composable
fun TripDetailScreen(row: TripRow, vm: TripsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // One pass over the file, off the main thread, every time the page opens: no cache, so a drive
    // that was deleted or replaced on disk can never be shown from a stale copy.
    val state by produceState<DetailState>(DetailState.Loading, row.file.absolutePath) {
        value = DetailState.Loading
        value = try {
            vm.detail(row)?.let { DetailState.Ready(it) } ?: DetailState.Unreadable
        } catch (e: Exception) {
            DetailState.Unreadable
        }
    }
    val detail = (state as? DetailState.Ready)?.detail
    val sharedStorage by vm.shared.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // The kebab closes on Back before the page does — a sheet that swallows the
    // gesture it hangs over, and then leaves, is two accidents for one press.
    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = !menuOpen) { onDismiss() }

    if (state == DetailState.Unreadable) {
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

    val summary = detail?.summary
    val runs = detail?.runs.orEmpty()
    // The run whose result this page is holding. Sticky, unlike the live map's card: the route
    // under it is not going anywhere, so the number has no reason to leave before the owner does.
    // One state answers to two doors — a tap on the map's yellow line and a tap on the run's row
    // below set the same value — so the highlighted row and the dressed line can never disagree.
    // Keyed on the file for the same reason the detail itself is.
    var shownRun by remember(row.file.absolutePath) { mutableStateOf<AccelRun?>(null) }

    // The drive's line at every rung the reader prepared, converted for osmdroid once per load.
    // The conversion is what the map's own guard keys on, so a recomposition that changes nothing
    // hands it the same two lists it already drew.
    val routes = remember(detail) {
        detail?.routes.orEmpty().map { rung ->
            rung.map { GeoPoint(it.lat, it.lon) }
        }
    }
    // Below 20 fps the page draws a coarser rung of the same drive: 3 000 points down to 400,
    // monotonically, and only when App Settings says it may (AUTO QUALITY, on by default).
    val adaptiveOn by vm.adaptiveQuality.collectAsStateWithLifecycle(initialValue = true)
    val rung = rememberAdaptiveRouteLevel(
        driveKey = row.file.absolutePath,
        enabled = adaptiveOn && routes.size > 1,
        rungs = routes.size,
    )
    // The rung this page has spent, never past the last one its own drive produced.
    val points = routes.getOrNull(rung.coerceAtMost(routes.lastIndex)).orEmpty()

    // The page sits on the page's flat black. Two panes, fixed: the hero keeps its
    // ground and the scroll column gets whatever is left.
    Box(Modifier.fillMaxSize().background(HudColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── The hero: the drive's ground, pinned. Not inside the scroll column,
            // so a map pan is a map pan and never a flung list. ──
            Box(Modifier.fillMaxWidth().weight(2f)) {
                when {
                    summary == null -> LoadingPane()
                    points.size >= 2 -> RouteMap(
                        points = points,
                        // Framing belongs to the drive, not the rung: stepping down must not move
                        // a camera the owner has panned.
                        fitKey = row.file.absolutePath,
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
                    else -> {
                        // The same pane, holding the honest answer instead of a route: a drive
                        // with no fixes still has every figure on this page, it just has no shape.
                        Box(
                            Modifier.fillMaxSize().hudCard(fill = HudColors.Msg).padding(12.dp),
                            contentAlignment = Alignment.Center,
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
                }

                // The figures the owner came for, worn at the map's bottom edge: a scrim from
                // nothing to near-black carries them off the ground without hiding it, and the
                // distance is the one figure allowed to be big.
                summary?.let { HeroBand(it, Modifier.align(Alignment.BottomCenter)) }

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
                if (detail != null) {
                    DetailBody(
                        detail = detail,
                        shownRun = shownRun,
                        onRun = { run -> shownRun = if (run.id == shownRun?.id) null else run },
                        sharedStorage = sharedStorage,
                        onDelete = { confirmDelete = true },
                    )
                }
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
                    // The file's name carries the start even before the summary lands: the page
                    // must be able to say what it is opening on its first frame.
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(Date(summary?.startMs ?: row.startMs)),
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
 * What the detail page is doing right now.
 *
 * A drive is read off disk on the IO dispatcher, so "no detail yet" is the page's ordinary first
 * state rather than a failure. It used to be a local `null` that the page had to interpret, and
 * the only meaning it could have had was "unreadable" — which is why the loading state is a type
 * now: the two answers must never be mistaken for one another.
 */
private sealed interface DetailState {
    data object Loading : DetailState
    data class Ready(val detail: TripDetail) : DetailState
    data object Unreadable : DetailState
}

/**
 * The hero while the file is being read: the page's own pane, holding the one sentence that
 * answers "did my tap do anything?".
 *
 * Same shape and ink as the no-position pane under it, because it *is* the same pane — the page
 * has a ground to show or it has a sentence to show, and neither state is allowed to be a blank.
 */
@Composable
private fun LoadingPane() {
    Box(
        Modifier.fillMaxSize().hudCard(fill = HudColors.Msg).padding(12.dp),
        // Centred, not pinned to the top-left: the floating bar owns the top of this pane, and a
        // sentence half under it answers nothing (bench, 2026-11-24). It goes where the route will.
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "reading the drive\u2026",
            color = HudColors.TextMuted,
            style = HudeCfgNote(),
        )
    }
}

/**
 * Everything under the hero: the drive's launches, its charts, and the two figure groups.
 *
 * Split out of [TripDetailScreen] so the loading state and the loaded page are two shapes of one
 * screen rather than one function with a `detail != null` wrapped around most of its body — and so
 * a recomposition caused by a tapped run cannot re-read a chart or a figure group's inputs.
 */
@Composable
private fun DetailBody(
    detail: TripDetail,
    shownRun: AccelRun?,
    onRun: (AccelRun) -> Unit,
    sharedStorage: Boolean,
    onDelete: () -> Unit,
) {
    // Collapsed by default (maintainer, 2026-11-24): ten rows of earlier launches pushed the
    // charts below them off the first screen, and the map above already carries the lines.
    var runsOpen by remember(detail.file.absolutePath) { mutableStateOf(false) }

    // The chart's window, **once per opened drive**. The chips and the plot read the same object, so
    // they can never disagree about which minute of the drive the owner is standing in, and switching
    // SPEED → ALTITUDE keeps the window: that continuity is the whole point of putting a time axis
    // under the trace. Keyed on the file, because a different drive is a different drive.
    val viewState = remember(detail.file.absolutePath) { TrendViewState() }
    val channels = remember(detail) { trendChannels(detail) }
    val spanSec = remember(detail) { driveSpanSec(detail) }
    // Only when the drive's length changes — never on recomposition, or a zoom the owner made would be
    // thrown away by an unrelated repaint.
    LaunchedEffect(spanSec) { viewState.openAt(spanSec) }

    if (detail.runs.isNotEmpty()) {
        HudEnter(delayMs = HudMotion.STAGGER_MS * 2) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                HudAccordion(open = runsOpen) {
                    HudAccordionHeader(
                        title = "0-50 RUNS",
                        open = runsOpen,
                        onClick = { runsOpen = !runsOpen },
                    )
                    HudAccordionBody(open = runsOpen) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            RunsCard(detail.runs, detail.runsSeen, shownRun, onRun)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    HudEnter(delayMs = HudMotion.STAGGER_MS * 3) {
        Column(Modifier.fillMaxWidth()) {
            TrendCard(
                channels = channels,
                state = viewState,
                startMs = detail.summary.startMs,
            )
        }
    }

    HudEnter(delayMs = HudMotion.STAGGER_MS * 4) {
        Column(Modifier.fillMaxWidth()) {
            VehicleGroup(detail.summary)
        }
    }
    HudEnter(delayMs = HudMotion.STAGGER_MS * 5) {
        Column(Modifier.fillMaxWidth()) {
            LogQualityGroup(detail.summary)
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
        onDelete()
    }
    Spacer(Modifier.height(24.dp))
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
 * The drive's 0-50 launches, oldest first — the list inside the map's dropdown that says which
 * yellow line was which. Tapping a row does what tapping the line does: the number floats over the
 * route and the line dresses for it.
 *
 * The list is at most ten rows (a recorded drive keeps its **quickest** ten — see
 * [com.alefinot.dashboardpp.trips.RunSelection.QUICKEST]), so the line above it says which ten they
 * are when the file held more: `10 of 27 runs` is a short list, not a lie about the drive. The
 * row's number is [AccelRun.id], not its position in this list, for the same reason — the 14th
 * launch of the drive is not made the 1st by being the only one kept.
 */
@Composable
private fun RunsCard(
    runs: List<AccelRun>,
    seen: Int,
    shownRun: AccelRun?,
    onRun: (AccelRun) -> Unit,
) {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    val best = if (runs.size > 1) runs.minByOrNull { it.seconds } else null

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                runs.size == 1 -> "1 run"
                seen > runs.size -> "${runs.size} of $seen runs"
                else -> "${runs.size} runs"
            },
            color = HudColors.TextMuted,
            style = HudeCfgNote(),
        )
        Spacer(Modifier.weight(1f))
        if (best != null) {
            Text(
                "BEST ${hudNum(best.seconds, 2)} s",
                color = HudColors.Green,
                style = HudeSectionTitle(0.2f / 13f),
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    runs.forEach { run ->
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
                "${run.id}",
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
 * The channels the trend card offers, in chip order, built once per read file.
 *
 * A channel appears only if the drive logged it — a night run with no economy readings gets three
 * chips, not four, and the card says so rather than drawing a flat line at zero, which would read as a
 * measurement of zero. Order is fixed, and so is the ink: the card paints channel *n* in the colour
 * the page has always used for that quantity, so SPEED stays cyan when ALTITUDE drops out.
 *
 * For SPEED the two figures come from [TripSummary] rather than from the channel, deliberately: they
 * are the same numbers the hero band wears, and a chart whose `avg` disagreed with the hero would make
 * the owner doubt one of them. ALTITUDE, ECONOMY and COOLANT have no summary figure that means "mean
 * of these samples" (`economyKml` is fuel over distance, and `minTemp`/`maxTemp` are a range, not a
 * mean), so theirs is the channel's own mean — which is also exactly what the dashed rule is drawn at.
 */
private fun trendChannels(detail: TripDetail): List<Channel> {
    val s = detail.summary
    val speed = detail.series.speed
    val altitude = detail.series.altitude
    val economy = detail.series.economy
    val temp = detail.series.temp
    val out = ArrayList<Channel>(4)
    if (speed.isPlottable) {
        out += Channel(
            label = "SPEED",
            unit = "km/h",
            digits = 1,
            series = speed,
            avg = s.avgSpeed,
            peak = s.maxSpeed,
        )
    }
    if (altitude.isPlottable) {
        out += Channel(
            label = "ALTITUDE",
            unit = "m",
            digits = 0,
            series = altitude,
            avg = altitude.meanValue(),
            peak = altitude.maxValue(),
        )
    }
    if (economy.isPlottable) {
        out += Channel(
            label = "ECONOMY",
            unit = "km/L",
            digits = 2,
            series = economy,
            avg = economy.meanValue(),
            peak = economy.maxValue(),
        )
    }
    if (temp.isPlottable) {
        out += Channel(
            // The name the ledger row directly below it wears, so the chip and the figure it comes from
            // are never two words for one thing.
            label = "COOLANT",
            unit = "°C",
            digits = 0,
            series = temp,
            avg = temp.meanValue(),
            peak = temp.maxValue(),
        )
    }
    return out
}

/**
 * How long the drive is, in whole seconds: the last second **any** channel holds a sample at.
 *
 * The channels are not co-extensive (economy drops rows, altitude drops rows, coolant drops rows), so
 * the widest one is the drive. The chart's window is measured in these seconds, which is also what
 * [trendEnvelope] indexes on, so a pan or a zoom never re-derives anything from a wall clock.
 */
private fun driveSpanSec(detail: TripDetail): Int = maxOf(
    detail.series.speed.lastSecond(),
    maxOf(detail.series.altitude.lastSecond(), detail.series.economy.lastSecond()),
    detail.series.temp.lastSecond(),
)

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

/** What the drive cost: fuel, economy, and the two things worth watching under the hood. */
@Composable
private fun VehicleGroup(s: TripSummary) {
    LedgerGroup(label = "VEHICLE") {
        LedgerRow(label = "FUEL USED", value = hudNum(s.fuelUsedL, 2), unit = "L", topRule = false)
        LedgerRow(label = "ECONOMY", value = hudNum(s.economyKml, 2), unit = "km/L")
        LedgerRow(
            label = "BATTERY",
            value = "${hudNum(s.minVolts, 1)}–${hudNum(s.maxVolts, 1)}",
            unit = "V",
        )
        LedgerRow(
            label = "COOLANT",
            value = "${hudNum(s.minTemp, 0)}–${hudNum(s.maxTemp, 0)}",
            unit = "°C",
            // A drive that never saw the coolant bus recedes rather than explains itself (decision 2).
            ink = if (s.maxTemp <= 0.0) HudColors.TextDim else HudColors.TextPrimary,
        )
    }
    Spacer(Modifier.height(10.dp))
}

/** How much of this drive the log can actually vouch for. */
@Composable
private fun LogQualityGroup(s: TripSummary) {
    LedgerGroup(label = "LOG QUALITY") {
        LedgerRow(
            label = "GNSS FIX",
            value = "${s.fixPct}%",
            // The bar carries what the number cannot: that this is a proportion of the whole drive.
            ink = if (s.fixPct > 60) HudColors.Green else HudColors.Amber,
            bar = s.fixPct.toDouble(),
            topRule = false,
        )
        LedgerRow(
            label = "GNSS TRACK",
            value = hudNum(s.gnssKm, 2),
            unit = "km",
            ink = if (s.gnssKm <= 0.0) HudColors.TextDim else HudColors.TextPrimary,
        )
        // The unit's own cumulative counter, not this drive's distance — hence a figure that can
        // exceed the hero's. The maintainer refused renaming it on 2026-11-24, so the wording stands
        // and the hero band above is what says how far this drive went.
        LedgerRow(label = "DASHBOARD++ TRIP", value = hudNum(s.espTripKm, 2), unit = "km")
        LedgerRow(
            label = "LOG GAPS",
            value = s.gaps.toString(),
            unit = if (s.gapSeconds > 0) "${s.gapSeconds} s lost" else "",
            ink = if (s.gaps == 0) HudColors.Green else HudColors.Amber,
        )
        LedgerRow(label = "SAMPLES", value = s.samples.toString())
    }
    Spacer(Modifier.height(10.dp))
}
