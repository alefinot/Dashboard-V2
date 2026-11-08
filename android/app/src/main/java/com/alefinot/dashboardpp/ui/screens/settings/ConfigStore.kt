package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a row stands between the unit and the screen. */
enum class RowStatus {
    /** Matches what the unit confirmed. */
    SYNCED,

    /** Edited locally; its write is waiting out the debounce. */
    PENDING,

    /** Sent, and the unit wrote it to flash. */
    SAVED,

    /** Applied in RAM but not flash — the unit answered with `nvsErrors`. */
    RAM_ONLY,

    /** The unit refused the write or the link failed; value reverted to the last confirmed one. */
    FAILED,

    /** The schema knows this key but `config.get` never reported it. */
    MISSING,
}

/** One row as drawn: the stored values in device units, plus what happened to them. */
data class RowView(
    val value: Any?,
    /** The second half of an `xy` pair, when the row is one. */
    val yValue: Any? = null,
    val status: RowStatus = RowStatus.SYNCED,
    val message: String? = null,
)

/** The state of the read everything else hangs off. */
sealed class SyncPhase {
    /** Nothing read yet — the rows do not exist until the read joins the schema. */
    object Idle : SyncPhase()

    /** Reading; [chunks] counts the pieces that have landed so far. */
    data class Running(val chunks: Int) : SyncPhase()

    /** Ready at [atMs] on [ConfigStore]'s clock. */
    data class Ready(val keys: Int, val chunks: Int, val atMs: Long, val rows: Int) : SyncPhase()

    data class Failed(val reason: String) : SyncPhase()
}

/** What one backup document did when it came back. */
data class RestoreReport(
    val applied: Int,
    /** Keys the unit refused to take. */
    val refused: Int,
    val leftOut: Int,
    val nvsErrors: Int,
    val batches: Int,
    val problems: List<String> = emptyList(),
) {
    val summary: String
        get() = "restored $applied keys in $batches batches" +
            (if (refused > 0) " · $refused refused by the unit" else "") +
            (if (nvsErrors > 0) " · $nvsErrors not in flash" else "") +
            problems.filter { it.isNotBlank() }.joinToString("") { " · $it" }
}

class ConfigStore(
    private val gateway: ConfigGateway,
    private val schema: ConfigSchema,
    private val scope: CoroutineScope,
    /** How long a number field gets to finish typing before its row is written. */
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    /** A read younger than this need not be re-run when the tab is re-entered. */
    private val syncTtlMs: Long = DEFAULT_SYNC_TTL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 600L
        const val DEFAULT_SYNC_TTL_MS = 60_000L

        /** Controls that commit one deliberate value at a time are written at once. */
        fun isInstant(control: RowControl): Boolean =
            control == RowControl.TOGGLE || control == RowControl.SELECT ||
                control == RowControl.SWATCH

        /** `WIFI_SSID_1` → `Wifi Ssid 1`. The schema's fallback label, and this one's. */
        fun humanize(key: String): String =
            key.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Every value the store believes, per firmware key, in device units. */
    private val values = LinkedHashMap<String, Any?>()

    /** Last value the unit accepted, per firmware key: what a failed write reverts to. */
    private val confirmed = LinkedHashMap<String, Any?>()

    private val readBack = LinkedHashMap<String, Any?>()

    /** Per-row status and messages, keyed by the row's primary key. */
    private val rowViews = LinkedHashMap<String, RowView>()

    /** Debounce timers, keyed by the row's primary key. */
    private val timers = HashMap<String, Job>()

    /** The number of chunk frames the last read has surfaced so far. */
    val syncChunks: Int get() = (_state.value.sync as? SyncPhase.Running)?.chunks ?: 0

    fun syncIfNeeded(force: Boolean = false) {
        val phase = _state.value.sync
        if (!force && phase is SyncPhase.Ready &&
            clock() - phase.atMs < syncTtlMs &&
            rowViews.values.none { it.status == RowStatus.PENDING }
        ) {
            return
        }
        sync()
    }

    fun sync() {
        if (_state.value.sync is SyncPhase.Running) return
        _state.update { it.copy(sync = SyncPhase.Running(0), error = null) }
        scope.launch {
            val fetch = try {
                gateway.fetchConfig { index ->
                    _state.update { it.copy(sync = SyncPhase.Running(index + 1)) }
                }
            } catch (e: Exception) {
                null
            }
            if (fetch == null) {
                _state.update {
                    it.copy(
                        sync = SyncPhase.Failed("The unit did not answer the read."),
                        error = "config.get",
                    )
                }
                return@launch
            }
            applyFetch(fetch)
        }
    }

    private fun applyFetch(fetch: ConfigFetch) {
        val now = clock()
        val pending = LinkedHashMap<String, Any?>()
        for ((key, view) in rowViews) {
            if (view.status == RowStatus.PENDING) values[key]?.let { pending[key] = it }
        }
        values.clear()
        values.putAll(fetch.values)
        values.putAll(pending)
        confirmed.clear()
        confirmed.putAll(fetch.values)
        readBack.clear()
        readBack.putAll(fetch.values)
        rowViews.clear()
        val missing = ArrayList<String>()
        for (item in schema.allItems) {
            val present = values.containsKey(item.key) ||
                (item.keyY != null && values.containsKey(item.keyY))
            if (!present) {
                missing += item.key
                rowViews[item.key] = RowView(item.defaultValue, item.defaultY, RowStatus.MISSING)
            } else {
                val isPending = pending.containsKey(item.key)
                rowViews[item.key] = RowView(
                    values[item.key],
                    item.keyY?.let { values[it] },
                    if (isPending) RowStatus.PENDING else RowStatus.SYNCED,
                    if (isPending) "waiting to be written" else null,
                )
            }
        }
        val unknown = values.keys
            .filter {
                !schema.isHidden(it) && it != schema.advancedKey && schema.item(it) == null &&
                    schema.extra(it) == null
            }
            .map { guessItem(it, values[it]) }
        for (item in unknown) {
            rowViews[item.key] = RowView(values[item.key], null, RowStatus.SYNCED, null)
        }
        val status = schema.statusExtras.mapNotNull { spec ->
            if (!values.containsKey(spec.key)) return@mapNotNull null
            val raw = values[spec.key]
            val shown = if (spec.kind.equals("int", ignoreCase = true)) {
                (raw as? Number)?.toInt() ?: raw
            } else {
                raw
            }
            spec to shown
        }
        _state.update {
            it.copy(
                sync = SyncPhase.Ready(
                    keys = fetch.keys,
                    chunks = fetch.chunks,
                    atMs = now,
                    rows = schema.rows,
                ),
                rows = LinkedHashMap(rowViews),
                pending = 0,
                unknown = unknown,
                unitStatus = status,
                advanced = truth(values[schema.advancedKey]),
                imperial = truth(values["UNITS_IMPERIAL"]),
                missingKeys = missing,
            )
        }
    }

    private fun guessItem(key: String, value: Any?): SchemaItem {
        val kind = when (value) {
            is Boolean -> ValueKind.BOOL
            is Number -> {
                val d = value.toDouble()
                if (d == d.toInt().toDouble()) ValueKind.INT else ValueKind.FLOAT
            }
            else -> ValueKind.STR
        }
        return SchemaItem(
            key = key,
            kind = kind,
            control = ConfigSchema.controlFor(kind, null, false),
            label = humanize(key),
            labelIsGuessed = true,
        )
    }

    fun edit(item: SchemaItem, boxX: Any?, boxY: Any? = null) {
        if (item.kind == ValueKind.SECRET) {
            if ((boxX as? String).isNullOrBlank()) {
                markRow(item, RowStatus.SYNCED, "kept what is stored")
                return
            }
        }
        val notice = clampNotice(item, boxX)
        val deviceX = prepare(item, boxX)
        values[item.key] = deviceX
        if (item.keyY != null) {
            (boxY as? Number)?.let { y ->
                values[item.keyY!!] = clamp(y.toDouble(), item.minY, item.maxY).toLong()
            }
        }
        markRow(item, RowStatus.PENDING, notice)
        schedule(item, notice)
    }

    /** A typed value converted and coerced to what the firmware column holds. */
    private fun prepare(item: SchemaItem, box: Any?): Any? {
        val converted = SettingsUnits.fromBox(item, box, _state.value.imperial)
        return when (item.kind) {
            ValueKind.INT -> {
                val n = (converted as? Number)?.toDouble()
                    ?: (item.defaultValue as? Number)?.toDouble() ?: 0.0
                clamp(n, item.clampMin, item.clampMax).toLong()
            }
            ValueKind.FLOAT -> {
                val n = (converted as? Number)?.toDouble()
                    ?: (item.defaultValue as? Number)?.toDouble() ?: 0.0
                clamp(n, item.clampMin, item.clampMax)
            }
            ValueKind.BOOL -> converted as? Boolean ?: truth(item.defaultValue)
            ValueKind.SECRET, ValueKind.STR -> {
                val text = converted?.toString() ?: ""
                if (item.maxLength in 1 until text.length) text.substring(0, item.maxLength) else text
            }
            else -> converted
        }
    }

    private fun clampNotice(item: SchemaItem, boxX: Any?): String? {
        if (item.kind != ValueKind.INT && item.kind != ValueKind.FLOAT) return null
        val typed = (boxX as? Number)?.toDouble() ?: return null
        val dev = (SettingsUnits.fromBox(item, typed, _state.value.imperial) as? Number)
            ?.toDouble() ?: return null
        val lo = item.clampMin
        val hi = item.clampMax
        if (lo != null && dev < lo) return "limited to ${bandLabel(item, lo)} — the unit's minimum"
        if (hi != null && dev > hi) return "limited to ${bandLabel(item, hi)} — the unit's maximum"
        val uiLo = item.uiMin
        val uiHi = item.uiMax
        if (uiLo != null && uiHi != null && (dev < uiLo || dev > uiHi)) {
            return "outside the range offered here, but inside the unit's"
        }
        return null
    }

    /** A band edge in the units the user is typing in, so the notice reads like the box. */
    private fun bandLabel(item: SchemaItem, device: Double): String {
        val box = SettingsUnits.toBox(item, device, _state.value.imperial) as? Number
        val v = box?.toDouble() ?: device
        return if (item.kind == ValueKind.INT) v.toLong().toString() else trimNumber(v)
    }

    private fun clamp(n: Double, lo: Double?, hi: Double?): Double = when {
        n.isNaN() -> lo ?: 0.0
        lo != null && n < lo -> lo
        hi != null && n > hi -> hi
        else -> n
    }

    private fun schedule(item: SchemaItem, notice: String?) {
        if (isInstant(item.control)) {
            scope.launch { writeRow(item, notice) }
            return
        }
        timers.remove(item.key)?.cancel()
        timers[item.key] = scope.launch {
            delay(debounceMs)
            timers.remove(item.key)
            writeRow(item, notice)
        }
    }

    private suspend fun writeRow(item: SchemaItem, notice: String?): Boolean {
        val payload = LinkedHashMap<String, Any?>()
        values[item.key]?.let { payload[item.key] = coerce(item.kind, it) }
        item.keyY?.let { ky -> values[ky]?.let { payload[ky] = it } }
        if (payload.isEmpty()) return true
        val result = try {
            gateway.write(payload)
        } catch (e: Exception) {
            WriteResult(ok = false, error = e.message ?: "link error")
        }
        if (!result.ok) {
            values[item.key] = confirmed[item.key]
            item.keyY?.let { values[it] = confirmed[it] }
            markRow(item, RowStatus.FAILED, result.error)
            return false
        }
        confirmed.putAll(payload)
        val message = if (result.nvsErrors > 0) {
            "in RAM only, not written to flash: ${result.failedKeys}"
        } else {
            notice
        }
        markRow(
            item,
            if (result.nvsErrors > 0) RowStatus.RAM_ONLY else RowStatus.SAVED,
            message,
        )
        if (item.reboot) {
            val backToRead = payload.isNotEmpty() && payload.all { (k, v) ->
                readBack.containsKey(k) && sameValue(readBack[k], v)
            }
            _state.update {
                it.copy(
                    rebootPending = if (backToRead) it.rebootPending - item.key
                    else it.rebootPending + item.key,
                )
            }
        }
        return true
    }

    private fun coerce(kind: ValueKind, value: Any?): Any? = when (kind) {
        ValueKind.INT -> (value as? Number)?.toDouble()?.toLong() ?: value
        ValueKind.FLOAT -> (value as? Number)?.toDouble() ?: value
        ValueKind.BOOL -> value as? Boolean ?: truth(value)
        else -> value
    }

    private fun markRow(item: SchemaItem, status: RowStatus, message: String?) {
        rowViews[item.key] = RowView(
            values[item.key],
            item.keyY?.let { values[it] },
            status,
            message,
        )
        _state.update { it.copy(rows = LinkedHashMap(rowViews), pending = pendingKeys().size) }
    }

    private fun pendingKeys(): List<String> =
        values.keys.filter { key -> !sameValue(confirmed[key], values[key]) }.toList()

    suspend fun flush(): Flush {
        val items = LinkedHashSet<SchemaItem>()
        for (key in pendingKeys()) {
            timers.remove(key)?.cancel()
            schema.item(key)?.let { items += it }
        }
        var rows = 0
        var notWritten = 0
        for (item in items) {
            rows++
            if (!writeRow(item, null)) notWritten++
        }
        publishPending()
        return Flush(rows, notWritten)
    }

    /** What one SAVE press did, in rows. */
    data class Flush(val rows: Int, val notWritten: Int)

    /** Republish the pending count after a path that writes without going through [markRow]. */
    private fun publishPending() {
        _state.update { it.copy(pending = pendingKeys().size) }
    }

    /** One key back to its compiled default — with `config.reset`, not by typing. */
    fun reset(item: SchemaItem) {
        timers.remove(item.key)?.cancel()
        scope.launch {
            when (gateway.reset(item.key)) {
                ResetOutcome.APPLIED -> {
                    values[item.key] = item.defaultValue
                    item.defaultValue?.let { confirmed[item.key] = it }
                    item.keyY?.let { ky ->
                        values[ky] = item.defaultY
                        item.defaultY?.let { confirmed[ky] = it }
                    }
                    markRow(item, RowStatus.SYNCED, "reset to default")
                    if (item.reboot) {
                        _state.update { it.copy(rebootPending = it.rebootPending + item.key) }
                    }
                }
                ResetOutcome.UNKNOWN_KEY ->
                    markRow(item, RowStatus.FAILED, "no such key in this firmware")
                ResetOutcome.FAILED ->
                    markRow(item, RowStatus.FAILED, "the unit refused")
            }
        }
    }

    fun setAdvanced(on: Boolean) {
        val key = schema.advancedKey
        values[key] = on
        _state.update { it.copy(advanced = on, error = null, pending = pendingKeys().size) }
        scope.launch {
            val result = try {
                gateway.write(linkedMapOf<String, Any?>(key to on))
            } catch (e: Exception) {
                WriteResult(ok = false, error = e.message ?: "link error")
            }
            if (result.ok) {
                confirmed[key] = on
            } else {
                values[key] = confirmed[key]
                _state.update {
                    it.copy(
                        advanced = truth(confirmed[key]),
                        error = "advanced mode: ${result.error}",
                        pending = pendingKeys().size,
                    )
                }
            }
        }
    }

    fun clearRebootPending() {
        _state.update { it.copy(rebootPending = emptySet()) }
    }

    /** Every stored value plus the settings-shaped extras — the backup document. */
    fun export(): String {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in values) {
            if (unshippable(key)) continue
            out[key] = value
        }
        for ((key, value) in schema.extras) {
            if (unshippable(key)) continue
            out.putIfAbsent(key, value)
        }
        return BleJson.encode(out)
    }

    private fun unshippable(key: String): Boolean {
        val spec = schema.extra(key) ?: return false
        return spec.readOnly || spec.live || !spec.editable
    }

    suspend fun restore(text: String): RestoreReport {
        val parsed = BleJson.obj(text)
            ?: return RestoreReport(0, 0, 0, 0, 0, listOf("that file is not a settings backup"))
        val problems = ArrayList<String>()
        var applied = 0
        var refused = 0
        var leftOut = 0
        var nvs = 0
        var batches = 0
        for (card in schema.groups.flatMap { it.cards }) {
            val batch = LinkedHashMap<String, Any?>()
            for (item in card.items) {
                if (parsed.containsKey(item.key)) batch[item.key] = parsed[item.key]
                item.keyY?.let { if (parsed.containsKey(it)) batch[it] = parsed[it] }
            }
            if (batch.isEmpty()) continue
            batches++
            val result = gateway.write(batch)
            if (!result.ok) {
                problems += "${card.title}: ${result.error}"
                refused += batch.size
                continue
            }
            applied += batch.size
            nvs += result.nvsErrors
            applyLocally(batch)
        }
        val rest = parsed.filter { (key, _) ->
            !schema.isHidden(key) && schema.item(key) == null && !unshippable(key)
        }
        if (rest.isNotEmpty()) {
            batches++
            val result = gateway.write(rest)
            if (result.ok) {
                applied += rest.size
                nvs += result.nvsErrors
                applyLocally(rest)
            } else {
                problems += "unlabelled keys: ${result.error}"
                refused += rest.size
            }
        }
        val hidden = parsed.keys.filter { schema.isHidden(it) }.distinct()
        val readings = parsed.keys.filter { !schema.isHidden(it) && unshippable(it) }.distinct()
        if (hidden.isNotEmpty()) {
            leftOut += hidden.size
            problems += "${hidden.size} key(s) this app does not show were left as the unit " +
                "has them: " + hidden.take(4).joinToString(", ") + if (hidden.size > 4) "…" else ""
        }
        if (readings.isNotEmpty()) {
            leftOut += readings.size
            problems += "${readings.size} value(s) that are not settings were left alone: " +
                readings.take(4).joinToString(", ") + if (readings.size > 4) "…" else ""
        }
        sync()
        return RestoreReport(applied, refused, leftOut, nvs, batches, problems)
    }

    private fun applyLocally(batch: Map<String, Any?>) {
        for ((key, value) in batch) {
            values[key] = value
            confirmed[key] = value
        }
        val rows = LinkedHashMap(rowViews)
        for (key in batch.keys) {
            val item = schema.item(key) ?: continue
            rows[item.key] = RowView(
                values[item.key],
                item.keyY?.let { values[it] },
                RowStatus.SAVED,
            )
        }
        rowViews.clear()
        rowViews.putAll(rows)
        _state.update { it.copy(rows = rows) }
    }

    /** Anything that is not a settings read or write — a trip reset, a reboot. */
    suspend fun action(method: String, paramsJson: String? = null): CallResult =
        gateway.call(method, paramsJson)

    /** The unit's own versions as `config.get` streamed them. Empty until it has answered. */
    val buildVersion: String get() = (values["build_version"] as? String).orEmpty()

    val coreVersion: String get() = (values["core_version"] as? String).orEmpty()

    /** Values as the unit last had them, for a screen that wants to show a raw map. */
    fun snapshotValues(): Map<String, Any?> = LinkedHashMap(values)

    private fun truth(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Boolean && b is Boolean -> a == b
        else -> a == b
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** The settings tab's whole view model. */
    data class SettingsState(
        val sync: SyncPhase = SyncPhase.Idle,
        val rows: Map<String, RowView> = emptyMap(),
        /** Values the unit streams that the schema has no row for. */
        val unknown: List<SchemaItem> = emptyList(),
        val unitStatus: List<Pair<ConfigSchema.ExtraSpec, Any?>> = emptyList(),
        val advanced: Boolean = false,
        val imperial: Boolean = false,
        /** Reboot-required rows changed since the read they are measured against. */
        val rebootPending: Set<String> = emptySet(),
        val pending: Int = 0,
        val missingKeys: List<String> = emptyList(),
        val error: String? = null,
    )
}
