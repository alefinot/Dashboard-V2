package com.alefinot.dashboardpp.ui.screens.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared input for the settings tests: the generated asset, and a small hand-built
 * schema for the cases that need to know exactly one row.
 *
 * The asset is read from the module's own `src/main/assets`, the same way
 * `AppXmlWiringTest` finds its manifest — a settings test that asserted against a
 * copy of the schema would be testing the copy.
 */
object SettingsFixtures {

    val assetText: String by lazy {
        val f = File(moduleDir(), "src/main/assets/${ConfigSchemaAsset.FILE}")
        assertTrue("cannot find the generated schema at $f", f.isFile)
        f.readText()
    }

    val asset: ConfigSchema by lazy {
        ConfigSchemaAsset.load(assetText) ?: throw IllegalStateException("asset did not parse")
    }

    /** The whole file in the shape a test wants: one group, the named cards. */
    fun schema(
        vararg cards: SchemaCard,
        hidden: List<String> = emptyList(),
        advancedKey: String = "ADV_MODE",
        rebootKeys: List<String> = emptyList(),
        extras: Map<String, Any?> = emptyMap(),
    ): ConfigSchema = ConfigSchema(
        schemaVersion = 1,
        firmware = "0.0.0-test",
        generated = "test",
        rows = cards.sumOf { it.items.size },
        groups = listOf(SchemaGroup("g", "Test group", cards.toList())),
        hidden = hidden,
        advancedKey = advancedKey,
        rebootKeys = rebootKeys,
        extras = extras,
        imperial = emptyMap(),
    )

    /** A module directory that can see `src/main/java/.../ui/screens`. */
    private fun moduleDir(): File {
        val markers = listOf(
            "src/main/assets/${ConfigSchemaAsset.FILE}",
            "app/src/main/assets/${ConfigSchemaAsset.FILE}",
        )
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (cand in listOf(File(dir, "android/app"), File(dir, "app"), dir)) {
                if (markers.any { File(cand, it).isFile }) return cand
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "cannot reach the app module walking up from " + System.getProperty("user.dir"),
        )
    }
}

/**
 * The schema is generated from the firmware, so the interesting assertion is not
 * "does it parse" but "does it still describe the firmware's own rules": unique
 * keys, defaults inside the firmware's band, hidden keys nowhere on screen.
 */
class ConfigSchemaTest {

    @Test
    fun keysAreUniqueAndControlsMatchTheirKind() {
        val schema = SettingsFixtures.asset
        val items = schema.allItems
        assertTrue("no items parsed", items.isNotEmpty())
        val seen = HashSet<String>()
        for (item in items) {
            assertTrue("duplicate key ${item.key}", seen.add(item.key))
            item.keyY?.let { assertTrue("duplicate y key $it", seen.add(it)) }
            assertTrue("empty label for ${item.key}", item.label.isNotEmpty())
            // The control has to suit the value, or the row writes the wrong shape of
            // JSON: a switch for a number would send `true` to an int column.
            val ok = when (item.kind) {
                ValueKind.BOOL -> item.control == RowControl.TOGGLE || item.control == RowControl.SELECT
                ValueKind.INT, ValueKind.FLOAT ->
                    item.control == RowControl.NUMBER || item.control == RowControl.SLIDER ||
                        item.control == RowControl.SELECT
                ValueKind.XY -> item.control == RowControl.PAIR
                ValueKind.COLOR -> item.control == RowControl.SWATCH
                ValueKind.SECRET -> item.control == RowControl.SECRET
                ValueKind.FUEL_TABLE -> item.control == RowControl.FUEL_TABLE
                ValueKind.STR, ValueKind.UNKNOWN ->
                    item.control == RowControl.TEXT || item.control == RowControl.NUMBER ||
                        item.control == RowControl.SLIDER || item.control == RowControl.SELECT ||
                        item.control == RowControl.SWATCH
            }
            assertTrue("${item.key} is a ${item.kind} drawn as a ${item.control}", ok)
        }
    }

    @Test
    fun defaultsSitInsideTheFirmwareBand() {
        for (item in SettingsFixtures.asset.allItems) {
            val lo = item.min
            val hi = item.max
            val def = item.defaultValue as? Number ?: continue
            val d = def.toDouble()
            if (lo != null) {
                assertTrue("default $d below min for ${item.key}", d >= lo)
            }
            if (hi != null) {
                assertTrue("default $d above max for ${item.key}", d <= hi)
            }
            item.defaultY?.let { y ->
                val loY = item.minY
                val hiY = item.maxY
                val v = (y as? Number)?.toDouble() ?: return@let
                if (loY != null) assertTrue("default y $v below min for ${item.key}", v >= loY)
                if (hiY != null) assertTrue("default y $v above max for ${item.key}", v <= hiY)
            }
        }
    }

    /** A hidden key is one the V2 unit cannot act on. Drawing it would be a lie. */
    @Test
    fun noHiddenKeyReachesAGroup() {
        val schema = SettingsFixtures.asset
        assertTrue("the asset hides nothing", schema.hidden.isNotEmpty())
        for (item in schema.allItems) {
            assertTrue("hidden key ${item.key} is rendered", !schema.isHidden(item.key))
            item.keyY?.let { assertTrue("hidden key $it is rendered", !schema.isHidden(it)) }
        }
    }

    /**
     * Every row the unit's form map labels, and every row the firmware says needs a
     * reboot, has to be reachable: an advanced row is hidden behind a switch, and a
     * reboot row has to be behind that switch only if the firmware says so too.
     */
    @Test
    fun rebootRowsMatchTheFirmware() {
        val schema = SettingsFixtures.asset
        for (key in schema.rebootKeys) {
            val item = schema.item(key) ?: continue   // hidden on V2, so not our problem
            assertTrue("$key is marked reboot-required in the firmware only", item.reboot)
        }
        for (item in schema.allItems) {
            if (item.reboot) {
                assertTrue(
                    "${item.key} says reboot but the firmware does not",
                    schema.rebootKeys.contains(item.key),
                )
            }
        }
    }

    /**
     * The unit carries ADV_MODE in `CFG_TABLE`, but this page owns it in the header next
     * to the search box rather than in a card, so the schema leaves it out of the card
     * list. What has to hold is that it is not drawn twice — the store writes it without
     * a card row (`theAdvancedSwitchIsWrittenEvenThoughNoCardCarriesIt`).
     */
    @Test
    fun theAdvancedSwitchIsNotDrawnAsAnOrdinaryRow() {
        val schema = SettingsFixtures.asset
        assertEquals("ADV_MODE", schema.advancedKey)
        assertNull("the master switch is drawn as a settings row", schema.item(schema.advancedKey))
        assertTrue(
            "the master switch appears as an unlabelled key",
            schema.allItems.none { it.key == schema.advancedKey },
        )
    }

    /**
     * What `config.set` in `bt_rpc.cpp` can actually store. A control for anything else
     * would send the unit a value it has no branch for, and the row would quietly never
     * land — the generator's list and the firmware's list have to be the same list.
     */
    @Test
    fun everyRowIsATypeTheFirmwareCanStore() {
        val writable = setOf(
            ValueKind.INT, ValueKind.FLOAT, ValueKind.BOOL,
            ValueKind.STR, ValueKind.SECRET, ValueKind.COLOR, ValueKind.XY, ValueKind.FUEL_TABLE,
        )
        for (item in SettingsFixtures.asset.allItems) {
            assertTrue("${item.key} is a ${item.kind}, which config.set cannot store", item.kind in writable)
            if (item.kind == ValueKind.XY) {
                assertNotNull("${item.key} is a pair with no second key", item.keyY)
            }
            if (item.kind == ValueKind.FUEL_TABLE) {
                assertNotNull("${item.key} has no row that counts its points", item.countKey)
            }
        }
    }

    @Test
    fun aBrokenAssetIsRejectedRatherThanHalfParsed() {
        assertNull(ConfigSchemaAsset.load("{not json"))
        assertNull(ConfigSchemaAsset.load("""{"schemaVersion":1}"""))
    }
}
