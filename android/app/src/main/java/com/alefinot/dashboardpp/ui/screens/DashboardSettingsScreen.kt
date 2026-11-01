package com.alefinot.dashboardpp.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.screens.settings.ConfigSchema
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore
import com.alefinot.dashboardpp.ui.screens.settings.RowControl
import com.alefinot.dashboardpp.ui.screens.settings.SchemaGroup
import com.alefinot.dashboardpp.ui.screens.settings.SchemaItem
import com.alefinot.dashboardpp.ui.screens.settings.SettingsRow
import com.alefinot.dashboardpp.ui.screens.settings.SyncPhase
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The unit's configuration, as a native page.
 *
 * This is the page the app used to hand to a WebView: the ESP's own settings site,
 * loaded over Wi-Fi, framed in a glass sheet. It worked, and it is the reason the
 * settings tab and the unit's own form map used to be the same artifact — the unit
 * served the form, so the form could not disagree with itself. Once the settings
 * live in Compose, that guarantee is gone, so the page is *generated* from the
 * firmware's own tables instead (see `ConfigSchema`), and every number here is a
 * `CFG_TABLE` row the unit confirmed it has.
 *
 * What the page is made of, in order:
 *
 *  - **The link header.** A V2 unit has no network stack; Bluetooth is the only way
 *    to it, so the page starts by saying whether there is a link at all.
 *  - **The sync bar.** Nothing below it is editable before `config.get` has been
 *    joined to the schema, and the bar says which of the unit's keys the read
 *    covered.
 *  - **Search, advanced, export/restore.** The three things a 198-row form needs
 *    that a card stack does not: find a row, reveal the rows the average owner
 *    should not touch, and get the whole config off the unit.
 *  - **The group strip and the cards.** Same grouping the unit's page used, because
 *    that grouping is what its `configMap` says the settings are.
 *
 * Nothing here is saved by a "save" button. Each row writes itself, and the row
 * reports what actually happened to it.
 */
@Composable
fun DashboardSettingsScreen(
    store: ConfigStore,
    schema: ConfigSchema?,
    link: BleLink,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsStateWithLifecycle()
    val linkState by link.state.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var odoText by remember { mutableStateOf("") }
    var noiseLine by remember { mutableStateOf("never run") }
    var restoring by remember { mutableStateOf(false) }
    // The link panel, over this page. For now it is the bench panel the app already
    // has (`BleScreen`, raised the same way App settings raises it); at the end of the
    // phase its scan list and status card live in this page's header instead, and the
    // panel goes away.
    var showLink by remember { mutableStateOf(false) }
    if (showLink) {
        BleScreen(onBack = { showLink = false })
        return
    }

    val ready = linkState is BleLinkState.Ready
    val groups = schema?.groups ?: emptyList()
    val toolsPage = groups.size
    val onTools = group >= groups.size

    // The read follows the link: pair, and the tab pulls; re-entering the tab inside
    // the TTL does not re-pull (the plan's 60 s rule).
    LaunchedEffect(linkState, schema) {
        if (linkState is BleLinkState.Ready && schema != null) store.syncIfNeeded()
    }

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
                else -> {
                    val report = store.restore(text)
                    "restored ${report.applied} keys in ${report.batches} batches" +
                        (if (report.skipped > 0) " · ${report.skipped} refused" else "") +
                        (if (report.nvsErrors > 0) " · ${report.nvsErrors} not in flash" else "") +
                        (report.problems.firstOrNull()?.let { " · $it" } ?: "")
                }
            }
            restoring = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(store, schema, state, linkState) { showLink = true }
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
            if (schema != null && schema.item(schema.advancedKey) != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HudToggle(on = state.advanced, onToggle = { store.setAdvanced(it) })
                    Text("advanced", style = HudeCfgNote(), color = HudColors.TextDim)
                }
            }
            HudButton(
                label = "export",
                style = HudButtonStyle.SECONDARY,
                enabled = state.sync is SyncPhase.Ready,
                onClick = { exportLauncher.launch("dashboardpp-settings.json") },
            )
            HudButton(
                label = if (restoring) "restoring…" else "restore",
                style = HudButtonStyle.SECONDARY,
                enabled = state.sync is SyncPhase.Ready && !restoring,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            )
        }

        GroupStrip(groups, group, toolsPage) { group = it }

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
        // A refusal with no row to carry it — the advanced switch is the only one, and
        // it lives in the header rather than in a card.
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
        if (onTools) {
            ToolsPage(
                store = store,
                schema = schema,
                enabled = unitEnabled,
                confirm = confirm,
                onConfirm = { confirm = it },
                onNote = { note = it },
                odoText = odoText,
                onOdo = { odoText = it },
                noiseLine = noiseLine,
                onNoise = { noiseLine = it },
                scope = scope,
                state = state,
            )
            return@Column
        }
        val items = renderablesFor(groups[group], state, query, state.advanced)
        if (items.isEmpty()) {
            Text(
                if (query.isEmpty()) "nothing in this group" else "nothing matches \"$query\"",
                style = HudeCfgNote(),
                color = HudColors.TextDim,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(items) { renderable ->
                when (renderable) {
                    is RenderCard -> {
                        CardHead(renderable.title)
                        renderable.hint?.let {
                            Text(
                                it,
                                style = HudeCfgNote(),
                                color = HudColors.TextDim,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    is RenderSection -> SectionHead(renderable.title)
                    is RenderRow -> SettingsRow(
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

// ---------------------------------------------------------------------------
// What the page is made of
// ---------------------------------------------------------------------------

/** One thing in the page's scroll list. */
sealed interface Renderable

data class RenderCard(val title: String, val hint: String? = null) : Renderable
data class RenderSection(val title: String) : Renderable
data class RenderRow(val item: SchemaItem) : Renderable

/**
 * The rows a group should show, in the unit's own order, with the advanced rows
 * either in or out and the search applied.
 *
 * Deliberately a plain function over the schema and the read: it is the piece most
 * likely to be wrong (a card that drops its rows, an advanced row that escapes the
 * switch), and it is the piece that can be tested without a phone.
 */
fun renderablesFor(
    group: SchemaGroup,
    state: ConfigStore.SettingsState,
    query: String,
    advanced: Boolean,
): List<Renderable> {
    val needle = query.trim().lowercase()
    val out = ArrayList<Renderable>()
    for (card in group.cards) {
        if (card.advanced && !advanced) continue
        val matched = card.items.filter { item ->
            (!item.advanced || advanced) && (
                needle.isEmpty() ||
                    item.label.lowercase().contains(needle) ||
                    item.key.lowercase().contains(needle) ||
                    (item.keyY?.lowercase()?.contains(needle) == true)
                )
        }
        if (matched.isEmpty()) continue
        // A widget card's settings are still settings: the button it grew on the
        // unit's page is an addition, not a replacement, so its rows are drawn here
        // like any other card's, and the action itself waits on the Tools page.
        out += RenderCard(
            card.title,
            if (card.widget != null) "its button is on the Tools page" else null,
        )
        var section: String? = null
        for (item in matched) {
            if (item.section != section) {
                section = item.section
                if (section != null) out += RenderSection(section)
            }
            out += RenderRow(item)
        }
    }
    // Values the unit streams that the schema has no row for: a firmware that grew a
    // setting nobody regenerated the schema for. They are shown rather than dropped.
    if (state.unknown.isNotEmpty() && needle.isNotEmpty()) {
        val unknown = state.unknown.filter {
            it.label.lowercase().contains(needle) || it.key.lowercase().contains(needle)
        }
        if (unknown.isNotEmpty()) {
            out += RenderCard("Unlabelled keys")
            unknown.forEach { out += RenderRow(it) }
        }
    }
    return out
}

@Composable
private fun Header(
    store: ConfigStore,
    schema: ConfigSchema?,
    state: ConfigStore.SettingsState,
    linkState: BleLinkState,
    onOpenLink: () -> Unit,
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
            Spacer(Modifier.weight(1f))
            HudButton(
                label = when (linkState) {
                    is BleLinkState.Ready -> "LINKED"
                    is BleLinkState.Pairing -> "PAIRING…"
                    is BleLinkState.Connecting -> "CONNECTING…"
                    else -> "PAIR THE UNIT"
                },
                style = when (linkState) {
                    is BleLinkState.Ready -> HudButtonStyle.SECONDARY
                    else -> HudButtonStyle.PRIMARY
                },
                onClick = onOpenLink,
            )
        }
        val (line, color) = when (linkState) {
            is BleLinkState.Ready ->
                "linked to ${linkState.name} · MTU ${linkState.mtu}" to HudColors.Green
            is BleLinkState.Pairing ->
                "pairing — confirm the code shown on the dashboard" to HudColors.Cyan
            is BleLinkState.Connecting -> "connecting to ${linkState.name}…" to HudColors.Cyan
            is BleLinkState.Failed -> "link failed: ${linkState.reason}" to HudColors.Red
            BleLinkState.Idle ->
                "no Bluetooth link — the unit's settings live on the unit" to HudColors.TextDim
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
            "read ${sync.keys} keys (${sync.rows} table rows) in ${sync.chunks} chunks" to
                // An xy row is one row that streams two keys, so "keys" is always a
                // little more than "rows"; the count that means something is whether a
                // row the schema describes came back at all.
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

@Composable
private fun GroupStrip(
    groups: List<SchemaGroup>,
    selected: Int,
    toolsPage: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        groups.forEachIndexed { index, group ->
            GroupPill(group.title, index == selected) { onSelect(index) }
        }
        GroupPill("TOOLS", selected == toolsPage) { onSelect(toolsPage) }
    }
}

@Composable
private fun GroupPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) HudColors.CyanDim else HudColors.Panel)
            .border(1.dp, if (selected) HudColors.Cyan else HudColors.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = HudeFormLabel(),
            color = if (selected) HudColors.TextPrimary else HudColors.TextDim,
        )
    }
}

/**
 * The bar a boot-only change leaves behind. `DISPLAY_ROTATION` and friends are read
 * once at boot, so the unit will draw the old geometry until it restarts — the row
 * saved, and the change is still not visible. Saying so is the whole point.
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
private fun CardHead(title: String) {
    Text(
        title,
        style = HudeSectionCaps(),
        color = HudColors.Cyan,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
}

@Composable
private fun SectionHead(title: String) {
    Text(
        title,
        style = HudeParamName(),
        color = HudColors.TextDim,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// Tools: the cards that act rather than configure
// ---------------------------------------------------------------------------

@Composable
private fun ToolsPage(
    store: ConfigStore,
    schema: ConfigSchema,
    enabled: Boolean,
    confirm: String?,
    onConfirm: (String?) -> Unit,
    onNote: (String) -> Unit,
    odoText: String,
    onOdo: (String) -> Unit,
    noiseLine: String,
    onNoise: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    /** So the rows this page draws carry their real status, and the unknown keys. */
    state: ConfigStore.SettingsState,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { CardHead("Unit actions") }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                HudButton(
                    label = "RESTART",
                    style = HudButtonStyle.SECONDARY,
                    enabled = enabled,
                    onClick = {
                        scope.launch {
                            val r = store.action("reboot")
                            if (r.ok || r.error == "timeout") store.clearRebootPending()
                            onNote(if (r.ok || r.error == "timeout") "restarting…" else "restart refused: ${r.error}")
                        }
                    },
                )
                HudButton(
                    label = "SLEEP",
                    style = HudButtonStyle.SECONDARY,
                    enabled = enabled,
                    onClick = {
                        scope.launch {
                            val r = store.action("sleep")
                            onNote(if (r.ok || r.error == "timeout") "the unit is asleep" else "sleep refused: ${r.error}")
                        }
                    },
                )
                HudButton(
                    label = if (confirm == "factory.reset") "CONFIRM FACTORY RESET" else "FACTORY RESET",
                    style = HudButtonStyle.DANGER,
                    enabled = enabled,
                    onClick = {
                        if (confirm != "factory.reset") {
                            onConfirm("factory.reset")
                            onNote("that wipes every setting on the unit — press again to confirm")
                        } else {
                            scope.launch {
                                val r = store.action("factory.reset")
                                onConfirm(null)
                                onNote(
                                    if (r.ok || r.error == "timeout") {
                                        "factory reset done — re-reading"
                                    } else {
                                        "factory reset refused: ${r.error}"
                                    },
                                )
                                store.syncIfNeeded(force = true)
                            }
                        }
                    },
                )
            }
        }
        item { CardHead("Trip") }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Text(
                    "resets the trip distance, time and averages on the unit",
                    style = HudeCfgHelp(),
                    color = HudColors.TextDim,
                    modifier = Modifier.weight(1f),
                )
                HudButton(
                    label = if (confirm == "trip.reset") "CONFIRM" else "RESET TRIP",
                    style = HudButtonStyle.WARNING,
                    enabled = enabled,
                    onClick = {
                        if (confirm != "trip.reset") {
                            onConfirm("trip.reset")
                        } else {
                            scope.launch {
                                val r = store.action("trip.reset")
                                onConfirm(null)
                                onNote(if (r.ok) "trip reset" else "trip reset refused: ${r.error}")
                            }
                        }
                    },
                )
            }
        }
        item { CardHead("Odometer") }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                HudInput(
                    value = odoText,
                    onValueChange = { raw -> onOdo(raw.filter { it.isDigit() || it == '.' }) },
                    modifier = Modifier.width(140.dp),
                    placeholder = "km",
                )
                HudButton(
                    label = "SET ODOMETER",
                    style = HudButtonStyle.WARNING,
                    enabled = enabled && odoText.toDoubleOrNull() != null,
                    onClick = {
                        scope.launch {
                            val km = odoText.toDoubleOrNull() ?: return@launch
                            val r = store.action("odo.set", BleJson.params("km" to km))
                            onNote(
                                if (r.ok) {
                                    "odometer set to ${(r.fields["km"] as? Number)?.toDouble() ?: km} km"
                                } else {
                                    "odometer set refused: ${r.error}"
                                },
                            )
                        }
                    },
                )
            }
        }
        item { CardHead("Ambient light calibration") }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Text(
                    "captures the light sensor reading the unit is reporting right now",
                    style = HudeCfgHelp(),
                    color = HudColors.TextDim,
                    modifier = Modifier.weight(1f),
                )
                listOf("dark", "bright").forEach { which ->
                    HudButton(
                        label = "CAPTURE $which",
                        style = HudButtonStyle.SECONDARY,
                        enabled = enabled,
                        onClick = {
                            scope.launch {
                                val r = store.action("cal.ambient", BleJson.params("which" to which))
                                onNote(
                                    if (r.ok) {
                                        "$which point captured" +
                                            if (r.fields["adjusted"] == true) " (the other point moved too)" else ""
                                    } else {
                                        "capture refused: ${r.error}"
                                    },
                                )
                                store.syncIfNeeded(force = true)
                            }
                        },
                    )
                }
            }
        }
        item { CardHead("Noise bench") }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Text(
                    "measures sensor input jitter on the unit: $noiseLine",
                    style = HudeCfgHelp(),
                    color = HudColors.TextDim,
                    modifier = Modifier.weight(1f),
                )
                HudButton(
                    label = "RUN",
                    style = HudButtonStyle.SECONDARY,
                    enabled = enabled,
                    onClick = {
                        scope.launch {
                            val start = store.action("noise", BleJson.params("start" to true))
                            if (!start.ok) {
                                onNoise("could not start: ${start.error}")
                                return@launch
                            }
                            // The bench runs on the unit between display frames; poll
                            // its snapshot until it says done (or give up).
                            var line = "started…"
                            repeat(20) {
                                delay(1500)
                                val r = store.action("noise")
                                if (!r.ok) {
                                    line = "lost: ${r.error}"
                                    return@repeat
                                }
                                val status = r.fields["status"] as? String ?: "?"
                                val runs = (r.fields["runs"] as? Number)?.toInt() ?: 0
                                line = "$status · $runs runs"
                                if (status == "done") return@repeat
                            }
                            onNoise(line)
                        }
                    },
                )
            }
        }
        item { CardHead("Firmware") }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "unit firmware V${store.buildVersion.ifEmpty { "?" }} · " +
                        "Arduino core ${store.coreVersion.ifEmpty { "?" }}",
                    style = HudeParamName(),
                    color = HudColors.TextPrimary,
                )
                Text(
                    "This tab does not push firmware. The unit has no path that accepts a " +
                        "firmware image over Bluetooth yet, and an unsigned image it pulled " +
                        "by itself is not a thing this project wants — the app's firmware " +
                        "page stays the way to update a unit that is reachable by Wi-Fi.",
                    style = HudeCfgHelp(),
                    color = HudColors.TextDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // A firmware that grew a setting nobody regenerated the schema for streams a
        // key with no row. It cannot be placed in a group — the schema is what does the
        // placing — so it lands here with a label guessed from the key, and it is in the
        // export either way. A setting that cannot be found looks exactly like a setting
        // that does not exist, which is why this list is not left to the search box.
        if (state.unknown.isNotEmpty()) {
            item { CardHead("Keys this app's schema does not describe") }
            item {
                Text(
                    "${state.unknown.size} setting(s) came from the unit with no row in this " +
                        "app's schema. They are kept, editable with a control guessed from the " +
                        "value, and included in the export — regenerate the schema after a " +
                        "firmware update (scripts/gen_config_schema.py) for proper labels.",
                    style = HudeCfgHelp(),
                    color = HudColors.Amber,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            items(state.unknown) { item ->
                SettingsRow(
                    item = item,
                    view = state.rows[item.key],
                    imperial = state.imperial,
                    unitSwap = schema.imperial,
                    enabled = enabled,
                    onEdit = { x, y -> store.edit(item, x, y) },
                    onReset = { store.reset(item) },
                )
            }
        }
    }
}
