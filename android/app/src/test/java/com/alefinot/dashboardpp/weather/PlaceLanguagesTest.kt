package com.alefinot.dashboardpp.weather

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The place-name languages the app offers, and the rule that decides membership.
 *
 * That rule is **what the panel can draw, not what the provider can say.** Both Conthrax VLW
 * fonts in fonts/ carry exactly 95 glyphs (U+0020-U+007E), so offering Russian or Japanese
 * would offer the owner a place name and deliver a coordinate pair on the dashboard. The
 * exclusions below are the result of probing each code against five names on 2026-11-16 (see
 * `Implementation plans/CITY-PICKER-AND-PLACE-LANGUAGE-2026-11-16.md` §6.1); this test is
 * what stops a well-meaning later addition from putting an undrawable script back on the list.
 */
class PlaceLanguagesTest {

    @Test
    fun `every offered language is a clean unique tag`() {
        val tags = PlaceLanguages.ALL.map { it.tag }

        assertEquals("tags must be unique", tags.size, tags.toSet().size)
        assertTrue(tags.joinToString(), PlaceLanguages.ALL.all { it.tag.matches(Regex("[a-z]{2,3}")) })
        assertTrue(PlaceLanguages.ALL.all { it.endonym.isNotBlank() })
        assertNotNull("English has to be on the list, it is the fallback", PlaceLanguages.byTag("en"))
        assertEquals(PlaceLanguages.FALLBACK_TAG, PlaceLanguages.byTag("en")?.tag)
    }

    @Test
    fun `a language the panel cannot draw is not offered`() {
        // Each of these answers a place query in a script outside U+0020-U+007E — measured,
        // not assumed: sr returns Берлин (Cyrillic, despite Serbian having its own Latin
        // alphabet), el Μόναχο, ar ميونخ, he מינכן, ja ミュンヘン, zh 慕尼黑, th มิวนิก,
        // hi म्यूनिख, ko 로마, and the rest likewise. `panelLabel` would reject every one of
        // them, so the unit would show `48.137 N 11.575 E` and the owner would be left to
        // work out why.
        val excluded = listOf(
            "sr", "mk", "be", "uk", "ru", "el", "he", "ar", "hi", "th",
            "ja", "ko", "zh", "ka", "hy", "am", "bn", "ta", "fa", "ur",
        )
        for (tag in excluded) {
            assertNull("$tag must not be offered", PlaceLanguages.byTag(tag))
        }
    }

    @Test
    fun `the offered languages are the ones that were probed`() {
        // Pinning the set, so membership is a decision someone has to make twice: here and in
        // the probe that backs it. Anything new needs a live answer to check against the font.
        // This is deliberately the *main* languages, not every language the provider happens to
        // answer in Latin letters — a longer list is just a longer scroll on the card.
        val expected = (
            "ca cs da de en es fi fil fr hr hu id it nb nl pl pt ro sk sl sv tr vi"
            ).split(" ")

        assertEquals(expected.sorted(), PlaceLanguages.ALL.map { it.tag }.sorted())
    }

    @Test
    fun `a language the provider answers under another code still resolves`() {
        // `no` is a real ISO code and the code a phone reports, but the gazetteer answers
        // Norwegian as `nb`; Tagalog is indexed as `fil`. A phone sending the familiar code
        // must still get names rather than the fallback.
        assertEquals("nb", PlaceLanguages.byTag("no")?.tag)
        assertEquals("nb", PlaceLanguages.byTag("nno")?.tag)
        assertEquals("fil", PlaceLanguages.byTag("tl")?.tag)
        assertEquals("ro", PlaceLanguages.byTag("mo")?.tag)
    }

    @Test
    fun `the language subtag is what is matched`() {
        // A Locale or a stored BCP-47 tag can carry script and region. `sr-Latn` is the trap:
        // the script subtag says Latin, but the gazetteer answers Serbian in Cyrillic, so
        // matching the whole tag would put an undrawable name on the panel.
        assertEquals("de", PlaceLanguages.byTag("DE")?.tag)
        assertEquals("pt", PlaceLanguages.byTag("pt-BR")?.tag)
        assertEquals("de", PlaceLanguages.byTag(" de ")?.tag)
        assertNull(PlaceLanguages.byTag("sr-Latn"))
        assertNull(PlaceLanguages.byTag("zh-Hans-CN"))
        assertNull(PlaceLanguages.byTag(""))
        assertNull(PlaceLanguages.byTag(null))
    }

    @Test
    fun `a phone starts in its own language when the panel can draw it`() {
        assertEquals("it", PlaceLanguages.fromLocale(Locale.ITALIAN).tag)
        assertEquals("de", PlaceLanguages.fromLocale(Locale.GERMAN).tag)
        assertEquals("pl", PlaceLanguages.fromLocale(Locale("pl")).tag)
        assertEquals("nb", PlaceLanguages.fromLocale(Locale("no")).tag)
        // A region-only locale still lands on its language.
        assertEquals("pt", PlaceLanguages.fromLocale(Locale("pt", "BR")).tag)
    }

    @Test
    fun `a phone whose script the panel cannot draw starts in English`() {
        // Never a guess at a neighbour: "the phone is Russian, so ask in Bulgarian" would put
        // the wrong language's names on the dashboard, which is a worse surprise than English.
        for (language in listOf("ru", "uk", "sr", "el", "ar", "he", "ja", "ko", "zh", "th", "hi")) {
            assertEquals(
                "phone in $language",
                PlaceLanguages.FALLBACK_TAG,
                PlaceLanguages.fromLocale(Locale(language)).tag,
            )
        }
        assertEquals(PlaceLanguages.FALLBACK_TAG, PlaceLanguages.fromLocale(Locale.ROOT).tag)
        assertEquals(PlaceLanguages.FALLBACK_TAG, PlaceLanguages.fromLocale(Locale("xx")).tag)
    }

    @Test
    fun `a language is shown by the name it calls itself`() {
        // "Polski" not "Polish": the person who reads that row is the person who speaks it.
        assertEquals("English", PlaceLanguages.byTag("en")?.endonym)
        assertEquals("Polski", PlaceLanguages.byTag("pl")?.endonym)
        assertEquals("Deutsch", PlaceLanguages.byTag("de")?.endonym)
        assertEquals("Italiano", PlaceLanguages.byTag("it")?.endonym)
        assertEquals("Türkçe", PlaceLanguages.byTag("tr")?.endonym)
    }

    @Test
    fun `a stored value this build does not offer reads as its own tag`() {
        // An older app may have stored `ru` before the list was filtered. Showing the raw tag
        // is honest; inventing an endonym for a language that is not offered is not, and
        // showing "English" while a Russian value is stored would be a lie about the setting.
        assertEquals("ru", PlaceLanguages.endonymOf("ru"))
        assertEquals("English", PlaceLanguages.endonymOf("en"))
        assertEquals("English", PlaceLanguages.endonymOf(null))
        assertEquals("English", PlaceLanguages.endonymOf("  "))
        assertFalse(PlaceLanguages.ALL.isEmpty())
    }
}
