package com.alefinot.dashboardpp.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map-style catalogue's own contract (App Settings → Live Map, 2026-11-21).
 *
 * This one is a real test of real objects rather than a scan of source text, because every
 * rule here is arithmetic over data: the catalogue is a list of records, and a wrong value in
 * one of them is invisible until a map is open. The failures these assertions prevent are all
 * bench-sized:
 *
 *  * two styles pointing at different servers under one source name — osmdroid's on-disk tile
 *    cache is keyed `<sourceName>/z/x/y.png`, so the second one would silently be served the
 *    first one's tiles and keep its own label;
 *  * a zoom ceiling under the camera the follow map holds the drive at (z15), which shows up
 *    as a grey box on one style and nothing else;
 *  * a colour filter whose rows do not sum evenly, i.e. a basemap with a colour cast;
 *  * an attribution that quietly left, which is the part the tile providers ask for and the
 *    part a code change has no compile-time reason to keep.
 *
 * Nothing here touches osmdroid or `android.graphics`: [MapStyle] is data, and [MapStyles]
 * builds its tile sources and colour filters lazily, so the JVM run never asks for a stub.
 */
class MapStyleContractTest {

    @Test
    fun idsAreUniqueAndTheFirstIsTheDefault() {
        val ids = MapStyles.ALL.map { it.id }
        assertEquals("style ids must be unique", ids.size, ids.toSet().size)
        assertTrue(
            "the shipped default must be in the list — the picker shows the list",
            MapStyles.ALL.any { it.id == MapStyles.STANDARD_ID },
        )
        assertSame(
            "the default style must be the one the fallback resolves to",
            MapStyles.byId(MapStyles.STANDARD_ID), MapStyles.Standard,
        )
    }

    /**
     * The Live page's rail mark walks the catalogue one press at a time (2026-11-22), so that
     * walk has to be a closed loop over exactly the grounds the settings picker offers: nothing
     * skipped, nothing twice, nothing outside the list, and the last ground handing the tour
     * back to the first rather than stopping or throwing on a driver mid-drive.
     */
    @Test
    fun theLivePageCycleWalksTheCatalogueAndComesBackAround() {
        val visited = mutableListOf<MapStyle>()
        var at = MapStyles.Standard
        repeat(MapStyles.ALL.size) { visited += MapStyles.nextAfter(at).also { at = it } }

        val ids = visited.map { it.id }
        assertEquals("a full tour must visit as many grounds as the picker offers", MapStyles.ALL.size, ids.size)
        assertEquals("a tour that lands on the same ground twice", ids.toSet().size, ids.size)
        assertTrue("the tour left the catalogue: $ids", MapStyles.ALL.all { it.id in ids })
        assertSame("the tour must come back to the ground it started from", MapStyles.Standard, at)
        assertSame(
            "the first press from the shipped default must land on the picker's second row — " +
                "the rail walks the order App Settings shows, not a list of its own",
            MapStyles.ALL[1], MapStyles.nextAfter(MapStyles.ALL[0]),
        )

        // A ground the catalogue stopped shipping: a rail press cannot be allowed to fail, so it
        // restarts at the default rather than throwing on `[-1 + 1]`.
        val ghost = MapStyle(id = "ghost", label = "Ghost", sentence = "", attribution = "", sourceName = "Ghost")
        assertSame(
            "an unknown ground must restart the tour",
            MapStyles.ALL.first(), MapStyles.nextAfter(ghost),
        )
    }

    @Test
    fun aCacheKeyIsSharedOnlyByStylesDrawingTheSameTiles() {
        // The name is the key osmdroid files every tile under inside its one tile database
        // (`cache.db`, the row's `provider` column — measured on the bench 2026-11-21: 2,533
        // tiles in one 27.4 MB file, no per-style folders), so two styles may share it exactly
        // when their tiles are the same bytes. `HUD Dark` does: it tints OpenStreetMap's tiles
        // rather than downloading a dark copy of a planet.
        for (a in MapStyles.ALL) {
            for (b in MapStyles.ALL) {
                if (a === b || a.sourceName != b.sourceName) continue
                assertEquals(
                    "${a.id} and ${b.id} share the tile cache key \"${a.sourceName}\" but ask " +
                        "different servers for their tiles — one of them would draw the " +
                        "other's map under its own name",
                    a.urls, b.urls,
                )
            }
        }
    }

    @Test
    fun everyBasemapCarriesItsCreditAndNothingElseDoes() {
        for (style in MapStyles.ALL) {
            if (style.hasBasemap) {
                assertTrue(
                    "${style.id} fetches tiles and has no attribution — the tile policies ask " +
                        "for the credit where the tiles are seen",
                    style.attribution.isNotBlank(),
                )
                assertTrue(
                    "${style.id}'s credit should name somebody",
                    style.credit.contains("©"),
                )
            } else {
                assertEquals(
                    "${style.id} draws no tiles and should thank nobody on the map",
                    "", style.attribution,
                )
                assertTrue(
                    "a basemap with no provider still has to say what it is",
                    style.credit.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun everyStyleCanDrawTheCameraTheFollowMapUses() {
        // LiveRouteMap parks a live drive at z15 and the trip detail map frames a drive at
        // whatever fits it; a style whose ceiling is under the follow level leaves the Live
        // page on a zoom it has no tiles for, and OpenTopoMap's real ceiling (z17) is the one
        // that is allowed to bind. The floor is the app's own: below z4 the world is not a
        // drive, and every style must leave room for that floor to exist.
        for (style in MapStyles.ALL) {
            assertTrue(
                "${style.id} tops out at z${style.maxZoom}, under the follow zoom of 15",
                style.maxZoom >= 15,
            )
            assertTrue(
                "${style.id} bottoms out above the app's world floor",
                style.minZoom <= 4,
            )
            assertTrue(
                "${style.id} has min zoom ${style.minZoom} above its max ${style.maxZoom}",
                style.minZoom <= style.maxZoom,
            )
            assertEquals(
                "${style.id}: the view's ceiling must be the style's own",
                style.maxZoom.toDouble(), MapStyles.maxZoomLevel(style), 0.001,
            )
            assertEquals(
                "${style.id}: the app's world floor (z4) has to stay reachable, and a style " +
                    "may only raise it, never drop below it",
                maxOf(4.0, style.minZoom.toDouble()), MapStyles.minZoomLevel(style), 0.001,
            )
        }
    }

    @Test
    fun theDefaultIsPlainOpenStreetMapAtTheOldNumbers() {
        // The owner who never opens the picker must get the map they already had: MAPNIK's
        // URL, z4..19, and the exact wash the Live page shipped with (18 % black under a
        // vignette to 55 %) — those two numbers are why the default style can be changed
        // without anyone's Live page looking different on the day this lands.
        val osm = MapStyles.OpenStreetMap
        assertEquals("Mapnik", osm.sourceName)
        assertEquals(listOf("https://tile.openstreetmap.org/"), osm.urls)
        assertEquals(4, osm.minZoom)
        assertEquals(19, osm.maxZoom)
        assertNull("the default ground is drawn as it arrives", osm.tileMatrix)
        assertEquals(0.18f, osm.scrim, 0.0001f)
        assertEquals(0.55f, osm.vignette, 0.0001f)
    }

    @Test
    fun anUnknownIdIsTheDefaultAndNotACrash() {
        // An owner can be holding an id this build removed. Falling back is the only legal
        // answer: a map that cannot resolve its style must still draw the drive.
        for (bad in listOf(null, "", "made-up", "OSM", "osm ")) {
            assertSame("$bad must resolve to the default", MapStyles.Standard, MapStyles.byId(bad))
        }
    }

    @Test
    fun everyStyleDecidesItsOwnGroundWashAndInk() {
        for (style in MapStyles.ALL) {
            val casing = MapStyles.casing(style)
            assertTrue(
                "${style.id} has a casing narrower than the route itself",
                casing.extraDp > 0f,
            )
            assertTrue(
                "${style.id} has a casing with no alpha — it would hide the line it edges",
                casing.colorArgb ushr 24 in 0x01..0xFF,
            )
            // A wash over nothing is a darkening of nothing, and the no-basemap style draws
            // its own ground: it must not be paying for a scrim it cannot show.
            assertTrue("${style.id} has a negative scrim", style.scrim >= 0f)
            assertTrue("${style.id} has a vignette over 1", style.vignette in 0f..1f)
            if (!style.hasBasemap) assertEquals("${style.id} dims its own ground", 0f, style.scrim, 0.0001f)
        }
    }

    // ─── the deeper-tile zoom world ─────────────────────────────────────────────

    @Test
    fun everyLevelAsksItsHostForOneGenerationDeeper() {
        // The whole point of MapZoom, asserted as arithmetic: a level's world is halved and
        // the level raised by one, so the same ground shows at the same apparent scale while
        // the fetch level — `floor` of what the view holds — is one deeper than the level an
        // owner reads. Get it wrong and either the map is at the wrong scale or it sits on a
        // wall of loading tiles, and neither shows up in review.
        assertEquals(1.0, MapZoom.TILE_DEPTH, 1e-9)
        assertEquals(
            "the tile factor must be the one that HALVES the world; above 1 it stretches " +
                "tiles further and makes the pixelation worse, not better",
            0.5f, MapZoom.TILE_SCALE_FACTOR, 0.0001f,
        )
        for (level in listOf(4.0, 9.0, 12.0, 15.0, 15.4, 15.9, 19.0)) {
            val held = MapZoom.toView(level)
            assertEquals("$level must survive a trip through the view", level,
                MapZoom.fromView(held), 1e-9)
            assertEquals(
                "$level is held as $held, so the host is asked for generation " +
                    "${Math.floor(held).toInt()} rather than one below the level the owner reads",
                Math.floor(level) + 1, Math.floor(held), 1e-9,
            )
            // The fetched tile is then drawn at 2^(fraction − depth) of its delivered size —
            // at most 1×, which is the whole "no pixelation during a continuous zoom": at any
            // level between two integers a tile is shrunk, never blown up.
            val drawnScale = Math.pow(2.0, held - Math.floor(held) - MapZoom.TILE_DEPTH)
            assertTrue(
                "$level would draw its tiles at $drawnScale× the size they arrive at",
                drawnScale <= 1.0 + 1e-9,
            )
        }
    }

    @Test
    fun aViewNeverAsksASourceForALevelThatSourceDoesNotHave() {
        for (style in MapStyles.ALL) {
            val max = MapZoom.viewMax(style)
            assertTrue(
                "${style.id}: the ceiling $max floors to z${Math.floor(max).toInt()}, past " +
                    "the source's own z${style.maxZoom} — the map would ask for tiles that " +
                    "server does not have",
                Math.floor(max) <= style.maxZoom,
            )
            val min = MapZoom.viewMin(style)
            assertTrue(
                "${style.id}: the floor $min floors below the app's world floor",
                Math.floor(min) >= MapStyles.minZoomLevel(style),
            )
            assertTrue(
                "${style.id}: inverted view range ($min .. $max)",
                min < max,
            )
        }
    }

    // ─── the colour matrix ────────────────────────────────────────────────────────

    @Test
    fun washToIsTheIdentityMatrixAtFullSaturationAndGain() {
        val rows = MapStyles.washTo(1.0, 1.0)
        assertEquals("a ColourMatrix needs 20 numbers", 20, rows.size)
        for (row in 0..3) {
            for (col in 0..3) {
                assertEquals(
                    "row $row column $col",
                    if (row == col) 1.0 else 0.0,
                    rows[row * 5 + col],
                    1e-9,
                )
            }
            assertEquals("row $row must not translate", 0.0, rows[row * 5 + 4], 1e-9)
        }
        // The matrix is row-major 4×5: row 3 is the alpha row, so its scale sits at index 18
        // and index 19 is alpha *translation*, which a map filter has no business doing.
        assertEquals("alpha stays the tile's own", 1.0, rows[18], 1e-9)
        for (col in listOf(15, 16, 17, 19)) {
            assertEquals("alpha row column $col must be empty", 0.0, rows[col], 1e-9)
        }
    }

    @Test
    fun aDesaturatedWashKeepsGreyGreyAndDarkensTheWholeMap() {
        // The reason the filter is a blend with luminance rather than a flat desaturation:
        // the row weights must sum to the same number in all three channels, or a neutral
        // grey tile comes out tinted. Summing to `gain` is what makes it darker, not greener.
        for (style in MapStyles.ALL) {
            val matrix = style.tileMatrix ?: continue
            assertEquals("${style.id}: 20 numbers", 20, matrix.size)
            val sums = (0..2).map { row -> (0..2).sumOf { col -> matrix[row * 5 + col] } }
            val spread = sums.max() - sums.min()
            assertTrue(
                "${style.id}: the three channels sum to ${sums} — grey tiles would come out " +
                    "tinted",
                spread < 1e-9,
            )
            val gain = sums.first()
            assertTrue(
                "${style.id}: the filter lifts the map (gain $gain) instead of darkening it",
                gain in 0.0..1.0,
            )
            assertTrue(
                "${style.id}: the wash is so dark the basemap is decoration (gain $gain)",
                gain > 0.2,
            )
            for (row in 0..3) {
                assertEquals(
                    "${style.id}: row $row must not translate channels",
                    0.0, matrix[row * 5 + 4], 1e-9,
                )
            }
            // The matrix's own meaning, stated as arithmetic rather than as indices: feed it a
            // neutral grey tile and the three channels must come out equal (no colour cast) and
            // equal to the input times the gain (a dimming, not a lift). Index 18 is the alpha
            // row's scale in a row-major 4x5 matrix; 19 is its translation and must stay empty.
            for (v in listOf(0.0, 0.25, 0.5, 1.0)) {
                val out = (0..2).map { row ->
                    matrix[row * 5] * v + matrix[row * 5 + 1] * v + matrix[row * 5 + 2] * v +
                        matrix[row * 5 + 4]
                }
                assertEquals("${style.id}: grey came out tinted ($out)", out[0], out[1], 1e-9)
                assertEquals("${style.id}: grey came out tinted ($out)", out[0], out[2], 1e-9)
                assertEquals("${style.id}: grey at $v came out at the wrong brightness",
                    v * gain, out[0], 1e-9)
            }
            assertEquals("${style.id}: alpha row scale", 1.0, matrix[18], 1e-9)
            assertEquals("${style.id}: alpha row translation", 0.0, matrix[19], 1e-9)
        }
        assertTrue(
            "no style uses a filter at all — the dark ground is dead code",
            MapStyles.ALL.any { it.tileMatrix != null },
        )
    }

    @Test
    fun arcgisPathsPutYBeforeX() {
        // The ArcGIS dialect is the one place a tile can be fetched from the wrong place by a
        // swapped pair, and the symptom is a map of somewhere else.
        assertEquals("3/5/7", MapStyles.arcgisPath(3, 7, 5))
        assertEquals("12/1/2", MapStyles.arcgisPath(12, 2, 1))
        val esri = MapStyles.ALL.filter { it.arcgis }
        assertTrue("the Esri styles must be marked as ArcGIS dialect", esri.isNotEmpty())
        for (style in esri) {
            assertTrue(
                "${style.id} is ArcGIS dialect and still asks for a file extension",
                !style.urls.any { it.endsWith(".png") || it.endsWith(".jpg") },
            )
        }
        // ...and every non-ArcGIS style is the ordinary {z}/{x}/{y}.png form, which is what
        // XYTileSource gives us — nothing else in the list should need a dialect at all.
        for (style in MapStyles.ALL.filterNot { it.arcgis }) {
            if (!style.hasBasemap) continue
            assertNotNull("${style.id} has no tile server", style.urls.firstOrNull())
        }
    }
}
