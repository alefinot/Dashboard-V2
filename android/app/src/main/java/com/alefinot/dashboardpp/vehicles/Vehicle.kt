package com.alefinot.dashboardpp.vehicles

import java.util.Locale

/** What a vehicle is filed under in the editor and in filters. */
enum class VehicleType { CAR, MOTORCYCLE, OTHER }

/**
 * A vehicle in the garage — the thing the app *means* when it connects.
 *
 * A card is not an address: it is a record about a physical vehicle that exists whether
 * or not a radio is in range, which is why a card can be selected while nothing is
 * answering. Two identities hang off it, and they mean different things:
 *
 * - [mac] is the dashboard's own eFuse MAC, read from `/api/mac` over the link. It is the
 *   **identity**: burnt into the unit at the factory, unchanged by network settings, a
 *   reboot, or a re-pairing, and it is what a trip is tagged with and what two cards are
 *   compared on ([com.alefinot.dashboardpp.viewmodel.ConnectionCore] refuses to connect a
 *   unit whose reported MAC belongs to a different card).
 * - [bleAddress] is the address the phone dials to reach that unit's radio — the address
 *   the pairing was made on. It is a **key, not an identity**: it identifies *this
 *   phone's* link to that unit, not the unit itself, which is why it can never be shown
 *   where the MAC can be, never be derived from the MAC, and never outrank it. A card
 *   that reports no MAC at all is still usable, it simply cannot be recognised when
 *   something else answers.
 *
 * [id] is the card's own handle: generated locally, never reused, and the foreign key
 * that the photo files and the trip records hang off. It is deliberately not derived from
 * either radio identity — a card exists before either is known, and a hand-made card has
 * neither.
 *
 * Everything here is plain data: the rules about what an identity means live in
 * [VehicleMatch], the rules about what the user may type live in [VehicleEditorRules],
 * and the file format lives in [VehicleCodec].
 */
data class Vehicle(
    /** Stable local handle. [VehicleStore.newId] mints it; never derived from [mac]. */
    val id: String,

    /**
     * The unit's eFuse MAC as reported by `/api/mac`, normalised by
     * [VehicleMatch.normalizeMac] to "AA:BB:CC:DD:EE:FF" uppercase.
     *
     * `null` means *not recorded*, which is not the same as *this unit has no MAC*: the
     * firmware always reports one, and what can be missing is the answer — a card made by
     * hand in the editor, or one met over a link that never got far enough to ask.
     *
     * The value is written only by code that read it from the device itself, never typed
     * by a user, and it is the one field two cards are compared on when deciding whether
     * they describe the same dashboard.
     */
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

    /**
     * The Bluetooth address this phone's pairing was made on — the address [BleLink]
     * dials, and the one Android may hand back only after a real bond exists (see
     * [com.alefinot.dashboardpp.ble.BleBond]).
     *
     * It names a *pairing*, not a unit: a different phone holds a different address for
     * the same dashboard, and re-pairing this phone to the same unit changes it. That is
     * why it is stored separately from [mac] rather than merged into it, and why the
     * recorder's session key is built from the two of them together ([UnitIdentity.key])
     * rather than from this one on its own.
     */
    val bleAddress: String? = null,

    /**
     * When a unit belonging to this card last answered, epoch milliseconds, or null when
     * the card has never connected. Written only by a verified connect, never edited by
     * the user — it is an observation, not an opinion, and it is what orders the garage.
     */
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

    /**
     * The identity line a card shows where an address used to go.
     *
     * The MAC comes first and alone when it is known, because it is the only one of the
     * two that names the dashboard itself; the pairing is appended as a fact ("PAIRED")
     * rather than as a place. With no MAC there is nothing better to show, so the pairing
     * address stands on its own under a `PAIRED` label — and it is labelled as such so a
     * reader cannot mistake it for an identity.
     *
     * Blank when the card has neither. A caller that draws a line should skip a blank one
     * rather than render an empty row.
     */
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

    /**
     * Whether this card's unit is too old to reach at all.
     *
     * [BLE_MIN_FIRMWARE] is the first firmware that carries the GATT server this app
     * dials; below it there is no link to make, and the app has no second transport to
     * fall back on — that is the accepted cost of deleting the Wi-Fi stack, and the card
     * is the only place left where it can be said. The label comes from the last connect
     * that *did* happen, which is why a card that has never been connected is not called
     * out this way: an unknown version is not an old one, and guessing would tell the
     * user to go update a unit that may already be current.
     */
    val preBleFirmware: Boolean
        get() = firmwareVersion != null && !firmwareAtLeast(firmwareVersion, BLE_MIN_FIRMWARE)
}

/**
 * The first firmware carrying the Bluetooth GATT server the app connects to. The ESP side
 * of the contract: `src/bt_gatt.cpp` publishes the service from this release on, and every
 * release before it can only be reached over HTTP — which this app no longer speaks.
 */
const val BLE_MIN_FIRMWARE = "1.5.0"

/**
 * Numeric firmware comparison: "1.10.2" is newer than "1.4.6" to a number compare and
 * older to a string compare, and the version labels the units print are dotted numbers
 * with an optional leading `V`.
 *
 * A label this parser does not understand answers **true** — it would be a cruel trick to
 * tell someone their unit is too old on the strength of a string the parser could not
 * read, when the alternative is to say nothing and let them find out by trying. The same
 * bias is why a missing version never triggers [Vehicle.preBleFirmware].
 */
fun firmwareAtLeast(version: String?, minimum: String): Boolean {
    if (version.isNullOrBlank()) return true
    val parts = version.trim().removePrefix("V").removePrefix("v").split(".").take(3)
    if (parts.size < 2) return true
    // The first two components have to read as numbers, or this is not a version at all.
    // Reading `1.x.2` as "1.2" would be inventing a fact about someone's unit.
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
