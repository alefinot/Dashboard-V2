package com.alefinot.dashboardpp.ui.map

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import androidx.compose.runtime.mutableStateOf
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import java.util.Collections

/**
 * The ground a drive is drawn on, as the owner chooses it (App Settings → Live Map).
 *
 * osmdroid draws **raster** tiles, so a "map style" here is a tile server plus the ink
 * decisions that go with it — and both halves matter. Swap only the tiles and the app gets
 * a bright, busy basemap fighting the cyan route for the same pixels, which is what the
 * Live page looked like before any of this: a full-screen wash (18 % black under a
 * vignette to 55 %) painted *over* the whole map, so it dimmed the track, the dots and the
 * tiles alike, because Compose can only paint over a platform view as one piece. A style
 * therefore carries the ground, the wash over it ([MapStyle.scrim] / [MapStyle.vignette],
 * drawn by [MapGroundOverlay] *under* the vectors) and the casing the route needs to stay
 * legible on it ([MapCasing]). Satellite ground wants a heavier dark casing and almost no
 * wash; the dark style wants the opposite, a cyan glow rather than a black outline.
 *
 * **Every source here is keyless.** CARTO's dark basemap would suit this palette better than
 * anything on the list, and it is gone as a keyless option: since 25 September 2026
 * `basemaps.cartocdn.com` answers a keyless request with a placeholder tile (its ETag says
 * `wm-…` — measured, not remembered), so it needs a key, and a key cannot live in this
 * repository (AGENTS rule 21). `Implementation plans/` records the same trade for the
 * vector-tile route: OpenFreeMap serves free dark *vector* tiles, and osmdroid cannot draw
 * a vector tile, so that road is a renderer swap, not a style.
 *
 * Two numbers here are load-bearing beyond looks:
 * - [MapStyle.sourceName] is osmdroid's cache **key**: it names the style inside the one
 *   tile database — `cache.db`, one row per tile, the style in its `provider` column and the
 *   tile in a packed `z/x/y` key (checked in 6.1.20) — not a folder of `name/z/x/y.png`
 *   files, though the name is used that way by the older layout. Two styles may share it only
 *   when they fetch the same image, which is exactly why `HUD Dark` names itself `Mapnik` like
 *   `OpenStreetMap` does: it darkens the same tiles, and re-downloading a planet to tint them
 *   would be absurd. Two styles with *different* URLs sharing a name would silently serve one
 *   provider's tiles under another's label — `MapStyleContractTest` refuses it.
 * - [MapStyle.maxZoom] replaces the `19.0` that used to be hard-coded in both map views.
 *   OpenTopoMap stops at z17; the app clamps its view to what the source has, which is what
 *   keeps a basemap from being asked for a level its server does not serve. (Past the ceiling
 *   osmdroid does not fail — it enlarges the last row it has: bench, 2026-11-21, Terrain at
 *   view 17.999 still drew its z17 tiles, magnified, with no loading tiles on screen.)
 *   A ceiling is **measured, not read off the service's own `lods` list**, because that list
 *   describes the tile schema, not the cache: `World_Street_Map` advertises z23 and answers
 *   z20 with a card. The measurement is a byte hash across zooms and places, and it separates
 *   three answers that all look like failure on screen (all of these measured 2026-11-22 with
 *   this app's own User-Agent):
 *   - one image, byte-identical across *services*, places and levels (Esri: 2,521 B, a grey
 *     card with something drawn on it) — the host's generic "no tiles at this level". Above
 *     it, nothing, anywhere: that is a ceiling. Esri's Canvas grey bases and World Hillshade
 *     stop at z16 this way, and Imagery/Street/Topo at z19.
 *   - a **flat single colour** matching the basemap's own land fill (Esri Street: 2,420 B) —
 *     a real, served, deliberately featureless tile: that tile has nothing in it at that
 *     level. Open country past z16 on `Street Atlas` is this, and so is `Relief` over a
 *     field at z19. It is the map being honest, not a hole, and a ceiling must not be set to
 *     "hide" it: Milan, Berlin and NYC all serve real detail at those same levels.
 *   - an **all-transparent** PNG (Esri's reference layers: 872 B, alpha 0 at every sampled
 *     pixel) — the layer's own "nothing here", which draws nothing and needs no clamp.
 * - [MapStyle.overlays] is a second kind of ground: tile layers painted *over* the base
 *   tiles, which is the only way to get labels on aerial imagery in a raster renderer.
 *   They are not styles — they never enter [ALL], the picker or the rail — and they share
 *   the cache-key rule with styles for the same reason.
 */
enum class MapGround {
    /** A pale basemap: the route needs a dark casing to hold its edge. */
    LIGHT,

    /** A dark basemap (or no basemap): the route glows instead of being outlined. */
    DARK,

    /** Aerial imagery: busy and uneven, so the casing goes darker and wider. */
    IMAGE,
}

/**
 * The route's outline for one kind of ground: the ink, and how much wider than the route
 * itself it is drawn in dp. It is a real second [org.osmdroid.views.overlay.Polyline] —
 * osmdroid's polyline has exactly one paint (6.1.20 exposes `getOutlinePaint()` and
 * nothing else), so "casing" costs one more path per recorded piece, and the map holders
 * only spend it on maps big enough to show it.
 */
class MapCasing(val colorArgb: Int, val extraDp: Float)

/**
 * One tile layer painted **over** a style's base tiles.
 *
 * It exists for one ground: aerial imagery with roads and place names on it. A raster
 * renderer cannot ask an image for its labels, so `Hybrid` stacks Esri's two reference
 * layers on top of World Imagery — the same composition Esri's own viewer makes. A layer is
 * deliberately not a [MapStyle]: it has no label, no wash and no place in the picker or the
 * rail, and calling it a style would let it wander into [MapStyles.ALL] and be offered as a
 * ground that draws nothing on its own.
 *
 * It carries its own [sourceName] because it fetches different bytes than its base does:
 * two layers of one ground sharing a cache folder would put label tiles in the imagery's
 * rows and show a driver a road name pasted on a field.
 */
class MapTileLayer(
    /** Key into [MapStyles]' source cache. Distinct from every style id and every other layer. */
    val id: String,
    /** osmdroid's tile-cache key — the same rule as [MapStyle.sourceName]. */
    val sourceName: String,
    val urls: List<String>,
    /** ArcGIS's `{z}/{y}/{x}` dialect, as in [MapStyle.arcgis]. */
    val arcgis: Boolean = true,
    val minZoom: Int = 4,
    val maxZoom: Int = 19,
    /** Whose work this is. Shown as part of the ground's credit, never on its own. */
    val attribution: String,
)

/**
 * One entry in the style picker. Plain data on purpose: the catalogue is asserted by a JVM
 * test, and constructing an [ColorMatrixColorFilter] in a field initialiser would put a
 * `android.graphics` stub between the test and the numbers ([tileMatrix] is the data, and
 * [tileFilter] builds the real thing when the map asks for it).
 */
class MapStyle(
    /** Persisted in DataStore. Never renamed without a carry — an owner's choice must survive. */
    val id: String,
    /** The row in the picker. */
    val label: String,
    /** The one line under the picker that says what choosing it does. */
    val sentence: String,
    /** The provider's credit, drawn on the map. Empty means there is nobody to credit. */
    val attribution: String,
    /** osmdroid's tile-cache key — see the note at the top of the file. */
    val sourceName: String,
    /** Tile URL bases. Empty is the [MapGround.NONE]-by-way-of-no-basemap style. */
    val urls: List<String> = emptyList(),
    /** ArcGIS serves `{z}/{y}/{x}` with no extension; every other source here is `{z}/{x}/{y}.png`. */
    val arcgis: Boolean = false,
    val minZoom: Int = 4,
    val maxZoom: Int = 19,
    val ground: MapGround = MapGround.LIGHT,
    /**
     * Tile layers painted over these tiles, in order, and **under** the wash — so the scrim
     * and the vignette dim a label exactly as much as they dim the road it names. Empty for
     * every ground but `Hybrid`. See [MapTileLayer].
     */
    val overlays: List<MapTileLayer> = emptyList(),
    /** A colour matrix for the tiles themselves, or null to paint them as they come. */
    val tileMatrix: List<Double>? = null,
    /** Flat black over the tiles, 0..1. */
    val scrim: Float = 0.18f,
    /** Black at the map's corners, 0..1. */
    val vignette: Float = 0.55f,
) {
    /** False only for the style that draws no tiles at all. */
    val hasBasemap: Boolean get() = urls.isNotEmpty()

    /** The provider credit, or the honest sentence for a map with no provider. */
    val credit: String get() = attribution.ifEmpty { "No basemap — this drive is drawn on the app's own ground" }
}

object MapStyles {

    /** The shipped default, and the fallback for a value this build does not know. */
    const val STANDARD_ID = "osm"

    /** What both map views clamp to at the low end: below z4 the world is not a drive. */
    private const val WORLD_FLOOR = 4

    val OpenStreetMap = MapStyle(
        id = "osm",
        label = "OpenStreetMap",
        sentence = "The full OSM map: every street name, every lane.",
        attribution = "© OpenStreetMap contributors",
        sourceName = "Mapnik",
        urls = listOf("https://tile.openstreetmap.org/"),
        maxZoom = 19,
        ground = MapGround.LIGHT,
        // The wash the Live page shipped with 2026-11-19 → 2026-11-21, kept exactly, so the
        // owner who never opens the picker sees the map they already have.
        scrim = 0.18f,
        vignette = 0.55f,
    )

    val Quiet = MapStyle(
        id = "quiet",
        label = "Quiet",
        sentence = "The same OSM data, drawn quietly: less ink, more route.",
        attribution = "© OpenStreetMap contributors",
        sourceName = "Wikimedia",
        urls = listOf("https://maps.wikimedia.org/osm-intl/"),
        maxZoom = 19,
        ground = MapGround.LIGHT,
        scrim = 0.12f,
        vignette = 0.50f,
    )

    val HudDark = MapStyle(
        id = "dark",
        label = "HUD Dark",
        sentence = "The OSM map through a dark filter, built for the Dashboard++ palette.",
        attribution = "© OpenStreetMap contributors",
        // Same tiles as OpenStreetMap, deliberately: the name is the cache folder, and a
        // tinted copy of a planet is not a second planet.
        sourceName = "Mapnik",
        urls = listOf("https://tile.openstreetmap.org/"),
        maxZoom = 19,
        ground = MapGround.DARK,
        tileMatrix = washTo(0.85, 0.62),
        scrim = 0.10f,
        vignette = 0.45f,
    )

    /**
     * Esri's Canvas grey base — the ground `HUD Dark` only imitates. It is genuinely drawn
     * dark rather than dimmed, and it carries almost no text, which is the property a driver
     * actually wants: the route's own labels become the only words on the map.
     *
     * z16 is its ceiling, measured at four places (see the header note): Milan, Berlin, NYC
     * and open country all answer z17 with the same 2,521 B card.
     */
    val Graphite = MapStyle(
        id = "graphite",
        label = "Graphite",
        sentence = "A charcoal base drawn dark at source, with almost no labels to fight the route.",
        attribution = "© Esri, HERE, Garmin · © OpenStreetMap contributors",
        sourceName = "EsriDarkGray",
        urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/",
        ),
        arcgis = true,
        maxZoom = 16,
        ground = MapGround.DARK,
        // Already the colour this dashboard wants: a heavy wash would just be a dimmer map.
        scrim = 0.06f,
        vignette = 0.40f,
    )

    val StreetAtlas = MapStyle(
        id = "street",
        label = "Street Atlas",
        sentence = "A muted street atlas: roads read first, everything else waits.",
        attribution = "© Esri, Garmin, FAO, NOAA, USGS · © OpenStreetMap contributors",
        sourceName = "EsriStreet",
        urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/",
        ),
        arcgis = true,
        // Real detail at least to z19 in every city measured (Milan, Berlin, NYC); the flat
        // khaki tile open country gives back past z16 is this basemap's own featureless tile,
        // not a missing one — the same zoom serves a city in full. Do not "fix" the ceiling
        // for it: the header note keeps the two apart.
        maxZoom = 19,
        ground = MapGround.LIGHT,
        scrim = 0.14f,
        vignette = 0.50f,
    )

    val Satellite = MapStyle(
        id = "satellite",
        label = "Satellite",
        sentence = "Aerial imagery: the route sits on the ground itself.",
        attribution = "© Esri, Maxar, Earthstar Geographics",
        sourceName = "EsriImagery",
        urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/",
        ),
        arcgis = true,
        maxZoom = 19,
        ground = MapGround.IMAGE,
        // Imagery is already the darkest ground here and it is the busiest: a heavy wash
        // would only muddy it, and the casing is what makes the line read.
        scrim = 0.05f,
        vignette = 0.35f,
    )

    // ─── the layers `Hybrid` stacks over its imagery ──────────────────────────────
    //
    // Declared before the style that uses them, and kept out of ALL: these are the label
    // half of one ground, not grounds of their own. Measured with this app's User-Agent
    // 2026-11-22 — roads are real to z18 in Milan (5.9 KB) and z19 in Berlin (6.0 KB), and
    // the same layers answer z17+ over open country with the 872 B all-transparent tile, so
    // they need no clamp of their own: where they have nothing to say they draw nothing.

    private val EsriRefRoads = MapTileLayer(
        id = "esri-ref-roads",
        sourceName = "EsriRefRoads",
        urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/",
        ),
        attribution = "© Esri, HERE, Garmin · © OpenStreetMap contributors",
    )

    private val EsriRefPlaces = MapTileLayer(
        id = "esri-ref-places",
        sourceName = "EsriRefPlaces",
        urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/",
        ),
        attribution = "© Esri, HERE, Garmin · © OpenStreetMap contributors",
    )

    /**
     * Aerial imagery with the road network and place names painted on it — the ground the
     * owner asked for when they said they wanted satellite and street names at once (2026-11-22).
     *
     * Three tile fetches per tile instead of one, and about twice the bytes (Milan z15:
     * 35 KB of image, 31 KB of roads, 0.9 KB of places). That is the price of a label on an
     * image in a raster renderer, and it is why this is a choice rather than the default.
     */
    val Hybrid = MapStyle(
        id = "hybrid",
        label = "Hybrid",
        sentence = "Aerial imagery with the road network and the place names drawn over it.",
        attribution = "© Esri, Maxar, Earthstar Geographics · © OpenStreetMap contributors",
        // The same imagery `Satellite` fetches, on purpose: one planet, cached once.
        sourceName = "EsriImagery",
        urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/",
        ),
        arcgis = true,
        maxZoom = 19,
        ground = MapGround.IMAGE,
        scrim = 0.05f,
        vignette = 0.35f,
        overlays = listOf(EsriRefRoads, EsriRefPlaces),
    )

    val Terrain = MapStyle(
        id = "topo",
        label = "Terrain",
        sentence = "OpenTopoMap: contours, hillshade and spot heights.",
        attribution = "© OpenStreetMap contributors · © OpenTopoMap (CC-BY-SA)",
        sourceName = "OpenTopoMap",
        urls = listOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/",
        ),
        // The source's own ceiling (TileSourceFactory's OpenTopo entry, 6.1.20). Past it the
        // map fills with nothing, which the owner reads as a broken map, not as a missing tile.
        maxZoom = 17,
        ground = MapGround.LIGHT,
        scrim = 0.14f,
        vignette = 0.50f,
    )

    /**
     * Esri's topographic map: contours, spot heights, indexed relief and cartographic road
     * shields. It is here because [Terrain] is the one ground that stops early — OpenTopoMap
     * ends at z17, so a driver who zooms in on a mountain pass on a phone is over the last
     * level that source has. This one serves real tiles to z19 (measured: Milan z18 8.1 KB,
     * Berlin and NYC z19 7.5 KB / 8.8 KB), so the same pinch keeps giving detail.
     */
    val Relief = MapStyle(
        id = "esritopo",
        label = "Relief",
        sentence = "Contours, spot heights and shaded relief — and it keeps zooming past Terrain.",
        attribution = "© Esri, HERE, Garmin, USGS, GEBCO · © OpenStreetMap contributors",
        sourceName = "EsriTopo",
        urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/",
        ),
        arcgis = true,
        maxZoom = 19,
        ground = MapGround.LIGHT,
        // Pale and busy, like every contour sheet: a little more wash than a street map wants.
        scrim = 0.14f,
        vignette = 0.50f,
    )

    val HudGround = MapStyle(
        id = "none",
        label = "HUD Ground",
        sentence = "No basemap at all: the drive on the HUD's own ground, and no tiles to fetch.",
        attribution = "",
        sourceName = "None",
        urls = emptyList(),
        maxZoom = 19,
        ground = MapGround.DARK,
        scrim = 0f,
        vignette = 0.30f,
    )

    /**
     * The picker's order, and the order the Live page's rail walks — across a narrowed cycle or
     * all of it: pale street maps, then the dark grounds, then imagery with its labelled twin,
     * then relief, then no ground at all — each new ground placed beside the one it resembles
     * rather than appended, so an owner who has learned the cycle keeps the neighbours they
     * learned. [cycleFrom] returns a chosen subset **in this order**, so narrowing the tour
     * removes grounds without ever reordering the ones that stay.
     */
    val ALL: List<MapStyle> = Collections.unmodifiableList(
        listOf(
            OpenStreetMap, Quiet, HudDark, Graphite, StreetAtlas,
            Satellite, Hybrid, Terrain, Relief, HudGround,
        ),
    )

    /** The style for a stored id. Unknown, null or absent is the shipped default. */
    fun byId(id: String?): MapStyle = ALL.firstOrNull { it.id == id } ?: OpenStreetMap

    /** The fallback every caller starts from, so nobody has to know the id constant. */
    val Standard: MapStyle get() = byId(STANDARD_ID)

    /**
     * The ground one press past [current], wrapping at the end of the walk — the Live page's
     * rail mark steps through the grounds rather than opening a menu over a moving map
     * (maintainer, 2026-11-22).
     *
     * The walk is [cycle] when the owner has narrowed it (App Settings → Live Map → RAIL CYCLE,
     * 2026-11-23): the rail then steps through only the grounds kept in the cycle, still in the
     * catalogue's order — [cycleFrom] guarantees a subset carries [ALL]'s order, so "which
     * ground comes after Terrain" has one answer wherever it is asked. An empty cycle is not
     * the owner saying "cycle nothing": it means they never narrowed the rail, and the walk is
     * all of [ALL] — which is also the safe reading of a corrupt or stale stored list.
     *
     * It lives next to [ALL] on purpose: a second list somewhere in a screen would be a second
     * truth about the order. Unknown input restarts the walk at its first ground instead of
     * throwing — a rail press must never be able to fail — so a ground dropped from the cycle
     * simply lands the next press on the first kept one.
     */
    fun nextAfter(current: MapStyle, cycle: List<MapStyle> = emptyList()): MapStyle {
        val walk = if (cycle.isEmpty()) ALL else cycle
        val at = walk.indexOfFirst { it.id == current.id }
        return if (at < 0) walk.first() else walk[(at + 1) % walk.size]
    }

    /**
     * The rail's cycle from stored ids: the catalogue filtered to what the owner kept, in the
     * catalogue's order. Ids this build does not know drop out silently — the same rule
     * [byId] applies to a stored style, so removing a style someday cannot strand a phone's
     * cycle on a ground that no longer draws. A cycle that resolves to nothing is returned as
     * nothing and [nextAfter] reads that as "walk everything", which is also what a row that
     * was never narrowed holds.
     */
    fun cycleFrom(ids: Collection<String>): List<MapStyle> {
        if (ids.isEmpty()) return emptyList()
        val kept = ids.toSet()
        return ALL.filter { it.id in kept }
    }


    /** The low zoom bound for a style: its own, never below the app's world floor. */
    fun minZoomLevel(style: MapStyle): Double = maxOf(WORLD_FLOOR.toDouble(), style.minZoom.toDouble())

    fun maxZoomLevel(style: MapStyle): Double = style.maxZoom.toDouble().coerceAtLeast(1.0)

    /**
     * The route's casing for one ground: dark ink on a pale map, darker and wider on aerial
     * imagery, and a translucent cyan bloom on a dark one — three grounds, three different
     * ways for the same cyan line to lose its edge. The route itself stays the app's cyan on
     * every style: it is the one thing on the map that is always the drive.
     *
     * The inks are literal ARGB rather than palette entries on purpose: `HudColors` is
     * Compose's palette for glass and text, and none of those three values is a casing.
     */
    fun casing(style: MapStyle): MapCasing = when (style.ground) {
        MapGround.LIGHT -> MapCasing(0xB3000000.toInt(), 3.0f)
        MapGround.IMAGE -> MapCasing(0xCC000000.toInt(), 3.5f)
        MapGround.DARK -> MapCasing(0x8C00E5FF.toInt(), 4.0f)
    }

    // ─── osmdroid's side ───────────────────────────────────────────────────────

    /**
     * Tile sources are built once per style and kept: a source holds no state this app
     * cares about, but `MapView.setTileSource` clears the in-memory tile cache, and
     * rebuilding a source on a style change is a free reason to re-decode tiles.
     */
    private val sources = HashMap<String, OnlineTileSourceBase>()

    fun tileSource(context: Context, style: MapStyle): OnlineTileSourceBase = synchronized(sources) {
        sources.getOrPut(style.id) {
            newSource(
                style.sourceName, style.urls, style.arcgis, style.attribution,
                style.minZoom, style.maxZoom,
            )
        }
    }

    /**
     * The same construction, from a layer that paints over a style's tiles. One cache for
     * both, because the two are keyed the same way: a style by its id, a layer by its own.
     */
    fun tileSource(context: Context, layer: MapTileLayer): OnlineTileSourceBase = synchronized(sources) {
        sources.getOrPut(layer.id) {
            newSource(
                layer.sourceName, layer.urls, layer.arcgis, layer.attribution,
                layer.minZoom, layer.maxZoom,
            )
        }
    }

    private fun newSource(
        sourceName: String,
        urls: List<String>,
        arcgis: Boolean,
        attribution: String,
        minZoom: Int,
        maxZoom: Int,
    ): OnlineTileSourceBase {
        val bases = urls.toTypedArray()
        return if (arcgis) {
            ArcGisTileSource(sourceName, bases, attribution, minZoom, maxZoom)
        } else {
            XYTileSource(
                sourceName, minZoom, maxZoom, 256, ".png",
                if (bases.isEmpty()) arrayOf(FALLBACK_URL) else bases,
                attribution,
            )
        }
    }

    /**
     * A no-basemap style still needs a provider the view can be left pointing at: osmdroid
     * builds its tile provider in the `MapView` constructor and `setTileSource` is the only
     * door that replaces it. The overlay that draws the tiles is switched off instead, so
     * this URL is never asked for anything — it exists so the provider is never null.
     */
    private const val FALLBACK_URL = "https://tile.openstreetmap.org/"

    private val filters = HashMap<String, ColorFilter?>()

    /** The tiles' colour filter, or null to paint them as they arrive. */
    fun tileFilter(style: MapStyle): ColorFilter? = synchronized(filters) {
        if (filters.containsKey(style.id)) {
            filters[style.id]
        } else {
            val made = style.tileMatrix?.let { ColorMatrixColorFilter(it.map(Double::toFloat).toFloatArray()) }
            filters[style.id] = made
            made
        }
    }

    /**
     * A tile filter that grays the basemap down and dims it, as a colour matrix rather than as
     * a second render.
     *
     * `washTo(1.0, 1.0)` is the identity. `saturation` blends each channel between the map's
     * own colours (1.0) and pure luminance (0.0), and `gain` scales the result — so
     * `washTo(0.85, 0.62)` is "keep most of the map's colour, draw it at two thirds
     * brightness". The blend is what keeps it a map: a fully desaturated basemap flattens a
     * motorway and a lane onto one grey, and the eye finds a road by its colour as much as by
     * its width.
     *
     * The arithmetic is public because it is checkable. Writing each row as
     * `gain * (saturation on the diagonal + (1 - saturation) · luminance)` makes all three
     * rows sum to exactly `gain`, since the Rec. 709 weights sum to 1 — which is what makes
     * grey stay grey (no colour cast) and the whole thing darken rather than tint.
     * `MapStyleContractTest` measures that instead of trusting a matrix written on a screen.
     */
    internal fun washTo(saturation: Double, gain: Double): List<Double> {
        val luminance = doubleArrayOf(0.2126, 0.7152, 0.0722)
        val grey = 1.0 - saturation
        val rows = ArrayList<Double>(20)
        for (row in 0..2) {
            for (col in 0..3) {
                rows.add(
                    when {
                        col == 3 -> 0.0
                        else -> gain * (if (col == row) saturation else 0.0) +
                            gain * grey * luminance[col]
                    },
                )
            }
            rows.add(0.0) // no channel translation: the filter darkens, it does not lift
        }
        rows.addAll(listOf(0.0, 0.0, 0.0, 1.0, 0.0)) // alpha is nobody's business
        return rows
    }

    /**
     * ArcGIS's tile order is `{z}/{y}/{x}` and its URLs carry no file extension, which is
     * not the dialect osmdroid's own [XYTileSource] speaks (`{z}/{x}/{y}.png`). Only the
     * URL changes: the on-disk cache key comes from the tile index, not the URL, so the
     * swap cannot confuse two styles' caches with each other.
     *
     * The path is written by [arcgisPath] so the one line that could put x and y the wrong
     * way round is a number the test can check, rather than a string only a map can catch.
     */
    private class ArcGisTileSource(
        name: String,
        urls: Array<String>,
        copyright: String,
        minZoom: Int,
        maxZoom: Int,
    ) : OnlineTileSourceBase(name, minZoom, maxZoom, 256, "", urls, copyright) {
        override fun getTileURLString(tileIndex: Long): String =
            baseUrl + arcgisPath(
                MapTileIndex.getZoom(tileIndex),
                MapTileIndex.getX(tileIndex),
                MapTileIndex.getY(tileIndex),
            )
    }

    /** `{z}/{y}/{x}` — the swapped pair is the whole of ArcGIS's dialect. */
    internal fun arcgisPath(zoom: Int, x: Int, y: Int): String = "$zoom/$y/$x"
}

/**
 * The translation between the zoom level an owner reads and the level a map view holds.
 *
 * It exists for one reason: pixels. `setTilesScaledToDpi(true)` — which both map views set —
 * sizes osmdroid's *global* tile to `density · 256 · tilesScaleFactor` (checked in the 6.1.20
 * bytecode: `updateTileSizeForDensity` ends in the static `TileSystem.setTileSize`), so on the
 * bench phone a tile the server delivers at 256 px is drawn 704 px wide: enlarged 2.75× before
 * the zoom moves at all. And [org.osmdroid.views.overlay.TilesOverlay] always asks a host for
 * `TileSystem.getInputTileZoomLevel(zoom)`, which is `floor(zoom)` — so a fractional level
 * enlarged that already-enlarged tile by up to a further 2×. That stretch, not the level
 * arithmetic, is the stepped, pixelated zoom the owner sees: osmdroid carries the level as a
 * double end to end (`setZoomLevel` keeps the fraction and re-anchors on the current centre),
 * and the picture it paints at 15.4 is the tiles of 15 blown up.
 *
 * 6.1.20 cannot be told to fetch a deeper level. It can be told that a level's *world* is
 * smaller: at [TILE_SCALE_FACTOR] the global tile is half what DPI scaling alone makes it, so
 * the same ground on screen needs a level one higher, and `floor(zoom + 1)` asks the host for
 * **one tile generation deeper**, drawn at half size. Every level is then as sharp as the
 * level above it used to be, fractional levels included, at an unchanged apparent scale — the
 * "tiles sized for a continuous zoom" the owner asked for (2026-11-21), built from the only
 * knob this version has. It is worth stating what was *not* used: 6.1.20 has no
 * `setTileSizeMultiplier`, no `movementMode` and no `setZoomWithCenter` — checked against the
 * artifact this app builds against and against osmdroid's current `master` — and its
 * `setTilesScaleFactor` would make the stretch **worse** if it were raised above 1 rather than
 * dropped to [TILE_SCALE_FACTOR].
 *
 * The rule this imposes on every future change: **a level in the interface is not a level in a
 * view.** Decide a level in owner terms, translate here, and nothing in the app says which
 * world it was measured in.
 */
object MapZoom {
    /** How many tile generations deeper the view works than the level it shows. */
    const val TILE_DEPTH = 1.0

    /** The tile scale that produces [TILE_DEPTH] — half the world per level; see above. */
    const val TILE_SCALE_FACTOR = 0.5f

    /**
     * A hair under the deepest allowed level. The fetch level is `floor(viewZoom)`, so a view
     * permitted to rest *exactly* one generation past the source's ceiling would ask that host
     * for the level above it and paint a wall of loading tiles.
     */
    const val CEILING_EPS = 0.001

    /** An owner's level, as a level to hand a view. */
    fun toView(level: Double): Double = level + TILE_DEPTH

    /** A view's level, as the level to show a person or to compare against a style. */
    fun fromView(level: Double): Double = level - TILE_DEPTH

    /** The view's zoom floor for [style]. */
    fun viewMin(style: MapStyle): Double = toView(MapStyles.minZoomLevel(style))

    /** The view's zoom ceiling for [style] — see [CEILING_EPS] for why it is not exact. */
    fun viewMax(style: MapStyle): Double = toView(MapStyles.maxZoomLevel(style)) - CEILING_EPS
}

/**
 * The style every map on screen is drawing right now, mirrored from the owner's preference.
 *
 * It mirrors rather than being read from the store by each map because that is how this app
 * already carries an app-wide look switch — [com.alefinot.dashboardpp.ui.theme.HudMotion]
 * does exactly this with the motion switch, for exactly the same two reasons. The store is
 * DataStore, which never answers synchronously, so a map that asked it directly would draw
 * its first frame with the default and swap basemaps a beat later; and the three map
 * surfaces (the Live page, the Trips row's thumbnail, a sealed drive's detail page) plus the
 * settings page all have to agree the instant the owner picks something, which one mutable
 * state object gives them and four independent collectors does not.
 *
 * [com.alefinot.dashboardpp.MainActivity] seeds it during the splash, so the very first map
 * a cold start shows is already the owner's choice, and
 * [com.alefinot.dashboardpp.ui.screens.ConnectedTabs] keeps it true when the picker writes
 * the store.
 *
 * A Compose state object, so reading [current] from a composable subscribes it: choosing
 * another style in App Settings recomposes the maps that are on screen and no others.
 */
object MapStylePref {
    private val _current = mutableStateOf(MapStyles.Standard)

    val current: MapStyle get() = _current.value

    fun set(id: String?) {
        val next = MapStyles.byId(id)
        if (next !== _current.value) _current.value = next
    }
}
