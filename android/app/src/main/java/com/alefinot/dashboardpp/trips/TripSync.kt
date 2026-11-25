package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot

/**
 * The dashboard's trip counter as a trip-boundary signal (App Settings → SYNC TRIP WITH
 * DASHBOARD, 2026-11-25).
 *
 * The unit accumulates `trp_km` and only ever sets it back to zero on a manual reset - the
 * GPIO25 hold, `trip.reset` from the app's RESET TRIP, or a factory reset. Between resets it
 * is monotonic, so the first frame that reads lower than the previous one is that reset. A
 * reboot also zeroes it (the trip lives in RAM), but the recorder's reboot detector owns that
 * sample and must win: a reboot is one new drive, not a reboot plus a manual reset.
 *
 * Kept as a pure object because the interesting part is the rule, not the link: the recorder
 * can be handed two [Snapshot]s on the JVM and the answer argued about without a unit.
 */
object EspTripSync {

    /**
     * The smallest drop that counts as a reset, in kilometres.
     *
     * `trp_km` is printed with two decimals, so a real reset moves the value by at least
     * 0.01 km unless the counter was already zero. The epsilon sits below that and above
     * floating-point noise, which means a two-decimal step is a reset and a re-sent frame is
     * not.
     */
    const val RESET_EPSILON_KM = 0.005

    /** The seal reason a sync split writes, so the log and the tests can name it. */
    const val RESET_SEAL_REASON = "esp-trip-reset"

    /** True when [next] reports a lower dashboard trip than [prev] - a manual reset. */
    fun isReset(prev: Snapshot, next: Snapshot): Boolean =
        next.trpKm + RESET_EPSILON_KM < prev.trpKm
}
