package com.alefinot.dashboardpp.weather

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// One DataStore per name, per process — the delegate guarantees it, and the settings
// card, the coordinator and the service all read the same one (same shape as
// trips/Stores.kt).
private val Context.weatherSettingsStore: DataStore<Preferences> by preferencesDataStore("weather_settings")

/**
 * What the app remembers about one unit's weather: the position it last asked about,
 * the label that went with it, and when the last successful feed and push happened.
 *
 * [lastPushEpochMs] and [fetchEpochMs] use [WeatherSettings.NO_EPOCH] for "never" —
 * DataStore has no null, and inventing a timestamp would be worse than saying 1970.
 *
 * [offlineSent] is the plan's rule made durable: the `valid=false` frame goes out at
 * most once per transition into staleness, so an app restart cannot spend a second one
 * on a unit that already shows "Weather Offline".
 */
data class WeatherCache(
    val lat: Double,
    val lon: Double,
    val label: String?,
    val fetchEpochMs: Long,
    val lastPushEpochMs: Long,
    val offlineSent: Boolean,
) {
    /** The coordinate to compare against for the 2 km move test, or null if never fed. */
    fun asLatLon(): LatLon? =
        if (fetchEpochMs == WeatherSettings.NO_EPOCH) null else LatLon(lat, lon)

    /** The same row as the ladder's cache rung; the ladder itself applies the age rule. */
    fun asCachedPosition(): CachedPosition = CachedPosition(lat, lon, fetchEpochMs, label)
}

/** The owner's typed fallback city, and the place it resolved to when it was saved. */
data class CityFallback(val typed: String?, val place: Place?) {
    val isUsable: Boolean get() = place != null
}

/**
 * The weather feed's settings and cache.
 *
 * The cache is keyed by `UnitIdentity.key(mac, bleAddress)`, not by the address the
 * link dialed: position belongs to a vehicle, and a second car that happens to be
 * reached over the same phone must never inherit the first one's last known place.
 *
 * The typed city and its geocode are stored as **separate keys** rather than one joined
 * string, because every one of those values is free text an owner types or a gazetteer
 * returns; the per-unit cache row has to be one value (it is keyed by unit), so there
 * the free-text field is kept last and split with a limit, which is what keeps a label
 * containing a `;` whole.
 */
class WeatherSettings(context: Context) : WeatherStore {

    private val store = context.applicationContext.weatherSettingsStore

    /** The feed switch, on by default (plan D1: the owner turns it off, never on). */
    val enabled: Flow<Boolean> =
        store.data.catch { emit(emptyPreferences()) }.map { it[KEY_ENABLED] ?: true }

    /** One-shot read for the coordinator, which is not a collector. */
    override suspend fun enabledNow(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[KEY_ENABLED] = value }
    }

    /** The typed city plus the coordinates it resolved to, read together. */
    val cityFallback: Flow<CityFallback> =
        store.data.catch { emit(emptyPreferences()) }.map { readCity(it) }

    override suspend fun cityNow(): CityFallback = cityFallback.first()

    /**
     * Store a typed city with the geocode that was looked up for it at save time.
     * Passing null for [place] means "the owner typed something, the gazetteer had no
     * answer" — the text is kept so it can be shown and re-tried, but it is not a
     * position and the ladder will not use it.
     */
    suspend fun setCity(typed: String?, place: Place?) {
        store.edit { prefs ->
            val text = typed?.trim()?.takeIf { it.isNotEmpty() }
            if (text == null) {
                listOf(KEY_CITY, KEY_CITY_LAT, KEY_CITY_LON, KEY_CITY_NAME, KEY_CITY_ADMIN1, KEY_CITY_COUNTRY)
                    .forEach { prefs.remove(it) }
            } else {
                prefs[KEY_CITY] = text
                if (place == null) {
                    // Drop any geocode left from a previous, different city: an old
                    // coordinate behind a new name would feed weather for the wrong town.
                    listOf(KEY_CITY_LAT, KEY_CITY_LON, KEY_CITY_NAME, KEY_CITY_ADMIN1, KEY_CITY_COUNTRY)
                        .forEach { prefs.remove(it) }
                } else {
                    prefs[KEY_CITY_LAT] = String.format(Locale.US, "%.7f", place.lat)
                    prefs[KEY_CITY_LON] = String.format(Locale.US, "%.7f", place.lon)
                    prefs[KEY_CITY_NAME] = place.name
                    place.admin1?.let { prefs[KEY_CITY_ADMIN1] = it } ?: prefs.remove(KEY_CITY_ADMIN1)
                    place.country?.let { prefs[KEY_CITY_COUNTRY] = it } ?: prefs.remove(KEY_CITY_COUNTRY)
                }
            }
        }
    }

    /** This unit's cache row, or null when the app has never fed this unit. */
    override suspend fun cacheFor(unitKey: String): WeatherCache? =
        readCache(store.data.first()[stringPreferencesKey(cacheKey(unitKey))])

    override suspend fun saveCache(unitKey: String, cache: WeatherCache) {
        store.edit { it[stringPreferencesKey(cacheKey(unitKey))] = encodeCache(cache) }
    }

    /**
     * Forget the cache rows of units being removed from the app. Whole-key equality, for
     * the reason `RecorderSettings.clearSessions` spells out: one identity's key can be a
     * text prefix of another's, and a prefix match would delete a stranger's history.
     */
    suspend fun clearCaches(unitKeys: List<String>): Int {
        if (unitKeys.isEmpty()) return 0
        var removed = 0
        store.edit { prefs ->
            val names = cacheKeysToRemove(prefs.asMap().keys.map { it.name }.toSet(), unitKeys)
            removed = names.size
            names.forEach { prefs.remove(stringPreferencesKey(it)) }
        }
        return removed
    }

    companion object {

        /** "Never happened", stored as a number because the row cannot hold a null. */
        const val NO_EPOCH = 0L

        private val KEY_ENABLED = booleanPreferencesKey("weather:enabled")
        private val KEY_CITY = stringPreferencesKey("weather:city")
        private val KEY_CITY_LAT = stringPreferencesKey("weather:city_lat")
        private val KEY_CITY_LON = stringPreferencesKey("weather:city_lon")
        private val KEY_CITY_NAME = stringPreferencesKey("weather:city_name")
        private val KEY_CITY_ADMIN1 = stringPreferencesKey("weather:city_admin1")
        private val KEY_CITY_COUNTRY = stringPreferencesKey("weather:city_country")

        /** The row for one unit identity: `weather:cache:<identity key>`. */
        fun cacheKey(unitKey: String) = "weather:cache:$unitKey"

        /** Of [existing], the cache rows that belong to [unitKeys]. */
        fun cacheKeysToRemove(existing: Set<String>, unitKeys: List<String>): List<String> {
            if (unitKeys.isEmpty()) return emptyList()
            val wanted = unitKeys.map { cacheKey(it) }.toSet()
            return existing.filter { it in wanted }
        }

        /**
         * `w1;<lat>;<lon>;<fetchMs>;<pushMs>;<offlineSent>;<label>` — the version tag
         * first so a row written by a future app is refused rather than mis-read, and the
         * label last because it is the only field that can hold arbitrary provider text.
         */
        fun encodeCache(cache: WeatherCache): String = listOf(
            "w1",
            String.format(Locale.US, "%.7f", cache.lat),
            String.format(Locale.US, "%.7f", cache.lon),
            cache.fetchEpochMs,
            cache.lastPushEpochMs,
            if (cache.offlineSent) 1 else 0,
            cache.label ?: "",
        ).joinToString(";")

        fun readCache(text: String?): WeatherCache? {
            val parts = text?.split(";", limit = 7) ?: return null
            if (parts.size != 7 || parts[0] != "w1") return null
            val lat = parts[1].toDoubleOrNull() ?: return null
            val lon = parts[2].toDoubleOrNull() ?: return null
            // A row that drifted outside the coordinate ranges is not a cache, it is
            // corruption; refusing it sends the ladder down to the typed city instead.
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            return WeatherCache(
                lat = lat,
                lon = lon,
                label = parts.getOrNull(6)?.takeIf { it.isNotEmpty() },
                fetchEpochMs = parts[3].toLongOrNull() ?: return null,
                lastPushEpochMs = parts[4].toLongOrNull() ?: return null,
                offlineSent = parts[5] == "1",
            )
        }

        private fun readCity(prefs: Preferences): CityFallback {
            val typed = prefs[KEY_CITY] ?: return CityFallback(null, null)
            val lat = prefs[KEY_CITY_LAT]?.toDoubleOrNull() ?: return CityFallback(typed, null)
            val lon = prefs[KEY_CITY_LON]?.toDoubleOrNull() ?: return CityFallback(typed, null)
            val name = prefs[KEY_CITY_NAME] ?: return CityFallback(typed, null)
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return CityFallback(typed, null)
            return CityFallback(
                typed = typed,
                place = Place(
                    name = name,
                    admin1 = prefs[KEY_CITY_ADMIN1],
                    country = prefs[KEY_CITY_COUNTRY],
                    lat = lat,
                    lon = lon,
                ),
            )
        }
    }
}
