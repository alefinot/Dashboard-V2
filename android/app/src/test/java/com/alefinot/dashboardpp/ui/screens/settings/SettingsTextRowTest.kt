package com.alefinot.dashboardpp.ui.screens.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every text row on the settings page is a string, and a string is drawn like one (maintainer,
 * 2026-11-16: "fix the custom signatures card it's looking like shit", then the same shape for
 * the two accel badge lines that had the identical defect).
 *
 * It used to be drawn like a figure: a 230 dp, end-aligned bare value at the end of the label's
 * line. On the bench phone's ~317 dp card row that leaves the setting's own name about 60 dp,
 * which wrapped `Badge Line 1 (e.g., '0-50')` over five lines and showed roughly a third of the
 * 45-character shipped watermark - both measured on the Mi 9 Lite the day this was written. So a
 * text row now takes the line under its name and the whole width of it, start-aligned, and an
 * empty one says so instead of being an invisible target.
 *
 * Two halves, so both are guarded: the row's shape (source-pinned, as the editor's other
 * row-shape tests are) and which rows the *band* rule splits (read off the generated asset, so a
 * new text key in the firmware cannot quietly change what a card looks like).
 */
class SettingsTextRowTest {

    private val settings = File("src/main/java/com/alefinot/dashboardpp/ui/screens/settings")

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

    /** The row's own band threshold, mirrored here so the asset assertions use the same rule. */
    private val longTextChars = 32

    @Test
    fun `a text row takes the line under its name and keeps the label`() {
        val exempt = Regex("NeedsItsOwnLine = setOf\\(([\\s\\S]*?)\\n\\)").find(row)
        assertTrue("the stacking rule lost its set", exempt != null)
        assertTrue(
            "a text row is back on its label's line: on a phone card that is the crushed label " +
                "and the unreadable watermark the maintainer had taken out on 2026-11-16",
            exempt!!.groupValues[1].contains("RowControl.TEXT"),
        )
        // The figure rows and the switch are what the 2026-11-08 rule was about, and they stay.
        for (beside in listOf("RowControl.NUMBER", "RowControl.TOGGLE")) {
            assertFalse("$beside was sent below its name", exempt.groupValues[1].contains(beside))
        }
        // It fills that line weighted, so the row's reset glyph is not starved to a zero-width
        // 48 dp box (the bug the Material slider carried).
        assertTrue(
            "the stacked row no longer weights a text field",
            Regex("RowControl\\.SELECT \\|\\|\\s*item\\.control == RowControl\\.TEXT").containsMatchIn(row),
        )
        assertTrue(
            "the field no longer takes the width the row gives it",
            Regex("if \\(wide\\) Modifier\\.widthIn\\(max = 420\\.dp\\) else Modifier\\.fillMaxWidth\\(\\)")
                .containsMatchIn(row),
        )
        assertTrue(
            "the field is aligned like a number again",
            Regex("textAlign = TextAlign\\.Start").containsMatchIn(row),
        )
        assertTrue(
            "an empty text field stopped saying it exists",
            row.contains("placeholder = \"empty"),
        )
    }

    @Test
    fun `the big band is kept only where the value fits it`() {
        assertTrue(
            "the row no longer carries the firmware-width threshold",
            Regex("private const val LongTextChars = $longTextChars").containsMatchIn(row),
        )
        assertTrue(
            "the band no longer follows the firmware's field size",
            Regex("textSize = if \\(isLongText\\(item\\)\\) 13\\.sp else TextUnit\\.Unspecified").containsMatchIn(row),
        )
        val textRows = SettingsFixtures.asset.allItems.filter { it.control == RowControl.TEXT }
        assertEquals(
            "the settings page is no longer exactly five text rows - recheck the card shapes",
            5,
            textRows.size,
        )
        assertEquals(
            "the small band no longer belongs to the Custom Signatures card alone",
            listOf("DASHBOARD_SIGNATURE", "REBOOT_SIGNATURE", "SPLASH_SIGNATURE"),
            textRows.filter { it.maxLength >= longTextChars }.map { it.key }.sorted(),
        )
        assertEquals(
            "a short string lost the page's value band",
            listOf("ACCEL_BADGE_LINE1", "ACCEL_BADGE_LINE2"),
            textRows.filter { it.maxLength in 1 until longTextChars }.map { it.key }.sorted(),
        )
    }
}
