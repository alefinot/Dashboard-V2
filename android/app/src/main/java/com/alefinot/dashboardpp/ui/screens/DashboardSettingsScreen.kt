package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.R
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.screens.settings.ConfigSchema
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore
import com.alefinot.dashboardpp.ui.screens.settings.PerfMonitorScreen
import com.alefinot.dashboardpp.ui.screens.settings.RenderBlock
import com.alefinot.dashboardpp.ui.screens.settings.RenderRow
import com.alefinot.dashboardpp.ui.screens.settings.RenderSection
import com.alefinot.dashboardpp.ui.screens.settings.RenderWidget
import com.alefinot.dashboardpp.ui.screens.settings.RowControl
import com.alefinot.dashboardpp.ui.screens.settings.RowStatus
import com.alefinot.dashboardpp.ui.screens.settings.RowView
import com.alefinot.dashboardpp.ui.screens.settings.SchemaGroup
import com.alefinot.dashboardpp.ui.screens.settings.SchemaItem
import com.alefinot.dashboardpp.ui.screens.settings.SettingsLayout
import com.alefinot.dashboardpp.ui.screens.settings.SettingsRow
import com.alefinot.dashboardpp.ui.screens.settings.SyncPhase
import com.alefinot.dashboardpp.ui.screens.settings.ValueKind
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The unit's configuration, as a native page.
 *
 * This is the page the app used to hand to a WebView: the ESP's own settings site, loaded
 * over Wi-Fi, framed in a glass sheet. It worked, and it is the reason the settings tab and
 * the unit's own form map used to be the same artifact — the unit served the form, so the
 * form could not disagree with itself. Once the settings live in Compose that guarantee is
 * gone, so two things replaced it: the page is *generated* from the firmware's own tables
 * (see `ConfigSchema`), so every number here is a `CFG_TABLE` row the unit confirmed it has;
 * and its shape copies the unit page's shape exactly, so a driver who knows where a setting
 * is on the dashboard's own screen finds it in the same place, behaving the same way, on the
 * phone.
 *
 * That shape is the accordion, and it is not a style choice. The unit's page is five
 * collapsible groups holding 198 rows, one open at a time, with a search box that opens
 * whatever it finds and hides the groups it does not. A tab strip of the same five groups was
 * tried first, and on the bench it failed in a way nobody noticed until someone typed into
 * the box: the tab you are standing on is the one that gets searched, so a search for "gps"
 * from the System tab found nothing, on a page that had no way to say that two rows were one
 * tap away. With one long scroll of collapsible groups there is no "current tab" to be
 * stranded on. `SettingsLayout` holds those rules and its tests hold them to the unit page's
 * behaviour.
 *
 * What the page is made of, in order:
 *
 *  - **The link header.** A V2 unit has no network stack; Bluetooth is the only way to it, so
 *    the page starts by saying whether there is a link at all. It does not offer to make one:
 *    pairing is something you do to a vehicle, so it lives on the Vehicles page.
 *  - **The sync bar.** Nothing below it is editable before `config.get` has been joined to the
 *    schema, and the bar says which of the unit's keys the read covered.
 *  - **Search and advanced mode.** The two things a 198-row form needs that a card stack does
 *    not: find a row, and reveal the rows the average owner should not touch.
 *  - **The groups.** Icons, titles and counts, from the same grouping the unit page uses,
 *    because that grouping is what its `configMap` says the settings are. Cards flow in
 *    columns inside a group the way the unit page's grid flows them.
 *  - **The sticky bar at the bottom.** Write what is waiting, restart the unit, get the config
 *    off it and back on. The actions that are not attached to one card.
 *
 * Each row still writes itself — there is no save button in the flow of the form. The SAVE in
 * the bottom bar is a different thing: it flushes what is still in the phone's RAM, for the
 * driver who is done editing and is walking to the bike.
 */
@Composable
fun DashboardSettingsScreen(
    store: ConfigStore,
    schema: ConfigSchema?,
    link: BleLink,
    /**
     * Why the unit's clock was not set from this phone (null when it was). Passed through
     * to the monitor page, which is where the rest of the link status lives; this page has
     * no business explaining a link it does not own.
     */
    timeNote: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsStateWithLifecycle()
    val linkState by link.state.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    /** The one group the accordion has open. Nothing is open until someone taps. */
    var open by remember { mutableStateOf<String?>(null) }
    /**
     * A group closed by a tap *during* a search. The unit page re-applies its open/close rule
     * on every keystroke, so this lasts only until the next character; it exists because
     * someone scrolling a long result list wants to shut the group they just read.
     */
    var closedWhileSearching by remember { mutableStateOf(setOf<String>()) }
    var extrasOpen by remember { mutableStateOf(false) }
    var unplacedOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var odoText by remember { mutableStateOf("") }
    var noiseLine by remember { mutableStateOf("never run") }
    var restoring by remember { mutableStateOf(false) }

    // A note about something that happened on the link — a restart the app asked for, a
    // restore — goes stale the moment the link changes. On the bench the page spent the rest
    // of a session saying "restarting…" about a unit that had been back and re-read for
    // minutes. Two things conspired: opening the monitor page below returns from this
    // composable and disposes everything after this point, so the transition tracking has to
    // sit above it if it is going to see the link come back; and it has to fire on both edges,
    // because a note about a link that just dropped is as dead as one about a link that just
    // returned.
    val linked = linkState is BleLinkState.Ready
    var wasLinked by remember { mutableStateOf(linked) }
    LaunchedEffect(linkState) {
        if (linked != wasLinked) note = ""
        wasLinked = linked
    }

    // The unit's performance monitor, taken the way the old link panel was: it needs the whole
    // screen for the memory and partition figures, it is reached from the last row of the
    // scroll, and because it is the only thing composed while it is open, its polling stops
    // the moment the user is back.
    var showPerf by remember { mutableStateOf(false) }
    if (showPerf) {
        PerfMonitorScreen(
            store = store,
            link = link,
            timeNote = timeNote,
            onBack = { showPerf = false },
        )
        return
    }

    val ready = linked
    val groups = schema?.groups ?: emptyList()

    // The read follows the link: pair, and the tab pulls; re-entering the tab inside the TTL
    // does not re-pull (the plan's 60 s rule).
    LaunchedEffect(linkState, schema) {
        if (linkState is BleLinkState.Ready && schema != null) store.syncIfNeeded()
    }
    LaunchedEffect(query) { closedWhileSearching = emptySet() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        note = if (uri == null) "" else try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(store.export().toByteArray(Charsets.UTF_8))
            }
            "written to ${uri.lastPathSegment}"
        } catch (e: Exception) {
            "export failed: ${e.message}"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            restoring = true
            val text = try {
                context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                null
            }
            note = when {
                text == null -> "could not read that file"
                // The report knows how to say what happened; the page only draws it.
                else -> store.restore(text).summary
            }
            restoring = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(store, schema, state, linkState)
        SyncBar(store, state, ready)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            HudInput(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = "search settings",
            )
            if (schema != null && schema.advancedKey.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HudToggle(
                        on = state.advanced,
                        label = "advanced mode",
                        onToggle = { store.setAdvanced(it) },
                    )
                    Text("advanced", style = HudeCfgNote(), color = HudColors.TextDim)
                }
            }
        }

        if (state.rebootPending.isNotEmpty()) {
            RebootBar(store, state.rebootPending) { note = it }
        }

        if (note.isNotEmpty()) {
            Text(
                note,
                style = HudeCfgHelp(),
                color = HudColors.Cyan,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // A refusal with no row to carry it — the advanced switch is the only one, and it
        // lives in the header rather than in a card.
        state.error?.let {
            Text(
                it,
                style = HudeCfgHelp(),
                color = HudColors.Red,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val unitEnabled = ready && state.sync is SyncPhase.Ready
        if (schema == null) {
            Text(
                "The settings schema is missing from the app. It is generated from the " +
                    "firmware's own tables (scripts/gen_config_schema.py); without it this " +
                    "page has no idea what the unit's settings are, so it shows nothing.",
                style = HudeCfgHelp(),
                color = HudColors.Amber,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        val searching = SettingsLayout.words(query).isNotEmpty()
        val shown = SettingsLayout.groupsShown(groups, query, state.advanced)
        val autoOpen = SettingsLayout.openGroups(open, query, groups, state.advanced)
        fun isOpen(id: String) = if (searching) id in autoOpen && id !in closedWhileSearching
        else open == id

        // Nothing has been read from the unit yet, so there are no values to draw. The schema
        // knows every label, but each one would be a blank box that invites a tap and then
        // refuses it — which is what this page looked like on the bench with no link, and it
        // read as a broken page rather than an unpaired one.
        if (state.rows.isEmpty()) {
            Text(
                if (ready) "Reading the unit…" else
                    "Nothing to show yet. Pair the unit from the Vehicles page, and this page " +
                    "fills in with its settings.",
                style = HudeCfgHelp(),
                color = HudColors.TextDim,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        if (searching && shown.isEmpty()) {
            Text(
                "no setting matches \"$query\". The box looks at each row's label, its " +
                    "firmware key and its unit — a word you can see on the row is a word that " +
                    "finds it.",
                style = HudeCfgNote(),
                color = HudColors.Cyan,
                modifier = Modifier.padding(16.dp),
            )
        }

        val widthDp = LocalConfiguration.current.screenWidthDp
        val columns = SettingsLayout.columns(widthDp)
        val unplaced = SettingsLayout.unlabelledBlock(state, query)

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    if (searching) {
                        "${shown.sumOf { g -> g.cards.sumOf { c -> c.items.size } }} of " +
                            "${groups.sumOf { g -> g.cards.sumOf { c -> c.items.size } }} settings " +
                            "match \"$query\""
                    } else {
                        "${groups.size} groups · ${groups.sumOf { g -> g.cards.sumOf { c -> c.items.size } }} " +
                            "settings · tap a group to open it"
                    },
                    style = HudeCfgNote(),
                    color = HudColors.TextDim,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                )
            }

            for (group in shown) {
                val groupOpen = isOpen(group.id)
                item {
                    GroupSummary(
                        group = group,
                        hits = group.cards.sumOf { card ->
                            if (SettingsLayout.cardShown(card, query, state.advanced))
                                SettingsLayout.rowsOf(card, query, state.advanced).size else 0
                        },
                        total = group.cards.sumOf { it.items.size },
                        open = groupOpen,
                        onClick = {
                            if (searching) {
                                closedWhileSearching =
                                    if (groupOpen) closedWhileSearching + group.id
                                    else closedWhileSearching - group.id
                            } else {
                                open = SettingsLayout.tapOpen(open, group.id)
                            }
                        },
                    )
                }
                if (groupOpen) {
                    items(SettingsLayout.pack(SettingsLayout.blocksFor(group, state, query, state.advanced), columns)) { rowOfCards ->
                        CardRow(
                            row = rowOfCards,
                            state = state,
                            schema = schema,
                            enabled = unitEnabled,
                            store = store,
                            scope = scope,
                            confirm = confirm,
                            onConfirm = { confirm = it },
                            onNote = { note = it },
                            odoText = odoText,
                            onOdo = { odoText = it },
                            noiseLine = noiseLine,
                            onNoise = { noiseLine = it },
                        )
                    }
                }
            }

            // Values the unit streams that the schema describes as readings rather than
            // settings — the ambient light ADC, the ESP core and build versions. They are read
            // only on purpose: a stepper next to a compiled-in version string only invites a
            // write the unit will spend a flash cycle refusing.
            if (state.unitStatus.isNotEmpty()) {
                item {
                    SimpleSummary("Values the unit reports", state.unitStatus.size, extrasOpen) {
                        extrasOpen = !extrasOpen
                    }
                }
                if (extrasOpen) {
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            state.unitStatus.forEach { (spec, value) ->
                                // The asset's kinds are lower-case ("int", "text"), and the
                                // store has already turned a whole-number reading into an Int so
                                // this row draws "2678" and not "2678.0".
                                val numeric = spec.kind.equals("int", ignoreCase = true)
                                SettingsRow(
                                    item = SchemaItem(
                                        key = spec.key,
                                        kind = if (numeric) ValueKind.INT else ValueKind.STR,
                                        control = RowControl.TEXT,
                                        label = spec.label,
                                    ),
                                    view = RowView(
                                        value,
                                        null,
                                        RowStatus.SYNCED,
                                        null,
                                    ),
                                    imperial = false,
                                    unitSwap = emptyMap(),
                                    enabled = false,
                                    onEdit = { _, _ -> },
                                    readOnlyHint = if (spec.live) {
                                        "read from the unit while it runs — not a setting"
                                    } else {
                                        "compiled into the firmware — not a setting"
                                    },
                                )
                            }
                            // The one extra that is a real setting (the fuel calibration table)
                            // has no row of its own here, so the page says where it lives rather
                            // than pretending the card is a reading.
                            val hint = schema.extras.keys
                                .mapNotNull { schema.extra(it) }
                                .filter { it.editable }
                                .joinToString(", ") { it.label }
                            if (hint.isNotEmpty()) {
                                Text(
                                    "editable extras: $hint",
                                    style = HudeCfgNote(),
                                    color = HudColors.TextDim,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            // A firmware that grew a setting nobody regenerated the schema for streams a key
            // with no row. It cannot be placed in a group — the schema is what does the placing
            // — so it gets a group of its own, and it is in the export either way. A setting
            // that cannot be found looks exactly like a setting that does not exist, which is
            // why this is not left to the search box.
            if (unplaced != null) {
                item {
                    SimpleSummary(
                        unplaced.card.title,
                        unplaced.body.size,
                        unplacedOpen,
                    ) { unplacedOpen = !unplacedOpen }
                }
                if (unplacedOpen) {
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            unplaced.card.hint?.let {
                                Text(
                                    it,
                                    style = HudeCfgHelp(),
                                    color = HudColors.Amber,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                )
                            }
                            unplaced.body.forEach { renderable ->
                                if (renderable is RenderRow) {
                                    SettingsRow(
                                        item = renderable.item,
                                        view = state.rows[renderable.item.key],
                                        imperial = state.imperial,
                                        unitSwap = schema.imperial,
                                        enabled = unitEnabled,
                                        onEdit = { x, y -> store.edit(renderable.item, x, y) },
                                        onReset = { store.reset(renderable.item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The last row of the scroll, where the unit page keeps its own performance panel.
            item {
                SimpleSummary("PERFORMANCE MONITOR", 0, false) { showPerf = true }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        BottomBar(
            store = store,
            state = state,
            ready = ready,
            scope = scope,
            onNote = { note = it },
            confirm = confirm,
            onConfirm = { confirm = it },
            onExport = { exportLauncher.launch("dashboardpp-settings.json") },
            onRestore = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            restoring = restoring,
        )
    }
}

// ---------------------------------------------------------------------------
// The accordion
// ---------------------------------------------------------------------------

/**
 * One group's header row: the unit page's badge, its title, how many settings it holds, and
 * the chevron that says whether it is open.
 *
 * The count changes meaning while a search is running — "12 of 23" is the answer to "how much
 * of this group did my typing find", and it is also the answer to "is this group worth opening
 * again after I clear the box", which the old tab strip never had to answer.
 */
@Composable
private fun GroupSummary(
    group: SchemaGroup,
    hits: Int,
    total: Int,
    open: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(shape)
            .background(if (open) HudColors.CyanDim else HudColors.Panel)
            .border(1.dp, if (open) HudColors.Cyan else HudColors.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        groupIcon(group.icon)?.let { res ->
            // Tinted in the UI layer rather than in the drawable: the same badge has to read
            // as a dim shape while a group is closed and as a lit one when it is open, and a
            // single white asset can do both.
            Image(
                painter = painterResource(res),
                contentDescription = group.title,
                colorFilter = ColorFilter.tint(
                    if (open) HudColors.Cyan else HudColors.TextDim,
                ),
                modifier = Modifier.width(18.dp).height(18.dp),
            )
        }
        Text(
            group.title.uppercase(),
            style = HudeSectionCaps(),
            color = if (open) HudColors.TextPrimary else HudColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (total == 0) "no settings" else "$hits of $total",
            style = HudeCfgNote(),
            color = HudColors.TextDim,
        )
        Text(if (open) "▾" else "▸", style = HudeParamName(), color = HudColors.Cyan)
    }
}

/** A summary row for the two things that are not schema groups: readings, and the monitor. */
@Composable
private fun SimpleSummary(title: String, count: Int, open: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            .clip(shape)
            .background(HudColors.Panel)
            .border(1.dp, HudColors.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            title,
            style = HudeSectionCaps(),
            color = HudColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text("$count", style = HudeCfgNote(), color = HudColors.TextDim)
        }
        Text(if (open) "▾" else "▸", style = HudeParamName(), color = HudColors.Cyan)
    }
}

/**
 * The drawable for a group's badge, by the name the schema carries.
 *
 * Written as a table rather than looked up by resource name, so an asset generated by a newer
 * firmware with a group nobody has drawn a badge for is a group with no badge — not a crash,
 * and not a placeholder icon that claims the app knows what the group is.
 */
private fun groupIcon(icon: String?): Int? = when (icon) {
    "system" -> R.drawable.ic_grp_system
    "display" -> R.drawable.ic_grp_display
    "sensors" -> R.drawable.ic_grp_sensors
    "layout" -> R.drawable.ic_grp_layout
    "more" -> R.drawable.ic_grp_more
    else -> null
}

/**
 * The cards of one row of the grid, side by side and weighted equally.
 *
 * This is the unit page's `repeat(auto-fit, minmax(min(240px,100%),1fr))`: equal fractions, a
 * card per track, and a card as tall as its own rows. The packing itself is
 * [SettingsLayout.pack], which is where the tests are.
 */
@Composable
private fun CardRow(
    row: List<RenderBlock>,
    state: ConfigStore.SettingsState,
    schema: ConfigSchema,
    enabled: Boolean,
    store: ConfigStore,
    scope: CoroutineScope,
    confirm: String?,
    onConfirm: (String?) -> Unit,
    onNote: (String) -> Unit,
    odoText: String,
    onOdo: (String) -> Unit,
    noiseLine: String,
    onNoise: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        row.forEach { block ->
            Column(Modifier.weight(1f)) {
                CardHead(block.card.title)
                block.card.hint?.let {
                    Text(
                        it,
                        style = HudeCfgNote(),
                        color = HudColors.TextDim,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                block.body.forEach { renderable ->
                    when (renderable) {
                        is RenderSection -> SectionHead(renderable.title)
                        is RenderWidget -> WidgetControl(
                            widget = renderable.widget,
                            store = store,
                            enabled = enabled,
                            confirm = confirm,
                            onConfirm = onConfirm,
                            onNote = onNote,
                            odoText = odoText,
                            onOdo = onOdo,
                            noiseLine = noiseLine,
                            onNoise = onNoise,
                            scope = scope,
                        )
                        is RenderRow -> SettingsRow(
                            item = renderable.item,
                            view = state.rows[renderable.item.key],
                            imperial = state.imperial,
                            unitSwap = schema.imperial,
                            enabled = enabled,
                            onEdit = { x, y -> store.edit(renderable.item, x, y) },
                            onReset = { store.reset(renderable.item) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The bottom bar
// ---------------------------------------------------------------------------

/**
 * The actions that belong to the page rather than to one card, kept where the thumb already
 * is: write what is waiting, restart the unit, take the config off it, put one back.
 *
 * **SAVE** is not how a row is saved — a row writes itself, and reports what happened to it.
 * What is left in the phone's RAM is a debounce window's worth of edits, and a driver who has
 * changed four rows and is about to walk outside wants them on the unit, not in a timer that a
 * dropped link will undo. The button says how many rows are waiting, and after writing, says
 * what is left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBar(
    store: ConfigStore,
    state: ConfigStore.SettingsState,
    ready: Boolean,
    scope: CoroutineScope,
    onNote: (String) -> Unit,
    confirm: String?,
    onConfirm: (String?) -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    restoring: Boolean,
) {
    Box(
        Modifier.fillMaxWidth()
            .background(HudColors.Bg)
            .border(1.dp, HudColors.Hairline, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HudButton(
                label = when {
                    state.pending > 0 -> "SAVE ${state.pending}"
                    state.sync is SyncPhase.Ready -> "SAVED"
                    else -> "SAVE"
                },
                style = HudButtonStyle.PRIMARY,
                enabled = ready && state.pending > 0,
                onClick = {
                    scope.launch {
                        noteOfFlush(store.flush())?.let(onNote)
                    }
                },
            )
            HudButton(
                label = "RESTART",
                style = HudButtonStyle.SECONDARY,
                enabled = ready,
                onClick = {
                    scope.launch {
                        val r = store.action("reboot")
                        if (r.ok || r.error == "timeout") store.clearRebootPending()
                        onNote(
                            if (r.ok || r.error == "timeout") "restarting…"
                            else "restart refused: ${r.error}",
                        )
                    }
                },
            )
            HudButton(
                label = "BACKUP",
                style = HudButtonStyle.SECONDARY,
                enabled = state.sync is SyncPhase.Ready,
                onClick = onExport,
            )
            HudButton(
                label = if (restoring) "RESTORING…" else "RESTORE",
                style = HudButtonStyle.SECONDARY,
                enabled = state.sync is SyncPhase.Ready && !restoring,
                onClick = onRestore,
            )
            HudButton(
                label = if (confirm == "factory.reset") "CONFIRM FACTORY RESET" else "FACTORY RESET",
                style = HudButtonStyle.DANGER,
                enabled = ready,
                onClick = {
                    if (confirm != "factory.reset") {
                        onConfirm("factory.reset")
                        onNote("that wipes every setting on the unit — press again to confirm")
                    } else {
                        scope.launch {
                            val r = store.action("factory.reset")
                            onConfirm(null)
                            onNote(
                                if (r.ok || r.error == "timeout") "factory reset done — re-reading"
                                else "factory reset refused: ${r.error}",
                            )
                            store.syncIfNeeded(force = true)
                        }
                    }
                },
            )
        }
    }
}

/**
 * What a SAVE should say, in the words the row itself would use.
 *
 * Null means "nothing worth saying": every waiting row wrote cleanly, which is the button's
 * normal ending and does not need a line under it. Anything that did not reach the unit does —
 * the unit refused it, or the link went away mid-write — and the reason is written on the row,
 * so this line only refuses to claim a clean sweep.
 */
private fun noteOfFlush(flush: ConfigStore.Flush): String? = when {
    flush.rows == 0 -> null
    flush.notWritten > 0 ->
        "wrote ${flush.rows - flush.notWritten} · ${flush.notWritten} did not reach the unit " +
            "— the rows say why"
    else -> "wrote ${flush.rows} row(s) to the unit"
}

// ---------------------------------------------------------------------------
// The header and the bars above the scroll
// ---------------------------------------------------------------------------

@Composable
private fun Header(
    store: ConfigStore,
    schema: ConfigSchema?,
    state: ConfigStore.SettingsState,
    linkState: BleLinkState,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DASHBOARD SETTINGS", style = HudeHeading(), color = HudColors.TextPrimary)
            Spacer(Modifier.width(12.dp))
            val unit = store.buildVersion
            Text(
                "unit " + (if (unit.isEmpty()) "—" else "V$unit") +
                    "  ·  schema " + (schema?.firmware?.let { "V$it" } ?: "missing"),
                style = HudeCfgNote(),
                color = HudColors.TextDim,
            )
        }
        // No button here. Pairing is something you do to a vehicle, and the Vehicles page is
        // where a vehicle is picked up; a second way to start pairing, on a page that only
        // makes sense once a unit is already linked, was a second story about how the app ever
        // finds a unit.
        val (line, color) = when (val link = linkState) {
            is BleLinkState.Ready ->
                "linked to ${link.name} · MTU ${link.mtu}" to HudColors.Green
            is BleLinkState.Pairing ->
                "pairing — confirm the code shown on the dashboard" to HudColors.Cyan
            is BleLinkState.Connecting -> "connecting to ${link.name}…" to HudColors.Cyan
            is BleLinkState.Failed -> "link failed: ${link.reason}" to HudColors.Red
            BleLinkState.Idle ->
                "no Bluetooth link — the unit's settings live on the unit, and the Vehicles " +
                    "page is where you pair one" to HudColors.TextDim
        }
        Text(line, style = HudeCfgNote(), color = color, modifier = Modifier.padding(top = 4.dp))
        if (state.missingKeys.isNotEmpty()) {
            Text(
                "${state.missingKeys.size} keys the schema knows are missing from the read: " +
                    state.missingKeys.take(6).joinToString(", ") +
                    if (state.missingKeys.size > 6) "…" else "",
                style = HudeCfgNote(),
                color = HudColors.Amber,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SyncBar(store: ConfigStore, state: ConfigStore.SettingsState, ready: Boolean) {
    val (text, color) = when (val sync = state.sync) {
        is SyncPhase.Running -> "reading the unit's settings… ${sync.chunks} chunks in" to HudColors.Cyan
        is SyncPhase.Ready ->
            // An xy row is one row that streams two keys, so "keys" is always a little more
            // than "rows"; the count that means something is whether a row the schema
            // describes came back at all.
            (
                "read ${sync.keys} keys (${sync.rows} table rows) in ${sync.chunks} chunks" +
                    if (!ready) " · read before the link dropped" else ""
                ) to
                (if (state.missingKeys.isEmpty()) HudColors.Green else HudColors.Amber)
        is SyncPhase.Failed -> sync.reason to HudColors.Red
        SyncPhase.Idle ->
            if (ready) "not read yet" to HudColors.Amber
            else "the settings are read from the unit over Bluetooth" to HudColors.TextDim
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text(text, style = HudeFormLabel(), color = color, modifier = Modifier.weight(1f))
        HudButton(
            label = if (state.sync is SyncPhase.Failed) "RETRY" else "SYNC",
            style = HudButtonStyle.SECONDARY,
            enabled = ready && state.sync !is SyncPhase.Running,
            onClick = { store.syncIfNeeded(force = true) },
        )
    }
}

/**
 * The bar a boot-only change leaves behind. `DISPLAY_ROTATION` and friends are read once at
 * boot, so the unit will draw the old geometry until it restarts — the row saved, and the
 * change is still not visible. Saying so is the whole point.
 */
@Composable
private fun RebootBar(
    store: ConfigStore,
    pending: Set<String>,
    onNote: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            "${pending.size} boot-only change${if (pending.size > 1) "s" else ""}: " +
                pending.joinToString(", "),
            style = HudeCfgNote(),
            color = HudColors.Amber,
            modifier = Modifier.weight(1f),
        )
        HudButton(
            label = "RESTART NOW",
            style = HudButtonStyle.WARNING,
            onClick = {
                scope.launch {
                    val result = store.action("reboot")
                    // The unit dies before it can answer; a timeout is the success case.
                    if (result.ok || result.error == "timeout") store.clearRebootPending()
                    onNote(
                        if (result.ok || result.error == "timeout") {
                            "restarting the unit…"
                        } else {
                            "restart refused: ${result.error}"
                        },
                    )
                }
            },
        )
    }
}

@Composable
internal fun CardHead(title: String) {
    Text(
        title,
        style = HudeSectionCaps(),
        color = HudColors.Cyan,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
}

@Composable
internal fun SectionHead(title: String) {
    Text(
        title,
        style = HudeParamName(),
        color = HudColors.TextDim,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}
