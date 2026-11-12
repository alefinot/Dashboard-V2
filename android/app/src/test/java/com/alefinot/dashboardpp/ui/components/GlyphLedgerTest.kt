package com.alefinot.dashboardpp.ui.components

import com.alefinot.dashboardpp.ui.argumentText
import com.alefinot.dashboardpp.ui.codeOnly
import com.alefinot.dashboardpp.ui.lineOf
import com.alefinot.dashboardpp.ui.uiSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icon vocabulary, enforced on the sources rather than on the artwork.
 *
 * [HudGlyphLedger] is a list, and a list nobody checks is a list that goes stale: the app grew seven
 * wordings for destroying something and four for putting something back because each screen wrote
 * down what it needed at the time. These tests are what stop the same thing happening to the marks —
 * a screen cannot draw a glyph the ledger does not know, a glyph cannot quietly start meaning two
 * things without the reason being written next to the assertion that fires, and a glyph-only control
 * cannot be built without the sentence a screen reader reads.
 */
class GlyphLedgerTest {

    private val uiRoot = File("src/main/java/com/alefinot/dashboardpp/ui")

    // ── the ledger is consistent with itself ──────────────────────────────────────

    @Test
    fun `every meaning resolves to a mark`() {
        for ((id, glyph) in HudGlyphLedger.glyphOf) {
            assertTrue("$id resolves to nothing", glyph.name.isNotBlank())
        }
    }

    @Test
    fun `a mark that answers to several meanings says why`() {
        val owners = HudGlyphLedger.glyphOf.entries
            .groupBy({ it.value.name.removePrefix("Hud") }, { it.key })
            .filter { it.value.size > 1 }

        val unjustified = owners.filterKeys { it !in HudGlyphLedger.SHARED }
        assertTrue(
            "these marks own more than one id with no reason written down: " +
                unjustified.entries.joinToString { "${it.key} -> ${it.value}" },
            unjustified.isEmpty(),
        )

        // The other direction: a justification for a collision that no longer exists is a comment
        // that lies about the set, which is worse than no comment.
        val phantom = HudGlyphLedger.SHARED.keys - owners.keys
        assertTrue("SHARED justifies a collision that is not in the ledger: $phantom", phantom.isEmpty())

        // And each reason has to actually be a reason, not a placeholder.
        for ((mark, why) in HudGlyphLedger.SHARED) {
            assertTrue("$mark's shared justification is one word", why.length > 40)
        }
    }

    @Test
    fun `the ledger's ids are dotted and lower case`() {
        // They read as meanings (`file.pick`, `value.default`), not as Kotlin members; the mark is the
        // member, and an id that looks like one is how a caller starts passing marks by hand again.
        val bad = HudGlyphLedger.glyphOf.keys.filter { !ID.matches(it) }
        assertTrue("not shaped as `area.verb`: $bad", bad.isEmpty())
    }

    // ── the ledger is consistent with the code ────────────────────────────────────

    @Test
    fun `every glyph the app draws is registered`() {
        val registered = HudGlyphLedger.glyphOf.values.map { it.name.removePrefix("Hud") }.toSet()
        // Every mark the ledger knows has to be a mark the set actually draws.
        val drawn = Regex("""val (\w+): ImageVector""").findAll(codeOnly("components/HudIcons.kt"))
            .map { it.groupValues[1] }.toSet()
        val ghost = registered - drawn
        assertTrue("the ledger points at marks HudIcons does not draw: $ghost", ghost.isEmpty())

        // And every mark the screens reach for has to be in the ledger. Comments are stripped, so a
        // name mentioned in prose cannot satisfy the guard — or trip it.
        val missing = linkedMapOf<String, MutableList<String>>()
        for (file in sources()) {
            for (match in Regex("""HudIcons\.(\w+)""").findAll(file.codeOnly())) {
                val name = match.groupValues[1]
                if (name !in registered) {
                    missing.getOrPut(name) { mutableListOf() } += file.name
                }
            }
        }
        assertTrue(
            "these glyphs are drawn by a screen but owned by no meaning: " +
                missing.entries.joinToString { "${it.key} in ${it.value.toSet()}" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `a glyph control cannot be built without the sentence it is known by`() {
        // The whole point of the shared control is that `description` has no default. This is the
        // second half: a call site that passes a blank has satisfied the compiler and nothing else.
        val offenders = mutableListOf<String>()
        for (file in sources().filterNot { it.name == "HudActions.kt" }) {
            // HudActions.kt is where the two controls are *declared*: there the parameter has no
            // default, which is the compiler's job. What this guard is for is the call sites, where
            // a description can be present and still be nothing.
            val code = file.codeOnly()
            for (call in listOf("HudGlyphAction", "HudCaptionedAction")) {
                var from = 0
                while (true) {
                    val at = code.indexOf(call + "(", from)
                    if (at < 0) break
                    val body = argumentText(code, code.indexOf('(', at))
                    from = at + 1
                    val description = Regex("""description\s*=\s*("[^"]*"|\w+)""").find(body)
                    if (description == null || description.groupValues[1] == "\"\"") {
                        offenders += "${file.name}: $call without a description"
                    }
                }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the three steps are the only sizes an icon is drawn at`() {
        // BarLayout used to name 30 and 22 free-standing; a fourth value between two of the steps is
        // how a system of marks turns into a pile of them. Anything in ui/ that sizes an icon has to
        // go through the enum.
        val strays = mutableListOf<String>()
        for (file in sources().filter { it.name != "HudActions.kt" }) {
            val code = file.codeOnly()
            for (match in Regex("""\bsize\s*=\s*(\d+(?:\.\d+)?)\.dp""").findAll(code)) {
                val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                strays += "${file.name}:$line sizes something ${match.groupValues[1]}.dp by hand"
            }
        }
        assertTrue(strays.joinToString("\n"), strays.isEmpty())
    }

    /**
     * Word-only buttons, counted rather than argued about.
     *
     * The grammar's end state is a word button nowhere except the confirm dialog, and a rule with no
     * number attached to it is a rule that quietly stays un-finished: the app had ~45 of these, and
     * "we'll get to them" is how seven wordings for destroying something got written in the first
     * place. So the count is pinned, and every phase that converts a screen lowers the ceiling. It
     * is a floor on progress in the only direction that matters here — a screen that adds a bare
     * word button, or a phase that forgets to take its number down, fails the build.
     *
     * `HudConfirmDialog.kt` is exempt by design (D3): the box is the safety for the risky verbs, and
     * the word on its buttons is the second channel that survives the glyph.
     */
    @Test
    fun theNumberOfWordOnlyButtonsOnlyGoesDown() {
        val wordOnly = mutableListOf<String>()
        for (file in sources().filter { it.name != "Hud.kt" && it.name != "HudConfirmDialog.kt" }) {
            val code = file.codeOnly()
            var from = 0
            while (true) {
                val at = code.indexOf("HudButton(", from)
                if (at < 0) break
                val body = argumentText(code, code.indexOf('(', at))
                from = at + 1
                if (!body.contains("glyph =")) {
                    wordOnly += "${file.name}:${lineOf(code, at)}"
                }
            }
        }
        assertTrue(
            "the app is down to $maxWordOnlyButtons word-only buttons; this found " +
                "${wordOnly.size}:\n" + wordOnly.joinToString("\n"),
            wordOnly.size <= maxWordOnlyButtons,
        )
    }

    // ── reading the sources ───────────────────────────────────────────────────────

    /**
     * Lower this as each phase converts a screen; never raise it. It is the count the ratchet test
     * found on 2026-11-12, after the trip screens came onto marks: only the phone settings page and
     * the not-linked cover (P7) are left standing.
     */
    private val maxWordOnlyButtons = 6

    // The scanner itself is shared with `TypedPseudoGlyphTest` in ui/SourceScan.kt, because both
    // guards read the same files and only one of them can be trusted if they disagree about what a
    // comment is.

    private fun sources(): List<File> = uiSources(uiRoot)

    private fun codeOnly(relative: String): String = File(uiRoot, relative).codeOnly()

    /** An id reads as a meaning — an area and a verb, dotted, lower case, hyphens inside a word. */
    private val ID = Regex("^[a-z]+(-[a-z]+)*(\\.[a-z]+(-[a-z]+)*)+$")
}
