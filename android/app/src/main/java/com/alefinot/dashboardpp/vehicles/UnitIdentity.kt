package com.alefinot.dashboardpp.vehicles

import java.util.Locale

/**
 * One string that names **a unit** to the parts of the app that cannot talk to it — the
 * key the trip recorder files a session under ([com.alefinot.dashboardpp.trips.RecorderSettings]),
 * and the id a drive carries in its header ([com.alefinot.dashboardpp.trips.TripFormat]).
 *
 * An address used to be that key, which stopped being true the day the dashboard was
 * reached over Bluetooth instead of dialed over a network: a Bluetooth address names
 * *this phone's pairing* with a radio, not the unit, so a second phone holds a different
 * one for the same dashboard and a re-pairing changes it. The eFuse MAC is the opposite:
 * burnt into the unit, reported by the unit, stable across phones, pairings, and
 * firmware. It is also not always known — a unit on firmware that predates the `mac`
 * field, or a card made by hand, reports nothing.
 *
 * So the key names whichever of those two facts the app has, in that order:
 *
 * ```
 * UnitIdentity.key(mac, bleAddress)
 *   mac present  -> "mac:A4:CF:12:3B:9E:07"   // the unit, whoever reaches it
 *   else pairing -> "ble:3F:38:00:11:22:33"   // this phone's link to something
 *   else neither -> "unknown"                 // a card that has never linked
 * ```
 *
 * Three consequences, all deliberate:
 *
 * - **A drive stays one drive across reconnects and re-pairings.** Once the unit has
 *   reported its MAC, the key does not mention the link at all, so the recorder's session
 *   row and the trip's tag survive a re-pairing, a different phone, and every reconnect in
 *   between. That is the whole reason this helper exists.
 * - **A unit first met without an identity moves key when it gains one** — from
 *   `ble:…` to `mac:…`. That is a real cost (a session row and a trip header written
 *   before the first MAC arrive sit under the old key), and it is smaller than the
 *   alternative: keeping the pairing in the key forever would split every later drive
 *   every time the user unpaired and re-paired the dashboard, which is a far more common
 *   event than the one-time firmware upgrade.
 * - **`unknown` is one value, not a wildcard.** A hand-made card and a link that reported
 *   nothing both land on it, which is honest (neither has named a unit), and it can never
 *   match a real identity because the labeled halves are different text.
 *
 * Nothing here can produce a key from a MAC alone *plus a guessed address*: deriving the
 * Bluetooth address from the MAC (by adding or subtracting one, which is what this
 * firmware's radio *happens* to do) is forbidden — it is not guaranteed by the chip, it
 * breaks the moment the radio offset changes, and it would let a fabricated key unlock a
 * session belonging to a real pairing. Pass what the link actually reported, or null.
 *
 * Keys are opaque to everything but their own writer: compare them whole, never by
 * prefix, and never parse the halves back out of the text (use [TripFormat]'s header
 * fields for that).
 */
object UnitIdentity {

    /** The key of a unit that has never named itself and never answered on a pairing. */
    const val UNKNOWN = "unknown"

    /**
     * The key for a unit, from the evidence available. Always non-null and always
     * deterministic for the same inputs.
     *
     * [mac] is read through [VehicleMatch.normalizeMac], deliberately: that is the one
     * place that decides what counts as an identity, and a key built on a looser reading
     * would fork in exactly the cases the identity rules already treat as "no identity" (a
     * blank field, a half-written value from a hand-edited record). Repairing such a field
     * then repairs the key with it.
     *
     * [bleAddress] is taken as written (upper-cased for the same reason as [normCase]
     * below), not validated: it is already whatever the stack dialed, and a value that is
     * not an address should stay a distinct key rather than collapse into `unknown` and
     * merge two strangers.
     */
    fun key(mac: String?, bleAddress: String?): String {
        VehicleMatch.normalizeMac(mac)?.let { return "mac:$it" }
        val address = bleAddress?.trim()?.takeIf { it.isNotEmpty() }?.let(::normCase)
        return if (address != null) "ble:$address" else UNKNOWN
    }

    /**
     * Upper case with a fixed locale. Turkish and Azerbaijani locales lowercase/uppercase
     * Latin letters differently, and a key whose hex changed shape under a different
     * locale is a key that no longer matches its own row.
     */
    private fun normCase(value: String): String = value.uppercase(Locale.US)
}
