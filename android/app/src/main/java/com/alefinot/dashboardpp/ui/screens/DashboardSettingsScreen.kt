package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.R
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudCaptionedAction
import com.alefinot.dashboardpp.ui.components.HudAccordion
import com.alefinot.dashboardpp.ui.components.HudAccordionBody
import com.alefinot.dashboardpp.ui.components.HudAccordionHeader
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.LocalEnterActive
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.screens.settings.ConfigSchema
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore
import com.alefinot.dashboardpp.ui.screens.settings.PerfMonitorCard
import com.alefinot.dashboardpp.ui.screens.settings.RenderBlock
import com.alefinot.dashboardpp.ui.screens.settings.RenderReading
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

    // An armed second press does not get to outlive the page it was armed on. The bar's pages stay
    // composed while a different one is on screen — the pager keeps them, which is why the scroll
    // position survives a tab change — so `confirm` survived one too: on 2026-11-12 the bench armed
    // `RESET TRIP`, went to App Settings, came back, and found a button one press away from
    // `trip.reset` with no memory of why anyone wanted the trip gone. Leaving view is the disarm,
    // whatever armed it. The two-press safety stays exactly where it was, on this page, where the
    // press that arms and the press that fires are in front of the same eyes.
    val inView = LocalEnterActive.current
    LaunchedEffect(inView) { if (!inView) confirm = null }

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
            // Three lines and nothing else: the page's name, its search box, and the five action
            // glyphs with one word under each. The maintainer had the rest of the header's chrome
            // taken out on 2026-11-11 —
            // the identity pill (`unit V2.0.4 · schema V2.0.4 · linked to Motosbu · MTU 517`),
            // which had already swallowed the separate link line to save one line, and the two
            // notes that used to sit under the icons (`read 182 keys (178 table rows) in 6 chunks`,
            // `4 groups · 156 settings · tap a group to open it`). The standing `SYNC` button came
            // out the same day, for the reason written where the search row is.
            //
            // The words under the glyphs are not those labels come back: one word each, inside the
            // same press target as the glyph and painted in the same ink, at the size a metric's
            // unit is drawn at. They cost the header no height — the 48 dp slot the glyph already
            // filled was taller than glyph plus caption.
            //
            // What that costs, written down so nobody rediscovers it by surprise: the page no
            // longer says which firmware it is talking to, which schema it expects, that the link
            // is up, how many keys the read covered, or that a read came back before the link
            // dropped — and a *failed* read (`SyncPhase.Failed`) no longer prints its reason, so it
            // shows only as an amber `RETRY` growing out of the search row. Typing no longer prints
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
                // A re-read, drawn only for the one state the page cannot get out of on its own.
                // Everything else that made a standing `SYNC` button worth pressing is already
                // automatic: the read follows the link into `Ready` above, re-entering the tab past
                // the store's 60 s TTL re-reads, and the actions that move unit-side numbers
                // (factory reset, the ambient capture) force their own read. So `SYNC` here at rest
                // was a refresh wearing a commit button's name — the word the flush glyph's own
                // documentation spends a paragraph denying — and it was not even free: `applyFetch`
                // sets `pending = 0`, so a read landing between an edit and its 600 ms debounce dim
                // the flush glyph while that write was still on its way.
                //
                // A read the unit never answered is a different thing: nothing re-runs it while the
                // link stays up, and since the status line came out of the header this amber `RETRY`
                // is the only thing on the page saying a read failed at all. Amber, not red, because
                // red on this page means FACTORY RESET.
                if (state.sync is SyncPhase.Failed) {
                    HudButton(
                        label = "RETRY",
                        style = HudButtonStyle.WARNING,
                        enabled = ready,
                        // The reload mark, the same one a row's default and the release list wear:
                        // this asks the unit for the read that did not come back. There is no mark
                        // for "retry" as a word, and inventing one for a fifth of a screen is how a
                        // set turns into a pile — so the description says which of the two the arrow
                        // means here, which is the rule the ledger writes down for this merge.
                        glyph = HudIcons.Reset,
                        onClick = { store.syncIfNeeded(force = true) },
                    )
                }
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
            // Rule 7 holds inside the dialog too: what this confirm does is destroy, so it
            // wears the bin and not the default accept mark.
            confirmGlyph = HudIcons.Bin,
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
                is RenderReading -> ReadingRow(renderable.spec, renderable.value)
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
// A reading drawn on a card
// ---------------------------------------------------------------------------

/**
 * One reading, drawn as a row that cannot be edited — the shape every other row on the page has,
 * so a number the owner cannot change is still recognisably the same kind of line.
 *
 * The row is a fiction: [SchemaItem] describes a setting, and there is no setting behind a reading,
 * so this builds the plainest one that exists (an int or a string, a text control, nothing else)
 * purely so the row widget does the laying out. It is `enabled = false` with a hint that says why
 * in the owner's words rather than the firmware's, because the honest answer to "why can't I type
 * in this box" is that the box is not a setting. The store has already turned a whole-number
 * reading into an `Int`, so an ADC count draws as "2678" and not "2678.0".
 *
 * One caller: the card whose own control acts on the number (`SettingsLayout.CARD_READINGS`).
 * A reading has no other home on the page.
 */
@Composable
private fun ReadingRow(spec: ConfigSchema.ExtraSpec, value: Any?) {
    val numeric = spec.kind.equals("int", ignoreCase = true)
    SettingsRow(
        item = SchemaItem(
            key = spec.key,
            kind = if (numeric) ValueKind.INT else ValueKind.STR,
            control = RowControl.TEXT,
            label = spec.label,
        ),
        view = RowView(value, null, RowStatus.SYNCED, null),
        imperial = false,
        unitSwap = emptyMap(),
        enabled = false,
        onEdit = { _, _ -> },
        readOnlyHint = if (spec.live) "live reading, not a setting" else "fixed in firmware, not a setting",
    )
}

// ---------------------------------------------------------------------------
// The action line
// ---------------------------------------------------------------------------

/**
 * The five actions that belong to no card, drawn as five small icons with one word under each, on
 * one line, in the page's `.sticky-top` header directly under the search box.
 *
 * They were words until 2026-11-11 — four in a two-by-two grid at the foot of the screen, and
 * Factory Reset as a full-width button at the end of the scroll. What came back when the captions
 * went on the same day is one word per action, not the sentences the buttons printed: the shape is
 * what a finger finds, the word is what it checks, and the colour does the rest — **amber** says the
 * phone still holds rows the unit has not got, **dim** says this action is not available as things
 * stand, and a red bin says what cannot be undone. The word is painted in the glyph's own ink for
 * exactly that reason: an action that is not available looks unavailable twice over.
 *
 * **SAVE** is the word the maintainer asked for, and it is still not how a row is saved — a row
 * writes itself, and reports what happened to it. What is left in the phone's RAM is a debounce
 * window's worth of edits, and a driver who has changed four rows and is about to walk outside wants
 * them on the unit, not in a timer that a dropped link will undo. The flush glyph is that action.
 * The count the button used to spell out (`SAVE 3`) is not on the screen: the colour is the count —
 * amber when anything waits — and the sentence, which still carries the number, is in
 * `contentDescription` for whoever cannot read the colour at all.
 *
 * The line lives in the header, so it does not scroll: five glyphs the owner taps by position are
 * worth more at a fixed y than at the bottom of a page that grows and shrinks its own notes. It
 * was pinned at the foot until the maintainer moved it up on 2026-11-11; both places depend on the
 * same thing, that the list takes `weight(1f)` — in the old one, a `fillMaxSize()` list left the
 * bar zero pixels tall and none of these five actions could be pressed at all.
 *
 * Factory Reset came up into the line with the rest, which is the one thing the unit's page did
 * deliberately and this page now does the other way: it kept that action on a third row of its
 * own, away from the hand that taps SAVE. Up here it is further from that hand than ever, its word
 * is `WIPE` and its glyph a bin — neither mistakable for its neighbours — but what really makes it
 * safe is that it is the only action on the page that asks first: the icon opens
 * [HudConfirmDialog], and not one byte reaches the unit until that is answered.
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
    // column owns the gutter padding). Glyph plus one word per slot still costs less height than the
    // two rows of word buttons they replaced, which is the part that matters on a phone: every dp
    // this line keeps is a dp of settings the owner has to scroll for.
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val pending = state.pending
        val synced = state.sync is SyncPhase.Ready
        HudCaptionedAction(
            glyph = HudIcons.Flush,
            caption = "SAVE",
            // The number the button used to show lives in the ink instead, where it costs the eye
            // nothing and a driver who cannot see the colour still gets the count from the sentence.
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
        HudCaptionedAction(
            glyph = HudIcons.Power,
            caption = "RESTART",
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
        HudCaptionedAction(
            glyph = HudIcons.ExportFile,
            caption = "BACKUP",
            description = "back up every setting to a file",
            ink = if (synced) HudColors.Cyan else HudColors.TextDim,
            enabled = synced,
            onClick = onExport,
        )
        HudCaptionedAction(
            glyph = HudIcons.ImportFile,
            caption = "RESTORE",
            description = if (restoring) "restoring the settings from that file…"
            else "restore the settings from a backup file",
            ink = if (synced && !restoring) HudColors.Cyan else HudColors.TextDim,
            enabled = synced && !restoring,
            onClick = onRestore,
        )
        HudCaptionedAction(
            glyph = HudIcons.Bin,
            caption = "WIPE",
            description = "factory reset the unit — wipes every setting",
            ink = if (ready) HudColors.Red else HudColors.TextDim,
            enabled = ready,
            onClick = onFactoryReset,
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
            // The same mark the action line's RESTART wears, two lines above: one verb, one
            // drawing, even when the page offers it twice — once as a standing action and once as
            // the prompt a boot-only change leaves behind.
            glyph = HudIcons.Power,
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
 *
 * [color] is cyan everywhere a card holds settings; the vehicle editor passes red for its
 * DELETE card, because that card is the one place this heading names something the page
 * does not offer to the thumb by accident.
 */
@Composable
internal fun CardHead(title: String, color: Color = HudColors.Cyan) {
    Text(
        title,
        style = HudeSectionTitle(0.2f / 13f),
        color = color,
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
