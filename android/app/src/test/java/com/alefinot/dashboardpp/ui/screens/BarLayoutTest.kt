package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudIcons
import kotlin.math.abs
import kotlin.math.hypot
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
 * One list now, and these tests are what keep it that way — that the centre slot is the
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
        assertEquals(30.dp, car.size)
        assertEquals(52.dp, car.box)
        assertTrue(car.colored)
        for (tab in BarLayout.pages.filter { it != AppTab.VEHICLES }) {
            val g = BarLayout.glyph(tab)
            assertEquals("$tab weight", 1f, g.weight, 0f)
            assertEquals("$tab size", 22.dp, g.size)
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
    fun `the wheel glyph is drawn on the same grid as the others`() {
        // Same 24-unit viewport, same stroke language: a borrowed icon from another set
        // would be a second visual language in a bar that already has one.
        assertEquals(24f, HudIcons.Wheel.viewportWidth, 0f)
        assertEquals(24f, HudIcons.Wheel.viewportHeight, 0f)
    }

    /**
     * The wheel obeys the same rule the hatchback it replaces was built to teach: at the bar's
     * sizes - a 1.9-unit stroke is ~5 px, so every interior has to be wider than the line that
     * would close it - a mark with interior detail is a *filled* body whose detail is opened by
     * counter-winding, never a stroked outline. So the tyre is a disc with a smaller disc wound
     * the other way cut out of it, the face is a disc drawn back inside that groove, and each
     * spoke gap is a wedge wound against the face. NonZero only opens a contour that winds the
     * other way, so what shows through the openings and the groove is the glass sheet the bar
     * floats on - nothing is painted over it.
     *
     * Checked on the path data rather than on the built `ImageVector` because Compose 1.7.5 keeps
     * a vector's paths to itself (`pathComponents` is gone, and what replaced it is not public
     * API), and the data is where the winding actually lives anyway.
     */
    @Test
    fun `the wheel is rings and spoke gaps cut out of a filled face`() {
        val subs = subpaths(HudIcons.WheelData)
        assertEquals("tyre outer, tyre inner, the wheel face, and five spoke gaps", 8, subs.size)
        // The first three contours are the two-arc circles the `circle` reader parses; the rest
        // are the spoke gaps, four chords each.
        val rings = subs.take(3).map(::circle)
        val gaps = subs.drop(3)
        assertEquals("five spoke gaps", 5, gaps.size)
        val (tyreOut, tyreIn, face) = rings
        for (ring in rings) {
            assertEquals("ring off-centre in x", 12f, ring.centre.x, 0.01f)
            assertEquals("ring off-centre in y", 12f, ring.centre.y, 0.01f)
        }

        // The tyre has to *be* a ring: its inner edge winds against its outer edge, and it is the
        // groove between the tyre and the face that says "wheel" rather than "solid disc".
        assertTrue("the tyre's outer edge does not wind as ink ($tyreOut)", tyreOut.sign > 0.0)
        assertTrue("the tyre's inner edge winds with the outer, so the tyre fills solid",
            tyreIn.sign * tyreOut.sign < 0.0)
        assertTrue("the face does not wind as ink, so the middle of the wheel is a hole",
            face.sign * tyreOut.sign > 0.0)
        val tyreWidth = tyreOut.radius - tyreIn.radius
        val groove = tyreIn.radius - face.radius
        assertTrue("a $tyreWidth-unit tyre closes up at the bar's smallest size", tyreWidth >= 2f)
        assertTrue("a $groove-unit groove between tyre and rim is not daylight at 30 dp",
            groove >= 1.2f)

        // Each spoke gap must wind against the face, or NonZero fills it and the wheel has no
        // spokes - it is a disc again, the very thing this glyph exists to not be.
        for ((i, area) in gaps.map { signedArea(vertices(it)) }.withIndex()) {
            assertTrue("spoke gap $i winds with the face ($area), so it fills and the wheel is " +
                "solid", area < 0.0)
        }

        // Gaps live strictly inside the face: a hub stays solid at the centre, a rim lip stays at
        // the edge, and nothing ever breaches the groove into the tyre.
        val gapRadii = gaps.flatMap { vertices(it) }.map { hypot(it.x - 12f, it.y - 12f) }
        val innermost = gapRadii.minOrNull()!!
        val outermost = gapRadii.maxOrNull()!!
        assertTrue("a spoke gap reaches to r=$innermost: the hub has been cut away",
            innermost >= 2.5f)
        assertTrue("a spoke gap reaches r=$outermost, into the rim lip or the tyre " +
            "(the face is only r${face.radius})", outermost < face.radius - 0.05f)

        // Five gaps, five-fold: their centroids sit ~72 degrees apart, so no two gaps merge and no
        // spoke is pinched out at the hub.
        val centroids = gaps.map {
            val v = vertices(it)
            val cx = v.map { p -> p.x }.average().toFloat()
            val cy = v.map { p -> p.y }.average().toFloat()
            Math.atan2((cy - 12f).toDouble(), (cx - 12f).toDouble())
        }
        val closest = centroids.flatMap { a ->
            centroids.map { b ->
                var d = Math.abs(a - b)
                if (d > Math.PI) d = 2 * Math.PI - d
                d
            }
        }.filter { it > 1e-6 }.minOrNull() ?: error("two spoke gaps share a centre")
        val separation = Math.toDegrees(closest)
        assertTrue("closest spoke gaps sit only $separation degrees apart; five-fold spacing " +
            "is ~72", separation >= 55.0)
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
        assertTrue("the rings sit $gap apart and never touch — two discs, not a link", gap < 2f * on[0].radius)
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
     * purpose — at the card corner's ~18 dp a ferrule band and a lead dot are the mush the glyph set
     * exists to avoid — so what is pinned is that it is one closed body, on the shared 24 grid,
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
}

// --- reading path data ---------------------------------------------------------------
// These glyphs use absolute M/L/Q/C/A/Z only, which is all this reads. Deliberately not a
// general SVG parser: it answers "how many contours, where are their vertices, which way do
// they wind", and nothing else.

private val COMMAND = Regex("([MLQCZ])([^MLQCZ]*)")

/** One subpath per `M`: a contour, which is what a fill rule reads. */
private fun subpaths(data: String): List<String> =
    Regex("M[^M]*").findAll(data).map { it.value.trim() }.toList()

/**
 * The vertices a fill sees, endpoint by endpoint. A curve contributes only its end point: a
 * control point is not on the outline, and counting it as a vertex makes a sampled arch
 * polygon cross itself - the quadratics that draw the wheel arches bulge outside the band.
 */
private fun vertices(sub: String): List<Offset> {
    val points = mutableListOf<Offset>()
    for (command in COMMAND.findAll(sub)) {
        val numbers = command.groupValues[2].trim()
            .takeIf { it.isNotEmpty() }
            ?.split(Regex("[,\\s]+"))
            ?.map { it.toFloat() }
            .orEmpty()
        when (command.groupValues[1]) {
            "M", "L" -> numbers.chunked(2) { points += Offset(it[0], it[1]) }
            // Q takes four numbers, C six: the last two are the end point.
            "Q", "C" -> if (numbers.size >= 4) {
                points += Offset(numbers[numbers.size - 2], numbers[numbers.size - 1])
            }
        }
    }
    return points
}

/** Signed area, y growing down: positive is the direction a fill winds as ink here. */
private fun signedArea(points: List<Offset>): Double =
    0.5 * points.indices.sumOf { i ->
        val a = points[i]
        val b = points[(i + 1) % points.size]
        a.x.toDouble() * b.y - b.x.toDouble() * a.y
    }

/** A circle drawn as two half arcs, and the direction it goes round in. */
private data class Circle(val centre: Offset, val radius: Float, val sign: Double)

private val ARCS = Regex(
    """M\s*([-\d.]+),([-\d.]+)\s+A\s*([\d.]+),([-\d.]+)\s+0 1 ([01])\s+([-\d.]+),([-\d.]+)""" +
        """\s+A\s*([\d.]+),([-\d.]+)\s+0 1 ([01])\s+([-\d.]+),([-\d.]+)\s*Z""",
)

private fun circle(sub: String): Circle {
    val m = requireNotNull(ARCS.matchEntire(sub.trim())) {
        "not a circle drawn as two half arcs: $sub"
    }.groupValues
    val start = Offset(m[1].toFloat(), m[2].toFloat())
    val mid = Offset(m[6].toFloat(), m[7].toFloat())
    val end = Offset(m[11].toFloat(), m[12].toFloat())
    val radius = m[3].toFloat()
    require(end == start && m[4] == m[3] && m[9] == m[3] && m[5] == m[10]) {
        "not a closed pair of equal arcs: $sub"
    }
    // Two arcs between opposite ends of a diameter: the centre is the midpoint, and the
    // distance between them is the diameter. Asserted, so the shape really is the circle the
    // flags claim rather than something that happens to parse.
    val centre = Offset((start.x + mid.x) / 2f, (start.y + mid.y) / 2f)
    require(kotlin.math.abs(hypot(mid.x - start.x, mid.y - start.y) - 2f * radius) < 0.02f) {
        "arcs are not semicircles of r$radius: $sub"
    }
    // sweep flag 1 = angle increasing in this y-down grid = the sign `signedArea` calls ink.
    return Circle(centre, radius, if (m[5] == "1") 1.0 else -1.0)
}
