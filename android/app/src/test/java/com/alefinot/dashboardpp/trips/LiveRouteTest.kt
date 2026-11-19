package com.alefinot.dashboardpp.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things a live map is built out of: the route's shape, and the positions the arrow
 * reads.
 *
 * Both exist because the owner looked at the bench phone and described what was wrong with
 * them (2026-11-18): the blue line "loses quality very quickly" - it was being halved every
 * few hundred seconds of driving - and the arrow "should keep turning" through a pause, which
 * it could not while its heading was measured across a frozen route.
 */
class LiveRouteTest {

    // ─── LiveTrack: a piece keeps its shape, whatever it costs in points ───────────

    @Test
    fun aStraightRunCollapsesToWhatItActuallyNeeds() {
        val track = LiveTrack(firstThinAt = 8)
        // Twenty-one fixes about thirty metres apart along one straight line: every interior
        // position is a restatement of the line its neighbours already draw.
        for (i in 0..20) track.add(45.6, 9.2 + i * 0.0004)
        val drawn = track.singlePiece()
        // Thinning is lazy and geometric - a pass runs when a piece has doubled since the last
        // one - so this is "collapsed hard", not "collapsed to two". What is asserted about the
        // line is where it is, not how few points carry it.
        assertTrue("kept ${drawn.size} of 21 on a straight road", drawn.size <= 8)
        // The ends are the piece's landmarks: its START dot is placed once, and its last
        // point is where the map's tip is. Neither may be thinned away.
        assertEquals(TrackPoint(45.6, 9.2), drawn.first())
        assertEquals(TrackPoint(45.6, 9.2 + 20 * 0.0004), drawn.last())
    }

    @Test
    fun aCornerSurvivesEveryPass() {
        val track = LiveTrack(firstThinAt = 6)
        // East for eleven fixes, then north for ten. The legs may collapse; the corner may
        // not - a map that cuts it draws a road the car never drove.
        for (i in 0..10) track.add(45.6, 9.2 + i * 0.0004)
        for (i in 1..10) track.add(45.6 + i * 0.0004, 9.2 + 10 * 0.0004)
        val drawn = track.singlePiece()
        val corner = TrackPoint(45.6, 9.2 + 10 * 0.0004)
        assertTrue("corner thinned away: $drawn", drawn.any { it == corner })
        assertTrue("legs did not collapse: ${drawn.size}", drawn.size <= 10)
    }

    @Test
    fun aPositionOffTheLineIsKept() {
        val track = LiveTrack(firstThinAt = 3)
        // The middle fix of three sits some fifteen metres to the side of the other two:
        // that is fifteen metres of drive, and it is on the map.
        track.add(45.6, 9.2)
        track.add(45.6002, 9.2002)
        track.add(45.6004, 9.2)
        assertEquals(3, track.singlePiece().size)
    }

    @Test
    fun aLongPieceKeepsItsEndsAndItsNewestFix() {
        val track = LiveTrack(firstThinAt = 16)
        // Five thousand fixes of ordinary wandering - far past the point the old cap had
        // already halved this drive five times over.
        for (i in 0 until 5000) track.add(45.6 + i * 1e-5, 9.2 + (i % 7) * 2e-5)
        val drawn = track.singlePiece()
        assertTrue("grew without thinning: ${drawn.size}", drawn.size < 5000)
        assertEquals(TrackPoint(45.6, 9.2), drawn.first())
        // The tip of the drawn line is always the drive's newest position: the polyline and
        // the recorder's file agree about where the car is.
        assertEquals(TrackPoint(45.6 + 4999 * 1e-5, 9.2 + (4999 % 7) * 2e-5), drawn.last())
    }

    @Test
    fun aPieceIsSeparateFromTheOneBeforeIt() {
        val track = LiveTrack(firstThinAt = 4)
        for (i in 0 until 12) track.add(45.6 + i * 1e-5, 9.2)
        track.startSegment()
        for (i in 0 until 12) track.add(45.7 + i * 1e-5, 9.3)
        val pieces = track.snapshot()
        assertEquals(2, pieces.size)
        // The first piece keeps its own first position, and the pause in the middle moved
        // nothing: this is what lets a map place one START dot per piece and never touch it.
        assertEquals(TrackPoint(45.6, 9.2), pieces[0].first())
        assertEquals(TrackPoint(45.7, 9.3), pieces[1].first())
    }

    @Test
    fun aDeclinedPositionNeverEntersTheRoute() {
        val track = LiveTrack()
        track.add(0.0, 0.0) // the unit saying "no fix"
        track.add(45.6, 9.2)
        track.add(45.6, 9.2 + 1e-5)
        val drawn = track.singlePiece()
        assertEquals(2, drawn.size)
        assertEquals(TrackPoint(45.6, 9.2), drawn.first())
    }

    @Test
    fun aPieceHandsOutTheSameListUntilItChanges() {
        val track = LiveTrack(firstThinAt = 32)
        track.add(45.6, 9.2)
        track.add(45.6001, 9.2)
        val before = track.snapshot().single()
        // The outer list is rebuilt per call - it holds one item per piece - but a piece that
        // did not grow is handed out by reference, which is what lets a map skip redrawing it.
        assertSame(before, track.snapshot().single())
        track.add(45.6002, 9.2)
        assertNotSame(before, track.snapshot().single())
    }

    // ─── TrackQuality: the app's ROUTE QUALITY slider, in dials ───────────────────

    /**
     * The slider's two ends, pinned as numbers (maintainer, 2026-11-19: "make it go full
     * quality up to 10000km at max quality"). Full quality is the promise the whole
     * redundancy scheme was built for - a two-metre tolerance that never climbs - and its
     * budget has to outlive 10 000 km of driving, which at a real road's thinning rate is
     * a couple of hundred thousand held positions. The cheap end thins early on purpose.
     */
    @Test
    fun theQualitySliderHasItsTwoEnds() {
        val full = TrackQuality.fromQuality(100)
        assertEquals(2.0, full.baseToleranceM, 1e-9)
        assertEquals(
            "max quality that lets the tolerance climb is not full quality",
            2.0, full.maxToleranceM, 1e-9,
        )
        assertEquals(1_000_000, full.totalThinAt)
        val cheap = TrackQuality.fromQuality(0)
        assertEquals(4.0, cheap.baseToleranceM, 1e-9)
        assertEquals(64.0, cheap.maxToleranceM, 1e-9)
        assertEquals(30_000, cheap.totalThinAt)
        // The store may be handed nonsense and the slider may be dragged at its ends;
        // both clamp rather than produce a negative budget.
        assertEquals(TrackQuality.fromQuality(0), TrackQuality.fromQuality(-40))
        assertEquals(TrackQuality.fromQuality(100), TrackQuality.fromQuality(9999))
    }

    @Test
    fun moreQualityIsAlwaysMoreLine() {
        val coarse = TrackQuality.fromQuality(20)
        val fine = TrackQuality.fromQuality(80)
        assertTrue(coarse.totalThinAt < fine.totalThinAt)
        assertTrue(coarse.baseToleranceM > fine.baseToleranceM)
        assertTrue(coarse.maxToleranceM > fine.maxToleranceM)
    }

    /**
     * The promise the slider's top end makes, observed as behaviour: a shape no chord can
     * represent holds every single position at full quality - the tolerance is not allowed
     * to climb, so nothing is ever declared redundant - while the cheap end, handed the
     * same shape, widens its net and throws the wiggles away. This is the one test that
     * says "full quality" means something beyond a constant in a table.
     */
    @Test
    fun atFullQualityTheToleranceNeverClimbs() {
        // A tight zigzag: every fix about twenty metres to one side of the line its
        // neighbours would draw. Nothing here is restatement at two metres.
        fun zigzag(track: LiveTrack, n: Int) {
            for (i in 0 until n) {
                val swing = if (i % 2 == 0) 0.0002 else -0.0002
                track.add(45.6 + swing, 9.2 + i * 0.0002)
            }
        }
        val full = LiveTrack(firstThinAt = 6, toleranceM = 2.0, maxToleranceM = 2.0, totalThinAt = 12)
        zigzag(full, 90)
        assertEquals(
            "full quality dropped points a chord does not cover",
            90, full.singlePiece().size,
        )
        val cheap = LiveTrack(firstThinAt = 6, toleranceM = 4.0, maxToleranceM = 64.0, totalThinAt = 12)
        zigzag(cheap, 90)
        assertTrue(
            "the cheap setting never widened its net - the budget and ceiling are decoration",
            cheap.singlePiece().size < 90,
        )
    }

    // ─── CursorTrail: the minute of movement the arrow's heading is read from ──────

    @Test
    fun theTrailKeepsAMinuteAndDropsTheRest() {
        val trail = CursorTrail(maxPoints = 8)
        for (i in 1..20) trail.add(45.6 + i * 1e-5, 9.2)
        val heard = trail.snapshot()
        assertEquals(8, heard.size)
        assertEquals(TrackPoint(45.6 + 20 * 1e-5, 9.2), heard.last())
        // The window slides: the minute is the newest minute, so the oldest position left is
        // the 13th of the 20, not the first.
        assertEquals(45.6 + 13 * 1e-5, heard.first().lat, 1e-9)
        assertEquals(45.6 + 20 * 1e-5, trail.last()!!.lat, 1e-9)
    }

    @Test
    fun aDeclinedPositionNeverEntersTheTrail() {
        val trail = CursorTrail()
        trail.add(0.0, 0.0)
        trail.add(Double.NaN, 9.2)
        trail.add(91.0, 9.2)
        assertNull(trail.last())
        assertTrue(trail.snapshot().isEmpty())
        trail.add(45.6, 9.2)
        assertEquals(1, trail.snapshot().size)
    }

    @Test
    fun theTrailHandsOutTheSameListUntilItGrows() {
        val trail = CursorTrail(maxPoints = 16)
        trail.add(45.6, 9.2)
        val first = trail.snapshot()
        assertSame(first, trail.snapshot())
        trail.add(45.6001, 9.2)
        assertNotSame(first, trail.snapshot())
        trail.clear()
        assertNull(trail.last())
        assertTrue(trail.snapshot().isEmpty())
    }

    // ─── routeToShow: a finished drive's shape leaves only when the next one can be drawn ───

    @Test
    fun aDriveWithNothingDrawnYetNeverErasesTheDriveBeforeIt() {
        // The press that begins a drive used to clear the map on its way in, and the new drive
        // has no position for a whole sample period - so the page went blank for a second
        // (owner, 2026-11-18: "the live map disappears for a moment").
        val finished = LiveRoute(
            driveId = 1_000L,
            segments = listOf(listOf(TrackPoint(45.6, 9.2), TrackPoint(45.6001, 9.2002))),
            endAt = TrackPoint(45.6001, 9.2002),
        )
        val started = LiveRoute(driveId = 2_000L)
        assertSame(finished, routeToShow(current = finished, incoming = started))
    }

    @Test
    fun theNewDriveTakesTheMapTheMomentItHasItsFirstPosition() {
        // Keeping the old shape must not mean keeping it for the drive: one recorded point is a
        // shape, and that frame is where the handover happens.
        val finished = LiveRoute(
            driveId = 1_000L,
            segments = listOf(listOf(TrackPoint(45.6, 9.2))),
            endAt = TrackPoint(45.6, 9.2),
        )
        val driving = LiveRoute(driveId = 2_000L, segments = listOf(listOf(TrackPoint(45.7, 9.3))))
        assertEquals(driving, routeToShow(current = finished, incoming = driving))
    }

    @Test
    fun aDriveStillBeingWrittenIsNeverHeldBackByItself() {
        // The rule is keyed on `driveId` so it cannot strand a route: the same drive publishing
        // an empty track - it cannot happen today, and this is the day it does - still reaches
        // the map.
        val open = LiveRoute(driveId = 3_000L, segments = listOf(listOf(TrackPoint(45.6, 9.2))))
        assertEquals(LiveRoute(driveId = 3_000L), routeToShow(current = open, incoming = LiveRoute(driveId = 3_000L)))
        // And with nothing drawn at all, an empty incoming route is simply the truth.
        assertEquals(LiveRoute(driveId = 4_000L), routeToShow(current = LiveRoute(), incoming = LiveRoute(driveId = 4_000L)))
    }

    /** One piece, asserted to be one piece: almost every case here builds exactly one. */
    private fun LiveTrack.singlePiece(): List<TrackPoint> {
        val pieces = snapshot()
        assertEquals(1, pieces.size)
        return pieces.single()
    }
}
