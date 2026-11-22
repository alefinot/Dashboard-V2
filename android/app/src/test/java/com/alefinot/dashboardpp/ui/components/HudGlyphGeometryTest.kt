package com.alefinot.dashboardpp.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import com.alefinot.dashboardpp.ui.components.HudGlyphLedger.glyphOf
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verb set's geometry, measured the way the bar's marks are measured.
 *
 * These glyphs are drawn to be read at 18 dp in a settings row and 22 dp in an action line. The page
 * established the bench phone's scale for the set: 22 dp is ~60 px, so one grid unit is ~2.5 px there
 * and ~2.05 px at 18 dp, which puts the shared 1.9-unit stroke at ~4 px at the smallest size. What
 * decides whether a mark survives that is not taste but two measurable things — whether an interior
 * detail is *cut* out of a body rather than drawn on top of it, and whether whatever daylight
 * separates two features is wider than the line that would close it. Both are properties of the path
 * data, so both are asserted here rather than eyeballed in a screenshot.
 *
 * The floor used throughout is 2.0 units — ~4 px at a row's 18 dp, slightly wider than the stroke
 * that draws the mark, which is the narrowest separation this set allows itself.
 */
class HudGlyphGeometryTest {

    /** The marks the unified icon grammar added, with the vector each one builds. */
    private val drawn: List<Pair<String, ImageVector>> = listOf(
        "Close" to HudIcons.Close,
        "Check" to HudIcons.Check,
        "Plus" to HudIcons.Plus,
        "Minus" to HudIcons.Minus,
        "Play" to HudIcons.Play,
        "Stop" to HudIcons.Stop,
        "Pause" to HudIcons.Pause,
        "File" to HudIcons.File,
        "Camera" to HudIcons.Camera,
        "Frame" to HudIcons.Frame,
        "Droplet" to HudIcons.Droplet,
        "Signal" to HudIcons.Signal,
        "Bolt" to HudIcons.Bolt,
        "Record" to HudIcons.Record,
        "Warn" to HudIcons.Warn,
        "Eye" to HudIcons.Eye,
        "FuelTank" to HudIcons.FuelTank,
        "Layers" to HudIcons.Layers,
    )

    /** Marks filled as a body, keyed by the outline identifier in HudIcons. */
    private val bodies: Map<String, String> = mapOf(
        "PlayData" to HudIcons.PlayData,
        "StopData" to HudIcons.StopData,
        "FileData" to HudIcons.FileData,
        "CameraData" to HudIcons.CameraData,
        "DropletData" to HudIcons.DropletData,
        "BoltData" to HudIcons.BoltData,
        "RecordData" to HudIcons.RecordData,
        "WarnData" to HudIcons.WarnData,
        "SignalDot" to HudIcons.SignalDot,
        "EyePupil" to HudIcons.EyePupil,
    )

    /** Marks drawn as a line, keyed by the stroke identifier in HudIcons. */
    private val lines: Map<String, String> = mapOf(
        "CloseData" to HudIcons.CloseData,
        "CheckData" to HudIcons.CheckData,
        "PlusData" to HudIcons.PlusData,
        "MinusData" to HudIcons.MinusData,
        "PauseData" to HudIcons.PauseData,
        "FrameData" to HudIcons.FrameData,
        "SignalArcs" to HudIcons.SignalArcs,
        "EyeLid" to HudIcons.EyeLid,
        "FuelTankData" to HudIcons.FuelTankData,
        "LayersData" to HudIcons.LayersData,
    )

    private val data: Map<String, String> = bodies + lines

    @Test
    fun `every mark is drawn on the same grid as the bar's`() {
        // A borrowed icon from another set would be a second visual language in an app that has one.
        for ((name, vector) in drawn) {
            assertEquals("$name viewport width", 24f, vector.viewportWidth, 0f)
            assertEquals("$name viewport height", 24f, vector.viewportHeight, 0f)
        }
    }

    @Test
    fun `every outline the set declares is measured by a test`() {
        // Adding a glyph whose geometry nobody checks is how a mark that closes up on the bench phone
        // gets committed. Every `internal val …: String` in HudIcons has to be measured here or in
        // BarLayoutTest, which measures the bar's own marks.
        val declared = Regex("""internal val (\w+): String""").findAll(iconsSource())
            .map { it.groupValues[1] }.toSet()
        val measuredByTheBar = setOf(
            "CogOutline", "SportWheelData", "EditData", "LinkOnData",
            "LinkOffData", "LinkOffBreak",
        )
        assertEquals("an outline exists that nothing measures", declared, data.keys + measuredByTheBar)
    }

    @Test
    fun `nothing runs off the grid`() {
        // A stroke's round cap reaches ~1 unit past its endpoint, which is why the box is the icon's
        // own 24 units plus that cap rather than the raw endpoint range.
        for ((name, datum) in data) {
            val off = contourPoints(datum).filter { it.x !in -1f..25f || it.y !in -1f..25f }
            assertTrue("$name leaves the grid: $off", off.isEmpty())
        }
    }

    /**
     * The one rule this set exists for, asserted on every mark that carries a detail: a hole is a
     * contour wound *against* the body, never a lighter fill painted over it — so whatever the icon
     * sits on shows through, and the detail cannot be lost to a tint change.
     */
    @Test
    fun `every interior detail is cut by counter-winding and clears the body's edge`() {
        // File: the page, and its folded corner.
        val file = subpaths(HudIcons.FileData)
        assertEquals("a page and the fold cut out of it", 2, file.size)
        assertTrue("the page does not wind as ink", area(file[0]) > 0.0)
        assertTrue("the fold winds with the page, so the corner fills and the mark is a blank sheet",
            area(file[1]) * area(file[0]) < 0.0)
        assertInside("the fold", file[1], boxOf(file[0]), "the page")

        // Camera: the body with its viewfinder bump, and the lens.
        val camera = subpaths(HudIcons.CameraData)
        assertEquals("a body and the lens cut out of it", 2, camera.size)
        assertTrue("the camera body does not wind as ink", area(camera[0]) > 0.0)
        val lens = circle(camera[1])
        assertTrue("the lens winds with the body, so the camera has no eye", lens.sign < 0.0)
        assertEquals("the lens is off the camera's axis", 12f, lens.centre.x, 0.01f)
        assertTrue("a r${lens.radius} lens is a speck at 18 dp", lens.radius >= 2.5f)
        assertWall("the camera's lens", lens, boxOf(camera[0]))

        // Droplet: the drop, and the bore cut through its belly.
        val drop = subpaths(HudIcons.DropletData)
        assertEquals("a drop and the bore cut out of it", 2, drop.size)
        assertTrue("the drop does not wind as ink", area(drop[0]) > 0.0)
        val bore = circle(drop[1])
        assertTrue("the bore winds with the drop, so the drop is solid", bore.sign < 0.0)
        assertEquals("the bore is off the drop's axis", 12f, bore.centre.x, 0.01f)
        assertTrue("a r${bore.radius} bore is a pinprick at 18 dp", bore.radius >= 2.0f)
        // The chord points of the body top out at the shoulder — the seam between the cone and the
        // sphere. A bore that crosses it eats into the point and the drop stops reading as a drop.
        val shoulder = contourPoints(drop[0]).maxOf { it.y }
        assertTrue("the bore climbs into the drop's point ($shoulder)", bore.centre.y - bore.radius >= shoulder)

        // Warn: the triangle, the bar and the dot of the exclamation.
        val warn = subpaths(HudIcons.WarnData)
        assertEquals("a triangle, a bar and a dot", 3, warn.size)
        assertTrue("the triangle does not wind as ink", area(warn[0]) > 0.0)
        val bar = contourPoints(warn[1])
        assertTrue("the bar winds with the triangle, so the exclamation is painted over",
            area(warn[1]) * area(warn[0]) < 0.0)
        val dot = circle(warn[2])
        assertTrue("the dot winds with the triangle, so the exclamation is painted over", dot.sign < 0.0)

        val barWidth = bar.maxOf { it.x } - bar.minOf { it.x }
        assertTrue("a $barWidth-unit bar closes up at a header's 18 dp", barWidth >= 2.0f)
        assertEquals("the exclamation is not centred on the triangle", 12f,
            (bar.minOf { it.x } + bar.maxOf { it.x }) / 2f, 0.01f)
        assertEquals("the exclamation is not centred on the triangle", 12f, dot.centre.x, 0.01f)
        assertTrue("an r${dot.radius} dot is a smudge at 18 dp", dot.radius >= 1.6f)
        val daylight = dot.centre.y - dot.radius - bar.maxOf { it.y }
        assertTrue("the bar and the dot sit $daylight apart, so the ! reads as one vertical smear",
            daylight >= 2.0f)
    }

    /** A solid body: one contour, wound as ink, sized so it is the mark and not a speck. */
    @Test
    fun `the solid bodies are single contours with real area`() {
        for ((name, datum) in listOf(
            "Play" to HudIcons.PlayData,
            "Stop" to HudIcons.StopData,
            "Bolt" to HudIcons.BoltData,
        )) {
            val subs = subpaths(datum)
            assertEquals("$name is one silhouette", 1, subs.size)
            val pts = contourPoints(datum)
            // A triangle is the fewest corners a body can have and still be a body, and the play mark
            // is exactly that.
            assertTrue("$name has too few corners to hold its shape", pts.size >= 3)
            val a = signedArea(pts)
            assertTrue("$name winds as a hole ($a) instead of ink", a > 0.0)
            assertTrue("$name covers only $a of the 576-unit box: it is a speck, not a mark",
                abs(a) >= 50.0)
        }
        val disc = circle(subpaths(HudIcons.RecordData).first())
        assertTrue("a r${disc.radius} record dot is under a pixel wide at 18 dp", disc.radius >= 4f)
        assertTrue("the disc is not centred on the grid",
            hypot(disc.centre.x - 12f, disc.centre.y - 12f) < 0.01f)
    }

    /**
     * The marks that are only a line. Nothing here can be cut, so what is asserted is the two ways
     * they can still go wrong: an accidental closure (a `Z`, which would let a fill close the
     * interior the set refuses to draw), and a mark that quietly stops going through the set's
     * [HudIcons] stroke builder and so loses the shared weight.
     */
    @Test
    fun `the line marks are open strokes through the set's own builder`() {
        val source = iconsSource()
        for ((id, datum) in lines) {
            assertTrue("$id closes its interior, so a fill can shut it", !datum.contains("Z"))
            // Every line mark but the two halves of a two-path mark is stroked by the set's shared
            // builder, which is what gives the whole vocabulary one weight. A two-path mark has to
            // add its own path with the stroke spelled out — the GNSS arcs and the eye's lens — and
            // each one still uses the set's W.
            val mark = id.removeSuffix("Data")
            assertTrue(
                "$id is not stroked by the shared builder, so it does not carry the set's weight",
                id in twoPathLines ||
                    Regex("""stroked\("$mark",\s*$id""").containsMatchIn(source),
            )
            val subs = subpaths(datum)
            for ((i, sub) in subs.withIndex()) {
                assertTrue("$id stroke $i has no line to stroke", contourPoints(sub).size >= 2)
            }
        }
        // And the two-path marks really do carry the set's weight by hand: same builder constant,
        // round ends, or they are a second line language on the grid.
        for (name in listOf("Signal", "Eye")) {
            val body = source.substringAfter("val $name: ImageVector").substringBefore("internal val")
            assertTrue("$name is stroked at a weight of its own",
                Regex("strokeLineWidth = W").containsMatchIn(body))
            assertTrue("$name has square line ends",
                Regex("StrokeCap\\.Round").containsMatchIn(body))
        }
    }

    /** Line data that lives inside a two-path mark, and so cannot go through `stroked(...)`. */
    private val twoPathLines = setOf("SignalArcs", "EyeLid")

    /**
     * The EYE on the Live page's rail: a lens of two arcs with a pupil inside it, and the two
     * things that can go wrong are the lens closing on the pupil and the mark forgetting to be an
     * eye. Both are measurable on the path data, so both are asserted here.
     *
     * The daylight is measured from the true crown of the arc, not from the three points the path
     * spells out: an arc's endpoints are its corners, and the corner of an eye is on its own
     * centre line. R and the chord between the corners give the sagitta, and the sagitta is the
     * top of the lens.
     */
    @Test
    fun `the eye keeps its pupil clear of its lens and stays an eye`() {
        val lid = HudIcons.EyeLid
        assertEquals("one path for the whole lens", 1, subpaths(lid).size)
        val arcs = Regex("""A([\d.]+),[\d.]+ 0 0 1 ([\d.]+),([\d.]+)""").findAll(lid).toList()
        assertEquals("two lids, both the short way round", 2, arcs.size)
        val radius = arcs.map { it.groupValues[1].toFloat() }
        assertEquals("the two lids are not the same curve", radius[0], radius[1], 0.01f)
        val r = radius[0]

        // Corners on the grid's centre line, symmetric about x = 12: the first arc ends at the
        // right corner, the second comes back to the left one.
        val rightCorner = Offset(arcs[0].groupValues[2].toFloat(), arcs[0].groupValues[3].toFloat())
        val leftCorner = Offset(arcs[1].groupValues[2].toFloat(), arcs[1].groupValues[3].toFloat())
        assertEquals("a lid corner is off the centre line", 12f, rightCorner.y, 0.01f)
        assertEquals("a lid corner is off the centre line", 12f, leftCorner.y, 0.01f)
        val halfChord = (rightCorner.x - leftCorner.x) / 2f
        assertEquals("the lens is not centred on the grid", 12f, (rightCorner.x + leftCorner.x) / 2f, 0.01f)
        val width = halfChord * 2f

        val pupil = circle(subpaths(HudIcons.EyePupil).first())
        assertEquals("the pupil is off the eye's axis", 12f, pupil.centre.x, 0.01f)
        assertEquals("the pupil is off the eye's axis", 12f, pupil.centre.y, 0.01f)
        assertTrue("a r${pupil.radius} pupil is a hair at 18 dp", pupil.radius >= 2.0f)

        val crown = 12f - (r - sqrt(r * r - halfChord * halfChord))
        val height = (12f - crown) * 2f
        // The daylight, on both sides at once: the drawn lid's inner edge is half the stroke
        // inside the crown, and the pupil is a body that reaches all the way to its radius.
        val daylight = (12f - pupil.radius) - (crown + 1.9f / 2f)
        assertTrue(
            "the lens closes on the pupil: $daylight units of daylight at r$r on a $width chord " +
                "is less than the 2.0 this set will not go below",
            daylight >= 2.0f,
        )
        assertTrue("an eye $width wide and $height tall is a circle with a dot in it", width >= 1.4f * height)
        assertTrue("the lens reaches outside the grid at its crown: $crown", crown - 1.9f / 2f >= 0f)
    }

    /**
     * PAUSE is two strokes and the daylight between them is the meaning. Measured rather than
     * eyeballed: at a row's 18 dp the gap between the bars is ~9 px, while the stroke that draws
     * them is ~4 - any closer and the pair reads as one fat bar, which is the opposite verb.
     */
    @Test
    fun `pause is two vertical bars with daylight between them`() {
        val bars = polylines(HudIcons.PauseData)
        assertEquals("pause is two strokes", 2, bars.size)
        for (bar in bars) {
            assertEquals("a pause bar is not vertical", 2, bar.size)
            assertEquals("a pause bar leans", bar.first().x, bar.last().x, 0.01f)
            assertEquals("a pause bar is shorter than the stop square beside it", 13.6f,
                bar.last().y - bar.first().y, 0.01f)
        }
        val left = bars.minByOrNull { it.first().x }!!
        val right = bars.maxByOrNull { it.first().x }!!
        // The pair straddles the grid's centre line, so the mark sits centred in its box.
        assertEquals("the pause bars are not centred on the grid", 12f,
            (left.first().x + right.first().x) / 2f, 0.01f)
        val daylight = right.first().x - left.first().x - 1.9f
        assertTrue("the bars sit $daylight apart, so they read as one bar", daylight >= 2.0f)
        // Same height as the stop square: the control line must not resize when the verb changes.
        assertEquals("pause is not the stop mark's height", 13.6f,
            (bars.maxOf { it.maxOf { p -> p.y } } - bars.minOf { it.minOf { p -> p.y } }), 0.01f)
    }

    @Test
    fun `close is a cross, plus is a cross, minus is one stroke`() {
        val close = polylines(HudIcons.CloseData)
        assertEquals("two strokes", 2, close.size)
        // Both strokes must pass through the middle, or it is a check that lost its short leg.
        for (stroke in close) {
            val mid = Offset(
                (stroke.first().x + stroke.last().x) / 2f,
                (stroke.first().y + stroke.last().y) / 2f,
            )
            assertTrue("a close stroke is off-centre at $mid",
                hypot(mid.x - 12f, mid.y - 12f) < 0.01f)
        }

        val plus = polylines(HudIcons.PlusData)
        assertEquals("two strokes", 2, plus.size)
        val vertical = plus.first { abs(it.first().x - it.last().x) < 0.01f }
        val horizontal = plus.first { abs(it.first().x - it.last().x) > 0.01f }
        assertEquals("the vertical arm is off-axis", 12f, vertical.first().x, 0.01f)
        assertEquals("the horizontal arm is off-axis", 12f, horizontal.first().y, 0.01f)
        assertEquals("a plus with unequal arms reads as an off-centre cross",
            length(vertical), length(horizontal), 0.01f)

        assertEquals("minus is one stroke", 1, polylines(HudIcons.MinusData).size)

        // Check is the mark that must not read as Close: one polyline, three points, and the knee
        // that makes it a tick rather than a rotated cross is the low-left one.
        val check = contourPoints(HudIcons.CheckData)
        assertEquals("a check is one polyline of three points", 3, check.size)
        val knee = check.minByOrNull { it.x + it.y }!!
        assertTrue("the check's knee is not the low-left point ($knee)", knee.x < 12f && knee.y > 12f)
        assertTrue("the check's long leg is not the long one",
            length(listOf(knee, check.maxByOrNull { it.x + it.y }!!)) >
                length(listOf(knee, check.minByOrNull { it.x - it.y }!!)))
    }

    /** A crop frame is four brackets, one in each corner, and nothing in the middle. */
    @Test
    fun `frame is four corner brackets`() {
        val brackets = polylines(HudIcons.FrameData)
        assertEquals("four brackets", 4, brackets.size)
        val quadrants = brackets.map { pts ->
            val cx = pts.map { it.x }.average()
            val cy = pts.map { it.y }.average()
            Pair(cx > 12.0, cy > 12.0)
        }.toSet()
        assertEquals("the brackets are not one per corner: $quadrants",
            setOf(Pair(false, false), Pair(true, false), Pair(false, true), Pair(true, true)),
            quadrants)
        // Open at the sides on purpose: a closed rectangle around a crop mark is the
        // stroked-outline-with-something-inside case again, so nothing may cross the middle.
        for (pts in brackets) {
            assertTrue("a bracket reaches into the middle of the mark: $pts",
                pts.all { abs(it.x - 12f) > 3f || abs(it.y - 12f) > 3f })
        }
    }

    /**
     * The GNSS mark: a source point with two arcs opening above it. They must be concentric with the
     * point, separated by more than the stroke that draws them, and open upward — which is what a
     * satellite with solar panels could not be guaranteed to stay at a card header's size.
     */
    @Test
    fun `the signal's arcs are concentric, open upward, and clear of each other`() {
        val radii = Regex("""A([\d.]+),[\d.]+ 0 0 1""").findAll(HudIcons.SignalArcs)
            .map { it.groupValues[1].toFloat() }.toList()
        assertEquals("two arcs", 2, radii.size)
        val gap = radii.max() - radii.min()
        assertTrue("arcs $radii sit $gap apart; that is not more than the stroke that draws them",
            gap >= 2.0f)

        val dot = circle(subpaths(HudIcons.SignalDot).first())
        assertEquals("the source point is off-axis", 12f, dot.centre.x, 0.01f)
        assertTrue("the source point is not clear of the inner arc",
            dot.radius + 1.9f <= radii.min())

        // Every endpoint lies on its own radius from the source point, and above it.
        for ((i, sub) in subpaths(HudIcons.SignalArcs).withIndex()) {
            val radius = Regex("""A([\d.]+),""").find(sub)!!.groupValues[1].toFloat()
            val ends = contourPoints(sub)
            assertEquals("arc $i is not a single arc", 2, ends.size)
            for (p in ends) {
                val d = hypot(p.x - dot.centre.x, p.y - dot.centre.y)
                assertTrue("an arc endpoint sits $d from the source point, not $radius",
                    abs(d - radius) < 0.35f)
                assertTrue("arc $i reaches below the source point it is meant to open above",
                    p.y < dot.centre.y)
            }
            // Sweep flag 1 with a large-arc flag of 0 is the short way round, and the short way must
            // be the way that passes over the top.
            assertTrue("arc $i is drawn the long way round", radius == radii[i])
        }
        // The crown of each arc — the top of the ring it belongs to — is what the eye reads as the
        // opening, so it has to stay inside the grid.
        for (r in radii) {
            assertTrue("an arc of r$r leaves the grid at its crown", dot.centre.y - r - 1.9f / 2f >= 0f)
        }
    }

    /**
     * LAYERS — the Live page's ground cycler (2026-11-22): a sheet over a sheet. The mark's whole
     * meaning is the seam between the two, so the seam is what gets measured. The lower `V` is
     * the upper sheet's lower half translated straight down, which is what keeps the gap even all
     * the way along a sloping arm rather than pinching it at one end; the vertical offset is not
     * the daylight, though — the arms slope, so the daylight is the perpendicular distance minus
     * the line that draws both.
     */
    @Test
    fun `layers is a sheet over a sheet with an even seam`() {
        val subs = polylines(HudIcons.LayersData)
        assertEquals("one sheet and the seam under it", 2, subs.size)
        val sheet = subs[0]
        val seam = subs[1]
        assertEquals("the sheet must run back to where it started", sheet.first(), sheet.last())
        assertEquals("the lower sheet is an open V, not a second sheet", 3, seam.size)
        for (pts in subs) {
            assertEquals(
                "a sheet is off the grid's axis", 12f,
                (pts.minOf { it.x } + pts.maxOf { it.x }) / 2f, 0.01f,
            )
        }

        // The seam's three points are the sheet's lower three, mirrored in x and pushed down.
        val lower = listOf(sheet[1], sheet[2], sheet[3])
        val offset = seam[1].y - sheet[2].y
        assertTrue("the seam is above the sheet it sits under", offset > 0f)
        for (i in seam.indices) {
            assertEquals("seam arm $i is not parallel to the sheet's", lower[2 - i].x, seam[i].x, 0.01f)
            assertEquals(
                "seam arm $i is not the sheet's arm moved down",
                offset, seam[i].y - lower[2 - i].y, 0.01f,
            )
        }

        val rise = sheet[2].y - sheet[1].y
        val run = sheet[1].x - sheet[2].x
        val perpendicular = offset * run / hypot(rise, run)
        val daylight = perpendicular - 1.9f
        assertTrue(
            "the seam closes: $daylight units between the two sheets at a $offset vertical offset " +
                "on a $rise-over-$run slope, against the 2.0 this set will not go below",
            daylight >= 2.0f,
        )
        assertTrue("a sheet $run wide is not a map", run >= 7f)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** The glyph set's own source, for the two assertions that are about how a mark is built. */
    private fun iconsSource(): String =
        java.io.File("src/main/java/com/alefinot/dashboardpp/ui/components/HudIcons.kt").readText()

    /** One polyline per `M`, every point the stroke actually passes through. */
    private fun polylines(data: String): List<List<Offset>> =
        subpaths(data).map { contourPoints(it) }

    private fun length(points: List<Offset>): Float =
        (1 until points.size).sumOf {
            hypot(points[it].x - points[it - 1].x, points[it].y - points[it - 1].y).toDouble()
        }.toFloat()

    /** Signed area of a contour read as the fill sees it, chords for the arcs. */
    private fun area(sub: String): Double = signedArea(contourPoints(sub))

    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * The outline's extremes. Arc ends are part of this on purpose: the camera's body is 22 wide
     * because of the arc that touches 22, and a wall measured without them would be a lie.
     */
    private fun boxOf(sub: String): Box {
        val pts = contourPoints(sub)
        return Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /** A detail must keep [FLOOR] of daylight from the body that carries it, on every side. */
    private fun assertWall(what: String, hole: Circle, body: Box) {
        val walls = listOf(
            "left" to hole.centre.x - hole.radius - body.left,
            "right" to body.right - (hole.centre.x + hole.radius),
            "top" to hole.centre.y - hole.radius - body.top,
            "bottom" to body.bottom - (hole.centre.y + hole.radius),
        )
        val thin = walls.filter { it.second < 2.0f }
        assertTrue("$what has no wall to spare: $thin", thin.isEmpty())
    }

    private fun assertInside(what: String, inner: String, outer: Box, inside: String) {
        val bad = contourPoints(inner).filter {
            it.x < outer.left || it.x > outer.right || it.y < outer.top || it.y > outer.bottom
        }
        assertTrue("$what is outside $inside: $bad", bad.isEmpty())
    }

    /** Every mark the ledger answers to must be one of the marks this file measures. */
    @Test
    fun `the ledger only points at marks that exist`() {
        val names = drawn.map { it.first }.toSet()
        val unmeasured = glyphOf.values.map { it.name.removePrefix("Hud") }.filter {
            it in setOf(
                "Close", "Check", "Plus", "Minus", "Play", "Stop", "Pause", "File", "Camera",
                "Frame", "Droplet", "Signal", "Bolt", "Record", "Warn", "Eye",
            ) && it !in names
        }
        assertTrue("the ledger points at a mark this test does not measure: $unmeasured",
            unmeasured.isEmpty())
    }
}
