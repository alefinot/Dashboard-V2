package com.alefinot.dashboardpp.trips

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The route a live map draws, including the pieces a pause leaves behind.
 *
 * The old shape was one flat [TrackPreview] list, which was honest only while a drive was
 * written in one stretch: a pause freezes the recorded route, a resume starts writing again a
 * kilometre later, and a single polyline would draw a straight line across the ground the
 * owner explicitly asked not to record. So the route is a list of **segments**, one per
 * recording stretch, and the maps draw each piece separately - no line across a pause.
 *
 * [pauses] is every pause this drive left behind, oldest first, and they are **kept**: the
 * orange dot is the mark of a decision the owner made at a place, and a resume that erased
 * the previous one threw away half of the drive's story (owner, 2026-11-18). A drive that
 * paused three times wears three dots and starts a new piece beside each of them.
 *
 * [endAt] is where a closed drive ended - the red dot. A drive closed while paused is the one
 * case where the newest pause dot is not in [pauses]: the pause command has been overtaken by
 * the drive being closed, and its position is now the END dot rather than a PAUSE one. The
 * older dots stay.
 *
 * The route carries no live position on purpose. The arrow is not part of the drive's shape -
 * it is where the vehicle is *now*, which is true after a stop and during a pause alike, and a
 * recorder that stops should not take the car off the map with it. That position lives in
 * [CursorTrail], next to the frame lane that reads it, and reaches the map through
 * [TripRuntimeState.liveTrail].
 *
 * [driveId] is the open trip's start instant. A map resets its overlays and reframes exactly
 * once per value, so a new drive is a new picture even when the first fix lands a metre from
 * the old drive's last one.
 */
data class LiveRoute(
    val driveId: Long = 0L,
    /** One recorded stretch per item, oldest first; a resume appends a new one. */
    val segments: List<List<TrackPoint>> = emptyList(),
    /** Where each pause of this drive froze the recorded line, oldest first. */
    val pauses: List<TrackPoint> = emptyList(),
    /** Last recorded position of a closed drive - the red dot. */
    val endAt: TrackPoint? = null,
) {
    /** True when at least one position was recorded; the maps exist only for a real shape. */
    val hasTrack: Boolean get() = segments.any { it.isNotEmpty() }

    /** The closed drive's route, as opposed to one still being written or paused. */
    val closed: Boolean get() = endAt != null

    /** Every recorded position in order, across the pieces. For readers, not for drawing. */
    val recordedPoints: List<TrackPoint> get() = segments.flatten()
}

/**
 * Which route the live maps should draw now that [incoming] came from the recorder.
 *
 * A finished drive's shape leaves the maps only when the drive that replaces it has a position
 * of its own. It used to leave at the moment of the press, because `START` cleared the picture
 * on its way in — and since the new drive has nothing recorded until its first fix lands, the
 * page spent that whole sample period showing no map at all ("when the recording is stopped and
 * you press to start it the live map disappears for a moment", owner, 2026-11-18). Handing the
 * change over on the incoming route's first point makes it atomic: the old shape is drawn on
 * every frame up to the new drive's first, and the new drive's `driveId` is what tells the map
 * to drop its overlays and reframe on that same frame.
 *
 * The rule is written on `driveId`, not on emptiness alone, so a same-drive publish that
 * happens to be empty — it cannot happen today, and a rule that trusts that is the one that
 * strands an old route when it does — still reaches the map.
 */
fun routeToShow(current: LiveRoute, incoming: LiveRoute): LiveRoute =
    if (!incoming.hasTrack && current.hasTrack && incoming.driveId != current.driveId) current else incoming

/**
 * The newest positions the phone has heard, recorded or not, oldest first.
 *
 * This is the arrow's whole life: the position it sits on, and the few hundred metres of
 * movement its heading is measured across. It used to be measured across the *recorded*
 * route, which is a good compass only while the recorder is writing - freeze the file with
 * PAUSE and the route stops moving while the car does not, so the head is left pointing back
 * at the pause dot instead of down the road the owner is still driving (owner, 2026-11-18).
 * The same reason it must not belong to the recorder either: after a stop there is no
 * recorder, and the map is supposed to go on living while the app is open.
 *
 * It is a sliding window, not a decimated preview: [MAX_POINTS] positions at the unit's
 * 1 Hz are about a minute of driving, which is far more than the six metres a heading needs
 * and far less than a screen can show. Dropping the oldest keeps that true for a drive of
 * any length, including the 12 h one the recorder caps a file at.
 */
class CursorTrail(private val maxPoints: Int = MAX_POINTS) {
    private val points = ArrayList<TrackPoint>(MAX_POINTS)

    /** The immutable view of [points], dropped whenever a position is added. */
    private var view: List<TrackPoint>? = null

    /** Record one position the stream reported. Positions the unit declines are ignored. */
    fun add(lat: Double, lon: Double) {
        if (!isRealPosition(lat, lon)) return
        if (points.size >= maxPoints) points.removeAt(0)
        points.add(TrackPoint(lat, lon))
        view = null
    }

    /** The newest position, or null while nothing has been heard. */
    fun last(): TrackPoint? = points.lastOrNull()

    fun clear() {
        points.clear()
        view = null
    }

    /** A list the callers can hold on to and compare by reference; rebuilt only when it grew. */
    fun snapshot(): List<TrackPoint> {
        val cached = view
        if (cached != null) return cached
        val made = points.toList()
        view = made
        return made
    }

    companion object {
        /** About a minute of frames - more than a heading needs, less than a screen shows. */
        const val MAX_POINTS = 64
    }
}

/**
 * Is this a place on Earth? A unit without a fix reports `0,0`, which is a decline and not a
 * point in the Gulf of Guinea, and a poisoned line in a re-opened file must not put a dot in
 * the ocean or an arrow a hemisphere away.
 */
private fun isRealPosition(lat: Double, lon: Double): Boolean {
    if (!lat.isFinite() || !lon.isFinite()) return false
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
    return !(lat == 0.0 && lon == 0.0)
}

/**
 * The live route's builder: bounded, segmented, and cheap to hand out.
 *
 * It used to thin like [TrackPreview] thins a sealed file's preview - at the cap, drop every
 * second point - and that was the right rule for a 72 dp thumbnail and the wrong rule for a
 * map a person drives by. Halving a running drive cuts the drawing down at exactly the moment
 * it gets interesting: on the bench phone a piece reached its cap in a little over four
 * minutes, so a commute's line visibly degenerated into a few long chords while the car was
 * still on it (owner, 2026-11-18: "the blue line should remain full quality for at least
 * 1000 km").
 *
 * So the cap no longer decides *how many* points survive; a point is dropped only when it is
 * provably redundant - within [TOLERANCE_M] of the straight line joining the kept point before
 * it and the next raw position after it. A straight road collapses hard, a corner never does,
 * and the drawn line never differs from the drive that happened by more than a couple of
 * metres, which is less than the GPS noise underneath it. That holds for a whole 12 h file:
 * 1000 km at a motorway's 100 km/h is 36 000 fixes, every one of them kept unless it lies on
 * the line its neighbours already say.
 *
 * Passing one point at a time would be O(n) per fix, so a piece grows in jumps: it is left
 * alone until [firstThinAt] points, then re-examined when it has doubled, and the tolerance
 * climbs only for a piece that keeps every point even at [MAX_TOLERANCE_M] - the pathological
 * shape that no chord can represent. Total memory is bounded by [TOTAL_THIN_AT] positions
 * across a whole drive.
 *
 * A piece's first position is never dropped, which is what lets a map place its START dot once
 * and leave it. The snapshot handed to [LiveTrack]'s owner caches each segment's list until that
 * segment changes, so a map can compare references and skip a redraw of a piece that did not
 * move. Only the outer list is rebuilt per call; the pieces themselves are shared.
 *
 * The three dials below a piece's length - [toleranceM] where redundancy starts, [maxToleranceM]
 * where it stops climbing, and [totalThinAt] for the whole drive - are constructor parameters
 * rather than constants because of the app's ROUTE QUALITY slider (maintainer, 2026-11-19:
 * "add a quality slider for the route, make it go full quality up to 10000km at max quality").
 * [TrackQuality.fromQuality] turns the slider into their values; the defaults here are today's
 * constants, so anything built without a quality setting behaves exactly as it did before it.
 */
class LiveTrack(
    private val firstThinAt: Int = FIRST_THIN_AT,
    private val toleranceM: Double = TOLERANCE_M,
    private val maxToleranceM: Double = MAX_TOLERANCE_M,
    private val totalThinAt: Int = TOTAL_THIN_AT,
) {

    private class Segment {
        val points = ArrayList<TrackPoint>(1024)

        /** The immutable view of [points], dropped whenever the piece changes. */
        var view: List<TrackPoint>? = null

        /** Point count that triggers the next redundancy pass over this piece. */
        var nextThinAt = 0

        /** Redundancy width this piece is examined at; climbs only when a pass frees nothing. */
        var toleranceM = TOLERANCE_M
    }

    private val segments = ArrayList<Segment>()

    /** Positions held across all pieces; what [TOTAL_THIN_AT] is compared against. */
    private var total = 0

    /** Begin another recorded stretch; the first point of it gets its own START dot. */
    fun startSegment() {
        segments.add(
            Segment().apply {
                nextThinAt = firstThinAt
                toleranceM = this@LiveTrack.toleranceM
            },
        )
    }

    fun add(lat: Double, lon: Double) {
        if (!isRealPosition(lat, lon)) return
        if (segments.isEmpty()) startSegment()
        val seg = segments.last()
        if (seg.points.size >= seg.nextThinAt) {
            thin(seg)
            // Grow geometrically: passes cost O(n), and doubling the interval between them
            // keeps the whole drive's thinning work proportional to its length.
            seg.nextThinAt = seg.points.size + seg.points.size.coerceAtLeast(firstThinAt)
        }
        seg.points.add(TrackPoint(lat, lon))
        seg.view = null
        total++
        if (total > totalThinAt) {
            // The drive as a whole has outgrown its budget - many short pieces, most likely.
            // Examine every piece; each one's tolerance climbs if examining it frees nothing,
            // so this cannot go on firing without making progress.
            for (s in segments) thin(s)
            total = segments.sumOf { it.points.size }
        }
    }

    /** Newest recorded position, or null while no position has landed. */
    fun lastRecorded(): TrackPoint? {
        for (i in segments.indices.reversed()) {
            val points = segments[i].points
            if (points.isNotEmpty()) return points.last()
        }
        return null
    }

    fun snapshot(): List<List<TrackPoint>> {
        val out = ArrayList<List<TrackPoint>>(segments.size)
        for (seg in segments) {
            val cached = seg.view
            if (cached != null) {
                out.add(cached)
            } else {
                val view = seg.points.toList()
                seg.view = view
                out.add(view)
            }
        }
        return out
    }

    /** Halve nothing: drop the points a straight line between their neighbours already covers. */
    private fun thin(seg: Segment) {
        val points = seg.points
        val n = points.size
        if (n < 3) return
        val tol = seg.toleranceM
        // Index of the last kept position. points[0] is kept always - a piece's START dot is
        // placed on it once - and every write below lands at or behind the read cursor, so the
        // pass compacts in place.
        var kept = 0
        var i = 1
        while (i < n - 1) {
            if (deviationM(points[kept], points[i], points[i + 1]) > tol) {
                kept++
                points[kept] = points[i]
            }
            i++
        }
        kept++
        points[kept] = points[n - 1] // the newest position is always the piece's last point
        var size = points.size
        while (size > kept + 1) {
            points.removeAt(size - 1)
            size--
        }
        seg.view = null
        if (kept + 1 >= n && seg.toleranceM < maxToleranceM) {
            // Nothing was redundant at this width. Widen the net for the next pass instead of
            // letting the piece grow without bound.
            seg.toleranceM *= 2
        }
    }

    companion object {
        /**
         * A piece of about this many positions - seventy minutes at the unit's 1 Hz - is kept
         * fix for fix with no examination at all: the horizon a person actually looks at.
         */
        const val FIRST_THIN_AT = 4_096

        /**
         * A position is redundant when it lies no further than this from the line joining the
         * kept point before it and the next raw position after it. Two metres is under the GPS
         * noise the fixes carry, so the drawn line stays as true as the drive it came from.
         */
        const val TOLERANCE_M = 2.0

        /** Ceiling for a pathological shape; reached only by doubling from [TOLERANCE_M]. */
        const val MAX_TOLERANCE_M = 64.0

        /** Positions a whole drive may hold before every piece is examined again. */
        const val TOTAL_THIN_AT = 60_000
    }
}

/**
 * The [LiveTrack] dials derived from the app's ROUTE QUALITY slider (App Settings →
 * Trips & Storage; maintainer, 2026-11-19: "add a quality slider for the route, make it go
 * full quality up to 10000km at max quality").
 *
 * The slider only ever decides *how much of the drive's line the live map keeps*. What is
 * written to the trip file is untouched - the CSV carries every fix whatever the slider
 * says - and a sealed drive's detail page draws from that file, not from here.
 *
 * At the top end the tolerance never climbs at all: the drawn line stays within
 * [LiveTrack.TOLERANCE_M] - two metres, under the GPS noise - for the whole drive. A real
 * road thinned at that width holds on the order of 10-25 positions per km, so a 10 000 km
 * drive is a couple of hundred thousand held positions: inside [MAX_TOTAL_THIN_AT] with
 * room to spare. That budget is the honest worst-case ceiling - positions a many-day drive
 * may sit in RAM before the tolerance is allowed to climb to save itself.
 *
 * At the bottom end the net starts coarser (four metres) and may climb to the old
 * [LiveTrack.MAX_TOLERANCE_M] once a mere 30 000 positions are held: a smaller line for a
 * phone that would rather spend its RAM on the map tiles. Between the anchors every dial
 * moves geometrically, so each press of the slider buys the same *factor*, not the same
 * number of metres nobody can feel.
 */
data class TrackQuality(
    /** Where redundancy starts, in metres. */
    val baseToleranceM: Double,
    /** The ceiling the tolerance may climb to; equal to the base at full quality. */
    val maxToleranceM: Double,
    /** Positions a whole drive may hold before every piece is examined again. */
    val totalThinAt: Int,
) {
    companion object {
        /** The slider's own range, as the app stores it. */
        const val MIN = 0
        const val MAX = 100

        /** The full-quality budget: see the class doc for why this covers 10 000 km. */
        const val MAX_TOTAL_THIN_AT = 1_000_000

        /** The cheapest setting's budget, in positions. */
        const val MIN_TOTAL_THIN_AT = 30_000

        /** Turn one slider press (0..100, clamped) into the three [LiveTrack] dials. */
        fun fromQuality(quality: Int): TrackQuality {
            val q = quality.coerceIn(MIN, MAX) / 100.0
            val down = 1.0 - q
            return TrackQuality(
                // 2 m at full quality, 4 m at the cheapest: the slider cannot make the line
                // *coarser than fine*, it can only decide how soon it starts saving RAM.
                baseToleranceM = 2.0 * 2.0.pow(down),
                // 2 m (never climbs) at full quality, up to the old 64 m ceiling at the
                // cheapest. Four doublings' worth of room is the whole span.
                maxToleranceM = 2.0 * 2.0.pow(5.0 * down),
                // Geometric between the two budgets, so half the slider is the square root
                // of the span, not the midpoint nobody would guess.
                totalThinAt = (
                    MIN_TOTAL_THIN_AT *
                        (MAX_TOTAL_THIN_AT.toDouble() / MIN_TOTAL_THIN_AT).pow(q)
                    ).roundToInt(),
            )
        }
    }
}

/** Metres per degree of latitude; the flat-plane scale the redundancy test runs on. */
private const val METRES_PER_DEG_LAT = 111_320.0

/**
 * How far [b] sits off the straight line from [a] to [c], in metres.
 *
 * Equirectangular, not geodesic: the three points are a few tens of metres apart, so a flat
 * plane is accurate to well under the tolerance being tested, and the whole pass stays
 * arithmetic instead of trigonometry per point (only the cosine of one latitude is needed).
 * Distances to the *segment*, not the infinite line: a piece that doubles back is not
 * redundant just because it is near a line's extension.
 */
private fun deviationM(a: TrackPoint, b: TrackPoint, c: TrackPoint): Double {
    val ky = METRES_PER_DEG_LAT
    val kx = ky * cos(Math.toRadians(b.lat))
    val ax = a.lon * kx
    val ay = a.lat * ky
    val bx = b.lon * kx - ax
    val by = b.lat * ky - ay
    val vx = c.lon * kx - ax
    val vy = c.lat * ky - ay
    val len2 = vx * vx + vy * vy
    if (len2 < 1e-9) return hypot(bx, by)
    val t = (bx * vx + by * vy) / len2
    if (t <= 0.0) return hypot(bx, by)
    if (t >= 1.0) return hypot(bx - vx, by - vy)
    return hypot(bx - vx * t, by - vy * t)
}

