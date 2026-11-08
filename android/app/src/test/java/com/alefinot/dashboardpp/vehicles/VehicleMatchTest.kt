package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleMatchTest {

    private val macA = "A4:CF:12:3B:9E:07"
    private val macB = "B0:97:3A:1C:DD:2F"

    private val bleA = "3F:38:00:11:22:33"
    private val bleB = "AA:BB:CC:DD:EE:01"
    private val bleC = "02:00:00:00:00:01"

    private fun card(
        id: String,
        mac: String? = null,
        ble: String? = null,
    ) = Vehicle(id = id, mac = mac, name = id, bleAddress = ble)

    @Test
    fun `an identity is read case- and space-insensitively but must be six hex pairs`() {
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("A4:CF:12:3B:9E:07"))
        // Lower-case arrives from a human typing the label off the back of the unit.
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("a4:cf:12:3b:9e:07"))
        // Padded by a JSON field or a paste buffer.
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("  A4:CF:12:3B:9E:07  "))
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("A4:CF:12:3B: 9E:07"))

        assertNull(VehicleMatch.normalizeMac(null))
        assertNull(VehicleMatch.normalizeMac(""))
        assertNull(VehicleMatch.normalizeMac("   "))
        assertNull(VehicleMatch.normalizeMac("A4CF123B9E07"))
        assertNull(VehicleMatch.normalizeMac("A4-CF-12-3B-9E-07"))
        assertNull(VehicleMatch.normalizeMac("A4:CF:12:3B:9E"))
        assertNull(VehicleMatch.normalizeMac("A4:CF:12:3B:9E:07:11"))
        assertNull(VehicleMatch.normalizeMac("A4:CF:12:3B:9E:0"))
        assertNull(VehicleMatch.normalizeMac("ZZ:CF:12:3B:9E:07"))
        assertNull(VehicleMatch.normalizeMac("mac"))

    }

    @Test
    fun `a reported identity outranks the card that happens to hold the pairing`() {
        val pressed = card("pairing-only", ble = bleA)
        val owner = card("bound-to-mac", mac = macA, ble = bleB)

        assertEquals(
            MatchDecision.Bound(owner),
            VehicleMatch.resolve(listOf(pressed, owner), bleA, macA),
        )
    }

    @Test
    fun `two phones pairing the same unit reach the same card`() {
        val owner = card("hatch", mac = macA, ble = bleA)

        assertEquals(
            MatchDecision.Bound(owner),
            VehicleMatch.resolve(listOf(owner), bleB, macA),
        )
        // Including an answer that arrived with no pairing to point at.
        assertEquals(
            MatchDecision.Bound(owner),
            VehicleMatch.resolve(listOf(owner), null, macA),
        )
    }

    @Test
    fun `a card that stored its identity in lower case still owns that unit`() {
        val stored = card("hatch", mac = "a4:cf:12:3b:9e:07", ble = bleB)

        assertEquals(
            MatchDecision.Bound(stored),
            VehicleMatch.resolve(listOf(stored), bleA, macA),
        )

        // The same card is the one rule 4 reports, for the same reason.
        assertEquals(
            stored,
            VehicleMatch.macTakenBy(listOf(stored, card("bike", mac = macB)), macA, null),
        )
    }

    @Test
    fun `firmware with no identity matches the unbound card that holds that pairing`() {
        val garage = card("pairing-only", ble = bleA)

        assertEquals(
            MatchDecision.Bound(garage),
            VehicleMatch.resolve(listOf(garage), bleA, null),
        )
    }

    @Test
    fun `an unbound card may not borrow a pairing a MAC-bound card already claims`() {
        val claimed = card("bound-to-mac", mac = macA, ble = bleA)
        val spare = card("pairing-only", ble = bleA)

        assertEquals(
            MatchDecision.Unknown(address = bleA, mac = null),
            VehicleMatch.resolve(listOf(claimed, spare), bleA, null),
        )
    }

    @Test
    fun `an answer that names no pairing matches no card that has no pairing`() {
        val handmade = card("hand-made", mac = null, ble = null)

        assertEquals(
            MatchDecision.Unknown(address = "", mac = null),
            VehicleMatch.resolve(listOf(handmade), null, null),
        )
    }

    @Test
    fun `a paired card that answers with an unclaimed identity takes it`() {
        val pressed = card("pairing-only", ble = bleA)

        assertEquals(
            MatchDecision.BindMac(pressed, macA),
            VehicleMatch.resolve(listOf(pressed), bleA, "a4:cf:12:3b:9e:07"),
        )
    }

    @Test
    fun `an identity and a pairing nobody claims is a new unit, offered for adoption`() {
        val other = card("bound-to-mac", mac = macA, ble = bleB)

        assertEquals(
            MatchDecision.Unknown(address = bleC, mac = macB),
            VehicleMatch.resolve(listOf(other), bleC, macB),
        )

        assertEquals(
            MatchDecision.Unknown(address = bleC, mac = macB),
            VehicleMatch.resolve(emptyList(), bleC, macB),
        )
    }

    @Test
    fun `a unit with no identity on a pairing nobody remembers is not offered for adoption`() {
        assertEquals(
            MatchDecision.Unknown(address = bleC, mac = null),
            VehicleMatch.resolve(emptyList(), bleC, null),
        )
    }

    @Test
    fun `the same identity can never be claimed by two cards`() {
        val owner = card("hatch", mac = macA)
        val claimer = card("bike", mac = macB)

        assertEquals(owner, VehicleMatch.macTakenBy(listOf(owner, claimer), macA, null))
        assertEquals(
            owner,
            VehicleMatch.macTakenBy(listOf(owner, claimer), "a4:cf:12:3b:9e:07", claimer.id),
        )
        // Editing the owner itself is not a collision with itself.
        assertNull(VehicleMatch.macTakenBy(listOf(owner, claimer), macA, owner.id))
        // Nothing to check for an absent or malformed MAC, and a free MAC is free.
        assertNull(VehicleMatch.macTakenBy(listOf(owner, claimer), null, null))
        assertNull(VehicleMatch.macTakenBy(listOf(owner, claimer), "not a mac", null))
        assertNull(VehicleMatch.macTakenBy(listOf(owner, claimer), "FF:FF:FF:FF:FF:FF", null))
    }

    @Test
    fun `a connect stores the pairing that answered as the address to dial next`() {
        val garage = card("hatch", mac = macA, ble = bleA)

        val updated = VehicleMatch.bindConnect(garage, bleB, macA, 1_700_000_000_000L)

        assertEquals(bleB, updated.bleAddress)
        assertEquals(1_700_000_000_000L, updated.lastConnectedAtMs)
        assertEquals(macA, updated.mac)
        assertEquals(garage.name, updated.name)
        assertEquals(garage.id, updated.id)
    }

    @Test
    fun `a re-pairing replaces the address and invents no identity from it`() {
        val garage = card("pairing-only", ble = bleA)

        val updated = VehicleMatch.bindConnect(garage, bleB, null, 1_700_000_000_000L)

        assertEquals(bleB, updated.bleAddress)
        assertNull(updated.mac)
    }

    @Test
    fun `a connect that reports no identity keeps the identity the card already had`() {
        val garage = card("hatch", mac = macA, ble = bleA)

        val updated = VehicleMatch.bindConnect(garage, bleB, null, 1_700_000_000_000L)

        assertEquals(macA, updated.mac)
        assertEquals(bleB, updated.bleAddress)
    }

    @Test
    fun `an all-zero identity is just another string, and still cannot be claimed twice`() {
        val allZero = "00:00:00:00:00:00"
        assertEquals(allZero, VehicleMatch.normalizeMac(allZero))

        val first = card("hatch", mac = allZero)
        val second = card("bike", mac = macB)
        assertEquals(first, VehicleMatch.macTakenBy(listOf(first, second), allZero, second.id))

        assertEquals(
            MatchDecision.Bound(first),
            VehicleMatch.resolve(listOf(first, second), bleA, allZero),
        )
    }

    @Test
    fun `a card holding a value that is not an identity counts as unbound, not as locked`() {
        val corrupt = card("half-written", mac = "", ble = bleA)

        assertEquals(
            MatchDecision.Bound(corrupt),
            VehicleMatch.resolve(listOf(corrupt), bleA, null),
        )
        // And the next connect repairs it.
        assertEquals(
            MatchDecision.BindMac(corrupt, macA),
            VehicleMatch.resolve(listOf(corrupt), bleA, macA),
        )
        assertNull(VehicleMatch.macTakenBy(listOf(corrupt), macA, null))

        // A connect that reports nothing leaves nothing junk behind.
        val written = VehicleMatch.bindConnect(corrupt, bleB, null, 1_700_000_000_000L)
        assertNull(written.mac)
    }
}
