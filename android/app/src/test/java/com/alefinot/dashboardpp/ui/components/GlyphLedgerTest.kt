package com.alefinot.dashboardpp.ui.components

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
            for (match in Regex("""HudIcons\.(\w+)""").findAll(codeOnly(file))) {
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
            val code = codeOnly(file)
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
            val code = codeOnly(file)
            for (match in Regex("""\bsize\s*=\s*(\d+(?:\.\d+)?)\.dp""").findAll(code)) {
                val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                strays += "${file.name}:$line sizes something ${match.groupValues[1]}.dp by hand"
            }
        }
        assertTrue(strays.joinToString("\n"), strays.isEmpty())
    }

    // ── reading the sources ───────────────────────────────────────────────────────

    private fun sources(): List<File> {
        assertTrue("the guard cannot see the sources at $uiRoot", uiRoot.exists())
        return uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "HudIcons.kt" && it.name != "HudGlyphLedger.kt" }
            .toList()
    }

    private fun codeOnly(relative: String): String = codeOnly(File(uiRoot, relative))

    /**
     * Comment-free source, so a description remembered in a comment cannot satisfy a guard.
     *
     * A scanner, not a regex: the block-comment pattern that worked on paper (`(?:[^*]|\*(?!/))*`)
     * blew the JVM stack on a 900-line screen, which is the kind of failure a guard should not have.
     */
    private fun codeOnly(file: File): String {
        assertTrue("missing ${file.path}", file.exists())
        val src = file.readText()
        val out = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                blockDepth > 0 -> when {
                    c == '*' && next == '/' -> { blockDepth--; i += 2 }
                    c == '/' && next == '*' -> { blockDepth++; i += 2 }
                    // A newline is kept so a line number in a failure message still means something.
                    else -> { if (c == '\n') out.append(c); i++ }
                }

                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }

                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }

                c == '"' || c == '\'' -> {
                    // Strings are copied: a `//` inside one is not a comment, and neither is a `"`.
                    val quote = c
                    out.append(c); i++
                    while (i < src.length && src[i] != quote) {
                        if (src[i] == '\\') {
                            out.append(src[i]); i++
                            if (i < src.length) { out.append(src[i]); i++ }
                        } else {
                            out.append(src[i]); i++
                        }
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }

                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** An id reads as a meaning — an area and a verb, dotted, lower case, hyphens inside a word. */
    private val ID = Regex("^[a-z]+(-[a-z]+)*(\\.[a-z]+(-[a-z]+)*)+$")

    /** The text between a `(` and its matching `)`, one nesting level at a time. */
    private fun argumentText(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        return code.substring(open + 1)
    }

    private operator fun String.contains(other: Regex): Boolean = other.containsMatchIn(this)
}
