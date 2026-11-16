package com.alefinot.dashboardpp.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * A place name for the unit's weather card, from two providers and one fallback.
 *
 * Both provider answers below are pasted from live calls made while this test was
 * written (2026-11-05), because the parser is only worth trusting against the
 * shape the service really returns: Nominatim's `address` block, Open-Meteo's
 * `results` array, and the fact that Open-Meteo answers a no-match search with
 * **no `results` key at all** (`{"generationtime_ms":0.14}`) rather than an empty list.
 */
class PlaceNamesTest {

    /** Records what was asked, and answers from a queue. */
    private class RecordingHttp(private vararg val answers: String?) : WeatherHttp {
        val urls = mutableListOf<String>()
        var calls = 0
        override suspend fun get(url: String): String? {
            calls++
            urls += url
            return answers.getOrElse(calls - 1) { null }
        }
    }

    // ---- the two requests, and what goes into them -------------------------

    @Test
    fun `the search is the typed city and nothing else`() {
        val url = PlaceNames.searchUrl("München am See", "en")

        assertTrue(url, url.startsWith(PlaceNames.SEARCH_URL))
        // The typed name goes out percent-encoded; a raw space or a raw umlaut in
        // the query would be a malformed URL, and the provider would answer nothing.
        assertTrue(url, url.contains("name=M%C3%BCnchen+am+See"))
        assertTrue(url, !url.contains(" "))
        assertTrue(url, url.contains("count=${PlaceNames.CANDIDATE_COUNT}"))
        assertTrue(url, url.contains("language=en"))
        assertTrue(url, url.contains("format=json"))
    }

    @Test
    fun `the language the owner chose goes out to both providers`() {
        // Not cosmetic, and this is the test that says so. The gazetteer matches three or
        // more characters against the **beginning of the name in the requested language**,
        // so `Roma` in English can only ever find the country Romania, and in Italian it
        // finds Roma, Lazio, Italia first. A language that quietly stopped reaching the URL
        // would put the wrong town back on the card with no error anywhere.
        assertTrue(PlaceNames.searchUrl("Roma", "it").contains("language=it"))
        assertTrue(PlaceNames.reverseUrl(41.89193, 12.51133, "it")!!.contains("accept-language=it"))
        assertTrue(PlaceNames.getUrl(3169070, "pl").contains("language=pl"))

        // A BCP-47 tag off a phone locale asks as its language subtag, and junk in a tag is
        // removed rather than pasted into a URL: a stray `;` or space would silently change
        // the request the provider sees.
        assertTrue(PlaceNames.searchUrl("Roma", "pt-BR").contains("language=pt"))
        val messy = PlaceNames.searchUrl("Roma", "it; x")
        assertTrue(messy, messy.contains("language=itx"))
        assertTrue(messy, !messy.contains(";"))
        assertTrue(PlaceNames.searchUrl("Roma", "").contains("language=${PlaceLanguages.FALLBACK_TAG}"))
    }

    @Test
    fun `the reverse query asks for a city-level name in the chosen language`() {
        val url = PlaceNames.reverseUrl(48.13743, 11.57549, "en")!!

        assertTrue(url, url.startsWith(PlaceNames.REVERSE_URL))
        // Seven decimals, matching every other coordinate this app sends; and
        // accept-language=en is what turns München into Munich for us.
        assertTrue(url, url.contains("lat=48.1374300"))
        assertTrue(url, url.contains("lon=11.5754900"))
        assertTrue(url, url.contains("format=jsonv2"))
        assertTrue(url, url.contains("addressdetails=1"))
        assertTrue(url, url.contains("accept-language=en"))
        // zoom=12 asks for a city, not a house: at the default zoom Nominatim
        // answers the nearest building, which is not a name for a dashboard.
        assertTrue(url, url.contains("zoom=12"))
    }

    @Test
    fun `a coordinate that is not a coordinate is not sent`() {
        assertNull(PlaceNames.reverseUrl(Double.NaN, 11.5, "en"))
        assertNull(PlaceNames.reverseUrl(48.1, Double.POSITIVE_INFINITY, "en"))
        assertNull(PlaceNames.reverseUrl(91.0, 11.5, "en"))
        assertNull(PlaceNames.reverseUrl(48.1, 181.0, "en"))
    }

    // ---- Open-Meteo's search answer ---------------------------------------

    @Test
    fun `the search's first hit is the one we take`() {
        // Live answer for name=Munich&count=3&language=en&format=json.
        val body = """{"results":[{"id":2867714,"name":"Munich","latitude":48.13743,
            |"longitude":11.57549,"elevation":524.0,"feature_code":"PPLA","country_code":"DE",
            |"population":1505005,"country":"Germany","admin1":"Bavaria"},
            |{"id":5060556,"name":"Munich","latitude":48.66917,"longitude":-98.83263,
            |"country_code":"US","country":"United States","admin1":"North Dakota"},
            |{"id":3580031,"name":"Munich","latitude":12.08322,"longitude":-61.64477,
            |"country_code":"GD","country":"Grenada"}],"generationtime_ms":0.22661686}""".trimMargin()

        val place = PlaceNames.parseSearch(body).first()

        assertEquals("Munich", place.name)
        assertEquals(48.13743, place.lat, 0.0)
        assertEquals(11.57549, place.lon, 0.0)
        assertEquals("Bavaria", place.admin1)
        assertEquals("Germany", place.country)
        // What the picker reads: the kind of place this is, its population, and the
        // gazetteer's own key (a JSON number, which BleJson reads as Double).
        assertEquals("PPLA", place.featureCode)
        assertEquals(2867714L, place.id)
        assertEquals(1505005L, place.population)
        // The other two are the same name in other countries; the card has to show
        // which one was chosen, and the coordinator has to keep all three's names
        // out of the panel's field. parseSearch keeps them in order, unsorted.
        val all = PlaceNames.parseSearch(body)
        assertEquals(3, all.size)
        assertNull("North Dakota row carries no population in the live answer", all[1].population)
    }

    @Test
    fun `a row knows whether it is a settlement`() {
        // feature_code is GeoNames' place type: PPL and its administrative ranks are places
        // people live in, PCLI is a country, MT is a mountain. A missing code is treated as a
        // settlement — rejecting a place for a field the provider omitted is how the picker
        // ends up empty for a name that plainly exists.
        assertTrue(Place("Rome", "Lazio", "Italy", 41.89, 12.51, featureCode = "PPLC").isSettlement)
        assertTrue(Place("Roma", "Botoșani", "Romania", 47.83, 26.6, featureCode = "PPLA2").isSettlement)
        assertTrue(Place("Rome", "Oneida", null, 43.21, -75.45, featureCode = "PPL").isSettlement)
        assertTrue(Place("Somewhere", null, null, 1.0, 1.0).isSettlement)
        assertFalse(Place("Romania", null, "Romania", 46.0, 25.0, featureCode = "PCLI").isSettlement)
        assertFalse(Place("Zugspitze", null, null, 47.42, 10.98, featureCode = "MT").isSettlement)
    }

    @Test
    fun `a search offers places, not the country that starts with the same letters`() {
        // The live `name=Roma&count=3&language=en` answer, verbatim: the gazetteer indexes
        // countries next to towns and ranks loosely, so results[0] is the country — and the
        // app used to store exactly that as the owner's city. settlementFirst is the filter
        // that keeps it out of the card.
        val romaniaFirst = """{"results":[
            |{"id":798549,"name":"Romania","latitude":46.0,"longitude":25.0,"feature_code":"PCLI",
            | "country_code":"RO","population":19020271,"country":"Romania"},
            |{"id":668737,"name":"Roma","latitude":47.83333,"longitude":26.6,"feature_code":"PPLA2",
            | "country_code":"RO","population":3218,"country":"Romania","admin1":"Botoșani County"}],
            |"generationtime_ms":0.24449825}""".trimMargin()

        val kept = PlaceNames.settlementFirst(PlaceNames.parseSearch(romaniaFirst))

        assertEquals(listOf("Roma"), kept.map { it.name })
        assertEquals(47.83333, kept[0].lat, 0.0)
    }

    @Test
    fun `a name with no settlement at all still gets its candidates`() {
        // The filter must never produce an empty card for a real query. "Alps" is a
        // mountain range in every language, so a settlements-only list would be blank and
        // the owner would be told a place they can see on the map does not exist.
        val onlyMountains = """{"results":[
            |{"id":1,"name":"Alps","latitude":46.5,"longitude":10.0,"feature_code":"MTL"},
            |{"id":2,"name":"Alps","latitude":38.0,"longitude":-98.0,"feature_code":"MTL"}]}""".trimMargin()

        val rows = PlaceNames.parseSearch(onlyMountains)

        assertEquals(rows, PlaceNames.settlementFirst(rows))
        assertEquals(2, PlaceNames.settlementFirst(rows).size)
    }

    @Test
    fun `the typed city comes back as candidates and the request carries the language`() {
        val body = """{"results":[{"id":3169070,"name":"Roma","latitude":41.89193,
            |"longitude":12.51133,"feature_code":"PPLC","country":"Italia","admin1":"Lazio",
            |"population":2318895}]}""".trimMargin()
        val http = RecordingHttp(body)

        val found = runBlocking { PlaceNames(http).searchCandidates("Roma ", "it") }

        assertEquals(1, found.size)
        assertEquals("Roma", found[0].name)
        assertEquals(3169070L, found[0].id)
        assertEquals(1, http.calls)
        assertTrue(http.urls[0], http.urls[0].contains("name=Roma"))
        assertTrue(http.urls[0], http.urls[0].contains("language=it"))
    }

    @Test
    fun `a blank query is not asked about`() {
        val http = RecordingHttp("""{"results":[]}""")

        assertEquals(emptyList<Place>(), runBlocking { PlaceNames(http).searchCandidates("   ", "en") })
        assertEquals(0, http.calls)
    }

    @Test
    fun `no results is not a place`() {
        // The live no-match body: Open-Meteo omits the key entirely.
        assertEquals(0, PlaceNames.parseSearch("""{"generationtime_ms":0.14388561}""").size)
        assertEquals(0, PlaceNames.parseSearch("""{"results":[]}""").size)
        assertEquals(0, PlaceNames.parseSearch("not json").size)
    }

    @Test
    fun `a saved place is re-read in the new language`() {
        // v1/get?id= answers with the place **at the root**, not inside a `results` array:
        // the same gazetteer, a different shape, and the reason this needs its own parser.
        // Captured live for id=3169070 (Rome) with language=it.
        val romeInItalian = """{"id":3169070,"name":"Roma","latitude":41.89193,"longitude":12.51133,
            |"elevation":20.0,"feature_code":"PPLC","country_code":"IT","population":2318895,
            |"country":"Italia","admin1":"Lazio","admin2":"Provincia di Roma"}""".trimMargin()
        val http = RecordingHttp(romeInItalian)

        val renamed = runBlocking {
            PlaceNames(http).placeById(3169070L, "it")
        }

        assertEquals("Roma", renamed?.name)
        assertEquals("Italia", renamed?.country)
        assertEquals(3169070L, renamed?.id)
        assertTrue(http.urls[0], http.urls[0].startsWith(PlaceNames.GET_URL))
        assertTrue(http.urls[0], http.urls[0].contains("id=3169070"))
        assertTrue(http.urls[0], http.urls[0].contains("language=it"))
    }

    @Test
    fun `an error body from the gazetteer is not a place`() {
        // What a bad id really returns (live, 2026-11-16), in case the status code is 200.
        assertNull(
            PlaceNames.parsePlace(
                """{"error":{"type":"unknown","message":"StatusCode: non 2xx"},"content":[]}""",
            ),
        )
        // And a place at the root with no name is not one either.
        assertNull(PlaceNames.parsePlace("""{"id":1,"latitude":1.0,"longitude":1.0}"""))
    }

    @Test
    fun `a result without coordinates is not a place`() {
        val body = """{"results":[{"name":"Nowhere"},{"name":"Here","latitude":1.0,"longitude":2.0}]}"""

        val places = PlaceNames.parseSearch(body)

        assertEquals(1, places.size)
        assertEquals("Here", places[0].name)
    }

    @Test
    fun `a typed city with no answer is an empty list`() {
        val http = RecordingHttp("""{"generationtime_ms":0.14}""")

        assertEquals(
            emptyList<Place>(),
            runBlocking { PlaceNames(http).searchCandidates("München am See", "en") },
        )
        assertEquals(1, http.calls)
    }

    // ---- Nominatim's reverse answer ---------------------------------------

    @Test
    fun `the name is the city, not the district it sits in`() {
        // Live answer for a point in Munich's Old Town, zoom=12&addressdetails=1.
        val body = """{"place_id":128261723,"osm_type":"relation","osm_id":56391,
            |"lat":"48.1378285","lon":"11.5745823","category":"boundary",
            |"addresstype":"suburb","name":"Old Town",
            |"display_name":"Old Town, Munich, Bavaria, Germany",
            |"address":{"suburb":"Old Town","city":"Munich","state":"Bavaria",
            |"country":"Germany","country_code":"de"}}""".trimMargin()

        // "Old Town" is the nearest named place, and useless on a dashboard: the
        // administrative name is the one a driver recognises.
        assertEquals("Munich", PlaceNames.parseReverse(body))
    }

    @Test
    fun `a town is the name where there is no city`() {
        // Live answer for a point in the Bavarian Alps.
        val body = """{"place_id":71088841,"addresstype":"town","name":"Garmisch-Partenkirchen",
            |"display_name":"Garmisch-Partenkirchen, Landkreis Garmisch-Partenkirchen, Bavaria, Germany",
            |"address":{"town":"Garmisch-Partenkirchen","county":"Landkreis Garmisch-Partenkirchen",
            |"state":"Bavaria","country":"Germany"}}""".trimMargin()

        assertEquals("Garmisch-Partenkirchen", PlaceNames.parseReverse(body))
    }

    @Test
    fun `the district alone is not a name and neither is a bare display string`() {
        // suburb / neighbourhood are skipped on purpose; only the county is left,
        // which is better than coordinates.
        assertEquals(
            "Landkreis Rosenheim",
            PlaceNames.parseReverse("""{"address":{"suburb":"Grafing","county":"Landkreis Rosenheim"}}"""),
        )
        // No address block at all: nothing to build a short name from, so no name.
        assertNull(PlaceNames.parseReverse("""{"name":"Old Town","display_name":"Old Town, Munich"}"""))
        assertNull(PlaceNames.parseReverse("garbage"))
    }

    @Test
    fun `Nominatim is not asked twice inside one second`() {
        val body = """{"address":{"city":"Munich"}}"""
        var clock = 10_000L
        val http = RecordingHttp(body, body, body)
        val names = PlaceNames(http) { clock }

        runBlocking {
            assertEquals("Munich", names.reverseName(48.13, 11.57, "en"))
            assertEquals(1, http.calls)

            // Etiquette: Nominatim's usage policy is one request per second, and the
            // car's position updates once a second — so a request inside the window is
            // refused locally and the caller falls back. This is not a network failure.
            clock += 500
            assertNull(names.reverseName(48.14, 11.58, "en"))
            assertEquals(1, http.calls)

            clock += 600
            assertEquals("Munich", names.reverseName(48.14, 11.58, "en"))
            assertEquals(2, http.calls)
        }
    }

    // ---- the fallback label ------------------------------------------------ */

    @Test
    fun `the coordinate label is two hemispheres and three decimals`() {
        assertEquals("47.398 N 11.000 E", PlaceNames.coordinateLabel(47.398, 11.0))
        assertEquals("33.860 S 151.210 E", PlaceNames.coordinateLabel(-33.86, 151.21))
        assertEquals("51.500 N 0.120 W", PlaceNames.coordinateLabel(51.5, -0.12))
        // Locale.US: a European phone locale would print 47,398 and the unit would
        // read it as a broken string.
        assertTrue(PlaceNames.coordinateLabel(47.398, 11.0), !PlaceNames.coordinateLabel(47.398, 11.0).contains(","))
    }

    // ---- what the panel can actually draw ---------------------------------- */

    @Test
    fun `a place name is folded to the ninety-five glyphs the panel carries`() {
        // Both Conthrax VLW files in fonts/ hold exactly U+0020..U+007E (their own
        // headers say 95 glyphs): no ü, no °, no en dash. A diacritic-free form is
        // the difference between a readable name and a gap in the text.
        assertEquals("Munchen", PlaceNames.panelLabel("München"))
        assertEquals("Zurich", PlaceNames.panelLabel("Zürich"))
        assertEquals("Lodz", PlaceNames.panelLabel("Łódź"))
        assertEquals("Orland", PlaceNames.panelLabel("Ørland"))
        assertEquals("Aarhus", PlaceNames.panelLabel("Aarhus"))
        assertEquals("Garmisch-Partenkirchen", PlaceNames.panelLabel("Garmisch-Partenkirchen"))
    }

    @Test
    fun `a name with no Latin form is not sent`() {
        assertNull(PlaceNames.panelLabel("Москва"))
        assertNull(PlaceNames.panelLabel("東京"))
        assertNull(PlaceNames.panelLabel("   "))
        assertNull(PlaceNames.panelLabel(null))
    }

    @Test
    fun `the label never outgrows the unit's field`() {
        // char city[48] in src/bt_rpc.cpp, filled by copyFixed which keeps 47 bytes
        // at most. Folding happens before the cut, so byte and codepoint agree.
        assertEquals(47, PlaceNames.LABEL_MAX_BYTES)
        val long = PlaceNames.panelLabel("Strathbogie Onus superdupercalifragilistic expialidocious")!!
        assertEquals(47, long.length)
        assertEquals(47, long.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `the ladder is the city it knows, then coordinates`() {
        val ok = """{"address":{"city":"Munich"}}"""

        runBlocking {
            // Reverse geocoding answered; the typed city is not used.
            assertEquals("Munich", PlaceNames(RecordingHttp(ok)).label(48.13, 11.57, "Seeshaupt", "en"))

            // Reverse geocoding failed, but the owner typed a city.
            assertEquals("Munich", PlaceNames(RecordingHttp(null)).label(48.13, 11.57, "Munich", "en"))

            // Both failed: coordinates, which are always drawable.
            assertEquals("48.130 N 11.570 E", PlaceNames(RecordingHttp(null)).label(48.13, 11.57, null, "en"))

            // A typed city the panel cannot draw is not used either.
            assertEquals("48.130 N 11.570 E", PlaceNames(RecordingHttp(null)).label(48.13, 11.57, "Москва", "en"))
        }
    }

    @Test
    fun `the reverse name the unit shows follows the chosen language`() {
        // The label on the dashboard is this function's output, so the language the owner
        // picks is visible on the panel, not only in this app's lists. Nominatim translates
        // the same way the gazetteer does: verified live for Rome, en -> "Rome", it -> "Roma".
        val rome = """{"address":{"city":"Roma"}}"""
        val http = RecordingHttp(rome, rome)

        runBlocking {
            assertEquals("Roma", PlaceNames(http).label(41.89193, 12.51133, null, "it"))
            assertEquals("Roma", PlaceNames(http).label(41.89193, 12.51133, null, "de"))
        }

        assertTrue(http.urls[0], http.urls[0].contains("accept-language=it"))
        assertTrue(http.urls[1], http.urls[1].contains("accept-language=de"))
    }

    @Test
    fun `the guard keeps this file the only one naming the providers`() {
        // WeatherHttp refuses to fetch anything but https; the endpoints here are the only
        // places the app names an address, and the settings-screen guard test allows exactly
        // this file plus weather/OpenMeteo.kt. A third host — or a new one for the language
        // list, which rule 19 says is app data — has to be added here and there together.
        assertNotNull(PlaceNames.SEARCH_URL)
        assertNotNull(PlaceNames.REVERSE_URL)
        assertNotNull(PlaceNames.GET_URL)
        assertTrue(PlaceNames.SEARCH_URL, PlaceNames.SEARCH_URL.startsWith("https://"))
        assertTrue(PlaceNames.REVERSE_URL, PlaceNames.REVERSE_URL.startsWith("https://"))
        assertTrue(PlaceNames.GET_URL, PlaceNames.GET_URL.startsWith("https://"))
    }
}
