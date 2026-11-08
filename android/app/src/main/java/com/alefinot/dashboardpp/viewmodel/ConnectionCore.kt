package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.vehicles.MatchDecision
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.VehicleMatch

/** Why an attempt is running, which decides how far it is allowed to go. */
enum class ConnectMode {
    /** Unattended, at launch: dial the selected card's pairing and nothing else. */
    SILENT,

    PROBE,

    SEARCH,
}

/** One dashboard on the advertising list, as the BLE scan reported it. */
data class Found(val address: String, val name: String, val rssi: Int)

/** What one link attempt answered. */
sealed interface LinkOutcome {
    data class Ok(
        val vehicleId: String?,
        val address: String,
        val name: String,
        val version: String?,
        val mac: String?,
        val mtu: Int = 0,
    ) : LinkOutcome

    /** The link did not come up, or came up and refused the identity read. */
    data class Fail(val address: String) : LinkOutcome
}

/** What to do next. One step, decided from what has already been tried. */
sealed interface NextAction {
    /** Nothing to reach, and nothing being reached for: the vehicle page shows [reason]. */
    data class Idle(val reason: String) : NextAction

    data class Pair(val vehicleId: String?, val address: String, val name: String) : NextAction

    /** Sweep for units advertising the dashboard service. */
    data object Scan : NextAction

    data class Connect(
        val vehicleId: String,
        val address: String,
        val name: String,
        val version: String?,
        val mac: String?,
        val mtu: Int,
    ) : NextAction

    /** The attempt is spent and nothing came up: [reason] is the line under that card. */
    data class Failed(val reason: String) : NextAction
}

/** A dashboard that answered and is claimed by no card — an offer for the page, not a write. */
data class UnclaimedUnit(val address: String, val name: String, val version: String?, val mac: String)

/** One decision: what to do, what it is worth writing down, and what the page must say. */
data class CoreResult(
    val next: NextAction,
    /** The card to save, when the step learned something about a unit. */
    val persist: Vehicle? = null,
    /** Set when an unclaimed unit answered; the page offers to name it. */
    val adoption: UnclaimedUnit? = null,
    /** Set when the connection belongs to a card other than the one selected. */
    val notice: String? = null,
)

object ConnectionCore {

    /** The line the vehicle page shows when there is no card to reach. */
    const val NO_VEHICLE_SELECTED = "No vehicle selected"

    const val NEVER_PAIRING = "has never been paired — scan from ADD VEHICLE"

    /** The line for a pairing that was dialed and did not answer. */
    const val NO_ANSWER = "did not answer over Bluetooth. Is it powered, paired, and in range?"

    private const val UNCLAIMED = "A dashboard answered that no vehicle card knows."
    private const val AMBIGUOUS =
        "More than one vehicle card is paired with that unit, and it did not report an " +
            "identity, so the app cannot tell which vehicle it is."
    private const val NOTHING_FOUND =
        "No dashboard answered, and the scan found none. Check that the unit is powered " +
            "and not already linked to another phone."

    fun decide(
        vehicles: List<Vehicle>,
        selectedId: String?,
        tried: List<String>,
        scanDone: Boolean,
        found: List<Found>,
        outcome: LinkOutcome?,
        mode: ConnectMode,
        nowMs: Long,
        pinned: Found? = null,
    ): CoreResult {
        val answer = outcome as? LinkOutcome.Ok
        if (answer != null) return resolveAnswer(vehicles, selectedId, answer, mode, nowMs)
        return nextStep(vehicles, selectedId, tried, scanDone, found, outcome, mode, pinned)
    }

    data class ForgetDecision(val needsSystemUnpair: Boolean, val note: String?)

    /** The sentence the vehicle page shows when the phone still holds the bond. */
    const val STILL_BONDED =
        "The card no longer holds a pairing, but this phone is still bonded to that unit. " +
            "Remove it in the phone's Bluetooth settings if this phone should stop " +
            "answering it."

    fun forgetUnit(hadPairing: Boolean, adapterBonded: Boolean): ForgetDecision =
        if (hadPairing && adapterBonded) ForgetDecision(true, STILL_BONDED) else ForgetDecision(false, null)

    private fun dialed(card: Vehicle, tried: List<String>): Boolean =
        card.bleAddress != null && card.bleAddress in tried

    private fun nextStep(
        vehicles: List<Vehicle>,
        selectedId: String?,
        tried: List<String>,
        scanDone: Boolean,
        found: List<Found>,
        outcome: LinkOutcome?,
        mode: ConnectMode,
        pinned: Found?,
    ): CoreResult {
        val card = selectedId?.let { id -> vehicles.firstOrNull { it.id == id } }
        if (selectedId != null && card == null) return CoreResult(NextAction.Idle(NO_VEHICLE_SELECTED))

        if (pinned != null && pinned.address !in tried) {
            return CoreResult(NextAction.Pair(null, pinned.address, pinned.name))
        }

        if (card != null && card.hasPairing && !dialed(card, tried)) {
            return CoreResult(
                NextAction.Pair(card.id, card.bleAddress!!, card.name.ifEmpty { card.id }),
            )
        }

        // 3. Only a search the user asked for may sweep the radio, and only once.
        if (mode == ConnectMode.SEARCH) {
            val nextFound = found
                .filter { it.address != pinned?.address }
                .sortedByDescending { it.rssi }
                .firstOrNull { it.address !in tried }
            return when {
                !scanDone -> CoreResult(NextAction.Scan)

                nextFound != null -> CoreResult(
                    NextAction.Pair(null, nextFound.address, nextFound.name),
                )

                else -> CoreResult(
                    NextAction.Failed(
                        if (outcome is LinkOutcome.Fail && card != null) {
                            "${card.name.ifEmpty { card.id }} $NO_ANSWER $NOTHING_FOUND"
                        } else NOTHING_FOUND,
                    ),
                )
            }
        }

        return when {
            card == null -> CoreResult(NextAction.Idle(NO_VEHICLE_SELECTED))
            !card.hasPairing -> CoreResult(
                NextAction.Idle("${card.name.ifEmpty { card.id }} $NEVER_PAIRING"),
            )
            else -> CoreResult(
                NextAction.Failed("${card.name.ifEmpty { card.id }} $NO_ANSWER"),
            )
        }
    }

    // ------------------------------------------------------------------ the identity

    private fun resolveAnswer(
        vehicles: List<Vehicle>,
        selectedId: String?,
        answer: LinkOutcome.Ok,
        mode: ConnectMode,
        nowMs: Long,
    ): CoreResult {
        val mac = VehicleMatch.normalizeMac(answer.mac)

        if (mac == null) {
            val owners = vehicles.filter { it.bleAddress != null && it.bleAddress == answer.address }
            return when (owners.size) {
                1 -> toConnect(owners.first(), answer, selectedId, vehicles, nowMs)
                0 -> CoreResult(NextAction.Failed(UNCLAIMED))
                else -> CoreResult(NextAction.Failed(AMBIGUOUS))
            }
        }

        return when (val decision = VehicleMatch.resolve(vehicles, answer.address, mac)) {
            is MatchDecision.Bound -> toConnect(decision.vehicle, answer, selectedId, vehicles, nowMs)

            is MatchDecision.BindMac -> toConnect(decision.vehicle, answer, selectedId, vehicles, nowMs)

            is MatchDecision.Unknown -> CoreResult(
                next = if (mode == ConnectMode.SILENT) {
                    NextAction.Idle(UNCLAIMED)
                } else {
                    NextAction.Failed(UNCLAIMED)
                },
                adoption = UnclaimedUnit(answer.address, answer.name, answer.version, mac),
            )
        }
    }

    private fun toConnect(
        card: Vehicle,
        answer: LinkOutcome.Ok,
        selectedId: String?,
        vehicles: List<Vehicle>,
        nowMs: Long,
    ): CoreResult {
        val bound = VehicleMatch.bindConnect(card, answer.address, answer.mac, nowMs)
        val version = answer.version ?: bound.firmwareVersion
        return CoreResult(
            next = NextAction.Connect(
                vehicleId = card.id,
                address = answer.address,
                name = answer.name,
                version = answer.version,
                mac = VehicleMatch.normalizeMac(answer.mac),
                mtu = answer.mtu,
            ),
            persist = bound.copy(firmwareVersion = version),
            notice = noticeFor(card, selectedId, vehicles),
        )
    }

    private fun noticeFor(card: Vehicle, selectedId: String?, others: List<Vehicle>): String? {
        if (selectedId == null || selectedId == card.id) return null
        val pressed = others.firstOrNull { it.id == selectedId }
        val pressedName = pressed?.name?.ifEmpty { pressed.id } ?: selectedId
        return "The dashboard that answered belongs to ${card.name.ifEmpty { card.id}}, " +
            "not $pressedName."
    }
}

object CardSelection {

    fun endsLiveSession(sessionVehicleId: String?, selectedVehicleId: String): Boolean =
        sessionVehicleId != null && sessionVehicleId != selectedVehicleId
}
