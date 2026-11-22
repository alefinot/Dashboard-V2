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
 *    "the live map is dark" cannot mean "the detail page is still bright";
 *  * the choice can also be made **on the map itself** — the Live page's rail steps through the
 *    grounds (2026-11-22) through those same two doors, so a pick made mid-drive is a pick.
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

    /**
     * The same choice, made on the map itself (maintainer, 2026-11-22: "add a map icon in the
     * live map that allows the user to change map style in realtime"). A rail press lays the
     * next ground under the drive, so this walks the second door and checks the two properties
     * the first door's tests check for the picker: it writes **both** stores, and it steps
     * through **the catalogue's** order rather than a list the page wrote for itself.
     */
    @Test
    fun theLivePageCanChangeTheGroundUnderItsOwnMap() {
        val hud = moduleSource("LiveHud.kt")
        val rail = hud.substringAfter("internal fun HudRail")
        assertTrue(
            "the rail lost the ground mark — with no menu over a moving map, this is the only " +
                "way to change the ground without leaving the Live page",
            rail.contains("HudGlyphLedger.mark(\"action.map-ground\")") &&
                rail.contains("onCycleGround"),
        )
        assertTrue(
            "the ground mark stopped saying what the press does — a glyph-only control without " +
                "a sentence is invisible to TalkBack, and it is the mark's one job here to say " +
                "which ground is down",
            rail.contains("change the map ground") && rail.contains("groundLabel"),
        )
        assertTrue(
            "the ground mark moved above the eye: the eye stays at the top of the rail, in the " +
                "thumb's first reach, or the control that brings the readings back hides",
            rail.indexOf("action.hud-visibility") in 0 until rail.indexOf("action.map-ground"),
        )

        val page = moduleSource("LiveTab.kt")
        assertTrue(
            "the page stopped asking the catalogue which ground comes next — the order the rail " +
                "walks must be the order App Settings shows, not a second list",
            page.contains("MapStyles.nextAfter(MapStylePref.current)") && !page.contains("MapStyles.ALL"),
        )
        assertTrue(
            "the rail's press no longer writes the stored row — a ground picked mid-drive " +
                "would be forgotten at the next start, and App Settings would still name the old one",
            page.contains("groundSettings.setMapStyle(next.id)"),
        )
        assertTrue(
            "the rail's press no longer moves the live mirror — the map under the finger would " +
                "wait on a DataStore round-trip (or never move at all) instead of changing now",
            page.contains("MapStylePref.set(next.id)"),
        )
        assertTrue(
            "the page stopped naming the ground it just laid down — the glyph can say \"ground\", " +
                "it cannot say which one, and every style's label is different",
            page.contains("groundName = next.label") &&
                page.contains("visible = groundNamed") &&
                page.contains("Box(Modifier.hudCard())"),
        )
        // The chip's text must outlive the flag that shows it, or the fade-out frame has nothing
        // left to say and the name snaps off instead of leaving with the page's own fade. So the
        // name is only ever written by a press, and the window is re-armed off the name itself: a
        // second press restarts the countdown rather than inheriting the first one's.
        val clearAt = page.indexOf("groundNamed = false")
        assertTrue(
            "the name is cleared along with its visibility, or the window is no longer re-armed " +
                "by the name itself",
            clearAt > 0 &&
                page.indexOf("LaunchedEffect(groundName)") < clearAt &&
                page.indexOf("delay(GROUND_NAME_MS)") < clearAt &&
                page.indexOf("groundName = \"\"") < 0,
        )
        // Recorded because it is the only way to re-check a 2.4 s chip from a bench: one
        // `uiautomator dump` on this page costs 2-3 s (the window never goes idle — the map moves
        // the arrow every fix), so a single timed dump says nothing about a name this short-lived.
        // A burst across the window does, and it is what proved the name reaches accessibility.
        assertTrue(
            "the note that tells the next person how to actually catch this chip on a bench is gone",
            page.contains("burst of twelve") && page.contains("costs 2-3 s"),
        )
        assertTrue(
            "the ground's name no longer puts itself away — a name that stays is a claim about " +
                "a map somebody else may have changed since",
            page.contains("private const val GROUND_NAME_MS") &&
                page.contains("groundNamed = false") &&
                page.contains("delay(GROUND_NAME_MS)"),
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
