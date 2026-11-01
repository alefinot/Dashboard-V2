package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version arithmetic behind "this unit cannot be reached at all".
 *
 * The app speaks one transport now, and units that predate it are still in the world -
 * flashed before the GATT server existed, and not reflashed by a phone that cannot reach
 * them. The card has to say so, and the sentence rests on a comparison a string compare
 * would get wrong: `firmwareVersion = "1.10.2"` sorts **below** "1.4.6" as text and above
 * it as numbers, and the units print dotted numbers.
 */
class FirmwareVersionTest {

    @Test
    fun `the comparison is numeric, not lexical`() {
        assertTrue(firmwareAtLeast("1.5.0", "1.5.0"))
        assertTrue(firmwareAtLeast("1.5.1", "1.5.0"))
        assertFalse(firmwareAtLeast("1.4.9", "1.5.0"))
        assertFalse(firmwareAtLeast("0.9.9", "1.5.0"))
        // The case a string compare loses, and the case a real unit shipped.
        assertTrue(firmwareAtLeast("1.10.2", "1.5.0"))
        assertFalse(firmwareAtLeast("1.10.2", "1.11.0"))
        // A short label means the missing part is zero, not unknown.
        assertTrue(firmwareAtLeast("1.5", "1.5.0"))
        assertFalse(firmwareAtLeast("1.4", "1.5.0"))
        // The units print `V1.4.0` in their own headers; a leading V is a label.
        assertTrue(firmwareAtLeast("V1.5.0", "1.5.0"))
        assertFalse(firmwareAtLeast("v1.4.6", "1.5.0"))
    }

    /**
     * Anything that does not read as a version answers **reachable**.
     *
     * A label is a fact about the unit; a failure to read it is a fact about this code.
     * The damage from a false "too old" is a user going to update a current unit, or
     * believing a card that lies about their car; the damage from a false "reachable" is a
     * failed connect, which is what the attempt's own words are for. `1.x.2` is included
     * because reading it as "1.2" would be inventing a fact about someone's dashboard.
     */
    @Test
    fun `an unreadable or missing label is never called too old`() {
        for (v in listOf(null, "", "   ", "unknown", "1", "snapshot-v1.5.0-rc1", "1.x.2")) {
            assertTrue("$v", firmwareAtLeast(v, "1.5.0"))
        }
    }

    @Test
    fun `a card is only called pre-Bluetooth when it recorded an old version`() {
        assertTrue(
            Vehicle(id = "a", name = "Old", type = VehicleType.CAR, firmwareVersion = "1.4.6")
                .preBleFirmware,
        )
        assertFalse(
            Vehicle(id = "b", name = "Now", type = VehicleType.CAR, firmwareVersion = "1.5.0")
                .preBleFirmware,
        )
        // The asymmetry the rule turns on: silence is not evidence of age.
        assertFalse(
            Vehicle(id = "c", name = "Unknown", type = VehicleType.CAR, firmwareVersion = null)
                .preBleFirmware,
        )
    }
}
