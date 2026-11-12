package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The firmware version picker is a HUD option menu now, and its "already running"
 * sentence is a status rather than an amber warning (maintainer request, 2026-11-12).
 *
 * Like the vehicle-type dropdown, this is source-pinned: the visible properties are the
 * shared three-row menu furniture and the exact wording/color of the release status line.
 * Neither is reachable through the unit, so a device can prove the drawing but cannot prove
 * the contract as narrowly as these reads can.
 */
class FirmwareVersionMenuTest {

    private val sourceRoot = File("src/main/java/com/alefinot/dashboardpp")

    /** Comment-stripped read, matching the dropdown's other source-pinned tests. */
    private fun code(vararg parts: String): String {
        val f = File(sourceRoot, parts.joinToString(File.separator))
        assertTrue("missing ${f.path}", f.isFile)
        var s = f.readText()
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\n]*"), "")
    }

    private val card = code("ui", "screens", "FirmwareOtaControl.kt")
    private val menu = code("ui", "components", "HudSelectMenu.kt")

    @Test
    fun `the firmware version picker uses the shared HUD select viewport`() {
        assertTrue("the firmware card does not use the shared HUD select", card.contains("HudSelectMenu("))
        assertFalse("the firmware card still uses Material menu rows", card.contains("DropdownMenuItem("))
        assertFalse("the shared menu still uses Material menu rows", menu.contains("DropdownMenuItem("))
        assertTrue("the firmware menu width is not bounded", card.contains("menuModifier = Modifier.widthIn"))

        assertTrue("the menu is not capped at three visible rows", menu.contains("heightIn(max = HudSelectMenuMaxHeight)"))
        assertTrue("the menu is not scrollable", menu.contains("scrollState = menuScrollState"))
    }

    @Test
    fun `the up-to-date release line is green and names the release`() {
        assertTrue(
            "the status line no longer knows whether the chosen release is running",
            card.contains("val upToDate = release.version == unitRunning"),
        )
        assertTrue(
            "the up-to-date line does not name the release",
            card.contains("\"\${release.title} - Up to date\""),
        )
        assertTrue(
            "the up-to-date line is not green",
            card.contains("color = if (upToDate) HudColors.Green else HudColors.TextDim"),
        )
    }
}
