package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.circle
import com.alefinot.dashboardpp.ui.components.contourPoints
import com.alefinot.dashboardpp.ui.components.signedArea
import com.alefinot.dashboardpp.ui.components.subpaths
import com.alefinot.dashboardpp.ui.components.vertices
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
        assertEquals(24f, HudIcons.Garage.viewportWidth, 0f)
        assertEquals(24f, HudIcons.Garage.viewportHeight, 0f)
    }

    /**
     * The garage front the bar and the cards draw, after the maintainer's 2026-11-22 restyle: the
     * wheel era ended, the car key that replaced it was pulled the same day for assembling a face
     * out of two round buttons on a square face, the menu became the Garage, and the mark drew what
     * a garage looks like from the street — gable and eaves, a roller door bored through the front,
     * three slats floating in the bore. It is the set's one rule stated three ways in one path:
     * ink, daylight counter-wound against the ink, ink again inside the daylight.
     *
     * The floors are stated in pixels at the bar's 30 dp (one unit ~3.4 px on the bench phone),
     * the size the mark is seen at most and the size a photo-less card cover draws it at; the
     * editor's well is 38 dp, and the `GO TO GARAGE` button's 18 dp is deliberately not
     * floored here because at that size the slats fall together into one ribbed panel, which is
     * still a garage door.
     */
    @Test
    fun `the garage front is a roof over a roller door of three slats`() {
        val subs = subpaths(HudIcons.GarageData)
        assertEquals("the building, the door bore, and three slats", 5, subs.size)

        val shell = contourPoints(subs[0])
        assertTrue("the garage leaves the 24-unit grid",
            shell.all { it.x in 0f..24f && it.y in 0f..24f })
        assertTrue("the shell does not wind as ink", signedArea(shell) > 0.0)

        val left = shell.minOf { it.x }
        val right = shell.maxOf { it.x }
        val apex = shell.minOf { it.y }
        val bottom = shell.maxOf { it.y }
        assertEquals("the front is off the grid's axis", 12f, (left + right) / 2f, 0.01f)

        // Walls, jambs, sill — the daylight-safe frame the door is cut inside. The wall line is
        // the bottom edge's own span: the eave tips sit above it, so min/max x there is the wall,
        // not the overhang.
        val wallLeft = shell.filter { it.y >= bottom - 0.01f }.minOf { it.x }
        val wallRight = shell.filter { it.y >= bottom - 0.01f }.maxOf { it.x }
        assertTrue("the eaves do not overhang the walls, so this is a house, not a garage front",
            wallLeft - left >= 2.0f && right - wallRight >= 2.0f)

        // The door is daylight bored through the face — wound against the shell exactly as the
        // pin bores its eye — and centred like everything else in the set.
        val door = contourPoints(subs[1])
        assertTrue("the door winds with the shell, so the front has no opening",
            signedArea(door) < 0.0)
        val doorLeft = door.minOf { it.x }
        val doorRight = door.maxOf { it.x }
        val doorTop = door.minOf { it.y }
        val doorBottom = door.maxOf { it.y }
        assertEquals("the door is not centred on the front", 12f, (doorLeft + doorRight) / 2f, 0.01f)
        assertTrue("a door at x$doorLeft leaves the shell's wall no jamb at 30 dp",
            doorLeft - wallLeft >= 2.0f && wallRight - doorRight >= 2.0f)
        assertTrue("the door runs into the sill", bottom - doorBottom >= 2.0f)

        // The lintel: the strip of front between the door's head and the eaves line. The eaves'
        // y is the only other height the shell's outline carries between them.
        val eave = shell.filter { it.y > apex + 0.01f && it.y < doorTop }.maxOf { it.y }
        assertTrue("the roof is only ${eave - apex} units tall over the eaves line",
            eave - apex >= 4.0f)
        assertTrue("the door climbs into the eaves, so there is no lintel at 30 dp",
            doorTop - eave >= 1.5f)

        // The slats: ink again inside the bore, so NonZero paints bars and leaves the grooves the
        // glass sheet shows through. Three of them, equal, evenly spaced, standing clear of the
        // bore's own edge so it stays a ring and not a comb.
        val slats = subs.subList(2, 5).map { contourPoints(it) }
            .sortedBy { pts -> pts.minOf { it.y } }
        for ((i, s) in slats.withIndex()) {
            assertTrue("slat $i winds against the shell, so the door is a solid dark panel",
                signedArea(s) > 0.0)
            assertTrue("slat $i pokes out of the bore",
                s.all { it.x > doorLeft && it.x < doorRight && it.y > doorTop && it.y < doorBottom })
        }
        val heights = slats.map { pts -> pts.maxOf { it.y } - pts.minOf { it.y } }
        assertTrue("the slats are not equal bars: ${heights.map { "%.2f".format(it) }}",
            heights.max() - heights.min() <= 0.05f)
        val grooves = mutableListOf(slats[0].minOf { it.y } - doorTop)
        grooves += slats.zipWithNext { a, b -> b.minOf { it.y } - a.maxOf { it.y } }
        grooves += doorBottom - slats.last().maxOf { it.y }
        assertTrue("the door's daylight is not even: ${grooves.map { "%.2f".format(it) }}",
            grooves.max() - grooves.min() <= 0.05f)
        val slatLeft = slats.minOf { pts -> pts.minOf { it.x } }
        val slatRight = slats.maxOf { pts -> pts.maxOf { it.x } }
        assertTrue("the slats touch the jambs, so the bore closes into a comb",
            slatLeft - doorLeft >= 0.75f && doorRight - slatRight >= 0.75f)

        // Pixels at the size the mark is seen most: the front carries the mark, so it gets the
        // biggest floor; the grooves between slats are the fine thing and the last to survive.
        val px = 82f / 24f   // the card cover's 30 dp on the bench phone
        assertTrue("${(right - left) * px}px of front is a shed", (right - left) * px >= 56f)
        assertTrue("${(eave - apex) * px}px of roof is a flat line at 30 dp",
            (eave - apex) * px >= 20f)
        assertTrue("${(doorRight - doorLeft) * px}px of door is a slot", (doorRight - doorLeft) * px >= 26f)
        val slat = heights.average().toFloat()
        assertTrue("${slat * px}px of slat is a hair at 30 dp", slat * px >= 4f)
        val groove = grooves.average().toFloat()
        assertTrue("${groove * px}px of groove closes up at 30 dp, and the door stops being " +
            "a roller door", groove * px >= 2.5f)
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

}

// The path-data readers these tests share (subpaths, vertices, signedArea, circle) live in
// ui/components/PathReading.kt, next to the glyph set they measure - the same winding rule is
// asserted about marks that live nowhere near the bar.
