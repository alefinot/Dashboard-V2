package com.alefinot.dashboardpp.ble

import kotlinx.coroutines.delay

/** The byte shape of one OTA data write. */
object OtaFrames {

    /** Bytes of the little-endian offset that starts every OTA write. */
    const val OFFSET_BYTES = 4

    /** Chunk sizes are a whole multiple of this, matching the unit's flash-friendly pacing. */
    const val CHUNK_ALIGN = 16

    const val CHUNK_CAP = 512

    /** The biggest chunk a link at [mtu] can carry, aligned and at least one byte. */
    fun maxChunk(mtu: Int): Int {
        val usable = mtu - 3 - OFFSET_BYTES
        if (usable < 1) return 1
        val capped = minOf(usable, CHUNK_CAP)
        val aligned = (capped / CHUNK_ALIGN) * CHUNK_ALIGN
        return if (aligned < 1) minOf(usable, CHUNK_ALIGN) else aligned
    }

    /** One OTA write: [offset] is the image index of the first payload byte. */
    fun data(offset: Int, payload: ByteArray, at: Int, len: Int): ByteArray {
        require(offset >= 0) { "offset must not be negative" }
        require(at >= 0 && len >= 0 && at + len <= payload.size) { "payload slice out of range" }
        val frame = ByteArray(OFFSET_BYTES + len)
        frame[0] = (offset and 0xFF).toByte()
        frame[1] = ((offset ushr 8) and 0xFF).toByte()
        frame[2] = ((offset ushr 16) and 0xFF).toByte()
        frame[3] = ((offset ushr 24) and 0xFF).toByte()
        if (len > 0) System.arraycopy(payload, at, frame, OFFSET_BYTES, len)
        return frame
    }

    /** The offset a write carries, as the unit reads it. */
    fun offsetOf(frame: ByteArray): Int {
        if (frame.size < OFFSET_BYTES) return -1
        return (frame[0].toInt() and 0xFF) or
            ((frame[1].toInt() and 0xFF) shl 8) or
            ((frame[2].toInt() and 0xFF) shl 16) or
            ((frame[3].toInt() and 0xFF) shl 24)
    }
}

/** What the app learned about a picked firmware file, before a single byte is sent. */
data class OtaImage(
    val size: Int,
    /** Unsigned CRC-32 of the whole file. */
    val crc32: Long,
    /** The version the image claims to be, from its own `DPPVER=` marker, or null. */
    val versionTag: String?,
)

object OtaImages {

    const val TAG = "DPPVER="

    /** Longest version string accepted after the marker. The firmware's own is under 8. */
    const val TAG_MAX = 24

    /** Bytes that may appear in a version tag; anything else ends it. */
    private fun isTagByte(b: Int): Boolean =
        b in '0'.code..'9'.code || b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code ||
            b == '.'.code || b == '-'.code || b == '_'.code || b == '+'.code

    fun scanVersionTag(bytes: ByteArray): String? {
        val marker = TAG.toByteArray(Charsets.US_ASCII)
        if (bytes.size < marker.size) return null
        val last = bytes.size - marker.size
        var i = 0
        while (i <= last) {
            if (bytes[i] == marker[0]) {
                var matched = true
                for (k in 1 until marker.size) {
                    if (bytes[i + k] != marker[k]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    val start = i + marker.size
                    var end = start
                    while (end < bytes.size && end - start < TAG_MAX && isTagByte(bytes[end].toInt() and 0xFF)) {
                        end++
                    }
                    if (end > start) return String(bytes, start, end - start, Charsets.US_ASCII)
                }
            }
            i++
        }
        return null
    }

    /** Size, CRC-32 and claimed version of [bytes], in one pass over the file. */
    fun probe(bytes: ByteArray): OtaImage {
        val crc = java.util.zip.CRC32()
        crc.update(bytes, 0, bytes.size)
        return OtaImage(size = bytes.size, crc32 = crc.value, versionTag = scanVersionTag(bytes))
    }

    /** CRC-32 of a stream, for a caller that reads the file in pieces. */
    fun crc32Of(input: java.io.InputStream): Long {
        val crc = java.util.zip.CRC32()
        val block = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(block)
            if (n < 0) break
            crc.update(block, 0, n)
        }
        return crc.value
    }
}

/** One slice of the image: bytes [offset, offset+length). */
data class OtaSegment(val offset: Int, val length: Int)

fun nextSegment(total: Int, chunk: Int, off: Int): OtaSegment? {
    require(chunk > 0) { "chunk must be positive" }
    if (off < 0 || off >= total) return null
    return OtaSegment(off, minOf(chunk, total - off))
}

fun adoptBeginOkOffset(sentTo: Int, eventOff: Int): Int = if (sentTo == 0) eventOff else sentTo

suspend fun settledOffset(
    read: suspend () -> Int?,
    attempts: Int = 4,
    settleMs: Long = 350L,
): Int? {
    var last: Int? = null
    var tries = 0
    while (tries < attempts) {
        tries++
        val off = read() ?: return last
        if (last != null && off == last) return off
        last = off
        if (tries < attempts) delay(settleMs)
    }
    return last
}

/** One message the unit sends about an install (`BT_F_OTA` on the RPC characteristic). */
sealed interface OtaEvent {
    /** The unit opened the install; [chunk] is the write size to use, [off] where to continue. */
    data class BeginOk(val chunk: Int, val off: Int) : OtaEvent

    /** [off] bytes are on flash. This is the resume token. */
    data class Ack(val off: Int) : OtaEvent

    /** The image is written, verified and otadata flipped; a reboot follows. */
    data object Done : OtaEvent

    /** Install over, [reason] is a stable code from the unit, [off] how far it got. */
    data class Failure(val reason: String, val off: Int) : OtaEvent
}

fun parseOtaEvent(json: String): OtaEvent? {
    val obj = BleJson.obj(json) ?: return null
    return when (BleJson.string(obj, "ev")) {
        "begin_ok" -> OtaEvent.BeginOk(
            chunk = BleJson.int(obj, "chunk") ?: 0,
            off = BleJson.int(obj, "off") ?: 0,
        )
        "ack" -> BleJson.int(obj, "off")?.let { OtaEvent.Ack(off = it) }
        "done" -> OtaEvent.Done
        "error" -> OtaEvent.Failure(
            reason = BleJson.string(obj, "reason") ?: "unknown",
            off = BleJson.int(obj, "off") ?: 0,
        )
        else -> null
    }
}

object OtaErrors {

    /** True when [code] is something the driver has to do something about. */
    fun needsAction(code: String): Boolean =
        code in setOf("crc", "no_space", "bad_magic", "bad_length", "offset_mismatch", "no_chunk_size")

    fun explain(code: String): String = when (code) {
        // Refusals before anything is written.
        "already_running" -> "That is the version the unit is already running. Nothing was changed."
        "already_active" ->
            "The unit already has an install open. Reconnect and press RESUME to continue it, or " +
            "CLEAR to throw it away, before starting a new one."
        "not_connected" -> "No Bluetooth link to the unit."
        "no_client" -> "The unit is not connected to a phone right now, so it cannot take an install."
        "busy" -> "The unit is busy and refused. Try again in a moment."
        "ota_in_progress" -> "Another install is running; the unit answers nothing else until it ends."
        "too_many" -> "The unit could not take any more pieces of this file. Start again."
        // The file itself.
        "no_space" -> "That file is bigger than the spare flash slot on the unit. The unit is still running what it was."
        "size_declined" -> "The unit could not make room for that size. The unit is still running what it was."
        "bad_magic" -> "That file does not look like ESP32 firmware at all. The unit is still running what it was."
        "bad_length" ->
            "The file is not the size the unit expected from its header — it is probably truncated " +
            "or was edited after it was built. The unit is still running what it was."
        "no_chunk_size" -> "The unit could not agree a write size with the phone. Try the update again."
        "bad_state" -> "The unit's flash updater was not in the state it expected. The unit is still running what it was."
        "update_begin_failed" -> "The unit could not open its spare flash slot. The unit is still running what it was."
        "write_failed" -> "The unit could not write its flash. The unit is still running what it was."
        // Mid-transfer trouble.
        "not_open" -> "The unit had no install open for those bytes, so it dropped them. Press RESUME or start again."
        "no_session" -> "There is no install on the unit to control. Nothing was changed."
        "offset_mismatch" -> "The pieces arrived out of order, so the unit stopped the install. Start it again from the file."
        "crc" ->
            "The bytes that arrived do not match the file you picked — something was lost in the " +
            "air. The unit is still running the firmware it had. Press START to send the file again."
        "link_lost" ->
            "The Bluetooth link dropped. The unit holds the install open for two minutes for the " +
            "phone to come back; reconnect on the link card and press RESUME inside that window."
        "stalled" -> "The unit heard nothing for too long and gave the install back. The unit is still running what it was."
        "aborted" -> "The install was cancelled. The unit is still running what it was."
        // The unit's own verdict at the end.
        "end_failed" ->
            "The unit wrote the whole file and then its own image check refused it — the file was " +
            "built for a different board or flash layout. The unit is still running what it was."
        "not_implemented" ->
            "This firmware does not carry a Bluetooth installer yet — flash it over USB until the " +
            "unit's own build includes one."
        // RPC-layer codes.
        "no_permission" -> "The unit will not run that command over this connection. Unlock it first."
        "unknown_method" -> "The unit does not know that command — it is running an older firmware."
        "too_many_frames" -> "The unit could not reassemble the reply. Try again."
        "parse_error" -> "The unit could not read the command. Nothing was changed."
        // Codes this app raises about the file, before the unit sees anything.
        "file_unreadable" -> "The file could not be read from wherever it is. Copy it onto the tablet and try again."
        "file_empty" -> "That file is empty."
        "too_small" -> "That file is far too small to be firmware — it is not a firmware image."
        "too_big" -> "That file is bigger than this unit's flash — it is not firmware for this board."
        "cancelled" -> "Cancelled. The unit is untouched."
        "timeout" -> "The unit did not answer. It may have restarted into the new firmware; check the version on this card after reconnecting."
        "unknown" -> "The unit refused the install for a reason it did not name."
        else -> "The unit refused the install ('$code'). The unit is still running what it was."
    }
}

/** Where the transfer stands, for the screen. */
enum class OtaStage {
    /** Nothing picked. */
    Idle,

    /** Reading the file the person chose: hashing it and looking for its version marker. */
    Reading,

    /** Read and understood, waiting for the go-ahead. */
    Ready,

    /** `ota.begin` sent, waiting for the unit to open its spare slot. */
    Handshaking,

    /** Bytes going over the air. */
    Sending,

    /** All bytes sent, waiting for the unit's own checks. */
    Verifying,

    /** The unit said it installed; the link will drop while it reboots. */
    Installed,

    /** Waiting for the phone to reconnect and continue from the last ACK. */
    Paused,

    /** Over, badly. */
    Failed,
}

/** What the firmware card shows. */
data class OtaView(
    val stage: OtaStage = OtaStage.Idle,
    val fileName: String = "",
    val image: OtaImage? = null,
    /** Bytes the phone has put on the wire. */
    val sent: Int = 0,
    /** Bytes the unit has confirmed on flash. Never ahead of what was sent. */
    val acked: Int = 0,
    val total: Int = 0,
    /** Rolling rate over the whole transfer, 0 before the first ACK lands. */
    val bytesPerSecond: Double = 0.0,
    /** Seconds the transfer has been running, measured from the first thing it did. */
    val elapsedSeconds: Double = 0.0,
    /** Writes per second over the same window as [bytesPerSecond]. */
    val writesPerSecond: Double = 0.0,
    /** OTA writes this phone put on the wire — the count a finished install is diagnosed by. */
    val writes: Int = 0,
    /** Set when the stage is [OtaStage.Failed]; the unit's own code. */
    val error: String? = null,
    /** A line the card shows beside the buttons (unit answers, gates, prompts). */
    val note: String? = null,
) {
    /** 0..1 for a progress bar; 0 when nothing is moving. */
    fun fraction(): Float {
        if (total <= 0) return 0f
        val f = acked.toFloat() / total.toFloat()
        return when {
            f < 0f -> 0f
            f > 1f -> 1f
            else -> f
        }
    }

    /** Seconds left at the current rate, null when there is no rate to project from. */
    fun etaSeconds(): Int? {
        if (bytesPerSecond <= 0.0 || total <= 0) return null
        val rest = total - acked
        if (rest <= 0) return 0
        return ((rest / bytesPerSecond) + 0.5).toInt()
    }

    /** Percent the unit has confirmed, for a one-line readout. */
    fun percent(): Int = (fraction() * 100f).toInt()
}

class OtaTracker(
    val total: Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private var sentAtMs = -1L
    private var startedAtMs = -1L

    var stage: OtaStage = OtaStage.Sending
        private set
    var sent: Int = 0
        private set
    var acked: Int = 0
        private set
    var chunk: Int = 0
        private set
    var writes: Int = 0
        private set
    var error: String? = null
        private set

    /** Applies one unit event; returns true when the transfer is over. */
    fun apply(event: OtaEvent): Boolean = when (event) {
        is OtaEvent.BeginOk -> {
            chunk = event.chunk
            if (event.off > acked) acked = event.off
            if (sent < acked) sent = acked
            if (startedAtMs < 0) startedAtMs = clock()
            stage = OtaStage.Sending
            false
        }
        is OtaEvent.Ack -> {
            if (startedAtMs < 0) startedAtMs = clock()
            if (event.off > acked) acked = minOf(event.off, total)
            stage = OtaStage.Sending
            false
        }
        is OtaEvent.Failure -> fail(event.reason, event.off)
        OtaEvent.Done -> {
            acked = total
            stage = OtaStage.Installed
            true
        }
    }

    /** The phone wrote up to [bytes] of the image. */
    fun wrote(bytes: Int) {
        if (sentAtMs < 0) sentAtMs = clock()
        if (startedAtMs < 0) startedAtMs = clock()
        writes++
        if (bytes > sent) sent = minOf(bytes, total)
    }

    fun fail(reason: String, off: Int = acked): Boolean {
        error = reason
        stage = OtaStage.Failed
        if (off > acked) acked = minOf(off, total)
        if (sent < acked) sent = acked
        return true
    }

    fun bytesPerSecond(): Double {
        if (startedAtMs < 0 || acked <= 0) return 0.0
        val elapsedMs = clock() - startedAtMs
        if (elapsedMs <= 0) return 0.0
        return acked * 1000.0 / elapsedMs.toDouble()
    }

    fun writesPerSecond(): Double {
        if (startedAtMs < 0 || writes <= 0) return 0.0
        val elapsedMs = clock() - startedAtMs
        if (elapsedMs <= 0) return 0.0
        return writes * 1000.0 / elapsedMs.toDouble()
    }

    fun elapsedSeconds(): Double {
        if (startedAtMs < 0) return 0.0
        val elapsedMs = clock() - startedAtMs
        return if (elapsedMs <= 0L) 0.0 else elapsedMs / 1000.0
    }
}
