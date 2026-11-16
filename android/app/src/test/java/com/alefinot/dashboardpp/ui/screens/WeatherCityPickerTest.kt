package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.api.Snapshot
import com.alefinot.dashboardpp.ble.BleReply
import com.alefinot.dashboardpp.ble.WeatherPush
import com.alefinot.dashboardpp.weather.CityFallback
import com.alefinot.dashboardpp.weather.Place
import com.alefinot.dashboardpp.weather.PlaceNames
import com.alefinot.dashboardpp.weather.WeatherCache
import com.alefinot.dashboardpp.weather.WeatherCoordinator
import com.alefinot.dashboardpp.weather.WeatherHttp
import com.alefinot.dashboardpp.weather.WeatherStore
import com.alefinot.dashboardpp.weather.WeatherUnit
import com.alefinot.dashboardpp.weather.WeatherWriter
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Weather Feed card stores, and what it refuses to store.
 *
 * This is the logic behind SAVE CITY, the candidate list and PLACE LANGUAGE, and it is worth
 * testing apart from the Compose around it because the mistake it guards against is silent:
 * the app used to store the gazetteer's first answer for a typed name, and on the maintainer's
 * phone that answer was **the country Romania, at 46.0 N 25.0 E**, under the word `Roma`.
 * Nothing complained; the dash simply showed weather for open countryside.
 *
 * The card holds a [WeatherWriter] rather than the DataStore-backed [com.alefinot.dashboardpp.weather.WeatherSettings]
 * so these writes are countable from a JVM test, the same seam `WeatherStore` and
 * `WeatherUnit` are for the coordinator.
 */
class WeatherCityPickerTest {

    private val http = FakeHttp()
    private val store = FakeStore()
    private val writer = FakeWriter()
    private val feed = WeatherCoordinator(FakeUnit(), store, http, PlaceNames(http))

    // ---- SAVE CITY: one answer, several, none ----------------------------------

    @Test
    fun `one match is stored at once`() {
        // Live answer for name=Roma&language=it, cut to the settlement the owner means: with
        // nothing to disambiguate, the card must not make them tap a row to get what they
        // already typed.
        http.searchBody = """{"results":[{"id":3169070,"name":"Roma","latitude":41.89193,
            |"longitude":12.51133,"feature_code":"PPLC","country":"Italia","admin1":"Lazio",
            |"population":2318895}]}""".trimMargin()

        val found = runBlocking { searchWeatherCity("Roma", feed, writer) }

        assertEquals("Fallback: Roma, Lazio, Italia", found.line)
        assertEquals(emptyList<Place>(), found.choices)
        assertEquals("Roma, Lazio, Italia", writer.city?.second?.let { placeLabel(it) })
    }

    @Test
    fun `several matches are offered and nothing is stored`() {
        // The heart of the fix: the app stops answering "which Munich?" on the owner's behalf.
        // Storing one of these would put a town on the dash the owner never chose, and storing
        // nothing *after* clearing the old one would leave the feed with no fallback at all —
        // so nothing is written at all until a row is tapped.
        http.searchBody = """{"results":[
            |{"id":1,"name":"Munich","latitude":48.13743,"longitude":11.57549,"feature_code":"PPLA",
            | "country":"Germany","admin1":"Bavaria","population":1505005},
            |{"id":2,"name":"Munich","latitude":48.66917,"longitude":-98.83263,"feature_code":"PPL",
            | "country":"United States","admin1":"North Dakota"},
            |{"id":3,"name":"Munich","latitude":12.08322,"longitude":-61.64477,"feature_code":"PPL",
            | "country":"Grenada"}]}""".trimMargin()

        val found = runBlocking { searchWeatherCity("Munich", feed, writer) }

        assertEquals(3, found.choices.size)
        assertEquals("\"Munich\" is 3 places \u2014 tap the one you mean.", found.line)
        assertNull("an ambiguous search stores nothing", writer.city)
    }

    @Test
    fun `no match keeps the text and stores no position`() {
        // The typed text survives so the field still shows what was typed and can be edited;
        // the missing place is what tells PUSH NOW there is nothing to fetch for it.
        http.searchBody = """{"generationtime_ms":0.14}"""

        val found = runBlocking { searchWeatherCity("Qwertyuiop", feed, writer) }

        assertEquals("\"Qwertyuiop\" not found.", found.line)
        assertEquals(emptyList<Place>(), found.choices)
        assertEquals("Qwertyuiop", writer.city?.first)
        assertNull(writer.city?.second)
    }

    @Test
    fun `a blank field clears the fallback`() {
        val found = runBlocking { searchWeatherCity("   ", feed, writer) }

        assertEquals("Fallback city cleared.", found.line)
        assertEquals(null to null, writer.city)
        assertEquals("a blank is not worth a network call", 0, http.urls.size)
    }

    @Test
    fun `the search is asked in the language the owner chose`() {
        store.language = "it"
        http.searchBody = """{"results":[]}"""

        runBlocking { searchWeatherCity("Roma", feed, writer) }

        val url = URLDecoder.decode(http.urls.single(), "UTF-8")
        assertTrue(url, url.contains("language=it"))
    }

    // ---- the candidate rows ---------------------------------------------------

    @Test
    fun `a candidate row says how big it is, because the names alone do not`() {
        // Both of these live rows read "Roma, Veneto, Italy" in the card. Without the count
        // the owner is being asked to pick between two identical labels.
        val big = Place("Roma", "Veneto", "Italy", 45.0, 12.0, id = 1L, population = 33L)
        val small = Place("Roma", "Veneto", "Italy", 45.1, 12.1, id = 2L, population = 13L)

        val rows = listOf(big, small).map { placeChoiceLabel(it) }

        assertEquals("Roma, Veneto, Italy · 33 people", rows[0])
        assertEquals("Roma, Veneto, Italy · 13 people", rows[1])
    }

    @Test
    fun `a big population is grouped, and a row that is not a town says so`() {
        val rome = Place("Roma", "Lazio", "Italia", 41.89193, 12.51133, id = 3169070L, population = 2318895L)
        // Grouped with Locale.US on purpose, the same reason coordinateLabel is: a European
        // locale would print 2.318.895 in a row whose commas and points already mean something
        // else.
        assertEquals("Roma, Lazio, Italia · 2,318,895 people", placeChoiceLabel(rome))

        // A non-settlement row only ever reaches the card when a query has no settlement at
        // all, and then the owner deserves to know they are looking at a mountain.
        val alps = Place("Alps", null, null, 46.5, 10.0, featureCode = "MTL")
        assertEquals("Alps · not a town", placeChoiceLabel(alps))

        // No population and a settlement: nothing invented, just the place.
        assertEquals("Munich, Bavaria, Germany", placeChoiceLabel(Place("Munich", "Bavaria", "Germany", 48.1, 11.5)))
    }

    // ---- PLACE LANGUAGE -------------------------------------------------------

    @Test
    fun `changing the language re-names the saved city`() {
        // Body captured live from v1/get?id=2867714&language=it.
        http.getBody = """{"id":2867714,"name":"Monaco di Baviera","latitude":48.13743,
            |"longitude":11.57549,"feature_code":"PPLA","country":"Germania","admin1":"Baviera"}"""
            .trimMargin()
        val saved = Place("München", "Bayern", "Deutschland", 48.13743, 11.57549, id = 2867714L)

        val line = runBlocking {
            applyPlaceLanguage("it", feed, writer, CityFallback("München", saved))
        }

        assertEquals("it", writer.language)
        assertEquals("Fallback: Monaco di Baviera, Baviera, Germania", line)
        assertEquals("Monaco di Baviera", writer.city?.second?.name)
        assertEquals("the typed text is the owner's, and stays theirs", "München", writer.city?.first)
    }

    @Test
    fun `a city saved without an id is not searched for again`() {
        // Re-searching a name to re-name it is how the wrong town gets picked; the card says
        // what the owner has to do instead.
        val legacy = Place("München", "Bayern", "Deutschland", 48.13743, 11.57549)

        val line = runBlocking {
            applyPlaceLanguage("de", feed, writer, CityFallback("München", legacy))
        }

        assertEquals("de", writer.language)
        assertTrue(line, line.contains("save München once more"))
        assertNull(writer.city)
    }

    @Test
    fun `a language change the provider cannot answer leaves the saved city alone`() {
        http.getBody = null // unreachable, bad id, gazetteer dropped the row
        val saved = Place("München", "Bayern", "Deutschland", 48.13743, 11.57549, id = 2867714L)

        val line = runBlocking {
            applyPlaceLanguage("it", feed, writer, CityFallback("München", saved))
        }

        assertTrue(line, line.contains("could not be re-read"))
        assertNull("a failed re-name must not clear the working fallback", writer.city)
    }

    @Test
    fun `a name the panel cannot draw is admitted on the spot`() {
        // Offered languages answer in Latin letters, but both providers fall back to the
        // *native* name when they have no translation — so the card still has to be able to
        // say it. Better here, with the owner looking at the card, than a bare coordinate pair
        // on the dash and a hunt for the reason.
        http.getBody = """{"id":524901,"name":"Москва","latitude":55.75,"longitude":37.61,
            |"feature_code":"PPLC","country":"Russia"}""".trimMargin()
        val saved = Place("Moscow", null, "Russia", 55.75, 37.61, id = 524901L)

        val line = runBlocking {
            applyPlaceLanguage("en", feed, writer, CityFallback("Moscow", saved))
        }

        assertTrue(line, line.contains("the unit shows its coordinates for that name"))
    }

    @Test
    fun `a language this build does not offer is refused`() {
        // Guard, not a feature: nothing downstream would stop `ru` reaching a provider, and
        // the panel would lose its place name. The list is the only authority.
        val saved = Place("Munich", "Bavaria", "Germany", 48.13743, 11.57549, id = 2867714L)

        val line = runBlocking {
            applyPlaceLanguage("ru", feed, writer, CityFallback("Munich", saved))
        }

        assertEquals("That language is not offered.", line)
        assertNull(writer.language)
        assertNull(writer.city)
        assertEquals(0, http.urls.size)
    }

    // ---- the fakes -----------------------------------------------------------

    /** Answers by URL prefix, and keeps every address it was asked for. */
    private class FakeHttp : WeatherHttp {
        val urls = mutableListOf<String>()
        var searchBody: String? = null
        var getBody: String? = null

        override suspend fun get(url: String): String? {
            urls += url
            return when {
                url.startsWith(PlaceNames.SEARCH_URL) -> searchBody
                url.startsWith(PlaceNames.GET_URL) -> getBody
                else -> null
            }
        }
    }

    private class FakeStore(var language: String = "en") : WeatherStore {
        override suspend fun enabledNow(): Boolean = true
        override suspend fun cityNow(): CityFallback = CityFallback(null, null)
        override suspend fun languageNow(): String = language
        override suspend fun cacheFor(unitKey: String): WeatherCache? = null
        override suspend fun saveCache(unitKey: String, cache: WeatherCache) = Unit
    }

    /** The card's two writes, recorded instead of committed to flash. */
    private class FakeWriter : WeatherWriter {
        var city: Pair<String?, Place?>? = null
        var language: String? = null

        override suspend fun setCity(typed: String?, place: Place?) {
            city = typed to place
        }

        override suspend fun setLanguage(tag: String) {
            language = tag
        }
    }

    private class FakeUnit : WeatherUnit {
        override suspend fun snapshot(): Snapshot? = null
        override suspend fun pushWeather(push: WeatherPush): BleReply = BleReply(ok = true)
        override suspend fun pushTime(epochSeconds: Long): BleReply = BleReply(ok = true)
    }
}
