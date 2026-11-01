package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleProto
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One `config.get` round trip: the values, and how many pieces they came in. */
data class ConfigFetch(
    val values: Map<String, Any?>,
    val keys: Int,
    val chunks: Int,
)

/** The answer to a `config.set`. [nvsErrors] is the part that did not reach flash. */
data class WriteResult(
    val ok: Boolean,
    val error: String? = null,
    val nvsErrors: Int = 0,
    val failedKeys: String = "",
)

enum class ResetOutcome { APPLIED, UNKNOWN_KEY, FAILED }

/** A reply to anything that is not a settings read or write — a trip reset, a reboot. */
data class CallResult(
    val ok: Boolean,
    val error: String? = null,
    val fields: Map<String, Any?> = emptyMap(),
    val body: String = "",
)

/**
 * The unit, as far as the settings store is concerned.
 *
 * Kept as an interface for the same reason the protocol code is unit-tested on the
 * JVM: the store's interesting behaviour — debouncing, clamping, what happens when
 * a write is refused — has nothing to do with GATT, and the only way to test it is
 * to hand it something that answers immediately and answers wrong on purpose.
 */
interface ConfigGateway {

    /** Reads the whole config. [onChunk] fires once per streamed chunk, for progress. */
    suspend fun fetchConfig(onChunk: (Int) -> Unit): ConfigFetch?

    /** Writes [values]. Split into as many `config.set` messages as the link needs. */
    suspend fun write(values: Map<String, Any?>): WriteResult

    /** One key back to its compiled default. */
    suspend fun reset(key: String): ResetOutcome

    /** A named action with optional parameters: `trip.reset`, `reboot`, `odo.set`. */
    suspend fun call(method: String, paramsJson: String? = null): CallResult
}

/**
 * The gateway over a paired BLE link. It holds no state of its own; the link owns
 * the connection and the retrying.
 */
class BleConfigGateway(
    private val api: BleApi,
    private val link: BleLink,
) : ConfigGateway {

    override suspend fun fetchConfig(onChunk: (Int) -> Unit): ConfigFetch? = coroutineScope {
        // Progress has to be collected while the request is in flight, and the
        // chunk frames arrive long before the summary that ends it.
        val watcher = launch { link.syncChunks.collect { onChunk(it) } }
        val mirror = try {
            api.config()
        } finally {
            watcher.cancel()
        }
        mirror?.let { ConfigFetch(values = it.values, keys = it.keys, chunks = it.chunks) }
    }

    override suspend fun write(values: Map<String, Any?>): WriteResult {
        var nvs = 0
        val failed = StringBuilder()
        for (group in RpcFraming.split(values)) {
            val reply = api.setConfig(group)
            if (!reply.ok) return WriteResult(ok = false, error = reply.error ?: "refused")
            nvs += BleJson.int(reply.fields, "nvsErrors") ?: 0
            BleJson.string(reply.fields, "nvsFailedKeys")?.let {
                if (it.isNotEmpty()) {
                    if (failed.isNotEmpty()) failed.append(',')
                    failed.append(it)
                }
            }
        }
        return WriteResult(ok = true, nvsErrors = nvs, failedKeys = failed.toString())
    }

    override suspend fun reset(key: String): ResetOutcome {
        val reply = api.resetConfig(key)
        return when {
            reply.ok -> ResetOutcome.APPLIED
            reply.error == "unknown_key" || reply.error == "bad_param" -> ResetOutcome.UNKNOWN_KEY
            else -> ResetOutcome.FAILED
        }
    }

    override suspend fun call(method: String, paramsJson: String?): CallResult {
        val reply = link.request(method, paramsJson)
        return CallResult(
            ok = reply.ok,
            error = reply.error,
            fields = reply.fields,
            body = reply.body,
        )
    }
}

/**
 * How a big write is cut into messages.
 *
 * The unit reassembles an inbound message up to `BT_MAX_MSG` bytes and refuses
 * anything past it, so a settings backup covering every key (the config the page
 * used to POST in one document, plus a `FUEL_TOUCH_POINTS` array of 128 numbers)
 * cannot go out as one request. The firmware applies a *partial* object — that is
 * what makes [split] legal at all: each piece is a valid `config.set` on its own,
 * and the order of keys inside a piece does not matter.
 */
object RpcFraming {

    /**
     * Bytes left for the envelope (`{"m":"config.set","id":N,"p":...}`) and the
     * terminator the unit's receive buffer needs. Generous on purpose: the limit is
     * a hard refusal, not a warning.
     */
    const val ENVELOPE_RESERVE = 128

    /** Largest encoded `p` object that may be sent as one message. */
    val MAX_BODY_BYTES: Int = BleProto.MAX_MESSAGE - ENVELOPE_RESERVE

    /**
     * [values] in insertion order, cut into groups whose encoded form fits under
     * [limit]. A single entry bigger than [limit] still becomes its own group — the
     * unit will refuse it, and the store reports that rather than dropping the key.
     */
    fun split(values: Map<String, Any?>, limit: Int = MAX_BODY_BYTES): List<Map<String, Any?>> {
        if (values.isEmpty()) return emptyList()
        val groups = ArrayList<Map<String, Any?>>()
        var current = LinkedHashMap<String, Any?>()
        var size = 2 // the braces of the object itself
        for ((key, value) in values) {
            // `,"key":value` — the comma is only there when the group is not empty.
            val entry = 1 + BleJson.encode(key).length + 1 + BleJson.encode(value).length
            if (current.isNotEmpty() && size + entry > limit) {
                groups += current
                current = LinkedHashMap()
                size = 2
            }
            current[key] = value
            size += if (current.size == 1) entry - 1 else entry
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }
}
