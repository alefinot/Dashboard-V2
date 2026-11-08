package com.alefinot.dashboardpp.vehicles

/** The app's answer to "which card is the dashboard that just answered?" */
sealed interface MatchDecision {
    /** A card already claims this unit — that card is it. */
    data class Bound(val vehicle: Vehicle) : MatchDecision

    /**
     * A card was dialled on its stored pairing and answered with a MAC nobody claims:
     * adopt it.
     */
    data class BindMac(val vehicle: Vehicle, val mac: String) : MatchDecision

    /**
     * Nothing claims this unit. The page offers adoption **only when a MAC was
     * reported** (`mac != null`): with no MAC there is no identity to adopt, only a
     * pairing that answered, and the card for a pairing alone is made by the address
     * rule, not by this branch.
     */
    data class Unknown(val address: String, val mac: String?) : MatchDecision
}

/**
 * The identity rules, as pure functions.
 *
 * They live here rather than in a composable because the whole design is the
 * argument about which evidence ranks above which - and because a wrong answer
 * renames someone's car: it tags a drive to the wrong vehicle, moves CONNECTED to a
 * card the user never pressed, and quietly binds two different units together.
 *
 * The ranking is MAC first, pairing second. A Bluetooth address is not an identity
 * either: it names *this phone's* link to a radio, so a different phone holds a
 * different address for the same dashboard, and re-pairing changes it. It is a better
 * handle than a DHCP address ever was (it does not move when the car moves), but it is
 * still a lease of a different kind, and it can never outrank the eFuse MAC the unit
 * prints about itself.
 *
 * Everything MAC-shaped goes through [normalizeMac] on both sides, so a card stored as
 * `a4:cf:12:3b:9e:07` (written by an older build) still matches the
 * `A4:CF:12:3B:9E:07` the ESP prints. Pairing addresses are compared as exact strings:
 * they are already what the app dialled, and a Bluetooth address has no other spelling.
 */
object VehicleMatch {

    /** Six hex pairs, colon-separated: exactly what `perfGetHandler` prints. */
    private val MAC_SHAPE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    /**
     * The MAC a card actually claims, or `null` if it has no usable identity.
     *
     * Going through [normalizeMac] here rather than trusting the stored text is
     * what keeps these rules total: a record holding `""` or a half-written value
     * is a card with *no* identity - still matchable by pairing, still upgradable
     * by rule 3 - instead of one that can never match anything and blocks the
     * pairing it remembers.
     */
    private fun claimedMac(v: Vehicle): String? = normalizeMac(v.mac)

    /** Whether a card is bound to a physical unit. */
    private fun isBound(v: Vehicle): Boolean = claimedMac(v) != null

    /**
     * Read a reported identity into the one form the app stores and compares.
     *
     * Case- and space-insensitive on the way in, canonical on the way out:
     * uppercase, colon-separated, no padding. Anything that is not six hex pairs
     * is `null` - an old firmware that has no `mac` field, a malformed body, a
     * half-typed editor value. Being strict about the shape is what makes the
     * stored text trustworthy: a lenient reader would let `A4CF123B9E07` into a
     * record and then never match it against the ESP's own spelling again.
     *
     * An all-zero MAC is *not* rejected. A unit whose MAC eFuse CRC is corrupt
     * does report `00:00:00:00:00:00`, and it is still the best (and only) string
     * that unit will ever give us; [macTakenBy] is what stops a second such unit
     * from claiming a card already holding it.
     */
    fun normalizeMac(raw: String?): String? {
        val text = raw?.filter { !it.isWhitespace() }?.uppercase() ?: return null
        return if (MAC_SHAPE.matches(text)) text else null
    }

    /**
     * Which card, if any, is the unit that answered on [address] with [mac]?
     *
     * [address] is the Bluetooth address the link came up on, or `null` when the answer
     * arrived with no pairing to point at. `null` disables the address half of the rules
     * rather than matching every card that has no pairing — a hand-made card holding no
     * address must never look like the owner of an answer that also named no address.
     *
     * 1. A reported MAC matches the card that claims it - that wins over any pairing.
     * 2. `mac == null` (old firmware) matches only an unbound card whose `bleAddress`
     *    is the answering pairing, and only when no MAC-bound card holds that pairing.
     * 3. A card dialled on its pairing that answers with a MAC nobody claims gets it
     *    bound ([MatchDecision.BindMac]) - a one-time upgrade, name and photo intact. A
     *    MAC nobody claims and no pairing explains is [MatchDecision.Unknown]: the page
     *    offers adoption rather than borrowing a card that exists.
     *
     * Ties are resolved by list order, first claimant wins; [MatchDecision.Unknown]
     * is preferred over guessing when the evidence contradicts itself.
     *
     * One consequence to carry into the UI: rule 2 matches only *unbound* cards, so a
     * card that already holds a MAC can never be matched by pairing. A bound card whose
     * unit is still running pre-`mac` firmware, or was rolled back, therefore answers on
     * its own remembered pairing and still resolves to [MatchDecision.Unknown] - only the
     * caller knows which card it dialled, so only the caller can decide what to show.
     */
    fun resolve(vehicles: List<Vehicle>, address: String?, mac: String?): MatchDecision {
        val reported = normalizeMac(mac)

        if (reported != null) {
            // Rule 1 - the identity is the whole answer, whatever the pairing says.
            val claimant = vehicles.firstOrNull { claimedMac(it) == reported }
            if (claimant != null) return MatchDecision.Bound(claimant)

            // Rule 3 - nobody owns this unit yet, but one card holds the pairing it
            // answered on. Bind them; the card's user already knows which vehicle this
            // is.
            val probed = unboundCardAt(vehicles, address)
            if (probed != null) return MatchDecision.BindMac(probed, reported)

            return MatchDecision.Unknown(address.orEmpty(), reported)
        }

        // Rule 2 - old firmware: the pairing is the only evidence, and it is weak
        // evidence. A card that has an identity and holds this pairing is the likely
        // owner of the link, and it is not answering (a unit that could report a MAC
        // would have done so), so an unbound card must not step into its place.
        if (address != null && vehicles.any { isBound(it) && it.bleAddress == address }) {
            return MatchDecision.Unknown(address, null)
        }
        val unbound = unboundCardAt(vehicles, address)
        if (unbound == null) return MatchDecision.Unknown(address.orEmpty(), null)
        return MatchDecision.Bound(unbound)
    }

    /**
     * The first unbound card whose pairing is [address], or `null` if there is no such
     * card. A null [address] never matches: without a pairing there is no evidence on
     * this half of the rules at all, and `card.bleAddress == null` is the shape of every
     * hand-made card in the garage.
     */
    private fun unboundCardAt(vehicles: List<Vehicle>, address: String?): Vehicle? {
        if (address == null) return null
        return vehicles.firstOrNull { !isBound(it) && it.bleAddress == address }
    }

    /**
     * Rule 4: the card that already claims [mac], or `null` if the editor may take
     * it. [exceptId] is the card being saved, which is allowed to keep its own
     * identity - without that, editing an existing vehicle would fail its own
     * uniqueness check.
     *
     * A `null` answer means two different things, and a save path must not read the
     * second as permission: "nobody holds this MAC" and "that string is not a MAC at
     * all". Check the shape first - `normalizeMac(typed) != null` - before accepting
     * a typed value, or a card can be saved with `mac = "garage door"`.
     *
     * This is the only thing standing between a garage and two cards bound to the
     * same unit (or to the same bogus all-zero MAC a CRC-corrupt eFuse reports), so
     * it is checked on save, not only in [resolve].
     */
    fun macTakenBy(vehicles: List<Vehicle>, mac: String?, exceptId: String?): Vehicle? {
        val wanted = normalizeMac(mac) ?: return null
        return vehicles.firstOrNull { it.id != exceptId && claimedMac(it) == wanted }
    }

    /**
     * Rule 5: what a successful link does to the record.
     *
     * The pairing the unit answered on becomes the card's `bleAddress` - the one
     * address the ladder dials next time. There is no history to keep: a card holds one
     * pairing, and replacing it is correct precisely because that string names a link
     * rather than a unit. The newest-first list of network addresses existed to survive a
     * DHCP lease moving under the app; a re-pairing replaces the address outright, and the
     * identity that has to survive it is the MAC, which this function never infers from
     * the address.
     *
     * [mac] is adopted when the unit reports one, and the card's existing identity
     * is kept when it does not: a link that proves nothing about identity must not
     * un-bind a card that had earned it. A stored value that is not a MAC is dropped
     * in the same pass, which leaves the card honestly unbound instead of locked.
     */
    fun bindConnect(v: Vehicle, address: String, mac: String?, nowMs: Long): Vehicle = v.copy(
        mac = normalizeMac(mac) ?: claimedMac(v),
        bleAddress = address,
        lastConnectedAtMs = nowMs,
    )
}
