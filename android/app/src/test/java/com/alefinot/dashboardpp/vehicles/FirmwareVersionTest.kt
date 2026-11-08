package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
