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
    val skipped: Int,
    val nvsErrors: Int,
    val batches: Int,
    val problems: List<String> = emptyList(),
)

/**
 * The settings page's model: schema + the unit's values + local edits.
 *
 * Three rules the class is built around, each one learned from the WebUI this
 * replaces (see the plan's §3.4):
 *
 *  1. **No editing before `config.get` has been joined to the schema.** The page
 *     went blank the same way; here the sync bar owns that window and the rows do
 *     not exist until there is a value to put in them.
 *  2. **A number never leaves the app outside the firmware's band.** The old page
 *     left that to the unit, which clamps silently and answers `{"ok":true}` — a
 *     row that looks saved over a value nobody typed. Out-of-band input is clamped
 *     here and the row says what it was clamped to.
 *  3. **The screen shows what was confirmed.** A refused write reverts its row; an
 *     `nvsErrors` answer marks it RAM-only; a key the unit stopped reporting is
 *     marked missing.
 *
 * Writes are per row (or per `xy` pair), not per form: a switch goes out when it is
 * flipped, a number when typing stops. The unit applies a partial object, so there
 * is no "Save all" to get wrong and a half-finished edit cannot lose another row's
 * change.
 */
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
                // Whatever is on screen stays: a failed read must not empty a form
                // the user may already be editing.
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
        // A row the user is still typing in (or whose write is in flight) survives the
        // read. A re-read happens on its own — entering the tab after the TTL, a RETRY
        // after a failed link — and a number that vanishes from the box a quarter of a
        // second after it was typed is the kind of thing that gets blamed on the unit.
        val pending = LinkedHashMap<String, Any?>()
        for ((key, view) in rowViews) {
            if (view.status == RowStatus.PENDING) values[key]?.let { pending[key] = it }
        }
        values.clear()
        values.putAll(fetch.values)
        values.putAll(pending)
        confirmed.clear()
        confirmed.putAll(fetch.values)
        // The debounce timers are deliberately left running: cancelling them is how a
        // typed value would be dropped rather than written.
        rowViews.clear()
        val missing = ArrayList<String>()
        for (item in schema.allItems) {
            // A secret row is streamed as an empty string by design; that is not a
            // missing value and must not be treated as one.
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
        // Values the unit streams that the schema has no row for. They are kept,
        // exported and drawn under a heading of their own rather than dropped — a
        // schema nobody regenerated should be a visible gap, not a setting that
        // quietly disappears.
        val unknown = values.keys
            .filter {
                !schema.isHidden(it) && it != schema.advancedKey && schema.item(it) == null
            }
            .map { guessItem(it, values[it]) }
        _state.update {
            it.copy(
                sync = SyncPhase.Ready(
                    keys = fetch.keys,
                    chunks = fetch.chunks,
                    atMs = now,
                    rows = schema.rows,
                ),
                rows = LinkedHashMap(rowViews),
                unknown = unknown,
                advanced = truth(values[schema.advancedKey]),
                imperial = truth(values["UNITS_IMPERIAL"]),
                missingKeys = missing,
                // `rebootPending` is deliberately not touched here. A re-read cannot say
                // whether the unit came back since a boot-only change, so the badge keeps
                // its meaning until the app is the one that asked for the restart (see
                // [clearRebootPending]); clearing it on every read would hide a change
                // that is still not in effect.
            )
        }
    }

    /**
     * A row the schema does not describe, guessed from the JSON type the unit sent:
     * editable, labelled from its key, flagged as a guess. Better a control with an
     * ugly name than a setting nobody can reach.
     */
    /**
     * The app asked the unit to restart, so the boot-only rows it changed are on their
     * way in. Nothing else clears them: `config.get` answers the same whether or not the
     * unit has come back, so the app would be guessing.
     */
    fun clearRebootPending() {
        _state.update { it.copy(rebootPending = emptySet()) }
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

    /**
     * Edits one row. [boxX] and [boxY] are in the units the box shows (mph, °F, in),
     * exactly as typed; conversion, clamping and coercion to the firmware's type all
     * happen here so no caller has to remember any of them.
     */
    fun edit(item: SchemaItem, boxX: Any?, boxY: Any? = null) {
        if (item.kind == ValueKind.SECRET) {
            // Secrets are streamed back empty, so an empty box means "leave the
            // stored one alone" — posting "" would clear a passphrase.
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

    /**
     * What a row should say when the typed value is not the value the unit will
     * hold. The unit clamps silently and reports success, so an app that forwards an
     * out-of-band number shows a saved row over a number nobody entered.
     */
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

    private suspend fun writeRow(item: SchemaItem, notice: String?) {
        val payload = LinkedHashMap<String, Any?>()
        values[item.key]?.let { payload[item.key] = coerce(item.kind, it) }
        item.keyY?.let { ky -> values[ky]?.let { payload[ky] = it } }
        if (payload.isEmpty()) return
        val result = try {
            gateway.write(payload)
        } catch (e: Exception) {
            WriteResult(ok = false, error = e.message ?: "link error")
        }
        if (!result.ok) {
            // Revert to what the unit last confirmed rather than leaving a number on
            // screen that was never accepted anywhere.
            values[item.key] = confirmed[item.key]
            item.keyY?.let { values[it] = confirmed[it] }
            markRow(item, RowStatus.FAILED, result.error)
            return
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
            _state.update { it.copy(rebootPending = it.rebootPending + item.key) }
        }
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
        _state.update { it.copy(rows = LinkedHashMap(rowViews)) }
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

    /** The tab's own master switch. It is a settings row, so it writes like one. */
    /**
     * The tab's own master switch. `ADV_MODE` is a firmware row like any other (it is
     * in `CFG_TABLE`, and the unit keeps it in NVS), but the schema keeps it out of the
     * card list because this page carries it in the header next to the search box — so
     * there is no row to [edit] here, and the key is written on its own instead.
     */
    fun setAdvanced(on: Boolean) {
        val key = schema.advancedKey
        values[key] = on
        _state.update { it.copy(advanced = on, error = null) }
        scope.launch {
            val result = try {
                gateway.write(linkedMapOf<String, Any?>(key to on))
            } catch (e: Exception) {
                WriteResult(ok = false, error = e.message ?: "link error")
            }
            if (result.ok) {
                confirmed[key] = on
            } else {
                // The switch goes back to what the unit says rather than showing an
                // advanced mode that is not on.
                values[key] = confirmed[key]
                _state.update {
                    it.copy(advanced = truth(confirmed[key]), error = "advanced mode: ${result.error}")
                }
            }
        }
    }

    /** Every stored value plus the extras the unit streams — the backup document. */
    fun export(): String {
        val out = LinkedHashMap<String, Any?>(values)
        for ((key, value) in schema.extras) out.putIfAbsent(key, value)
        return BleJson.encode(out)
    }

    /**
     * Restores a backup document, **card by card** rather than as one object: the
     * whole config plus the fuel ohm array is larger than the unit's inbound message
     * cap, and a card is the largest group that still means something if one batch
     * turns out to be refused. Each batch is cut again by [RpcFraming] if it is
     * still too large.
     */
    suspend fun restore(text: String): RestoreReport {
        val parsed = BleJson.obj(text)
            ?: return RestoreReport(0, 0, 0, 0, listOf("that file is not a settings backup"))
        val problems = ArrayList<String>()
        var applied = 0
        var skipped = 0
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
                skipped += batch.size
                continue
            }
            applied += batch.size
            nvs += result.nvsErrors
            applyLocally(batch)
        }
        // Keys the schema does not know — another firmware's rows — still go back
        // rather than being dropped by the restore. `ADV_MODE` belongs in this group:
        // it is a firmware row that the schema keeps out of the cards, and a backup
        // that did not bring it back would quietly turn advanced mode off.
        val rest = parsed.filter { (key, _) -> !schema.isHidden(key) && schema.item(key) == null }
        if (rest.isNotEmpty()) {
            batches++
            val result = gateway.write(rest)
            if (result.ok) {
                applied += rest.size
                nvs += result.nvsErrors
                applyLocally(rest)
            } else {
                problems += "unlabelled keys: ${result.error}"
            }
        }
        // A V2 unit has no Wi-Fi stack and no NTP client, so the credential keys the
        // backup carries from an older unit are named rather than written: they are in
        // the file, and saying they were left alone is what keeps "restored 198 keys"
        // from meaning something other than what it says.
        val unused = parsed.keys.filter { schema.isHidden(it) }
        if (unused.isNotEmpty()) {
            skipped += unused.size
            problems += "${unused.size} key(s) this unit has no use for were left alone: " +
                unused.take(4).joinToString(", ") + if (unused.size > 4) "…" else ""
        }
        sync()
        return RestoreReport(applied, skipped, nvs, batches, problems)
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

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** The settings tab's whole view model. */
    data class SettingsState(
        val sync: SyncPhase = SyncPhase.Idle,
        val rows: Map<String, RowView> = emptyMap(),
        /** Values the unit streams that the schema has no row for. */
        val unknown: List<SchemaItem> = emptyList(),
        val advanced: Boolean = false,
        val imperial: Boolean = false,
        /** Reboot-required rows changed since the read they are measured against. */
        val rebootPending: Set<String> = emptySet(),
        val missingKeys: List<String> = emptyList(),
        val error: String? = null,
    )
}
