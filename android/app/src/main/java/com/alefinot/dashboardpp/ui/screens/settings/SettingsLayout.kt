package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore.SettingsState

/**
 * What the settings page shows, and in what order — the decisions, not the pixels.
 *
 * The rules are the unit's own page's rules, copied deliberately. The page a driver has
 * been using for a year (src/webui.html) is a search box over one long list; the app has
 * the same list, and an owner who has learned where `FUEL_EXC_RES_OHM` lives on the unit
 * should land in the same place on the phone. Two of those rules are load-bearing enough
 * to be written down as code rather than left to a screen:
 *
 *  - **Word-prefix matching** (the page's `match()`, webui.html:3685). Every word typed is
 *    a prefix of some word in the row's searchable text, and the searchable text is the
 *    label, the firmware key, and the unit word — nothing else. The page says so in a
 *    comment: select option labels, notes, help text and live readings are "deliberately
 *    NOT searchable". A row that matched on its help text would show up for a word its
 *    owner never saw it carry, and then look like a different setting.
 *  - **Structure carries matches too**: a matching card title shows the whole card, and a
 *    matching section title shows that section ("Sensors Tuning" / "GPS" are navigable by
 *    name). The group's own title is *not* searched, so typing a group name does not leave
 *    every group open with nothing in it.
 *  - **One group open at a time** (the page's `initDetailsAnim`, webui.html:2785). A
 *    198-row page that opens everything is a page with no structure; a tap on an open
 *    header closes it. While a query is active the page opens every group that has a hit —
 *    a search you have to expand by hand is a search that looks broken — and closes the
 *    rest.
 *
 * All of it is pure and takes the width as a number, because "is this card one column or
 * three" is the settings page's only layout decision worth checking without a tablet.
 */
object SettingsLayout {

    /** The WebUI's breakpoint (`minmax(min(240px,100%),1fr)`): two cards from 600 dp. */
    const val WIDTH_TWO_COLUMNS = 600

    /** Three cards from 900 dp, where a tablet's landscape width lands. */
    const val WIDTH_THREE_COLUMNS = 900

    /** How many cards across a settings page [widthDp] dp wide. */
    fun columns(widthDp: Int): Int = when {
        widthDp >= WIDTH_THREE_COLUMNS -> 3
        widthDp >= WIDTH_TWO_COLUMNS -> 2
        else -> 1
    }

    /**
     * The row's searchable text: label, key, the second key of an `xy` pair, unit.
     *
     * Nothing else, on purpose, because that is the unit page's rule and it is a rule
     * about what a person can *see* on the row. A help sentence that mentions "GPS" does
     * not make a fuel row a GPS row.
     */
    fun searchText(item: SchemaItem): String = buildString {
        append(item.label)
        append(' ').append(item.key)
        item.keyY?.let { append(' ').append(it) }
        item.unit?.let { append(' ').append(it) }
    }

    /** The words a query is made of, lower-cased; empty query means no filter at all. */
    fun words(query: String): List<String> =
        query.trim().lowercase().split(" ").filter { it.isNotEmpty() }

    /**
     * Word-prefix matching, the page's `match()`: every query word must be a prefix of
     * some word of [text].
     *
     * Prefix, not substring — `exc` finds `EXC_RES_OHM`, `cu` does not find "fuel". The
     * unit page works this way and owners have learned it; a substring matcher would find
     * different rows for the same typing on the phone than on the unit, which is the
     * difference between a search and a coincidence.
     */
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

    /**
     * The card's rows under [query], with the advanced gate already applied.
     *
     * A card whose title matches keeps all of its rows — someone who types "noise" is
     * asking for the noise bench, not for one row of it. A section title that matches keeps
     * that section. And a row is never shown past its own advanced flag or its card's, so a
     * search can never walk through the advanced gate: the gate is a statement about the
     * row, not about how hard someone looked for it.
     */
    fun rowsOf(card: SchemaCard, query: String, advanced: Boolean): List<SchemaItem> =
        card.items.filter { item ->
            (!item.advanced || advanced) && rowShown(item, card, query)
        }

    /** Does this card have anything to show — including a card that is only an action? */
    fun cardShown(card: SchemaCard, query: String, advanced: Boolean): Boolean {
        if (card.advanced && !advanced) return false
        // A card with no rows of its own is the action itself (the noise bench, the
        // firmware panel). The unit's page shows those on their own title, which is the
        // only text they have.
        if (card.items.isEmpty()) return matches(query, card.title)
        return rowsOf(card, query, advanced).isNotEmpty()
    }

    /**
     * What one group is made of on screen: its cards, each with the rows it will draw.
     *
     * The section titles are recomputed here rather than carried, because a section header
     * is shown when its own title matched *or* when a row under it survived the filter —
     * which is only knowable after filtering, and getting it wrong is what leaves a bare
     * heading above an empty card.
     */
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
            // A card that grew an action on the unit's page draws that action inside
            // itself; the rows the card holds are still rows, and they stay under it.
            card.widget?.let { body += RenderWidget(it) }
            for (item in card.items) {
                if (!kept.contains(item)) continue
                val section = item.section
                if (section != null && section != currentSection(body)) {
                    // The heading shows when it matched the query or when a row under it
                    // survived. A heading with nothing under it is dropped on the unit's
                    // page and dropped here.
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

    /**
     * One line on a card that is not a list of rows: what its action risks, said on the
     * card itself rather than promised on some other page.
     *
     * Only the firmware card needs one, and it needs it because a firmware update is the one
     * setting-shaped thing on this page that can end with a vehicle sitting in the drive with a
     * dead display. What the line says is what `src/bt_ota.cpp` guarantees: the image is written
     * to the spare flash slot, and the unit switches to it only after its own checks pass.
     */
    private fun cardHint(card: SchemaCard): String? = when (card.widget) {
        // Said on the card, above the buttons, because it is the sentence that decides whether a
        // person presses them: a refused, cancelled or half-finished install leaves the running
        // firmware exactly where it was. The buttons and their answers belong to the control
        // (`FirmwareUpdateControl`); this line is only about what can go wrong.
        "firmwareUpdate" -> "The new image is written to the unit's spare flash slot and put in " +
            "charge only after it passes the unit's own checks. A cancelled, interrupted or " +
            "refused install leaves the unit running what it ran before."
        else -> null
    }

    /** The section title the body currently sits under, if any. */
    private fun currentSection(body: List<Renderable>): String? =
        body.lastOrNull { it is RenderSection }?.let { (it as RenderSection).title }

    /**
     * The unlabelled keys: values the unit streams that this app's schema has no row for.
     *
     * They cannot be placed in a group — the schema is what does the placing — so they form
     * their own block. They are shown rather than dropped, and searched like any other
     * block: a setting that cannot be found looks exactly like a setting that does not
     * exist.
     */
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

    /**
     * The accordion's tap rule: opening a group closes the one that was open, and tapping
     * an open header closes it. The unit page's `initDetailsAnim` in two lines, and the
     * reason the page never becomes a 198-row scroll.
     */
    fun tapOpen(current: String?, id: String): String? = if (current == id) null else id

    /** Would a search for [query] find anything in this group? */
    fun groupHasHits(group: SchemaGroup, query: String, advanced: Boolean): Boolean =
        group.cards.any { cardShown(it, query, advanced) }

    /**
     * A group with no hits is hidden while a query is active — the unit page's
     * `visibleCards > 0` test. Browsing (no query) shows every group.
     */
    fun groupsShown(groups: List<SchemaGroup>, query: String, advanced: Boolean): List<SchemaGroup> =
        if (words(query).isEmpty()) groups
        else groups.filter { groupHasHits(it, query, advanced) }

    /**
     * The card titles a query would find in groups other than the open ones.
     *
     * Deliberately *not* implemented: the unit page hides the groups that have no hits and
     * opens the ones that do, so there is no stranded page to explain. The old per-tab hint
     * ("2 matches in another group") existed because the tab page could leave someone
     * staring at an empty filter; with the accordion, typing "gps" opens Sensors Tuning in
     * place and closes the rest.
     */
    fun otherGroupsMatching(
        groups: List<SchemaGroup>,
        current: Int,
        query: String,
        advanced: Boolean,
    ): List<Pair<String, Int>> = emptyList()

    /**
     * How the cards of one group are packed across [columns] columns.
     *
     * A card is one grid cell — that is what "packed Row-wise with weight columns" means:
     * the unit's page lets a card be as tall as its rows and lets the row of cards be as
     * tall as its tallest card, which is what `auto-fit` does in CSS. Wide blocks (a card
     * that grew an action: the noise bench readout, the fuel table, a colour ramp) take the
     * whole width on their own, because their control is a thing with a shape and cannot be
     * squeezed into a third of a tablet.
     */
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

// ---------------------------------------------------------------------------
// What the page is made of
// ---------------------------------------------------------------------------

/** One thing a page draws: a card heading, or something inside a card. */
sealed interface Renderable

/**
 * What goes inside a card. Split from [Renderable] on purpose: a card heading is not one of
 * these, so the page's `when` over a card's body is exhaustive without an `else` that would
 * otherwise swallow a new kind of row and draw nothing.
 */
sealed interface RenderBody : Renderable

/** A card's heading, with an optional line about the card as a whole. */
data class RenderCard(val title: String, val hint: String? = null) : Renderable

data class RenderSection(val title: String) : RenderBody
data class RenderRow(val item: SchemaItem) : RenderBody

/** A card's action, drawn inside the card. The [widget] name is the schema's, which came
 * from the unit page's own markup (`tripReset`, `noiseBench`, `ambientCalibrate`,
 * `odometerSet`, `firmwareUpdate`, `tempCalibrate`, `batteryCalibrate`).
 */
data class RenderWidget(val widget: String) : RenderBody

/**
 * A card as the grid moves it: its heading and its body together, because the card is the
 * thing that lands in a column — a row is not.
 */
data class RenderBlock(
    val card: RenderCard,
    val body: List<RenderBody>,
    /** True when the block cannot be packed into a column: its control needs the width. */
    val wide: Boolean = false,
)

/**
 * One group as a flat list — heading, then body — which is what a phone-width page draws,
 * and what the tests read.
 *
 * [SettingsLayout.blocksFor] is the same decision kept at card granularity for the page
 * where columns are chosen at draw time from the screen width. Same filter either way: a
 * setting cannot be reachable in one shape and hidden in the other.
 */
fun renderablesFor(
    group: SchemaGroup,
    state: SettingsState,
    query: String,
    advanced: Boolean,
): List<Renderable> = SettingsLayout.blocksFor(group, state, query, advanced).flatMap { block ->
    listOf<Renderable>(block.card) + block.body
}
