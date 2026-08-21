package com.alefinot.dashboardpp.ble

import android.bluetooth.BluetoothUuid

/**
 * The BLE GATT transport between the Dashboard++ ESP32 (GATT server /
 * peripheral) and this app (GATT central). See
 * Implementation plans/bluetooth-weather-and-phone-notifications-plan.md §4.
 *
 * One service, two characteristics:
 *  - RX  (phone -> ESP): write-without-response, the app writes framed bytes.
 *  - TX  (ESP -> phone): notify + read, the ESP pushes STATUS (and any
 *      future ESP -> phone framed data).
 *
 * Frame layout (3-byte header + UTF-8 JSON payload):
 *   +--------+----------------+----------------+
 *   | type   | len (2 LE)    | JSON payload    |
 *   | 1 byte |               | (len bytes)     |
 *   +--------+----------------+----------------+
 * `len` is the 16-bit little-endian length of the JSON payload *excluding*
 * the 3-byte header. The ESP reassembles the byte stream (it is the
 * authoritative reassembler); this object is the single codec the app uses
 * to *encode* outbound frames and (for symmetry / any future inbound path)
 * to parse them.
 */
object Protocol {
    // §4.1 identifiers — the 16-bit UUIDs mapped onto the standard
    // 0000XXXX-0000-1000-8000-00805F9B34FB base (BLE 16-bit -> 128-bit).
    const val ADVERTISE_NAME = "Dashboard++"
    // The 16-bit §4.1 UUIDs mapped onto the standard 128-bit base, as
    // android.bluetooth.BluetoothUuid (the type the GATT stack compares
    // against — a java.util.UUID would never be equal to s.uuid).
    val SVC_UUID: BluetoothUuid = BluetoothUuid.fromString("0000D11A-0000-1000-8000-00805F9B34FB")
    val RX_UUID: BluetoothUuid = BluetoothUuid.fromString("0000D101-0000-1000-8000-00805F9B34FB")
    val TX_UUID: BluetoothUuid = BluetoothUuid.fromString("0000D102-0000-1000-8000-00805F9B34FB")

    // §4.2 frame type bytes.
    const val T_WEATHER: Byte = 0x01.toByte()
    const val T_NOTIFICATION: Byte = 0x02.toByte()
    const val T_PING: Byte = 0x03.toByte()
    const val T_ACK: Byte = 0x04.toByte()
    const val T_NAV: Byte = 0x05.toByte()
    const val T_MEDIA: Byte = 0x06.toByte()

    private val KNOWN_TYPES = setOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
    )

    /**
     * Encode one frame: 1-byte type + 2-byte LE length + UTF-8 JSON.
     * @throws IllegalArgumentException if the payload exceeds the 512-byte
     * payload cap (the ESP RX characteristic max value).
     */
    fun encode(type: Byte, json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        require(payload.size <= 512) { "frame payload too large: ${payload.size}" }
        val out = ByteArray(3 + payload.size)
        out[0] = type
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }

    /**
     * The single inbound parser (kept in one place for the §4.2 codec).
     * Today the app only *writes* frames (it is the central); this exists so
     * the framing logic lives in exactly one function, and so any future
     * ESP -> phone framed path reuses it. Returns the number of bytes
     * consumed, or 0 when the buffer is too short for a complete frame.
     */
    fun parseFrame(
        bytes: ByteArray,
        offset: Int,
        cb: (type: Byte, json: String) -> Unit,
    ): Int {
        if (bytes.size - offset < 3) return 0
        val type = bytes[offset].toInt() and 0xFF
        if (type !in KNOWN_TYPES) return 0
        val len = (bytes[offset + 1].toInt() and 0xFF) or
            (((bytes[offset + 2].toInt() and 0xFF) shl 8) and 0xFFFF)
        if (bytes.size - offset < 3 + len) return 0
        val payload = bytes.copyOfRange(offset + 3, offset + 3 + len)
        cb(type.toByte(), String(payload, Charsets.UTF_8))
        return 3 + len
    }
}
