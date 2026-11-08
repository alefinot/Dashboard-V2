package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.R
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudAccordion
import com.alefinot.dashboardpp.ui.components.HudAccordionHeader
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.components.hudPage
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
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DashboardSettingsScreen(
    store: ConfigStore,
    schema: ConfigSchema?,
    link: BleLink,
    timeNote: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsStateWithLifecycle()
    val linkState by link.state.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    /** The one group the accordion has open. Nothing is open until someone taps. */
    var open by remember { mutableStateOf<String?>(null) }
    var closedWhileSearching by remember { mutableStateOf(setOf<String>()) }
    var extrasOpen by remember { mutableStateOf(false) }
    var unplacedOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var odoText by remember { mutableStateOf("") }
    var noiseLine by remember { mutableStateOf("never run") }
    var restoring by remember { mutableStateOf(false) }

    val linked = linkState is BleLinkState.Ready
    var wasLinked by remember { mutableStateOf(linked) }
    LaunchedEffect(linkState) {
        if (linked != wasLinked) note = ""
        wasLinked = linked
    }

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

    val advanced: (@Composable () -> Unit)? =
        if (schema != null && schema.advancedKey.isNotEmpty()) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Advanced Mode", style = HudeFormLabel(), color = HudColors.Cyan)
                    HudToggle(
                        on = state.advanced,
                        label = "advanced mode",
                        onToggle = { store.setAdvanced(it) },
                    )
                }
            }
        } else {
            null
        }
    val unitTag = store.buildVersion.ifEmpty { "—" }.let { "unit V$it" }
    val schemaTag = "schema " + (schema?.firmware?.let { "V$it" } ?: "missing")
    val (linkText, linkColor) = linkLine(linkState)
    val (syncText, syncColor) = syncLine(state, ready)

    Column(Modifier.hudPage()) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = HudPage.Gutter)
                .padding(top = 10.dp, bottom = 4.dp),
        ) {
            HudPageHeader(title = "Dashboard++ Setup", trailing = advanced) {
                HudBanner("$unitTag  ·  $schemaTag")
            }
            Spacer(Modifier.height(8.dp))
            // `#msg`: the page's status strip, centred and in its status colour.
            Text(
                linkText,
                style = HudeCfgNote(),
                color = linkColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(6.dp))
            Text(syncText, style = HudeCfgNote(), color = syncColor)
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
            state.error?.let {
                Text(it, style = HudeCfgHelp(), color = HudColors.Red)
            }
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
        val columns = SettingsLayout.columns(widthDp.coerceAtMost(HudPage.Width.value.toInt()))
            .coerceAtMost(2)
        val unplaced = SettingsLayout.unlabelledBlock(state, query)

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = HudPage.Gutter)) {
            item {
                Text(
                    if (searching) {
                        SettingsLayout.searchSummary(groups, query, state.advanced)
                    } else {
                        "${groups.size} groups · ${groups.sumOf { g -> g.cards.sumOf { c -> c.items.size } }} " +
                            "settings · tap a group to open it"
                    },
                    style = HudeCfgNote(),
                    color = HudColors.TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                )
            }

            for (group in shown) {
                val groupOpen = isOpen(group.id)
                item(key = "group-${group.id}") {
                    val blocks =
                        SettingsLayout.blocksFor(group, state, query, state.advanced)
                    HudAccordion(open = groupOpen, modifier = Modifier.padding(bottom = 14.dp)) {
                        HudAccordionHeader(
                            title = group.title,
                            badge = groupIcon(group.icon),
                            meta = SettingsLayout.groupMeta(group, query, state.advanced),
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
                        if (groupOpen) {
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

            if (state.unitStatus.isNotEmpty()) {
                item(key = "readings") {
                    HudAccordion(open = extrasOpen, modifier = Modifier.padding(bottom = 14.dp)) {
                        HudAccordionHeader(
                            title = "Values the unit reports",
                            meta = "${state.unitStatus.size}",
                            open = extrasOpen,
                            onClick = { extrasOpen = !extrasOpen },
                        )
                        if (extrasOpen) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(
                                    Modifier.fillMaxWidth().hudCard()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    state.unitStatus.forEach { (spec, value) ->
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

            if (unplaced != null) {
                item(key = "unplaced") {
                    HudAccordion(open = unplacedOpen, modifier = Modifier.padding(bottom = 14.dp)) {
                        HudAccordionHeader(
                            title = unplaced.card.title,
                            meta = "${unplaced.body.size}",
                            open = unplacedOpen,
                            onClick = { unplacedOpen = !unplacedOpen },
                        )
                        if (unplacedOpen) {
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

            // The last row of the scroll, where the unit page keeps its own performance panel.
            item(key = "performance") {
                HudAccordion(open = false, modifier = Modifier.padding(bottom = 14.dp)) {
                    HudAccordionHeader(
                        title = "Performance Monitor",
                        open = false,
                        onClick = { showPerf = true },
                    )
                }
            }

            item(key = "factory-reset") {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    HudButton(
                        label = if (confirm == "factory.reset") "CONFIRM FACTORY RESET"
                        else "FACTORY RESET",
                        style = HudButtonStyle.DANGER,
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        onClick = {
                            if (confirm != "factory.reset") {
                                confirm = "factory.reset"
                                note = "that wipes every setting on the unit — press again to confirm"
                            } else {
                                scope.launch {
                                    val r = store.action("factory.reset")
                                    confirm = null
                                    note = if (r.ok || r.error == "timeout") {
                                        "factory reset done — re-reading"
                                    } else {
                                        "factory reset refused: ${r.error}"
                                    }
                                    store.syncIfNeeded(force = true)
                                }
                            }
                        },
                    )
                    Text(
                        "Wipes every setting on the unit and restarts it. The app asks once " +
                            "more before it sends the command.",
                        style = HudeCfgNote(),
                        color = HudColors.TextDim,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        ActionBar(
            store = store,
            state = state,
            ready = ready,
            scope = scope,
            onNote = { note = it },
            onExport = { exportLauncher.launch("dashboardpp-settings.json") },
            onRestore = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            restoring = restoring,
        )
    }
}

private fun groupIcon(icon: String?): Int? = when (icon) {
    "system" -> R.drawable.ic_grp_system
    "display" -> R.drawable.ic_grp_display
    "sensors" -> R.drawable.ic_grp_sensors
    "layout" -> R.drawable.ic_grp_layout
    "more" -> R.drawable.ic_grp_more
    else -> null
}

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
) {
    Column(Modifier.fillMaxWidth().background(HudColors.Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = HudPage.Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    label = when {
                        state.pending > 0 -> "SAVE ${state.pending}"
                        state.sync is SyncPhase.Ready -> "SAVED"
                        else -> "SAVE"
                    },
                    style = HudButtonStyle.PRIMARY,
                    enabled = ready && state.pending > 0,
                    modifier = Modifier.weight(1f).height(44.dp),
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
                    modifier = Modifier.weight(1f).height(44.dp),
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    label = "BACKUP",
                    style = HudButtonStyle.SECONDARY,
                    enabled = state.sync is SyncPhase.Ready,
                    modifier = Modifier.weight(1f).height(44.dp),
                    onClick = onExport,
                )
                HudButton(
                    label = if (restoring) "RESTORING…" else "RESTORE",
                    style = HudButtonStyle.SECONDARY,
                    enabled = state.sync is SyncPhase.Ready && !restoring,
                    modifier = Modifier.weight(1f).height(44.dp),
                    onClick = onRestore,
                )
            }
        }
    }
}

private fun noteOfFlush(flush: ConfigStore.Flush): String? = when {
    flush.rows == 0 -> null
    flush.notWritten > 0 ->
        "wrote ${flush.rows - flush.notWritten} · ${flush.notWritten} did not reach the unit " +
            "— the rows say why"
    else -> "wrote ${flush.rows} row(s) to the unit"
}

private fun linkLine(linkState: BleLinkState): Pair<String, Color> = when (val link = linkState) {
    is BleLinkState.Ready -> "linked to ${link.name} · MTU ${link.mtu}" to HudColors.Green
    is BleLinkState.Pairing ->
        "pairing — confirm the code shown on the dashboard" to HudColors.Cyan
    is BleLinkState.Connecting -> "connecting to ${link.name}…" to HudColors.Cyan
    is BleLinkState.Failed -> "link failed: ${link.reason}" to HudColors.Red
    BleLinkState.Idle ->
        "no Bluetooth link — the unit's settings live on the unit, and the Vehicles " +
            "page is where you pair one" to HudColors.TextDim
}

/** The read's own line, under the search box: which of the unit's keys the read covered. */
private fun syncLine(state: ConfigStore.SettingsState, ready: Boolean): Pair<String, Color> =
    when (val sync = state.sync) {
        is SyncPhase.Running ->
            "reading the unit's settings… ${sync.chunks} chunks in" to HudColors.Cyan
        is SyncPhase.Ready ->
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
