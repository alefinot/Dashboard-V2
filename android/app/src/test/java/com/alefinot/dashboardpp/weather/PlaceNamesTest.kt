package com.alefinot.dashboardpp.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

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
        val url = PlaceNames.searchUrl("München am See")

        assertTrue(url, url.startsWith(PlaceNames.SEARCH_URL))
        assertTrue(url, url.contains("name=M%C3%BCnchen+am+See"))
        assertTrue(url, !url.contains(" "))
        assertTrue(url, url.contains("count=3"))
        assertTrue(url, url.contains("language=en"))
        assertTrue(url, url.contains("format=json"))
    }

    @Test
    fun `the reverse query asks for a city-level name in English`() {
        val url = PlaceNames.reverseUrl(48.13743, 11.57549)!!

        assertTrue(url, url.startsWith(PlaceNames.REVERSE_URL))
        assertTrue(url, url.contains("lat=48.1374300"))
        assertTrue(url, url.contains("lon=11.5754900"))
        assertTrue(url, url.contains("format=jsonv2"))
        assertTrue(url, url.contains("addressdetails=1"))
        assertTrue(url, url.contains("accept-language=en"))
        assertTrue(url, url.contains("zoom=12"))
    }

    @Test
    fun `a coordinate that is not a coordinate is not sent`() {
        assertNull(PlaceNames.reverseUrl(Double.NaN, 11.5))
        assertNull(PlaceNames.reverseUrl(48.1, Double.POSITIVE_INFINITY))
        assertNull(PlaceNames.reverseUrl(91.0, 11.5))
        assertNull(PlaceNames.reverseUrl(48.1, 181.0))
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
        // The other two are the same name in other countries; the card has to show
        // which one was chosen, and the coordinator has to keep all three's names
        // out of the panel's field. parseSearch keeps them in order, unsorted.
        assertEquals(3, PlaceNames.parseSearch(body).size)
    }

    @Test
    fun `no results is not a place`() {
        // The live no-match body: Open-Meteo omits the key entirely.
        assertEquals(0, PlaceNames.parseSearch("""{"generationtime_ms":0.14388561}""").size)
        assertEquals(0, PlaceNames.parseSearch("""{"results":[]}""").size)
        assertEquals(0, PlaceNames.parseSearch("not json").size)
    }

    @Test
    fun `a result without coordinates is not a place`() {
        val body = """{"results":[{"name":"Nowhere"},{"name":"Here","latitude":1.0,"longitude":2.0}]}"""

        val places = PlaceNames.parseSearch(body)

        assertEquals(1, places.size)
        assertEquals("Here", places[0].name)
    }

    @Test
    fun `a typed city with no answer is reported as not found`() {
        val http = RecordingHttp("""{"generationtime_ms":0.14}""")

        assertNull(runBlocking { PlaceNames(http).geocodeCity("München am See") })
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
            assertEquals("Munich", names.reverseName(48.13, 11.57))
            assertEquals(1, http.calls)

            clock += 500
            assertNull(names.reverseName(48.14, 11.58))
            assertEquals(1, http.calls)

            clock += 600
            assertEquals("Munich", names.reverseName(48.14, 11.58))
            assertEquals(2, http.calls)
        }
    }

    // ---- the fallback label ------------------------------------------------ */

    @Test
    fun `the coordinate label is two hemispheres and three decimals`() {
        assertEquals("47.398 N 11.000 E", PlaceNames.coordinateLabel(47.398, 11.0))
        assertEquals("33.860 S 151.210 E", PlaceNames.coordinateLabel(-33.86, 151.21))
        assertEquals("51.500 N 0.120 W", PlaceNames.coordinateLabel(51.5, -0.12))
        assertTrue(PlaceNames.coordinateLabel(47.398, 11.0), !PlaceNames.coordinateLabel(47.398, 11.0).contains(","))
    }

    // ---- what the panel can actually draw ---------------------------------- */

    @Test
    fun `a place name is folded to the ninety-five glyphs the panel carries`() {
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
            assertEquals("Munich", PlaceNames(RecordingHttp(ok)).label(48.13, 11.57, "Seeshaupt"))

            // Reverse geocoding failed, but the owner typed a city.
            assertEquals("Munich", PlaceNames(RecordingHttp(null)).label(48.13, 11.57, "Munich"))

            // Both failed: coordinates, which are always drawable.
            assertEquals("48.130 N 11.570 E", PlaceNames(RecordingHttp(null)).label(48.13, 11.57, null))

            // A typed city the panel cannot draw is not used either.
            assertEquals("48.130 N 11.570 E", PlaceNames(RecordingHttp(null)).label(48.13, 11.57, "Москва"))
        }
    }

    @Test
    fun `the guard keeps this file the only one naming the two providers`() {
        // WeatherHttp refuses to fetch anything but https; the two endpoints here
        // are the only places the app names an address, and the settings-screen
        // guard test allows exactly this file plus weather/OpenMeteo.kt.
        assertNotNull(PlaceNames.SEARCH_URL)
        assertNotNull(PlaceNames.REVERSE_URL)
        assertTrue(PlaceNames.SEARCH_URL, PlaceNames.SEARCH_URL.startsWith("https://"))
        assertTrue(PlaceNames.REVERSE_URL, PlaceNames.REVERSE_URL.startsWith("https://"))
    }
}
