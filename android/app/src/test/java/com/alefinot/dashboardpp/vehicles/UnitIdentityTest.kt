package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UnitIdentityTest {

    private val macA = "A4:CF:12:3B:9E:07"
    private val bleA = "3F:38:00:11:22:33"
    private val bleB = "AA:BB:CC:DD:EE:01"

    @Test
    fun `a unit that reported its identity keeps one key across a re-pairing`() {
        assertEquals(
            UnitIdentity.key(macA, bleA),
            UnitIdentity.key(macA, bleB),
        )
        assertEquals(
            UnitIdentity.key(macA, bleA),
            UnitIdentity.key(macA, null),
        )
    }

    @Test
    fun `a card that never linked and a unit that reported nothing share a key`() {
        assertEquals(UnitIdentity.key(null, null), UnitIdentity.key("", ""))
        assertEquals(UnitIdentity.key(null, null), UnitIdentity.key("not a mac", null))
    }

    @Test
    fun `an unusable identity never forks a key from an absent one`() {
        assertEquals(UnitIdentity.key(null, bleA), UnitIdentity.key("", bleA))
        assertEquals(UnitIdentity.key(null, bleA), UnitIdentity.key("a4:cf:12:3b", bleA))
        // Lower case is the same identity, so it is the same key.
        assertEquals(UnitIdentity.key(macA, null), UnitIdentity.key("a4:cf:12:3b:9e:07", null))
    }

    @Test
    fun `two pairings of a unit nobody has an identity for are two different keys`() {
        assertNotEquals(UnitIdentity.key(null, bleA), UnitIdentity.key(null, bleB))
    }
    @Test
    fun `a value in one half is not the same unit as the same value in the other`() {
        assertNotEquals(
            UnitIdentity.key(macA, null),
            UnitIdentity.key(null, macA),
        )
        assertNotEquals(UnitIdentity.key(macA, bleA), UnitIdentity.key(bleA, macA))
    }

    @Test
    fun `a pairing written in lower case keys the same as the stack wrote it`() {
        assertEquals(UnitIdentity.key(null, bleA), UnitIdentity.key(null, bleA.lowercase()))
        // Blank is not an address.
        assertEquals(UnitIdentity.key(null, null), UnitIdentity.key(null, "   "))
    }

    @Test
    fun `the key is stable text, so it can be a file name and a database column`() {
        assertEquals("mac:A4:CF:12:3B:9E:07", UnitIdentity.key(macA, bleA))
        assertEquals("ble:$bleA", UnitIdentity.key(null, bleA))
        assertEquals("unknown", UnitIdentity.key(null, null))
    }
}
