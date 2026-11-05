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
        link.request("weather.set", weatherParams(weather))

    suspend fun setOdometer(km: Double): BleReply =
        link.request("odo.set", BleJson.params("km" to km))

    suspend fun resetTrip(): BleReply = link.request("trip.reset")

    /** `which` is "dark" or "bright", matching the two ambient calibration steps. */
    suspend fun calibrateAmbient(which: String): BleReply =
        link.request("cal.ambient", BleJson.params("which" to which))

    suspend fun reboot(): BleReply = link.request("reboot", timeoutMs = 5_000)

    suspend fun sleep(): BleReply = link.request("sleep", timeoutMs = 5_000)

    suspend fun factoryReset(): BleReply = link.request("factory.reset", timeoutMs = 10_000)

    /** The bounded tail of the unit's log, pulled on demand. */
    suspend fun log(): String? {
        val reply = link.request("log.get")
        return if (reply.ok) BleJson.string(reply.fields, "text") ?: reply.body else null
    }

    // ------------------------------------------------------------------
    // Firmware install (phase 2b)
    //
    // The control messages are ordinary RPC methods so the handshake gets the same request
    // queue, correlation and timeout as every other call; only the image itself bypasses RPC
    // and travels as raw writes on the OTA characteristic. That split is `ble/OtaCore.kt`'s
    // business — from here it is four questions and one flow of answers.
    // ------------------------------------------------------------------

    /**
     * Asks the unit to open its spare flash slot for a [size]-byte image whose CRC-32 is
     * [crc32]. The answer carries the write size to use and the offset to continue from, which
     * is what makes a resumed transfer continue instead of start over.
     */
    suspend fun otaBegin(size: Int, crc32: Long): BleReply =
        link.request(
            "ota.begin",
            BleJson.params("size" to size, "crc32" to crc32),
            // Opening a flash slot is not fast, and a wrong-size answer is worth waiting for
            // rather than retrying into a half-open install.
            timeoutMs = 20_000,
        )

    /** Where an install stands: `active`, `off`, `size`, `stage`. */
    suspend fun otaStatus(): BleReply = link.request("ota.status", timeoutMs = 5_000)

    /** Stops an install. The unit's running image is untouched either way. */
    suspend fun otaAbort(): BleReply = link.request("ota.abort", timeoutMs = 8_000)

    /** Tells the unit the last byte went out; it then runs its own image check. */
    suspend fun otaEnd(): BleReply = link.request("ota.end", timeoutMs = 10_000)

    companion object {
        /**
         * The `weather.set` body, kept separate from the call so the payload is testable
         * without a link (see `WeatherPushShapeTest`).
         *
         * The ten keys are the firmware's — `methodWeatherSet` (`src/bt_rpc.cpp:725-741`)
         * reads these names and defaults each one, so a typo here is not an error, it is a
         * card that quietly shows the wrong thing. Every key is always present, including
         * `valid`, whose firmware default is `true`.
         */
        fun weatherParams(weather: WeatherPush): String = BleJson.params(
            "temperature" to weather.temperature,
            "humidity" to weather.humidity,
            "windSpeed" to weather.windSpeed,
            "windDirection" to weather.windDirection,
            "weatherCode" to weather.weatherCode,
            "cloudCover" to weather.cloudCover,
            // A missing sun time goes out as JSON null, which the unit already understands
            // (anything that is not a number becomes "--:--", src/bt_rpc.cpp:717-722). It
            // must not go out as 0: that formats as 00:00, and a zero reading is a claim.
            "sunrise" to weather.sunrise,
            "sunset" to weather.sunset,
            "city" to weather.city,
            "valid" to weather.valid,
        )
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
 * What the app pushes to the unit's weather card, keyed the way `weather.set` reads it.
 * The payload's units are the firmware's (°C, km/h, %): the panel does its own °F
 * conversion in Imperial mode (src/ui.cpp:2435), so nothing here is converted.
 *
 * [sunrise] and [sunset] are Unix seconds — the provider's numbers, unshifted, because
 * the unit formats them with the timezone settings `time.set` already gave it. Null means
 * the provider had no sun time, and goes to the unit as JSON null so the card prints
 * `--:--`; [city] is null when there is no name to show, which the unit renders as its
 * own "no name".
 */
data class WeatherPush(
    val temperature: Double,
    val humidity: Int,
    val windSpeed: Double,
    val windDirection: Double,
    val weatherCode: Int,
    val cloudCover: Int,
    val sunrise: Long?,
    val sunset: Long?,
    val city: String?,
    val valid: Boolean = true,
)
