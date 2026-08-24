package com.alefinot.dashboardpp.viewmodel

import android.app.Application
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.LinkStatus
import com.alefinot.dashboardpp.ble.Protocol
import com.alefinot.dashboardpp.notify.NotifOutbox
import com.alefinot.dashboardpp.weather.OpenMeteoClient
import com.alefinot.dashboardpp.weather.WeatherDto
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The companion's single ViewModel (§5.4). Owns the [BleLink], the
 * phone-side weather timer (the only fetch path — the ESP no longer fetches
 * Open-Meteo itself), and the [NotifOutbox] (the notification → bar glue).
 *
 * The ESP's `WEATHER_*` config is read once from `/api/config` (the app has
 * the `ip`); it becomes the Open-Meteo lat/lon/city/refresh. On a BLE
 * connect the forecast is fetched once, then every `WEATHER_REFRESH_MIN`;
 * a fetch failure keeps the last-pushed value (it is never cleared).
 */
class CompanionViewModel(private val app: Application) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ble = BleLink(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // The weather config (read once from /api/config).
    private var city: String = ""
    private var lat: Double = 0.0
    private var lon: Double = 0.0
    private var refreshMin: Int = 15
    private var weatherJob: Job? = null
    private var ip: String = ""

    // The bar's own rule: the alert (or media, or nav) is pushed over BLE;
    // the ESP draws it in the persistent bar.
    init {
        NotifOutbox.ble = ble
    }

    val link: BleLink
        get() = ble

    val lastStatus: LinkStatus?
        get() = LinkStatus.fromJson(ble.lastStatus)

    /** The current BLE link state (the HUD chip). */
    sealed class LinkUiState {
        object Idle : LinkUiState()
        object Scanning : LinkUiState()
        object Connecting : LinkUiState()
        data class Connected(val status: LinkStatus) : LinkUiState()
        data class Failed(val reason: String) : LinkUiState()
    }

    private val uiState = mutableStateOf<LinkUiState>(LinkUiState.Idle)

    fun state(): LinkUiState = uiState.value

    /** Set the ESP IP (called once when the connection is established). */
    fun setIp(newIp: String) {
        ip = newIp
    }

    fun boot() {
        scope.launch {
            try {
                val config = fetchConfig()
                city = config.optString("WEATHER_CITY")
                lat = config.optDouble("WEATHER_LAT", 0.0)
                lon = config.optDouble("WEATHER_LON", 0.0)
                refreshMin = config.optInt("WEATHER_REFRESH_MIN", 15)
            } catch (e: Exception) {
                // config fetch failed: keep the defaults (the ESP's factory
                // values); the weather timer will use them.
            }
            startScan()
            startWeatherTimer()
        }
    }

    private fun startScan() {
        uiState.value = LinkUiState.Scanning
        ble.startScan()
    }

    /**
     * The phone-side weather timer (§5.5). Fetches once on connect, then
     * every `WEATHER_REFRESH_MIN`; a fetch failure keeps the last-pushed
     * value (it is never cleared). If BLE is not linked, the phone does not
     * fetch.
     *
     * Re-enters on reconnect: the outer loop waits for a connect, pushes
     * while connected, and - when the link drops - waits for the next
     * connect and resumes (previously the coroutine terminated after the
     * first disconnect and the timer was gone for good).
     */
    private fun startWeatherTimer() {
        weatherJob?.cancel()
        weatherJob = scope.launch {
            while (true) {
                // wait for the link (poll — the BleLink is the central; the
                // connect is not a push event here).
                while (!ble.isConnected()) delay(1000)
                while (ble.isConnected()) {
                    try {
                        val dto = OpenMeteoClient.fetch(lat, lon, city)
                        ble.sendFrame(Protocol.encode(Protocol.T_WEATHER, dto.toJson()))
                    } catch (e: Exception) {
                        // keep the last-pushed value (never cleared)
                    }
                    delay(refreshMin.toLong() * 60_000L)
                }
            }
        }
    }

    /** The manual "push weather now" action (§11 chip). */
    fun pushWeatherNow() {
        scope.launch {
            try {
                val dto = OpenMeteoClient.fetch(lat, lon, city)
                ble.sendFrame(Protocol.encode(Protocol.T_WEATHER, dto.toJson()))
            } catch (e: Exception) {
                // keep the last-pushed value
            }
        }
    }

    private fun fetchConfig(): org.json.JSONObject {
        if (ip.isEmpty()) return org.json.JSONObject()
        val response = http.newCall(
            Request.Builder().url("http://$ip/api/config").build(),
        ).execute()
        val body = response.body?.string() ?: ""
        response.close()
        return org.json.JSONObject(body)
    }

    fun dispose() {
        weatherJob?.cancel()
        ble.close()
        scope.coroutineContext[Job]?.cancel()
    }
}
