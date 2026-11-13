package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.circle
import com.alefinot.dashboardpp.ui.components.contourPoints
import com.alefinot.dashboardpp.ui.components.signedArea
import com.alefinot.dashboardpp.ui.components.subpaths
import com.alefinot.dashboardpp.ui.components.vertices
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar and the pager, as data instead of as a row of composables.
 *
 * The order of the bar used to be written twice: once as `BarSlots` for the row of
 * icons and once as `BarPages` for the swipe order, with a comment explaining why they
 * differed (the Coming Soon slot advertised a screen that did not exist, so it had to
 * stay out of the pager). Two lists and a carve-out is how a hole in the middle of the
 * app becomes invisible: the centre of the bar was a gauge, and the page it sat on was
 * four slots away.
 *
 * One list now, and these tests are what keep it that way â€” that the centre slot is the
 * vehicle page, that the app opens on it, and that every slot is a page.
 */
class BarLayoutTest {

    @Test
    fun `the bar and the swipe order are one list, and nothing is missing from it`() {
        assertEquals(
            listOf(AppTab.LIVE, AppTab.TRIPS, AppTab.VEHICLES, AppTab.WEB, AppTab.SETTINGS),
            BarLayout.pages,
        )
        // Five slots, five pages. The old bar had a slot with no page behind it; a slot
        // you can tap that goes nowhere is a dead end wearing an icon.
        assertEquals(BarLayout.pages.size, AppTab.values().size)
        for (tab in AppTab.values()) {
            assertTrue("$tab", BarLayout.pages.contains(tab))
        }
    }

    @Test
    fun `the vehicle page is the centre slot and the page the app opens on`() {
        // Five slots means index 2 is the middle, and the middle of this bar has always
        // been the thing the app is mostly in. That argument now belongs to the garage:
        // it is where a connection is made, and every other page needs it.
        assertEquals(AppTab.VEHICLES, BarLayout.centre)
        assertEquals(2, BarLayout.pages.indexOf(BarLayout.centre))
        assertEquals(BarLayout.centre, BarLayout.home)
        assertEquals(2, BarLayout.homePage)
    }

    @Test
    fun `the vehicle glyph takes the centre treatment and nothing else does`() {
        val car = BarLayout.glyph(AppTab.VEHICLES)
        assertEquals(1.4f, car.weight, 0f)
        // The size is one of the set's three steps, not a number the bar invented.
        assertEquals(HudGlyphSize.Card, car.size)
        assertEquals(30.dp, car.size.dp)
        assertEquals(52.dp, car.box)
        assertTrue(car.colored)
        for (tab in BarLayout.pages.filter { it != AppTab.VEHICLES }) {
            val g = BarLayout.glyph(tab)
            assertEquals("$tab weight", 1f, g.weight, 0f)
            assertEquals("$tab size", HudGlyphSize.Action, g.size)
            assertEquals("$tab size", 22.dp, g.size.dp)
            assertEquals("$tab box", 48.dp, g.box)
            // `colored` means "paint the artwork as drawn and lose alpha instead", which
            // was the gauge's privilege while it sat in the centre. Tinted like its
            // neighbours is what lets a dimmed ESP slot read as unusable next to it.
            assertFalse("$tab colored", g.colored)
        }
    }

    @Test
    fun `every slot keeps its name as the accessibility description`() {
        // The bar replaced words with icons, so the words have to survive somewhere a
        // screen reader can reach, and a touch target that reads as "unlabelled" is a
        // regression nobody would notice until it was reported.
        for (tab in AppTab.values()) {
            assertTrue("$tab", BarLayout.glyph(tab).desc.isNotBlank())
        }
    }

    @Test
    fun `the vehicle mark is drawn on the same grid as the others`() {
        // Same 24-unit viewport, same language of filled bodies: a borrowed icon from another set
        // would be a second visual language in a bar that already has one.
        assertEquals(24f, HudIcons.SportWheel.viewportWidth, 0f)
        assertEquals(24f, HudIcons.SportWheel.viewportHeight, 0f)
    }

    /**
     * The wheel the bar and the cards draw, after OZ's Ultraleggera: a thin low-profile tyre, a
     * wide rim, and **six double spokes** that run out to the lip with the centre carved open. Two
     * openings per pitch is the entire design — the daylight between two pairs, and a wedge inside
     * each pair that starts out at the hub so the two branches keep one shared root and fork only
     * as they reach the lip. Every one of the twelve is a contour wound against the face, so what
     * shows through the spokes is the bar's glass sheet or the card's empty panel, never paint.
     *
     * The floors are stated in pixels at the bar's 30 dp (one unit ~3.4 px on the bench phone),
     * the size the mark is seen at most and the size a photo-less card cover draws it at; the
     * editor's well is 38 dp, and the `OPEN VEHICLE PAGE` button's 18 dp is deliberately not
     * floored here because a wheel's twelve openings are not what that size can resolve.
     */
    @Test
    fun `the sport wheel is a thin tyre and six double spokes cut out of the rim`() {
        val subs = subpaths(HudIcons.SportWheelData)
        assertEquals("tyre outer, tyre inner, the rim face, six openings, six splits, the bore",
            16, subs.size)

        val rings = listOf(circle(subs[0]), circle(subs[1]), circle(subs[2]), circle(subs[15]))
        for ((i, ring) in rings.withIndex()) {
            assertEquals("ring $i off-centre in x", 12f, ring.centre.x, 0.01f)
            assertEquals("ring $i off-centre in y", 12f, ring.centre.y, 0.01f)
        }
        val (tyre, tyreIn, lip, bore) = rings
        assertTrue("the tyre's outer edge does not wind as ink ($tyre)", tyre.sign > 0.0)
        assertTrue("the tyre's inner edge winds with it, so the tyre fills solid",
            tyreIn.sign * tyre.sign < 0.0)
        assertTrue("the rim face does not wind as ink, so the middle of the wheel is a hole",
            lip.sign * tyre.sign > 0.0)
        assertTrue("the centre bore winds with the face, so the hub has no bore",
            bore.sign * lip.sign < 0.0)

        // Twelve openings. A contour that winds *with* the face is filled by NonZero, and the
        // wheel is a disc again — the one thing this glyph exists not to be.
        val openings = subs.subList(3, 9).map { contourPoints(it) }
        val splits = subs.subList(9, 15).map { contourPoints(it) }
        for ((i, pts) in openings.withIndex()) {
            assertEquals("opening $i is an arc-chord-arc-chord band", 4, pts.size)
            assertTrue("opening $i winds with the face, so the wheel has no spokes",
                signedArea(pts) < 0.0)
        }
        for ((i, pts) in splits.withIndex()) {
            assertEquals("split $i is a wedge of daylight with its apex inboard", 3, pts.size)
            assertTrue("split $i winds with the face, so the pair is one spoke, not a double",
                signedArea(pts) < 0.0)
        }

        // Where the carving stops is what keeps the wheel a wheel: a continuous lip at the edge,
        // a solid hub at the centre, and a wall of hub left around the bore.
        val lipIn = openings.maxOf { pts -> pts.maxOf { rad(it) } }
        val hub = openings.minOf { pts -> pts.minOf { rad(it) } }
        assertTrue("the openings reach r=$lipIn on a face of r=${lip.radius}: the lip is cut " +
            "through, and a wheel with a broken rim lip is a snowflake",
            lipIn <= lip.radius - 0.05f)
        assertTrue("the openings cut in to r=$hub, so there is no hub left", hub >= 2.0f)
        assertTrue("a r$hub hub bored to r${bore.radius} has no wall at 30 dp",
            hub - bore.radius >= 1.2f)
        val root = splits.minOf { pts -> pts.minOf { rad(it) } }
        assertTrue("the split starts at r=$root, inboard of the hub's edge (r$hub): the pair " +
            "never shares a root, and six double spokes become twelve plain ones",
            root > hub)

        val px = 82f / 24f   // the card cover's 30 dp on the bench phone
        val tyreWidth = (tyre.radius - tyreIn.radius) * px
        val groove = (tyreIn.radius - lip.radius) * px
        val lipRing = (lip.radius - lipIn) * px
        assertTrue("a ${tyreWidth}px tyre is not a low-profile sidewall — it is the old wheel",
            tyreWidth >= 4.5f)
        assertTrue("${groove}px of daylight between tyre and rim closes up at 30 dp: that band " +
            "is what says 'wheel' rather than 'solid disc'", groove >= 2.2f)
        assertTrue("${lipRing}px of rim lip closes up at 30 dp", lipRing >= 2.2f)

        // Six-fold, and the split runs up the middle of a pair rather than through the daylight
        // beside it, which is the difference between a double spoke and a broken one.
        val openCentres = openings.map { centre(it) }
        val splitCentres = splits.map { centre(it) }
        assertTrue("the six openings are not six-fold apart", closestToEach(openCentres) >= 55.0)
        assertTrue("the six splits are not six-fold apart", closestToEach(splitCentres) >= 55.0)
        val offset = openCentres.flatMap { a -> splitCentres.map { b -> apart(a, b) } }
            .minOrNull() ?: error("the mark has no openings")
        assertTrue("the split runs $offset degrees off the middle of its pair",
            offset in 25.0..35.0)

        // The two branches of a pair, measured where they are thinnest-in-practice — at the lip.
        // 60 degrees of pitch, the daylight's own span, and the split's span leave the rest: that
        // is what the eye has to resolve, and it is why this is the tight number in the mark.
        val narrowest = openings.zip(splits).map { (open, split) ->
            val branchDeg = (60.0 - span(open) - span(split)) / 2.0
            2.0 * lipIn * sin(Math.toRadians(branchDeg / 2.0))
        }.minOrNull()!!
        assertTrue("${"%.1f".format(narrowest * px)}px of branch at 30 dp: below this the pair's " +
            "two spokes fuse into one and the wheel stops being an Ultraleggera",
            narrowest * px >= 2.6f)
        val window = 2.0 * lipIn * sin(Math.toRadians(span(splits.first()) / 2.0))
        assertTrue("${window * px}px of daylight between a pair's branches at the lip",
            window * px >= 2.2f)
    }

    /**
     * A cog is legible when its teeth stand clear of the root circle with daylight between
     * them, so that is what is measured - not the picture, but the geometry that makes it.
     * Eight trapezoids, 2.9 units of tooth height on a 6.9-unit root: the gap at the root is
     * about the width of a stroke on the neighbouring glyphs, which is what lets the eye count
     * teeth instead of lumps.
     */
    @Test
    fun `the settings glyph is a cog with teeth, not a ring with spokes`() {
        val cog = subpaths(HudIcons.CogOutline)
        assertEquals("the gear body, and the axis hole", 2, cog.size)

        val teeth = vertices(cog.first())
        assertEquals("eight teeth, four corners each", 32, teeth.size)
        val reach = teeth.map { hypot(it.x - 12f, it.y - 12f) }
        val tip = reach.maxOrNull()!!
        val root = reach.minOrNull()!!
        assertTrue("teeth tips reach only $tip from the centre: at the bar's 22 dp a gear with " +
            "no radial bite is a blob", tip >= 9.7f)
        assertTrue("teeth are $tip-$root = ${tip - root} units tall; under ~2.5 the teeth and " +
            "the ring wall fuse, which is the drawing the maintainer rejected",
            tip - root >= 2.5f)
        assertEquals("two tips per tooth", 16, reach.count { it > root + 1f })

        val body = signedArea(teeth)
        val bore = circle(cog[1])
        assertTrue(
            "the axis hole winds with the body (${bore.sign}), so it fills and the cog loses " +
                "the one feature that says 'cog' rather than 'nut'",
            bore.sign * body < 0.0,
        )
    }

    /**
     * The link mark a vehicle card carries in its lower corner (see [HudIcons.LinkOn]): two
     * interlocked rings say "this phone holds a session", one ring and a broken join say otherwise.
     * Like every glyph here a hole is a contour wound against the body, never a lighter fill painted
     * over it, so the card shows through the ring's eye.
     */
    @Test
    fun `the link glyph is rings that wind to open`() {
        val linked = subpaths(HudIcons.LinkOnData)
        assertEquals("two rings, each an outer disc and a hole", 4, linked.size)
        val on = linked.map(::circle)
        // Order is outer, hole, outer, hole.
        for (i in listOf(0, 2)) {
            assertEquals("ring $i off-centre in y", 12f, on[i].centre.y, 0.01f)
            assertTrue("ring $i outer does not wind as ink", on[i].sign > 0.0)
            assertTrue(
                "the hole in ring $i winds with its outer, so the link fills solid",
                on[i].sign * on[i + 1].sign < 0.0,
            )
            assertTrue(
                "a ${on[i].radius - on[i + 1].radius}-unit link wall closes up at the corner's 16 dp",
                on[i].radius - on[i + 1].radius >= 2f,
            )
        }
        // They must overlap to read as one chain, yet stay two rings rather than one blob.
        val gap = on[2].centre.x - on[0].centre.x
        assertTrue("the rings sit $gap apart and never touch â€” two discs, not a link", gap < 2f * on[0].radius)
        assertTrue("one ring reaches past the other's centre; it is a blob, not two links", gap > on[0].radius)

        val unlinked = subpaths(HudIcons.LinkOffData)
        assertEquals("one ring: an outer disc and its hole", 2, unlinked.size)
        val off = unlinked.map(::circle)
        assertTrue("the unlinked ring winds with its hole, so it is solid", off[0].sign * off[1].sign < 0.0)
        // The break stands where the far link used to be: two short strokes.
        assertEquals("the broken join is two marks", 2, Regex("M").findAll(HudIcons.LinkOffBreak).count())
    }

    /**
     * The EDIT pencil (see [HudIcons.Edit]) is a solid silhouette. It carries no interior hole on
     * purpose â€” at the card corner's ~18 dp a ferrule band and a lead dot are the mush the glyph set
     * exists to avoid â€” so what is pinned is that it is one closed body, on the shared 24 grid,
     * pointing to the lower-left.
     */
    @Test
    fun `the edit glyph is one solid pencil on the shared grid`() {
        assertEquals(24f, HudIcons.Edit.viewportWidth, 0f)
        assertEquals(24f, HudIcons.Edit.viewportHeight, 0f)
        val body = subpaths(HudIcons.EditData)
        assertEquals("a pencil is one closed silhouette", 1, body.size)
        val pts = vertices(body.first())
        assertEquals("flat eraser end, two barrel sides, a pointed tip", 5, pts.size)
        assertTrue("the mark runs off the 24-unit grid", pts.all { it.x in 0f..24f && it.y in 0f..24f })
        // The tip is the leftmost vertex, and it sits low: the pencil points to the lower-left.
        val tip = pts.minByOrNull { it.x }!!
        assertTrue("the pencil does not point to the lower-left ($tip)", tip.x < 6f && tip.y > 18f)
    }

    // ── reading the wheel's arcs ─────────────────────────────────────────────────────
    // The wheel's twelve openings are arcs, not chords, so `vertices` cannot see them and the
    // interesting numbers are angles and radii rather than coordinates. The old mark needed none
    // of this: its spokes were four straight chords each.

    /** How far a point sits from the grid's centre. */
    private fun rad(p: Offset): Float = hypot(p.x - 12f, p.y - 12f)

    /** Which way round the grid's centre a point is, in degrees, y growing down. */
    private fun deg(p: Offset): Double =
        Math.toDegrees(Math.atan2((p.y - 12f).toDouble(), (p.x - 12f).toDouble()))

    /**
     * Which way round the centre a whole contour sits. The vector mean, not the arithmetic one:
     * the pair of spokes that straddles the ±180° line has points at 176.5° and -176.5°, and
     * averaging those reads as if it sat at 0° — on the opposite side of the wheel.
     */
    private fun centre(pts: List<Offset>): Double {
        val x = pts.sumOf { Math.cos(Math.toRadians(deg(it))) } / pts.size
        val y = pts.sumOf { Math.sin(Math.toRadians(deg(it))) } / pts.size
        return Math.toDegrees(Math.atan2(y, x))
    }

    /**
     * The angle a contour covers — for these arc-and-chord bands, its width. Twice the widest
     * reach of any point from the contour's own centre, measured the short way round, which is
     * what keeps the pair across the ±180° line from being read as a 356° gap.
     */
    private fun span(pts: List<Offset>): Double = 2.0 * pts.maxOf { apart(deg(it), centre(pts)) }

    /** The smallest angle between two of a set of centres, folded to ±180°: six-fold says ~60. */
    private fun closestToEach(centres: List<Double>): Double =
        centres.flatMap { a -> centres.map { b -> apart(a, b) } }
            .filter { it > 1e-6 }
            .minOrNull() ?: error("two features share a centre")

    /** The angle between two directions round the grid, folded into 0..180°. */
    private fun apart(a: Double, b: Double): Double {
        var d = Math.abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }
}

// The path-data readers these tests share (subpaths, vertices, signedArea, circle) live in
// ui/components/PathReading.kt, next to the glyph set they measure - the same winding rule is
// asserted about marks that live nowhere near the bar.
