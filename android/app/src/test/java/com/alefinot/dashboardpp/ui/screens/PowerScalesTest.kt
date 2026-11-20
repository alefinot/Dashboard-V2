package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.graphics.Color
import com.alefinot.dashboardpp.ui.theme.HudColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Live page's two power bars actually draw.
 *
 * The bars are meant to be the unit's own bars: the coolant's scale and colour stops are the
 * unit's NVS settings, read through `config.get`, and the battery's are the constants compiled
 * into its display. So the numbers are checked here as numbers - the clamp order in
 * [PowerScales.from] is `cfgValidate()`'s in `src/config.cpp`, and if it drifts the app's bar
 * disagrees with the bike's dash and nobody can see why from a screenshot.
 */
class PowerScalesTest {

    // ─── The unit's temp settings ──────────────────────────────────────────────

    @Test
    fun `no answer from the unit leaves the firmware defaults`() {
        val s = PowerScales.from(emptyMap())
        assertEquals(PowerScales.Default, s)
        assertEquals(10.0, s.tempMin, 1e-9)
        assertEquals(110.0, s.tempMax, 1e-9)
        assertEquals(20.0, s.tempLow, 1e-9)
        assertEquals(45.0, s.tempYel, 1e-9)
        assertEquals(90.0, s.tempRed, 1e-9)
    }

    @Test
    fun `a unit that resized its bar moves the app's bar with it`() {
        val s = PowerScales.from(
            mapOf(
                "TEMP_BAR_MIN" to 0.0,
                "TEMP_BAR_MAX" to 130.0,
                "TEMP_WARN_LOW" to 30.0,
                "TEMP_WARN_YEL" to 60.0,
                "TEMP_WARN_RED" to 105.0,
            ),
        )
        assertEquals(0.0, s.tempMin, 1e-9)
        assertEquals(130.0, s.tempMax, 1e-9)
        assertEquals(30.0, s.tempLow, 1e-9)
        assertEquals(60.0, s.tempYel, 1e-9)
        assertEquals(105.0, s.tempRed, 1e-9)
        // 65 °C of a 0-130 bar.
        assertEquals(50.0, s.tempPercent(65.0), 1e-9)
        assertEquals(0.0, s.tempPercent(-40.0), 1e-9)
        assertEquals(100.0, s.tempPercent(400.0), 1e-9)
    }

    @Test
    fun `stops outside the bar are pulled into it, the way the unit pulls them in`() {
        // cfgValidate() order, on a unit whose warn points escaped its bar: RED=300 above the
        // ceiling, YEL=120 above that RED (so it comes back down to RED), LOW=-10 below the
        // floor (so it comes up to the floor).
        val s = PowerScales.from(
            mapOf(
                "TEMP_BAR_MIN" to 10.0,
                "TEMP_BAR_MAX" to 110.0,
                "TEMP_WARN_LOW" to -10.0,
                "TEMP_WARN_YEL" to 120.0,
                "TEMP_WARN_RED" to 300.0,
            ),
        )
        assertEquals(10.0, s.tempLow, 1e-9)
        assertEquals(110.0, s.tempRed, 1e-9)
        assertEquals(110.0, s.tempYel, 1e-9)
        // A ramp with two stops on top of each other still answers every reading, and the
        // top of the bar is the hot colour.
        assertEquals(HudColors.Red, s.tempColor(200.0))
        assertEquals(HudColors.Blue, s.tempColor(5.0))
    }

    @Test
    fun `a bar whose ceiling is under its floor is not a scale`() {
        // The unit's own answer here is to force max = min + 1, which would draw a bar that
        // is a single vertical line; the app keeps the defaults instead of agreeing.
        val s = PowerScales.from(mapOf("TEMP_BAR_MIN" to 120.0, "TEMP_BAR_MAX" to 110.0))
        assertEquals(PowerScales.Default, s)
    }

    @Test
    fun `a key the unit did not answer keeps its default rather than inventing one`() {
        val s = PowerScales.from(mapOf("TEMP_BAR_MAX" to 200.0, "TEMP_WARN_LOW" to null))
        assertEquals(10.0, s.tempMin, 1e-9)
        assertEquals(200.0, s.tempMax, 1e-9)
        assertEquals(20.0, s.tempLow, 1e-9)
    }

    @Test
    fun `the ramp wears the unit's own inks`() {
        // The bench unit's ramp: cyan cold, stock yellow, stock red, on a 0-110 bar. A dash
        // that was recoloured has to be recoloured on the phone with it, or the two stop
        // agreeing on the number and disagreeing on the colour.
        val s = PowerScales.from(
            mapOf(
                "TEMP_BAR_MIN" to 0.0,
                "TEMP_WARN_LOW" to 33.0,
                "TEMP_WARN_YEL" to 35.0,
                "TEMP_WARN_RED" to 90.0,
                "COLOR_TEMP_NORM" to "#00fffb",
                "COLOR_TEMP_WARN" to "#ffff00",
                "COLOR_TEMP_CRIT" to "#ff0000",
            ),
        )
        assertEquals(Color(0xFF00FFFB), s.tempCold)
        assertEquals(Color(0xFFFFFF00), s.tempWarm)
        assertEquals(Color(0xFFFF0000), s.tempHot)
        // Below the first stop the bar is cold ink, whatever the app's palette says.
        assertEquals(Color(0xFF00FFFB), s.tempColor(12.0))
        assertNotEquals(HudColors.Blue, s.tempColor(12.0))
        // And the hot end is still the unit's red, not HudColors.Red.
        assertEquals(Color(0xFFFF0000), s.tempColor(95.0))
    }

    @Test
    fun `an ink the unit never wrote or wrote badly falls back to the app palette`() {
        val s = PowerScales.from(
            mapOf("COLOR_TEMP_NORM" to "not a colour", "COLOR_TEMP_CRIT" to "#ff00"),
        )
        assertEquals(PowerScales.Default, s)
    }

    // ─── The battery's compiled-in scale ───────────────────────────────────────

    @Test
    fun `the battery bar fills on the span the unit's icon fills on`() {
        val s = PowerScales.Default
        assertEquals(0.0, s.battPercent(10.0), 1e-9)
        assertEquals(0.0, s.battPercent(10.5), 1e-9)
        assertEquals(50.0, s.battPercent(12.5), 1e-9)
        assertEquals(100.0, s.battPercent(14.5), 1e-9)
        assertEquals(100.0, s.battPercent(16.0), 1e-9)
    }

    @Test
    fun `the battery colour walks red to yellow to green across the unit's zones`() {
        val s = PowerScales.Default
        // The unit's own verdicts, flat: red below 11.8 V, green from 13.1 V.
        assertEquals(HudColors.Red, s.battColor(0.0))
        assertEquals(HudColors.Red, s.battColor(11.8))
        assertEquals(HudColors.Green, s.battColor(13.2))
        assertEquals(HudColors.Green, s.battColor(20.0))
        // And a blend between them, so a charging bike is not a colour traffic light: 12.45 V
        // is the middle of the span, and half-way to each side sits half-way between stops.
        assertClose(HudColors.Yellow, s.battColor(PowerScales.BATT_MID))
        assertClose(mix(HudColors.Red, HudColors.Yellow, 0.5f), s.battColor(12.125))
        assertClose(mix(HudColors.Yellow, HudColors.Green, 0.5f), s.battColor(12.775))
        assertNotEquals(HudColors.Red, s.battColor(12.4))
        assertNotEquals(HudColors.Green, s.battColor(12.5))
    }

    @Test
    fun `the temp colour walks blue to yellow to red across the unit's stops`() {
        val s = PowerScales.Default
        // Flat outside the end stops, exactly like rampColor3() on the unit.
        assertEquals(HudColors.Blue, s.tempColor(-40.0))
        assertEquals(HudColors.Blue, s.tempColor(20.0))
        assertEquals(HudColors.Red, s.tempColor(90.5))
        assertEquals(HudColors.Red, s.tempColor(300.0))
        assertClose(HudColors.Yellow, s.tempColor(45.0))
        assertClose(mix(HudColors.Blue, HudColors.Yellow, 0.5f), s.tempColor(32.5))
        assertClose(mix(HudColors.Yellow, HudColors.Red, 0.5f), s.tempColor(67.5))
        // The two words the owner used are different colours: a cold engine is not a hot one
        // wearing a different hat.
        assertNotEquals(HudColors.Blue, s.tempColor(95.0))
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun mix(a: Color, b: Color, t: Float): Color =
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

    /** Two colours the same to within a few units of 8-bit ink. */
    private fun assertClose(expected: Color, actual: Color) {
        val d = maxOf(
            Math.abs(expected.red - actual.red),
            Math.abs(expected.green - actual.green),
            Math.abs(expected.blue - actual.blue),
        )
        assertTrue("expected $expected, got $actual (channel delta $d)", d < 0.02)
    }
}
