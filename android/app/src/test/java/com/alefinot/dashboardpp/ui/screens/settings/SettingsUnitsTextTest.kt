package com.alefinot.dashboardpp.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsUnitsTextTest {

    private fun row(kind: ValueKind, unit: String? = null) = SchemaItem(
        key = "SOMETHING",
        kind = kind,
        control = RowControl.TEXT,
        label = "Something",
        unit = unit,
    )

    @Test
    fun anIntegerRowNeverShowsADecimalPoint() {
        // What a box actually holds after a fetch: toBox makes every number a Double.
        assertEquals("3474", SettingsUnits.text(row(ValueKind.INT), 3474.0))
        assertEquals("0", SettingsUnits.text(row(ValueKind.INT), 0.0))
        assertEquals("-12", SettingsUnits.text(row(ValueKind.INT), -12.0))
    }

    @Test
    fun aFractionalRowKeepsItsDecimals() {
        assertEquals("1.5", SettingsUnits.text(row(ValueKind.FLOAT), 1.5))
        // A whole value in a float row is not a measurement of "12.000000".
        assertEquals("12", SettingsUnits.text(row(ValueKind.FLOAT), 12.0))
    }

    @Test
    fun textAndMissingValuesReadAsThemselves() {
        assertEquals("3.3.12", SettingsUnits.text(row(ValueKind.STR), "3.3.12"))
        assertEquals("—", SettingsUnits.text(row(ValueKind.STR), null))
    }

    /** text() shows what the box holds; the scale the schema declares is toBox's job. */
    @Test
    fun theBoxValueIsShownAsGiven() {
        val item = row(ValueKind.INT, unit = "Hz")
        val box = SettingsUnits.toBox(item, 3474.0, imperial = false)
        assertEquals(3474.0, box)
        assertEquals("3474", SettingsUnits.text(item, box))
    }
}
