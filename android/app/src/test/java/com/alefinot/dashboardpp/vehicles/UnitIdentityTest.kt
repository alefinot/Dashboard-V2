package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The one string that names a dashboard to the parts of the app that cannot talk to it.
 *
 * These are the properties the trip store and the recorder's session row are built on, and
 * the one that matters most is the sad one: a key built while the unit reported no identity
 * is a *provisional* key, and the same unit under its real identity is a different key. That
 * is not a bug to design away - a card that has never linked cannot name a unit it has never
 * met - but it has to be visible, because the two halves of one drive's log sit under two
 * keys across that upgrade. The upgrade is one-time; a key that mentioned the pairing
 * forever would break on every re-pairing, which is the much more common event.
 */
class UnitIdentityTest {

    private val macA = "A4:CF:12:3B:9E:07"
    private val bleA = "3F:38:00:11:22:33"
    private val bleB = "AA:BB:CC:DD:EE:01"

    @Test
    fun `a unit that reported its identity keeps one key across a re-pairing`() {
        // The property the whole transport swap rests on. A phone that re-pairs a
        // dashboard gets a different link, and the drive the user took before and after
        // is still one vehicle's history.
        assertEquals(
            UnitIdentity.key(macA, bleA),
            UnitIdentity.key(macA, bleB),
        )
        // And it keeps it when the pairing is forgotten entirely - the identity does not
        // depend on the link still being there.
        assertEquals(
            UnitIdentity.key(macA, bleA),
            UnitIdentity.key(macA, null),
        )
    }

    @Test
    fun `a card that never linked and a unit that reported nothing share a key`() {
        // The no-evidence case has to be one value, not two: a hand-made card (no MAC, no
        // pairing) and a link that carried no MAC are both "we have not met this unit
        // yet", and if they keyed differently, the same unit's logs would sit under two
        // keys depending on which half of the record happened to be filled in.
        assertEquals(UnitIdentity.key(null, null), UnitIdentity.key("", ""))
        assertEquals(UnitIdentity.key(null, null), UnitIdentity.key("not a mac", null))
    }

    @Test
    fun `an unusable identity never forks a key from an absent one`() {
        // `mac = ""` and `mac = "half-written"` are what a corrupt or hand-edited record
        // holds. [VehicleMatch] treats both as "no identity", so the key has to agree -
        // otherwise a card's trips would move when the record was merely repaired.
        assertEquals(UnitIdentity.key(null, bleA), UnitIdentity.key("", bleA))
        assertEquals(UnitIdentity.key(null, bleA), UnitIdentity.key("a4:cf:12:3b", bleA))
        // Lower case is the same identity, so it is the same key.
        assertEquals(UnitIdentity.key(macA, null), UnitIdentity.key("a4:cf:12:3b:9e:07", null))
    }

    @Test
    fun `two pairings of a unit nobody has an identity for are two different keys`() {
        // Pinning the cost, not hiding it. A Bluetooth address belongs to the phone's link,
        // so two phones (or one phone after a re-pairing) hold different pairings for one
        // dashboard, and until the unit reports its MAC there is nothing else to key on.
        // The upgrade path closes this: the first link that reports a MAC moves the row to
        // the identity key, and everything after that is stable.
        assertNotEquals(UnitIdentity.key(null, bleA), UnitIdentity.key(null, bleB))
    }
    @Test
    fun `a value in one half is not the same unit as the same value in the other`() {
        // A MAC and a Bluetooth address have the same shape - six hex pairs - so the shape
        // cannot tell them apart, and precedence cannot catch a caller that swaps the two
        // arguments. What the labels buy is that the two mistakes produce two *different*
        // keys rather than one shared one: a card whose fields are mixed up keeps its own
        // session row instead of quietly taking someone else's, and `02:00:00:00:00:01` (a
        // real risk in either half) stays safe to tell apart.
        assertNotEquals(
            UnitIdentity.key(macA, null),
            UnitIdentity.key(null, macA),
        )
        assertNotEquals(UnitIdentity.key(macA, bleA), UnitIdentity.key(bleA, macA))
    }

    @Test
    fun `a pairing written in lower case keys the same as the stack wrote it`() {
        // The stack hands back upper case, but a value that took a trip through a settings
        // screen, a backup file or a hand edit may not have. A key that changed shape on
        // the way back would orphan the session row it belongs to.
        assertEquals(UnitIdentity.key(null, bleA), UnitIdentity.key(null, bleA.lowercase()))
        // Blank is not an address.
        assertEquals(UnitIdentity.key(null, null), UnitIdentity.key(null, "   "))
    }

    @Test
    fun `the key is stable text, so it can be a file name and a database column`() {
        // Not a behavior test: a statement about the shape. Session rows are keyed by this
        // string in SQLite and a trip carries it in its header, so anything that changes
        // the layout silently orphans the history under it. The layout is pinned here.
        assertEquals("mac:A4:CF:12:3B:9E:07", UnitIdentity.key(macA, bleA))
        assertEquals("ble:$bleA", UnitIdentity.key(null, bleA))
        assertEquals("unknown", UnitIdentity.key(null, null))
    }
}
