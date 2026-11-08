package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val r = decide(emptyList(), null, mode = ConnectMode.SEARCH)
        assertEquals(NextAction.Scan, r.next)
    }

    @Test
    fun `a selection that no longer exists is idle, not a crash`() {
        val r = decide(emptyList(), "gone")
        assertTrue(r.next is NextAction.Idle)
    }

    @Test
    fun `a card that has never been paired is idle on the unattended paths`() {
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
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", tried = listOf(PAIR_A),
            outcome = LinkOutcome.Fail(PAIR_A), mode = ConnectMode.PROBE)
        assertTrue(r.next is NextAction.Failed)
    }

    @Test
    fun `an exhausted ladder is a failure, not an idle wait`() {
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
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, "aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", r.persist!!.mac)
    }

    @Test
    fun `a connect stores the pairing that answered as the address to dial next`() {
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
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, null))
        assertEquals(NextAction.Connect("a", PAIR_A, UNIT, "1.4.6", null, 512), r.next)
        assertEquals("AA:BB:CC:DD:EE:FF", r.persist!!.mac)
    }

    @Test
    fun `a bound card reconnects to its own address when the unit reports nothing`() {
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, null))
        assertTrue(r.next is NextAction.Connect)
        assertEquals("a", (r.next as NextAction.Connect).vehicleId)
        assertNull(r.adoption)
    }

    @Test
    fun `when the answering unit belongs to another card that card becomes the connection`() {
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
        val cards = listOf(card("a", mac = "AA:BB:CC:DD:EE:FF", ble = "3C:71:0A:00:00:11"))
        val r = decide(cards, "a", outcome = ok("3C:71:0A:00:00:99", null))
        assertNull(r.adoption)
        assertTrue(r.next is NextAction.Failed)
    }

    @Test
    fun `the unclaimed offer is cleared when nothing is unclaimed`() {
        val cards = listOf(card("a", ble = PAIR_A))
        val r = decide(cards, "a", outcome = ok(PAIR_A, "AA:BB:CC:DD:EE:FF"))
        assertNull(r.adoption)
    }

    @Test
    fun `the next probe always belongs to the card that is selected`() {
        val cards = listOf(card("a", ble = PAIR_A), card("b", ble = "3C:71:0A:00:00:02"))
        assertEquals("a", (decide(cards, "a").next as NextAction.Pair).vehicleId)
        assertEquals("b", (decide(cards, "b").next as NextAction.Pair).vehicleId)
        assertEquals("3C:71:0A:00:00:02", (decide(cards, "b").next as NextAction.Pair).address)
    }

    @Test
    fun `a card that has never been paired is swept for only when the user asked`() {
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

    // ------------------------------------------------------------------- selecting a card

    @Test
    fun `pointing the app at the connected card keeps the session, another card ends it`() {
        assertFalse(
            "selecting the card that is connected must keep the session",
            CardSelection.endsLiveSession("v2", "v2"),
        )
        assertTrue(
            "selecting a different card must end it - the drive belongs to the vehicle being driven",
            CardSelection.endsLiveSession("v2", "v7"),
        )
        assertFalse(
            "nothing is connected, so there is nothing to end",
            CardSelection.endsLiveSession(null, "v2"),
        )
        assertFalse(
            "and no card at all selected answers the same way",
            CardSelection.endsLiveSession(null, ""),
        )
    }
}
