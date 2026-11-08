package com.alefinot.dashboardpp.trips

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tripIndexStore: DataStore<Preferences> by preferencesDataStore("trip_index")
private val Context.tripSettingsStore: DataStore<Preferences> by preferencesDataStore("trip_settings")

data class CachedSummary(
    val mtime: Long,
    val size: Long,
    val summary: TripSummary,
    val tag: TripTag?,
    val tagKnown: Boolean = true,
)

class TripIndex(context: Context) {
    private val store = context.applicationContext.tripIndexStore

    private fun key(path: String) = "idx:$path"

    suspend fun put(path: String, mtime: Long, size: Long, summary: TripSummary, tag: TripTag? = null) {
        store.edit {
            it[stringPreferencesKey(key(path))] =
                "$mtime;$size;${tag?.raw() ?: NO_TAG};${TripSummaryCodec.encode(summary)}"
        }
    }

    fun cached(): Flow<Map<String, CachedSummary>> =
        store.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                val out = HashMap<String, CachedSummary>()
                for ((k, v) in prefs.asMap()) {
                    if (k.name.startsWith("idx:") && v is String) {
                        val parts = v.split(";", limit = 4)
                        val legacy = parts.size == 3
                        if (parts.size != 3 && parts.size != 4) continue
                        val summary =
                            TripSummaryCodec.decode(if (legacy) parts[2] else parts[3]) ?: continue
                        out[k.name.removePrefix("idx:")] = CachedSummary(
                            mtime = parts[0].toLongOrNull() ?: continue,
                            size = parts[1].toLongOrNull() ?: continue,
                            summary = summary,
                            tag = if (legacy) null else TripTag.fromRaw(parts[2]),
                            tagKnown = !legacy && parts[2] != NO_TAG,
                        )
                    }
                }
                out
            }

    /** Drop cache rows for files that no longer exist. */
    suspend fun retain(livePaths: Set<String>) {
        store.edit { prefs ->
            val stale = prefs.asMap().keys
                .filter { it.name.startsWith("idx:") && it.name.removePrefix("idx:") !in livePaths }
            stale.forEach { prefs.remove(it) }
        }
    }

    suspend fun reseat(file: File, cached: CachedSummary, tag: TripTag?) {
        if (cached.tagKnown) return
        put(file.absolutePath, cached.mtime, cached.size, cached.summary, tag)
    }

    companion object {
        /** The marker for "this file was opened and has no tag line". */
        private const val NO_TAG = "-"

        /** True when a cached row still matches the file on disk. */
        fun fresh(file: File, cached: CachedSummary): Boolean =
            file.length() == cached.size && file.lastModified() == cached.mtime
    }
}

class RecorderSettings(context: Context) {
    private val store = context.applicationContext.tripSettingsStore

    val recordingEnabled: Flow<Boolean> =
        store.data.catch { emit(emptyPreferences()) }
            .map { it[KEY_RECORDING] ?: true }

    val motionEnabled: Flow<Boolean> =
        store.data.catch { emit(emptyPreferences()) }
            .map { it[KEY_MOTION] ?: true }

    val notificationEnabled: Flow<Boolean> =
        store.data.catch { emit(emptyPreferences()) }
            .map { it[KEY_NOTIFICATION] ?: true }

    val tripFilter: Flow<String> =
        store.data.catch { emit(emptyPreferences()) }
            .map { it[KEY_TRIP_FILTER] ?: FILTER_ALL }

    suspend fun setTripFilter(value: String) {
        store.edit { it[KEY_TRIP_FILTER] = value }
    }

    /** One-time battery-optimisation hint, so it never nags twice. */
    val batteryPromptShown: Flow<Boolean> =
        store.data.catch { emit(emptyPreferences()) }
            .map { it[KEY_BATTERY_PROMPT] ?: false }

    suspend fun warm() {
        recordingEnabled.first()
        motionEnabled.first()
        notificationEnabled.first()
        tripFilter.first()
    }

    /** The persisted session per device, so a trip survives an app restart. */
    suspend fun sessionFor(ip: String): SessionRecord? {
        val text = try {
            store.data.first()[stringPreferencesKey(sessionKey(ip))]
        } catch (e: Exception) {
            null
        } ?: return null
        return SessionCodec.decode(ip, text)
    }

    suspend fun saveSession(record: SessionRecord) {
        store.edit { it[stringPreferencesKey(sessionKey(record.ip))] = SessionCodec.encode(record) }
    }

    suspend fun clearSessions(ips: List<String>): Int {
        if (ips.isEmpty()) return 0
        var removed = 0
        store.edit { tx ->
            val names = sessionKeysToRemove(tx.asMap().keys.map { it.name }.toSet(), ips)
            removed = names.size
            for (n in names) tx.remove(stringPreferencesKey(n))
        }
        return removed
    }

    suspend fun setRecordingEnabled(enabled: Boolean) {
        store.edit { it[KEY_RECORDING] = enabled }
    }

    suspend fun setMotionEnabled(enabled: Boolean) {
        store.edit { it[KEY_MOTION] = enabled }
    }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        store.edit { it[KEY_NOTIFICATION] = enabled }
    }

    suspend fun setBatteryPromptShown() {
        store.edit { it[KEY_BATTERY_PROMPT] = true }
    }

    companion object {
        private val KEY_RECORDING = booleanPreferencesKey("recording_enabled")
        private val KEY_MOTION = booleanPreferencesKey("motion_enabled")
        private val KEY_NOTIFICATION = booleanPreferencesKey("notification_enabled")
        private val KEY_BATTERY_PROMPT = booleanPreferencesKey("battery_prompt_shown")
        private val KEY_TRIP_FILTER = stringPreferencesKey("trip_filter")

        /** The filter value meaning "no filter". */
        const val FILTER_ALL = "ALL"

        /** The filter value meaning "files with no vehicle tag". */
        const val FILTER_UNKNOWN = "UNKNOWN"

        /** The key holding the recorder's row for one address. */
        fun sessionKey(ip: String) = "session:$ip"

        fun sessionKeysToRemove(existing: Set<String>, ips: List<String>): List<String> {
            if (ips.isEmpty()) return emptyList()
            val wanted = ips.map { sessionKey(it) }.toSet()
            return existing.filter { it in wanted }
        }
    }
}
