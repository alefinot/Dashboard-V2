package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.graphics.Color
import com.alefinot.dashboardpp.ui.screens.settings.parseHex
import com.alefinot.dashboardpp.ui.theme.HudColors

/**
 * The scales the BATT and TEMP bars are drawn on, and the colours they ramp through.
 *
 * The unit draws both of these for itself, and the app is meant to be the same instrument
 * (maintainer, 2026-11-20: *"the batt bar should go from red for low voltage, to yellow, to
 * green for optimal voltage… the temp bar from blue for cold to yellow for operating temp to
 * red for hot, basically simulating the behaviour of the display of the esp"* - and when
 * asked whether the app's fixed scales should stay, *"change the scale dynamically based on
 * the scales in the ESP settings"*). So the temperature's scale and colour stops are read
 * from the unit: `TEMP_BAR_MIN` / `TEMP_BAR_MAX`, the stop values `TEMP_WARN_LOW` /
 * `TEMP_WARN_YEL` / `TEMP_WARN_RED` and the stop inks `COLOR_TEMP_NORM` / `COLOR_TEMP_WARN` /
 * `COLOR_TEMP_CRIT`, all streamed by `config.get`, with the numbers clamped on this side
 * exactly the way `cfgValidate()` in `src/config.cpp` clamps them on the unit's side. The
 * defaults below are that table's own compiled defaults (10-110 °C, 20/45/90 °C), which is
 * what the bars show before the answer lands and what they fall back to if it never does.
 *
 * The battery has **no** settings on the unit to read: its fill and its colour zones are
 * compiled in, so they are mirrored here as constants - 10.5-14.5 V from `drawBatteryIcon()`
 * (`src/gfx.cpp`) for the fill, and red below 11.8 V / green from 13.1 V from the digit
 * colouring in `src/ui.cpp` for the ramp. Its yellow stop is the centre of that red-to-green
 * span, which is the middle of the band the unit holds yellow, so red, yellow and green land
 * where the bike's own display puts them.
 *
 * The ramps themselves are the unit's `rampColor3()` (`src/gfx.cpp`): flat outside the two
 * end stops, a straight channel-by-channel blend between the three - `blendColorLinear()`,
 * whose ink the app reproduces rather than Compose's perceptual `lerp`, which would walk
 * blue-to-yellow through mint and disagree with the dash. The three inks are the unit's
 * `COLOR_TEMP_NORM` / `_WARN` / `_CRIT` off the same mirror, so an owner who recoloured their
 * dash recolours the phone with it. Only before that answer lands does the app use its own
 * palette, and there the cold stop is [HudColors.Blue] rather than the firmware's default
 * green, because blue is the word the owner used for cold.
 */
internal data class PowerScales(
    val tempMin: Double = 10.0,
    val tempMax: Double = 110.0,
    val tempLow: Double = 20.0,
    val tempYel: Double = 45.0,
    val tempRed: Double = 90.0,
    /** The three stops of the unit's ramp - its `COLOR_TEMP_NORM` / `_WARN` / `_CRIT`. */
    val tempCold: Color = HudColors.Blue,
    val tempWarm: Color = HudColors.Yellow,
    val tempHot: Color = HudColors.Red,
) {

    /** Coolant as a percentage of the unit's bar, the way its sidebar fills it. */
    fun tempPercent(tempC: Double): Double = percent(tempC, tempMin, tempMax)

    /** Coolant's colour on the unit's own ramp, blended between the unit's own stops. */
    fun tempColor(tempC: Double): Color =
        ramp(tempC, tempLow, tempCold, tempYel, tempWarm, tempRed, tempHot)

    /**
     * Battery as a percentage of the unit's icon fill (10.5-14.5 V) - the same span the
     * bike's battery glyph uses, so a half-icon on the dash is a half-bar here.
     */
    fun battPercent(volts: Double): Double = percent(volts, BATT_MIN, BATT_MAX)

    /** Battery's colour on the unit's zones, ramped: red low, yellow charging, green optimal. */
    fun battColor(volts: Double): Color =
        ramp(volts, BATT_LOW, HudColors.Red, BATT_MID, HudColors.Yellow, BATT_OPT, HudColors.Green)

    companion object {

        /** What the bars draw with before `config.get` has answered - the firmware's defaults. */
        val Default = PowerScales()

        /** `drawBatteryIcon()` in `src/gfx.cpp`: `(v - 10.5) / 4.0`, clamped to the icon. */
        const val BATT_MIN = 10.5
        const val BATT_MAX = 14.5

        /** The battery digits in `src/ui.cpp`: red below this, green from [BATT_OPT] up. */
        const val BATT_LOW = 11.8
        const val BATT_OPT = 13.1

        /**
         * The ramp's yellow: the middle of the span the unit holds yellow, so the green end
         * still begins exactly where the firmware switches to green.
         */
        const val BATT_MID = 12.45

        /**
         * The unit's temp settings out of a `config.get` mirror, with the unit's own clamps
         * applied. A key the unit did not answer keeps the compiled default, so an old
         * firmware or a half-read config cannot invent a scale - and a nonsense one
         * (`min >= max`) is refused outright rather than clamped into a 1 °C bar.
         *
         * The ramp's three inks are settings too (`COLOR_TEMP_NORM` / `_WARN` / `_CRIT`), and
         * owners do change them - the bench unit was found running a cyan cold stop. So they
         * come off the same mirror, read the way `hexToRGB565()` in `src/config.cpp` reads
         * them, and the app's own palette is only the fallback for a unit that never answered.
         */
        fun from(values: Map<String, Any?>): PowerScales {
            val def = Default
            val min = number(values, "TEMP_BAR_MIN") ?: def.tempMin
            var max = number(values, "TEMP_BAR_MAX") ?: def.tempMax
            if (max <= min) return def
            var yel = number(values, "TEMP_WARN_YEL") ?: def.tempYel
            var red = number(values, "TEMP_WARN_RED") ?: def.tempRed
            var low = number(values, "TEMP_WARN_LOW") ?: def.tempLow
            // cfgValidate() in src/config.cpp, in its own order: every stop pulled into the
            // bar, then the order low <= yel <= red restored.
            if (yel < min) yel = min
            if (red < min) red = min
            if (red > max) red = max
            if (yel > red) yel = red
            if (low < min) low = min
            if (low > yel) low = yel
            return PowerScales(
                tempMin = min,
                tempMax = max,
                tempLow = low,
                tempYel = yel,
                tempRed = red,
                tempCold = color(values, "COLOR_TEMP_NORM") ?: def.tempCold,
                tempWarm = color(values, "COLOR_TEMP_WARN") ?: def.tempWarm,
                tempHot = color(values, "COLOR_TEMP_CRIT") ?: def.tempHot,
            )
        }

        /** A firmware number from the mirror. `BleJson` reads every JSON number as Double. */
        private fun number(values: Map<String, Any?>, key: String): Double? =
            (values[key] as? Number)?.toDouble()

        private fun color(values: Map<String, Any?>, key: String): Color? =
            parseHex(values[key] as? String)

        private fun percent(value: Double, min: Double, max: Double): Double {
            val span = max - min
            if (span <= 0.0) return 0.0
            return ((value - min) / span * 100.0).coerceIn(0.0, 100.0)
        }

        /** `rampColor3()` from `src/gfx.cpp`, in Compose colours instead of RGB565. */
        private fun ramp(value: Double, v0: Double, c0: Color, v1: Double, c1: Color, v2: Double, c2: Color): Color =
            when {
                value <= v0 -> c0
                value <= v1 -> blend(c0, c1, v0, v1, value)
                value <= v2 -> blend(c1, c2, v1, v2, value)
                else -> c2
            }

        private fun blend(from: Color, to: Color, v0: Double, v1: Double, value: Double): Color {
            val span = v1 - v0
            if (span <= 0.0) return to
            val f = ((value - v0) / span).toFloat().coerceIn(0f, 1f)
            // The unit's `blendColorLinear()` in src/gfx.cpp: the three channels, blended,
            // nothing else. Compose's own `lerp` is a perceptual (OKLab) blend and walks
            // blue-to-yellow through mint, which is not the ink the dash puts down.
            return Color(
                from.red + (to.red - from.red) * f,
                from.green + (to.green - from.green) * f,
                from.blue + (to.blue - from.blue) * f,
            )
        }
    }
}
