package com.alefinot.dashboardpp.ble

/**
 * The BLE wire format, mirrored from `src/bt_proto.h`.
 *
 * Every message is an 8-byte header plus a payload, and a message bigger than one
 * MTU travels as several fragments of the same `seq`, `FIRST` on the first one and
 * `LAST` on the final one. The unit reassembles by `seq` and refuses a second open
 * message, so a fragment lost by the radio can never grow an unbounded buffer on
 * either side.
 *
 *   byte 0  version   1
 *   byte 1  type      [BtType]
 *   byte 2  flags     bit0 FIRST, bit1 LAST
 *   byte 3  reserved  0
 *   byte 4  seq       message id, little endian
 *   byte 6  len       payload bytes in this fragment, little endian
 *
 * This object is deliberately free of Android types: the framing is the part that
 * has to be right on the first try, and it is unit-tested without a radio.
 */
object BleProto {

    const val VERSION = 1

    const val HEADER = 8

    /** Largest payload one fragment may carry; matches the unit's frame buffer. */
    const val MAX_FRAGMENT = 512

    /** Largest message either side will reassemble (`BT_MAX_MSG`). */
    const val MAX_MESSAGE = 8192

    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02

    /** Phone -> unit, JSON request. */
    const val T_REQ = 1

    /** Unit -> phone, the final JSON answer to a request. */
    const val T_RESP = 2

    /** Unit -> phone, the 1 Hz snapshot. */
    const val T_TELEM = 3

    const val T_WEATHER = 4
    const val T_TIME = 5
    const val T_PING = 6
    const val T_PONG = 7
    const val T_OTA = 8
    const val T_LOG = 9

    /**
     * One slice of a streamed `config.get`. Its body is
     * `{"id":req,"i":n,"last":bool,"d":{...}}`; the stream closes with an ordinary
     * [T_RESP]. A separate type means the client never has to guess, from the shape
     * of an object, whether it holds a complete answer or one more slice.
     */
    const val T_CHUNK = 10

    const val SERVICE_UUID = "d9a1f000-6c1e-4b8a-9f2d-3e7c1a5b9d40"
    const val TELEM_UUID = "d9a1f001-6c1e-4b8a-9f2d-3e7c1a5b9d40"
    const val RPC_UUID = "d9a1f002-6c1e-4b8a-9f2d-3e7c1a5b9d40"
    const val OTA_UUID = "d9a1f003-6c1e-4b8a-9f2d-3e7c1a5b9d40"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val TARGET_MTU = 517

    /**
     * Bytes of payload one ATT write carries at [mtu]. The unit's own buffer caps a
     * fragment at [MAX_FRAGMENT], so a negotiated MTU larger than it buys nothing
     * and must not be used to send longer frames.
     */
    fun fragmentPayload(mtu: Int): Int {
        val usable = mtu - 3 - HEADER
        return when {
            usable < 1 -> 1
            usable > MAX_FRAGMENT -> MAX_FRAGMENT
            else -> usable
        }
    }

    /** Splits [payload] into the frames that carry it, in order. */
    fun frames(type: Int, seq: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        val size = fragmentPayload(mtu)
        val out = ArrayList<ByteArray>((payload.size / size) + 1)
        var offset = 0
        do {
            val n = minOf(size, payload.size - offset)
            val last = offset + n >= payload.size
            val frame = ByteArray(HEADER + n)
            frame[0] = VERSION.toByte()
            frame[1] = type.toByte()
            frame[2] = ((if (offset == 0) FLAG_FIRST else 0) or
                (if (last) FLAG_LAST else 0)).toByte()
            frame[3] = 0
            frame[4] = (seq and 0xFF).toByte()
            frame[5] = ((seq shr 8) and 0xFF).toByte()
            frame[6] = (n and 0xFF).toByte()
            frame[7] = ((n shr 8) and 0xFF).toByte()
            if (n > 0) System.arraycopy(payload, offset, frame, HEADER, n)
            out.add(frame)
            offset += n
        } while (offset < payload.size)
        return out
    }
}

/**
 * Reassembles one characteristic's fragments into messages.
 *
 * One instance per characteristic, never one for the link: telemetry and RPC
 * answers travel on different handles and can interleave on the air, so a shared
 * accumulator would splice a snapshot into the middle of a config chunk.
 */
class FrameAssembler {

    class Message(val type: Int, val seq: Int, val payload: ByteArray)

    private val buffer = ByteArray(BleProto.MAX_MESSAGE)
    private var length = 0
    private var type = -1
    private var seq = -1
    private var open = false

    /** Feeds one frame; returns a message only when its LAST fragment landed. */
    fun accept(frame: ByteArray): Message? {
        if (frame.size < BleProto.HEADER) return null
        if (frame[0].toInt() != BleProto.VERSION) return null

        val frameType = frame[1].toInt() and 0xFF
        val flags = frame[2].toInt() and 0xFF
        val frameSeq = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
        val len = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
        if (len > frame.size - BleProto.HEADER) return null

        if (flags and BleProto.FLAG_FIRST != 0) {
            // A new message starts here. Anything half-received belongs to a frame
            // that will never complete (the unit sends one message at a time per
            // characteristic), so it is dropped rather than merged.
            open = true
            this.type = frameType
            this.seq = frameSeq
            length = 0
        }
        if (!open || frameSeq != seq || frameType != type) return null
        if (length + len > BleProto.MAX_MESSAGE) {
            open = false
            length = 0
            return null
        }
        if (len > 0) System.arraycopy(frame, BleProto.HEADER, buffer, length, len)
        length += len

        if (flags and BleProto.FLAG_LAST != 0) {
            open = false
            val message = Message(type, seq, buffer.copyOf(length))
            length = 0
            return message
        }
        return null
    }

    fun reset() {
        open = false
        length = 0
        type = -1
        seq = -1
    }
}
