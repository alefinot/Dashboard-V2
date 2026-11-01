package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.vehicles.MatchDecision
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.VehicleMatch

/** Why an attempt is running, which decides how far it is allowed to go. */
enum class ConnectMode {
    /** Unattended, at launch: dial the selected card's pairing and nothing else. */
    SILENT,

    /**
     * The user pressed CONNECT, or the reconnect ladder decided to try again: dial the
     * card's pairing, still without a scan. A scan is a radio sweep the user did not
     * ask for, and on Bluetooth it is the difference between a reconnect in two seconds
     * and a hunt that can land on the dashboard in the next car.
     */
    PROBE,

    /**
     * The user asked to *find* a dashboard (ADD VEHICLE → SEARCH): dial what the card
     * remembers, then let the radio sweep for units advertising the dashboard service.
     */
    SEARCH,
}

/** One dashboard on the advertising list, as the BLE scan reported it. */
data class Found(val address: String, val name: String, val rssi: Int)

/** What one link attempt answered. */
sealed interface LinkOutcome {
    /**
     * A dashboard came up on the link.
     *
     * [vehicleId] is the card the link was **dialed for** (null for a unit found by a
     * scan, which no card owns yet) — it is not the answer to "which vehicle is this",
     * which is what the identity rules below decide from [mac] and [address].
     *
     * [version] is null when the unit never sent a snapshot to read it from, and [mac]
     * is null on firmware that predates the `perf.mac` field. Both nulls are load
     * bearing: with no MAC the address is the whole claim, and a unit that never
     * reported a version cannot be checked against the trip store's requirements.
     */
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

    /**
     * Dial [address] for the card [vehicleId] — a pairing this phone already holds, or a
     * scan result named by [name]. This is the only step that touches the radio's
     * connect path, and it is the same call either way: Android's `connectGatt` pairs
     * on first use and reuses the bond afterwards.
     */
    data class Pair(val vehicleId: String?, val address: String, val name: String) : NextAction

    /** Sweep for units advertising the dashboard service. */
    data object Scan : NextAction

    /**
     * The unit on the link belongs to [vehicleId]: show it as connected.
     *
     * The identity rules pick the card; a Connect naming a card the user did not select
     * is legal (rule 1 outranks the dial) and comes with a [CoreResult.notice] saying so.
     */
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

/**
 * The connection ladder, as a pure step function.
 *
 * The app used to hunt for *whatever Dashboard++ is out there* and take the first one
 * that answered; the garage replaced that question with "reach **this** vehicle's
 * dashboard", which splits the old single ladder into three decisions that only work
 * together: how far to go (mode), which card an answer belongs to (identity), and what
 * to write down (rule 5). Drawing them as one `when` in a composable is how the old code
 * ended up unable to tell "the unit at this address belongs to another card" from
 * "nothing answered" — the two look identical from inside a verify call.
 *
 * Over Bluetooth the ladder got shorter and its vocabulary changed, and the difference
 * is worth being explicit about, because it is the reason this file still exists:
 *
 *  - **An address is a pairing, not a location.** A network address described where a
 *    unit could be reached and went stale whenever DHCP moved it; a Bluetooth address is
 *    this phone's name for the bond, and it is the *only* thing that can dial a unit that
 *    is switched on and in range. So `Dial`/`Pair` is one step, not a probe of a list,
 *    and there is no such thing as trying "the other address" for the same card.
 *  - **Discovery is a radio sweep, and it is the user's decision.** A network sweep was
 *    cheap to hide in a retry; a BLE scan needs a permission, an on radio, and fifteen
 *    seconds of the phone listening for a stranger's advertisement. So only [ConnectMode.SEARCH]
 *    ever scans, and a card that has never been paired says so instead of quietly
 *    starting a sweep.
 *  - **Identity is the only cross-phone truth.** A MAC says "this unit"; a pairing says
 *    "this phone's link to something". The rules below therefore always prefer the MAC,
 *    and refuse to guess a vehicle from a pairing when more than one card could own it.
 *
 * So [decide] answers exactly one step, and the view model is the loop. Everything here
 * is pure: no radio, no clock, no store. That is what makes the expensive cases testable
 * — a card bound to a unit that reports no identity, an address two cards claim, a unit
 * nobody owns — instead of leaving them to be discovered on a tablet where the wrong
 * answer writes someone's car name into a trip log.
 *
 * The identity half deliberately **does not** reuse [VehicleMatch.resolve] for a
 * no-MAC answer. `resolve` takes the first unbound card holding the address, which is
 * the right tie-break for a matcher; for a connect it is not safe enough, because the
 * card this picks is the card the next drive gets attributed to. Where the answer
 * carries no identity, [decide] therefore insists on exactly one claimant and fails
 * otherwise.
 */
object ConnectionCore {

    /** The line the vehicle page shows when there is no card to reach. */
    const val NO_VEHICLE_SELECTED = "No vehicle selected"

    /**
     * The line for a card that has never been paired. It names the way out, because the
     * app will not start a scan on this card's behalf: a scan is how a *new* unit is
     * found, and a card the user made by hand has no unit to find yet.
     */
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

    /**
     * [tried] is every address already linked to in this attempt (including the one
     * [outcome] just reported), [scanDone] says whether the radio sweep has already run
     * in this attempt, [found] is what that sweep returned, [outcome] is the answer to
     * the step that was just taken or null, and [nowMs] is supplied rather than read so
     * the record a step produces is testable.
     *
     * [scanDone] is a flag rather than something inferred from [found] being empty,
     * because "the sweep ran and found nothing" and "no sweep yet" look identical in the
     * list and the second one is the only case where scanning again would ever help.
     */
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

    /**
     * What clearing a card's pairing costs the phone.
     *
     * [hadPairing] is whether the card held one at all (a card that never had a pairing
     * leaves nothing behind), and [adapterBonded] is whether Android still holds a bond
     * for that address. The app cannot drop Android's side of a bond — `removeBond` is a
     * hidden API on the versions this app runs on — so the honest answer is that one half
     * is cleared here and the other half is the phone's to clear, in its own settings.
     *
     * The note is only worth showing when there is a bond left behind: a forget that
     * leaves nothing is finished, and a warning about a bond that does not exist teaches
     * the user to distrust the page.
     */
    data class ForgetDecision(val needsSystemUnpair: Boolean, val note: String?)

    /** The sentence the vehicle page shows when the phone still holds the bond. */
    const val STILL_BONDED =
        "The card no longer holds a pairing, but this phone is still bonded to that unit. " +
            "Remove it in the phone's Bluetooth settings if this phone should stop " +
            "answering it."

    fun forgetUnit(hadPairing: Boolean, adapterBonded: Boolean): ForgetDecision =
        if (hadPairing && adapterBonded) ForgetDecision(true, STILL_BONDED) else ForgetDecision(false, null)

    /**
     * True when this card's pairing has already been dialed in this attempt. The card,
     * not the address, is the unit of retry: a card holds exactly one pairing, so
     * "tried" means "we already reached for that unit once".
     */
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
        // A selection that is gone (deleted from under the attempt) is the same
        // nothing-to-reach answer as never having one; there is no card to dial.
        if (selectedId != null && card == null) return CoreResult(NextAction.Idle(NO_VEHICLE_SELECTED))

        // 1. A unit the user tapped in the sweep list is dialed before anything else.
        //    Every other rule here is a guess about which dashboard was meant - the card's
        //    pairing says "something this phone met once", the sweep order says "whoever is
        //    loudest" - and a tap on a name is not a guess. A pinned unit is not tied to a
        //    card yet, which is why the step carries no `vehicleId`: the identity rules get
        //    to say which card (or none) the unit that answers turns out to be.
        if (pinned != null && pinned.address !in tried) {
            return CoreResult(NextAction.Pair(null, pinned.address, pinned.name))
        }

        // 2. A card that has a pairing gets dialed before anything is swept. This is the
        //    fast path and the one the user expects: their own unit, no radio hunt.
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
                // No sweep has run yet in this attempt: the card's own pairing (if any)
                // has been dialed, and the radio is the only way left to meet a unit
                // this phone has never bonded with.
                !scanDone -> CoreResult(NextAction.Scan)

                nextFound != null -> CoreResult(
                    NextAction.Pair(null, nextFound.address, nextFound.name),
                )

                else -> CoreResult(
                    // The card's pairing failed (or did not exist) and the sweep found
                    // nothing worth dialing, or found only units already dialed.
                    NextAction.Failed(
                        if (outcome is LinkOutcome.Fail && card != null) {
                            "${card.name.ifEmpty { card.id }} $NO_ANSWER $NOTHING_FOUND"
                        } else NOTHING_FOUND,
                    ),
                )
            }
        }

        // 4. A silent or user-retry attempt stops at the card's own pairing. A sweep
        //    needs a permission prompt and the phone listening for other people's
        //    dashboards — neither belongs at launch, or behind a reconnect timer.
        return when {
            card == null -> CoreResult(NextAction.Idle(NO_VEHICLE_SELECTED))
            !card.hasPairing -> CoreResult(
                NextAction.Idle("${card.name.ifEmpty { card.id }} $NEVER_PAIRING"),
            )
            // The pairing on file was dialed and nobody came up. That is a failure, not
            // a wait: the vehicle page shows it under that card, and [ReconnectLadder]
            // is what decides whether it is retried at all.
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

        // No identity in the answer: the pairing is the whole claim, so it has to be
        // unambiguous on its own. See the class note for why this does not go through
        // VehicleMatch.resolve's first-claimant tie-break.
        if (mac == null) {
            val owners = vehicles.filter { it.bleAddress != null && it.bleAddress == answer.address }
            return when (owners.size) {
                1 -> toConnect(owners.first(), answer, selectedId, vehicles, nowMs)
                0 -> CoreResult(NextAction.Failed(UNCLAIMED))
                else -> CoreResult(NextAction.Failed(AMBIGUOUS))
            }
        }

        return when (val decision = VehicleMatch.resolve(vehicles, answer.address, mac)) {
            // Rule 1 — a card claims this MAC. Rule 2 is unreachable here (it only
            // fires when no MAC was reported).
            is MatchDecision.Bound -> toConnect(decision.vehicle, answer, selectedId, vehicles, nowMs)

            // Rule 3 — an unbound card holds this pairing and the unit that came up
            // reports a MAC nobody owns: bind them, name and photo intact.
            is MatchDecision.BindMac -> toConnect(decision.vehicle, answer, selectedId, vehicles, nowMs)

            // Nobody owns this unit. It is never written into the card that happened to
            // be selected — that is how two cards end up claiming one dashboard.
            is MatchDecision.Unknown -> CoreResult(
                next = if (mode == ConnectMode.SILENT) {
                    // Found unattended: the vehicle page shows the offer when it is
                    // opened; there is no attempt to have failed.
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
        // Rule 5 — the pairing that came up becomes the card's address to dial next
        // time, the identity is adopted when one was reported and kept when it was not,
        // and the firmware version rides along because the link-up read already fetched
        // it and the trips page needs it for the trip store.
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

    /**
     * One line for the page when the connection belongs to a card other than the one
     * the user had selected (plan §4: "the selection moves to that card and the page
     * says so in one line"). It is not a failure and it is not silent: the page is
     * about to show a different vehicle than the card that was pressed.
     */
    private fun noticeFor(card: Vehicle, selectedId: String?, others: List<Vehicle>): String? {
        if (selectedId == null || selectedId == card.id) return null
        val pressed = others.firstOrNull { it.id == selectedId }
        val pressedName = pressed?.name?.ifEmpty { pressed.id } ?: selectedId
        return "The dashboard that answered belongs to ${card.name.ifEmpty { card.id}}, " +
            "not $pressedName."
    }
}
