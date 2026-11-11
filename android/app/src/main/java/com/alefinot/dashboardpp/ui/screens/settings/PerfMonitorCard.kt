package com.alefinot.dashboardpp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ui.components.HudAccordion
import com.alefinot.dashboardpp.ui.components.HudAccordionBody
import com.alefinot.dashboardpp.ui.components.HudAccordionHeader
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeBodyFamily
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeMonoFamily
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfRowValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudeTable
import com.alefinot.dashboardpp.ui.theme.hudCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The unit's Performance Monitor, as one more dropdown card in the settings scroll.
 *
 * It used to be a second page: the last row of the scroll opened it full-screen, and a BACK button
 * was the only way out. That made the unit's own numbers a place of their own, with a header, a
 * toolbar and a poller that ran until you navigated away — for figures that only mean something
 * beside the settings they explain, a CPU bar beside the frame rate it is costing, a heap line
 * beside a reboot nobody asked for. So the entry row became the card: it opens the way a group
 * opens, closes the way a group closes, and draws the panel the ESP's own Web UI used to serve.
 *
 * Every number here is the unit talking about itself: `perf` and `health` from `src/bt_rpc.cpp`.
 * Nothing in it is something the app could work out for itself — that is the point. The questions
 * it exists to answer are bench questions: is the display really running at `TARGET_FPS`, how much
 * heap is free and how much of it is one block, how hot is the CPU doing that, and how much room is
 * left in the settings budget.
 *
 * The layout is the deleted page's `#perf-panel`, not a stack of rows:
 *
 *  - **Display | System** side by side on anything tablet-sized, one above the other on a
 *    phone, the way `.perf-grid` reflows below 600 px. Display carries the frame figures;
 *    System carries uptime, the SPI clock, the heap line and the read interval.
 *  - **CPU Performance** and **Memory & Storage** span the grid. Both are `.perf-card`
 *    tiles with a caption line over a 20 dp `--bg-tertiary` bar, and Memory keeps the
 *    segmented flash bar, its legend and the settings-budget line under it.
 *  - **Four tiles, and only four.** The **Bluetooth Link** card and the four bench additions that
 *    used to follow it — task stacks, why the unit is running, the inputs the unit is measuring,
 *    and the spread over the time the card has been open — were all taken out on 2026-11-11 at the
 *    maintainer's request, and the card stopped asking for `live` and `boot` with them. What the
 *    link *is* still gets said once, in the banner over the body: the phone's own name and MTU for
 *    the unit, which is all the panel needs about the radio to be trustworthy about the rest.
 *    [UnitDiag] still parses `live`, `boot` and the link block of `perf`, because the unit still
 *    answers every one of them.
 *  - **Serial Output Monitor** is the one card that did not travel: serial is a cable on the
 *    bench, and this app has no wire to the unit.
 *
 * Reading costs link round trips, and `perf` is the widest reply on the link, so the cadence is a
 * fixed one second rather than a control the page spends a button on. It runs only while the card
 * is open: closing it, or scrolling it out of the list, cancels the loop instead of leaving a
 * poller competing with the settings read that shares the link.
 */
@Composable
fun PerfMonitorCard(
    store: ConfigStore,
    link: BleLink,
    /** Whether this card is the open one; the read follows it, the way the body's draw does. */
    open: Boolean,
    onToggle: () -> Unit,
    /** Why the unit's clock was not set from the phone; null when it was set [plan §4]. */
    timeNote: String? = null,
) {
    val linkState by link.state.collectAsStateWithLifecycle()
    val linked = linkState is BleLinkState.Ready

    // There is no cadence control any more: opening the card is the whole interface, and
    // [PollEvery] is the interval it reads at.
    var perf by remember { mutableStateOf<PerfReadout?>(null) }
    var health by remember { mutableStateOf<HealthReadout?>(null) }
    var error by remember { mutableStateOf("") }
    var lastRead by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }

    /**
     * One round of reads: `perf` for everything the panel draws, and `health` for the one figure
     * `perf` does not carry — the largest single free block. Since the cards that drew `live` and
     * `boot` were taken out, the round is the same every second: there is no longer a slow half
     * worth spacing out, and a `health` reply read once at opening would sit there as if it were
     * current for as long as the card stayed open.
     */
    suspend fun read() {
        reading = true
        try {
            val perfCall = store.action("perf")
            val parsed = if (perfCall.ok) UnitDiag.perf(perfCall.fields) else null
            if (parsed != null) perf = parsed
            val healthCall = store.action("health")
            if (healthCall.ok) health = UnitDiag.health(healthCall.fields) ?: health
            error = when {
                !perfCall.ok && !healthCall.ok ->
                    "the unit did not answer: ${perfCall.error ?: healthCall.error}"
                // An ok reply with nothing in it is its own failure: the unit said yes and sent no
                // numbers, and drawing that as a page full of dashes would blame the unit later.
                perfCall.ok && parsed == null -> "perf answered, but sent no fields"
                !perfCall.ok -> "perf refused: ${perfCall.error}"
                !healthCall.ok -> "health refused: ${healthCall.error}"
                else -> ""
            }
            lastRead = clock()
        } finally {
            // A cancelled round leaves the card anyway; a round that threw must not leave the
            // header stuck saying "reading…" over numbers that stopped moving.
            reading = false
        }
    }

    // One effect drives everything, keyed on the card being open: opening it reads once and then
    // keeps to the interval, and closing it — or scrolling it out of the list, which is what
    // disposes a list item — cancels the loop instead of leaving a poller running on a link the
    // settings read needs. Unlike a group's rows, a monitor behind a closed header would still poll,
    // so the open flag is both the draw condition and the loop's key.
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        read()
        while (isActive) {
            delay(PollEveryMillis)
            if (!isActive) break
            read()
        }
    }

    HudAccordion(open = open, modifier = Modifier.padding(bottom = 14.dp)) {
        HudAccordionHeader(
            title = "Performance Monitor",
            // The other cards count their rows in this slot. A monitor has no rows to count, so the
            // chip says what the numbers are doing instead: reading now, polling now, or — behind a
            // closed header — how stale the figures it is holding are.
            meta = when {
                open && reading -> "reading…"
                open -> "polling every $PollEveryLabel"
                lastRead.isEmpty() -> null
                else -> "last read $lastRead"
            },
            open = open,
            onClick = onToggle,
        )
        HudAccordionBody(open = open) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HudBanner(
                    text = when (val state = linkState) {
                        is BleLinkState.Ready -> "linked to ${state.name} · MTU ${state.mtu}"
                        // A link that is down freezes the figures where they were; the pill has to
                        // say whether there is anything to freeze in the first place.
                        else -> if (perf == null) {
                            "no link — waiting for the unit"
                        } else {
                            "no link — showing the last figures read"
                        }
                    },
                    color = if (linked) HudColors.Green else HudColors.Amber,
                )
                // The clock is part of the link's state, and the only place it is reported is
                // here — the app pushed it on the way in, and if that failed the unit's own
                // stamps are the thing that is wrong, which is a fact about the numbers below.
                timeNote?.let {
                    Text(
                        it,
                        style = HudeCfgNote(),
                        color = HudColors.Amber,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        style = HudeCfgNote(),
                        color = HudColors.Red,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val p = perf
                val h = health
                // `health` reports the largest single free block, which `perf` does not; the System
                // card says that one only when the unit has actually sent it.
                val largestBlock = h?.let { DiagFormat.bytes(it.maxAlloc) } ?: "--"
                // `.perf-grid` is two 1fr tracks above 600 px and one `auto-fit` track below it.
                val twoUp = LocalConfiguration.current.screenWidthDp >= 600

                // Display | System. The row is measured to its taller card and both fill that
                // height, which is what `align-items: stretch` did to the page's grid row.
                if (twoUp) {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DisplayCard(p, Modifier.weight(1f).fillMaxHeight())
                        SystemCard(
                            p = p,
                            largestBlock = largestBlock,
                            lastRead = lastRead,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                } else {
                    DisplayCard(p)
                    SystemCard(p = p, largestBlock = largestBlock, lastRead = lastRead)
                }

                CpuCard(p)
                MemoryCard(p)
            }
        }
    }
}

/**
 * The read's interval while the card is open, and the same interval in the words the header chip
 * and the System card use.
 *
 * A fixed one second, where the page used to offer off/1s/2s/5s/10s from a toolbar button: the
 * card's own open and closed states are the control now, so the only rate that has to be chosen is
 * the one that fits a `perf` reply — the widest frame the link carries — beside the settings writes
 * that share it.
 */
private const val PollEveryMillis = 1_000L

private const val PollEveryLabel = "1 s"

// ─── The page's `.perf-card` recipe ────────────────────────────────────────────

/**
 * `.perf-card`: rgba(18,25,38,.8), 12 px of padding, 8 px corners and the page's
 * `--border-color` hairline. `CardInner` is that fill, `Hairline` is that border, and
 * `HudRadius.CardShape` is its 8 px radius - so this is the page's tile, not a
 * `.card` with different spacing.
 */
@Composable
private fun PerfCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .hudCard(fill = HudColors.CardInner, borderColor = HudColors.Hairline)
            .padding(12.dp),
    ) {
        Text(
            title,
            style = HudeSectionTitle(0.2f / 13f),
            color = HudColors.Cyan,
        )
        // `.perf-card h4 { margin: 0 0 8px 0 }` - the tile's heading sits closer to its
        // first row than `.card h4`'s 5 px, because the grid is denser.
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * `.perf-row`: a dim 12 px label, a white mono figure, 3 px above and below.
 *
 * The label is the part that keeps its width and the figure takes the rest, right-aligned -
 * that is the page's `justify-content: space-between` in Compose, and it matters the moment
 * the column stops being 920 px. In the other order the figure is measured first and claims
 * everything: on a phone a spread line ("now 37108 · min 37108 · max 38160 · avg 37405 over
 * 60 s") ate the row and "free heap" arrived one letter per line (bench, Xiaomi Mi 9 Lite,
 * 2026-11-07). So the label never wraps and the figure is the thing that does.
 */
@Composable
private fun PerfRow(label: String, value: String, warn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = HudePerfLabel(),
            color = HudColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = HudePerfRowValue(),
            color = if (warn) HudColors.Amber else HudColors.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The least room a figure keeps next to its label. The page's flex leaves none; a phone needs some. */
private val RowGap = 12.dp

/**
 * `.perf-bar-label`: muted 12 px caption on the left, the values on the right with the
 * numbers in `strong`. The page leaves the separators muted and paints only the figures
 * white, which is what [parts] carries - each `String to strong` pair is appended with
 * the matching span.
 */
@Composable
private fun CaptionLine(
    label: String,
    parts: List<Pair<String, Boolean>>,
    modifier: Modifier = Modifier,
    monoValues: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = HudePerfLabel(),
            color = HudColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildAnnotatedString {
                for ((text, strong) in parts) {
                    if (strong) {
                        withStyle(
                            SpanStyle(
                                color = HudColors.White,
                                fontFamily = if (monoValues) HudeMonoFamily else HudeBodyFamily,
                                fontWeight = if (monoValues) FontWeight.W600 else FontWeight.W700,
                            ),
                        ) { append(text) }
                    } else {
                        withStyle(SpanStyle(color = HudColors.TextMuted)) { append(text) }
                    }
                }
            },
            style = HudePerfLabel(),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** `.perf-bar`: a 20 px `--bg-tertiary` recess with 4 px corners and a coloured fill. */
@Composable
private fun PerfBar(fraction: Double, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HudColors.Track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color),
        )
    }
}

/** A caption over its bar, 6 px apart, the way every labelled bar on the page is built. */
@Composable
private fun BarBlock(
    label: String,
    parts: List<Pair<String, Boolean>>,
    fraction: Double,
    color: Color,
    modifier: Modifier = Modifier,
    monoValues: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        CaptionLine(label, parts, monoValues = monoValues)
        Spacer(Modifier.height(6.dp))
        PerfBar(fraction, color)
    }
}

// ─── The panel, card by card ───────────────────────────────────────────────────

@Composable
private fun DisplayCard(p: PerfReadout?, modifier: Modifier = Modifier) {
    PerfCard("Display", modifier) {
        PerfRow("Current FPS", p?.let { fixed(it.fpsCurrent, 1) } ?: "--")
        PerfRow("Average FPS", p?.let { fixed(it.fpsAverage, 1) } ?: "--")
        PerfRow("Target FPS", p?.let { "${it.fpsTarget}" } ?: "--")
        // Slower than the target is the one thing on this page the driver alone cannot see.
        PerfRow(
            "Refresh",
            p?.let { "${it.refreshMs} ms" } ?: "--",
            warn = p != null && p.fpsTarget > 0 && p.fpsCurrent < p.fpsTarget * 0.85,
        )
        PerfRow("Resolution", p?.resolution?.ifEmpty { "--" } ?: "--")
    }
}

@Composable
private fun SystemCard(
    p: PerfReadout?,
    largestBlock: String,
    lastRead: String,
    modifier: Modifier = Modifier,
) {
    PerfCard("System", modifier) {
        PerfRow("Uptime", p?.let { DiagFormat.uptime(it.uptimeS) } ?: "--")
        PerfRow("SPI Bus", p?.let { "${it.spiHz / 1_000_000} MHz" } ?: "--")
        // The page put the low-water mark and the mem-saver flag inside the heap value
        // rather than giving each its own row, and that is how a bench reader scans it.
        PerfRow(
            "Heap",
            p?.let {
                "${DiagFormat.bytes(it.freeHeap)} free / min ${DiagFormat.bytes(it.minFreeHeap)}" +
                    if (it.memSaver) " (mem-saver ON)" else ""
            } ?: "--",
            warn = p != null && p.minFreeHeap < 20_000L,
        )
        PerfRow("Largest free block", largestBlock)
        if ((p?.psramSize ?: 0L) > 0L) {
            PerfRow(
                "PSRAM",
                p?.let { "${DiagFormat.bytes(it.psramSize)} · free ${DiagFormat.bytes(it.psramFree)}" }
                    ?: "--",
            )
        }
        // `#p-live-poll` and the dim detail line under it: what the monitor is doing, and
        // when it last heard from the unit.
        PerfRow("Live Poll", "every $PollEveryLabel")
        Text(
            if (lastRead.isEmpty()) "--" else "last read $lastRead",
            style = HudeTable(),
            color = HudColors.TextDim,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
    }
}

@Composable
private fun CpuCard(p: PerfReadout?, modifier: Modifier = Modifier) {
    PerfCard("CPU Performance", modifier) {
        PerfRow("Frequency", p?.let { "${it.cpuMHz} MHz" } ?: "--")
        PerfRow(
            "Mode",
            when {
                p == null -> "--"
                p.cpuDynamic -> "Dynamic"
                else -> "Fixed ${p.cpuMHz} MHz"
            },
        )
        PerfRow("Temperature", p?.let { "${fixed(it.cpuTempC, 1)} °C" } ?: "--")
        val pct = p?.cpuUsagePct ?: 0.0
        BarBlock(
            label = "CPU Usage",
            parts = if (p == null) {
                listOf("--" to false)
            } else {
                listOf(fixed(pct, 1) to true, "%" to false)
            },
            fraction = pct / 100.0,
            color = when {
                pct < 50.0 -> HudColors.Green
                pct < 80.0 -> HudColors.Amber
                else -> HudColors.Red
            },
            monoValues = true,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MemoryCard(p: PerfReadout?, modifier: Modifier = Modifier) {
    PerfCard("Memory & Storage", modifier) {
        val ramUsed = if (p == null) 0L else DiagFormat.usedOf(p.heapSize, p.freeHeap)
        val ramPct = if (p == null || p.heapSize <= 0L) 0.0 else ramUsed * 100.0 / p.heapSize

        // `.perf-bar-label` before the 20 px bar, with the used / total / percent figures
        // in `strong` and the units around them muted - the page's own caption, not a
        // prose sentence.
        BarBlock(
            label = "RAM Usage",
            parts = if (p == null) {
                listOf("--" to false)
            } else {
                listOf(
                    "${ramUsed / 1024}" to true,
                    " / " to false,
                    "${p.heapSize / 1024}" to true,
                    " KB (" to false,
                    fixed(ramPct, 1) to true,
                    "%)" to false,
                )
            },
            fraction = ramPct / 100.0,
            color = when {
                ramPct < 70.0 -> HudColors.Green
                ramPct < 85.0 -> HudColors.Amber
                else -> HudColors.Red
            },
            modifier = Modifier.padding(bottom = 14.dp),
        )

        val flashTotal = p?.flashTotal ?: 0L
        val flashUsed = p?.flashUsed ?: 0L
        val flashPct = if (flashTotal <= 0L) 0.0 else flashUsed * 100.0 / flashTotal
        CaptionLine(
            label = "Flash Storage",
            parts = if (p == null) {
                listOf("--" to false)
            } else {
                listOf(
                    "${flashUsed / 1024}" to true,
                    " / " to false,
                    "${flashTotal / 1024}" to true,
                    " KB (" to false,
                    fixed(flashPct, 1) to true,
                    "%)" to false,
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        FlashBar(p)
        Spacer(Modifier.height(6.dp))
        FlashLegend(p)

        // Settings-storage budget. NVS runs out of ENTRY SLOTS, not bytes, and it can keep
        // hundreds free while refusing a write (its tidier needs a spare page), so
        // `available` is the number worth watching - and it keeps the page's colours.
        if (p != null && p.nvsEntriesTotal > 0L) {
            val available = p.nvsEntriesAvailable
            val total = p.nvsEntriesTotal
            val nearlyFull = available <= total * 0.03
            Text(
                buildAnnotatedString {
                    append("Settings storage (NVS ${p.nvsBytes / 1024} KB): ")
                    withStyle(SpanStyle(color = HudColors.White, fontWeight = FontWeight.W700)) {
                        append("${p.nvsEntriesUsed}")
                    }
                    append(" of $total entries used, ")
                    withStyle(
                        SpanStyle(
                            color = nvsColor(available, total),
                            fontWeight = FontWeight.W700,
                        ),
                    ) { append("$available") }
                    append(" available")
                    if (nearlyFull) {
                        withStyle(SpanStyle(color = HudColors.Red)) {
                            append(" - nearly full, new settings may not save")
                        }
                    }
                },
                style = HudeCfgNote(),
                color = HudColors.TextMuted,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/**
 * The flash bar as `#fl-partitions-bar`: one flex slice per partition that has bytes in
 * it, in the page's five-colour rotation, and the unclaimed width left as `--bg-tertiary`
 * for free space. A partition with nothing written draws nothing, exactly as the page's
 * `used <= 0` guard does.
 */
@Composable
private fun FlashBar(p: PerfReadout?) {
    val total = p?.flashTotal ?: 0L
    val partitions = p?.partitions.orEmpty()
    val used = partitions.sumOf { it.used }
    val free = (total - used).coerceAtLeast(0L)

    Row(
        Modifier.fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HudColors.Track),
    ) {
        if (total <= 0L) return@Row
        partitions.forEachIndexed { index, part ->
            if (part.used <= 0L) return@forEachIndexed
            Box(
                Modifier
                    .weight(part.used.toFloat())
                    .fillMaxHeight()
                    .background(HudColors.PartitionColors[index % HudColors.PartitionColors.size]),
            )
        }
        if (free > 0L) {
            Box(Modifier.weight(free.toFloat()).fillMaxHeight())
        }
    }
}

/** `#fl-legend`: 8 px swatches, 11 px muted labels, two 2×12 gaps and wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlashLegend(p: PerfReadout?) {
    val total = p?.flashTotal ?: 0L
    if (p == null || total <= 0L) return
    val used = p.partitions.sumOf { it.used }
    val free = (total - used).coerceAtLeast(0L)

    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        p.partitions.forEachIndexed { index, part ->
            if (part.used <= 0L) return@forEachIndexed
            LegendItem(
                color = HudColors.PartitionColors[index % HudColors.PartitionColors.size],
                text = "${part.label} ${part.used / 1024}KB " +
                    "(${DiagFormat.pct(part.used, total)})",
            )
        }
        // The free slice keeps the same swatch but in the track colour with a hairline,
        // because a track-coloured square on a card-coloured tile would otherwise vanish.
        if (free > 0L) {
            LegendItem(
                color = HudColors.Track,
                text = "free ${free / 1024}KB (${DiagFormat.pct(free, total)})",
                dim = true,
                bordered = true,
            )
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    text: String,
    dim: Boolean = false,
    bordered: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
                .then(
                    if (bordered) {
                        Modifier.border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(2.dp))
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text,
            style = HudeCfgNote(),
            color = if (dim) HudColors.TextDim else HudColors.TextMuted,
        )
    }
}

/** The NVS line's colour ladder, straight out of the page's `updatePerf()`. */
private fun nvsColor(available: Long, total: Long): Color = when {
    available > total * 0.15 -> HudColors.Green
    available > total * 0.03 -> HudColors.Amber
    else -> HudColors.Red
}

private fun fixed(value: Double, decimals: Int): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

/** Wall clock on the phone, so a figure can be tied to what the bench log says at that minute. */
private fun clock(): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
