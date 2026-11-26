package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.vehicles.UnitIdentity
import com.alefinot.dashboardpp.vehicles.Vehicle

/**
 * Which card the background link should chase.
 *
 * The owner's rule (2026-11-26): the **last connected vehicle**, not the card that
 * happens to be selected. Selection is a thing a finger does on the vehicle page;
 * the background link answers "which dashboard was this phone talking to last", and
 * that is the one whose ignition most likely just came on. A card the owner
 * disconnected by hand is skipped until they press CONNECT again, even across a
 * reboot - see [com.alefinot.dashboardpp.trips.RecorderSettings.pausedUnits].
 *
 * A paused **target** stops the service rather than handing it to the next card: the
 * owner said "not this unit", not "some other unit instead". That is why [pick]
 * answers null for it instead of filtering it out and choosing again.
 *
 * Pure and JVM-testable on purpose: the service cannot be built in a unit test, and
 * "which car does the app chase at 06:40" is exactly the decision worth pinning.
 */
object BackgroundTarget {

    /**
     * The paired card with the newest [Vehicle.lastConnectedAtMs], falling back to the
     * selected card when no card has ever connected, then to the first paired card.
     *
     * Null when nothing is paired, or when the card this rule lands on is paused - the
     * caller stops the service rather than guessing at a different car.
     */
    fun pick(
        vehicles: List<Vehicle>,
        selectedId: String?,
        pausedKeys: Set<String>,
    ): Vehicle? {
        val paired = vehicles.filter { it.hasPairing }
        if (paired.isEmpty()) return null
        val target = paired
            .filter { (it.lastConnectedAtMs ?: 0L) > 0L }
            .maxByOrNull { it.lastConnectedAtMs ?: 0L }
            ?: paired.firstOrNull { it.id == selectedId }
            ?: paired.first()
        return target.takeUnless { isPaused(it, pausedKeys) }
    }

    /** True when [vehicle]'s unit key is in the manual-disconnect latch. */
    fun isPaused(vehicle: Vehicle, pausedKeys: Set<String>): Boolean =
        UnitIdentity.key(vehicle.mac, vehicle.bleAddress) in pausedKeys
}
