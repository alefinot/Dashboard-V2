package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection ladder, decided one step at a time, with no network and no clock.
 *
 * The app used to hunt for *whatever Dashboard++ is out there* and take the first one
 * that answered. That is the design the garage replaces: since a card is bound to a
 * physical unit by its eFuse MAC, "connect" now means "reach *this* vehicle's
 * dashboard", and the answer to "which card is the unit that answered?" has to be
 * settled before anything is written down. Those two questions are the whole of this
 * file, and they are pure functions because getting them wrong renames someone's car:
 * it tags a drive to the wrong vehicle, marks CONNECTED on a card nobody pressed, and
 * binds two different dashboards into one record.
 *
 * [ConnectionCore.decide] answers one step. The view model is the loop, so every test
 * here says what the *next* step is, given what has already been dialed, whether the
 * radio has been swept, what the sweep found and what the last link answered — including
 * the two cases the old code could not express: a card with no unit bound (rule 3), and a
 * unit that answers without an identity at all (firmware predating the `mac` field).
 *
 * The shapes below changed when the transport did, and the old names are worth listing
 * because they explain what the new ones are not: there is no `Probe` (an address is not a
 * place to knock on), no `ScanThenProbe` (a sweep is a step of its own, and it is the
 * user's decision rather than a fallback the ladder reaches for by itself), and an
 * `Ok(...)` answer is a link that came up rather than a host that answered a request.
 */
class ConnectionCoreTest {

    private val now = 1_700_000_000_000L

    /** What a unit advertises and reports as its name. */
    private val UNIT = "Dashboard++"

    /** A BLE address this phone could hold as a pairing. */
    private val PAIR_A = "3C:71:0A:00:00:50"

    /** The line the shell shows when there is nothing to reach. */
    private val noSelection = ConnectionCore.NO_VEHICLE_SELECTED

    private fun card(
        id: String,
        mac: String? = null,
        ble: String? = null,
        name: String = id.uppercase(),
    ) = Vehicle(
        id = id,
        mac = mac,
        name = name,
        type = VehicleType.CAR,
        bleAddress = ble,
        createdAtMs = now,
    )

    private fun decide(
        cards: List<Vehicle>,
        selectedId: String?,
        tried: List<String> = emptyList(),
        scanDone: Boolean = false,
        found: List<Found> = emptyList(),
        outcome: LinkOutcome? = null,
        mode: ConnectMode = ConnectMode.SEARCH,
        pinned: Found? = null,
    ) = ConnectionCore.decide(cards, selectedId, tried, scanDone, found, outcome, mode, now, pinned)

    /** A dashboard that came up on the link. [address] is the pairing, not a location. */
    private fun ok(
        address: String,
        mac: String?,
        version: String? = "1.4.6",
        name: String = UNIT,
        mtu: Int = 512,
    ) = LinkOutcome.Ok(null, address, name, version, mac, mtu)

    // ---------------------------------------------------------------- selection

    @Test
    fun `with no vehicle selected nothing is dialed`() {
        // This is the state the garage opens in on a fresh install: there is no card
        // to reach, so an unattended attempt must sit still rather than fall back to
        // hunting the network for anything that answers — which is the exact behaviour
        // the refactor exists to remove.
        for (mode in listOf(ConnectMode.SILENT, ConnectMode.PROBE)) {
            val r = decide(listOf(card("a", ble = "3C:71:0A:00:00:11")), null, mode = mode)
            assertTrue(r.next is NextAction.Idle)
            assertEquals(noSelection, (r.next as NextAction.Idle).reason)
            assertNull(r.persist)
            assertNull(r.adoption)
        }
    }

    @Test
    fun `a search with no card selected still sweeps`() {
        // SEARCH FOR DASHBOARD on the ADD VEHICLE card: there is no card yet, so the
        // sweep is the only door, and what it finds comes back as an offer to name.
        val r = decide(emptyList(), null, mode = ConnectMode.SEARCH)
        assertEquals(NextAction.Scan, r.next)
    }

    @Test
    fun `a selection that no longer exists is idle, not a crash`() {
        // A card deleted from under an in-flight attempt. The view model re-reads the
        // list every step, so the core has to answer this rather than assume its
        // caller kept the card alive.
        val r = decide(emptyList(), "gone")
        assertTrue(r.next is NextAction.Idle)
    }

    @Test
    fun `a card that has never been paired is idle on the unattended paths`() {
        // Distinct from "no selection": here the user pressed CONNECT on a card they
        // made in the editor, and the honest answer at launch is that the app holds no
        // pairing for it yet — not "no vehicle selected". And the line names the way
        // out, because nothing in the app will start a scan on this card's behalf.
        for (mode in listOf(ConnectMode.SILENT, ConnectMode.PROBE)) {
            val r = decide(listOf(card("a")), "a", mode = mode)
            assertTrue("$mode", r.next is NextAction.Idle)
            assertNotEqualsLine(r, noSelection)
        }
    }

    private fun assertNotEqualsLine(r: CoreResult, line: String) {
        val reason = (r.next as NextAction.Idle).reason
        assertNotNull(reason)
        assertTrue(reason != line)
    }

    // ------------------------------------------------------- the probe ladder

    @Test
    fun `the card's remembered pairing is dialed before anything else`() {
        // One address per card, and it is the one the last link came up on: there is no
        // list to walk and no order to argue about.
        val cards = listOf(card("a", ble = "3C:71:0A:00:00:50"))
        assertEquals(NextAction.Pair("a", PAIR_A, "A"), decide(cards, "a").next)
    }

    @Test
    fun `a ladder never redials a pairing that already failed in this attempt`() {
        // A connect timeout costs the user seconds of watching a ring, and with one
        // pairing per card, dialling it twice would be a ladder that loops rather than
        // progresses - so a spent pairing moves the attempt on to the sweep.
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", tried = listOf(PAIR_A),
            outcome = LinkOutcome.Fail(PAIR_A), mode = ConnectMode.SEARCH)
        assertEquals(NextAction.Scan, r.next)
    }

    @Test
    fun `silent mode stops at the pairing the card remembers`() {
        // Launch reconnect must not put a radio sweep and a permission prompt in front of
        // the first frame: the garage is usable while the dial runs, and a dashboard that
        // is switched off is not going to be found by sweeping the air for it.
        val cards = listOf(card("a", name = "Workshop Van", ble = PAIR_A))
        val r = decide(cards, "a", tried = listOf(PAIR_A),
            outcome = LinkOutcome.Fail(PAIR_A), mode = ConnectMode.SILENT)
        val failed = r.next as NextAction.Failed
        assertTrue(failed.reason.contains("Workshop Van"))
        assertTrue(failed.reason.isNotBlank())
    }

    @Test
    fun `an exhausted silent ladder still says which vehicle it failed to reach`() {
        // The line has to name the vehicle, because on the vehicle page it sits under
        // that card: "no Dashboard++ answered" on its own is a sentence about a network
        // the user is not asking about.
        val cards = listOf(card("a", name = "Workshop Van", ble = PAIR_A))
        val r = decide(cards, "a", tried = listOf(PAIR_A),
            outcome = LinkOutcome.Fail(PAIR_A), mode = ConnectMode.SILENT)
        assertTrue((r.next as NextAction.Failed).reason.contains("Workshop Van"))
    }

    @Test
    fun `search mode sweeps the radio once the pairing is spent`() {
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", tried = listOf(PAIR_A),
            outcome = LinkOutcome.Fail(PAIR_A), mode = ConnectMode.SEARCH)
        assertEquals(NextAction.Scan, r.next)
    }

    @Test
    fun `a search that already swept gives up instead of sweeping again`() {
        // Without this the view model would put the phone back on the radio every time
        // the sweep came back empty, which is a loop the user watches - and a sweep is a
        // permission, an on radio and fifteen seconds of attention, not a free retry.
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", tried = listOf(PAIR_A), scanDone = true,
            outcome = LinkOutcome.Fail("3C:71:0A:00:00:09"), mode = ConnectMode.SEARCH)
        assertTrue(r.next is NextAction.Failed)
    }

    @Test
    fun `the sweep is offered in order of signal, and never a pairing already dialed`() {
        // A sweep can return several dashboards - two units in range, or one unit and a
        // neighbour's. The nearest is the one the user is standing next to, and a unit
        // this attempt already reached for must not be dialed a second time.
        val cards = listOf(card("a"))
        val found = listOf(
            Found("3C:71:0A:00:00:22", UNIT, -70),
            Found("3C:71:0A:00:00:11", UNIT, -55),
            Found("3C:71:0A:00:00:33", UNIT, -40),
        )
        val first = decide(cards, "a", scanDone = true, found = found, mode = ConnectMode.SEARCH)
        assertEquals(NextAction.Pair(null, "3C:71:0A:00:00:33", UNIT), first.next)
        val second = decide(cards, "a", tried = listOf("3C:71:0A:00:00:33"), scanDone = true,
            found = found, mode = ConnectMode.SEARCH)
        assertEquals(NextAction.Pair(null, "3C:71:0A:00:00:11", UNIT), second.next)
    }

    @Test
    fun `probe mode never sweeps the radio`() {
        // RECONNECT on a card means "the pairing I remember, again" — the user is
        // asking for a retry, not for a hunt that may land on a different unit.
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", tried = listOf(PAIR_A),
            outcome = LinkOutcome.Fail(PAIR_A), mode = ConnectMode.PROBE)
        assertTrue(r.next is NextAction.Failed)
    }

    @Test
    fun `an exhausted ladder is a failure, not an idle wait`() {
        // The difference matters on screen: Idle means "there is nothing to reach",
        // which the vehicle page answers by inviting the user in; Failed means "it was
        // reached for and nobody answered", which is what earns a RECONNECT button.
        val cards = listOf(card("a", ble = PAIR_A))
        for (mode in listOf(ConnectMode.SILENT, ConnectMode.PROBE)) {
            val r = decide(cards, "a", tried = listOf(PAIR_A),
                outcome = LinkOutcome.Fail(PAIR_A), mode = mode)
            assertTrue("$mode", r.next is NextAction.Failed)
        }
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun `the unit that answers is bound to the card that was probed`() {
        // Rule 3: a card made in the editor has a name but no identity. The first
        // connect that reaches it adopts the MAC the unit reports, name and photo
        // intact.
        val cards = listOf(card("a", name = "Workshop Van", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, "AA:BB:CC:DD:EE:FF"))
        assertEquals(
            NextAction.Connect("a", PAIR_A, UNIT, "1.4.6", "AA:BB:CC:DD:EE:FF", 512), r.next,
        )
        assertNotNull(r.persist)
        assertEquals("AA:BB:CC:DD:EE:FF", r.persist!!.mac)
        assertEquals("Workshop Van", r.persist!!.name)
        assertEquals(now, r.persist!!.lastConnectedAtMs)
    }

    @Test
    fun `a reported MAC is stored in one canonical form`() {
        // A unit spelling its identity in lower case, or a value read from a backup
        // file, has to end up in the record the way the ESP prints it, or the next
        // compare misses and the unit looks like a stranger.
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, "aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", r.persist!!.mac)
    }

    @Test
    fun `a connect stores the pairing that answered as the address to dial next`() {
        // The launch ladder dials the card's one address, so the link that just came up
        // is the one worth remembering - and a re-pairing has to be allowed to replace it,
        // because a pairing belongs to the phone's link and not to the dashboard.
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = "3C:71:0A:00:00:05"))
        val r = decide(cards, "a", tried = listOf("3C:71:0A:00:00:05"),
            outcome = ok(PAIR_A, "AA:BB:CC:DD:EE:FF"))
        val persisted = r.persist!!
        assertEquals(
            NextAction.Connect("a", PAIR_A, UNIT, "1.4.6", "AA:BB:CC:DD:EE:FF", 512), r.next,
        )
        assertEquals(PAIR_A, persisted.bleAddress)
        assertEquals("1.4.6", persisted.firmwareVersion)
    }

    @Test
    fun `a unit reporting no MAC keeps the identity its card already earned`() {
        // Firmware predating the mac field, or a body the parser could not read. The
        // card was bound by MAC earlier; a connect that proves nothing about identity
        // must not un-bind it.
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, null))
        assertEquals(NextAction.Connect("a", PAIR_A, UNIT, "1.4.6", null, 512), r.next)
        assertEquals("AA:BB:CC:DD:EE:FF", r.persist!!.mac)
    }

    @Test
    fun `a bound card reconnects to its own address when the unit reports nothing`() {
        // The case the MAC rule could otherwise break: a card already bound to a unit
        // that is still running old firmware answers at its own remembered address
        // with no identity at all. Address alone is enough to connect when exactly one
        // card remembers that address — refusing here would leave a card that can
        // never reconnect after a firmware rollback.
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, null))
        assertTrue(r.next is NextAction.Connect)
        assertEquals("a", (r.next as NextAction.Connect).vehicleId)
        assertNull(r.adoption)
    }

    @Test
    fun `when the answering unit belongs to another card that card becomes the connection`() {
        // The MAC outranks the pairing, so the unit reached on the selected card's
        // pairing belongs to another vehicle - the same dashboard, another link. The
        // selection moves to the card that owns the unit (plan §4) rather than marking
        // CONNECTED on the card the user pressed — and the move has to be said out loud,
        // because the page now shows a different vehicle than the one that was tapped.
        val cards = listOf(
            card("other", mac = "AA:BB:CC:DD:EE:FF", ble = "3C:71:0A:00:00:11"),
            card("a", ble = "3C:71:0A:00:00:11"),
        )
        val r = decide(cards, "a", outcome = ok("3C:71:0A:00:00:11", "AA:BB:CC:DD:EE:FF"))
        assertEquals(
            NextAction.Connect("other", "3C:71:0A:00:00:11", UNIT, "1.4.6", "AA:BB:CC:DD:EE:FF", 512),
            r.next,
        )
        assertEquals("other", r.persist!!.id)
        assertEquals("3C:71:0A:00:00:11", r.persist!!.bleAddress)
        assertNull(r.adoption)
        assertNotNull(r.notice)
        assertTrue(r.notice!!.contains("OTHER"))
        assertTrue(r.notice!!.contains("A"))
    }

    @Test
    fun `one pairing remembered by two cards is not connected on pairing evidence alone`() {
        // With no identity in the answer and two cards holding the same pairing, the
        // app cannot say which vehicle this is - and a wrong guess writes that
        // vehicle's name into the trip log. Two cards can hold one pairing only through
        // a hand-made or imported record, which is how this state is reachable at all.
        val cards = listOf(
            card("a", ble = "3C:71:0A:00:00:11"),
            card("b", ble = "3C:71:0A:00:00:11"),
        )
        val r = decide(cards, "a", outcome = ok("3C:71:0A:00:00:11", null))
        assertTrue(r.next is NextAction.Failed)
        assertNull(r.persist)
    }

    @Test
    fun `a unit nobody owns is offered for adoption, not adopted`() {
        // An unclaimed MAC must never be written into the card the user happened to
        // have selected — that is how two cards end up claiming one dashboard. The
        // offer is data for the page to show, and the user decides.
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = "3C:71:0A:00:00:11"))
        val r = decide(cards, "a", outcome = ok("3C:71:0A:00:00:99", "11:22:33:44:55:66"))
        assertTrue(r.next is NextAction.Failed)
        assertNull(r.persist)
        assertEquals(
            UnclaimedUnit("3C:71:0A:00:00:99", UNIT, "1.4.6", "11:22:33:44:55:66"),
            r.adoption,
        )
    }

    @Test
    fun `an unclaimed unit found at launch leaves the app idle rather than failed`() {
        // The silent ladder runs unattended at startup. Finding an unlinked dashboard
        // there is information for the garage to show, not an error to apologise for.
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = "3C:71:0A:00:00:11"))
        val r = decide(cards, "a", mode = ConnectMode.SILENT,
            outcome = ok("3C:71:0A:00:00:11", "11:22:33:44:55:66"))
        assertTrue(r.next is NextAction.Idle)
        assertEquals(
            UnclaimedUnit("3C:71:0A:00:00:11", UNIT, "1.4.6", "11:22:33:44:55:66"),
            r.adoption,
        )
    }

    @Test
    fun `a unit that reports no identity at all is not offered for adoption`() {
        // There is nothing to adopt without a MAC: no identity to carry into a card.
        // Offering one would let the user bind a card to a pairing that may belong to a
        // completely different unit - a phone's own watch, a headset, anything this radio
        // has bonded with before.
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = "3C:71:0A:00:00:11"))
        val r = decide(cards, "a", outcome = ok("3C:71:0A:00:00:99", null))
        assertNull(r.adoption)
        assertTrue(r.next is NextAction.Failed)
    }

    @Test
    fun `the unclaimed offer is cleared when nothing is unclaimed`() {
        // The view model publishes whatever the core hands it, so a step that finds
        // nothing to offer has to say so explicitly rather than leave the previous
        // offer on screen.
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, "AA:BB:CC:DD:EE:FF"))
        assertNull(r.adoption)
    }

    @Test
    fun `the next probe always belongs to the card that is selected`() {
        // The view model aborts an attempt when the selection moves, and it can only
        // notice that a step is stale if every step says who it is for.
        val cards = listOf(card("a", ble = PAIR_A), card("b", ble = "3C:71:0A:00:00:02"))
        assertEquals("a", (decide(cards, "a").next as NextAction.Pair).vehicleId)
        assertEquals("b", (decide(cards, "b").next as NextAction.Pair).vehicleId)
        assertEquals("3C:71:0A:00:00:02", (decide(cards, "b").next as NextAction.Pair).address)
    }

    @Test
    fun `a card that has never been paired is swept for only when the user asked`() {
        // A card made in the editor has no pairing, so the sweep is the only way it can
        // ever get one — but a sweep is fifteen seconds of the phone listening for a
        // stranger's advertisement, and it needs a permission, so it runs from the button
        // the user pressed and never from launch or the link watcher.
        val cards = listOf(card("a"))
        val search = decide(cards, "a", mode = ConnectMode.SEARCH)
        assertEquals(NextAction.Scan, search.next)
        // A second sweep is a loop the user watches.
        val spent = decide(cards, "a", scanDone = true, mode = ConnectMode.SEARCH)
        assertTrue(spent.next is NextAction.Failed)
        assertTrue((spent.next as NextAction.Failed).reason.isNotBlank())
    }

    @Test
    fun `a sweep that finds an unclaimed unit hands over the identity to name`() {
        // The NAME IT offer needs the MAC and the firmware version, or the line the
        // page shows is empty and the editor starts blank.
        val cards = listOf(card("a"))
        val r = decide(cards, "a", scanDone = true, mode = ConnectMode.SEARCH,
            outcome = ok("3C:71:0A:00:00:99", "11:22:33:44:55:66"))
        assertEquals(
            UnclaimedUnit("3C:71:0A:00:00:99", UNIT, "1.4.6", "11:22:33:44:55:66"),
            r.adoption,
        )
        assertTrue(r.next is NextAction.Failed)
        assertNull(r.persist)
    }

    // ------------------------------------------------------------------- picking from a sweep

    /**
     * The point of listing what the sweep heard instead of dialing what the ladder
     * picked: two units in range, and a tap decides. Both the card's own pairing and a
     * stronger signal lose to the name the user pressed, because every other rule is a
     * guess about which dashboard was meant and a tap is not.
     */
    @Test
    fun `a unit the user picked is dialed ahead of the card's own pairing and of louder signals`() {
        val cards = listOf(card("card", mac = "AABBCCDDEE02", ble = "AA:BB:CC:DD:EE:02"))
        val r = decide(
            cards, "card",
            scanDone = true,
            found = listOf(Found("AA:BB:CC:DD:EE:03", "DPP-03", -40), Found(PAIR_A, UNIT, -90)),
            pinned = Found(PAIR_A, UNIT, -90),
        )
        assertEquals(NextAction.Pair(null, PAIR_A, UNIT), r.next)
    }

    /** A pinned unit that already refused is not dialed twice; the ladder carries on. */
    @Test
    fun `a picked unit that already refused is behind the user now`() {
        val cards = listOf(card("card", mac = "AABBCCDDEE02", ble = "AA:BB:CC:DD:EE:02"))
        val r = decide(
            cards, "card",
            tried = listOf(PAIR_A),
            scanDone = true,
            found = listOf(Found("AA:BB:CC:DD:EE:03", "DPP-03", -40)),
            pinned = Found(PAIR_A, UNIT, -90),
        )
        // The card's remembered pairing is next, and the sweep keeps its own order.
        assertEquals(NextAction.Pair("card", "AA:BB:CC:DD:EE:02", "CARD"), r.next)
    }

    /** With nothing pinned, the ladder's own order (strongest signal first) stands. */
    @Test
    fun `a search dials the loudest unit when the user did not pick one`() {
        val r = decide(
            emptyList(), null,
            scanDone = true,
            found = listOf(Found("AA:BB:CC:DD:EE:09", "DPP-09", -91), Found("AA:BB:CC:DD:EE:10", "DPP-10", -30)),
        )
        assertEquals(NextAction.Pair(null, "AA:BB:CC:DD:EE:10", "DPP-10"), r.next)
    }

    // ---------------------------------------------------------------------- forgetting a unit

    /**
     * What clearing a card's pairing leaves behind.
     *
     * Android owns the bond and the app cannot revoke it (`removeBond` is not a public
     * API here), so the only honest forget is one that says which half it did. The note
     * appears exactly when there is a bond to work off - and never when there is nothing
     * to apologise for, because a page that warns about bonds that do not exist teaches
     * the user to ignore the page.
     */
    @Test
    fun `forgetting a unit warns only when this phone is still bonded`() {
        for (had in listOf(true, false)) {
            for (bonded in listOf(true, false)) {
                val d = ConnectionCore.forgetUnit(had, bonded)
                assertEquals("$had/$bonded", had && bonded, d.needsSystemUnpair)
            }
        }
        assertNull(ConnectionCore.forgetUnit(false, false).note)
        assertNull(ConnectionCore.forgetUnit(true, false).note)
        assertTrue(ConnectionCore.forgetUnit(true, true).note!!.contains("Bluetooth settings"))
    }

    // ------------------------------------------------------------------- reaching for a card

    /**
     * Reaching for a vehicle is a request, not a reset.
     *
     * Since the vehicle card *is* the connect control, one pressed card can produce a dozen
     * presses while its first dial is still in the air, and every one of those presses used to
     * call `BleLink.connect()` again — which opens by tearing the previous link down. On the
     * bench 2026-11-09 that showed up as the unit cycling `phone connected` -> `auth ok` ->
     * `MTU now 517` -> `phone disconnected (reason 0x13)` about every ten seconds, forever: the
     * phone was taking its own links off the air before the subscription could be written.
     *
     * So the rule is: press again on what is already connected -> nothing (the card's own
     * disconnect handler owns that gesture); press again while the same card is being reached
     * for -> nothing; press on *another* card while one is dialing -> the new instruction wins,
     * because the user changed their mind and a swallowed press reads as a dead button.
     */
    @Test
    fun `a second press on a card that is already being reached for does not restart the dial`() {
        val idle = ConnectionCore.onReach(
            connectedSameVehicle = false, attemptRunning = false, sameVehicleRunning = false,
        )
        assertEquals("a cold press must dial", ConnectionCore.ReachDecision.Proceed, idle)

        assertTrue(
            "a press on a connected card is not a new dial",
            ConnectionCore.onReach(connectedSameVehicle = true, attemptRunning = false, sameVehicleRunning = false)
                is ConnectionCore.ReachDecision.Ignore,
        )
        assertTrue(
            "a second press on the card already dialing must not cancel the first",
            ConnectionCore.onReach(connectedSameVehicle = false, attemptRunning = true, sameVehicleRunning = true)
                is ConnectionCore.ReachDecision.Ignore,
        )
        assertTrue(
            "nor does a press while the ladder is sweeping the radio",
            ConnectionCore.onReach(connectedSameVehicle = false, attemptRunning = true, sameVehicleRunning = false)
                is ConnectionCore.ReachDecision.Ignore,
        )

        // Every swallow says why, in a sentence that belongs in the log: a silent swallow is
        // indistinguishable from a broken button, and the bench session started exactly there.
        for (args in listOf(
            Triple(true, false, false),
            Triple(false, true, true),
            Triple(false, true, false),
        )) {
            val d = ConnectionCore.onReach(args.first, args.second, args.third)
            assertTrue("why is missing for $args", (d as ConnectionCore.ReachDecision.Ignore).why.isNotBlank())
        }
    }

}
