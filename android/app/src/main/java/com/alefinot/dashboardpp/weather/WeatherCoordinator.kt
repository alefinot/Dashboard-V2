package com.alefinot.dashboardpp.weather

import android.content.Context
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleReply
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.ble.WeatherPush
import com.alefinot.dashboardpp.api.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface WeatherUnit {
    /** The unit's own position, or null when it has no fix or the link is down. */
    suspend fun snapshot(): Snapshot?

    suspend fun pushWeather(push: WeatherPush): BleReply

    suspend fun pushTime(epochSeconds: Long): BleReply
}

/** The production [WeatherUnit]: the app's BLE api for the live link. */
class BleWeatherUnit(private val api: BleApi) : WeatherUnit {
    override suspend fun snapshot(): Snapshot? = api.snapshot()

    override suspend fun pushWeather(push: WeatherPush): BleReply = api.pushWeather(push)

    override suspend fun pushTime(epochSeconds: Long): BleReply = api.pushTime(epochSeconds)
}

interface WeatherStore {
    suspend fun enabledNow(): Boolean

    suspend fun cityNow(): CityFallback

    suspend fun cacheFor(unitKey: String): WeatherCache?

    suspend fun saveCache(unitKey: String, cache: WeatherCache)
}

/** What the App Settings card and any other watcher shows about the feed. */
data class WeatherStatus(
    val unitKey: String? = null,
    val lastSuccessAt: Long? = null,
    val lastPushAt: Long? = null,
    val source: PositionSource? = null,
    /** What the app calls the place it fetched for, in full — the card may wrap it. */
    val label: String? = null,
    /** Plain-language problem for the card; null when nothing is wrong. */
    val note: String? = null,
    /** True while a fetch/push pass is running. */
    val busy: Boolean = false,
)

class WeatherCoordinator(
    private val unit: WeatherUnit,
    private val store: WeatherStore,
    private val http: WeatherHttp,
    private val names: PlaceNames = PlaceNames(http),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val lock = Mutex()
    private val _status = MutableStateFlow(WeatherStatus())
    val status: StateFlow<WeatherStatus> = _status

    private var inFlight = false

    private var retryAtMs = 0L

    /** A reading fetched but not delivered, kept for the next pass. */
    private var pending: WeatherPush? = null

    private var heldFrame: WeatherPush? = null
    private var heldFrameFor: String? = null
    private var heldAtMs = 0L

    /** The unit that came up and has not been given a frame yet. Cleared on delivery. */
    private var awaitingFrameFor: String? = null

    private var servingUnitKey: String? = null
    private var lastTimePushMs = 0L

    suspend fun unitWoke(unitKey: String, snapshot: Snapshot? = null) {
        awaitingFrameFor = unitKey
        wake("link-up", unitKey, snapshot)
    }

    suspend fun wake(reason: String, unitKey: String, snapshot: Snapshot? = null, force: Boolean = false) =
        withContext(io) {
            if (inFlight) return@withContext
            inFlight = true
            _status.value = _status.value.copy(unitKey = unitKey, busy = true)
            try {
                lock.withLock { pass(unitKey, snapshot, force) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = _status.value.copy(note = internalProblem(e))
                retryAtMs = clock() + WeatherPolicy.RETRY_AFTER_FAILURE_MS
            } finally {
                inFlight = false
                _status.value = _status.value.copy(busy = false)
            }
        }

    private fun internalProblem(e: Exception): String {
        val frame = e.stackTrace.firstOrNull { it.className.startsWith("com.alefinot.dashboardpp") }
            ?.let { " at ${it.methodName}(${it.fileName}:${it.lineNumber})" }
            ?: ""
        val detail = e.javaClass.simpleName +
            e.message?.take(140)?.replace('\n', ' ')?.let { ": $it" }.orEmpty()
        return "The weather feed hit a problem it did not expect ($detail)$frame. " +
            "It will try again."
    }

    /** Ask for weather for one position and push it, ignoring the schedule. */
    suspend fun pushNow(unitKey: String, snapshot: Snapshot? = null) =
        wake("manual", unitKey, snapshot, force = true)

    suspend fun resolveCity(name: String): Place? = withContext(io) { names.geocodeCity(name) }

    private suspend fun pass(unitKey: String, snapshot: Snapshot?, force: Boolean) {
        val now = clock()

        if (servingUnitKey != unitKey) {
            servingUnitKey = unitKey
            lastTimePushMs = now
        }

        if (!store.enabledNow()) {
            pending = null
            _status.value = _status.value.copy(note = null, source = null, label = null)
            return
        }

        if (now - lastTimePushMs >= WeatherPolicy.TIME_REPUSH_MS) {
            if (unit.pushTime(now / 1000L).ok) lastTimePushMs = now
        }

        val cache = store.cacheFor(unitKey)
        val lastFetchMs = cache?.fetchEpochMs?.takeIf { it != WeatherSettings.NO_EPOCH }

        pending?.let { queued ->
            if (now >= retryAtMs) {
                when (deliver(queued)) {
                    DeliverResult.DONE -> {
                        pending = null
                        savePush(unitKey, queued, now)
                    }
                    DeliverResult.BAD_PAYLOAD -> {
                        pending = null
                        _status.value = _status.value.copy(
                            note = "The unit rejected the weather payload (${BleApi.weatherParams(queued)}).",
                        )
                    }
                    DeliverResult.RETRY_LATER -> {}
                }
            }
            return
        }

        if (awaitingFrameFor == unitKey && now >= retryAtMs) {
            val held = heldFrame?.takeIf {
                heldFrameFor == unitKey && it.valid &&
                    now - heldAtMs < WeatherPolicy.INTERVAL_MS
            }
            if (held != null) {
                if (deliver(held) == DeliverResult.DONE) {
                    awaitingFrameFor = null
                    savePush(unitKey, held, now)
                }
                return
            }
        }

        if (cache != null && lastFetchMs != null &&
            cache.lastPushEpochMs != WeatherSettings.NO_EPOCH &&
            WeatherPolicy.stale(now, lastFetchMs) && !cache.offlineSent
        ) {
            val goodbye = WeatherPolicy.stalePush(cache.label)
            if (deliver(goodbye) == DeliverResult.DONE) {
                store.saveCache(unitKey, cache.copy(lastPushEpochMs = now, offlineSent = true))
                _status.value = _status.value.copy(lastPushAt = now, note = null)
            }
            return
        }

        // 4. Is anything due, using only positions we already have for free?
        val host = snapshot ?: HostFrames.last(unitKey)
        val due = WeatherPolicy.due(
            nowMs = now,
            lastFetchMs = lastFetchMs,
            last = cache?.asLatLon(),
            current = WeatherPolicy.positionOf(host),
            enabled = true,
        )
        if (!force && due == WeatherDue.NEVER && awaitingFrameFor != unitKey) return
        if (!force && now < retryAtMs) return

        val city = store.cityNow()
        val pos = WeatherPolicy.positionLadder(
            fix = host ?: unit.snapshot(),
            cached = cache?.asCachedPosition(),
            city = city.place,
            nowMs = now,
        ) ?: run {
            _status.value = _status.value.copy(
                note = "No position to ask for weather about — give the feed a city, or wait " +
                    "for the unit's GNSS fix.",
            )
            retryAtMs = now + NO_POSITION_BACKOFF_MS
            return
        }

        val forCard = pos.labelHint ?: names.label(pos.lat, pos.lon, city.typed)
        val forUnit = PlaceNames.panelLabel(forCard) ?: PlaceNames.coordinateLabel(pos.lat, pos.lon)

        val url = OpenMeteo.url(pos.lat, pos.lon)
        val reading = url?.let { OpenMeteo.parse(http.get(it) ?: return@let null) }
        if (reading == null) {
            _status.value = _status.value.copy(
                note = "Open-Meteo did not answer for $forCard. The unit keeps showing the last " +
                    "frame it was sent.",
            )
            retryAtMs = now + WeatherPolicy.RETRY_AFTER_FAILURE_MS
            return
        }

        val row = WeatherCache(
            lat = pos.lat,
            lon = pos.lon,
            label = forUnit,
            fetchEpochMs = now,
            lastPushEpochMs = cache?.lastPushEpochMs ?: WeatherSettings.NO_EPOCH,
            offlineSent = cache?.offlineSent ?: false,
        )
        store.saveCache(unitKey, row)

        val push = WeatherPolicy.buildPush(reading, forUnit)
        when (deliver(push)) {
            DeliverResult.DONE -> {
                pending = null
                savePush(unitKey, push, now)
                _status.value = _status.value.copy(
                    lastSuccessAt = now,
                    source = pos.source,
                    label = forCard,
                    note = null,
                )
            }
            DeliverResult.BAD_PAYLOAD -> {
                pending = null
                _status.value = _status.value.copy(
                    source = pos.source,
                    label = forCard,
                    note = "The unit rejected the weather payload (${BleApi.weatherParams(push)}).",
                )
            }
            DeliverResult.RETRY_LATER -> {
                pending = push
                _status.value = _status.value.copy(
                    source = pos.source,
                    label = forCard,
                    note = "The unit did not take the weather frame; it will be sent again.",
                )
            }
        }
    }

    private enum class DeliverResult { DONE, RETRY_LATER, BAD_PAYLOAD }

    /** Send one frame, taking the unit's `busy` (its 100 ms settings mutex) once. */
    private suspend fun deliver(push: WeatherPush): DeliverResult {
        var reply = unit.pushWeather(push)
        if (!reply.ok && reply.error == "busy") reply = unit.pushWeather(push)
        if (reply.ok) return DeliverResult.DONE

        retryAtMs = clock() + backoffFor(reply.error)
        return if (reply.error == "bad_param") DeliverResult.BAD_PAYLOAD else DeliverResult.RETRY_LATER
    }

    private fun backoffFor(error: String?): Long = when (error) {
        "ota_in_progress" -> WeatherPolicy.RETRY_AFTER_OTA_MS
        "busy" -> BUSY_BACKOFF_MS
        else -> WeatherPolicy.RETRY_AFTER_FAILURE_MS
    }

    /** Record that [push] reached the unit now, and that its data is current. */
    private suspend fun savePush(unitKey: String, push: WeatherPush, now: Long) {
        val cache = store.cacheFor(unitKey) ?: return
        store.saveCache(
            unitKey,
            cache.copy(
                lastPushEpochMs = now,
                // A live frame ends any offline claim; a goodbye frame starts one.
                offlineSent = !push.valid,
            ),
        )
        _status.value = _status.value.copy(lastPushAt = now)
        if (push.valid) {
            heldFrame = push
            heldFrameFor = unitKey
            heldAtMs = cache.fetchEpochMs
        }
    }

    companion object {
        /** The unit's 100 ms settings mutex, after the one immediate retry. */
        const val BUSY_BACKOFF_MS = 5 * 1000L

        /** Nothing to ask about (no fix, no cache, no city): not a failure, check later. */
        const val NO_POSITION_BACKOFF_MS = 5 * 60 * 1000L
    }
}

object HostFrames {
    private val byUnit = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    fun note(unitKey: String, snapshot: Snapshot) {
        byUnit[unitKey] = snapshot
    }

    fun last(unitKey: String): Snapshot? = byUnit[unitKey]

    fun clear(unitKey: String) {
        byUnit.remove(unitKey)
    }
}

object WeatherRuntime {

    @Volatile
    private var instance: WeatherCoordinator? = null

    fun coordinator(context: Context): WeatherCoordinator =
        instance ?: synchronized(this) {
            instance ?: WeatherCoordinator(
                unit = BleWeatherUnit(BleApi(BleRuntime.link(context))),
                store = WeatherSettings(context),
                http = OkHttpWeatherHttp(OkHttpWeatherHttp.userAgent(context)),
            ).also { instance = it }
        }
}
