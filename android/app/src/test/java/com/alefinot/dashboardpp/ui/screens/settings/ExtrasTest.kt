package com.alefinot.dashboardpp.ui.screens.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The values the unit streams on top of its `CFG_TABLE` rows: the ambient light reading,
 * the ESP core and build versions, the fuel ohm array. The generated asset describes them
 * — a label for each, and which of them are readings rather than settings — and the first
 * cut of the settings tab parsed that and then ignored it.
 *
 * On a bench unit that showed up as a row called "Ambientlightvalue" with a stepper and a
 * reset button next to it, which the unit answered `unknown_key` to after spending a flash
 * cycle on a full re-save. A reading is not a control, and a version string is not a
 * setting; these tests keep them that way.
 */
class ExtrasTest {

    private val gateway = FakeGateway()

    private fun scope() = CoroutineScope(Dispatchers.Unconfined)

    /** One row, a unit streaming that row plus the extras the real asset names. */
    private fun store(
        extras: Map<String, Any?>,
        streamed: Map<String, Any?>,
        hidden: List<String> = emptyList(),
    ): ConfigStore {
        gateway.stored = LinkedHashMap()
        gateway.stored["A_ONE"] = 7
        gateway.stored.putAll(streamed)
        gateway.knownResetKeys = setOf("A_ONE")
        val schema = SettingsFixtures.schema(
            SchemaCard("Test card", listOf(
                SchemaItem("A_ONE", ValueKind.INT, RowControl.NUMBER, "A one", defaultValue = 7),
            )),
            extras = extras,
            hidden = hidden,
        )
        return ConfigStore(gateway, schema, scope(), 40, 60_000, { 0L })
    }

    private val liveExtras = mapOf(
        "ambientLightValue" to mapOf("kind" to "int", "label" to "Ambient light reading (ADC)", "live" to true),
        "core_version" to mapOf("kind" to "text", "label" to "ESP core version", "readOnly" to true),
        "fuelCalOhms" to mapOf("kind" to "floatArray", "label" to "Fuel sender calibration (ohm)", "countKey" to "FUEL_TOUCH_POINTS"),
    )

    /**
     * The asset's own description, parsed into something the renderer can act on. Before
     * this existed the flags were read into a map and thrown away.
     */
    @Test
    fun theAssetSaysWhichStreamedValuesAreReadings() {
        val schema = SettingsFixtures.asset
        assertTrue(
            "the ambient light reading is not flagged live",
            schema.extra("ambientLightValue")?.live == true,
        )
        assertEquals(
            "the core version is not flagged read-only",
            true,
            schema.extra("core_version")?.readOnly,
        )
        assertTrue(
            "the fuel ohm array is a setting and must stay writable",
            schema.extra("fuelCalOhms")?.editable == true,
        )
        assertEquals(
            "the labels the asset carries are the labels the page should use",
            "Ambient light reading (ADC)",
            schema.extra("ambientLightValue")?.label,
        )
        assertEquals(
            listOf("ambientLightValue", "core_version", "build_version").sorted(),
            schema.statusExtras.map { it.key }.sorted(),
        )
    }

    /** A value the asset names is not a mystery key; it is shown as what it is. */
    @Test
    fun aReadingIsNotFiledAmongTheKeysNobodyLabelled() {
        val store = store(liveExtras, mapOf("ambientLightValue" to 2678.0, "core_version" to "3.3.12"))
        runBlocking { store.sync() }
        val state = store.state.value
        assertEquals(
            "a value the asset describes should not be an unlabelled key: " +
                state.unknown.map { it.key },
            emptyList<String>(),
            state.unknown.map { it.key },
        )
        assertEquals(
            "the readings the Tools page draws",
            listOf("ambientLightValue", "core_version"),
            state.unitStatus.map { it.first.key },
        )
        assertEquals(
            "the value the unit sent should be on the row",
            "3.3.12",
            state.unitStatus.first { it.first.key == "core_version" }.second,
        )
        assertEquals(
            "an int-shaped reading reached the row as 2678.0 — the Tools page draws " +
                "that as a decimal",
            2678,
            state.unitStatus.first { it.first.key == "ambientLightValue" }.second,
        )
    }

    /** A backup holds settings. A live ADC reading cannot be put back, so it does not belong. */
    @Test
    fun theExportCarriesSettingsAndNotReadings() {
        val store = store(liveExtras, mapOf("ambientLightValue" to 1, "core_version" to "3.3.12"))
        runBlocking { store.sync() }
        val json = store.export()
        assertTrue("the table row is missing from the export", json.contains("\"A_ONE\""))
        assertFalse(
            "a live reading was exported as if it could be restored",
            json.contains("ambientLightValue"),
        )
        assertFalse(
            "a compiled-in version string was exported as a setting",
            json.contains("core_version"),
        )
    }

    /**
     * Restoring a file that carries readings: they are left alone, counted as left out,
     * and not counted as refused — the unit never got the chance to say no.
     */
    @Test
    fun aRestoreLeavesReadingsOutOfTheUnitAndSaysSo() {
        val store = store(liveExtras, mapOf("core_version" to "3.3.12"))
        runBlocking {
            store.sync()
            val report = store.restore("""{"A_ONE":4,"core_version":"3.3.11"}""")
            assertEquals(1, report.applied)
            assertEquals(1, report.leftOut)
            assertEquals("a reading was counted as refused by the unit", 0, report.refused)
            assertTrue(
                "the left-out reading was not named: ${report.problems}",
                report.problems.any { it.contains("core_version") },
            )
        }
        assertFalse(
            "a version string was offered to the unit's flash",
            gateway.writtenKeys.contains("core_version"),
        )
        assertEquals(
            "the unit's own compiled-in version was overwritten",
            "3.3.12",
            gateway.stored["core_version"],
        )
    }

    /**
     * A key the schema really does not know is editable on purpose — but it must at least
     * show the value the unit sent, or it looks like a setting nobody ever set.
     */
    @Test
    fun anUnlabelledKeyStillCarriesItsValue() {
        val store = store(liveExtras, mapOf("SOME_NEW_THING" to 42))
        runBlocking { store.sync() }
        val state = store.state.value
        assertEquals(listOf("SOME_NEW_THING"), state.unknown.map { it.key })
        val view = state.rows["SOME_NEW_THING"]
        assertNotNull("the unlabelled key has no row view to show its value", view)
        assertEquals(42, (view!!.value as Number).toInt())
    }

    /**
     * A file that carries both a reading and a hidden setting is skipped for two different
     * reasons, and the report has to say both: "this app does not show WIFI_SSID" is a
     * fact about the app, "core_version is not a setting" is a fact about the value. One
     * blended sentence covering fourteen keys read on the bench as fourteen write failures,
     * which is the thing the user reported first.
     */
    @Test
    fun aRestoreNamesEachReasonForKeyItLeftOut() = runBlocking {
        val store = store(
            extras = liveExtras,
            streamed = emptyMap(),
            hidden = listOf("WIFI_SSID"),
        )
        gateway.stored["core_version"] = "3.3.12"
        gateway.stored["WIFI_SSID"] = "home network"
        val report = store.restore("""{"A_ONE":4,"core_version":"9.9.9","WIFI_SSID":"elsewhere"}""")
        assertEquals(1, report.applied)
        assertEquals("a key left out on purpose was counted as refused", 0, report.refused)
        assertEquals(2, report.leftOut)
        assertTrue(
            "the two reasons were not reported as two sentences: ${report.problems}",
            report.problems.any { it.contains("does not show") && it.contains("WIFI_SSID") } &&
                report.problems.any { it.contains("not settings") && it.contains("core_version") },
        )
        assertEquals(
            "a compiled-in version was pushed at the unit",
            "3.3.12", gateway.stored["core_version"],
        )
        assertEquals(
            "a hidden credential was pushed at the unit",
            "home network", gateway.stored["WIFI_SSID"],
        )
    }
}
