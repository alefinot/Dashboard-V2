package com.alefinot.dashboardpp.ble

import kotlinx.coroutines.delay

/**
 * The firmware-install transfer, in the parts that can be checked without a radio.
 *
 * A phone pushing a 1.5 MB image into a dashboard unit is the one thing this app does where a
 * silent off-by-one bricks nothing but does waste a driver's afternoon, so everything that
 * decides *what goes on the wire and how far we got* lives here, free of Android types, and is
 * unit-tested against the same rules `src/bt_ota.cpp` follows on the unit:
 *
 *  - the image travels as raw writes on the OTA characteristic, each one tagged with the byte
 *    index it belongs at: `offset(4, little endian) + payload`. Nothing frames them and no JSON
 *    wraps them, because JSON around 1.5 MB is 25 % of padding for no benefit.
 *  - the chunk size is the unit's decision, announced in `begin_ok`; the phone clamps to the
 *    ATT ceiling too, so a unit that answers a silly number cannot make the stack drop writes.
 *  - integrity is CRC-32 over every byte of the image, declared before the first write and
 *    folded up by both sides as they go. It catches a mis-framed or dropped write before the
 *    ESP32's own image check does. It is *not* a signature — nothing here authenticates the
 *    file (AGENTS.md rule 3), it only says "the bytes that arrived are the bytes that left".
 *  - resume works because the ACK is an offset. After a link drop the phone asks where the unit
 *    stands and continues from there; a phone that tries to start over from 0 is refused,
 *    because the unit's CRC has already folded bytes 0..off and re-sending them would corrupt
 *    the check.
 */

/** The byte shape of one OTA data write. */
object OtaFrames {

    /** Bytes of the little-endian offset that starts every OTA write. */
    const val OFFSET_BYTES = 4

    /** Chunk sizes are a whole multiple of this, matching the unit's flash-friendly pacing. */
    const val CHUNK_ALIGN = 16

    /**
     * The largest payload one OTA write may carry, whatever the MTU says.
     *
     * An ATT write carries `MTU - 3` bytes (opcode + 2-byte handle), and 4 of those belong to the
     * offset that opens every OTA frame — so at a 517-byte MTU the ceiling is 510, and the
     * biggest 16-byte multiple at or under it is 496. Getting this wrong by four bytes is not a
     * performance problem: the stack rejects the write (or, on some phones, quietly fragments
     * it) and the transfer stalls with a write timeout.
     */
    const val CHUNK_CAP = 512

    /** The biggest chunk a link at [mtu] can carry, aligned and at least one byte. */
    fun maxChunk(mtu: Int): Int {
        val usable = mtu - 3 - OFFSET_BYTES
        if (usable < 1) return 1
        val capped = minOf(usable, CHUNK_CAP)
        val aligned = (capped / CHUNK_ALIGN) * CHUNK_ALIGN
        // A 23-byte MTU leaves 16 usable, and a 19-byte link leaves 12, which is not a whole
        // 16-byte step. Staying under the ceiling matters more than staying aligned, so an odd
        // remainder is allowed rather than rounding up past what the link can carry.
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

/**
 * Reading a firmware file the way the unit will read it.
 *
 * `src/config.cpp` embeds `DPPVER=<FW_VERSION>` inside `firmware.bin` so a binary can be
 * identified without a manifest, and `scripts/fix_upload_layout.py` reads that same marker when
 * a wired flash happens. The app reads it for the same reason: the file a person picked off a
 * download or out of a build folder has no name worth trusting (`firmware.bin` is what every
 * build is called), and "you are about to put 1.5.1 on a unit already running 1.5.1" is worth
 * saying out loud before 1.5 MB travels over the air.
 */
object OtaImages {

    const val TAG = "DPPVER="

    /** Longest version string accepted after the marker. The firmware's own is under 8. */
    const val TAG_MAX = 24

    /** Bytes that may appear in a version tag; anything else ends it. */
    private fun isTagByte(b: Int): Boolean =
        b in '0'.code..'9'.code || b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code ||
            b == '.'.code || b == '-'.code || b == '_'.code || b == '+'.code

    /**
     * Finds the version the image declares, or null when it carries no readable marker.
     *
     * The marker sits wherever the linker put it, so this is a plain search. Ending the tag at
     * the first byte that cannot belong to a version is what stops a run of binary past the
     * real value from being read as part of it.
     */
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
                    // A marker with nothing readable after it is a stray byte sequence, not a
                    // version. Keep looking.
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

/**
 * The next write to put on the wire, or null when the image is already there.
 *
 * This is the whole tiling rule, in one place, so the transfer loop cannot invent its own:
 * a segment always starts where the previous one ended and only the last is short. That is what
 * makes "did the whole file go" answerable by comparing the unit's ACK against [total] instead of
 * counting writes — and it is why a resume can start anywhere: the segment at a resumed offset is
 * computed the same way as the one the interrupted transfer was sending when the link dropped.
 */
fun nextSegment(total: Int, chunk: Int, off: Int): OtaSegment? {
    require(chunk > 0) { "chunk must be positive" }
    if (off < 0 || off >= total) return null
    return OtaSegment(off, minOf(chunk, total - off))
}

/**
 * The offset to continue from when a `begin_ok` turns up while a transfer is running.
 *
 * `ota.begin` is answered twice by the unit: in the RPC reply, and again as a `begin_ok` event on
 * the OTA characteristic. The reply has already been applied before the loop starts, so the event
 * is a duplicate that arrives one write later. Re-adopting its offset then moves the phone back
 * behind bytes the unit already has on flash, and the unit ends the install with
 * `offset_mismatch` — measured on a bench unit on 2026-11-02, where a duplicate `begin_ok` at 0
 * arrived 0.4 s into a 1.5 MB install and killed it at 496 bytes. Before the first write nothing
 * has been sent, so the event is still the authoritative resume answer and is taken; after it, the
 * phone's own position stands. A unit that genuinely restarted mid-transfer is caught by the
 * ACK/`ota.status` recovery in the loop, which pulls the offset back from the unit when the unit
 * is behind.
 */
fun adoptBeginOkOffset(sentTo: Int, eventOff: Int): Int = if (sentTo == 0) eventOff else sentTo

/**
 * The unit's position in the image, read until it stops moving.
 *
 * `ota.status` answers with the bytes BtTask has drained into flash, not with the bytes the radio
 * delivered: the unit's handoff ring can be holding several frames of everything the phone has
 * already sent, and a drain pass takes up to ~125 ms on this bench (`OTA: ... longest Nms between
 * drains`). One reading taken during a hiccup can therefore sit behind reality by a frame or more,
 * and continuing from it re-sends frames the unit already accepted — which the unit answers with
 * `offset_mismatch`, killing the install (bench, 2026-11-04: `write at 396304, unit expects 396800`
 * at 396800 of 1593664 bytes, after a single `ota.status` answered 496 B short).
 *
 * So keep reading while the number still moves, and believe it once it stands still. Returns null if
 * the unit never answered; if it is still moving when the attempts run out, the newest reading is
 * returned — that is the closest thing to the truth available, and it errs on the side of not
 * re-sending bytes the unit has.
 */
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

/**
 * Parses one `BT_F_OTA` message.
 *
 * Shapes, straight from `src/bt_ota.cpp`:
 * `{"ev":"begin_ok","chunk":512,"off":0}`, `{"ev":"ack","off":65536}`,
 * `{"ev":"done"}`, `{"ev":"error","reason":"crc","off":12345}`.
 * Anything unrecognised returns null and is ignored: an event the app cannot read must not
 * end a transfer that is going fine, and the transfer's own watchdog covers a unit that has
 * genuinely stopped.
 */
fun parseOtaEvent(json: String): OtaEvent? {
    val obj = BleJson.obj(json) ?: return null
    return when (BleJson.string(obj, "ev")) {
        "begin_ok" -> OtaEvent.BeginOk(
            chunk = BleJson.int(obj, "chunk") ?: 0,
            off = BleJson.int(obj, "off") ?: 0,
        )
        // An ACK without an offset is not an ACK: the offset is the whole message, and it is the
        // resume token a dropped transfer is continued from. Reading a missing one as 0 would
        // silently rewind a transfer that is nearly finished.
        "ack" -> BleJson.int(obj, "off")?.let { OtaEvent.Ack(off = it) }
        "done" -> OtaEvent.Done
        "error" -> OtaEvent.Failure(
            reason = BleJson.string(obj, "reason") ?: "unknown",
            off = BleJson.int(obj, "off") ?: 0,
        )
        else -> null
    }
}

/**
 * What a failure code means, in a sentence for the firmware card.
 *
 * The codes are the unit's (`src/bt_ota.cpp` and `src/bt_rpc.cpp`), not invented here, plus the
 * handful this app raises about the file itself before anything is sent. Every unit code leaves
 * the image that was running still installed — that is the whole point of writing to the other
 * OTA slot — and the sentence says so where it matters, because "the update failed" on a driver's
 * phone reads like "my dashboard is dead".
 *
 * The list is kept beside the code that raises it on the unit; `OtaCoreTest` asserts every one of
 * them has its own sentence, so a new code on the firmware side cannot arrive here unnamed.
 */
object OtaErrors {

    /** True when [code] is something the driver has to do something about. */
    fun needsAction(code: String): Boolean =
        code in setOf("crc", "no_space", "bad_magic", "bad_length", "offset_mismatch", "no_chunk_size")

    /**
     * Codes that can only have left the running firmware where it was: the unit refused before
     * writing, threw the image away, or never got it. `OtaCoreTest` pins the sentence for the ones
     * a driver most often sees, so the reassurance cannot be edited out of one of them by accident.
     */
    private val UNIT_UNTOUCHED = setOf(
        "too_many", "no_space", "size_declined", "bad_magic", "bad_length", "no_chunk_size",
        "bad_state", "update_begin_failed", "write_failed", "offset_mismatch", "crc", "stalled",
        "aborted", "end_failed", "not_implemented", "too_many_frames", "parse_error",
        "file_unreadable", "file_empty", "too_small", "too_big", "cancelled",
    )

    fun explain(code: String): String {
        val said = when (code) {
            // Refusals before anything is written.
            "already_running" -> "Same version is already running."
            "already_active" -> "An install is already open — RESUME or CLEAR it first."
            "not_connected" -> "No Bluetooth link."
            "no_client" -> "No phone connected to the unit."
            "busy" -> "Unit busy — try again."
            "ota_in_progress" -> "Another install is running."
            "too_many" -> "Too many pieces — start again."
            // The file itself.
            "no_space" -> "Larger than the unit's spare flash slot."
            "size_declined" -> "Unit could not make room for that size."
            "bad_magic" -> "Not ESP32 firmware."
            "bad_length" -> "Size does not match the header — file truncated or edited."
            "no_chunk_size" -> "No write size agreed."
            "bad_state" -> "Flash updater in an unexpected state."
            "update_begin_failed" -> "Could not open the spare flash slot."
            "write_failed" -> "Flash write failed."
            // Mid-transfer trouble.
            "not_open" -> "No install open — press RESUME or start again."
            "no_session" -> "No install to control."
            "offset_mismatch" -> "Pieces arrived out of order — start again."
            "crc" -> "CRC mismatch — bytes lost in the air."
            "link_lost" -> "Link dropped — press RESUME within two minutes."
            "stalled" -> "Unit gave the install back after silence."
            "aborted" -> "Install cancelled."
            // The unit's own verdict at the end.
            "end_failed" -> "Image check refused it — wrong board or flash layout."
            "not_implemented" -> "No Bluetooth installer in this firmware — flash over USB."
            // RPC-layer codes.
            "no_permission" -> "Command not allowed on this connection."
            "unknown_method" -> "This firmware does not know that command."
            "too_many_frames" -> "Reply could not be reassembled."
            "parse_error" -> "Command unreadable."
            // Codes this app raises about the file, before the unit sees anything.
            "file_unreadable" -> "File could not be read."
            "file_empty" -> "File is empty."
            "too_small" -> "Not a firmware image."
            "too_big" -> "Bigger than this board's flash."
            "cancelled" -> "Cancelled."
            "timeout" -> "No answer — check the version after reconnecting."
            "unknown" -> "Install refused without a reason."
            else -> "Refused ('$code')."
        }
        return if (code in UNIT_UNTOUCHED) "$said Unit unchanged." else said
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

/**
 * Keeps the transfer's progress honest.
 *
 * Two numbers matter and they are not the same number: what the phone wrote, and what the unit
 * said is on its flash. The screen shows the second, because that is the one that survives a
 * dropped link. The rate is measured over the whole transfer rather than the last ACK window —
 * a BLE link breathes with the connection interval, and a rate read from the last two ACKs
 * jumps around enough to make an ETA useless.
 */
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
    /**
     * OTA writes this phone has put on the wire: one per [wrote] call, never one per byte, because
     * a write is the thing the link actually costs.
     */
    var writes: Int = 0
        private set
    var error: String? = null
        private set

    /** Applies one unit event; returns true when the transfer is over. */
    fun apply(event: OtaEvent): Boolean = when (event) {
        is OtaEvent.BeginOk -> {
            chunk = event.chunk
            // A unit that resumes tells us where it stands. Trust it over our own memory: it is
            // the one holding the CRC state and the flash writes.
            if (event.off > acked) acked = event.off
            if (sent < acked) sent = acked
            if (startedAtMs < 0) startedAtMs = clock()
            stage = OtaStage.Sending
            false
        }
        is OtaEvent.Ack -> {
            if (startedAtMs < 0) startedAtMs = clock()
            if (event.off > acked) acked = minOf(event.off, total)
            // A lower ACK is only legitimate as part of a fresh begin_ok after a resume; applied
            // on its own it is a stale frame from a transfer that already ended, and moving the
            // bar backwards on the strength of that is worse than ignoring it.
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
        // Counted per call, not per new byte: a re-send after a hiccup is a real write on the link
        // even when it covers ground the progress figure already knows about.
        writes++
        if (bytes > sent) sent = minOf(bytes, total)
    }

    /**
     * Ends the transfer with [reason]. Always terminal.
     *
     * The unit's [off] is the honest distance: it is the byte index the unit stopped at, which is
     * ahead of the last ACK whenever the failure happened between two ACK windows. The bar shows
     * where the unit actually got rather than where this phone last heard from it — that number is
     * diagnostic only, since a failed install cannot be resumed from it.
     */
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

    /**
     * Writes per second over the same whole-transfer window as [bytesPerSecond]. Bytes/s says how
     * much data moved; this says how hard the link was pushed, independent of the chunk the unit
     * asked for: 1450 writes of 1.2 kB and 1750 writes of 1.0 kB look the same in bytes and very
     * different here. Same "no window yet -> 0.0" rule as the byte rate.
     */
    fun writesPerSecond(): Double {
        if (startedAtMs < 0 || writes <= 0) return 0.0
        val elapsedMs = clock() - startedAtMs
        if (elapsedMs <= 0) return 0.0
        return writes * 1000.0 / elapsedMs.toDouble()
    }

    /**
     * Seconds from the first thing the transfer did (the `begin_ok`, an ACK or the first write) to
     * now, 0.0 before any of them. This is the "how long did that take" number the install summary
     * shows next to the rate: a field install has no bench log to read it out of.
     */
    fun elapsedSeconds(): Double {
        if (startedAtMs < 0) return 0.0
        val elapsedMs = clock() - startedAtMs
        return if (elapsedMs <= 0L) 0.0 else elapsedMs / 1000.0
    }
}
