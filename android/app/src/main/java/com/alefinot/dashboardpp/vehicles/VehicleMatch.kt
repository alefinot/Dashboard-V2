package com.alefinot.dashboardpp.vehicles

/** The app's answer to "which card is the dashboard that just answered?" */
sealed interface MatchDecision {
    /** A card already claims this unit — that card is it. */
    data class Bound(val vehicle: Vehicle) : MatchDecision

    data class BindMac(val vehicle: Vehicle, val mac: String) : MatchDecision

    data class Unknown(val address: String, val mac: String?) : MatchDecision
}

object VehicleMatch {

    /** Six hex pairs, colon-separated: exactly what `perfGetHandler` prints. */
    private val MAC_SHAPE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    private fun claimedMac(v: Vehicle): String? = normalizeMac(v.mac)

    /** Whether a card is bound to a physical unit. */
    private fun isBound(v: Vehicle): Boolean = claimedMac(v) != null

    fun normalizeMac(raw: String?): String? {
        val text = raw?.filter { !it.isWhitespace() }?.uppercase() ?: return null
        return if (MAC_SHAPE.matches(text)) text else null
    }

    fun resolve(vehicles: List<Vehicle>, address: String?, mac: String?): MatchDecision {
        val reported = normalizeMac(mac)

        if (reported != null) {
            // Rule 1 - the identity is the whole answer, whatever the pairing says.
            val claimant = vehicles.firstOrNull { claimedMac(it) == reported }
            if (claimant != null) return MatchDecision.Bound(claimant)

            val probed = unboundCardAt(vehicles, address)
            if (probed != null) return MatchDecision.BindMac(probed, reported)

            return MatchDecision.Unknown(address.orEmpty(), reported)
        }

        if (address != null && vehicles.any { isBound(it) && it.bleAddress == address }) {
            return MatchDecision.Unknown(address, null)
        }
        val unbound = unboundCardAt(vehicles, address)
        if (unbound == null) return MatchDecision.Unknown(address.orEmpty(), null)
        return MatchDecision.Bound(unbound)
    }

    private fun unboundCardAt(vehicles: List<Vehicle>, address: String?): Vehicle? {
        if (address == null) return null
        return vehicles.firstOrNull { !isBound(it) && it.bleAddress == address }
    }

    fun macTakenBy(vehicles: List<Vehicle>, mac: String?, exceptId: String?): Vehicle? {
        val wanted = normalizeMac(mac) ?: return null
        return vehicles.firstOrNull { it.id != exceptId && claimedMac(it) == wanted }
    }

    fun bindConnect(v: Vehicle, address: String, mac: String?, nowMs: Long): Vehicle = v.copy(
        mac = normalizeMac(mac) ?: claimedMac(v),
        bleAddress = address,
        lastConnectedAtMs = nowMs,
    )
}
