package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.BleProto

/**
 * A unit that answers immediately, and answers wrong on purpose.
 *
 * Everything the settings store does that is worth testing — debouncing, clamping,
 * what a refused write leaves on screen — has nothing to do with GATT, and none of it
 * can be tested against a real dashboard on a bench. So the fake records what it was
 * asked to do, and the tests read that back.
 *
 * [write] cuts its input the same way [BleConfigGateway] does, so a test that asserts
 * "no message over the unit's cap" is measuring the bytes the real link would carry.
 */
class FakeGateway : ConfigGateway {

    /** The values this fake unit holds, as the read would stream them. */
    var stored = LinkedHashMap<String, Any?>()

    /** Keys this fake unit knows how to reset; anything else is `unknown_key`. */
    var knownResetKeys: Set<String> = emptySet()

    val writes = ArrayList<Map<String, Any?>>()

    /** Every key, in the order the pieces carried it — what actually reached the unit. */
    val writtenKeys = ArrayList<String>()

    /** The encoded length of each piece [write] sent, for the size assertions. */
    val pieceBytes = ArrayList<Int>()

    val resets = ArrayList<String>()

    val calls = ArrayList<Pair<String, String?>>()

    /** Scripted answers for [write], consumed in order; the default is "ok". */
    val writeQueue = ArrayDeque<WriteResult>()

    /** Scripted answers for [call], consumed in order; the default is "ok". */
    val callQueue = ArrayDeque<CallResult>()

    var fetchThrows = false

    /** How many chunk callbacks the last read reported, and their indexes. */
    val chunkIndexes = ArrayList<Int>()

    var fetchCount = 0

    override suspend fun fetchConfig(onChunk: (Int) -> Unit): ConfigFetch? {
        if (fetchThrows) throw IllegalStateException("link went away mid-read")
        fetchCount++
        val values = LinkedHashMap(stored)
        values.keys.chunked(40).forEachIndexed { index, _ ->
            chunkIndexes += index
            onChunk(index)
        }
        return ConfigFetch(values = values, keys = values.size, chunks = chunkIndexes.size)
    }

    override suspend fun write(values: Map<String, Any?>): WriteResult {
        val pieces = RpcFraming.split(values)
        val result = writeQueue.removeFirstOrNull() ?: WriteResult(ok = true)
        for (piece in pieces) {
            writes += piece
            pieceBytes += BleJson.encode(piece).length
            writtenKeys += piece.keys
            // A write the unit refused does not reach its flash. (The real firmware
            // applies piece by piece, so a refusal in the middle leaves the earlier
            // pieces in; this fake is all-or-nothing, which is the case the tests need.)
            if (result.ok) stored.putAll(piece)
        }
        return result
    }

    override suspend fun reset(key: String): ResetOutcome {
        resets += key
        return if (key in knownResetKeys) ResetOutcome.APPLIED else ResetOutcome.UNKNOWN_KEY
    }

    override suspend fun call(method: String, paramsJson: String?): CallResult {
        calls += method to paramsJson
        return callQueue.removeFirstOrNull() ?: CallResult(ok = true)
    }

    /** Largest single message body this fake was asked to carry, in bytes. */
    fun biggestPiece(): Int = pieceBytes.maxOrNull() ?: 0

    /** The cap the firmware enforces on a reassembled inbound message. */
    fun linkCap(): Int = BleProto.MAX_MESSAGE
}
