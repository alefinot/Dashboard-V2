package com.alefinot.dashboardpp.weather

import java.util.Locale

/**
 * One place-name language the app may ask the providers for. [tag] is the lower-case code
 * both providers take (`language=` on Open-Meteo, `accept-language=` on Nominatim);
 * [endonym] is what the language calls itself, because "Српски" and "Serbian" are the same
 * choice and only one of them is readable to the person who speaks it.
 */
data class PlaceLanguage(val tag: String, val endonym: String)

/**
 * The place-name languages this app offers, and only these.
 *
 * The list is **filtered by what the panel can draw, not by what the provider can say.**
 * Both `Conthrax_SemiBold_16px.vlw` and `28px.vlw` carry exactly 95 glyphs, U+0020-U+007E,
 * so a name in Cyrillic, Greek, Arabic, Hebrew, Thai or any CJK script cannot reach the
 * dashboard at all — `panelLabel` rejects it and the unit falls back to coordinates. The
 * language selector drives the label on the **panel**, not just the list in this app, so
 * offering those languages would offer a name and deliver a coordinate pair.
 *
 * Membership is measured, not assumed. Each code was probed on 2026-11-16 against five
 * names (Berlin, Warsaw, Rome, Liege, Gdansk) and every returned `name` and `country` was
 * run through [PlaceNames.panelLabel]; a code is listed only when all of them survive the
 * fold inside U+0020-U+007E. That excludes `sr`, `mk`, `be`, `uk`, `ru`, `el`, `he`, `ar`,
 * `hi`, `th`, `ja`, `ko`, `zh`, `ka`, `hy`, `am`, `bn`, `ta`, `fa` and `ur` — Serbian
 * included, because the gazetteer answers Serbian in Cyrillic even though the language has
 * a Latin alphabet of its own. `no` and `tl` are folded into `nb` and `fil`, the codes the
 * provider actually answers.
 *
 * The list is app data, never fetched (AGENTS rule 19): the only network answers here are
 * place names. Endonyms are written with `\uXXXX` escapes so no source-encoding change on
 * any machine can quietly rewrite them.
 */
object PlaceLanguages {

    /** The language used when nothing matches — and today's long-standing behaviour. */
    const val FALLBACK_TAG = "en"

    /**
     * The languages on the card's **PLACE LANGUAGE** row: the main ones, and nothing else.
     * Every tag here was probed and answers in Latin letters the panel can draw (the probe is
     * in this object's doc). A short list is also a short scroll — the row is a dropdown, not
     * a language catalogue, and [FALLBACK_TAG] answers for everyone else.
     */
    val ALL: List<PlaceLanguage> = listOf(
        PlaceLanguage("ca", "Catal\u00E0"),
        PlaceLanguage("cs", "\u010Ce\u0161tina"),
        PlaceLanguage("da", "Dansk"),
        PlaceLanguage("de", "Deutsch"),
        PlaceLanguage("en", "English"),
        PlaceLanguage("es", "Espa\u00F1ol"),
        PlaceLanguage("fi", "Suomi"),
        PlaceLanguage("fil", "Filipino"),
        PlaceLanguage("fr", "Fran\u00E7ais"),
        PlaceLanguage("hr", "Hrvatski"),
        PlaceLanguage("hu", "Magyar"),
        PlaceLanguage("id", "Bahasa Indonesia"),
        PlaceLanguage("it", "Italiano"),
        PlaceLanguage("nb", "Norsk"),
        PlaceLanguage("nl", "Nederlands"),
        PlaceLanguage("pl", "Polski"),
        PlaceLanguage("pt", "Portugu\u00EAs"),
        PlaceLanguage("ro", "Rom\u00E2n\u0103"),
        PlaceLanguage("sk", "Sloven\u010Dina"),
        PlaceLanguage("sl", "Sloven\u0161\u010Dina"),
        PlaceLanguage("sv", "Svenska"),
        PlaceLanguage("tr", "T\u00FCrk\u00E7e"),
        PlaceLanguage("vi", "Ti\u1EBFng Vi\u1EC7t"),
    ).sortedBy { it.endonym.lowercase(Locale.ROOT) }

    /**
     * Spelling variants and retired codes that arrive from a `Locale` (or from an older
     * stored value) mapped onto the code the providers actually answer. Deliberately short:
     * a language that is not on this list is a language the panel cannot draw, and the
     * honest answer for it is [FALLBACK_TAG], not a near-miss.
     */
    private val ALIASES = mapOf(
        "no" to "nb", "nno" to "nb", "nob" to "nb",
        "tl" to "fil",
        "mo" to "ro",
    )

    /** The entry for an exact tag, or null when that tag is not offered. */
    fun byTag(tag: String?): PlaceLanguage? {
        val key = normalized(tag) ?: return null
        return ALL.firstOrNull { it.tag == key } ?: ALL.firstOrNull { it.tag == ALIASES[key] }
    }

    /**
     * The language to start with for a phone: its own, if the panel can draw what that
     * language's place names look like — otherwise English, which always can. Never a
     * guess at a nearby language: "the phone is in Russian, so let's ask in Bulgarian"
     * would put the wrong language's names on the dashboard.
     */
    fun fromLocale(locale: Locale): PlaceLanguage =
        byTag(locale.language) ?: PlaceLanguage(FALLBACK_TAG, endonymOf(FALLBACK_TAG))

    /**
     * The name to show for a tag.
     *
     * Two different empties, and they get different answers: **no tag at all** means the
     * owner never chose, which is the default language and is named like it ("English");
     * a tag we no longer offer means an older app stored something, and showing the bare tag
     * (`ru`) tells the truth about a setting the current list cannot honour, rather than
     * dressing it up as the language the app is actually asking for.
     */
    fun endonymOf(tag: String?): String {
        val stored = normalized(tag) ?: return endonymOf(FALLBACK_TAG)
        return ALL.firstOrNull { it.tag == stored }?.endonym
            ?: ALL.firstOrNull { it.tag == ALIASES[stored] }?.endonym
            ?: stored
    }

    /** A provider tag: lower-case, primary subtag only (`pt-BR` asks as `pt`). */
    private fun normalized(tag: String?): String? {
        val trimmed = tag?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        // A BCP-47 tag can carry script and region: `zh-Hans-CN`, `sr-Latn-RS`. The
        // providers key on the language subtag, and the subtags after it are exactly the
        // part that would otherwise make `sr-Latn` look like a language we support.
        return trimmed.substringBefore('-').substringBefore('_')
    }
}
