package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson

object SettingsUnits {

    /** The four unit families the unit's rows declare (`unitType` in the schema). */
    private const val SPEED = "speed"
    private const val LENGTH = "length"
    private const val VOLUME = "volume"
    private const val TEMP = "temp"

    fun toBox(item: SchemaItem, device: Any?, imperial: Boolean): Any? {
        val n = (device as? Number)?.toDouble() ?: return device
        var v = n / item.unitScale
        if (imperial && item.unitType != null) v = toImperial(item.unitType, v)
        return v
    }

    /** What a box holds → the value to store. The exact inverse of [toBox]. */
    fun fromBox(item: SchemaItem, box: Any?, imperial: Boolean): Any? {
        val n = (box as? Number)?.toDouble() ?: return box
        var v = n
        if (imperial && item.unitType != null) v = fromImperial(item.unitType, v)
        return v * item.unitScale
    }

    fun text(item: SchemaItem, box: Any?): String {
        val n = box as? Number ?: return box?.toString() ?: "—"
        return if (item.kind == ValueKind.INT) n.toInt().toString() else BleJson.display(n)
    }

    /** The unit label as it should read: `km/h` becomes `mph` only when it is on. */
    fun label(item: SchemaItem, imperial: Boolean, swap: Map<String, Any?>): String? {
        val u = item.unit ?: return null
        if (!imperial) return u
        return (swap[u] as? String) ?: u
    }

    private fun toImperial(type: String, metric: Double): Double = when (type) {
        SPEED -> metric * KmH_TO_MPH
        LENGTH -> metric * MM_TO_IN
        VOLUME -> metric * L_TO_GAL
        TEMP -> metric * 1.8 + 32
        else -> metric
    }

    /** The pure multiplier of a family — what a *step* converts by (°C steps have no offset). */
    private fun factor(type: String): Double = when (type) {
        SPEED -> KmH_TO_MPH
        LENGTH -> MM_TO_IN
        VOLUME -> L_TO_GAL
        TEMP -> 1.8
        else -> 1.0
    }

    fun boxStep(item: SchemaItem, imperial: Boolean): Double {
        val dev = item.step
            ?: return if (item.kind == ValueKind.FLOAT) 0.1 else 1.0
        var s = dev / item.unitScale
        if (imperial) item.unitType?.let { s *= factor(it) }
        return if (s <= 0.0 || s.isNaN()) 1.0 else s
    }

    private fun fromImperial(type: String, imperial: Double): Double = when (type) {
        SPEED -> imperial / KmH_TO_MPH
        LENGTH -> imperial / MM_TO_IN
        VOLUME -> imperial / L_TO_GAL
        TEMP -> (imperial - 32) / 1.8
        else -> imperial
    }

    fun decimals(item: SchemaItem): Int {
        if (item.kind == ValueKind.INT) return 0
        val step = item.step
        if (step == null || step >= 1.0) return 1
        var d = 0
        var s = step
        while (d < 3 && s % 1.0 != 0.0) {
            s *= 10.0
            d++
        }
        return d
    }

    /** Same factors as the page's `KMH_TO_MPH` / `MM_TO_IN` / `L_TO_GAL`. */
    const val KmH_TO_MPH = 0.621371
    const val MM_TO_IN = 1.0 / 25.4
    const val L_TO_GAL = 0.264172
}
