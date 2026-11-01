package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which garage card is the dashboard that just answered.
 *
 * The garage card is the user's, so getting this wrong is not a data bug - it renames
 * someone's car. The firmware reports its eFuse MAC, and that is the only evidence that
 * survives everything: an address, of any kind, is a lease. A DHCP address moved the
 * moment the car moved; a Bluetooth address does not move, but it belongs to the *phone's
 * link* rather than to the unit, so a second phone holds a different one for the same
 * dashboard and a re-pairing changes it. The rules below therefore rank the evidence (MAC
 * first, pairing only as a fallback, and never as a licence to touch a card that already
 * has an identity), and they are here as a pure function so the argument happens in this
 * file instead of inside a composable on a tablet in a car.
 */
class VehicleMatchTest {

    private val macA = "A4:CF:12:3B:9E:07"
    private val macB = "B0:97:3A:1C:DD:2F"

    // Bluetooth addresses, as the stack reports them: what the app dialed, and what it
    // gets back. They are written in the form Android's `BluetoothDevice.getAddress()`
    // returns - upper case, colon-separated - because the comparison is exact and this
    // text is what travels through the code.
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
        // What the firmware prints (task 1), and what it prints after a reboot:
        // identical, uppercase, colon-separated.
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("A4:CF:12:3B:9E:07"))
        // Lower-case arrives from a human typing the label off the back of the unit.
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("a4:cf:12:3b:9e:07"))
        // Padded by a JSON field or a paste buffer.
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("  A4:CF:12:3B:9E:07  "))
        // An *internal* space is repaired too, and that is a decision rather than
        // an accident: normalizeMac strips every whitespace character, so a value
        // mangled by a line-wrapped backup file or a hand-edited record still
        // resolves. Nothing else here pins that half of the leniency, and without
        // this line a later task could tighten it to trim() and silently un-bind
        // every card that happened to store such a value.
        assertEquals("A4:CF:12:3B:9E:07", VehicleMatch.normalizeMac("A4:CF:12:3B: 9E:07"))

        // Everything else means "no identity", not "an odd-looking one": an old
        // firmware that has no `mac` field at all (null / ""), a value the ESP
        // would never print, and half a MAC. Keeping the shape strict is what
        // makes the stored string canonical - the codec round-trips exactly this
        // text, so a lenient reader would let "A4CF123B9E07" into a record and then
        // never match it again.
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

        // Note what is *not* checked here: nothing about this function knows about
        // Bluetooth. A MAC and a pairing address have the same shape, so the shape can
        // never be the discriminator - which is exactly why the two are separate fields
        // and why the identity key says which half it was built from.
    }

    @Test
    fun `a reported identity outranks the card that happens to hold the pairing`() {
        // Rule 1. The user pressed the card that remembers this pairing; the unit
        // that answered is somebody else's - a second phone paired the other dashboard,
        // or a re-pairing left a stale address on a card. The MAC has to win or the wrong
        // card turns CONNECTED and the trip gets tagged to the wrong vehicle.
        val pressed = card("pairing-only", ble = bleA)
        val owner = card("bound-to-mac", mac = macA, ble = bleB)

        assertEquals(
            MatchDecision.Bound(owner),
            VehicleMatch.resolve(listOf(pressed, owner), bleA, macA),
        )
    }

    @Test
    fun `two phones pairing the same unit reach the same card`() {
        // The one property the whole transport swap hangs on: a Bluetooth address names
        // this phone's link, so the address the app stores is not comparable across
        // phones and changes when the pairing is redone. The unit's own MAC is what makes
        // it the same vehicle - so whichever address the link happened to come up on,
        // rule 1 answers with the same card.
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
        // Rule 1 again, from the other direction: the codec round-trips the
        // record's `mac` text verbatim (it is not the store's job to sanitize),
        // so a value written by hand, by an older build, or typed by the user in
        // the editor reaches `claimedMac` in whatever case it was stored in. The
        // ESP always prints upper case, so if the comparison were case-sensitive
        // the card would look unbound, a second card would be offered the same
        // unit, and rule 4 would never see the collision.
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
        // Rule 2, the fallback that keeps firmware before task 1 usable: the pairing is
        // the only evidence there is, and the card has no identity to betray.
        val garage = card("pairing-only", ble = bleA)

        assertEquals(
            MatchDecision.Bound(garage),
            VehicleMatch.resolve(listOf(garage), bleA, null),
        )
    }

    @Test
    fun `an unbound card may not borrow a pairing a MAC-bound card already claims`() {
        // Rule 2's negative half, and the reason the rule is worth testing: two cards
        // remember the same pairing. One of them has an identity, so that pairing is
        // *its* link - a unit answering there without a MAC (old firmware, or a MAC the
        // eFuse CRC rejected) is more likely that card's dashboard running older code
        // than a licence to move the spare card onto it. Unknown is honest;
        // Bound(spare) would silently mislabel.
        val claimed = card("bound-to-mac", mac = macA, ble = bleA)
        val spare = card("pairing-only", ble = bleA)

        assertEquals(
            MatchDecision.Unknown(address = bleA, mac = null),
            VehicleMatch.resolve(listOf(claimed, spare), bleA, null),
        )
    }

    @Test
    fun `an answer that names no pairing matches no card that has no pairing`() {
        // The null half of rule 2. A hand-made card (imported, adopted from a screen
        // that never linked, made in the editor) holds no address at all, and an answer
        // that arrived with no pairing to point at names no address either. Neither is
        // evidence, and matching the two of them would bind a card to a unit on the
        // strength of nothing.
        val handmade = card("hand-made", mac = null, ble = null)

        assertEquals(
            MatchDecision.Unknown(address = "", mac = null),
            VehicleMatch.resolve(listOf(handmade), null, null),
        )
    }

    @Test
    fun `a paired card that answers with an unclaimed identity takes it`() {
        // Rule 3: the one-time upgrade. The card was made before the firmware knew it
        // had a MAC; from now on it is bound, name and photo intact. The MAC in the
        // decision is normalised, because that is what gets written to the record.
        val pressed = card("pairing-only", ble = bleA)

        assertEquals(
            MatchDecision.BindMac(pressed, macA),
            VehicleMatch.resolve(listOf(pressed), bleA, "a4:cf:12:3b:9e:07"),
        )
    }

    @Test
    fun `an identity and a pairing nobody claims is a new unit, offered for adoption`() {
        // Rule 3's limit: a MAC nobody holds must never be glued to a card just
        // because that card exists. The vehicles page shows "a dashboard nobody knows"
        // and asks the user to name it.
        val other = card("bound-to-mac", mac = macA, ble = bleB)

        assertEquals(
            MatchDecision.Unknown(address = bleC, mac = macB),
            VehicleMatch.resolve(listOf(other), bleC, macB),
        )

        // An empty garage answers the same way - the first dashboard the app ever
        // meets is adopted, not auto-created.
        assertEquals(
            MatchDecision.Unknown(address = bleC, mac = macB),
            VehicleMatch.resolve(emptyList(), bleC, macB),
        )
    }

    @Test
    fun `a unit with no identity on a pairing nobody remembers is not offered for adoption`() {
        // The other half of the same limit, and the reason `Unknown` carries a nullable
        // MAC: with no MAC there is no identity to hand to a new card, so the vehicles
        // page must not offer to make one. This is the state a pre-`mac` firmware reaches
        // when it answers on a pairing the app has never stored - the card can only come
        // from the pairing rule, and that rule needs a card that already holds it.
        assertEquals(
            MatchDecision.Unknown(address = bleC, mac = null),
            VehicleMatch.resolve(emptyList(), bleC, null),
        )
    }

    @Test
    fun `the same identity can never be claimed by two cards`() {
        // Rule 4: the editor's refusal. It has to point at the card that already
        // has the MAC, and it has to allow the card that owns it to keep it while
        // being edited - otherwise saving an existing card would fail its own
        // uniqueness check.
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
        // Rule 5. The card holds one pairing because the app can hold one link at a
        // time, and a cold start dials that one address - so the link that just worked is
        // the only one worth remembering, and replacing it is right for the same reason a
        // list of network addresses was wrong: a pairing is not the unit.
        val garage = card("hatch", mac = macA, ble = bleA)

        val updated = VehicleMatch.bindConnect(garage, bleB, macA, 1_700_000_000_000L)

        assertEquals(bleB, updated.bleAddress)
        assertEquals(1_700_000_000_000L, updated.lastConnectedAtMs)
        // The identity survives a connect that reports it, and nothing else about
        // the user's card moves.
        assertEquals(macA, updated.mac)
        assertEquals(garage.name, updated.name)
        assertEquals(garage.id, updated.id)
    }

    @Test
    fun `a re-pairing replaces the address and invents no identity from it`() {
        // The trap this transport swap has to avoid: a Bluetooth address looks like a
        // MAC, and the code that used to derive an identity from an address
        // (`02:` + a reversed IP) is only one careless refactor away. An address is a
        // link, so rule 5 may replace it and may never manufacture an identity from it.
        val garage = card("pairing-only", ble = bleA)

        val updated = VehicleMatch.bindConnect(garage, bleB, null, 1_700_000_000_000L)

        assertEquals(bleB, updated.bleAddress)
        assertNull(updated.mac)
    }

    @Test
    fun `a connect that reports no identity keeps the identity the card already had`() {
        // Old firmware, or a unit answering on a pairing other than the one the card
        // remembered: dropping the MAC here would un-bind a card that had earned it.
        val garage = card("hatch", mac = macA, ble = bleA)

        val updated = VehicleMatch.bindConnect(garage, bleB, null, 1_700_000_000_000L)

        assertEquals(macA, updated.mac)
        assertEquals(bleB, updated.bleAddress)
    }

    @Test
    fun `an all-zero identity is just another string, and still cannot be claimed twice`() {
        // Carried from task 1's review: ESP.getEfuseMac() discards the IDF return
        // code, so a unit whose MAC eFuse CRC is corrupt reports a plausible
        // looking value - commonly 00:00:00:00:00:00 - and two such units would
        // report the same one. The firmware fix is out of this plan's scope; the
        // app side has to be honest about it instead of pretending the value is
        // unique. It is not filtered (a real device could legally be all-zero on
        // some board revision, and silently treating it as "no MAC" would throw
        // away the only identity two such units can be told apart by *nothing*),
        // so what protects the garage is rule 4: the second unit is refused, and
        // resolve answers deterministically for the card that got there first.
        val allZero = "00:00:00:00:00:00"
        assertEquals(allZero, VehicleMatch.normalizeMac(allZero))

        val first = card("hatch", mac = allZero)
        val second = card("bike", mac = macB)
        assertEquals(first, VehicleMatch.macTakenBy(listOf(first, second), allZero, second.id))

        // Deterministic, not a coin flip: the store hands `resolve` its display
        // order, and the first claimant wins. Both units answering with the same
        // bogus MAC therefore land on one card every time, never alternating.
        assertEquals(
            MatchDecision.Bound(first),
            VehicleMatch.resolve(listOf(first, second), bleA, allZero),
        )
    }

    @Test
    fun `a card holding a value that is not an identity counts as unbound, not as locked`() {
        // A record whose `mac` text is unusable (an empty field from a codec, a
        // hand-edited file) must not become a card that can never match anything:
        // it is still matchable by pairing, still upgradable, and it must not hold
        // the pairing it remembers hostage from a card that has no identity at all.
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
