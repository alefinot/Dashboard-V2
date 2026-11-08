package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCodecTest {

    private val full = Vehicle(
        id = "v7",
        mac = "A4:CF:12:3B:9E:07",
        name = "Hatch",
        photoFile = "v7.jpg",
        type = VehicleType.MOTORCYCLE,
        plate = "AB123CD",
        maker = "Volkswagen",
        model = "Golf III",
        year = "c. 1998",
        notes = "second carb\nneeds the idle jet,\ttwice",
        bleAddress = "AA:BB:CC:DD:EE:01",
        lastConnectedAtMs = 1_700_000_000_000L,
        firmwareVersion = "V1.3.3",
        createdAtMs = 1_690_000_000_000L,
    )

    @Test
    fun `a fully populated record survives the round trip unchanged`() {
        val text = VehicleCodec.encode(full)

        assertEquals(full, VehicleCodec.decode(text))

        assertTrue(text.startsWith("""{"v":"v1""""))
    }

    @Test
    fun `empty and null fields round trip as themselves, not as each other`() {
        val sparse = Vehicle(id = "v1")
        assertEquals(sparse, VehicleCodec.decode(VehicleCodec.encode(sparse)))

        val decoded = VehicleCodec.decode(VehicleCodec.encode(sparse))!!
        assertNull(decoded.mac)
        assertNull(decoded.photoFile)
        assertNull(decoded.lastConnectedAtMs)
        assertNull(decoded.firmwareVersion)
        assertEquals(0L, decoded.createdAtMs)
        assertNull(decoded.bleAddress)
    }

    @Test
    fun `quotes, backslashes, control characters and Unicode survive exactly`() {
        val nasty = Vehicle(
            id = "v2",
            name = "Nähe \"Auf\" Hof",
            plate = "Kühe, 3",
            maker = "back\\slash",
            model = "tab\there",
            year = "",
            notes = "line1\nline2\r\nline3\ttail ",
            bleAddress = "AA:BB:CC:DD:EE:01",
        )

        val back = VehicleCodec.decode(VehicleCodec.encode(nasty))

        assertEquals(nasty, back)
        assertEquals("Nähe \"Auf\" Hof", back?.name)
        assertEquals("line1\nline2\r\nline3\ttail ", back?.notes)
    }

    @Test
    fun `an emoji and a trailing space in a name are kept`() {
        val v = Vehicle(id = "v3", name = "Beetle 🐛 ", model = "Käfer")

        assertEquals(v, VehicleCodec.decode(VehicleCodec.encode(v)))
        assertEquals("Beetle 🐛 ", VehicleCodec.decode(VehicleCodec.encode(v))?.name)
    }

    @Test
    fun `an escape sequence is read as one character, not as its own letter`() {
        val text = """{"v":"v1","id":"v13","name":"A\"B\\C\/D\bE\nF\rG\tH\u0041I"}"""
        assertEquals("A\"B\\C/D\bE\nF\rG\tHAI", VehicleCodec.decode(text)?.name)
    }

    @Test
    fun `a record from the Wi-Fi era keeps its row and loses its address book`() {
        val foreign = """{"v":"v1","id":"v15","name":"Van","ips":["10.0.0.9","10.0.0.8","10.0.0.7","10.0.0.6"]}"""
        val row = VehicleCodec.decode(foreign) ?: error("the old row must still decode")

        assertEquals("Van", row.name)
        assertNull(row.mac)
        assertNull(row.bleAddress)

        val healed = VehicleMatch.bindConnect(row, "3F:38:00:11:22:33", null, 1_700_000_000_000L)

        assertEquals(
            "3F:38:00:11:22:33",
            VehicleCodec.decode(VehicleCodec.encode(healed))?.bleAddress,
        )
    }

    @Test
    fun `unknown keys are ignored, including nested ones`() {
        val newer = """{"v":"v1","id":"v9","name":"Van","type":"CAR",""" +
            """"trailer":true,"service":[{"date":"2024-05","km":120000},"spare"],""" +
            """"createdAtMs":1690000000000}"""

        val back = VehicleCodec.decode(newer)

        assertNotNull(back)
        assertEquals("v9", back?.id)
        assertEquals("Van", back?.name)
        assertEquals(1_690_000_000_000L, back?.createdAtMs)
        // A value this build has no field for does not leak into another one.
        assertNull(back?.bleAddress)
    }

    @Test
    fun `a missing field takes the data-class default instead of failing the record`() {
        val back = VehicleCodec.decode("""{"v":"v1","id":"v4"}""")

        assertEquals(Vehicle(id = "v4"), back)
    }

    @Test
    fun `a field of the wrong shape defaults that field, not the card`() {
        val torn = """{"v":"v1","id":"v5","name":"Broken","createdAtMs":"soon",""" +
            """"lastConnectedAtMs":"soon","unparsedNum":42,"ips":"not-a-list"}"""

        val back = VehicleCodec.decode(torn)

        assertNotNull(back)
        assertEquals("Broken", back?.name)
        assertEquals(0L, back?.createdAtMs)
        assertNull(back?.lastConnectedAtMs)
        assertNull(back?.bleAddress)
    }

    @Test
    fun `text that cannot be a record decodes to null, not to an empty card`() {
        for (bad in listOf(
            "",
            "   ",
            "not json",
            "[1,2,3]",
            "\"just a string\"",
            "42",
            """{"v":"v1"}trailing junk""",
            """{"v":"v1","id":"hatch""", // unterminated
            """{"v":"v1","id":}""",
            """{"v":"v1" "id":"hatch"}""", // missing comma
        )) {
            assertNull("should not decode: <$bad>", VehicleCodec.decode(bad))
        }
    }

    @Test
    fun `a record from another format version is refused, not half-read`() {
        assertNull(VehicleCodec.decode("""{"v":"v2","id":"v9","name":"Future"}"""))
        assertNull(VehicleCodec.decode("""{"id":"v9","name":"No tag"}"""))
        assertNull(VehicleCodec.decode("""{"v":1,"id":"v9"}"""))
    }

    @Test
    fun `a type this build does not know falls back to OTHER and keeps the card`() {
        val newer = """{"v":"v1","id":"v6","name":"Truck","type":"TRUCK","mac":"A4:CF:12:3B:9E:07"}"""

        val back = VehicleCodec.decode(newer)

        assertEquals(VehicleType.OTHER, back?.type)
        assertEquals("Truck", back?.name)
        assertEquals("A4:CF:12:3B:9E:07", back?.mac)

        assertEquals(VehicleType.OTHER, VehicleCodec.decode("""{"v":"v1","type":42}""")?.type)
        assertEquals(VehicleType.CAR, VehicleCodec.decode("""{"v":"v1","id":"v6"}""")?.type)
    }

    @Test
    fun `a mac that is not a mac is kept verbatim`() {
        val junk = Vehicle(id = "v8", mac = "not-a-mac", name = "Hatch")

        val back = VehicleCodec.decode(VehicleCodec.encode(junk))

        assertEquals("not-a-mac", back?.mac)
        // The seam, pinned: the codec keeps it, VehicleMatch rejects it.
        assertNull(VehicleMatch.normalizeMac(back?.mac))
        assertTrue(VehicleMatch.resolve(listOf(back!!), "3F:38:00:11:22:33", junk.mac)
            is MatchDecision.Unknown)
    }

    @Test
    fun `epoch milliseconds keep every digit through the round trip`() {
        val v = Vehicle(
            id = "v10",
            createdAtMs = 9_007_199_254_740_993L,
            lastConnectedAtMs = 1_700_000_123_456_789L,
        )

        val back = VehicleCodec.decode(VehicleCodec.encode(v))

        assertEquals(9_007_199_254_740_993L, back?.createdAtMs)
        assertEquals(1_700_000_123_456_789L, back?.lastConnectedAtMs)
    }

    @Test
    fun `a numeric field written as a decimal still reads`() {
        val text = """{"v":"v1","id":"v11","createdAtMs":1690000000000.0,""" +
            """"lastConnectedAtMs":1.7e12}"""

        val back = VehicleCodec.decode(text)

        assertEquals(1_690_000_000_000L, back?.createdAtMs)
        assertEquals(1_700_000_000_000L, back?.lastConnectedAtMs)
    }

    @Test
    fun `a number followed by whitespace still decodes`() {
        val spaced = """{"v":"v1","id":"v13","createdAtMs":1690000000000 ,"notes":"y", "lastConnectedAtMs":1700000000000 }"""

        val back = VehicleCodec.decode(spaced)

        assertEquals("v13", back?.id)
        assertEquals(1_690_000_000_000L, back?.createdAtMs)
        assertEquals(1_700_000_000_000L, back?.lastConnectedAtMs)
        assertEquals("y", back?.notes) // the space after a ',' has to work as well
    }

    @Test
    fun `two numbers with a space between them are still refused`() {
        assertNull(VehicleCodec.decode("""{"v":"v1","id":"v14","createdAtMs":12 34}"""))
    }

    @Test
    fun `a connect never invents an identity from the address it dialled`() {
        val row = Vehicle(id = "v16", bleAddress = "3F:38:00:11:22:33")

        val healed = VehicleMatch.bindConnect(row, "AA:BB:CC:DD:EE:02", null, 1_700_000_000_000L)

        assertEquals("AA:BB:CC:DD:EE:02", healed.bleAddress)
        assertNull(healed.mac)
    }

    @Test
    fun `a null string field is written as null and read back as null`() {
        val text = VehicleCodec.encode(Vehicle(id = "v12", mac = null, photoFile = ""))

        assertTrue(text.contains("\"mac\":null"))
        assertTrue(text.contains("\"photoFile\":\"\""))

        val back = VehicleCodec.decode(text)
        assertNull(back?.mac)
        assertEquals("", back?.photoFile)
    }
}
