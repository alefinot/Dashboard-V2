package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.ble.BleJson
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * One place, from Open-Meteo's geocoding search. [admin1] and [country] exist for the
 * settings card, which has to show *which* Munich it picked: "Munich" resolves to a
 * city of 1.5 million in Bavaria, a village of 202 in North Dakota, and a landing in
 * Grenada, and the provider orders them by population, not by where the car is.
 *
 * [featureCode] is the place *type* (GeoNames: `PPLC` a capital, `PPL` a populated place,
 * `PCLI` a country) and it is the reason the card can offer settlements instead of offering
 * Romania as a spelling of Roma. [id] is the gazetteer's own key, kept so a saved city can
 * be re-named in another language by key rather than by re-typing and re-guessing it.
 */
data class Place(
    val name: String,
    val admin1: String?,
    val country: String?,
    val lat: Double,
    val lon: Double,
    val id: Long? = null,
    val featureCode: String? = null,
    /**
     * Inhabitants, when the gazetteer knows them. It is on the card because a search for
     * `Roma` answers with two rows both reading "Roma, Veneto, Italy" — 33 people and 13 —
     * and without the number the owner is being asked to choose between two identical
     * labels. Null for a row the gazetteer has no count for, including every non-settlement.
     */
    val population: Long? = null,
) {
    /**
     * A place people live, as opposed to a country (`PCLI`), a mountain (`MT`) or a body
     * of water (`H*`) — the kinds the gazetteer mixes into the same result list. `PPL` is a
     * populated place and `PPLC` / `PPLA` / `PPLA2` … are its administrative ranks; a row
     * that carries no type at all is treated as a settlement, because rejecting a place for
     * a missing field is how a picker ends up empty for a name that does exist.
     */
    val isSettlement: Boolean
        get() = featureCode == null || featureCode.startsWith("PPL")
}

/**
 * The app's half of "what name goes in the unit's weather card".
 *
 * Two providers, in order, and one fallback that needs no network:
 *
 *  - **Nominatim** (`nominatim.openstreetmap.org/reverse`) for a coordinate → name. Its
 *    usage policy is one request per second and no repeated identical queries, so this
 *    class enforces the interval locally and the caller caches per grid cell.
 *  - **Open-Meteo geocoding** (`geocoding-api.open-meteo.com/v1/search`) for a typed
 *    city → coordinate, asked once per save, not per feed.
 *  - **Coordinate label** (`47.398 N 11.000 E`) when neither answers.
 *
 * The label is then folded to what the panel can draw. That is not decoration: both
 * `fonts/Conthrax_SemiBold_16px.vlw` and `28px.vlw` carry exactly 95 glyphs, U+0020
 * through U+007E (their own headers say so), so "München" or a degree sign would render
 * as gaps. The **language the providers are asked in** (`language=` on the search,
 * `accept-language=` on the reverse) gets most of the way there — `de` hands back
 * `München`, `pl` hands back `Monachium`, and every language the app offers answers in
 * Latin letters, which is the whole rule for that list (see [PlaceLanguages]) —
 * [panelLabel] catches the rest, and a name with no Latin form at all ("Москва") is
 * dropped rather than mangled.
 *
 * Not GeoNames: the app never calls geonames.org and holds no key for it.
 */
class PlaceNames(
    private val http: WeatherHttp,
    /** Injectable clock so the etiquette rule is testable without sleeping. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private var lastReverseAtMs: Long? = null

    /**
     * Typed city → the places it could mean, for the card to choose between.
     *
     * The app used to answer this question for the owner by taking `results[0]`, and on the
     * bench that stored **Romania** for the typed word `Roma`: the gazetteer indexes
     * countries too, and its matcher takes three-or-more characters as a *prefix*, so
     * "Roma" matches "Romania" (population 19 M) before anything else does. Returning the
     * candidates is the fix; [settlementFirst] is what keeps a country out of the list.
     *
     * Empty means the gazetteer has no answer, which is also what a no-match body looks
     * like (the provider omits `results` entirely).
     */
    suspend fun searchCandidates(query: String, language: String, count: Int = CANDIDATE_COUNT): List<Place> {
        val name = query.trim()
        if (name.isEmpty()) return emptyList()
        val body = http.get(searchUrl(name, language, count)) ?: return emptyList()
        val all = parseSearch(body)
        return settlementFirst(all).take(count)
    }

    /**
     * One saved place, re-read in another language by its gazetteer id — so changing the
     * place language renames the fallback city instead of leaving the card reading
     * `Monachium` while the panel is being fed `Monaco di Baviera`. Null when the place is
     * gone, the provider is unreachable, or the saved row has no id to ask by.
     */
    suspend fun placeById(id: Long, language: String): Place? {
        val body = http.get(getUrl(id, language)) ?: return null
        return parsePlace(body)
    }

    /**
     * Coordinate → city name, or null: refused locally inside Nominatim's one-second
     * window, answered without a usable address block, or unreachable.
     */
    suspend fun reverseName(lat: Double, lon: Double, language: String): String? {
        val url = reverseUrl(lat, lon, language) ?: return null
        val last = lastReverseAtMs
        if (last != null && nowMs() - last < MIN_REVERSE_INTERVAL_MS) return null
        lastReverseAtMs = nowMs()
        return parseReverse(http.get(url) ?: return null)
    }

    /**
     * The name to put on the card, by the plan's ladder: the name of this place, else
     * the city the owner typed, else the coordinates. The result is always drawable by
     * the panel and always at most [LABEL_MAX_BYTES] bytes.
     */
    suspend fun label(lat: Double, lon: Double, typedCity: String?, language: String): String =
        reverseName(lat, lon, language)
            ?: panelLabel(typedCity)
            ?: coordinateLabel(lat, lon)

    companion object {

        const val SEARCH_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"

        /** The same gazetteer, addressed by its own id: what re-names a saved city. */
        const val GET_URL = "https://geocoding-api.open-meteo.com/v1/get"

        /**
         * Candidate rows the card offers. Five, not the three this file used to ask for:
         * probed with `name=Roma&count=10`, the answer the owner meant sat below the
         * third row for several spellings, so a shorter list would keep guessing wrong.
         */
        const val CANDIDATE_COUNT = 5

        /** A hair over a second, so clock jitter cannot put two requests in one second. */
        const val MIN_REVERSE_INTERVAL_MS = 1_100L

        /**
         * `char city[48]` in the unit's `methodWeatherSet` (src/bt_rpc.cpp:693,727),
         * filled by `copyFixed`, which keeps at most 47 bytes at a UTF-8 boundary.
         * The app fills its half of that agreement.
         */
        const val LABEL_MAX_BYTES = 47

        /** Administrative names Nominatim puts in `address`, most specific useful first. */
        private val ADDRESS_KEYS = listOf("city", "town", "village", "hamlet", "municipality", "county", "state")

        /**
         * The search URL. [language] is not cosmetic: the gazetteer's matcher treats three
         * or more characters as a **prefix of the name in the requested language**, so the
         * same letters find different places. `name=Roma&language=en` answers with the
         * country Romania; the same query as `language=it` answers with Roma, Lazio, Italia
         * (population 2.3 M) first. See [PlaceLanguages] for the codes this app offers.
         */
        fun searchUrl(name: String, language: String, count: Int = CANDIDATE_COUNT): String =
            SEARCH_URL + "?name=" + URLEncoder.encode(name.trim(), "UTF-8") +
                // The candidates are what the card offers to choose between, so the owner
                // can tell "Munich, Bavaria, Germany" from "Munich, North Dakota" by hand
                // rather than trusting the provider's population ranking.
                "&count=" + count.coerceIn(1, 40) +
                "&language=" + tag(language) + "&format=json"

        /** One saved place, re-read in another language. Same response shape as [searchUrl]. */
        fun getUrl(id: Long, language: String): String =
            GET_URL + "?id=" + id + "&language=" + tag(language) + "&format=json"

        /** Null for a coordinate that is not one — those must not leave the phone. */
        fun reverseUrl(lat: Double, lon: Double, language: String): String? {
            if (!lat.isFinite() || !lon.isFinite()) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            return REVERSE_URL + String.format(
                Locale.US,
                // Seven decimals, the same precision the weather query and the route
                // points use. zoom=12 asks for a city rather than the nearest building;
                // addressdetails=1 is what makes the short administrative name available.
                // Nominatim's language knob is a header-named parameter, and it translates
                // the same way: Rome -> Roma -> Рим, which is the label on the panel.
                "?format=jsonv2&lat=%.7f&lon=%.7f&zoom=12&addressdetails=1&accept-language=%s",
                lat, lon, tag(language),
            )
        }

        /** A provider language tag, always one this file can safely put in a URL. */
        private fun tag(language: String): String =
            language.trim().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' }
                .ifEmpty { PlaceLanguages.FALLBACK_TAG }

        /**
         * Settlement rows first, in the provider's own order.
         *
         * The gazetteer indexes countries, mountains and water bodies next to towns, and
         * `feature_code` names the kind: `PPL` is a populated place and `PPLC`/`PPLA*` are
         * its administrative ranks, while `PCLI` is a country — the row that put the centre
         * of Romania on the card as a spelling of "Roma". Anything that is not a settlement
         * is dropped here, **unless** a query has no settlement at all, in which case the
         * raw list is handed back: a person searching for a mountain range or an island
         * group gets candidates rather than an empty card and a "not found".
         */
        fun settlementFirst(all: List<Place>): List<Place> =
            all.filter { it.isSettlement }.ifEmpty { all }

        /** Open-Meteo's `results` array, in the provider's own order (largest first). */
        fun parseSearch(body: String): List<Place> {
            val root = BleJson.obj(body) ?: return emptyList()
            val results = root["results"] as? List<*> ?: return emptyList()
            return results.mapNotNull { entry ->
                placeFrom(entry as? Map<*, *>)
            }
        }

        /**
         * The `v1/get?id=` answer, which is **one place object at the root** rather than a
         * `results` array — the same gazetteer, a different shape, and the reason this is a
         * second entry point instead of [parseSearch] with a flag. A bad id comes back as an
         * error object with no `name`, so it reads as null here, and [WeatherHttp] has
         * already turned its 400 into null.
         */
        fun parsePlace(body: String): Place? = placeFrom(BleJson.obj(body))

        /** One gazetteer row, from either shape. Null unless it is a place with a position. */
        private fun placeFrom(o: Map<*, *>?): Place? {
            o ?: return null
            val fields = o.asStringKeyMap()
            val name = BleJson.string(fields, "name") ?: return null
            // A coordinate the app cannot ask weather about is not a candidate, whatever
            // the gazetteer says the row is called.
            val lat = (o["latitude"] as? Double)?.takeIf { it.isFinite() } ?: return null
            val lon = (o["longitude"] as? Double)?.takeIf { it.isFinite() } ?: return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            return Place(
                name = name,
                admin1 = BleJson.string(fields, "admin1"),
                country = BleJson.string(fields, "country"),
                lat = lat,
                lon = lon,
                // The two fields the picker needs: `feature_code` says what kind of place
                // this is (settlement / country / mountain), `id` lets a saved one be re-read
                // in another language instead of being searched for and guessed again.
                // `BleJson` reads every JSON number as Double (its own doc says so), so this
                // is `as? Number` and not `as? Long`.
                id = (o["id"] as? Number)?.toLong()?.takeIf { it > 0L },
                featureCode = BleJson.string(fields, "feature_code")?.trim()?.takeIf { it.isNotEmpty() },
                population = (o["population"] as? Number)?.toLong()?.takeIf { it > 0L },
            )
        }

        /**
         * Nominatim's `address` block → the name a driver recognises. The `name` /
         * `display_name` of a reverse hit is the nearest feature, which for a point in
         * Munich's Old Town is "Old Town" — accurate and useless on a dashboard, so the
         * administrative name is taken instead and the district is ignored.
         */
        fun parseReverse(body: String): String? {
            val root = BleJson.obj(body) ?: return null
            val address = (root["address"] as? Map<*, *>)?.asStringKeyMap() ?: return null
            val hit = ADDRESS_KEYS.firstNotNullOfOrNull { BleJson.string(address, it)?.takeIf { v -> v.isNotBlank() } }
            return panelLabel(hit)
        }

        /** `"47.398 N 11.000 E"`: three decimals, hemisphere letters, no sign confusion. */
        fun coordinateLabel(lat: Double, lon: Double): String {
            val ns = if (lat < 0.0) 'S' else 'N'
            val ew = if (lon < 0.0) 'W' else 'E'
            return String.format(Locale.US, "%.3f %c %.3f %c", Math.abs(lat), ns, Math.abs(lon), ew)
        }

        /**
         * Fold a provider's place name into the panel's glyph set: Unicode-normalize and
         * drop the combining marks (München → Munchen), translate the few letters that
         * have no decomposition (Ørland → Orland, Łódź → Lodz), and keep ASCII
         * punctuation. Returns null when nothing Latin survives — a name the panel cannot
         * draw is worse than the coordinate label the caller falls back to.
         */
        fun panelLabel(text: String?): String? {
            var s = text ?: return null
            // Invisible and look-alike separators first, so they never reject a name that
            // is otherwise plain ASCII: no-break space, soft hyphen, zero-width space,
            // BOM, and the typographic quotes and dashes Nominatim does return.
            s = s.replace('\u00A0', ' ').replace("\u00AD", "").replace("\u200B", "").replace("\uFEFF", "")
                .replace('\u2019', '\'').replace('\u02BC', '\'').replace('\u2018', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2010', '-').replace('\u2011', '-')
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            s = StringBuilder(s).map { translateUnfolding(it) }.joinToString("")
            s = s.replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) return null
            if (s.any { it.code < 0x20 || it.code > 0x7E }) return null
            // The fold runs before the cut, so one char is one byte here and the cut can
            // never land inside a multi-byte sequence the way copyFixed's can.
            return if (s.length <= LABEL_MAX_BYTES) s else s.substring(0, LABEL_MAX_BYTES)
        }

        /** Letters that NFD leaves alone; empty string means "drop it". */
        private fun translateUnfolding(c: Char): String = when (c) {
            'ß' -> "ss"
            'æ' -> "ae"; 'Æ' -> "AE"
            'œ' -> "oe"; 'Œ' -> "OE"
            'ø' -> "o"; 'Ø' -> "O"
            'đ' -> "d"; 'Đ' -> "D"
            'ð' -> "d"; 'Ð' -> "D"
            'ł' -> "l"; 'Ł' -> "L"
            'ħ' -> "h"; 'Ħ' -> "H"
            'þ' -> "th"; 'Þ' -> "TH"
            'ı' -> "i"; 'ĳ' -> "ij"; 'Ĳ' -> "IJ"
            'ŋ' -> "n"; 'Ŋ' -> "N"
            else -> c.toString()
        }

        /** BleJson's accessors want `Map<String, Any?>`; the parsed tree is that shape. */
        @Suppress("UNCHECKED_CAST")
        private fun Map<*, *>.asStringKeyMap(): Map<String, Any?> = this as Map<String, Any?>
    }
}
