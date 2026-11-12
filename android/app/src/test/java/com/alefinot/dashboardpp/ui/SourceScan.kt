package com.alefinot.dashboardpp.ui

import java.io.File

/**
 * The small amount of source reading the vocabulary guards share.
 *
 * These guards are the only way the icon rules survive contact with the next screen: a glyph drawn in
 * one file and re-invented in another looks fine in review, so what is enforced here is the text of
 * the sources — which mark a screen reaches for, whether the control it passes it to was given a
 * sentence for the screen reader, and whether a control affordance was typed as a character instead
 * of drawn.
 *
 * [codeOnly] is why these guards can be strict. Half of the things they forbid are things the code
 * explains at length — `"▾"` appears in a KDoc to say what the drawn chevron replaced — and a comment
 * must not be able to satisfy a guard, or trip one.
 */

/** The app's UI sources, minus the two files that *define* the vocabulary. */
internal fun uiSources(uiRoot: File): List<File> {
    check(uiRoot.exists()) { "the guard cannot see the sources at ${uiRoot.path}" }
    return uiRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.name != "HudIcons.kt" && it.name != "HudGlyphLedger.kt" }
        .toList()
}

/**
 * Comment-free source.
 *
 * A scanner, not a regex: the block-comment pattern that worked on paper (`(?:[^*]|\*(?!/))*`) blew
 * the JVM stack on a 900-line screen, which is the kind of failure a guard should not have.
 */
internal fun File.codeOnly(): String {
    check(exists()) { "missing $path" }
    val src = readText()
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
                // Strings are copied: a `//` inside one is not a comment, and neither is a quote.
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

/** The text between a `(` and its matching `)`. */
internal fun argumentText(code: String, open: Int): String {
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

/** The line a character offset falls on, 1-based, so a failure message can be acted on. */
internal fun lineOf(code: String, offset: Int): Int = code.substring(0, offset).count { it == '\n' } + 1
