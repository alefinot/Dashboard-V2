package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson

object ConfigSchemaAsset {

    /** The asset's name under `assets/`. */
    const val FILE = "config_schema.json"

    /** The layout this loader understands. A newer asset is refused, not guessed. */
    const val VERSION = 1

    fun load(text: String): ConfigSchema? {
        val root = BleJson.obj(text) ?: return null
        return ConfigSchema.read(root)
    }
}

/** One choice in a picker. `value` is exactly what `config.set` will be sent. */
data class SchemaOption(val value: Any?, val label: String)

enum class ValueKind { INT, FLOAT, BOOL, STR, SECRET, COLOR, XY, FUEL_TABLE, UNKNOWN }

/** How the row is edited. Derived from [ValueKind] plus what the schema says about it. */
enum class RowControl { TOGGLE, NUMBER, TEXT, SECRET, SELECT, SLIDER, SWATCH, PAIR, FUEL_TABLE }

data class SchemaItem(
    val key: String,
    val kind: ValueKind,
    val control: RowControl,
    val label: String,
    val labelIsGuessed: Boolean = false,
    /** The second key of an `xy` pair. Null for every other row. */
    val keyY: String? = null,
    val unit: String? = null,
    /** The firmware's own band — the clamp. Out-of-band input never reaches the unit. */
    val min: Double? = null,
    val max: Double? = null,
    val minY: Double? = null,
    val maxY: Double? = null,
    val uiMin: Double? = null,
    val uiMax: Double? = null,
    /** Step of a slider or stepper, in device units (already multiplied by [unitScale]). */
    val step: Double? = null,
    /** What the human's number is divided by on the way to the screen: 1000000 = MHz. */
    val unitScale: Double = 1.0,
    /** `speed` / `temp` / `length` / `volume`: the row's numbers move with UNITS_IMPERIAL. */
    val unitType: String? = null,
    /** Buffer size minus the terminator, for a string row. */
    val maxLength: Int = 0,
    val options: List<SchemaOption>? = null,
    val advanced: Boolean = false,
    /** Boot-only: changing it needs a reboot before the unit honours it. */
    val reboot: Boolean = false,
    /** A sub-heading inside a card, from the page's `section:` grouping. */
    val section: String? = null,
    /** For a fuel table row: the int row holding the point count. */
    val countKey: String? = null,
    /** The id of the live readout the old page showed next to a table. Carried, unused yet. */
    val liveId: String? = null,
    val defaultValue: Any? = null,
    val defaultY: Any? = null,
) {

    /** The band a control may offer: its own window where it has one, else the firmware band. */
    val sliderMin: Double get() = uiMin ?: min ?: 0.0
    val sliderMax: Double get() = uiMax ?: max ?: 0.0

    /** The band to clamp to — always the firmware's, never the control's. */
    val clampMin: Double? get() = min
    val clampMax: Double? get() = max
}

/** A titled block of rows, matching one `<div class="card">` of the old page. */
data class SchemaCard(
    val title: String,
    val items: List<SchemaItem>,
    /** A whole card is advanced, not only the rows inside it. */
    val advanced: Boolean = false,
    val widget: String? = null,
)

/** A group of the unit's settings page: System & General, Display, ..., plus leftovers. */
data class SchemaGroup(
    val id: String,
    val title: String,
    val cards: List<SchemaCard>,
    val icon: String? = null,
)

data class ConfigSchema(
    val schemaVersion: Int,
    val firmware: String,
    val generated: String,
    val rows: Int,
    val groups: List<SchemaGroup>,
    val hidden: List<String>,
    /** The key that is the tab's own "show advanced" switch rather than a row. */
    val advancedKey: String,
    val rebootKeys: List<String>,
    /** Values the unit streams that are not `CFG_TABLE` rows: versions, the ohm array. */
    val extras: Map<String, Any?>,
    /** The factors behind the imperial display, copied from the page. */
    val imperial: Map<String, Any?>,
) {

    val allItems: List<SchemaItem> get() = groups.flatMap { it.cards.flatMap { c -> c.items } }

    private val byKey: Map<String, SchemaItem> by lazy {
        buildMap {
            for (item in allItems) {
                put(item.key, item)
                item.keyY?.let { put(it, item) }
            }
        }
    }

    /** The row that owns [key] — either half of an `xy` pair returns the same item. */
    fun item(key: String): SchemaItem? = byKey[key]

    fun isHidden(key: String): Boolean = hidden.contains(key)

    data class ExtraSpec(
        val key: String,
        val label: String,
        val kind: String,
        /** The unit has no row to write it into — a version string, a computed value. */
        val readOnly: Boolean,
        /** It changes by itself while the unit runs — an ADC reading. */
        val live: Boolean,
        /** For an array, the key holding its length. */
        val countKey: String?,
    ) {
        /** Only `fuelCalOhms`-shaped extras are settings the unit will take back. */
        val editable: Boolean get() = !readOnly && !live
    }

    private val extraSpecs: Map<String, ExtraSpec> by lazy {
        buildMap {
            for ((key, raw) in extras) {
                val m = raw as? Map<*, *> ?: continue
                put(
                    key,
                    ExtraSpec(
                        key = key,
                        label = ConfigJson.str(m, "label") ?: key,
                        kind = ConfigJson.str(m, "kind") ?: "text",
                        readOnly = ConfigJson.bool(m, "readOnly") == true,
                        live = ConfigJson.bool(m, "live") == true,
                        countKey = ConfigJson.str(m, "countKey"),
                    ),
                )
            }
        }
    }

    fun extra(key: String): ExtraSpec? = extraSpecs[key]

    /** Extras that are readings rather than settings, in asset order. */
    val statusExtras: List<ExtraSpec> get() = extraSpecs.values.filter { !it.editable }

    companion object {

        fun read(root: Map<String, Any?>): ConfigSchema? {
            val version = ConfigJson.int(root, "schemaVersion") ?: 0
            if (version != ConfigSchemaAsset.VERSION) return null
            val groupList = root["groups"] as? List<*> ?: return null
            val groups = ArrayList<SchemaGroup>(groupList.size)
            for (g in groupList) {
                val gm = g as? Map<*, *> ?: return null
                val cards = (gm["cards"] as? List<*>).orEmpty()
                val outCards = ArrayList<SchemaCard>(cards.size)
                for (c in cards) {
                    val cm = c as? Map<*, *> ?: return null
                    val items = (cm["items"] as? List<*>).orEmpty()
                    val outItems = ArrayList<SchemaItem>(items.size)
                    for (i in items) {
                        val im = i as? Map<*, *> ?: return null
                        outItems += readItem(im) ?: return null
                    }
                    outCards += SchemaCard(
                        title = ConfigJson.str(cm, "title") ?: "",
                        items = outItems,
                        advanced = ConfigJson.bool(cm, "advanced") == true,
                        widget = ConfigJson.str(cm, "widget"),
                    )
                }
                groups += SchemaGroup(
                    id = ConfigJson.str(gm, "id") ?: "",
                    title = ConfigJson.str(gm, "title") ?: "",
                    cards = outCards,
                    icon = ConfigJson.str(gm, "icon"),
                )
            }
            return ConfigSchema(
                schemaVersion = version,
                firmware = ConfigJson.str(root, "firmware") ?: "",
                generated = ConfigJson.str(root, "generated") ?: "",
                rows = ConfigJson.int(root, "rows") ?: 0,
                groups = groups,
                hidden = (root["hidden"] as? List<*>).orEmpty().mapNotNull { it as? String },
                advancedKey = ConfigJson.str(root, "advancedKey") ?: "ADV_MODE",
                rebootKeys = (root["rebootKeys"] as? List<*>).orEmpty()
                    .mapNotNull { it as? String },
                extras = (root["extras"] as? Map<*, *>).orEmpty()
                    .mapNotNull { (k, v) -> if (k is String) k to v else null }
                    .toMap(),
                imperial = (root["imperial"] as? Map<*, *>).orEmpty()
                    .mapNotNull { (k, v) -> if (k is String) k to v else null }
                    .toMap(),
            )
        }

        private fun readItem(m: Map<*, *>): SchemaItem? {
            val key = ConfigJson.str(m, "key") ?: return null
            val kind = when (ConfigJson.str(m, "kind")) {
                "int", "uint" -> ValueKind.INT
                "float", "double" -> ValueKind.FLOAT
                "bool" -> ValueKind.BOOL
                "str", "text" -> ValueKind.STR
                "secret" -> ValueKind.SECRET
                "color" -> ValueKind.COLOR
                "xy" -> ValueKind.XY
                "fuelTable" -> ValueKind.FUEL_TABLE
                else -> ValueKind.UNKNOWN
            }
            val options = (m["options"] as? List<*>).orEmpty().mapNotNull { o ->
                val om = o as? Map<*, *> ?: return@mapNotNull null
                SchemaOption(om["value"], ConfigJson.str(om, "label") ?: return@mapNotNull null)
            }
            val defaults = m["default"]
            val isPair = kind == ValueKind.XY
            val defaultX = if (isPair) (defaults as? List<*>)?.getOrNull(0) else defaults
            val defaultY = if (isPair) (defaults as? List<*>)?.getOrNull(1) else null
            val control = controlFor(kind, options, ConfigJson.bool(m, "slider") == true)
            return SchemaItem(
                key = key,
                kind = kind,
                control = control,
                label = ConfigJson.str(m, "label") ?: key,
                labelIsGuessed = ConfigJson.bool(m, "generated") == true,
                keyY = ConfigJson.str(m, "keyY"),
                unit = ConfigJson.str(m, "unit"),
                min = ConfigJson.dbl(m, "min"),
                max = ConfigJson.dbl(m, "max"),
                minY = ConfigJson.dbl(m, "minY"),
                maxY = ConfigJson.dbl(m, "maxY"),
                uiMin = ConfigJson.dbl(m, "uiMin"),
                uiMax = ConfigJson.dbl(m, "uiMax"),
                step = ConfigJson.dbl(m, "step") ?: ConfigJson.dbl(m, "uiStep"),
                unitScale = ConfigJson.dbl(m, "unitScale") ?: 1.0,
                unitType = ConfigJson.str(m, "unitType"),
                maxLength = ConfigJson.int(m, "maxLength") ?: 0,
                options = options.ifEmpty { null },
                advanced = ConfigJson.bool(m, "advanced") == true,
                reboot = ConfigJson.bool(m, "reboot") == true,
                section = ConfigJson.str(m, "section"),
                countKey = ConfigJson.str(m, "countKey"),
                liveId = ConfigJson.str(m, "liveId"),
                defaultValue = defaultX,
                defaultY = defaultY,
            )
        }

        fun controlFor(
            kind: ValueKind,
            options: List<SchemaOption>?,
            slider: Boolean,
        ): RowControl = when {
            options != null && options.isNotEmpty() -> RowControl.SELECT
            kind == ValueKind.XY -> RowControl.PAIR
            kind == ValueKind.COLOR -> RowControl.SWATCH
            kind == ValueKind.FUEL_TABLE -> RowControl.FUEL_TABLE
            kind == ValueKind.SECRET -> RowControl.SECRET
            kind == ValueKind.BOOL -> RowControl.TOGGLE
            slider -> RowControl.SLIDER
            kind == ValueKind.STR -> RowControl.TEXT
            else -> RowControl.NUMBER
        }
    }
}

object ConfigJson {

    fun str(m: Map<*, *>, key: String): String? = m[key] as? String

    fun bool(m: Map<*, *>, key: String): Boolean? = m[key] as? Boolean

    fun dbl(m: Map<*, *>, key: String): Double? = (m[key] as? Number)?.toDouble()

    fun int(m: Map<*, *>, key: String): Int? = (m[key] as? Number)?.toInt()
}
