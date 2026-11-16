package com.alefinot.dashboardpp.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every option row on the settings page opens a dropdown (maintainer request, 2026-11-16:
 * "the dst rule, time zone and manual cpu frequency should be dropdown menus" — and Speed
 * Source came with them so no row is styled unlike its neighbours).
 *
 * Like the editor's other row-shape tests, the source is the surface: this module has no
 * Compose UI harness, and what is at risk is structural. The chip wall came off, the menu is
 * the shared component the vehicle type and the firmware card already open, and the dropdown
 * still leaves the row's reset glyph a hittable box instead of starving it with `fillMaxWidth`.
 */
class SettingsOptionMenuTest {

    private val app = File("src/main")
    private val settings = File(app, "java/com/alefinot/dashboardpp/ui/screens/settings")

    /** Comment-stripped read, matching the editor's other source-pinned tests. */
    private fun code(name: String): String {
        val f = File(settings, name)
        assertTrue("missing ${f.path}", f.isFile)
        var s = f.readText()
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\n]*"), "")
    }

    private val row = code("SettingsRow.kt")

    @Test
    fun `an option row opens the shared HUD select instead of drawing chips`() {
        assertTrue("SELECT is not dispatched to the menu control", row.contains("RowControl.SELECT -> MenuControl("))
        assertTrue("the settings page does not open the shared HUD select", row.contains("HudSelectMenu("))
        assertTrue(
            "the chip wall is back behind the menu",
            !Regex("private fun SelectControl").containsMatchIn(row),
        )
        // Picking commits through the row's normal edit path — same door a typed value comes
        // through, so clamping, the pending state and the status note all still happen.
        assertTrue(
            "the menu no longer commits through the row's edit path",
            Regex("RowControl\\.SELECT -> MenuControl\\([^)]*\\) \\{ onEdit\\(it, null\\) \\}").containsMatchIn(row),
        )
    }

    @Test
    fun `a stacked dropdown shares its line with the reset glyph`() {
        // The dropdown fills its line, so it has to be weighted when the reset is drawn beside
        // it: an unweighted fillMaxWidth starves the glyph to zero width (that is the bug this
        // row has already had once, with a slider).
        assertTrue(
            "the stacked row no longer weights the controls that fill their line",
            Regex("item\\.control == RowControl\\.SLIDER \\|\\| item\\.control == RowControl\\.SELECT")
                .containsMatchIn(row),
        )
        assertTrue("the weight is no longer applied to the control", row.contains("Trailing(if (fills) Modifier.weight(1f)"))
        // And it stays under its label rather than being squeezed beside it.
        val exempt = Regex("NeedsItsOwnLine = setOf\\(([\\s\\S]*?)\\n\\)").find(row)
        assertTrue("SELECT is no longer on a line of its own", exempt != null && exempt.groupValues[1].contains("RowControl.SELECT"))
    }

    @Test
    fun `the trigger names the stored option, or the raw value when none matches`() {
        assertTrue(
            "the trigger no longer shows the matching option's label, falling back to the raw value",
            Regex("selected\\?\\.label \\?: box\\?\\.toString\\(\\)\\?\\.takeIf \\{ it\\.isNotBlank\\(\\) \\}")
                .containsMatchIn(row),
        )
        // The option table is the unit's, straight from the generated asset - the app adds no
        // labels of its own and offers nothing the firmware does not.
        assertTrue(
            "the menu no longer reads its options from the schema",
            Regex("val options = item\\.options \\?: return").containsMatchIn(row),
        )
        // The unit's labels run long; the default menu width ellipsized the part that tells two
        // time zones apart.
        assertTrue("the option menu lost its widened viewport", row.contains("widthIn(min = 220.dp, max = 360.dp)"))
    }
}
