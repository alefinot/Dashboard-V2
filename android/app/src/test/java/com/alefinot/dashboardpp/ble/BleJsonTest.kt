package com.alefinot.dashboardpp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleJsonTest {

    @Test
    fun readsAnRpcEnvelopeAndItsNestedBody() {
        val obj = BleJson.obj("""{"id":7,"ok":true,"r":{"pong":1,"ver":"2.0.0"}}""")!!

        assertEquals(7, BleJson.int(obj, "id"))
        assertEquals(true, BleJson.bool(obj, "ok"))
        val r = obj["r"] as Map<*, *>
        assertEquals(1.0, r["pong"])
        assertEquals("2.0.0", r["ver"])
    }

    @Test
    fun readsAConfigChunkWithItsSettingsObject() {
        val obj = BleJson.obj(
            """{"id":3,"i":2,"last":false,"d":{"speedScale":1.25,"ENABLE_AUTO_BRIGHTNESS":true,"cityName":"Aosta"}}""",
        )!!

        assertEquals(2, BleJson.int(obj, "i"))
        assertEquals(false, BleJson.bool(obj, "last"))
        val d = obj["d"] as Map<*, *>
        assertEquals(1.25, d["speedScale"])
        assertEquals(true, d["ENABLE_AUTO_BRIGHTNESS"])
        assertEquals("Aosta", d["cityName"])
    }

    @Test
    fun decodesTheEscapesTheFirmwareWrites() {
        val obj = BleJson.obj("""{"s":"a\"b\\c\nd\t\u00e9"}""")!!
        assertEquals("a\"b\\c\nd\té", obj["s"])
    }

    @Test
    fun rejectsTrailingGarbageAndMalformedText() {
        assertNull(BleJson.obj("""{"a":1} {"""))
        assertNull(BleJson.obj("""{"a":}"""))
        assertNull(BleJson.obj("""{"a":1"""))
        assertNull(BleJson.obj("""[1,2,3]"""))
        assertNull(BleJson.obj(""))
    }

    @Test
    fun parsesArraysAndNumbersTheEnvelopeMightGrow() {
        val obj = BleJson.obj("""{"list":[1,-2,3.5,true,null],"n":-1e3}""")!!
        val list = obj["list"] as List<*>
        assertEquals(1.0, list[0])
        assertEquals(-2.0, list[1])
        assertEquals(3.5, list[2])
        assertEquals(true, list[3])
        assertNull(list[4])
        assertEquals(-1000.0, obj["n"])
    }

    @Test
    fun writesRequestBodiesAndQuotesWhatNeedsIt() {
        assertEquals(
            """{"m":"time.set","id":1,"p":{"timestamp":1700000000}}""",
            "{\"m\":\"time.set\",\"id\":1,\"p\":" +
                BleJson.params("timestamp" to 1_700_000_000L) + "}",
        )
        assertEquals("""{"name":"a\"b\\c"}""", BleJson.params("name" to "a\"b\\c"))
        assertEquals("""{"on":true,"pi":3.5}""", BleJson.params("on" to true, "pi" to 3.5))
        assertEquals(
            """{"outer":{"inner":1}}""",
            BleJson.params("outer" to BleJson.Raw("""{"inner":1}""")),
        )
    }

    @Test
    fun displayKeepsWholeNumbersFromGrowingADotZero() {
        assertEquals("202", BleJson.display(202.0))
        assertEquals("1.25", BleJson.display(1.25))
        assertEquals("true", BleJson.display(true))
        assertEquals("Aosta", BleJson.display("Aosta"))
        assertEquals("null", BleJson.display(null))
    }

    @Test
    fun aFullConfigStreamMergesIntoOneMap() {
        // The merge the link does: chunks arrive in table order, each with its own
        // `d`, and the last one says so.
        val merged = LinkedHashMap<String, Any?>()
        val chunks = listOf(
            """{"id":9,"i":0,"last":false,"d":{"a":1,"b":2.5}}""",
            """{"id":9,"i":1,"last":true,"d":{"c":"x","d":false}}""",
        )
        var lastSeen = false
        for (text in chunks) {
            val obj = BleJson.obj(text)!!
            @Suppress("UNCHECKED_CAST")
            val d = obj["d"] as Map<String, Any?>
            merged.putAll(d)
            if (obj["last"] == true) lastSeen = true
        }

        assertTrue(lastSeen)
        assertEquals(listOf("a", "b", "c", "d"), merged.keys.toList())
        assertEquals(2.5, merged["b"])
    }
}
