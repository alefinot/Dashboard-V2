package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.R
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudAccordion
import com.alefinot.dashboardpp.ui.components.HudAccordionBody
import com.alefinot.dashboardpp.ui.components.HudAccordionHeader
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.screens.settings.ConfigSchema
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore
import com.alefinot.dashboardpp.ui.screens.settings.PerfMonitorCard
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
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.HudTouch
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.ui.theme.HudeParamName
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
    /** The bin in the action line was tapped; the confirm box is up. */
    var factoryAsk by remember { mutableStateOf(false) }

    // A note about something that happened on the link — a restart the app asked for, a
    // restore — goes stale the moment the link changes. On the bench the page spent the rest
    // of a session saying "restarting…" about a unit that had been back and re-read for
    // minutes. It has to fire on both edges, because a note about a link that just dropped is
    // as dead as one about a link that just returned. It had a second reason to sit this high
    // once: the monitor route below returned from this composable and disposed everything under
    // the early return. The monitor is a card in the scroll now, and there is no route left to
    // dispose it.
    val linked = linkState is BleLinkState.Ready
    var wasLinked by remember { mutableStateOf(linked) }
    LaunchedEffect(linkState) {
        if (linked != wasLinked) note = ""
        wasLinked = linked
    }

    // The unit's performance monitor, now one of the scroll's own cards rather than a page it
    // opened. It reads while it is open and stops when it closes, so nothing here has to remember
    // to leave a route: the card is the last block of the scroll, under the header with its five
    // action glyphs, where the unit's page kept its own performance panel.
    var perfOpen by remember { mutableStateOf(false) }

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

    // `.sticky-top`: the page's own header — its title, its search box, the five action glyphs —
    // held above the scroll the way the page holds them above its 198 rows. `hudPage` is
    // `.main-content`: a 920 dp column with `margin: 0 auto`, which is the thing this
    // page never had - every control used to sit at the far edge of a 1920 px screen
    // from the label it belonged to.
    //
    // There is no Advanced Mode control here any more. The header card that carried it cost
    // 38 dp of a 748 dp scroll viewport, and the maintainer had it removed on 2026-11-08 with
    // the instruction to show everything: the gate below is simply open, and `ADV_MODE` stays a
    // firmware row that a config backup still writes back (see [ConfigStore]).
    val advanced = true

    Column(Modifier.hudPage()) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = HudPage.Gutter)
                .padding(top = 6.dp, bottom = 4.dp),
        ) {
            HudPageHeader(title = "Dashboard++ Setup")
            // Three lines and nothing else: the page's name, the search box with its SYNC, and
            // the five action glyphs. The maintainer had the rest of the header's chrome taken
            // out on 2026-11-11 — the identity pill (`unit V2.0.4 · schema V2.0.4 · linked to
            // Motosbu · MTU 517`), which had already swallowed the separate link line to save one
            // line, and the two notes that used to sit under the icons (`read 182 keys (178 table
            // rows) in 6 chunks`, `4 groups · 156 settings · tap a group to open it`).
            //
            // What that costs, written down so nobody rediscovers it by surprise: the page no
            // longer says which firmware it is talking to, which schema it expects, that the link
            // is up, how many keys the read covered, or that a read came back before the link
            // dropped — and a *failed* read (`SyncPhase.Failed`) no longer prints its reason, so
            // it shows only as the search row's button turning to `RETRY`. Typing no longer prints
            // `N settings and M cards match "gps"` either; what the box found is said by the groups
            // that open and the ones that vanish. What is still drawn here is only what happened
            // since the last look: the flush and restart notes, a refused action, the reboot
            // prompt, and keys the unit left out of the read.
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                HudInput(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    style = HudInputStyle.SEARCH,
                    placeholder = "Search configuration parameters…",
                    leading = {
                        Image(
                            HudIcons.Search,
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(HudColors.TextDim),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                HudButton(
                    label = if (state.sync is SyncPhase.Failed) "RETRY" else "SYNC",
                    style = HudButtonStyle.SECONDARY,
                    enabled = ready && state.sync !is SyncPhase.Running,
                    onClick = { store.syncIfNeeded(force = true) },
                )
            }
            // The five actions that belong to no row, drawn on one line under the search box. The
            // header is the part of the page that does not scroll, so these five hold one y — the
            // note lines under them only appear when something has just happened, and they do not
            // push the glyphs around any more.
            ActionBar(
                store = store,
                state = state,
                ready = ready,
                scope = scope,
                onNote = { note = it },
                onExport = { exportLauncher.launch("dashboardpp-settings.json") },
                onRestore = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                restoring = restoring,
                onFactoryReset = { factoryAsk = true },
            )
            // Only what happened since the last look is drawn under the icon line, and only when
            // something is actually there does that block need its own gap from the glyphs.
            if (
                state.missingKeys.isNotEmpty() || state.rebootPending.isNotEmpty() ||
                note.isNotEmpty() || state.error != null
            ) {
                Spacer(Modifier.height(4.dp))
            }
            if (state.missingKeys.isNotEmpty()) {
                Text(
                    "${state.missingKeys.size} keys the schema knows are missing from the " +
                        "read: " + state.missingKeys.take(6).joinToString(", ") +
                        if (state.missingKeys.size > 6) "…" else "",
                    style = HudeCfgNote(),
                    color = HudColors.Amber,
                )
            }
            if (state.rebootPending.isNotEmpty()) {
                RebootBar(store, state.rebootPending) { note = it }
            }
            if (note.isNotEmpty()) {
                Text(note, style = HudeCfgHelp(), color = HudColors.Cyan)
            }
            // A refusal with no row to carry it. This used to be the advanced switch's case and
            // nothing else's; now that the header has no control, it is what a failed backup or
            // restore reports.
            state.error?.let {
                Text(it, style = HudeCfgHelp(), color = HudColors.Red)
            }
        }

        val unitEnabled = ready && state.sync is SyncPhase.Ready
        if (schema == null) {
            Text(
                "Settings schema missing from the app.",
                style = HudeCfgHelp(),
                color = HudColors.Amber,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        val searching = SettingsLayout.words(query).isNotEmpty()
        val shown = SettingsLayout.groupsShown(groups, query, advanced)
        val autoOpen = SettingsLayout.openGroups(open, query, groups, advanced)
        fun isOpen(id: String) = if (searching) id in autoOpen && id !in closedWhileSearching
        else open == id

        // Nothing has been read from the unit yet, so there are no values to draw. The schema
        // knows every label, but each one would be a blank box that invites a tap and then
        // refuses it — which is what this page looked like on the bench with no link, and it
        // read as a broken page rather than an unpaired one.
        if (state.rows.isEmpty()) {
            Text(
                if (ready) "Reading the unit…" else
                    "Nothing read from the unit yet — pair it from the Vehicles page.",
                style = HudeCfgHelp(),
                color = HudColors.TextDim,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        if (searching && shown.isEmpty()) {
            Text(
                "no setting matches \"$query\"",
                style = HudeCfgNote(),
                color = HudColors.Cyan,
                modifier = Modifier.padding(16.dp),
            )
        }

        val widthDp = LocalConfiguration.current.screenWidthDp
        // The cards are packed for the *column*, not the tablet: 920 dp is the page's
        // width, and `SettingsLayout.columns` is the page's `auto-fit` rule.
        //
        // Two tracks, not three. The page fits three 284 px cards because a browser row
        // is a 70 px box and a 12 px label; this app's controls are the size a finger
        // needs - a stepper is ~140 dp, a text box 230 dp - so a third of the column
        // leaves the label three lines deep and pushes the control off its own row. Two
        // tracks keep a card at the ~420 dp it had when the page was the whole 1920 px
        // screen, which is the width the rows were designed against.
        val columns = SettingsLayout.columns(widthDp.coerceAtMost(HudPage.Width.value.toInt()))
            .coerceAtMost(2)
        val unplaced = SettingsLayout.unlabelledBlock(state, query)

        // The list is the only child that flexes. The header above it - title, search row and the
        // five action glyphs - keeps its own height, and the list takes everything left, which is
        // what stopped the action line being squeezed to zero pixels when it lived under a
        // `fillMaxSize()` list at the foot of the page.
        // Its own top padding is what keeps the first group clear of the icon line now that the
        // count line that used to sit between them is gone.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = HudPage.Gutter),
            contentPadding = PaddingValues(top = 10.dp),
        ) {
            for (group in shown) {
                val groupOpen = isOpen(group.id)
                // One `details` per group: the summary band and its cards are a single
                // 12 dp sheet, the way the unit's page draws them. Only the open group is
                // built, so a 78-row group costs what opening it costs and nothing more.
                item(key = "group-${group.id}") {
                    // The group's cards, packed the way the page's grid packs them: a card
                    // takes one track of the row, and a card that is the only one in its row
                    // fills the width - which is what `auto-fit` does with a track nobody
                    // else claims. A block whose control needs the room keeps a row of its
                    // own (see SettingsLayout.pack).
                    val blocks =
                        SettingsLayout.blocksFor(group, state, query, advanced)
                    HudAccordion(open = groupOpen, modifier = Modifier.padding(bottom = 14.dp)) {
                        HudAccordionHeader(
                            title = group.title,
                            badge = groupIcon(group.icon),
                            meta = SettingsLayout.groupMeta(group, query, advanced),
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
                        HudAccordionBody(open = groupOpen) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (rowOfCards in SettingsLayout.pack(blocks, columns)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        rowOfCards.forEach { block ->
                                            CardBlock(
                                                block = block,
                                                modifier = Modifier.weight(1f),
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
                            }
                        }
                    }
                }
            }

            // Values the unit streams that the schema describes as readings rather than
            // settings — the ambient light ADC, the ESP core and build versions. They are read
            // only on purpose: a stepper next to a compiled-in version string only invites a
            // write the unit will spend a flash cycle refusing.
            if (state.unitStatus.isNotEmpty()) {
                item(key = "readings") {
                    HudAccordion(open = extrasOpen, modifier = Modifier.padding(bottom = 14.dp)) {
                        HudAccordionHeader(
                            title = "Values the unit reports",
                            meta = "${state.unitStatus.size}",
                            open = extrasOpen,
                            onClick = { extrasOpen = !extrasOpen },
                        )
                        HudAccordionBody(open = extrasOpen) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(
                                    Modifier.fillMaxWidth().hudCard()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    state.unitStatus.forEach { (spec, value) ->
                                        // The asset's kinds are lower-case ("int", "text"), and
                                        // the store has already turned a whole-number reading
                                        // into an Int so this row draws "2678" and not "2678.0".
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
                                                "live reading, not a setting"
                                            } else {
                                                "fixed in firmware, not a setting"
                                            },
                                        )
                                    }
                                    // The one extra that is a real setting (the fuel calibration
                                    // table) has no row of its own here, so the page says where
                                    // it lives rather than pretending the card is a reading.
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
                }
            }

            // A firmware that grew a setting nobody regenerated the schema for streams a key
            // with no row. It cannot be placed in a group — the schema is what does the placing
            // — so it gets a group of its own, and it is in the export either way. A setting
            // that cannot be found looks exactly like a setting that does not exist, which is
            // why this is not left to the search box.
            if (unplaced != null) {
                item(key = "unplaced") {
                    HudAccordion(open = unplacedOpen, modifier = Modifier.padding(bottom = 14.dp)) {
                        HudAccordionHeader(
                            title = unplaced.card.title,
                            meta = "${unplaced.body.size}",
                            open = unplacedOpen,
                            onClick = { unplacedOpen = !unplacedOpen },
                        )
                        HudAccordionBody(open = unplacedOpen) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(
                                    Modifier.fillMaxWidth().hudCard()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    unplaced.card.hint?.let {
                                        Text(
                                            it,
                                            style = HudeCfgHelp(),
                                            color = HudColors.Amber,
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(vertical = 6.dp),
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
                                                onEdit = { x, y ->
                                                    store.edit(renderable.item, x, y)
                                                },
                                                onReset = { store.reset(renderable.item) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The last card of the scroll, where the unit page keeps its own performance panel. It
            // opens like a group now — including under a search, whose rule is that a card whose
            // title matches shows itself — and the note effect above stays in composition while it
            // does: this page no longer has a route that disposes the scroll it belongs to.
            item(key = "performance") {
                val perfSearching = searching &&
                    SettingsLayout.matches(query, "Performance Monitor") &&
                    "performance" !in closedWhileSearching
                val perfShown = if (searching) perfSearching else perfOpen
                PerfMonitorCard(
                    store = store,
                    link = link,
                    open = perfShown,
                    onToggle = {
                        if (searching) {
                            closedWhileSearching =
                                if (perfShown) closedWhileSearching + "performance"
                                else closedWhileSearching - "performance"
                        } else {
                            perfOpen = !perfOpen
                        }
                    },
                    timeNote = timeNote,
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // The bin in the line above asks first, and nothing is sent until this is answered. That is
    // what makes the fifth action safe to put on the same line as SAVE: the icon costs a finger
    // the width of a thumbnail, and the answer costs a look.
    //
    // The wording follows what `factory.reset` actually does on the unit (src/config.cpp
    // `factoryResetConfig()` + src/bt_rpc.cpp): it clears the `cfg` namespace and calls
    // `btClearBonds()`, so the settings and every pairing go, while the odometer lives in the
    // separate `dashboard` namespace and is deliberately kept.
    if (factoryAsk) {
        HudConfirmDialog(
            message = "Wipe every setting on the unit and restart it? The whole configuration " +
                "goes back to the shipped defaults, and every paired phone is forgotten — this " +
                "app will have to be paired again. The odometer is kept. Only a backup written " +
                "before this can put a setup back.",
            confirmLabel = "FACTORY RESET",
            confirmStyle = HudButtonStyle.DANGER,
            onConfirm = {
                factoryAsk = false
                scope.launch {
                    val r = store.action("factory.reset")
                    note = if (r.ok || r.error == "timeout") {
                        "factory reset done — re-reading"
                    } else {
                        "factory reset refused: ${r.error}"
                    }
                    store.syncIfNeeded(force = true)
                }
            },
            onDismiss = { factoryAsk = false },
        )
    }
}

// ---------------------------------------------------------------------------
// The cards
// ---------------------------------------------------------------------------

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
 * One card of the group's grid: `.card` itself — 8 dp corners, `rgba(22,30,46,.6)`, a 5%
 * hairline, 12x14 padding, and the card's title in `.card h4`'s 13 px/700 cyan.
 *
 * It used to draw its rows straight onto the black page with a hairline under a section
 * caption, which is why the settings page read as a wall of labels with no surfaces:
 * the unit's page puts every row group in a card, and a card is what tells the eye where
 * one group of settings ends and the next begins.
 *
 * The packing above it is [SettingsLayout.pack] - the page's
 * `repeat(auto-fit, minmax(min(240px,100%),1fr))`, one card per track.
 */
@Composable
private fun CardBlock(
    block: RenderBlock,
    modifier: Modifier,
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
    Column(
        modifier.hudCard().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        CardHead(block.card.title)
        block.card.hint?.let {
            Text(
                it,
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.padding(bottom = 4.dp),
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

// ---------------------------------------------------------------------------
// The action line
// ---------------------------------------------------------------------------

/**
 * The five actions that belong to no card, drawn as five small icons on one line, in the page's
 * `.sticky-top` header directly under the search box.
 *
 * They were words until 2026-11-11 — four in a two-by-two grid at the foot of the screen, and
 * Factory Reset as a full-width button at the end of the scroll. The words and the colours now
 * share the work: **amber** says the phone still holds rows the unit has not got, **dim** says this
 * action is not available as things stand, and a red bin says what cannot be undone.
 *
 * **SAVE** is not how a row is saved — a row writes itself, and reports what happened to it.
 * What is left in the phone's RAM is a debounce window's worth of edits, and a driver who has
 * changed four rows and is about to walk outside wants them on the unit, not in a timer that a
 * dropped link will undo. The flush glyph is that action. The count the button used to spell out
 * (`SAVE 3`) is not on the screen any more: the colour is the count — amber when anything waits,
 * and the rows themselves still say which ones and why.
 *
 * The line lives in the header, so it does not scroll: five glyphs the owner taps by position are
 * worth more at a fixed y than at the bottom of a page that grows and shrinks its own notes. It
 * was pinned at the foot until the maintainer moved it up on 2026-11-11; both places depend on the
 * same thing, that the list takes `weight(1f)` — in the old one, a `fillMaxSize()` list left the
 * bar zero pixels tall and none of these five actions could be pressed at all.
 *
 * Factory Reset came up into the line with the rest, which is the one thing the unit's page did
 * deliberately and this page now does the other way: it kept that action on a third row of its
 * own, away from the hand that taps SAVE. Up here it is further from that hand than ever, and the
 * glyph is a bin — not mistakable for its neighbours — but what really makes it safe is that it is
 * the only action on the page that asks first: the icon opens [HudConfirmDialog], and not one byte
 * reaches the unit until that is answered.
 */
@Composable
private fun ActionBar(
    store: ConfigStore,
    state: ConfigStore.SettingsState,
    ready: Boolean,
    scope: CoroutineScope,
    onNote: (String) -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    restoring: Boolean,
    onFactoryReset: () -> Unit,
) {
    // One line, five equal slots, the same left edge as the search box it sits under (the header
    // column owns the gutter padding). Five 48 dp boxes cost less height than the two rows of word
    // buttons they replaced did, which is the part that matters on a phone: every dp this line
    // keeps is a dp of settings the owner has to scroll for.
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val pending = state.pending
        val synced = state.sync is SyncPhase.Ready
        ActionSlot(
            glyph = HudIcons.Flush,
            // The number the button used to show lives here instead, where it costs the eye
            // nothing and a driver who cannot see the colour still gets it.
            description = if (pending > 0) {
                "write the $pending waiting row${if (pending == 1) "" else "s"} to the unit now"
            } else {
                "write the rows still waiting in this phone to the unit — nothing is waiting"
            },
            ink = when {
                pending > 0 && ready -> HudColors.Amber
                else -> HudColors.TextDim
            },
            enabled = ready && pending > 0,
            onClick = {
                scope.launch {
                    noteOfFlush(store.flush())?.let(onNote)
                }
            },
        )
        ActionSlot(
            glyph = HudIcons.Power,
            description = "restart the unit",
            ink = if (ready) HudColors.Cyan else HudColors.TextDim,
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
        ActionSlot(
            glyph = HudIcons.ExportFile,
            description = "back up every setting to a file",
            ink = if (synced) HudColors.Cyan else HudColors.TextDim,
            enabled = synced,
            onClick = onExport,
        )
        ActionSlot(
            glyph = HudIcons.ImportFile,
            description = if (restoring) "restoring the settings from that file…"
            else "restore the settings from a backup file",
            ink = if (synced && !restoring) HudColors.Cyan else HudColors.TextDim,
            enabled = synced && !restoring,
            onClick = onRestore,
        )
        ActionSlot(
            glyph = HudIcons.Bin,
            description = "factory reset the unit — wipes every setting",
            ink = if (ready) HudColors.Red else HudColors.TextDim,
            enabled = ready,
            onClick = onFactoryReset,
        )
    }
}

/**
 * One icon slot of [ActionBar]: a glyph on the app's 48 dp floor, and the words the removed
 * labels used to carry.
 *
 * A `RowScope` extension because the slot claims an equal share of the line with `weight`, and
 * that share is what keeps the five actions one apart no matter how narrow the phone is.
 *
 * 48 dp and not [HudTouch.Button]'s 36: a labelled button may be small, because the label tells
 * you what it is. A lone glyph cannot, so the target is the largest of the app's two floors and
 * the name goes where a finger cannot read it — into `contentDescription`, and into
 * `onClickLabel`, which is what TalkBack reads as the action a double-tap will take.
 */
@Composable
private fun RowScope.ActionSlot(
    glyph: ImageVector,
    description: String,
    ink: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.weight(1f).heightIn(min = HudTouch.Min)
            .hudPressable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            glyph,
            // Five glyphs and no words anywhere on the bar. Without a description on each, a
            // screen reader reads this bar as "button, button, button, button, button" — the
            // same complaint `uiautomator` wrote down about the page's switches.
            contentDescription = description,
            colorFilter = ColorFilter.tint(ink),
            modifier = Modifier.size(22.dp),
        )
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
// The reboot prompt
// ---------------------------------------------------------------------------

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

/**
 * `.card h4`: 13 px, 700, `--accent-cyan`, 0.2 px tracking, 5 px under the title.
 *
 * It used to be `.section-title` (11 px caps) with a hairline under it, drawn straight onto
 * the page. The page's cards name themselves with an `h4`, and the difference is what makes
 * a card read as a card instead of as a section of a long form.
 */
@Composable
internal fun CardHead(title: String) {
    Text(
        title,
        style = HudeSectionTitle(0.2f / 13f),
        color = HudColors.Cyan,
        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
    )
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
