package com.alefinot.dashboardpp.ble

import com.alefinot.dashboardpp.api.Snapshot

/**
 * Everything the app says to a unit, in the unit's own method names.
 *
 * The names and parameter keys are the firmware's (`src/bt_rpc.cpp`), not an
 * invention: `config.get` streams, `time.set` takes a Unix timestamp in seconds,
 * `weather.set` takes Open-Meteo's raw numbers plus the two sunrise/sunset epochs.
 * Keeping one typed method per method is what lets the settings screens and the
 * trip recorder share the link without either knowing the wire format.
 */
class BleApi(private val link: BleLink) {

    /** Liveness and the unit's own uptime/heap, for the link card. */
    suspend fun ping(): BleReply = link.request("ping")

    /** The unit's snapshot, parsed by the same code the HTTP transport uses. */
    suspend fun snapshot(): Snapshot? {
        val reply = link.request("snapshot")
        if (!reply.ok) return null
        return Snapshot.parse(BleJson.encode(reply.fields))
    }

    /**
     * The settings mirror.
     *
     * `config.get` answers as several `BT_F_CHUNK` frames followed by a summary;
     * [BleLink] merges the chunk bodies, so this is one call from here. Null when
     * the unit refused or the link timed out.
     */
    suspend fun config(timeoutMs: Long = 30_000): ConfigMirror? {
        val reply = link.request("config.get", timeoutMs = timeoutMs)
        if (!reply.ok) return null
        val values = reply.chunks ?: emptyMap()
        return ConfigMirror(
            values = values,
            chunks = BleJson.int(reply.fields, "chunks") ?: 0,
            keys = BleJson.int(reply.fields, "keys") ?: values.size,
            rows = BleJson.int(reply.fields, "rows") ?: values.size,
        )
    }

    /**
     * Writes only the keys in [values]. The firmware applies a partial object and
     * recalculates what depends on it, so a settings screen can send one switch.
     */
    suspend fun setConfig(values: Map<String, Any?>): BleReply =
        link.request("config.set", BleJson.encode(values), timeoutMs = 20_000)

    /** One key back to its compiled default. */
    suspend fun resetConfig(key: String): BleReply =
        link.request("config.reset", BleJson.params("key" to key))

    /** Sets the unit's clock. Seconds since the Unix epoch, as the firmware checks. */
    suspend fun pushTime(epochSeconds: Long = System.currentTimeMillis() / 1000): BleReply =
        link.request("time.set", BleJson.params("timestamp" to epochSeconds))

    /** Pushes the weather the phone fetched; the unit only displays it. */
    suspend fun pushWeather(weather: WeatherPush): BleReply =
        link.request(
            "weather.set",
            BleJson.params(
                "temperature" to weather.temperature,
                "humidity" to weather.humidity,
                "windSpeed" to weather.windSpeed,
                "windDirection" to weather.windDirection,
                "weatherCode" to weather.weatherCode,
                "cloudCover" to weather.cloudCover,
                "sunrise" to weather.sunrise,
                "sunset" to weather.sunset,
                "city" to weather.city,
                "valid" to weather.valid,
            ),
        )

    suspend fun setOdometer(km: Double): BleReply =
        link.request("odo.set", BleJson.params("km" to km))

    suspend fun resetTrip(): BleReply = link.request("trip.reset")

    /** `which` is "dark" or "bright", matching the two ambient calibration steps. */
    suspend fun calibrateAmbient(which: String): BleReply =
        link.request("cal.ambient", BleJson.params("which" to which))

    suspend fun reboot(): BleReply = link.request("reboot", timeoutMs = 5_000)

    suspend fun sleep(): BleReply = link.request("sleep", timeoutMs = 5_000)

    suspend fun factoryReset(): BleReply = link.request("factory.reset", timeoutMs = 10_000)

    /** The bounded tail of the unit's log, as a JSON object with a `text` field. */
    suspend fun log(): String? {
        val reply = link.request("log.get")
        return if (reply.ok) BleJson.string(reply.fields, "text") ?: reply.body else null
    }
}

/** The merged result of a streamed `config.get`. */
data class ConfigMirror(
    val values: Map<String, Any?>,
    val chunks: Int,
    val keys: Int,
    val rows: Int,
)

/**
 * What Open-Meteo gives the app, keyed the way `weather.set` reads it. The two sun
 * times are Unix seconds; the firmware turns them into the panel's local `HH:MM`
 * with the timezone settings it already owns.
 */
data class WeatherPush(
    val temperature: Double,
    val humidity: Int,
    val windSpeed: Double,
    val windDirection: Double,
    val weatherCode: Int,
    val cloudCover: Int,
    val sunrise: Long,
    val sunset: Long,
    val city: String,
    val valid: Boolean = true,
)
