package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vehicle TYPE control is a menu now, not three pills (maintainer request, 2026-11-12).
 *
 * The source is the test surface because this module has no Compose UI harness and the
 * property at risk is structural: a broad preset list is one dropdown, `OTHER` reveals the
 * draft field that carries its own label, and SAVE forwards that label with the rest of the
 * form. A pill row returning behind the menu, or the custom field living outside the draft,
 * are exactly the regressions this can read without a device.
 */
class VehicleTypeDropdownTest {

    private val app = File("src/main")
    private val screens = File(app, "java/com/alefinot/dashboardpp/ui/screens")
    private val vehicles = File(app, "java/com/alefinot/dashboardpp/vehicles")

    /** Comment-stripped read, matching the editor's other source-pinned tests. */
    private fun code(dir: File, name: String): String {
        val f = File(dir, name)
        assertTrue("missing ${f.path}", f.exists())
        var s = f.readText()
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\n]*"), "")
    }

    private val editor = code(screens, "VehicleEditorScreen.kt")
    private val vehicle = code(vehicles, "Vehicle.kt")

    @Test
    fun `the broad type list is enum data rather than editor furniture`() {
        val presets = listOf(
            "CAR", "MOTORCYCLE", "SCOOTER", "VAN", "TRUCK", "BUS", "CAMPER", "BICYCLE",
            "E_BIKE", "BOAT", "TRACTOR", "OTHER",
        )
        for (preset in presets) {
            assertTrue("$preset is not a stored VehicleType", Regex("\\n    $preset\\(").containsMatchIn(vehicle))
        }
        assertTrue("E-BIKE is not spelled for the HUD", vehicle.contains("E_BIKE(\"E-BIKE\")"))
    }

    @Test
    fun `the TYPE control opens a dropdown and no longer names enum buttons`() {
        assertTrue("TYPE does not use a DropdownMenu", editor.contains("DropdownMenu("))
        assertTrue("TYPE no longer calls the editor's dropdown control", editor.contains("TypeDropdown("))
        // The old row labelled its buttons `option.name`; the menu labels them through
        // `label`, which is the field that turns E_BIKE into `E-BIKE`.
        assertFalse("TYPE went back to enum-name pill buttons", editor.contains("option.name"))
    }

    @Test
    fun `the TYPE menu uses HUD rows and keeps a three-choice viewport`() {
        assertFalse("the menu still uses Material menu rows", editor.contains("DropdownMenuItem("))
        assertTrue("the menu rows are not HUD rows", editor.contains("TypeMenuRow("))
        assertTrue("the menu rows do not disable Material's ripple", editor.contains("indication = null"))

        assertTrue(
            "the menu is not capped at three visible rows",
            Regex("private const val VehicleTypeMenuVisibleRows = 3(?s).*?" +
                "private val VehicleTypeMenuMaxHeight = VehicleTypeMenuRowHeight \\* VehicleTypeMenuVisibleRows")
                .containsMatchIn(editor),
        )
        assertTrue("the menu viewport is not capped", editor.contains("heightIn(max = VehicleTypeMenuMaxHeight)"))
        assertTrue("the menu is not scrollable", editor.contains("scrollState = menuScrollState"))

        assertTrue("the menu does not use the HUD sheet", editor.contains("containerColor = HudColors.Sheet"))
        assertTrue("the menu does not match field corners", editor.contains("shape = HudRadius.FieldShape"))
        assertTrue("the menu has no HUD active edge", editor.contains("BorderStroke(1.dp, HudColors.HairlineActive)"))
    }

    @Test
    fun `OTHER reveals the custom label as part of the editor draft`() {
        assertTrue(
            "OTHER does not reveal a custom type field",
            Regex("if \\(type == VehicleType\\.OTHER\\)(?s).*?\"CUSTOM TYPE\"(?s).*?customType = it")
                .containsMatchIn(editor),
        )
        assertTrue(
            "the editor header no longer follows the live custom label",
            Regex("val shownType = vehicleTypeLabel\\(type, customType\\)").containsMatchIn(editor),
        )
        assertTrue(
            "the editor header no longer receives the live type label",
            Regex("EditorHeader\\((?s).*?typeLabel = shownType").containsMatchIn(editor),
        )
        assertTrue(
            "SAVE no longer carries the custom label with the rest of the form",
            Regex("onSave\\(\\s*draft\\.copy\\((?s).*?customType = customType(?s).*?photoEdit")
                .containsMatchIn(editor),
        )
    }
}
