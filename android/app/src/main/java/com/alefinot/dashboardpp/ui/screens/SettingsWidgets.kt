package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.screens.settings.ConfigStore
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The settings page's actions — the ones that are not a row.
 *
 * The unit's own page puts each of these inside the card it belongs to (the trip reset sits
 * in the Trip Reset card above the row that decides whether trips reset themselves), so the
 * app does the same, and an action that is not in the card next to its settings is an action
 * somebody has to go looking for. They used to live on a page of their own called TOOLS;
 * that page is gone, and each control came back to the card it belongs to.
 *
 * Every control here is an RPC the unit answers over the link. (The firmware push used to be
 * the one exception that lived here too — `firmwareUpdate` — until the card moved to App
 * Settings on 2026-11-09; the settings page is now only settings, and `FirmwareUpdateControl`
 * composes itself there.)
 *
 * Each one wears the mark its verb has in [HudIcons], beside the word rather than instead of it:
 * these are single controls loose on a long card, not a fixed line of peers with room for a
 * caption under the glyph, so they keep the word the header line keeps its caption. Two pairs of
 * them share a mark: this page's restart is the reboot prompt's, and its commit is the same accept
 * the vehicle editor's SAVE wears — which is the whole reason the marks exist.
 */
@Composable
internal fun WidgetControl(
    widget: String,
    store: ConfigStore,
    enabled: Boolean,
    /** The action currently waiting for a second press; one at a time, on purpose. */
    confirm: String?,
    onConfirm: (String?) -> Unit,
    onNote: (String) -> Unit,
    odoText: String,
    onOdo: (String) -> Unit,
    noiseLine: String,
    onNoise: (String) -> Unit,
    scope: CoroutineScope,
) {
    when (widget) {
        "tripReset" -> TripResetControl(store, enabled, confirm, onConfirm, onNote, scope)
        "odometerSet" -> OdometerControl(store, enabled, odoText, onOdo, onNote, scope)
        "ambientCalibrate" -> AmbientCalibrationControl(store, enabled, onNote, scope)
        "noiseBench" -> NoiseBenchControl(store, enabled, noiseLine, onNoise, scope)
        "tempCalibrate" -> NoLiveReadingControl("engine temperature", "NTC_TEMP_OFFSET", enabled)
        "batteryCalibrate" -> NoLiveReadingControl("battery voltage", "BATTERY_OFFSET", enabled)
        else -> Text(
            "no control for \"$widget\"",
            style = HudeCfgNote(),
            color = HudColors.Amber,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }
}

/**
 * The trip reset, on the card that also holds `TRIP_AUTO_RESET`.
 *
 * Two presses, because the trip distance is the one number a driver may still want: the unit
 * does not ask, so the app does. The confirmation is cleared as soon as it is acted on, so
 * the button cannot be pressed a second time by accident afterwards.
 *
 * The two presses are drawn apart as well as worded apart. The first is a plain secondary button
 * wearing the circular arrow; the second turns amber, the ink this app reserves for "armed — this
 * one does it". Both presses carry the arrow, because both are the same act seen from either side
 * of the finger.
 */
@Composable
private fun TripResetControl(
    store: ConfigStore,
    enabled: Boolean,
    confirm: String?,
    onConfirm: (String?) -> Unit,
    onNote: (String) -> Unit,
    scope: CoroutineScope,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(
            "resets trip distance, time and averages",
            style = HudeCfgHelp(),
            color = HudColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        HudButton(
            label = if (confirm == "trip.reset") "CONFIRM" else "RESET TRIP",
            // Amber only on the press that does it: the first press arms, and the ink is what says
            // so without a word being added to the line.
            style = if (confirm == "trip.reset") HudButtonStyle.WARNING else HudButtonStyle.SECONDARY,
            enabled = enabled,
            glyph = HudIcons.Reset,
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

/**
 * Set the odometer. The unit answers `odo.set` with the value it stored, so the note reports
 * that number and not the one that was typed — they can differ, and the unit's is the true
 * one.
 */
@Composable
private fun OdometerControl(
    store: ConfigStore,
    enabled: Boolean,
    odoText: String,
    onOdo: (String) -> Unit,
    onNote: (String) -> Unit,
    scope: CoroutineScope,
) {
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
            // Amber, as it was: this overwrites the one distance the unit cannot get back. The
            // mark beside it is the accept the rest of the app wears for "commit what is in front
            // of you", so the two channels agree that this press stores something.
            style = HudButtonStyle.WARNING,
            enabled = enabled && odoText.toDoubleOrNull() != null,
            glyph = HudIcons.Check,
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

/**
 * Capture the dark and bright points of the ambient light sensor.
 *
 * `cal.ambient` reports `adjusted` when the other point moved too (the unit keeps the two
 * points a fixed distance apart), and the note says so, because a capture that moved a value
 * nobody touched looks like a bug unless the screen mentions it. The read is forced
 * afterwards: the rows of this same card just changed on the unit.
 *
 * What the two buttons store is whatever the sensor says at the instant one is pressed — the
 * firmware assigns `ambientLightValue` to the point's row (`bt_rpc.cpp`) — so the reading itself
 * is drawn on this card, above the buttons, by `SettingsLayout.CARD_READINGS`. It is a snapshot of
 * the last read rather than a stream (the settings page has no telemetry feed of its own), and the
 * forced re-read below is what keeps it honest: after a capture the number on the card is the
 * number that just went into the row, not the one that was on the screen when the finger landed.
 */
@Composable
private fun AmbientCalibrationControl(
    store: ConfigStore,
    enabled: Boolean,
    onNote: (String) -> Unit,
    scope: CoroutineScope,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(
            "stores the reading above as the point you tap",
            style = HudeCfgHelp(),
            color = HudColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        listOf("dark", "bright").forEach { which ->
            HudButton(
                label = "CAPTURE $which",
                style = HudButtonStyle.SECONDARY,
                enabled = enabled,
                // Begin the one thing this card can begin: take the reading that is on the card
                // now and store it as the point. Same mark the bench's RUN wears, for the same
                // reason — a press that starts something on the unit and answers afterwards.
                glyph = HudIcons.Play,
                onClick = {
                    scope.launch {
                        val r = store.action("cal.ambient", BleJson.params("which" to which))
                        onNote(
                            if (r.ok) {
                                "$which point captured" +
                                    if (r.fields["adjusted"] == true) " (the other point moved too)"
                                    else ""
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

/**
 * The jitter bench, on its own card.
 *
 * The unit runs the bench between display frames, so the app starts it and then polls the
 * snapshot until the unit says `done` — and says what it is doing the whole time, because a
 * button that appears to do nothing for ten seconds is a button people press twice.
 */
@Composable
private fun NoiseBenchControl(
    store: ConfigStore,
    enabled: Boolean,
    noiseLine: String,
    onNoise: (String) -> Unit,
    scope: CoroutineScope,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "measures sensor jitter",
                style = HudeCfgHelp(),
                color = HudColors.TextDim,
                modifier = Modifier.weight(1f),
            )
            HudButton(
                label = "RUN",
                style = HudButtonStyle.SECONDARY,
                enabled = enabled,
                // The bench runs for ten seconds on the unit's side and answers nothing until it
                // is asked, so this is the app's clearest "press once, then it is working" — which
                // is what the triangle means everywhere else in the app.
                glyph = HudIcons.Play,
                onClick = {
                    scope.launch {
                        val start = store.action("noise", BleJson.params("start" to true))
                        if (!start.ok) {
                            onNoise("could not start: ${start.error}")
                            return@launch
                        }
                        // Polled rather than waited for: the unit runs the bench between display
                        // frames and answers `status` as it goes. Each poll republishes the line,
                        // because a button that appears to do nothing for ten seconds is a button
                        // people press twice.
                        var line = "started…"
                        var round = 0
                        while (round++ < 20) {
                            delay(1500)
                            val r = store.action("noise")
                            if (!r.ok) {
                                line = "lost the unit mid-run: ${r.error}"
                                break
                            }
                            val status = r.fields["status"] as? String ?: "?"
                            val runs = (r.fields["runs"] as? Number)?.toInt() ?: 0
                            line = "$status · $runs runs"
                            onNoise(line)
                            if (status == "done") break
                        }
                        onNoise(line)
                    }
                },
            )
        }
        Text(
            "last run: $noiseLine",
            style = HudeCfgNote(),
            color = HudColors.TextDim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The "match to reference" calibrations the unit's page offers for the temperature and the
 * battery.
 *
 * The unit's version reads the live temperature, subtracts it from a reference the driver
 * types, and puts the difference in the offset row. That needs a live reading, and this page
 * has none — it holds the configuration, not the telemetry — so the app does not draw a button
 * that would be working from a number it does not have. The offset row is editable, and that
 * is where this lands today; the honest line says which row and why the button is missing.
 */
@Composable
private fun NoLiveReadingControl(what: String, offsetKey: String, enabled: Boolean) {
    Text(
        if (enabled) {
            "\"match to reference\" works on the unit's own page — use the $offsetKey row above"
        } else {
            "$what calibration needs a link"
        },
        style = HudeCfgNote(),
        color = HudColors.TextDim,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
