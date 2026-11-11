package com.alefinot.dashboardpp.ui.screens.settings

/**
 * What the unit says about itself while it runs.
 *
 * These are transcriptions of the four diagnostic methods in `src/bt_rpc.cpp` — `methodPerf`,
 * `methodHealth`, `methodLive` and `methodBoot` — under the firmware's own key names, so a field
 * that goes missing on the unit shows up as a missing field here instead of as a plausible zero.
 * The performance monitor draws `perf` and `health`; `live` and `boot` are the app's side of the
 * two methods it no longer asks for, kept here so the firmware contract stays transcribed and
 * tested. Nothing else in the app needs any of them, and nothing here parses a value the firmware
 * does not actually send.
 *
 * One thing worth knowing before reading the parsers: this firmware prints its booleans with `%d`.
 * `ble_connected`, `mem_saver`, `cpu_dynamic`, `storm` all arrive as the numbers 0 or 1, never as
 * JSON `true`/`false`, so every flag here goes through [flag] and not through a cast.
 */

/** A task's stack high-water mark, in words, as `uxTaskGetStackHighWaterMark` reports it. */
data class StackRow(
    val label: String,
    val wordsFree: Int,
)

/** One entry of the unit's partition table, as the `perf` reply lists it. */
data class PartitionRow(
    val label: String,
    val size: Long,
    val used: Long,
    val addr: Long,
)

/**
 * `perf` — the widest of the four. Display, CPU, memory, flash, the settings budget and the
 * Bluetooth link in one reply, which is why the monitor polls this one on its own cadence.
 */
data class PerfReadout(
    // Display
    val fpsCurrent: Double = 0.0,
    val fpsAverage: Double = 0.0,
    val fpsTarget: Int = 0,
    val refreshMs: Long = 0,
    val spiHz: Long = 0,
    val ambientLight: Int = 0,
    val resolution: String = "",
    // CPU
    val cpuMHz: Int = 0,
    val cpuTempC: Double = 0.0,
    val cpuDynamic: Boolean = false,
    val cpuUsagePct: Double = 0.0,
    val uptimeS: Long = 0,
    val mac: String = "",
    // Memory
    val freeHeap: Long = 0,
    val minFreeHeap: Long = 0,
    val heapSize: Long = 0,
    val psramSize: Long = 0,
    val psramFree: Long = 0,
    val memSaver: Boolean = false,
    val stacks: List<StackRow> = emptyList(),
    // Flash and settings storage
    val flashTotal: Long = 0,
    val flashFree: Long = 0,
    val flashUsed: Long = 0,
    val partitions: List<PartitionRow> = emptyList(),
    val nvsBytes: Long = 0,
    val nvsEntriesUsed: Long = 0,
    val nvsEntriesAvailable: Long = 0,
    val nvsEntriesTotal: Long = 0,
    // The link the unit actually has — it replaced the Wi-Fi block the old monitor carried
    val bleConnected: Boolean = false,
    val bleMtu: Int = 0,
    val blePairing: Boolean = false,
    val bleState: Int = 0,
)

/** `health` — the heap low-water marks, which `perf` only partly covers. */
data class HealthReadout(
    val heap: Long = 0,
    val maxAlloc: Long = 0,
    val minHeap: Long = 0,
    val memSaver: Boolean = false,
    val nvsAvailable: Long = 0,
    val nvsUsed: Long = 0,
    val nvsTotal: Long = 0,
    val uptimeS: Long = 0,
)

/** `live` — the inputs the display is drawing right now, in one cheap reply. */
data class LiveReadout(
    val ambient: Int = 0,
    val odometerKm: Double = 0.0,
    val fuelRaw: Int = 0,
    val fuelOhm: Double = 0.0,
    val fuelState: Int = 0,
    val volts: Double = 0.0,
    val engineTempC: Double = 0.0,
)

/**
 * `boot` — why the unit is running, and what it cost to get here. This is the block that answers
 * "did it crash?": a unit that has been restarting every ten minutes looks perfectly healthy in
 * every other card.
 */
data class BootReadout(
    val resetReason: String = "",
    val bootCount: Long = 0,
    val storm: Boolean = false,
    val lastRebootTag: String = "",
    val heapAtLastReboot: Long = 0,
    val minHeapLastBoot: Long = 0,
    val minHeapSinceBoot: Long = 0,
    val maxAlloc: Long = 0,
    val freeHeap: Long = 0,
)

object UnitDiag {

    /**
     * A reply with no fields at all is a failed call, not a unit reporting zeros — the screen has
     * to be able to tell those two apart, or a dead link looks like an idling engine.
     */
    fun perf(fields: Map<String, Any?>): PerfReadout? =
        if (fields.isEmpty()) null else PerfReadout(
            fpsCurrent = num(fields, "fps_current") ?: 0.0,
            fpsAverage = num(fields, "fps_average") ?: 0.0,
            fpsTarget = int(fields, "fps_target") ?: 0,
            refreshMs = num(fields, "refresh_ms")?.toLong() ?: 0L,
            spiHz = num(fields, "spi_speed")?.toLong() ?: 0L,
            ambientLight = int(fields, "ambient_light") ?: 0,
            resolution = text(fields, "resolution"),
            cpuMHz = int(fields, "cpu_freq") ?: 0,
            cpuTempC = num(fields, "cpu_temp") ?: 0.0,
            cpuDynamic = flag(fields, "cpu_dynamic"),
            cpuUsagePct = num(fields, "cpu_usage") ?: 0.0,
            uptimeS = num(fields, "uptime_s")?.toLong() ?: 0L,
            mac = text(fields, "mac"),
            freeHeap = num(fields, "free_heap")?.toLong() ?: 0L,
            minFreeHeap = num(fields, "min_free_heap")?.toLong() ?: 0L,
            heapSize = num(fields, "heap_size")?.toLong() ?: 0L,
            psramSize = num(fields, "psram_size")?.toLong() ?: 0L,
            psramFree = num(fields, "psram_free")?.toLong() ?: 0L,
            memSaver = flag(fields, "mem_saver"),
            stacks = stacks(fields["stack_free"]),
            flashTotal = num(fields, "flash_total")?.toLong() ?: 0L,
            flashFree = num(fields, "flash_free")?.toLong() ?: 0L,
            flashUsed = num(fields, "flash_used")?.toLong() ?: 0L,
            partitions = partitions(fields["partitions"]),
            nvsBytes = num(fields, "nvs_bytes")?.toLong() ?: 0L,
            nvsEntriesUsed = num(fields, "nvs_entries_used")?.toLong() ?: 0L,
            nvsEntriesAvailable = num(fields, "nvs_entries_available")?.toLong() ?: 0L,
            nvsEntriesTotal = num(fields, "nvs_entries_total")?.toLong() ?: 0L,
            bleConnected = flag(fields, "ble_connected"),
            bleMtu = int(fields, "ble_mtu") ?: 0,
            blePairing = flag(fields, "ble_pairing"),
            bleState = int(fields, "ble_state") ?: 0,
        )

    fun health(fields: Map<String, Any?>): HealthReadout? =
        if (fields.isEmpty()) null else HealthReadout(
            heap = num(fields, "heap")?.toLong() ?: 0L,
            maxAlloc = num(fields, "maxalloc")?.toLong() ?: 0L,
            minHeap = num(fields, "minheap")?.toLong() ?: 0L,
            memSaver = flag(fields, "mem_saver"),
            nvsAvailable = num(fields, "nvs_available")?.toLong() ?: 0L,
            nvsUsed = num(fields, "nvs_used")?.toLong() ?: 0L,
            nvsTotal = num(fields, "nvs_total")?.toLong() ?: 0L,
            uptimeS = num(fields, "uptime")?.toLong() ?: 0L,
        )

    fun live(fields: Map<String, Any?>): LiveReadout? =
        if (fields.isEmpty()) null else LiveReadout(
            ambient = int(fields, "ambient") ?: 0,
            odometerKm = num(fields, "odo") ?: 0.0,
            fuelRaw = int(fields, "fuel_raw") ?: 0,
            fuelOhm = num(fields, "fuel_ohm") ?: 0.0,
            fuelState = int(fields, "fuel_st") ?: 0,
            volts = num(fields, "volts") ?: 0.0,
            engineTempC = num(fields, "temp") ?: 0.0,
        )

    fun boot(fields: Map<String, Any?>): BootReadout? =
        if (fields.isEmpty()) null else BootReadout(
            resetReason = text(fields, "reset"),
            bootCount = num(fields, "bootCount")?.toLong() ?: 0L,
            storm = flag(fields, "storm"),
            lastRebootTag = text(fields, "lastRebootTag"),
            heapAtLastReboot = num(fields, "heapAtLastReboot")?.toLong() ?: 0L,
            minHeapLastBoot = num(fields, "minHeapLastBoot")?.toLong() ?: 0L,
            minHeapSinceBoot = num(fields, "minHeapSinceBoot")?.toLong() ?: 0L,
            maxAlloc = num(fields, "maxAllocHeap")?.toLong() ?: 0L,
            freeHeap = num(fields, "freeHeap")?.toLong() ?: 0L,
        )

    // The names are the firmware's, and the task list is whatever the unit chose to report:
    // hard-coding three names here would drop a task the day the firmware grows one.
    private fun stacks(node: Any?): List<StackRow> {
        val map = node as? Map<*, *> ?: return emptyList()
        return map.entries.map { entry ->
            StackRow(entry.key.toString(), (entry.value as? Number)?.toInt() ?: 0)
        }
    }

    private fun partitions(node: Any?): List<PartitionRow> {
        val list = node as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<*, *>>().map { row ->
            PartitionRow(
                label = row["label"] as? String ?: "?",
                size = (row["size"] as? Number)?.toLong() ?: 0L,
                used = (row["used"] as? Number)?.toLong() ?: 0L,
                addr = (row["addr"] as? Number)?.toLong() ?: 0L,
            )
        }
    }

    private fun num(fields: Map<String, Any?>, key: String): Double? =
        (fields[key] as? Number)?.toDouble()

    private fun int(fields: Map<String, Any?>, key: String): Int? = num(fields, key)?.toInt()

    /** 0/1 as printed by `%d`, which is every boolean this firmware sends. */
    private fun flag(fields: Map<String, Any?>, key: String): Boolean =
        when (val value = fields[key]) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            else -> false
        }

    private fun text(fields: Map<String, Any?>, key: String): String = fields[key] as? String ?: ""
}

/** Numbers said the way the unit's own page said them, so a bench reading is comparable. */
object DiagFormat {

    /** Bytes as the old monitor printed them: an integer below 1 KB, then KB, then MB. */
    fun bytes(value: Long): String = when {
        value < 1_024L -> "$value B"
        value < 1_048_576L -> one(value / 1_024.0) + " KB"
        else -> two(value / 1_048_576.0) + " MB"
    }

    /** `1h 22m 50s`, the format the old System card used. */
    fun uptime(seconds: Long): String {
        if (seconds <= 0L) return "0s"
        val h = seconds / 3_600L
        val m = (seconds % 3_600L) / 60L
        val s = seconds % 60L
        return if (h > 0L) "${h}h ${m}m ${s}s" else if (m > 0L) "${m}m ${s}s" else "${s}s"
    }

    /** A share, or `--` when the whole is unknown — never a division by nothing. */
    fun pct(part: Long, total: Long): String =
        if (total <= 0L) "--" else one(part * 100.0 / total) + "%"

    /** The unit reports what is free; the bars the old page drew were the other half. */
    fun usedOf(total: Long, free: Long): Long = if (total <= free) 0L else total - free

    /**
     * `btLinkState()` as the firmware's heartbeats print it: 2 is a live link, 1 is advertising,
     * 0 is the radio off. Anything else is printed as the number it is — inventing a name for a
     * code this app does not know is how a monitor starts lying.
     */
    fun bleState(code: Int): String = when (code) {
        2 -> "LINKED"
        1 -> "ADVERTISING"
        0 -> "OFF"
        else -> "state $code"
    }

    /**
     * The fuel input state, by the names the firmware gives it (`FUEL_INPUT_*` in
     * `src/dashboard.h`). The raw ADC number on its own means nothing at the bench: 4095 is either
     * a full tank or a wire that fell off, and only this code says which.
     */
    fun fuelState(code: Int): String = when (code) {
        0 -> "input off — nothing wired"
        1 -> "in range"
        2 -> "OPEN CIRCUIT — sender unplugged or wire broken"
        3 -> "SHORTED — pin at ground"
        else -> "state $code"
    }

    /**
     * Whether the unit came back for a reason nobody asked it to. The strings are the firmware's
     * own (`resetReasonStr` in `src/bootinfo.cpp`), which prints every reason in the core's enum
     * and falls back to `RST_<n>` for one it does not know — so an unknown label is treated as
     * unasked for rather than assumed innocent, and a clean `SW (clean restart)` is not.
     */
    fun resetLooksUnasked(reason: String): Boolean {
        val upper = reason.uppercase()
        if (upper.isEmpty() || upper == "UNKNOWN") return true
        if (upper.startsWith("RST_")) return true
        return listOf("PANIC", "WDT", "BROWNOUT", "GLITCH", "LOCKUP", "EFUSE")
            .any { upper.contains(it) }
    }

    /** A trend as one line: what it is now, and the spread since the screen was opened. */
    fun trend(series: Series?, decimals: Int): String = if (series == null || series.count == 0) {
        "no samples yet"
    } else {
        val d = decimals.coerceAtLeast(0)
        "now ${fixed(series.last, d)} · min ${fixed(series.min, d)} · max ${fixed(series.max, d)}" +
            " · avg ${fixed(series.average, d)} over ${series.count}"
    }

    private fun one(value: Double) = fixed(value, 1)

    private fun two(value: Double) = fixed(value, 2)

    private fun fixed(value: Double, decimals: Int): String =
        String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
}

/** One bounded series: the window the monitor keeps for a single figure. */
class Series(
    val count: Int,
    val last: Double,
    val min: Double,
    val max: Double,
    val average: Double,
)

/**
 * The spread of a handful of figures over a run of `perf` readings.
 *
 * A single reading tells you what the unit is doing this second; a leak, a stack that is running
 * out, and an FPS figure that only dips while the config stream runs are all things you can only
 * see as a range. The window is capped because a screen may stay open for an hour on a tablet
 * and this app has no business growing an unbounded list.
 *
 * Nothing draws this today — the performance monitor's spread card was taken out on 2026-11-11 —
 * so it stays as the tested machinery a re-drawn one would sit on.
 */
class PerfTrends(val cap: Int = 60) {

    private val extractors: Map<String, (PerfReadout) -> Double> = linkedMapOf(
        "fps" to { it.fpsCurrent },
        "heap" to { it.freeHeap.toDouble() },
        "minheap" to { it.minFreeHeap.toDouble() },
        "cpu" to { it.cpuUsagePct },
        "cputemp" to { it.cpuTempC },
    )

    private val windows: Map<String, ArrayDeque<Double>> =
        extractors.keys.associateWith { ArrayDeque<Double>(cap.coerceAtLeast(1)) }

    fun add(readout: PerfReadout) {
        for ((name, extract) in extractors) {
            val window = windows[name] ?: continue
            window.addLast(extract(readout))
            while (window.size > cap) window.removeFirst()
        }
    }

    /** Null for a name this screen does not track, and for a window with nothing in it yet. */
    fun series(name: String): Series? {
        val window = windows[name] ?: return null
        if (window.isEmpty()) return null
        var sum = 0.0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (value in window) {
            sum += value
            if (value < lo) lo = value
            if (value > hi) hi = value
        }
        return Series(
            count = window.size,
            last = window.last(),
            min = lo,
            max = hi,
            average = sum / window.size,
        )
    }

    fun names(): List<String> = extractors.keys.toList()
}
