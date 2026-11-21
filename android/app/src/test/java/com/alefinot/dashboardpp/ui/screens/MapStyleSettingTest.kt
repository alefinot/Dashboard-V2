package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map-ground picker, end to end (maintainer, 2026-11-21: "add different map styles and
 * allow the user to choose between them in the app settings").
 *
 * The lane crosses five files that never import each other — the settings page, the DataStore
 * row, the app-wide mirror, the two map holders, and the page that shows the credit — and a
 * future edit can delete any one of them without breaking a line of compilation in the rest.
 * So this walks the whole lane and checks the two properties the feature was for:
 *
 *  * the choice is **stored** and re-seeded at boot, so it survives a restart rather than
 *    resetting to the default every launch;
 *  * the choice reaches **every** map surface and no map keeps its own hard-coded basemap, so
 *    "the live map is dark" cannot mean "the detail page is still bright".
 *
 * These assertions read source text with plain `contains`, not regular expressions: every
 * thing being looked for is a literal call site, and a pattern for a literal is just a place
 * for an escaping mistake to hide. The catalogue's own data rules — unique cache folders,
 * attribution, zoom ceilings, the tile generations, the colour matrix — are behaviour, and
 * are tested where they live, in `MapStyleContractTest`.
 */
class MapStyleSettingTest {

    @Test
    fun thePickerSitsOnAppSettingsAndWritesTheStore() {
        val page = moduleSource("SettingsScreen.kt")
        assertTrue(
            "the MAP GROUND label left App Settings — the choice has nowhere to be made",
            page.contains("\"MAP GROUND\""),
        )
        assertTrue(
            "the row lost its single-choice menu, or stopped offering the catalogue itself",
            page.contains("HudSelectMenu(") && page.contains("items = MapStyles.ALL"),
        )
        assertTrue(
            "picking a ground no longer writes the store — the choice would not survive the " +
                "page leaving composition",
            page.contains("settings.setMapStyle(picked.id)"),
        )
        assertTrue(
            "picking a ground no longer moves the live mirror — the Live page behind this one " +
                "would keep drawing the old ground until the app restarted",
            page.contains("MapStylePref.set(picked.id)"),
        )
        assertTrue(
            "the group left the one-open-at-a-time rule the page's other groups follow",
            page.contains("openGroup == \"map\"") &&
                page.contains("SettingsLayout.tapOpen(openGroup, \"map\")"),
        )
    }

    @Test
    fun theStoreKeepsTheIdAndWarmsItAtBoot() {
        val store = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/Stores.kt")
        assertTrue(
            "the map_style key left the store — every owner's choice would reset",
            store.contains("stringPreferencesKey(\"map_style\")"),
        )
        assertTrue(
            "mapStyle is not warmed at boot — the first map would pay a cold DataStore read " +
                "the splash already has time for",
            store.contains("mapStyle.first()"),
        )
        assertTrue(
            "setMapStyle went missing, or stopped writing what the picker sends",
            store.contains("suspend fun setMapStyle(id: String)") &&
                store.contains("it[KEY_MAP_STYLE] = id"),
        )
    }

    @Test
    fun theMirrorIsSeededAtBootAndFollowsTheStoreAfterwards() {
        val boot = moduleFile("src/main/java/com/alefinot/dashboardpp/MainActivity.kt")
        assertTrue(
            "the ground is no longer mirrored during the splash — the first map of a cold " +
                "start would draw the default and swap basemaps a beat later",
            boot.contains("MapStylePref.set(settings.mapStyle.first())"),
        )
        val shell = moduleSource("ConnectedTabs.kt")
        assertTrue(
            "the shell stopped collecting mapStyle — a write from anywhere but the picker " +
                "would leave every map on the old ground",
            shell.contains("settings.mapStyle.collectAsStateWithLifecycle") &&
                shell.contains("LaunchedEffect(mapStyle) { MapStylePref.set(mapStyle) }"),
        )
    }

    @Test
    fun everyMapFollowsTheChosenGround() {
        val live = mapSource("LiveRouteMap.kt")
        assertTrue(
            "the Live map stopped reading the ground as state — a pick on the settings page " +
                "would not move a map that is on screen",
            live.contains("val style = MapStylePref.current"),
        )
        assertTrue(
            "the Live map builds its view without a style, or stopped re-applying it on change",
            live.contains("holder.build(ctx, style)") &&
                live.contains("holder.applyStyle(map, style)"),
        )
        val detail = mapSource("RouteMap.kt")
        assertTrue(
            "a sealed drive's detail map stopped following the ground — two surfaces of one " +
                "app drawing two different worlds",
            detail.contains("val style = MapStylePref.current") &&
                detail.contains("holder.build(ctx, style)") &&
                detail.contains("holder.applyTrack(map, points, style)"),
        )
        // The point of the feature: no surface is allowed to keep a basemap of its own.
        for ((name, src) in listOf("LiveRouteMap.kt" to live, "RouteMap.kt" to detail)) {
            assertFalse(
                "$name picked a tile source for itself again",
                src.contains("TileSourceFactory"),
            )
            assertFalse(
                "$name hard-coded a zoom ceiling again — the ceiling belongs to the style " +
                    "(OpenTopoMap stops at z17)",
                src.contains("setMaxZoomLevel(") || src.contains("setMinZoomLevel("),
            )
        }
    }

    @Test
    fun theWashAndTheCreditLiveWithTheMapNotOverIt() {
        val page = moduleSource("LiveTab.kt")
        assertFalse(
            "the cockpit wash is back in Compose, painted over the whole map slot — which is " +
                "how the drive's own line and dots got dimmed along with the tiles " +
                "(ui/map/MapChrome.kt puts it under the vectors now)",
            page.contains("drawRect(Color.Black.copy(alpha = 0.18f)"),
        )
        assertTrue(
            "the Live page lost the provider's credit — the tile policies ask for the " +
                "attribution where the tiles are seen",
            page.contains("MapCredit(") && page.contains("MapStylePref.current"),
        )
        val chrome = mapSource("MapChrome.kt")
        assertTrue(
            "the ground overlay is no longer inserted at the bottom of the overlay list, so it " +
                "is above the route again",
            chrome.contains("manager.add(0, made)"),
        )
        assertTrue(
            "a style with no basemap would still fetch tiles — the overlay that asks for them " +
                "is the one being switched off",
            chrome.contains("tiles.isEnabled = style.hasBasemap"),
        )
        val live = mapSource("LiveRouteMap.kt")
        assertTrue(
            "route lines went back to index 0, which is where the wash now sits",
            live.contains("chrome.lineInsertAt"),
        )
        assertTrue(
            "a piece lost its casing — the cyan line has no edge on aerial imagery",
            live.contains("casings[i].setPoints(mirror)") && live.contains("casings[i].addPoint(point)"),
        )
    }

    @Test
    fun zoomLevelsAreTranslatedToTheDeeperTileWorld() {
        val chrome = mapSource("MapChrome.kt")
        assertTrue(
            "the applier stopped halving the world tile size — every tile on screen goes back " +
                "to being enlarged rather than shrunk, which is the pixelation this was for",
            chrome.contains("setTilesScaleFactor(MapZoom.TILE_SCALE_FACTOR)"),
        )
        assertTrue(
            "the applier stopped owning the DPI tile scaling — two callers writing osmdroid's " +
                "one global TileSystem size is how maps start disagreeing about scale",
            chrome.contains("setTilesScaledToDpi(true)"),
        )
        assertTrue(
            "the view's zoom bounds went back to being set in the levels an owner reads",
            chrome.contains("MapZoom.viewMin(style)") && chrome.contains("MapZoom.viewMax(style)"),
        )
        val live = mapSource("LiveRouteMap.kt")
        assertTrue(
            "the follow's one zoom is no longer translated — the Live page would frame the " +
                "drive one tile generation tighter than the rest of the app",
            live.contains("setZoom(MapZoom.toView(FOLLOW_ZOOM))"),
        )
        val detail = mapSource("RouteMap.kt")
        assertTrue(
            "the detail map's framing stopped translating — a track with no extent would be " +
                "framed at the wrong scale, and its own ceiling would bite one level late",
            detail.contains("MapZoom.toView(plan.zoom)") &&
                detail.contains("MapZoom.fromView(view.maxZoomLevel)"),
        )
        // Neither map may write the global tile size itself: it is one number per process.
        for ((name, src) in listOf("LiveRouteMap.kt" to live, "RouteMap.kt" to detail)) {
            assertFalse(
                "$name sizes tiles for itself again — osmdroid's tile size is a global, so a " +
                    "second writer silently changes every other map in the app",
                src.contains("setTilesScaledToDpi") || src.contains("setTilesScaleFactor"),
            )
        }
    }

    @Test
    fun theCacheCanBeClearedFromThePageThatChoseTheGround() {
        val page = moduleSource("SettingsScreen.kt")
        assertTrue(
            "the tile-cache button left the Live Map card",
            page.contains("\"CLEAR TILE CACHE\""),
        )
        assertTrue(
            "the button no longer asks first — deleting every cached tile should not be one " +
                "careless tap deep (the page's rule for destructive actions)",
            page.contains("showConfirmTiles = true") &&
                page.contains("if (showConfirmTiles)") &&
                page.contains("OsmdroidSetup.clearTileCache()"),
        )
        val setup = mapSource("RouteMap.kt")
        assertTrue(
            "clearTileCache stopped emptying the tile store itself",
            setup.contains("fun clearTileCache(): Long") &&
                setup.contains("getOsmdroidTileCache()"),
        )
        // The count this button used to print was a count of FILES, and the store is one
        // SQLite database: the bench cleared 27.4 MB of tiles and the card said `Removed 2
        // cached tiles` (2026-11-21). What the owner is told is bytes, so bytes is what the
        // door has to hand back and what the card has to format.
        assertTrue(
            "the card went back to counting something other than bytes",
            page.contains("hudBytes("),
        )
    }

    // ─── helpers (same walking-up search as RouteQualitySettingTest) ────────────────

    private fun moduleSource(name: String): String =
        moduleFile("src/main/java/com/alefinot/dashboardpp/ui/screens/$name")

    private fun mapSource(name: String): String =
        moduleFile("src/main/java/com/alefinot/dashboardpp/ui/map/$name")

    private fun moduleFile(relative: String): String {
        val f = File(moduleDir(), relative)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    private fun moduleDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/ui/screens"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (cand in listOf(File(dir, "android/app"), File(dir, "app"), dir)) {
                if (File(cand, marker).isDirectory) return cand
            }
            dir = dir.parentFile
        }
        error("no app module found above ${System.getProperty("user.dir")}")
    }
}
