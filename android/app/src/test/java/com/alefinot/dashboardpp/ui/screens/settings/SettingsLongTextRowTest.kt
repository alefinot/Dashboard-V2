package com.alefinot.dashboardpp.ui.screens.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **Custom Signatures** card, the way it reads now (maintainer, 2026-11-16: "fix the custom
 * signatures card it's looking like shit").
 *
 * It was crushed. The card's three rows are strings the firmware can hold 48 and 96 bytes of, and
 * they were drawn like any other value: a 230 dp, end-aligned bare figure at the end of the
 * label's line. A shipped watermark of 45 characters does not fit that, so the card showed about a
 * third of the string and squeezed the setting's own name to three words stacked on top of each
 * other (`Main / Dashboard / Watermark`) - both measured on the bench Mi 9 Lite the day this was
 * written. A string is now a row of its own: label line, then the field at the width the card has,
 * start-aligned, at the page's small band.
 *
 * Two halves, so both are guarded: the row's shape (source-pinned, as the editor's other
 * row-shape tests are) and which rows the rule actually reaches (read off the generated asset, so
 * a new long text key in the firmware cannot quietly change what a card looks like).
 */
class SettingsLongTextRowTest {

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

    /** The row's own threshold, mirrored here so the asset assertions below are the same rule. */
    private val longTextChars = 32

    @Test
    fun `a long string takes its setting's whole line and keeps its label`() {
        assertTrue(
            "the row no longer carries the firmware-width threshold",
            Regex("private const val LongTextChars = $longTextChars").containsMatchIn(row),
        )
        assertTrue(
            "the stacking rule stopped consulting it, so a signature is back on its label's line",
            Regex("item\\.control in NeedsItsOwnLine \\|\\| isLongText\\(item\\)").containsMatchIn(row),
        )
        val exempt = Regex("NeedsItsOwnLine = setOf\\(([\\s\\S]*?)\\n\\)").find(row)
        assertTrue(
            "a text row is in the exemption set: every value on the page is back below its name, " +
                "which is the rule the maintainer set on 2026-11-08",
            exempt != null && !exempt.groupValues[1].contains("RowControl.TEXT"),
        )
        // And it fills the line it is stacked on, weighted, so the row's reset glyph is not
        // starved to a zero-width 48 dp box (the bug the Material slider carried).
        assertTrue(
            "the stacked row no longer weights a long text field",
            Regex("RowControl\\.SELECT \\|\\|\\s*isLongText\\(item\\)").containsMatchIn(row),
        )
        assertTrue(
            "the field no longer takes the width the row gives it",
            Regex("if \\(wide\\) Modifier\\.widthIn\\(max = 420\\.dp\\) else Modifier\\.fillMaxWidth\\(\\)")
                .containsMatchIn(row),
        )
        // A string is read from the left, and at the page's small band: both are the difference
        // between reading the stored watermark and seeing the tail of it.
        assertTrue(
            "the field is aligned like a number again",
            Regex("textAlign = if \\(longText\\) TextAlign\\.Start else TextAlign\\.End").containsMatchIn(row),
        )
        assertTrue(
            "the field is drawn at the big band again, so a 45-character string stops fitting",
            Regex("textSize = if \\(longText\\) 13\\.sp else TextUnit\\.Unspecified").containsMatchIn(row),
        )
        assertTrue(
            "an empty signature field stopped saying it exists",
            row.contains("placeholder = if (longText) \"empty — tap to type\" else \"\""),
        )
    }

    @Test
    fun `the rule reaches the three signature rows and nobody else on the page`() {
        val textRows = SettingsFixtures.asset.allItems.filter { it.control == RowControl.TEXT }
        val long = textRows.filter { it.maxLength >= longTextChars }.map { it.key }.sorted()
        assertEquals(
            "the long-string shape is no longer exactly the Custom Signatures card",
            listOf("DASHBOARD_SIGNATURE", "REBOOT_SIGNATURE", "SPLASH_SIGNATURE"),
            long,
        )
        // The accel badge lines are short values and keep their place on the label's line.
        val short = textRows.filter { it.maxLength in 1 until longTextChars }.map { it.key }.sorted()
        assertEquals(
            "a short text row moved below its name",
            listOf("ACCEL_BADGE_LINE1", "ACCEL_BADGE_LINE2"),
            short,
        )
    }
}
