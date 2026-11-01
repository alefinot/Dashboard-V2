package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The garage record's text form (vehicle page plan §3).
 *
 * The expensive failure for a store like this is not a garbled field - it is losing
 * a card the user named, typed a plate for and photographed. So the codec is
 * forgiving about everything it can repair (an unknown key, a missing key, a `type`
 * from a newer build, a value of the wrong shape) and strict only about text it
 * cannot interpret at all, where a half-read record would be written back worse than
 * it was read.
 *
 * These tests are the codec's only real exercise: nothing in the app reads or writes
 * a vehicle row yet (task 4 wires the store into the pager), and the JVM is the only
 * place this parser runs, which is the whole reason it is hand-rolled.
 */
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

        // The format tag is part of the text, not an assumption: [decode] refuses a
        // record it cannot identify, so a write from a later build is skipped rather
        // than half-mapped onto this build's defaults and written back.
        assertTrue(text.startsWith("""{"v":"v1""""))
    }

    @Test
    fun `empty and null fields round trip as themselves, not as each other`() {
        // A card with no photo and no identity is the normal state of a newly made
        // vehicle; collapsing "" into null or null into "" would turn "the user
        // cleared the photo" into "this build never wrote one".
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
        // Everything a user can actually type into a name or a note. A name is the
        // one field they will notice if it comes back wrong, and a note with a
        // newline in it is a note they wrote on purpose.
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
        // Spelled out rather than left to the data-class equality above, because a
        // reader that stopped at the first quote would still produce a *plausible*
        // name ("Nähe ") and a dropped tail.
        assertEquals("Nähe \"Auf\" Hof", back?.name)
        assertEquals("line1\nline2\r\nline3\ttail ", back?.notes)
    }

    @Test
    fun `an emoji and a trailing space in a name are kept`() {
        // An emoji is two surrogate halves, the one thing a char-by-char reader can
        // get wrong by counting UTF-16 units as characters; a trailing space is what
        // a copy-paste from a chat window adds, and a name that quietly loses it no
        // longer matches the one the user typed.
        val v = Vehicle(id = "v3", name = "Beetle 🐛 ", model = "Käfer")

        assertEquals(v, VehicleCodec.decode(VehicleCodec.encode(v)))
        assertEquals("Beetle 🐛 ", VehicleCodec.decode(VehicleCodec.encode(v))?.name)
    }

    @Test
    fun `an escape sequence is read as one character, not as its own letter`() {
        // The writer (quote) and the reader (unescape) are the two ends of one
        // agreement, and the failure mode is quiet: a reader that consumes only
        // the backslash appends the decoded character and then reads the escape
        // letter again as text, so "a\nb" comes back as "a<newline>nb" - a
        // plausible string, which is how this parser failed before these tests ran.
        // \/ is never written but is legal JSON, and \uXXXX is the one form
        // that carries its own four digits.
        val text = """{"v":"v1","id":"v13","name":"A\"B\\C\/D\bE\nF\rG\tH\u0041I"}"""
        assertEquals("A\"B\\C/D\bE\nF\rG\tHAI", VehicleCodec.decode(text)?.name)
    }

    @Test
    fun `a record from the Wi-Fi era keeps its row and loses its address book`() {
        // `ips` is no longer a field this app has. It must not make the row unreadable -
        // the cost of that is the card itself, with the user's name on it - and the
        // array must not smuggle itself into the one address field that remains, because
        // a network number is not a Bluetooth address and dialling one is not possible.
        val foreign = """{"v":"v1","id":"v15","name":"Van","ips":["10.0.0.9","10.0.0.8","10.0.0.7","10.0.0.6"]}"""
        val row = VehicleCodec.decode(foreign) ?: error("the old row must still decode")

        assertEquals("Van", row.name)
        assertNull(row.mac)
        assertNull(row.bleAddress)

        // The card is still useful: the first BLE link that answers for it binds, and the
        // pairing is the only address it keeps.
        val healed = VehicleMatch.bindConnect(row, "3F:38:00:11:22:33", null, 1_700_000_000_000L)

        assertEquals(
            "3F:38:00:11:22:33",
            VehicleCodec.decode(VehicleCodec.encode(healed))?.bleAddress,
        )
    }

    @Test
    fun `unknown keys are ignored, including nested ones`() {
        // Forward compatibility: a record written by a newer app (a trailer flag, a
        // service history list) must still load on this build. Failing on an unknown
        // key would be the same as deleting the card.
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
        // The minimum a record can be: an id and nothing else. Everything else is a
        // default the caller already expects.
        val back = VehicleCodec.decode("""{"v":"v1","id":"v4"}""")

        assertEquals(Vehicle(id = "v4"), back)
    }

    @Test
    fun `a field of the wrong shape defaults that field, not the card`() {
        // A torn row or a hand-edited value costs one field: the card, the name and
        // the photo are still there. "soon" is not an epoch, and a number where a
        // name belongs is not a name.
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
        // The store skips a null and keeps the other rows, so "unusable" has to mean
        // "no record at all" - a torn prefix that decoded into a card with an empty
        // id would be a vehicle the user cannot delete by name.
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
        // A v2 record may rename or retype fields; mapping it onto v1's defaults and
        // writing it back would destroy the newer record in place.
        assertNull(VehicleCodec.decode("""{"v":"v2","id":"v9","name":"Future"}"""))
        assertNull(VehicleCodec.decode("""{"id":"v9","name":"No tag"}"""))
        assertNull(VehicleCodec.decode("""{"v":1,"id":"v9"}"""))
    }

    @Test
    fun `a type this build does not know falls back to OTHER and keeps the card`() {
        // Dropping the card over a category is the worst possible trade: the user
        // loses the name, the photo and the bound unit over a label.
        val newer = """{"v":"v1","id":"v6","name":"Truck","type":"TRUCK","mac":"A4:CF:12:3B:9E:07"}"""

        val back = VehicleCodec.decode(newer)

        assertEquals(VehicleType.OTHER, back?.type)
        assertEquals("Truck", back?.name)
        assertEquals("A4:CF:12:3B:9E:07", back?.mac)

        // A type that is not a string at all is the same case, and a *missing* type
        // is not: that one takes the data-class default, CAR.
        assertEquals(VehicleType.OTHER, VehicleCodec.decode("""{"v":"v1","type":42}""")?.type)
        assertEquals(VehicleType.CAR, VehicleCodec.decode("""{"v":"v1","id":"v6"}""")?.type)
    }

    @Test
    fun `a mac that is not a mac is kept verbatim`() {
        // The store remembers what it was given; identity belongs to VehicleMatch,
        // whose claimedMac already treats this text as "no identity". Sanitizing here
        // would be a second, quieter identity rule - and it would make rule 4 unable
        // to see that two cards hold the same junk.
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
        // Timestamps are the field most likely to be silently mangled: a parser that
        // reads a number as a Double loses the low bits above 2^53, and the app's
        // own rule-5 bookkeeping compares those values, so a one-millimetre drift is
        // an ordering change. 2^53+1 is where a Double can no longer tell two
        // integers apart.
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
        // Hand-edited rows and any writer that emits 1700000000000.0 (a JS-shaped
        // export, a future import) must not lose the timestamp: the field-level
        // default would otherwise zero the card's created date.
        val text = """{"v":"v1","id":"v11","createdAtMs":1690000000000.0,""" +
            """"lastConnectedAtMs":1.7e12}"""

        val back = VehicleCodec.decode(text)

        assertEquals(1_690_000_000_000L, back?.createdAtMs)
        assertEquals(1_700_000_000_000L, back?.lastConnectedAtMs)
    }

    @Test
    fun `a number followed by whitespace still decodes`() {
        // A hand-edited row, or any writer that pretty-prints, puts a space between a
        // number and the ',' or '}' that ends it. Losing the whole record over that
        // spacing is the expensive failure - it deletes a card the user named - and the
        // parser already tolerates newlines and tabs in the same position, so a space
        // has to work too.
        val spaced = """{"v":"v1","id":"v13","createdAtMs":1690000000000 ,"notes":"y", "lastConnectedAtMs":1700000000000 }"""

        val back = VehicleCodec.decode(spaced)

        assertEquals("v13", back?.id)
        assertEquals(1_690_000_000_000L, back?.createdAtMs)
        assertEquals(1_700_000_000_000L, back?.lastConnectedAtMs)
        assertEquals("y", back?.notes) // the space after a ',' has to work as well
    }

    @Test
    fun `two numbers with a space between them are still refused`() {
        // Widening the number scan set must not make "12 34" readable as something:
        // the value ends at the space, and the object parser is what rejects the rest.
        // Pinning both halves is the point - without this, the fix above would look
        // like it had thrown away the guard.
        assertNull(VehicleCodec.decode("""{"v":"v1","id":"v14","createdAtMs":12 34}"""))
    }

    @Test
    fun `a connect never invents an identity from the address it dialled`() {
        // The migration's whole reason for this field split: a Bluetooth address names a
        // link, not a unit, so bindConnect may replace it and may not manufacture a mac
        // from it. The old code derived one (`02:` + reversed ip); that habit has to stay
        // dead, so pin it.
        val row = Vehicle(id = "v16", bleAddress = "3F:38:00:11:22:33")

        val healed = VehicleMatch.bindConnect(row, "AA:BB:CC:DD:EE:02", null, 1_700_000_000_000L)

        assertEquals("AA:BB:CC:DD:EE:02", healed.bleAddress)
        assertNull(healed.mac)
    }

    @Test
    fun `a null string field is written as null and read back as null`() {
        // Not a test of a value so much as of the writer: "mac":"" and "mac":null
        // mean different things (see the sparse test), so both forms have to be
        // emitted and read distinctly.
        val text = VehicleCodec.encode(Vehicle(id = "v12", mac = null, photoFile = ""))

        assertTrue(text.contains("\"mac\":null"))
        assertTrue(text.contains("\"photoFile\":\"\""))

        val back = VehicleCodec.decode(text)
        assertNull(back?.mac)
        assertEquals("", back?.photoFile)
    }
}
