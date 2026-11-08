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

class BleConfigGateway(
    private val api: BleApi,
    private val link: BleLink,
) : ConfigGateway {

    override suspend fun fetchConfig(onChunk: (Int) -> Unit): ConfigFetch? = coroutineScope {
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

object RpcFraming {

    const val ENVELOPE_RESERVE = 128

    /** Largest encoded `p` object that may be sent as one message. */
    val MAX_BODY_BYTES: Int = BleProto.MAX_MESSAGE - ENVELOPE_RESERVE

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
