package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup and restore, measured in bytes as well as keys.
 *
 * The old page sent the whole configuration to the unit as one HTTP body. Over the
 * link there is no "one body": the dashboard reassembles an inbound message up to
 * `BT_MAX_MSG` and refuses anything larger, and the config plus a 128-point fuel
 * array is bigger than that. So a restore goes back card by card, and this test is
 * what keeps "card by card" from turning into "one object again" the day someone
 * simplifies the loop.
 */
class BackupTest {

    private val gateway = FakeGateway()

    private fun store(schema: ConfigSchema) =
        ConfigStore(gateway, schema, CoroutineScope(Dispatchers.Unconfined), 10, 60_000) { 0 }

    /** The real generated schema, so the sizes are the sizes a V2 unit will be sent. */
    @Test
    fun aWholeConfigGoesBackInPiecesUnderTheLinkCap() {
        val schema = SettingsFixtures.asset
        gateway.stored = LinkedHashMap()
        for (item in schema.allItems) {
            gateway.stored[item.key] = item.defaultValue
            item.keyY?.let { gateway.stored[it] = item.defaultY }
        }
        // The fuel curve is the fat one: 128 ohm readings, and the point-count row
        // that goes with them.
        gateway.stored["fuelCalOhms"] = (0 until 128).map { 100 + it }
        gateway.stored["FUEL_TOUCH_POINTS"] = 128
        schema.extras.forEach { (key, value) -> gateway.stored.putIfAbsent(key, value) }

        val store = store(schema)
        runBlocking {
            store.sync()
            val doc = store.export()
            val parsed = BleJson.obj(doc) ?: throw AssertionError("export did not encode as an object")
            assertTrue("the export lost the fuel curve", (parsed["fuelCalOhms"] as? List<*>)?.size == 128)

            gateway.writes.clear()
            gateway.writtenKeys.clear()
            gateway.pieceBytes.clear()
            val report = store.restore(doc)

            assertTrue("restore applied nothing (${report.problems})", report.applied > 0)
            assertTrue("a whole config went back in one message", report.batches > 1)
            val cap = gateway.linkCap()
            for (size in gateway.pieceBytes) {
                assertTrue("a $size-byte message near the cap of $cap", size + RpcFraming.ENVELOPE_RESERVE <= cap)
            }
            assertEquals(
                "restore did not put every key back",
                parsed.keys.toSet(),
                gateway.writtenKeys.toSet(),
            )
        }
    }

    /** A refused card is reported, not retried into oblivion and not hidden. */
    @Test
    fun aRefusedBatchIsCountedAndNamed() {
        val schema = SettingsFixtures.schema(
            SchemaCard("Alpha", listOf(SchemaItem("A_ONE", ValueKind.INT, RowControl.NUMBER, "A one", defaultValue = 1))),
            SchemaCard("Bravo", listOf(SchemaItem("B_ONE", ValueKind.INT, RowControl.NUMBER, "B one", defaultValue = 2))),
        )
        gateway.stored = linkedMapOf("A_ONE" to 1, "B_ONE" to 2)
        val store = store(schema)
        runBlocking {
            store.sync()
            gateway.writeQueue.addLast(WriteResult(ok = false, error = "unknown_key"))
            val report = store.restore("""{"A_ONE":5,"B_ONE":6}""")
            assertEquals(1, report.refused)
            assertEquals("a clean key was counted as refused too", 0, report.leftOut)
            assertEquals(1, report.applied)
            assertEquals(listOf("Alpha: unknown_key"), report.problems)
        }
        assertEquals(6, (gateway.stored["B_ONE"] as Number).toInt())
    }

    /** A document that is not a settings backup at all must fail loudly. */
    @Test
    fun aForeignDocumentIsRejected() {
        val schema = SettingsFixtures.schema(
            SchemaCard("Alpha", listOf(SchemaItem("A_ONE", ValueKind.INT, RowControl.NUMBER, "A one", defaultValue = 1))),
        )
        val store = store(schema)
        runBlocking {
            val report = store.restore("this is not json")
            assertEquals(0, report.applied)
            assertTrue(report.problems.isNotEmpty())
        }
    }

    /**
     * How a restore reads in the bar. Two clauses were joined with a separator in the join
     * and another inside each clause, so the page printed "… ·  · 3 value(s) …", and a
     * blank entry in the list made it worse. One separator between clauses, nothing else.
     */
    @Test
    fun theRestoreLinePrintsEachClauseOnce() {
        val line = RestoreReport(
            applied = 185,
            refused = 0,
            leftOut = 17,
            nvsErrors = 0,
            batches = 23,
            problems = listOf(
                "14 key(s) this app does not show were left as the unit has them: WIFI_SSID",
                " ",
                "3 value(s) that are not settings were left alone: ambientLightValue",
            ),
        ).summary
        assertEquals(
            "restored 185 keys in 23 batches"
                + " · 14 key(s) this app does not show were left as the unit has them: WIFI_SSID"
                + " · 3 value(s) that are not settings were left alone: ambientLightValue",
            line,
        )
        assertTrue("the bar line still carries a doubled separator: $line", !line.contains("  · "))
    }
}
