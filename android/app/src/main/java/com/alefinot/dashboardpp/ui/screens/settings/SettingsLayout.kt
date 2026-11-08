package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore.SettingsState

object SettingsLayout {

    /** The page's breakpoint (`minmax(min(240px,100%),1fr)`): two cards from 600 dp. */
    const val WIDTH_TWO_COLUMNS = 600

    /** Three cards from 900 dp, where a tablet's landscape width lands. */
    const val WIDTH_THREE_COLUMNS = 900

    /** How many cards across a settings page [widthDp] dp wide. */
    fun columns(widthDp: Int): Int = when {
        widthDp >= WIDTH_THREE_COLUMNS -> 3
        widthDp >= WIDTH_TWO_COLUMNS -> 2
        else -> 1
    }

    fun searchText(item: SchemaItem): String = buildString {
        append(item.label)
        append(' ').append(item.key)
        item.keyY?.let { append(' ').append(it) }
        item.unit?.let { append(' ').append(it) }
    }

    /** The words a query is made of, lower-cased; empty query means no filter at all. */
    fun words(query: String): List<String> =
        query.trim().lowercase().split(" ").filter { it.isNotEmpty() }

    fun matches(query: String, text: String?): Boolean {
        val q = words(query)
        if (q.isEmpty()) return true
        if (text.isNullOrEmpty()) return false
        val words = text.lowercase().split(Regex("\\s+"))
        return q.all { word -> words.any { it.startsWith(word) } }
    }

    /** A row visible under [query]: its own text matches, or the card/section title does. */
    private fun rowShown(item: SchemaItem, card: SchemaCard, query: String): Boolean =
        matches(query, searchText(item)) ||
            matches(query, card.title) ||
            (item.section != null && matches(query, item.section))

    fun rowsOf(card: SchemaCard, query: String, advanced: Boolean): List<SchemaItem> =
        card.items.filter { item ->
            (!item.advanced || advanced) && rowShown(item, card, query)
        }

    /** Does this card have anything to show — including a card that is only an action? */
    fun cardShown(card: SchemaCard, query: String, advanced: Boolean): Boolean {
        if (card.advanced && !advanced) return false
        if (card.items.isEmpty()) return matches(query, card.title)
        return rowsOf(card, query, advanced).isNotEmpty()
    }

    fun blocksFor(
        group: SchemaGroup,
        state: SettingsState,
        query: String,
        advanced: Boolean,
    ): List<RenderBlock> {
        val out = ArrayList<RenderBlock>()
        for (card in group.cards) {
            if (!cardShown(card, query, advanced)) continue
            val kept = rowsOf(card, query, advanced).toSet()
            val body = ArrayList<RenderBody>()
            card.widget?.let { body += RenderWidget(it) }
            for (item in card.items) {
                if (!kept.contains(item)) continue
                val section = item.section
                if (section != null && section != currentSection(body)) {
                    val show = matches(query, section) ||
                        card.items.any { it.section == section && kept.contains(it) }
                    if (show) body += RenderSection(section)
                }
                body += RenderRow(item)
            }
            out += RenderBlock(
                card = RenderCard(card.title, cardHint(card)),
                body = body,
                wide = card.widget != null,
            )
        }
        return out
    }

    private fun cardHint(card: SchemaCard): String? = when (card.widget) {
        "firmwareUpdate" -> "The new image is written to the unit's spare flash slot and put in " +
            "charge only after it passes the unit's own checks. Pick firmware.bin from a build " +
            "or a release; a cancelled, interrupted or refused install leaves the unit running " +
            "what it ran before."
        else -> null
    }

    /** The section title the body currently sits under, if any. */
    private fun currentSection(body: List<Renderable>): String? =
        body.lastOrNull { it is RenderSection }?.let { (it as RenderSection).title }

    fun unlabelledBlock(state: SettingsState, query: String): RenderBlock? {
        val kept = state.unknown.filter { rowShown(it, SchemaCard("", emptyList()), query) }
        if (kept.isEmpty()) return null
        return RenderBlock(
            card = RenderCard(
                "Keys this app's schema does not describe",
                "they are kept, editable with a control guessed from the value, and included " +
                    "in the export — regenerate the schema after a firmware update " +
                    "(scripts/gen_config_schema.py) for proper labels",
            ),
            body = kept.map { RenderRow(it) },
        )
    }

    /** Which group headers are open. Null [open] is "none"; an active query overrides. */
    fun openGroups(open: String?, query: String, groups: List<SchemaGroup>, advanced: Boolean): Set<String> {
        val q = words(query)
        if (q.isEmpty()) return if (open == null) emptySet() else setOf(open)
        return groups.filter { groupHasHits(it, query, advanced) }.map { it.id }.toSet()
    }

    fun tapOpen(current: String?, id: String): String? = if (current == id) null else id

    /** Would a search for [query] find anything in this group? */
    fun groupHasHits(group: SchemaGroup, query: String, advanced: Boolean): Boolean =
        group.cards.any { cardShown(it, query, advanced) }

    fun groupMeta(group: SchemaGroup, query: String, advanced: Boolean): String {
        val total = group.cards.sumOf { it.items.size }
        if (total == 0) return "no settings"
        val cards = group.cards.filter { cardShown(it, query, advanced) }
        val hits = cards.sumOf { rowsOf(it, query, advanced).size }
        val actions = cards.count { it.items.isEmpty() }
        if (actions > 0 && hits == 0) return if (actions == 1) "1 card" else "$actions cards"
        return "$hits of $total"
    }

    fun searchSummary(groups: List<SchemaGroup>, query: String, advanced: Boolean): String {
        val total = groups.sumOf { g -> g.cards.sumOf { it.items.size } }
        val cards = groups.sumOf { g ->
            g.cards.count { it.items.isEmpty() && cardShown(it, query, advanced) }
        }
        val hits = groups.sumOf { g ->
            g.cards.sumOf { card ->
                if (cardShown(card, query, advanced)) rowsOf(card, query, advanced).size else 0
            }
        }
        return when {
            cards == 0 -> "$hits of $total settings match \"$query\""
            hits == 0 ->
                if (cards == 1) "1 card matches \"$query\"" else "$cards cards match \"$query\""
            else -> {
                val rowPart = if (hits == 1) "1 setting" else "$hits settings"
                val cardPart = if (cards == 1) "1 card" else "$cards cards"
                "$rowPart and $cardPart match \"$query\""
            }
        }
    }

    fun groupsShown(groups: List<SchemaGroup>, query: String, advanced: Boolean): List<SchemaGroup> =
        if (words(query).isEmpty()) groups
        else groups.filter { groupHasHits(it, query, advanced) }

    fun otherGroupsMatching(
        groups: List<SchemaGroup>,
        current: Int,
        query: String,
        advanced: Boolean,
    ): List<Pair<String, Int>> = emptyList()

    fun pack(blocks: List<RenderBlock>, columns: Int): List<List<RenderBlock>> {
        val cols = columns.coerceIn(1, 3)
        val rows = ArrayList<List<RenderBlock>>()
        var row = ArrayList<RenderBlock>()
        for (block in blocks) {
            if (block.wide) {
                if (row.isNotEmpty()) { rows += row; row = ArrayList() }
                rows += listOf(block)
                continue
            }
            if (row.size == cols) { rows += row; row = ArrayList() }
            row += block
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }
}

/** One thing a page draws: a card heading, or something inside a card. */
sealed interface Renderable

sealed interface RenderBody : Renderable

/** A card's heading, with an optional line about the card as a whole. */
data class RenderCard(val title: String, val hint: String? = null) : Renderable

data class RenderSection(val title: String) : RenderBody
data class RenderRow(val item: SchemaItem) : RenderBody

data class RenderWidget(val widget: String) : RenderBody

data class RenderBlock(
    val card: RenderCard,
    val body: List<RenderBody>,
    /** True when the block cannot be packed into a column: its control needs the width. */
    val wide: Boolean = false,
)

fun renderablesFor(
    group: SchemaGroup,
    state: SettingsState,
    query: String,
    advanced: Boolean,
): List<Renderable> = SettingsLayout.blocksFor(group, state, query, advanced).flatMap { block ->
    listOf<Renderable>(block.card) + block.body
}
