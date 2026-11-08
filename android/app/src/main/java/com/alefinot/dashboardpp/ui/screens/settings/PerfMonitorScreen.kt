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
import androidx.compose.foundation.lazy.LazyColumn
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
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.hudPage
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

@Composable
fun PerfMonitorScreen(
    store: ConfigStore,
    link: BleLink,
    /** Why the unit's clock was not set from the phone; null when it was set [plan §4]. */
    timeNote: String? = null,
    onBack: () -> Unit,
) {
    val linkState by link.state.collectAsStateWithLifecycle()
    val linked = linkState is BleLinkState.Ready

    var cadence by remember { mutableStateOf(Cadence.TWO) }
    var round by remember { mutableStateOf(0) }
    var perf by remember { mutableStateOf<PerfReadout?>(null) }
    var health by remember { mutableStateOf<HealthReadout?>(null) }
    var live by remember { mutableStateOf<LiveReadout?>(null) }
    var boot by remember { mutableStateOf<BootReadout?>(null) }
    var error by remember { mutableStateOf("") }
    var lastRead by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    val trends = remember { PerfTrends() }

    suspend fun read(full: Boolean) {
        reading = true
        try {
            val perfCall = store.action("perf")
            val parsed = if (perfCall.ok) UnitDiag.perf(perfCall.fields) else null
            if (parsed != null) {
                perf = parsed
                trends.add(parsed)
            }
            val liveCall = store.action("live")
            if (liveCall.ok) live = UnitDiag.live(liveCall.fields) ?: live
            if (full) {
                val healthCall = store.action("health")
                if (healthCall.ok) health = UnitDiag.health(healthCall.fields) ?: health
                val bootCall = store.action("boot")
                if (bootCall.ok) boot = UnitDiag.boot(bootCall.fields) ?: boot
            }
            error = when {
                !perfCall.ok && !liveCall.ok ->
                    "the unit did not answer: ${perfCall.error ?: liveCall.error}"
                perfCall.ok && parsed == null -> "perf answered, but sent no fields"
                !perfCall.ok -> "perf refused: ${perfCall.error}"
                !liveCall.ok -> "live refused: ${liveCall.error}"
                else -> ""
            }
            lastRead = clock()
        } finally {
            reading = false
        }
    }

    LaunchedEffect(cadence, round) {
        read(full = true)
        if (cadence == Cadence.OFF) return@LaunchedEffect
        while (isActive) {
            delay(cadence.millis)
            if (!isActive) break
            read(full = false)
        }
    }

    Column(Modifier.hudPage()) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = HudPage.Gutter)
                .padding(top = 10.dp, bottom = 4.dp),
        ) {
            HudPageHeader(
                title = "Performance Monitor",
                trailing = {
                    HudButton(
                        label = "READ NOW",
                        style = HudButtonStyle.PRIMARY,
                        onClick = { round += 1 },
                    )
                },
            ) {
                HudBanner(
                    text = when (val state = linkState) {
                        is BleLinkState.Ready -> "linked to ${state.name} · MTU ${state.mtu}"
                        else -> if (perf == null) {
                            "no link — waiting for the unit"
                        } else {
                            "no link — showing the last figures read"
                        }
                    },
                    color = if (linked) HudColors.Green else HudColors.Amber,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HudButton(
                    label = "BACK",
                    style = HudButtonStyle.SECONDARY,
                    onClick = onBack,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        reading -> "reading…"
                        cadence == Cadence.OFF -> "polling off"
                        else -> "polling every ${cadence.label}"
                    },
                    style = HudeCfgNote(),
                    color = if (reading) HudColors.Cyan else HudColors.TextDim,
                )
                HudButton(
                    label = "CADENCE",
                    style = HudButtonStyle.SECONDARY,
                    onClick = {
                        cadence = cadence.next()
                        round += 1
                    },
                )
            }
            timeNote?.let {
                Text(
                    it,
                    style = HudeCfgNote(),
                    color = HudColors.Amber,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            if (error.isNotEmpty()) {
                Text(
                    error,
                    style = HudeCfgNote(),
                    color = HudColors.Red,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        val p = perf
        val l = live
        val h = health
        val b = boot
        val largestBlock = h?.let { DiagFormat.bytes(it.maxAlloc) }
            ?: b?.let { DiagFormat.bytes(it.maxAlloc) }
            ?: "--"
        // `.perf-grid` is two 1fr tracks above 600 px and one `auto-fit` track below it.
        val twoUp = LocalConfiguration.current.screenWidthDp >= 600

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = HudPage.Gutter),
        ) {
            item { Spacer(Modifier.height(10.dp)) }

            if (twoUp) {
                item {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DisplayCard(p, Modifier.weight(1f).fillMaxHeight())
                        SystemCard(
                            p = p,
                            largestBlock = largestBlock,
                            cadence = cadence,
                            lastRead = lastRead,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            } else {
                item { DisplayCard(p) }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    SystemCard(
                        p = p,
                        largestBlock = largestBlock,
                        cadence = cadence,
                        lastRead = lastRead,
                    )
                }
            }

            item { Spacer(Modifier.height(10.dp)) }
            item { CpuCard(p) }
            item { Spacer(Modifier.height(10.dp)) }
            item { MemoryCard(p) }

            item { Spacer(Modifier.height(10.dp)) }
            item { LinkCard(p) }
            item { Spacer(Modifier.height(10.dp)) }
            item { StacksCard(p) }
            item { Spacer(Modifier.height(10.dp)) }
            item { BootCard(b) }
            item { Spacer(Modifier.height(10.dp)) }
            item { MeasuringCard(l) }
            item { Spacer(Modifier.height(10.dp)) }
            item { SpreadCard(trends) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** How often the monitor asks the unit for another set of figures. */
private enum class Cadence(val label: String, val millis: Long) {
    OFF("off", 0L),
    ONE("1s", 1_000L),
    TWO("2s", 2_000L),
    FIVE("5s", 5_000L),
    TEN("10s", 10_000L),
    ;

    fun next(): Cadence {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }
}

// ─── The page's `.perf-card` recipe ────────────────────────────────────────────

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
        Spacer(Modifier.height(8.dp))
        content()
    }
}

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
    cadence: Cadence,
    lastRead: String,
    modifier: Modifier = Modifier,
) {
    PerfCard("System", modifier) {
        PerfRow("Uptime", p?.let { DiagFormat.uptime(it.uptimeS) } ?: "--")
        PerfRow("SPI Bus", p?.let { "${it.spiHz / 1_000_000} MHz" } ?: "--")
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
        PerfRow(
            "Live Poll",
            if (cadence == Cadence.OFF) "off" else "every ${cadence.label}",
        )
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

@Composable
private fun LinkCard(p: PerfReadout?, modifier: Modifier = Modifier) {
    PerfCard("Bluetooth Link", modifier) {
        PerfRow("Radio", p?.let { DiagFormat.bleState(it.bleState) } ?: "--")
        PerfRow("Link up on the unit side", if (p?.bleConnected == true) "yes" else "no")
        PerfRow("MTU (unit side)", p?.let { "${it.bleMtu} bytes" } ?: "--")
        PerfRow("Passkey entry open", if (p?.blePairing == true) "yes" else "no")
        PerfRow("Unit MAC", p?.mac?.ifEmpty { "--" } ?: "--")
    }
}

@Composable
private fun StacksCard(p: PerfReadout?, modifier: Modifier = Modifier) {
    PerfCard("Task Stacks", modifier) {
        if (p == null || p.stacks.isEmpty()) {
            PerfRow("Stack left", "--")
        } else {
            for (stack in p.stacks) {
                PerfRow(
                    "Stack left · ${stack.label}",
                    "${stack.wordsFree} words",
                    warn = stack.wordsFree < 512,
                )
            }
        }
    }
}

@Composable
private fun BootCard(b: BootReadout?, modifier: Modifier = Modifier) {
    PerfCard("Why This Unit Is Running", modifier) {
        PerfRow(
            "Reset reason",
            b?.resetReason?.ifEmpty { "unknown" } ?: "--",
            warn = b != null && DiagFormat.resetLooksUnasked(b.resetReason),
        )
        PerfRow("Boot count", b?.let { "${it.bootCount}" } ?: "--")
        PerfRow(
            "Boot storm",
            if (b?.storm == true) "yes — it rebooted too many times in a row" else "no",
            warn = b?.storm == true,
        )
        PerfRow("Last reboot tag", b?.lastRebootTag?.ifEmpty { "none recorded" } ?: "--")
        PerfRow("Heap at last reboot", b?.let { DiagFormat.bytes(it.heapAtLastReboot) } ?: "--")
        PerfRow("Lowest free heap · last boot", b?.let { DiagFormat.bytes(it.minHeapLastBoot) } ?: "--")
        PerfRow("Lowest free heap · this boot", b?.let { DiagFormat.bytes(it.minHeapSinceBoot) } ?: "--")
    }
}

@Composable
private fun MeasuringCard(l: LiveReadout?, modifier: Modifier = Modifier) {
    PerfCard("What The Unit Is Measuring", modifier) {
        PerfRow("Battery", l?.let { fixed(it.volts, 2) + " V" } ?: "--")
        PerfRow("Engine temperature", l?.let { fixed(it.engineTempC, 1) + " °C" } ?: "--")
        PerfRow("Ambient light", l?.let { "${it.ambient}" } ?: "--")
        PerfRow("Odometer", l?.let { fixed(it.odometerKm, 2) + " km" } ?: "--")
        PerfRow("Fuel sender", l?.let { "${it.fuelRaw} ADC · ${fixed(it.fuelOhm, 1)} ohm" } ?: "--")
        PerfRow("Fuel input", l?.let { DiagFormat.fuelState(it.fuelState) } ?: "--")
    }
}

@Composable
private fun SpreadCard(trends: PerfTrends, modifier: Modifier = Modifier) {
    PerfCard("Spread While This Screen Is Open", modifier) {
        for ((name, decimals) in listOf(
            "fps" to 1,
            "heap" to 0,
            "minheap" to 0,
            "cpu" to 1,
            "cputemp" to 1,
        )) {
            PerfRow(trendLabel(name), DiagFormat.trend(trends.series(name), decimals))
        }
        Text(
            "This window holds the last ${trends.cap} readings taken while the page was open, " +
                "so it cannot see a fault that stopped before the page was opened. The unit's " +
                "own low-water marks — lowest free heap, the stack marks — do reach back over " +
                "the whole boot.",
            style = HudeCfgHelp(),
            color = HudColors.TextDim,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

/** The NVS line's colour ladder, straight out of the page's `updatePerf()`. */
private fun nvsColor(available: Long, total: Long): Color = when {
    available > total * 0.15 -> HudColors.Green
    available > total * 0.03 -> HudColors.Amber
    else -> HudColors.Red
}

private fun trendLabel(name: String): String = when (name) {
    "fps" -> "FPS"
    "heap" -> "free heap"
    "minheap" -> "lowest free heap"
    "cpu" -> "CPU usage %"
    "cputemp" -> "CPU °C"
    else -> name
}

private fun fixed(value: Double, decimals: Int): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

/** Wall clock on the phone, so a figure can be tied to what the bench log says at that minute. */
private fun clock(): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
