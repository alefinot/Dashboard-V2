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

/**
 * What the coordinator can ask a unit for.
 *
 * A seam on purpose, like [WeatherHttp]. The coordinator's whole job is deciding when
 * to spend a call, so the calls it decides *not* to make have to be countable from a
 * JVM test — and [BleApi] is welded to [BleLink], which is welded to the phone's GATT
 * stack. [BleWeatherUnit] is the one production implementation.
 */
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

/**
 * The slice of the store the coordinator reads and writes, so the schedule can be
 * tested without Android's DataStore (same reasoning as [WeatherUnit]).
 * [WeatherSettings] is the production implementation.
 */
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

/**
 * The single owner of weather fetching, pushing and scheduling (weather push plan
 * phase 1).
 *
 * Hosts — the ViewModel's link-up, its 60 s tick, and the trip service on each handled
 * frame — only call [wake]. One pass holds one lock plus one in-flight guard, so the
 * ViewModel and the service waking at the same moment costs one fetch, which is the
 * whole reason this is one object instead of two call sites.
 *
 * The clock is injected because "is this fetch due" is the behaviour under test, and a
 * test cannot wait fifteen minutes for a wall clock.
 */
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

    /**
     * Not the lock: a wake that arrives while a pass is running is dropped rather than
     * queued, because a queued pass would immediately re-run the same decision.
     */
    private var inFlight = false

    /**
     * When to try again after a failed fetch or a refused push. Separate from
     * [WeatherPolicy.INTERVAL_MS] on purpose: a transient failure must not wait out the
     * schedule to be tried again, and a unit that refused us must not be hammered either.
     */
    private var retryAtMs = 0L

    /** A reading fetched but not delivered, kept for the next pass. */
    private var pending: WeatherPush? = null

    /**
     * The last frame this process got onto a unit, which unit it went to, and how old its
     * data is (stamped from the fetch, not from the push, so re-sending it does not make
     * it younger). RAM on purpose: its only job is answering a unit that came up between
     * two fetches, and a frame older than the interval belongs to a fetch anyway.
     */
    private var heldFrame: WeatherPush? = null
    private var heldFrameFor: String? = null
    private var heldAtMs = 0L

    /** The unit that came up and has not been given a frame yet. Cleared on delivery. */
    private var awaitingFrameFor: String? = null

    private var servingUnitKey: String? = null
    private var lastTimePushMs = 0L

    /**
     * A unit just came up: hand it a frame as fast as possible, and fetch only if this
     * process has nothing worth handing over.
     *
     * Separate from [wake] because the question is different. [wake] asks "is a fetch
     * due"; a unit powered off and back on five minutes after a fetch is not due for one
     * and still shows an empty card, because its weather lives in RAM (`g_weatherData`,
     * stamped by `millis()` in src/bt_rpc.cpp) and every reboot loses what we sent it. The
     * answer to "this unit has nothing to show" cannot be "wait for the schedule".
     */
    suspend fun unitWoke(unitKey: String, snapshot: Snapshot? = null) {
        awaitingFrameFor = unitKey
        wake("link-up", unitKey, snapshot)
    }

    /**
     * One wake-up: "something happened; decide what is due".
     *
     * [snapshot] is the frame the host already has in hand — the trip service passes the
     * frame it just recorded, which is how a 2 km move is noticed without spending an RPC
     * to ask the unit where it is. Pass null (the ViewModel's tick) and the coordinator
     * spends at most one `snapshot` RPC, and only when it was going to fetch anyway.
     */
    suspend fun wake(reason: String, unitKey: String, snapshot: Snapshot? = null, force: Boolean = false) =
        withContext(io) {
            if (inFlight) return@withContext
            inFlight = true
            _status.value = _status.value.copy(unitKey = unitKey, busy = true)
            try {
                lock.withLock { pass(unitKey, snapshot, force) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Whoever owns this object is going away; that is not a feed failure and it
                // must not be recorded as one. `finally` still parks `busy`.
                throw e
            } catch (e: Exception) {
                // If this object dies the dashboard's weather card goes stale with it, so an
                // unexpected problem becomes a note and a later retry instead of a crash. The
                // note carries the exception's own identity, because this app logs nothing and
                // the card is the only window anyone has onto what happened.
                _status.value = _status.value.copy(note = internalProblem(e))
                retryAtMs = clock() + WeatherPolicy.RETRY_AFTER_FAILURE_MS
            } finally {
                inFlight = false
                _status.value = _status.value.copy(busy = false)
            }
        }

    /**
     * The swallowed-exception report, spelled so an owner can read it and a developer can
     * act on it: the class, the message, and the first frame inside this app. Frames from
     * OkHttp or the coroutines machinery are noise next to that.
     */
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

    /**
     * Look a typed city up for the settings card, so the only thing a screen has to call
     * is this coordinator. The card lives in `ui/screens`, where no network client belongs
     * — that is the rule the Web page used to enforce by not existing.
     */
    suspend fun resolveCity(name: String): Place? = withContext(io) { names.geocodeCity(name) }

    private suspend fun pass(unitKey: String, snapshot: Snapshot?, force: Boolean) {
        val now = clock()

        if (servingUnitKey != unitKey) {
            servingUnitKey = unitKey
            // The clock push at link-up stays the ViewModel's job (it predates this
            // object, and it happens once per link); this timer starts at link-up too, so
            // the 6 h re-push cannot fire right on top of it.
            lastTimePushMs = now
        }

        if (!store.enabledNow()) {
            pending = null
            _status.value = _status.value.copy(note = null, source = null, label = null)
            return
        }

        // The plan's 6 h `time.set` re-push, riding this schedule so there is one timer
        // and one log line for both periodic jobs.
        if (now - lastTimePushMs >= WeatherPolicy.TIME_REPUSH_MS) {
            if (unit.pushTime(now / 1000L).ok) lastTimePushMs = now
        }

        val cache = store.cacheFor(unitKey)
        val lastFetchMs = cache?.fetchEpochMs?.takeIf { it != WeatherSettings.NO_EPOCH }

        // 1. A frame we already paid the network for goes out before anything new is
        // asked for.
        pending?.let { queued ->
            if (now >= retryAtMs) {
                when (deliver(queued)) {
                    DeliverResult.DONE -> {
                        pending = null
                        savePush(unitKey, queued, now)
                    }
                    // A payload the unit refuses will not start passing on the third try;
                    // dropping it costs one frame and keeps the note as the report.
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

        // 2. A unit that came up between two fetches. It has lost the frame we last sent
        // it and the schedule says nothing is due, so the frame this process is holding is
        // the difference between a weather card and a blank one. Asked for nothing: no
        // network, no `snapshot` RPC, one frame on the link. Ahead of the staleness
        // goodbye on purpose - a unit that just woke up needs data, not a "no data" marker
        // it would then wait a tick to have corrected.
        if (awaitingFrameFor == unitKey && now >= retryAtMs) {
            val held = heldFrame?.takeIf {
                heldFrameFor == unitKey && it.valid &&
                    now - heldAtMs < WeatherPolicy.INTERVAL_MS
            }
            if (held != null) {
                // Still owed on failure: `awaitingFrameFor` is only cleared by a delivery
                // the unit accepted, so the next tick offers it again.
                if (deliver(held) == DeliverResult.DONE) {
                    awaitingFrameFor = null
                    savePush(unitKey, held, now)
                }
                return
            }
            // Nothing here to hand over, and `awaitingFrameFor` stays set: that is what
            // makes the schedule below fetch instead of obeying its own "not due yet".
        }

        // 3. A unit that has a frame from us, and whose last feed is now stale, gets one
        // `valid=false` frame — the plan's once-per-transition, never again until it
        // recovers. `offlineSent` is what makes that survive an app restart.
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
        // `PUSH NOW` on the settings card is the owner asking, so it skips the schedule;
        // everything else obeys it. A unit that came up and was handed nothing is the other
        // exception: it is owed a frame, so "the cache is fresh" is not an answer.
        if (!force && due == WeatherDue.NEVER && awaitingFrameFor != unitKey) return
        if (!force && now < retryAtMs) return

        // 5. The ladder. The unit's snapshot RPC is spent only here, only when nothing
        // already tells us where the car is, and only because this pass is fetching.
        val city = store.cityNow()
        // One `snapshot` RPC, spent only because this pass is fetching and nothing else
        // says where the car is.
        val pos = WeatherPolicy.positionLadder(
            fix = host ?: unit.snapshot(),
            cached = cache?.asCachedPosition(),
            city = city.place,
            nowMs = now,
        ) ?: run {
            _status.value = _status.value.copy(
                note = "No position \u2014 add a city or wait for a GNSS fix.",
            )
            retryAtMs = now + NO_POSITION_BACKOFF_MS
            return
        }

        // 6. Two labels, because the card and the unit need different things: the card
        // can wrap and show accents, the unit has a 47-byte field and ASCII-only fonts.
        val forCard = pos.labelHint ?: names.label(pos.lat, pos.lon, city.typed)
        val forUnit = PlaceNames.panelLabel(forCard) ?: PlaceNames.coordinateLabel(pos.lat, pos.lon)

        val url = OpenMeteo.url(pos.lat, pos.lon)
        val reading = url?.let { OpenMeteo.parse(http.get(it) ?: return@let null) }
        if (reading == null) {
            _status.value = _status.value.copy(
                note = "Open-Meteo did not answer for $forCard.",
            )
            retryAtMs = now + WeatherPolicy.RETRY_AFTER_FAILURE_MS
            return
        }

        // The fetch is recorded before the push is attempted: the network is already
        // spent, and a unit that refuses the frame must not be re-billed for it.
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
            // A refused payload is our bug, not the link's, so it is reported with the
            // payload in it and not queued: the plan calls `bad_param` a parser bug.
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
                    note = "Weather frame not taken \u2014 retrying.",
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

    /**
     * How long to leave a refusing unit alone. `ota_in_progress` is the unit saying it
     * is writing firmware — the plan's 60 s re-arm, so a weather push never shows up as
     * a link failure during an install.
     */
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
        // Remember what the unit now holds, for the next time it comes up. A `valid=false`
        // goodbye is not worth holding: it is a marker, and a unit that woke up to an empty
        // card does not need a second one.
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

/**
 * The last frame each host has already handled, per unit.
 *
 * Kept outside the coordinator so a ViewModel tick that has no frame in hand does not
 * spend an RPC on a move check it cannot use, while the recorder's 1 Hz frames are
 * still there for the next pass to look at.
 */
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

/**
 * One coordinator for the whole process, same shape as [BleRuntime]: the ViewModel and
 * the foreground service have to share one schedule, or the unit gets two feeds and the
 * phone makes two calls for the same minute of weather.
 */
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
