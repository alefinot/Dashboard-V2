package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.service.BootReceiver
import com.alefinot.dashboardpp.vehicles.UnitIdentity
import com.alefinot.dashboardpp.vehicles.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which dashboard the always-on lane follows (background link plan §2), and whether a boot may
 * ask for it at all (§4).
 *
 * This is the decision the whole feature turns on, and it is the one a bench cannot re-run on
 * demand: the ignition comes on at 06:40, the phone is in another room, and the only thing that
 * happened the night before is that two dashboards were paired and one of them was disconnected
 * by hand. `TripLogService` cannot be built in a JVM test, so the rule lives in
 * [BackgroundTarget] precisely so that it can be pinned here.
 *
 * The owner's words for the first rule (2026-11-26): *"the target is the last connected vehicle,
 * not the selected card."* Selection is a finger on the vehicle page; the link answers a
 * different question, and a car that answered at 07:00 this morning must not lose to a card that
 * was tapped once when it was being set up.
 */
class WhichCarDoesTheBackgroundChaseTest {

    private fun car(
        id: String,
        ble: String? = "3F:38:00:00:00:$id",
        mac: String? = null,
        lastConnectedAtMs: Long? = null,
    ) = Vehicle(
        id = id,
        name = id,
        bleAddress = ble,
        mac = mac,
        lastConnectedAtMs = lastConnectedAtMs,
        createdAtMs = 1_000L,
    )

    /** The unit key a pause would be written under, the same helper the latch uses. */
    private fun key(vehicle: Vehicle) = UnitIdentity.key(vehicle.mac, vehicle.bleAddress)

    @Test
    fun `the card that answered last wins, whatever is selected`() {
        val van = car("van", lastConnectedAtMs = 1_650_000_000_000L)
        val carLast = car("car", lastConnectedAtMs = 1_700_000_000_000L)
        val bike = car("bike", lastConnectedAtMs = 1_600_000_000_000L)

        // "van" is the card the vehicle page is standing on. It is not the answer.
        assertEquals("car", BackgroundTarget.pick(listOf(van, carLast, bike), "van", emptySet())?.id)
    }

    @Test
    fun `the newest connect wins whatever order the garage is in`() {
        // The garage is sorted by selection first (VehicleStore.sorted), so the target is
        // regularly not the head of the list the caller passes in.
        val old = car("old", lastConnectedAtMs = 1_000L)
        val newest = car("newest", lastConnectedAtMs = 2_000L)

        assertEquals("newest", BackgroundTarget.pick(listOf(old, newest), "old", emptySet())?.id)
        assertEquals("newest", BackgroundTarget.pick(listOf(newest, old), "old", emptySet())?.id)
    }

    @Test
    fun `a card with no connect history is not the target when one has history`() {
        // A freshly paired card has never answered, and the phone is only guessing that it is
        // a dashboard at all. It must not pull the lane off the unit that drove yesterday.
        val fresh = car("fresh")
        val driven = car("driven", lastConnectedAtMs = 1_700_000_000_000L)

        assertEquals("driven", BackgroundTarget.pick(listOf(fresh, driven), "fresh", emptySet())?.id)
    }

    @Test
    fun `nothing has connected yet, so the selected card stands in`() {
        // Day one: two cards, no history. Selection is the only opinion the app has, and it is
        // the owner's - they paired this one last, and they are the one holding the phone.
        val a = car("a")
        val b = car("b")

        assertEquals("b", BackgroundTarget.pick(listOf(a, b), "b", emptySet())?.id)
    }

    @Test
    fun `with no history and no selection the first paired card is the target`() {
        // A brand-new store can have no selection pointer at all. Answering null here would mean
        // an owner who paired a dashboard and closed the app never gets a link back.
        val a = car("a")
        val b = car("b")

        assertEquals("a", BackgroundTarget.pick(listOf(a, b), null, emptySet())?.id)
        assertEquals("a", BackgroundTarget.pick(listOf(a, b), "gone", emptySet())?.id)
    }

    @Test
    fun `an unpaired card is never the target`() {
        // A card with no Bluetooth address is a name typed into a form. There is nothing to dial,
        // and a lane that "chased" it would announce itself in the shade and reach nothing.
        val draft = car("draft", ble = "  ")
        val driven = car("driven", lastConnectedAtMs = 1L)

        assertEquals("driven", BackgroundTarget.pick(listOf(draft, driven), "draft", emptySet())?.id)
        assertNull(BackgroundTarget.pick(listOf(draft, car("other", ble = null)), null, emptySet()))
    }

    @Test
    fun `an empty garage has no target`() {
        assertNull(BackgroundTarget.pick(emptyList(), null, emptySet()))
    }

    @Test
    fun `a hand-disconnected car stops the chase instead of moving it on`() {
        // The owner pressed DISCONNECT on this unit. "Not this one" is not a request to talk to a
        // different car behind their back, so the answer is null and the service goes down - the
        // next CONNECT is what lifts it, including across a reboot.
        val paused = car("car", lastConnectedAtMs = 1_700_000_000_000L)
        val other = car("van", lastConnectedAtMs = 1_600_000_000_000L)

        assertNull(
            BackgroundTarget.pick(listOf(paused, other), null, setOf(key(paused))),
        )
    }

    @Test
    fun `a pause on a card the rule did not land on changes nothing`() {
        // The latch is per unit, not a global "stop": a truck that was disconnected on Friday
        // does not cancel the car that answers every morning.
        val paused = car("truck", lastConnectedAtMs = 1_600_000_000_000L)
        val daily = car("car", lastConnectedAtMs = 1_700_000_000_000L)

        assertEquals(
            "car",
            BackgroundTarget.pick(listOf(paused, daily), null, setOf(key(paused)))?.id,
        )
    }

    @Test
    fun `a pause survives a re-pairing of the same unit`() {
        // The latch is keyed by the unit's identity, and the identity prefers the burnt-in MAC:
        // re-pairing changes the Bluetooth address, so a pause written under the old pairing still
        // names this dashboard. Answering the other way would mean a re-pairing is how an owner
        // gets the app calling their car again after they said it may not.
        val before = car("car", ble = "3F:38:00:11:11:11", mac = "A4:CF:12:3B:9E:07", lastConnectedAtMs = 2_000L)
        val after = before.copy(bleAddress = "3F:38:00:22:22:22")

        assertTrue(BackgroundTarget.isPaused(after, setOf(key(before))))
        assertNull(BackgroundTarget.pick(listOf(after), null, setOf(key(before))))
    }

    @Test
    fun `a pause does not follow a car to a different unit`() {
        // The same person, a different dashboard: without a MAC the two pairings are the only
        // evidence, and they are different evidence, so the second unit is fair game.
        val paused = car("car", ble = "3F:38:00:11:11:11", lastConnectedAtMs = 2_000L)
        val stranger = car("van", ble = "3F:38:00:22:22:22", lastConnectedAtMs = 3_000L)

        assertFalse(BackgroundTarget.isPaused(stranger, setOf(key(paused))))
        assertEquals("van", BackgroundTarget.pick(listOf(paused, stranger), null, setOf(key(paused)))?.id)
    }

    @Test
    fun `a boot starts the lane only when all three answers agree`() {
        val target = car("car", lastConnectedAtMs = 1_700_000_000_000L)

        // The switch is off: the owner said no, and a reboot is not a licence to change their mind.
        assertFalse(BootReceiver.BootDecision.allowed(enabled = false, target = target, bluetoothGranted = true))
        // Nothing to chase, including the hand-disconnected case, which is what makes the latch
        // survive a reboot: without this, the boot path is how a paused unit gets dialled anyway.
        assertFalse(BootReceiver.BootDecision.allowed(enabled = true, target = null, bluetoothGranted = true))
        // Android 12+ would refuse the foreground start and MIUI's autostart switch would have
        // killed the broadcast first, but a receiver that dials without the grant is a crash with
        // no window to explain it.
        assertFalse(BootReceiver.BootDecision.allowed(enabled = true, target = target, bluetoothGranted = false))

        assertTrue(BootReceiver.BootDecision.allowed(enabled = true, target = target, bluetoothGranted = true))
    }
}
