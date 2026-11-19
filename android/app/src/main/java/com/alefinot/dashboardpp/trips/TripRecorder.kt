package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot
import java.io.File

/** What trip is currently open, if any. */
data class TripRef(val path: String, val startMs: Long)

/**
 * Everything needed to recognise the same ESP power session later — including
 * after the app died — so a trip sealed when the device went out of range can
 * still be continued by the same boot of the ESP (plan §3).
 */
data class SessionRecord(
    val ip: String,
    /** Last `up` (ESP uptime in s) accepted for this session. */
    val lastUp: Long,
    /** Phone time of that last accepted sample. */
    val lastSampleMs: Long,
    /** Highest `max` (ESP session peak speed) seen in this session. */
    val lastMaxSpeed: Double,
    val tripPath: String?,
    val tripStartMs: Long,
    /** True once the trip was sealed; a sealed trip may still be re-opened. */
    val sealed: Boolean,
)

enum class TripEvent { NONE, OPENED, REOPENED, APPENDED, SEALED_THEN_OPENED, SEALED }

/**
 * The trip session state machine (trip recording plan §3): one trip per ESP
 * power session, sealed when the device stops answering, re-opened if the same
 * session comes back within 24 h, split after 12 h of continuous recording.
 *
 * Deliberately free of Android and HTTP: it is handed samples, a clock and a
 * [TripWriter], so every boundary case is unit-testable on the JVM.
 */
class TripRecorder(
    private val writer: TripWriter,
    private val pathFor: (startMs: Long) -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    initialSession: SessionRecord? = null,
    /** The app's ROUTE QUALITY slider (0..100), read once at start-up. */
    private val routeQuality: Int = TrackQuality.MAX,
) {
    var openTrip: TripRef? = null
        private set

    var lastSnapshot: Snapshot? = null
        private set

    /** Phone time of the last successful sample — the seal timer reads this. */
    var lastSampleMs: Long = 0L
        private set

    /** Why the last seal happened, for the log and the notification copy. */
    var lastSealReason: String? = null
        private set

    private var accumulator = TripAccumulator()
    private var ip: String = initialSession?.ip ?: ""
    private var session: SessionRecord? = initialSession

    /**
     * The open trip's shape so far, for the live route maps (live route plan §1).
     *
     * The recorder already holds every position it appends, and the maps otherwise had
     * no way to see the drive before its file was scanned - so the shape lives here,
     * next to the totals, and travels through [TripRuntimeState] like the totals do.
     * It runs [LiveTrack] doubled: at zoom 15 the follow map shows a few hundred
     * metres across, and the stored preview's 128 points are a whole drive stretched
     * over a card, too coarse for a line being watched as it is drawn.
     *
     * The route is segmented because a pause is a real gap in it (2.0.7/179): a resume
     * starts a new piece, and no map draws a line across the ground the file never saw.
     */
    private var liveTrack = newLiveTrack()

    /**
     * A fresh [LiveTrack] at the app's current route-quality setting: the slider decides how
     * long the live line stays full detail before RAM buys itself relief (maintainer,
     * 2026-11-19). Built here rather than at each call site so a drive, a re-opened drive
     * and its seeding pass all run the same dials.
     */
    private fun newLiveTrack(): LiveTrack {
        val q = TrackQuality.fromQuality(routeQuality)
        return LiveTrack(
            firstThinAt = LIVE_TRACK_FIRST_THIN_AT,
            toleranceM = q.baseToleranceM,
            maxToleranceM = q.maxToleranceM,
            totalThinAt = q.totalThinAt,
        )
    }

    /**
     * Where each pause of the open drive froze the recorded line, oldest first.
     *
     * Appended by [resume], not by [pause]: a pause is still in progress, and while it is,
     * its dot is derived from the route (the last written position) instead of being fixed.
     * On resume the position stops moving and becomes a fact about the drive, so it is
     * written down here - and it stays written: the owner reads these dots as "where I
     * stopped and started again", and a resume that cleared them lost the trail of every
     * pause but the last one (2026-11-18).
     */
    private val pauseMarks = ArrayList<TrackPoint>()

    /** Where the last closed drive ended - the maps' red END dot. */
    private var endedAt: TrackPoint? = null

    /**
     * The open trip's start instant, used as the live route's identity. A map resets its
     * overlays and reframes once per value, so a new drive cannot inherit the old one's
     * heading or markers even when the first fix lands a metre away.
     */
    private var driveId = 0L

    /**
     * The `#vehicle=` line for the next file this recorder creates ("" = no tag).
     *
     * The app - not the recorder - decides who a trip belongs to: the recorder is
     * handed samples, and a sample carries no identity. What it is handed instead
     * is this line, rendered by the service from the vehicle card that was selected
     * when the drive started. It is deliberately a snapshot rather than a live
     * lookup: renaming a vehicle later must not rewrite a drive that already
     * happened, and [bindVehicle] during an open trip changes the next file, never
     * the one being written.
     */
    private var tag: String = ""

    /**
     * Say whose drive this is. Empty [vehicleId] (no card selected) means no tag:
     * an untagged file is honest, while a tag with a half-known id would be a guess.
     */
    fun bindVehicle(vehicleId: String, eff: String, name: String) {
        val next = TripFormat.tagLine(vehicleId, eff, name)
        if (next != tag) tag = next
    }

    /**
     * A trip sealed by the 12 h cap must not be re-opened by the next sample of
     * the same ESP session — otherwise the cap would never actually split the
     * the file. Remembered here rather than in [SessionRecord] so the persisted
     * session format stays unchanged. The cost of holding it in memory only: if
     * the app itself restarts inside the 24 h re-open window, that trip may be
     * continued past the cap — still one file per ESP power session, which is the
     * rule that matters, just longer than 12 h.
     */
    private var cappedTripPath: String? = null

    fun ip(): String = ip

    /**
     * Point the recorder at a device. A different ip means a different unit, so
     * any persisted session for the previous one must not be reused — and an open
     * trip is sealed first, because two dashboards can never be one drive: their
     * uptimes are unrelated, and merging them would invent a single trip out of
     * two devices.
     */
    fun bindIp(newIp: String) {
        if (ip != newIp) {
            if (openTrip != null && ip.isNotEmpty()) seal("device-changed")
            ip = newIp
            session = null
        }
    }

    val isRecording: Boolean get() = openTrip != null

    /**
     * True while the open trip is paused: the file stays open, but no sample is
     * appended until [resume]. Runtime state only - it is not part of [SessionRecord],
     * because a pause belongs to the pass the owner is on, not to the drive.
     */
    var isPaused: Boolean = false
        private set

    /**
     * Stop logging samples without ending the trip. The file stays open, so a resume
     * continues the same drive; the span becomes a timestamp gap in it.
     *
     * Deliberately allowed before the first sample too: a service command can land
     * while the recorder is still waiting for a frame, and an ignored pause would log
     * the very samples the owner asked it not to.
     */
    fun pause() {
        isPaused = true
    }

    /** Continue appending to the same open file. */
    fun resume() {
        if (!isPaused) return
        isPaused = false
        // A resume closes one recorded stretch and opens another. Two things follow: the
        // place the old one froze at becomes a permanent orange dot, and the new piece is
        // not joined to it - the map draws a second START beside it and no line crosses the
        // ground the file never saw.
        //
        // Only while a trip is open: with no file there was nothing recorded to freeze, and
        // the position the drive last wrote is already the END dot of the file that closed.
        if (openTrip != null) {
            liveTrack.lastRecorded()?.let { pauseMarks.add(it) }
            liveTrack.startSegment()
        }
    }

    /** Running summary of the open trip, straight from RAM (no file re-read). */
    fun summary(): TripSummary? = accumulator.summary()

    /**
     * The route the live maps draw: every recorded stretch, and the dots a pause or a stop
     * left behind.
     *
     * The route survives a seal (2.0.7/179): the file is closed, but the drive's shape stays
     * on the maps with its END dot until the next START replaces it. [LiveRoute.endAt] is
     * therefore reported while no trip is open, and the pause dot a closing seal overtakes is
     * deliberately not - the drive closed at that position, so it is now the END dot, while
     * every pause before it stays orange.
     *
     * A recorder whose pause began before the first sample has no open file and no recorded
     * point; its route is empty and has no pause dot, which is the honest picture of a drive
     * that does not exist yet.
     */
    fun liveRoute(): LiveRoute {
        val open = openTrip != null
        val pauses = if (open && isPaused) {
            val frozen = liveTrack.lastRecorded()
            if (frozen == null) pauseMarks.toList() else pauseMarks + frozen
        } else {
            pauseMarks.toList()
        }
        return LiveRoute(
            driveId = driveId,
            segments = liveTrack.snapshot(),
            pauses = pauses,
            endAt = if (open) null else endedAt,
        )
    }

    fun sessionRecord(): SessionRecord? = session

    /** Feed one successful snapshot; returns what happened for logging. */
    fun onSnapshot(newIp: String, s: Snapshot): TripEvent {
        bindIp(newIp)
        val now = nowMs()
        var opened = false
        var reopened = false
        var sealedBeforeOpen = false

        if (openTrip != null) {
            val prev = lastSnapshot
            if (prev != null && rebooted(prev, s)) {
                seal("esp-reboot")
                sealedBeforeOpen = true
            } else if (now - (openTrip?.startMs ?: now) >= TripPolicy.MAX_TRIP_MS) {
                cappedTripPath = openTrip?.path
                seal("session-cap-12h")
                sealedBeforeOpen = true
            }
        }

        // A paused recorder keeps following the link - the fresh silence baseline is
        // what stops the 120 s seal from firing during a long pause on a live unit -
        // but it writes nothing, accumulates nothing and opens no file. The reboot and
        // cap checks above still ran, so a pause cannot mask a dead or restarted device.
        if (isPaused) {
            lastSampleMs = now
            lastSnapshot = s
            return TripEvent.NONE
        }

        if (openTrip == null) {
            val reuse = reusableSession(s, now)
            if (reuse != null && reuse.tripPath != null) {
                writer.reopen(reuse.tripPath)
                seedAccumulator(reuse.tripPath, reuse.tripStartMs)
                openTrip = TripRef(reuse.tripPath, reuse.tripStartMs)
                reopened = true
                opened = true
            } else {
                val path = pathFor(now)
                writer.create(path, tag)
                accumulator = TripAccumulator()
                liveTrack = newLiveTrack()
                pauseMarks.clear()
                endedAt = null
                driveId = now
                openTrip = TripRef(path, now)
                opened = true
            }
        }

        val trip = openTrip ?: return TripEvent.NONE
        lastSampleMs = now
        lastSnapshot = s
        val sample = TripSample(now, s)
        accumulator.add(sample)
        if (s.hasPosition) liveTrack.add(s.lat, s.lon)
        writer.append(trip.path, TripFormat.line(sample))
        session = SessionRecord(
            ip = ip,
            lastUp = s.up,
            lastSampleMs = now,
            lastMaxSpeed = maxOf(session?.lastMaxSpeed ?: 0.0, s.max),
            tripPath = trip.path,
            tripStartMs = trip.startMs,
            sealed = false,
        )
        return when {
            reopened -> TripEvent.REOPENED
            sealedBeforeOpen -> TripEvent.SEALED_THEN_OPENED
            opened -> TripEvent.OPENED
            else -> TripEvent.APPENDED
        }
    }

    /**
     * Called every tick whether or not the poll worked. Seals the trip once the
     * device has been silent past [TripPolicy.SEAL_AFTER_MS].
     */
    fun onTick(): TripEvent {
        val trip = openTrip ?: return TripEvent.NONE
        val last = lastSampleMs
        if (last != 0L && nowMs() - last > TripPolicy.SEAL_AFTER_MS) {
            seal("device-silent")
            return TripEvent.SEALED
        }
        return TripEvent.NONE
    }

    /** Service stopping: seal, leaving a complete file. */
    fun stop(): TripEvent {
        isPaused = false
        if (openTrip == null) return TripEvent.NONE
        seal("service-stop")
        return TripEvent.SEALED
    }

    private fun rebooted(prev: Snapshot, s: Snapshot): Boolean {
        // Uptime went backwards: the ESP rebooted, end of session.
        if (s.up < prev.up) return true
        // `max` is RAM-only state on the ESP: it returns to 0 on a reboot and
        // nowhere else (no NVS, no manual reset). If it dropped while `up` kept
        // climbing, the unit restarted inside a window we were not polling in.
        return s.max < prev.max - MAX_SPEED_EPS
    }

    private fun reusableSession(s: Snapshot, now: Long): SessionRecord? {
        val r = session ?: return null
        val path = r.tripPath ?: return null
        if (!r.sealed) return null
        if (path == cappedTripPath) return null
        if (!writer.exists(path)) return null
        if (now - r.lastSampleMs > TripPolicy.REOPEN_WINDOW_MS) return null
        if (s.up < r.lastUp) return null
        // Same boot: the ESP's clock may not be set, but its uptime still advances
        // at one second per second, so it cannot have gained more time than the
        // phone has elapsed (plus slack for phone sleep and the ESP's NTP jump).
        val elapsedMs = now - r.lastSampleMs
        val upDeltaMs = (s.up - r.lastUp) * 1000L
        if (upDeltaMs > elapsedMs + TripPolicy.UP_SLACK_MS) return null
        if (s.max < r.lastMaxSpeed - MAX_SPEED_EPS) return null
        return r
    }

    /**
     * Re-opened files already hold samples; the running summary has to start from
     * them or the "Recording…" row would show only the part after the gap. The live
     * route is seeded from the same pass for the same reason - the map on return
     * shows the whole drive, not the last minute of it.
     */
    private fun seedAccumulator(path: String, startMs: Long) {
        accumulator = TripAccumulator()
        liveTrack = newLiveTrack()
        pauseMarks.clear()
        endedAt = null
        driveId = startMs
        writer.readDataLines(path).forEach { line ->
            TripFormat.parseLine(line)?.let {
                accumulator.add(it)
                val s = it.snapshot
                if (s.hasPosition) liveTrack.add(s.lat, s.lon)
            }
        }
    }

    private fun seal(reason: String) {
        val trip = openTrip ?: return
        // The drive's last written position - what the END dot marks. The route itself is
        // not thrown away: a closed drive stays on the maps, with every pause dot it
        // gathered, until the next START replaces it (2.0.7/179).
        endedAt = liveTrack.lastRecorded()
        writer.seal(trip.path)
        openTrip = null
        session = (session ?: SessionRecord(
            ip = ip,
            lastUp = lastSnapshot?.up ?: 0L,
            lastSampleMs = lastSampleMs,
            lastMaxSpeed = 0.0,
            tripPath = trip.path,
            tripStartMs = trip.startMs,
            sealed = false,
        )).copy(sealed = true)
        lastSealReason = reason
    }

    companion object {
        /** Guard against float noise when comparing the ESP's session peak. */
        private const val MAX_SPEED_EPS = 0.15

        /** See [liveTrack]: double the stored preview's 128, for the follow maps. */
        /** Positions a live route piece holds before it is examined for redundant ones. */
        private const val LIVE_TRACK_FIRST_THIN_AT = LiveTrack.FIRST_THIN_AT
    }
}

/**
 * Text form of a [SessionRecord] for preference storage: it is what lets a trip
 * be continued after the app was killed between drives. The ip is not stored in
 * the value — it is the storage key — so [decode] takes it as an argument.
 */
object SessionCodec {
    private const val V = "s1"

    fun encode(r: SessionRecord): String = listOf(
        V, r.lastUp, r.lastSampleMs, String.format(java.util.Locale.US, "%.2f", r.lastMaxSpeed),
        if (r.sealed) 1 else 0, r.tripStartMs, r.tripPath ?: "",
    ).joinToString(";")

    fun decode(ip: String, text: String): SessionRecord? {
        val p = text.split(";", limit = 7)
        if (p.size != 7 || p[0] != V) return null
        return try {
            SessionRecord(
                ip = ip,
                lastUp = p[1].toLong(),
                lastSampleMs = p[2].toLong(),
                lastMaxSpeed = p[3].toDouble(),
                sealed = p[4] == "1",
                tripStartMs = p[5].toLong(),
                tripPath = p[6].ifEmpty { null },
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * What one pass over a trip file yields: the headline numbers the list shows,
 * and the bounded route shape its card draws. They travel together because a
 * file is read once; asking for the summary and the preview separately would
 * parse every sample twice.
 */
data class TripScan(val summary: TripSummary?, val track: List<TrackPoint>)

/** Reading sealed (or still-open) trip files back into memory. */
object TripReader {
    fun samples(path: String): List<TripSample> {
        val out = ArrayList<TripSample>()
        forEachLine(path) { line ->
            if (!TripFormat.skippable(line)) {
                TripFormat.parseLine(line)?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * Summary and preview in one pass. A malformed or truncated last line is
     * skipped — a crash mid-write costs that one sample, not the trip.
     */
    fun scan(path: String): TripScan {
        val acc = TripAccumulator()
        val preview = TrackPreview()
        var any = false
        forEachLine(path) { line ->
            if (!TripFormat.skippable(line)) {
                TripFormat.parseLine(line)?.let {
                    acc.add(it)
                    any = true
                    val s = it.snapshot
                    if (s.hasPosition) preview.add(s.lat, s.lon)
                }
            }
        }
        return TripScan(if (any) acc.summary() else null, preview.points())
    }

    /** Summary of a file on disk; see [scan] for the one-pass rule. */
    fun summarize(path: String): TripSummary? = scan(path).summary

    private fun forEachLine(path: String, block: (String) -> Unit) {
        val f = File(path)
        if (!f.exists()) return
        try {
            f.forEachLine(action = block)
        } catch (e: Exception) {
            // Partial reads (storage revoked, file deleted mid-read) leave the
            // caller with whatever was parsed already.
        }
    }
}
