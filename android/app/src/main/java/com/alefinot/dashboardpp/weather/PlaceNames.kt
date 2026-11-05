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
 */
data class Place(
    val name: String,
    val admin1: String?,
    val country: String?,
    val lat: Double,
    val lon: Double,
)

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
 * as gaps. `accept-language=en` gets most of the way there at the provider ("Munich"),
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
     * Typed city → place, or null when the owner typed something no gazetteer knows.
     * The provider omits the `results` key entirely for a no-match answer, which is
     * why [parseSearch] returns an empty list rather than failing.
     */
    suspend fun geocodeCity(name: String): Place? {
        val query = name.trim()
        if (query.isEmpty()) return null
        val body = http.get(searchUrl(query)) ?: return null
        return parseSearch(body).firstOrNull()
    }

    /**
     * Coordinate → city name, or null: refused locally inside Nominatim's one-second
     * window, answered without a usable address block, or unreachable.
     */
    suspend fun reverseName(lat: Double, lon: Double): String? {
        val url = reverseUrl(lat, lon) ?: return null
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
    suspend fun label(lat: Double, lon: Double, typedCity: String?): String =
        reverseName(lat, lon)
            ?: panelLabel(typedCity)
            ?: coordinateLabel(lat, lon)

    companion object {

        const val SEARCH_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"

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

        fun searchUrl(name: String): String =
            SEARCH_URL + "?name=" + URLEncoder.encode(name.trim(), "UTF-8") +
                // Three candidates, so the settings card can show what it chose and the
                // owner can tell "Munich, Bavaria, Germany" from "Munich, North Dakota".
                "&count=3&language=en&format=json"

        /** Null for a coordinate that is not one — those must not leave the phone. */
        fun reverseUrl(lat: Double, lon: Double): String? {
            if (!lat.isFinite() || !lon.isFinite()) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            return REVERSE_URL + String.format(
                Locale.US,
                // Seven decimals, the same precision the weather query and the route
                // points use. zoom=12 asks for a city rather than the nearest building;
                // addressdetails=1 is what makes the short administrative name available.
                "?format=jsonv2&lat=%.7f&lon=%.7f&zoom=12&addressdetails=1&accept-language=en",
                lat, lon,
            )
        }

        /** Open-Meteo's `results` array, in the provider's own order (largest first). */
        fun parseSearch(body: String): List<Place> {
            val root = BleJson.obj(body) ?: return emptyList()
            val results = root["results"] as? List<*> ?: return emptyList()
            return results.mapNotNull { entry ->
                val o = entry as? Map<*, *> ?: return@mapNotNull null
                val name = BleJson.string(o.asStringKeyMap(), "name") ?: return@mapNotNull null
                val lat = (o["latitude"] as? Double)?.takeIf { it.isFinite() } ?: return@mapNotNull null
                val lon = (o["longitude"] as? Double)?.takeIf { it.isFinite() } ?: return@mapNotNull null
                if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return@mapNotNull null
                Place(
                    name = name,
                    admin1 = BleJson.string(o.asStringKeyMap(), "admin1"),
                    country = BleJson.string(o.asStringKeyMap(), "country"),
                    lat = lat,
                    lon = lon,
                )
            }
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
