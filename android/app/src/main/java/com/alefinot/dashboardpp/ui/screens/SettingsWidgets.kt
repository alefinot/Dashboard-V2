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
        "firmwareUpdate" -> FirmwareUpdateControl(store, enabled, onNote)
        "tempCalibrate" -> NoLiveReadingControl("engine temperature", "NTC_TEMP_OFFSET", enabled)
        "batteryCalibrate" -> NoLiveReadingControl("battery voltage", "BATTERY_OFFSET", enabled)
        else -> Text(
            "this app has no control for \"$widget\" — the settings above belong to it",
            style = HudeCfgNote(),
            color = HudColors.Amber,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }
}

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
                "measures sensor input jitter on the unit",
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

@Composable
private fun NoLiveReadingControl(what: String, offsetKey: String, enabled: Boolean) {
    Text(
        if (enabled) {
            "\"match to reference\" works from the live $what reading on the unit's own page. " +
                "This page holds the configuration, not the live numbers, so it offers the " +
                "$offsetKey offset row above instead — set it there and the unit applies it."
        } else {
            "the $what calibration needs the unit linked and read"
        },
        style = HudeCfgNote(),
        color = HudColors.TextDim,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
