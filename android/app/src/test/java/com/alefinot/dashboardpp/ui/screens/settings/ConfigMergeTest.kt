package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a `config.get` that arrives in pieces has to become: one joined set of values,
 * the unit's extras beside the rows, and nothing quietly dropped because the schema
 * had not heard of it.
 *
 * The frame-level merge (bytes → JSON pieces) is [com.alefinot.dashboardpp.ble.BleProtocolTest]'s
 * business; this is the layer above it, where a row is either drawn or missing and the
 * difference is a setting the owner cannot find.
 */
class ConfigMergeTest {

    private val gateway = FakeGateway()
    private val schema = SettingsFixtures.schema(
        SchemaCard("Card", listOf(
            SchemaItem("SPEED_MAX", ValueKind.INT, RowControl.NUMBER, "Speed limit", defaultValue = 60),
            SchemaItem(
                "GAUGE_POS", ValueKind.XY, RowControl.PAIR, "Gauge position",
                keyY = "GAUGE_POS_Y", defaultValue = 10, defaultY = 20,
            ),
        )),
        extras = mapOf("build_version" to "1.4.0", "fuelCalOhms" to listOf(100.0, 200.0)),
    )

    private fun store() = ConfigStore(gateway, schema, CoroutineScope(Dispatchers.Unconfined), 10, 60_000) { 0 }

    @Test
    fun chunksArriveAsProgressAndTheJoinIsComplete() = runBlocking {
        gateway.stored = linkedMapOf(
            "SPEED_MAX" to 60,
            "GAUGE_POS" to 10,
            "GAUGE_POS_Y" to 20,
            "build_version" to "1.4.0",
            "fuelCalOhms" to listOf(100.0, 200.0),
        )
        val store = store()
        store.sync()
        val phase = store.state.value.sync as SyncPhase.Ready
        assertEquals(5, phase.keys)
        assertTrue("no chunk progress was reported", phase.chunks >= 1)
        assertEquals(gateway.chunkIndexes, (0 until gateway.chunkIndexes.size).toList())
        assertEquals(emptyList<String>(), store.state.value.missingKeys)
        val xy = store.state.value.rows.getValue("GAUGE_POS")
        assertEquals(10, (xy.value as Number).toInt())
        assertEquals("an xy row lost its second half", 20, (xy.yValue as Number).toInt())
    }

    /**
     * A value the unit streams that no schema row describes — the sign that the schema
     * was not regenerated after a firmware change. It stays in the map, is exported,
     * and is drawn under a heading that says it is a guess. Dropping it would be a
     * setting that silently disappears.
     */
    @Test
    fun aKeyWithNoSchemaRowSurvivesTheRead() = runBlocking {
        gateway.stored = linkedMapOf(
            "SPEED_MAX" to 60,
            "GAUGE_POS" to 10,
            "GAUGE_POS_Y" to 20,
            "NEW_CAP_MODE" to 2,
            "NEW_CAP_NAME" to "front",
        )
        val store = store()
        store.sync()
        val unknown = store.state.value.unknown.map { it.key }.toSet()
        assertEquals(setOf("NEW_CAP_MODE", "NEW_CAP_NAME"), unknown)
        val guess = store.state.value.unknown.first { it.key == "NEW_CAP_NAME" }
        assertEquals("New cap name", guess.label)
        assertTrue("a guessed row must say so", guess.labelIsGuessed)
        assertEquals(ValueKind.STR, guess.kind)
        // And it is still in the backup document, so a restore can put it back.
        assertTrue(store.export().contains("NEW_CAP_NAME"))
    }

    @Test
    fun theExtrasLandInTheExportBesideTheRows() = runBlocking {
        gateway.stored = linkedMapOf("SPEED_MAX" to 60)
        val store = store()
        store.sync()
        val doc = BleJson.obj(store.export()) ?: throw AssertionError("export is not an object")
        assertEquals(60, (doc["SPEED_MAX"] as Number).toInt())
        assertEquals("1.4.0", doc["build_version"])
        assertEquals(2, (doc["fuelCalOhms"] as List<*>).size)
    }
}
