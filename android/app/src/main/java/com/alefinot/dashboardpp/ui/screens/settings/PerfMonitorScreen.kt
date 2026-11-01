package com.alefinot.dashboardpp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.screens.CardHead
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The unit's Performance Monitor, as a page this app draws over the Bluetooth link.
 *
 * Every number here is the unit talking about itself: `perf`, `health`, `live` and `boot` from
 * `src/bt_rpc.cpp`. Nothing on this page is something the app could work out for itself — that is
 * the point. The questions it exists to answer are bench questions: is the display really running
 * at `TARGET_FPS`, is the heap flat over ten minutes or climbing, how much stack does each task
 * have left, did this unit reboot without anybody asking, and how much room is left in the settings
 * budget.
 *
 * The cards, and what each is for:
 *
 *  - **The link.** What the unit believes its own radio is doing — the only place where a stale
 *    "advertising" claim can be seen side by side with what this phone actually has.
 *  - **Display.** FPS against the target, refresh interval, geometry, the SPI clock.
 *  - **CPU.** Clock, whether it may drop when idle, temperature, load.
 *  - **Memory.** The bar the unit's old page drew (in use / total), free heap, the low-water mark
 *    since boot, the largest single free block, and each task's remaining stack.
 *  - **Storage.** The partition map, and the settings budget in ENTRIES — the thing that runs out
 *    long before the bytes do.
 *  - **Why this unit is running.** Reset reason, boot counter, boot-storm flag: the difference
 *    between "up for two days" and "quietly rebooting every ten minutes".
 *  - **What it is measuring.** The inputs the HUD is drawing right now.
 *  - **The spread while this screen is open.** Turns the single readings above into a range, which
 *    is the only way a slow leak or an FPS dip during a config read becomes visible.
 *
 * Reading costs link round trips and `perf` is the widest reply on the link, so the cadence is a
 * choice rather than a constant. It defaults to 2 s, and leaving the screen stops it.
 */
@Composable
fun PerfMonitorScreen(
    store: ConfigStore,
    link: BleLink,
    onBack: () -> Unit,
) {
    val linkState by link.state.collectAsStateWithLifecycle()
    val linked = linkState is BleLinkState.Ready

    var cadence by remember { mutableStateOf(Cadence.TWO) }
    // Bumped by READ NOW and by changing the cadence: both restart the effect below, which reads
    // once in full and then keeps to the cadence.
    var round by remember { mutableStateOf(0) }
    var perf by remember { mutableStateOf<PerfReadout?>(null) }
    var health by remember { mutableStateOf<HealthReadout?>(null) }
    var live by remember { mutableStateOf<LiveReadout?>(null) }
    var boot by remember { mutableStateOf<BootReadout?>(null) }
    var error by remember { mutableStateOf("") }
    var lastRead by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    val trends = remember { PerfTrends() }

    /**
     * One round of reads. A tick takes `perf` and `live`; a full round also takes `health` (the
     * largest free block, which `perf` does not report) and `boot`, which changes only when the
     * unit restarts — so polling it every two seconds buys nothing.
     */
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
                // An ok reply with nothing in it is its own failure: the unit said yes and sent no
                // numbers, and drawing that as a page full of dashes would blame the unit later.
                perfCall.ok && parsed == null -> "perf answered, but sent no fields"
                !perfCall.ok -> "perf refused: ${perfCall.error}"
                !liveCall.ok -> "live refused: ${liveCall.error}"
                else -> ""
            }
            lastRead = clock()
        } finally {
            // A cancelled round leaves the page anyway; a round that threw must not leave the
            // header stuck saying "reading…" over numbers that stopped moving.
            reading = false
        }
    }

    // One effect drives everything: keyed on the cadence and on the round, so changing cadence or
    // pressing READ NOW restarts it, and so that leaving the screen — or switching the cadence to
    // off — cancels the loop instead of leaving a poller running on a link the settings page needs.
    LaunchedEffect(cadence, round) {
        read(full = true)
        if (cadence == Cadence.OFF) return@LaunchedEffect
        while (isActive) {
            delay(cadence.millis)
            if (!isActive) break
            read(full = false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            HudButton(label = "BACK", style = HudButtonStyle.SECONDARY, onClick = onBack)
            Text("PERFORMANCE MONITOR", style = HudeSectionTitle(), color = HudColors.Cyan)
            Spacer(Modifier.weight(1f))
            Text(
                if (reading) "reading…" else "every ${cadence.label}",
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
            HudButton(
                label = "READ NOW",
                style = HudButtonStyle.PRIMARY,
                onClick = { round += 1 },
            )
        }

        // What the numbers are resting on. A dropped link freezes them where they were, and a page
        // that does not say so is how a stale figure ends up believed.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text(
                when (val state = linkState) {
                    is BleLinkState.Ready -> "linked to ${state.name} · MTU ${state.mtu}"
                    else -> "NO LINK — these are the last values read from the unit"
                },
                style = HudeCfgHelp(),
                color = if (linked) HudColors.TextDim else HudColors.Amber,
                modifier = Modifier.weight(1f),
            )
            if (lastRead.isNotEmpty()) {
                Text("last read $lastRead", style = HudeCfgNote(), color = HudColors.TextDim)
            }
        }
        if (error.isNotEmpty()) {
            Text(
                error,
                style = HudeCfgNote(),
                color = HudColors.Red,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        val p = perf
        val l = live
        val h = health
        val b = boot

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item { CardHead("The link the unit thinks it has") }
            row("Radio", p?.let { DiagFormat.bleState(it.bleState) } ?: "--")
            row("Link up on the unit side", if (p?.bleConnected == true) "yes" else "no")
            row("MTU (unit side)", p?.let { "${it.bleMtu} bytes" } ?: "--")
            row("Passkey entry open", if (p?.blePairing == true) "yes" else "no")
            row("Unit MAC", p?.mac?.ifEmpty { "--" } ?: "--")
            row("Uptime", p?.let { DiagFormat.uptime(it.uptimeS) } ?: "--")

            item { CardHead("Display") }
            row("Current FPS", p?.let { fixed(it.fpsCurrent, 1) } ?: "--")
            row("Average FPS", p?.let { fixed(it.fpsAverage, 1) } ?: "--")
            row("Target FPS", p?.let { "${it.fpsTarget}" } ?: "--")
            // Slower than the target is the one thing on this page the driver alone cannot see.
            row(
                "Refresh interval",
                p?.let { "${it.refreshMs} ms" } ?: "--",
                warn = p != null && p.fpsTarget > 0 && p.fpsCurrent < p.fpsTarget * 0.85,
            )
            row("Resolution", p?.resolution?.ifEmpty { "--" } ?: "--")
            row("SPI bus", p?.let { "${it.spiHz / 1_000_000} MHz" } ?: "--")
            row("Ambient light ADC", p?.let { "${it.ambientLight}" } ?: "--")

            item { CardHead("CPU") }
            row("Frequency", p?.let { "${it.cpuMHz} MHz" } ?: "--")
            row(
                "Mode",
                when {
                    p == null -> "--"
                    p.cpuDynamic -> "may drop when idle"
                    else -> "fixed"
                },
            )
            row("Temperature", p?.let { "${fixed(it.cpuTempC, 1)} °C" } ?: "--")
            bar(
                label = "CPU usage",
                detail = p?.let { "${fixed(it.cpuUsagePct, 1)} %" } ?: "--",
                fraction = (p?.cpuUsagePct ?: 0.0) / 100.0,
                color = HudColors.Cyan,
            )

            item { CardHead("Memory") }
            val ramUsed = if (p == null) 0L else DiagFormat.usedOf(p.heapSize, p.freeHeap)
            bar(
                label = "RAM in use",
                detail = if (p == null) {
                    "--"
                } else {
                    "${DiagFormat.bytes(ramUsed)} / ${DiagFormat.bytes(p.heapSize)} " +
                        "(${DiagFormat.pct(ramUsed, p.heapSize)})"
                },
                fraction = if (p == null || p.heapSize <= 0L) 0.0 else ramUsed.toDouble() / p.heapSize,
                color = HudColors.Green,
            )
            row("Free heap", p?.let { DiagFormat.bytes(it.freeHeap) } ?: "--")
            row(
                "Lowest free heap since boot",
                p?.let { DiagFormat.bytes(it.minFreeHeap) } ?: "--",
                warn = p != null && p.minFreeHeap < 20_000L,
            )
            row(
                "Largest single free block",
                h?.let { DiagFormat.bytes(it.maxAlloc) }
                    ?: b?.let { DiagFormat.bytes(it.maxAlloc) }
                    ?: "--",
            )
            row("Mem saver", if (p?.memSaver == true || h?.memSaver == true) "on" else "off")
            row(
                "PSRAM",
                p?.let { "${DiagFormat.bytes(it.psramSize)} · free ${DiagFormat.bytes(it.psramFree)}" }
                    ?: "--",
            )
            // The stack marks by the names the firmware prints. These are words of stack still
            // free, read from the unit's own high-water marks — the number that has to stay away
            // from zero, and the one that says a task is about to overflow before it does.
            p?.stacks?.forEach { stack ->
                row("Stack left · ${stack.label}", "${stack.wordsFree} words", warn = stack.wordsFree < 512)
            }

            item { CardHead("Storage and the settings budget") }
            val flashUsed = p?.flashUsed ?: 0L
            val flashTotal = p?.flashTotal ?: 0L
            bar(
                label = "Flash taken by the partitions",
                detail = if (p == null) {
                    "--"
                } else {
                    "${DiagFormat.bytes(flashUsed)} / ${DiagFormat.bytes(flashTotal)} " +
                        "(${DiagFormat.pct(flashUsed, flashTotal)})"
                },
                fraction = if (flashTotal <= 0L) 0.0 else flashUsed.toDouble() / flashTotal,
                color = HudColors.Blue,
            )
            p?.partitions?.forEach { part ->
                row(
                    "· ${part.label} @ 0x${part.addr.toString(16)}",
                    "${DiagFormat.bytes(part.used)} of ${DiagFormat.bytes(part.size)}",
                )
            }
            bar(
                label = "Settings entries",
                detail = p?.let {
                    "${it.nvsEntriesUsed} / ${it.nvsEntriesTotal} " +
                        "(${DiagFormat.pct(it.nvsEntriesUsed, it.nvsEntriesTotal)})"
                } ?: "--",
                fraction = if (p == null || p.nvsEntriesTotal <= 0L) {
                    0.0
                } else {
                    p.nvsEntriesUsed.toDouble() / p.nvsEntriesTotal
                },
                color = HudColors.Amber,
            )
            row(
                "Entries still free",
                p?.let { "${it.nvsEntriesAvailable}" } ?: "--",
                warn = p != null && p.nvsEntriesAvailable < 10L,
            )
            row("NVS bytes", p?.let { DiagFormat.bytes(it.nvsBytes) } ?: "--")

            item { CardHead("Why this unit is running") }
            row(
                "Reset reason",
                b?.resetReason?.ifEmpty { "unknown" } ?: "--",
                warn = b != null && DiagFormat.resetLooksUnasked(b.resetReason),
            )
            row("Boot count", b?.let { "${it.bootCount}" } ?: "--")
            row(
                "Boot storm",
                if (b?.storm == true) "yes — it rebooted too many times in a row" else "no",
                warn = b?.storm == true,
            )
            row("Last reboot tag", b?.lastRebootTag?.ifEmpty { "none recorded" } ?: "--")
            row("Heap at last reboot", b?.let { DiagFormat.bytes(it.heapAtLastReboot) } ?: "--")
            row("Lowest free heap · last boot", b?.let { DiagFormat.bytes(it.minHeapLastBoot) } ?: "--")
            row("Lowest free heap · this boot", b?.let { DiagFormat.bytes(it.minHeapSinceBoot) } ?: "--")

            item { CardHead("What the unit is measuring right now") }
            row("Battery", l?.let { fixed(it.volts, 2) + " V" } ?: "--")
            row("Engine temperature", l?.let { fixed(it.engineTempC, 1) + " °C" } ?: "--")
            row("Ambient light", l?.let { "${it.ambient}" } ?: "--")
            row("Odometer", l?.let { fixed(it.odometerKm, 2) + " km" } ?: "--")
            row("Fuel sender", l?.let { "${it.fuelRaw} ADC · ${fixed(it.fuelOhm, 1)} ohm" } ?: "--")
            row("Fuel input", l?.let { DiagFormat.fuelState(it.fuelState) } ?: "--")

            item { CardHead("Spread while this screen is open") }
            for ((name, decimals) in listOf(
                "fps" to 1,
                "heap" to 0,
                "minheap" to 0,
                "cpu" to 1,
                "cputemp" to 1,
            )) {
                row(trendLabel(name), DiagFormat.trend(trends.series(name), decimals))
            }
            item {
                Text(
                    "This window holds the last ${trends.cap} readings taken while the page was open, " +
                        "so it cannot see a fault that stopped before the page was opened. The unit's " +
                        "own low-water marks — lowest free heap, the stack marks — do reach back over " +
                        "the whole boot.",
                    style = HudeCfgHelp(),
                    color = HudColors.TextDim,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
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

/** One label/value line, in the unit's own page style: dim label, monospace value. */
private fun LazyListScope.row(label: String, value: String, warn: Boolean = false) {
    item {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        ) {
            Text(
                label,
                style = HudePerfLabel(),
                color = HudColors.TextMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = HudePerfValue(),
                color = if (warn) HudColors.Amber else HudColors.TextPrimary,
            )
        }
    }
}

/** A figure the unit gives as a pair, drawn as a bar the way its old page drew them. */
private fun LazyListScope.bar(
    label: String,
    detail: String,
    fraction: Double,
    color: Color,
) {
    item {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    label,
                    style = HudePerfLabel(),
                    color = HudColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(detail, style = HudePerfValue(), color = HudColors.TextPrimary)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(HudColors.Hairline),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(color),
                )
            }
        }
    }
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
