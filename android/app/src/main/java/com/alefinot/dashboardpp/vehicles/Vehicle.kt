package com.alefinot.dashboardpp.vehicles

import java.util.Locale

/** What a vehicle is filed under in the editor and in filters. */
enum class VehicleType { CAR, MOTORCYCLE, OTHER }

data class Vehicle(
    /** Stable local handle. [VehicleStore.newId] mints it; never derived from [mac]. */
    val id: String,

    val mac: String? = null,

    /** The user's own name for this vehicle — what the card is titled with. */
    val name: String = "",

    /** Filename inside `vehicle_photos/`, or null when no photo has been chosen. */
    val photoFile: String? = null,

    val type: VehicleType = VehicleType.CAR,

    /** Free text, as entered. Blank means "not filled in", and there is no format to enforce. */
    val plate: String = "",

    val maker: String = "",
    val model: String = "",

    /** Free text as well: a year, a model year, or a nickname — validating it would be guessing. */
    val year: String = "",

    /** A note to self. No structure, no length limit, and never used by any rule. */
    val notes: String = "",

    val bleAddress: String? = null,

    val lastConnectedAtMs: Long? = null,

    /** Firmware version read on the last connect; a label for the card, not a rule input. */
    val firmwareVersion: String? = null,

    /** Creation stamp, epoch milliseconds; kept for sorting and for undo. */
    val createdAtMs: Long = 0L,
) {
    /** This card has a recorded identity: a `mac` the identity rules can read. */
    val hasIdentity: Boolean get() = !mac.isNullOrBlank()

    /** This phone has a pairing to dial. Says nothing about *which* unit it reaches. */
    val hasPairing: Boolean get() = !bleAddress.isNullOrBlank()

    fun identityLabel(): String {
        val m = mac?.takeIf { it.isNotBlank() }
        val paired = hasPairing
        return when {
            m != null && paired -> "MAC ${m.uppercase(Locale.US)} · PAIRED"
            m != null -> "MAC ${m.uppercase(Locale.US)}"
            paired -> "PAIRED ${bleAddress!!.uppercase(Locale.US)}"
            else -> ""
        }
    }

    val preBleFirmware: Boolean
        get() = firmwareVersion != null && !firmwareAtLeast(firmwareVersion, BLE_MIN_FIRMWARE)
}

const val BLE_MIN_FIRMWARE = "1.5.0"

fun firmwareAtLeast(version: String?, minimum: String): Boolean {
    if (version.isNullOrBlank()) return true
    val parts = version.trim().removePrefix("V").removePrefix("v").split(".").take(3)
    if (parts.size < 2) return true
    val nums = parts.map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    if (nums[0] == null || nums[1] == null) return true
    val min = minimum.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until 3) {
        val a = nums.getOrElse(i) { null } ?: 0
        val b = min.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return true
}
