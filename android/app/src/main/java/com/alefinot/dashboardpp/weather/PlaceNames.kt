package com.alefinot.dashboardpp.weather

import com.alefinot.dashboardpp.ble.BleJson
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

data class Place(
    val name: String,
    val admin1: String?,
    val country: String?,
    val lat: Double,
    val lon: Double,
)

class PlaceNames(
    private val http: WeatherHttp,
    /** Injectable clock so the etiquette rule is testable without sleeping. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private var lastReverseAtMs: Long? = null

    suspend fun geocodeCity(name: String): Place? {
        val query = name.trim()
        if (query.isEmpty()) return null
        val body = http.get(searchUrl(query)) ?: return null
        return parseSearch(body).firstOrNull()
    }

    suspend fun reverseName(lat: Double, lon: Double): String? {
        val url = reverseUrl(lat, lon) ?: return null
        val last = lastReverseAtMs
        if (last != null && nowMs() - last < MIN_REVERSE_INTERVAL_MS) return null
        lastReverseAtMs = nowMs()
        return parseReverse(http.get(url) ?: return null)
    }

    suspend fun label(lat: Double, lon: Double, typedCity: String?): String =
        reverseName(lat, lon)
            ?: panelLabel(typedCity)
            ?: coordinateLabel(lat, lon)

    companion object {

        const val SEARCH_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"

        /** A hair over a second, so clock jitter cannot put two requests in one second. */
        const val MIN_REVERSE_INTERVAL_MS = 1_100L

        const val LABEL_MAX_BYTES = 47

        /** Administrative names Nominatim puts in `address`, most specific useful first. */
        private val ADDRESS_KEYS = listOf("city", "town", "village", "hamlet", "municipality", "county", "state")

        fun searchUrl(name: String): String =
            SEARCH_URL + "?name=" + URLEncoder.encode(name.trim(), "UTF-8") +
                "&count=3&language=en&format=json"

        /** Null for a coordinate that is not one — those must not leave the phone. */
        fun reverseUrl(lat: Double, lon: Double): String? {
            if (!lat.isFinite() || !lon.isFinite()) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            return REVERSE_URL + String.format(
                Locale.US,
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

        fun panelLabel(text: String?): String? {
            var s = text ?: return null
            s = s.replace('\u00A0', ' ').replace("\u00AD", "").replace("\u200B", "").replace("\uFEFF", "")
                .replace('\u2019', '\'').replace('\u02BC', '\'').replace('\u2018', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2010', '-').replace('\u2011', '-')
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            s = StringBuilder(s).map { translateUnfolding(it) }.joinToString("")
            s = s.replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) return null
            if (s.any { it.code < 0x20 || it.code > 0x7E }) return null
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
