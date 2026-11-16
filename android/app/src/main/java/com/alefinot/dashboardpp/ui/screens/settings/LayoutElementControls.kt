package com.alefinot.dashboardpp.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudOverlayScrim
import com.alefinot.dashboardpp.ui.components.HudSlider
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeParamName
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.ui.theme.hudGlass
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore.SettingsState

/**
 * The UI Layout group, drawn as the screen it configures.
 *
 * The unit's page (and the first app pages) listed the UI Layout rows as settings rows —
 * fifty-eight lines of `SHOW_ELEMENT_…` and `OFFSET_…` where the eye had to group by
 * prefix. But those rows are not really a list of settings: they are a list of *things
 * on the dashboard* — the clock, the fuel bar, the weather widget — and almost every one
 * of them is the same three questions about one thing: is it shown, where is it, how
 * many digits. So this draws the questions the way the owner asks them: one row per
 * screen element with its Visible switch and its current position on the face of it,
 * and a sheet per element carrying everything else about it.
 *
 * It is the same page underneath. Every write still goes through [ConfigStore] — the
 * per-row debounce, the amber flush, the reboot bar, all of it — and every row in a
 * sheet is still one `CFG_TABLE` row with its schema label, band and default. Nothing
 * here invents a setting or moves one: the elements and their rows come from the
 * `element` stamp the generator carries in the schema (see [LayoutElements]), and a row
 * the presentation never placed in an element lands in the trailing "Other rows" bucket
 * rather than off the page.
 *
 * The sheet deliberately uses the app's own modal vocabulary ([HudOverlayScrim],
 * [HudEnter], `hudGlass`) rather than Material's `ModalBottomSheet`: every other modal
 * on this page — the factory-reset ask, the photo crop — is drawn from those pieces,
 * and a Material sheet would arrive with its own tonal colors, drag handle and scrim
 * rules that nothing else in the app answers to. Dismissal follows the same house rule
 * as [com.alefinot.dashboardpp.ui.components.HudConfirmDialog]: the scrim swallows
 * taps, the X and the back button close.
 */

/**
 * The element list that replaces the UI Layout group's cards, search-filtered the way
 * the accordion itself filters: a query that names the element or any row inside its
 * sheet keeps the row; everything else hides. With no query every element shows, in
 * the order the presentation lists them.
 */
@Composable
fun LayoutElementList(
    elements: List<LayoutElement>,
    state: SettingsState,
    store: ConfigStore,
    query: String,
    searching: Boolean,
    advanced: Boolean,
    enabled: Boolean,
    onOpen: (LayoutElement) -> Unit,
) {
    val shown = if (searching) {
        elements.filter { LayoutElements.elementShown(it, query, advanced) }
    } else {
        elements
    }
    for (element in shown) {
        val matches = if (searching) LayoutElements.matchCount(element, query, advanced) else null
        LayoutElementRow(
            element = element,
            state = state,
            store = store,
            enabled = enabled,
            matches = matches,
            onOpen = { onOpen(element) },
        )
    }
    if (searching && shown.isEmpty()) {
        Text(
            "no screen element matches \"$query\"",
            style = HudeCfgNote(),
            color = HudColors.Cyan,
            modifier = Modifier.padding(4.dp),
        )
    }
}

/**
 * One element's line: its name, where it stands right now, and the Visible switch that
 * needs no sheet at all — hiding an element is the most common visit to this page, and
 * it should cost one tap, not two. Tapping anywhere else on the line opens the sheet.
 */
@Composable
private fun LayoutElementRow(
    element: LayoutElement,
    state: SettingsState,
    store: ConfigStore,
    enabled: Boolean,
    matches: Int?,
    onOpen: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .hudCard()
            .hudPressable(enabled = true, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    element.label,
                    style = HudeParamName(),
                    color = HudColors.TextPrimary,
                )
                // Under the name, the numbers the sheet exists to change — so the list
                // answers "where is the clock right now" without opening anything. While
                // searching, the match count rides in its place: the hint the person
                // asked for is *what did the query find*, not where the bar stands.
                val hint = matches?.let {
                    "$it row${if (it == 1) "" else "s"} match your search"
                } ?: LayoutElements.summary(element, state)
                hint?.let {
                    Text(it, style = HudeCfgNote(), color = HudColors.TextDim)
                }
            }
            LayoutElements.toggleOf(element)?.let { toggle ->
                val on = LayoutElements.truthy(state.rows[toggle.key]?.value)
                Box(
                    // The switch owns its own press; the row underneath opens the sheet.
                    // Compose hit-tests innermost first, so the two never fight.
                    Modifier.heightIn(min = 44.dp).padding(start = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HudToggle(
                        on = on,
                        label = "Show ${element.label}",
                        onToggle = { store.edit(toggle, it, null) },
                    )
                }
            }
        }
    }
}

/**
 * The sheet: everything tunable about one screen element, in the order the schema lists
 * it, with the sheet's own section headings kept as sub-headings. A section heading that
 * merely repeats the element's name is dropped — the header already said it once.
 *
 * The panel takes the bottom of the window and most of its height: a bar element's
 * sheet is fourteen rows deep, and a sheet one must fight to scroll inside a half
 * screen is the complaint the Material default invites.
 */
@Composable
fun LayoutElementSheet(
    element: LayoutElement,
    store: ConfigStore,
    state: SettingsState,
    imperial: Boolean,
    unitSwap: Map<String, Any?>,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    HudOverlayScrim(contentAlignment = Alignment.BottomCenter) {
        HudEnter(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .hudGlass(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                        ),
                    )
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Text(
                        element.label,
                        style = HudeParamName(),
                        color = HudColors.Cyan,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(44.dp).hudPressable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            HudIcons.Close,
                            contentDescription = "close ${element.label} settings",
                            colorFilter = ColorFilter.tint(HudColors.TextDim),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Sections as they come; only the first row *after* a section name
                    // pays for the heading, and a heading equal to the element's name is
                    // the presentation's bookkeeping, not information.
                    var lastSection: String? = null
                    for (item in element.items) {
                        val section = item.section
                        if (section != null && section != element.label && section != lastSection) {
                            Text(
                                section,
                                style = HudeParamName(),
                                color = HudColors.TextDim,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        lastSection = section
                        if (item.kind == ValueKind.XY) {
                            PositionBlock(
                                element = element,
                                item = item,
                                view = state.rows[item.key],
                                state = state,
                                enabled = enabled,
                                store = store,
                            )
                        } else {
                            SettingsRow(
                                item = item,
                                view = state.rows[item.key],
                                imperial = imperial,
                                unitSwap = unitSwap,
                                enabled = enabled,
                                onEdit = { x, y -> store.edit(item, x, y) },
                                onReset = { store.reset(item) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * The position of one element: the two axes, each a full-width slider over the screen with
 * the exact figure beside it, typed in place.
 *
 * The slider is the aim and the number is the entry. The slider spans what [LayoutElements]
 * computes — the screen itself for the viewport centre, half a screen either side of the
 * middle for every offset, intersected with the firmware band so the track can never ask
 * for a value the unit would clamp. The number keeps the whole band the old steppers
 * allowed (±4096 for most offsets), because aiming at 468 px with a thumb is not how a
 * fine placement gets done — one drags close, one types exact. A drag commits one
 * rounded pixel on release, never per frame: every intermediate pixel of a drag through
 * the whole screen would put that many `config.set` writes on the link.
 *
 * A drag also rewrites the figure beside it, so the number under the thumb is the number
 * the thumb is on — the same hand-back the settings rows' sliders do (2026-11-16, the day
 * every value box came off the app and the values themselves became the controls).
 */
@Composable
private fun PositionBlock(
    element: LayoutElement,
    item: SchemaItem,
    view: RowView?,
    state: SettingsState,
    enabled: Boolean,
    store: ConfigStore,
) {
    val x = (view?.value as? Number)?.toDouble() ?: 0.0
    val y = (view?.yValue as? Number)?.toDouble() ?: 0.0

    @Composable
    fun Axis(name: String, confirmed: Double, xAxis: Boolean) {
        val range = LayoutElements.axisWindow(element.id, item, state, xAxis)
        val lo = range.start
        val hi = range.endInclusive.coerceAtLeast(lo)
        var draft by remember(item.key, confirmed, lo, hi) {
            mutableStateOf(confirmed.coerceIn(lo, hi))
        }
        var text by remember(item.key, confirmed) { mutableStateOf(renderNumber(confirmed, 0)) }
        val commitDrag: () -> Unit = {
            val snapped = (if (draft > 0) draft + 0.5 else draft - 0.5).toInt().toDouble()
            if (snapped != confirmed) {
                if (xAxis) store.edit(item, snapped, y) else store.edit(item, x, snapped)
            }
            draft = confirmed // re-seeded by the row anyway; keeps the thumb honest on refuse
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = HudeParamName(),
                    color = HudColors.TextDim,
                    modifier = Modifier.width(18.dp),
                )
                if (hi - lo >= 1.0) {
                    // The rail carries no figures of its own: the number beside is the exact
                    // value, and the whole width the ends used to take is rail - a longer
                    // track is what makes a drag land on the pixel the driver wants. Same
                    // control as every settings slider, one line.
                    HudSlider(
                        value = draft.toFloat().coerceIn(lo.toFloat(), hi.toFloat()),
                        onValueChange = { v ->
                            draft = v.toDouble()
                            text = renderNumber(v.toDouble(), 0)
                        },
                        onValueChangeFinished = commitDrag,
                        valueRange = lo.toFloat()..hi.toFloat(),
                        steps = sliderSteps(lo, hi, 1.0),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // The firmware band does not reach the screen window at all — the
                    // slider would be a pin on a point. Say so and lean on the number.
                    Text(
                        "off-screen range",
                        style = HudeCfgNote(),
                        color = HudColors.TextDim,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
                HudInput(
                    value = text,
                    onValueChange = { raw ->
                        text = raw.filter { it.isDigit() || it == '-' || it == '.' || it == ',' }
                    },
                    modifier = Modifier.width(SettingsValueSlot).padding(start = 8.dp),
                    style = HudInputStyle.BARE,
                    textAlign = TextAlign.Center,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    onDone = {
                        val parsed = text.trim().replace(',', '.').toDoubleOrNull()
                        if (parsed != null && parsed != confirmed) {
                            // The number takes the whole firmware band; the store clamps
                            // and reports a clamp, exactly as a typed number does on
                            // any other row of the page.
                            if (xAxis) store.edit(item, parsed, y) else store.edit(item, x, parsed)
                        }
                    },
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.label,
                style = HudeParamName(),
                color = if (enabled) HudColors.TextPrimary else HudColors.TextDim,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.heightIn(min = 44.dp)
                    .padding(horizontal = 8.dp)
                    .hudPressable(enabled = enabled) { store.reset(item) },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    HudIcons.Reset,
                    contentDescription = "reset ${item.label} to its default",
                    colorFilter = ColorFilter.tint(
                        if (enabled) HudColors.Cyan else HudColors.TextDim,
                    ),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Axis("X", x, xAxis = true)
        Axis("Y", y, xAxis = false)
        statusNote(view)?.let {
            Text(
                it,
                style = HudeCfgNote(),
                color = when (view?.status) {
                    RowStatus.FAILED, RowStatus.RAM_ONLY, RowStatus.MISSING -> HudColors.Amber
                    else -> HudColors.TextDim
                },
            )
        }
    }
}
