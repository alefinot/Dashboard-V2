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

/**
 * Capture the dark and bright points of the ambient light sensor.
 *
 * `cal.ambient` reports `adjusted` when the other point moved too (the unit keeps the two
 * points a fixed distance apart), and the note says so, because a capture that moved a value
 * nobody touched looks like a bug unless the screen mentions it. The read is forced
 * afterwards: the rows of this same card just changed on the unit.
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
            "captures the current light reading",
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
