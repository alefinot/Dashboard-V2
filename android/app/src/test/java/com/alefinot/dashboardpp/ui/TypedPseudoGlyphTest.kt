package com.alefinot.dashboardpp.ui

import com.alefinot.dashboardpp.ui.components.HudGlyphLedger
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing the owner is meant to read as a mark may be typed as a character.
 *
 * The app has three ways of doing this and has used all of them: a `▾` glued onto a field's label, a
 * `✓` in a menu row, and `−`/`+` as a stepper's face. Every one of them is a shape the app does not
 * own — it comes from whatever font Android happens to resolve, at whatever size and weight that font
 * is set to, and it is centred inside whatever box the text layout gives it. This device's sans font
 * has already turned two of them into boxed question marks where the arrow should have been (see
 * `HudIcons.Chevron`), and a mark that fails silently on the owner's phone is the worst kind of bug
 * this app can have, because the fix is not in the code that draws it.
 *
 * The rule is not "no non-ASCII": the dashboards print `°`, `Ω`, `±`, `·` and `→` as *data*, and
 * those are correct. What is forbidden is a character whose only job is to look like a control — an
 * arrow, a tick, a cross, a shape — and each one named here has a drawn answer in [HudGlyphLedger].
 */
class TypedPseudoGlyphTest {

    private val uiRoot = File("src/main/java/com/alefinot/dashboardpp/ui")

    /**
     * Codepoints that only ever appear here as a hand-drawn affordance, with the mark that owns the
     * meaning instead.
     */
    private val forbidden: Map<Int, String> = mapOf(
        0x25B2 to "HudIcons.Chevron rotated", // ▲
        0x25B4 to "HudIcons.Chevron rotated", // ▴
        0x25B6 to "HudIcons.Chevron", // ▶
        0x25B8 to "HudIcons.Chevron", // ▸
        0x25BC to "HudIcons.Chevron rotated", // ▼
        0x25BE to "HudIcons.Chevron rotated", // ▾
        0x25C0 to "HudIcons.Chevron rotated", // ◀
        0x25C2 to "HudIcons.Chevron rotated", // ◂
        0x2039 to "HudIcons.Chevron", // ‹
        0x203A to "HudIcons.Chevron", // ›
        0x276E to "HudIcons.Chevron", // ❮
        0x276F to "HudIcons.Chevron", // ❯
        0x2713 to "`action.accept` — HudIcons.Check", // ✓
        0x2714 to "`action.accept` — HudIcons.Check", // ✔
        0x2715 to "`action.dismiss` — HudIcons.Close", // ✕
        0x2716 to "`action.dismiss` — HudIcons.Close", // ✖
        0x2717 to "`action.dismiss` — HudIcons.Close", // ✗
        0x2718 to "`action.dismiss` — HudIcons.Close", // ✘
        0x2212 to "`action.step-down` — HudIcons.Minus", // −
        0x00D7 to "`action.dismiss` — HudIcons.Close", // ×
        0x25A0 to "a drawn body", // ■
        0x25A1 to "a drawn body", // □
        0x25CB to "a drawn body", // ○
        0x25CF to "`readout.recording` — HudIcons.Record", // ●
        0x25D0 to "a drawn body", // ◐
        0x25C6 to "a drawn body", // ◆
    )

    @Test
    fun `no control affordance is typed as a character`() {
        val hits = mutableListOf<String>()
        for (file in uiSources(uiRoot)) {
            val code = file.codeOnly()
            for ((codepoint, replacement) in forbidden) {
                var from = 0
                while (true) {
                    val at = code.indexOf(codepoint.toChar(), from)
                    if (at < 0) break
                    from = at + 1
                    hits += "${file.name}:${lineOf(code, at)} types U+${codepoint.toString(16).uppercase()}" +
                        " — draw $replacement instead"
                }
            }
        }
        assertTrue(
            "a mark the app cannot size, weight or centre is typed as text:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `the guard still guards`() {
        // A guard that quietly stopped matching anything is worse than no guard — it would report a
        // clean app forever. Every planted mark has to be found by the same call the scan uses.
        val planted = listOf(0x25BE, 0x2713, 0x2212, 0x25CF, 0x00D7)
        val probe = "label " + planted.joinToString(" ") { it.toChar().toString() }
        for (codepoint in planted) {
            assertTrue(
                "U+${codepoint.toString(16).uppercase()} is in the forbidden list but the scan cannot " +
                    "see it in a string that contains it",
                probe.indexOf(codepoint.toChar()) >= 0,
            )
        }

        // And comments really are stripped, or this file's own prose would trip the rule above.
        val snippet = "// \u25BE in a line comment\n/* \u2713 in a block comment */\nval x = 1\n"
        val stripped = snippet.codeOnlyFile()
        assertTrue("a comment survived the scanner: ${stripped.trim()}",
            stripped.none { forbidden.containsKey(it.code) })
        // The code beside it survives, or the scanner is deleting source rather than comments.
        assertTrue("the scanner ate the code as well", stripped.contains("val x = 1"))
    }

    /** Runs [codeOnly] on an in-memory snippet by way of a temp file, the same path the scan uses. */
    private fun String.codeOnlyFile(): String {
        val tmp = File.createTempFile("scan", ".kt")
        tmp.writeText(this)
        return tmp.codeOnly().also { tmp.delete() }
    }
}
