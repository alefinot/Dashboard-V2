package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore.SettingsState

/**
 * One thing drawn on the unit's screen — the clock, the fuel bar, the weather widget —
 * and everything the owner can tune about it.
 *
 * The element is not something this file invents. The generator stamps `element.id` and
 * `element.label` onto every UI Layout row whose presentation section sits under an
 * `element_header` (scripts/config_schema_presentation.json), so the grouping the list
 * shows is the grouping the *schema* says these settings have — the same rule that keeps
 * every other label on the page honest. A row the presentation never placed inside an
 * element still has to be reachable, so [elementsOf] keeps it in a trailing bucket
 * rather than dropping it off the page.
 *
 * Everything here is pure: elements, hints and search counts come out of the schema and
 * the store's rows as numbers and strings, which is what lets [LayoutElementsTest] hold
 * the rules without a tablet.
 */
data class LayoutElement(
    val id: String,
    val label: String,
    /** The element's rows in schema order — toggle, value rows, then the position pair. */
    val items: List<SchemaItem>,
)

object LayoutElements {

    /** The bucket for rows an element never claimed. Nothing on the page is unreachable. */
    const val UNASSIGNED_ID = "_unassigned"

    /** The element whose position is absolute on the screen rather than an offset. */
    const val CENTER_ID = "center"

    /** The two fallback edges of the unit's panel, from `DISPLAY_WIDTH`/`_HEIGHT` defaults. */
    const val FALLBACK_WIDTH = 480.0
    const val FALLBACK_HEIGHT = 320.0

    /** Does this group render as a list of elements rather than as cards? */
    fun isElementGroup(group: SchemaGroup): Boolean =
        group.cards.any { card -> card.items.any { it.elementId != null } }

    /**
     * The group's elements, in the order the presentation lists them. First-appearance
     * order of the stamped rows, so the list order is the presentation's list order and
     * nobody has to keep two orders in step. The unassigned bucket is always last — a
     * row nobody placed is an afterthought, and it should read that way at the foot of
     * the list rather than wherever its key happened to sort.
     */
    fun elementsOf(group: SchemaGroup): List<LayoutElement> {
        val order = ArrayList<String>()
        val items = LinkedHashMap<String, MutableList<SchemaItem>>()
        var unassigned: MutableList<SchemaItem>? = null
        for (card in group.cards) {
            for (item in card.items) {
                val id = item.elementId
                if (id == null) {
                    if (unassigned == null) unassigned = ArrayList()
                    unassigned += item
                    continue
                }
                if (!items.containsKey(id)) {
                    order += id
                    items[id] = ArrayList()
                }
                items.getValue(id) += item
            }
        }
        val out = order.map { id ->
            val owned = items.getValue(id)
            LayoutElement(
                id = id,
                label = owned.firstNotNullOfOrNull { it.elementLabel } ?: id,
                items = owned.toList(),
            )
        }
        if (unassigned != null) {
            return out + LayoutElement(UNASSIGNED_ID, "Other rows", unassigned.toList())
        }
        return out
    }

    /** The element's on/off row — the switch the list row carries, when there is one. */
    fun toggleOf(element: LayoutElement): SchemaItem? =
        element.items.firstOrNull { it.control == RowControl.TOGGLE }

    /** Whether the element is currently switched off on the unit. Null until it is known. */
    fun hiddenNow(element: LayoutElement, state: SettingsState): Boolean? {
        val toggle = toggleOf(element) ?: return null
        val value = state.rows[toggle.key]?.value ?: return null
        return !truthy(value)
    }

    /**
     * The one line under an element's name: position, digit counts, widget size — the
     * numbers the sheet is opened to change, so the list answers "where is it now"
     * without opening anything. Null when nothing is readable yet.
     */
    fun summary(element: LayoutElement, state: SettingsState): String? {
        val parts = ArrayList<String>()
        if (hiddenNow(element, state) == true) parts += "hidden"

        element.items.firstOrNull { it.kind == ValueKind.XY }?.let { pos ->
            val view = state.rows[pos.key] ?: return@let
            val x = (view.value as? Number)?.toDouble()
            val y = (view.yValue as? Number)?.toDouble()
            if (x != null && y != null) {
                val word = if (element.id == CENTER_ID) "center" else "offset"
                parts += "$word ${x.toInt()} / ${y.toInt()}"
            }
        }

        // Digit-count rows: one `_DIGITS` says "2 digits"; an int/dec pair says "5+1".
        val digits = element.items.filter {
            it.kind == ValueKind.INT && it.key.endsWith("_DIGITS")
        }
        if (digits.isNotEmpty()) {
            val ints = digits.singleOrNull { it.key.endsWith("_INT_DIGITS") }
            val decs = digits.singleOrNull { it.key.endsWith("_DEC_DIGITS") }
            val plain = digits.filter { it !== ints && it !== decs }
            val counted: List<SchemaItem?> = when {
                ints != null && decs != null -> listOf(ints, decs)
                plain.isNotEmpty() -> listOf(plain.firstOrNull())
                else -> listOf(ints ?: decs)
            }
            val values = counted.map { it?.let { d -> number(state, d.key) } }
            if (values.none { it == null } && values.firstOrNull() != null) {
                parts += if (values.size == 2) {
                    "${values[0]!!.toInt()}+${values[1]!!.toInt()} digits"
                } else {
                    "${values[0]!!.toInt()} digits"
                }
            }
        }

        // A `_WIDTH`/`_HEIGHT` pair (the weather widget, the sidebar bars) as one size.
        // Plain `x`, not the U+00D7 sign: TypedPseudoGlyphTest forbids that codepoint in
        // the UI sources outright, because there it always means "close", never "by".
        val width = element.items.firstOrNull { it.key.endsWith("_WIDTH") }
        val height = element.items.firstOrNull { it.key.endsWith("_HEIGHT") }
        if (width != null && height != null) {
            val w = number(state, width.key)
            val h = number(state, height.key)
            if (w != null && h != null) parts += "${w.toInt()}x${h.toInt()}"
        }

        return parts.joinToString(" · ").ifEmpty { null }
    }

    private fun number(state: SettingsState, key: String): Double? =
        (state.rows[key]?.value as? Number)?.toDouble()

    /**
     * Would this element show under [query]? The element's own name counts — a person
     * who types "clock" is asking for the whole element, not for the one row that
     * happens to spell it.
     */
    fun elementShown(element: LayoutElement, query: String, advanced: Boolean = true): Boolean =
        SettingsLayout.matches(query, element.label) ||
            matchCount(element, query, advanced) > 0

    /**
     * How many of the element's rows [query] finds, under the unit page's word-prefix
     * rule (see [SettingsLayout.matches]) over the same text a settings row is searchable
     * by, plus the element's name and the section headings inside its sheet.
     */
    fun matchCount(element: LayoutElement, query: String, advanced: Boolean = true): Int {
        if (SettingsLayout.words(query).isEmpty()) return element.items.size
        return element.items.count { item ->
            (!item.advanced || advanced) &&
                (SettingsLayout.matches(query, SettingsLayout.searchText(item)) ||
                    SettingsLayout.matches(query, element.label) ||
                    (item.section != null && SettingsLayout.matches(query, item.section)))
        }
    }

    /**
     * The unit's current panel size, for the position sliders: the two display rows when
     * the read has them, the shipped defaults when it does not. The unit lets the panel
     * be anything from 16 to 2048 px, so the sliders move with what is set rather than
     * assuming 480×320 forever.
     */
    fun screenSize(state: SettingsState): Pair<Double, Double> {
        val w = number(state, "DISPLAY_WIDTH") ?: FALLBACK_WIDTH
        val h = number(state, "DISPLAY_HEIGHT") ?: FALLBACK_HEIGHT
        return w to h
    }

    /**
     * The window a position slider may offer for one axis.
     *
     * Offsets hang off the screen centre, so a slider spanning the screen is the offset
     * from the middle to one edge on each side — every pixel the element can be moved to
     * without moving the centre itself. [CENTER_ID] is the exception: its position is the
     * centre, and it ranges over the screen, not around it. The window is then intersected
     * with the firmware band, because a slider must never be able to ask for a value the
     * unit would silently clamp. The keyboard box beside it still takes the whole band —
     * the slider is the aim, not the limit.
     */
    fun axisWindow(
        elementId: String,
        item: SchemaItem,
        state: SettingsState,
        xAxis: Boolean,
    ): ClosedFloatingPointRange<Double> {
        val (w, h) = screenSize(state)
        val span = if (xAxis) w else h
        val bandLo = (if (xAxis) item.min else item.minY) ?: Double.NEGATIVE_INFINITY
        val bandHi = (if (xAxis) item.max else item.maxY) ?: Double.POSITIVE_INFINITY
        val raw = if (elementId == CENTER_ID) 0.0..span else -span / 2.0..span / 2.0
        val lo = maxOf(raw.start, bandLo)
        val hi = minOf(raw.endInclusive, bandHi)
        return if (lo <= hi) lo..hi else raw.start..raw.start
    }

    /** The unit's JSON answers a bool row as `true`/`false`; some paths answer `1`. */
    fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }
}
