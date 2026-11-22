package com.alefinot.dashboardpp.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.hudCard
import androidx.compose.ui.graphics.toArgb
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.hypot

/**
 * The map's own ground: a wash over the tiles, or a ground where there are no tiles.
 *
 * It exists because of where the wash used to live. The Live page painted its cockpit wash
 * in Compose — a flat 18 % black plus a radial vignette to 55 % — in a `Box` in front of the
 * `AndroidView`. Compose can only paint over a platform view as *one piece*, so that wash sat
 * on top of the tiles **and** on top of the route, the START/END/PAUSE dots and the live
 * arrow: the ink the app draws itself was dimmed by the very thing meant to quiet the
 * basemap under it. An overlay fixes it by placement, not by arithmetic: osmdroid's
 * [org.osmdroid.views.overlay.OverlayManager] draws the tiles overlay first and then its list
 * in order, so this overlay, sitting at the bottom of that list, is above every tile and
 * below every line.
 *
 * Two more things fall out of that placement. The vignette no longer darkens the corners
 * where the dots and the arrow land, so a PAUSE dot in a corner is as bright as one in the
 * middle. And the numbers stop being one global taste decision: they belong to the
 * [MapStyle], because imagery that is already dark and busy wants almost no wash and a heavy
 * casing, while a pale basemap wants the opposite.
 *
 * For the no-basemap style it paints the ground itself — the HUD's recess colour with a
 * hairline grid — so a map with no tiles is a designed surface rather than a dead void.
 * The grid is **screen-fixed**, and stays that way on purpose: a grid that scrolled with the
 * ground would read as geography the app does not have, while this one reads as the same
 * inset tile the trip cards' [RouteSketch] is drawn on.
 *
 * Nothing is built per frame. [rebuild] runs when the drawn rectangle changes — once per
 * rotation at most — and a map frame after that costs two `drawRect` calls, or one fill, one
 * path and two rects. That discipline is not decoration: this overlay sits in the Live page's
 * draw path, where one second of frames measured 361 frames with 126 over the deadline.
 */
internal class MapGroundOverlay(
    /**
     * Not named `style`: inside a `Paint().apply { }` block `style` is the paint's own
     * `setStyle`/`getStyle` property, and the scrim's alpha was being read off a Paint.Style.
     */
    private val mapStyle: MapStyle,
    density: Float,
) : Overlay() {

    private val scrimPaint = Paint().apply {
        color = Color.argb((mapStyle.scrim * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
    }

    /** The ground under the grid, for the style that fetches no tiles. */
    private val groundPaint = Paint().apply { color = HudColors.Track.toArgb() }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColors.Hairline.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val vignettePaint = Paint()

    /** The hairline grid's spacing, in pixels for this display. */
    private val gridPx = 56f * density

    private val grid = Path()

    /** The rectangle [grid] and [vignettePaint] were built for; null until the first draw. */
    private var builtFor: Rect? = null

    override fun draw(canvas: Canvas, projection: Projection) {
        // The same rectangle osmdroid's own TilesOverlay sizes its tile painting from: the
        // canvas this view was handed. `AndroidView` draws a platform view into Compose's
        // canvas without clipping it to the slot, so trusting anything else paints the wash
        // over the status bar and the tab bar — the identical trap LiveRouteMap's
        // `clipToBounds` note records.
        val bounds = canvas.clipBounds ?: return
        if (bounds.isEmpty) return
        val built = builtFor
        if (built == null || built != bounds) rebuild(Rect(bounds))

        if (!mapStyle.hasBasemap) {
            canvas.drawRect(bounds, groundPaint)
            canvas.drawPath(grid, gridPaint)
        }
        if (mapStyle.scrim > 0f) canvas.drawRect(bounds, scrimPaint)
        if (mapStyle.vignette > 0f) canvas.drawRect(bounds, vignettePaint)
    }

    /** One pass over the geometry: the vignette's shader and, for the ground style, the grid. */
    private fun rebuild(bounds: Rect) {
        builtFor = bounds
        val radius = hypot(bounds.width().toDouble(), bounds.height().toDouble()).toFloat() / 2f
        if (radius > 0f && mapStyle.vignette > 0f) {
            val ink = Color.argb((mapStyle.vignette * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
            vignettePaint.shader = RadialGradient(
                bounds.exactCenterX(), bounds.exactCenterY(), radius,
                intArrayOf(Color.TRANSPARENT, ink), null, Shader.TileMode.CLAMP,
            )
        } else {
            vignettePaint.shader = null
        }
        grid.rewind()
        if (!mapStyle.hasBasemap) {
            var x = bounds.left + gridPx
            while (x < bounds.right) {
                grid.moveTo(x, bounds.top.toFloat())
                grid.lineTo(x, bounds.bottom.toFloat())
                x += gridPx
            }
            var y = bounds.top + gridPx
            while (y < bounds.bottom) {
                grid.moveTo(bounds.left.toFloat(), y)
                grid.lineTo(bounds.right.toFloat(), y)
                y += gridPx
            }
        }
    }
}

/**
 * Puts one [MapStyle] onto a live [MapView], and remembers which one it put there.
 *
 * Both map holders use this rather than each repeating the same calls, because the order of
 * those calls is the part that can be quietly wrong:
 *
 * 1. the **tile size**, before anything else touches the provider. `setTilesScaleFactor` and
 *    `setTilesScaledToDpi` both re-derive it, and what they derive is osmdroid's *global*
 *    `TileSystem` tile — `density · 256 · factor` on a DPI-scaled view — which is the number
 *    every level's world is measured in. [MapZoom] keeps it at half DPI size so a level asks
 *    its host for one tile generation deeper; [MapStyleApplier] is the one place allowed to
 *    say that number, because a second view setting a different one would silently change
 *    every other map's ground in the same process.
 * 2. the zoom bounds **next**. `MapView.setTileSource` ends in `setZoomLevel(mZoomLevel)`,
 *    which clamps against the view's own bounds — so a view still holding a 19 ceiling when
 *    OpenTopoMap (z17) goes in is left pointing at a level with no tiles, and the owner sees
 *    a grey box rather than a mountain. The bounds are [MapZoom]'s view terms, not the
 *    style's owner terms: the style says where the host stops, the view is one generation
 *    deeper than that.
 * 3. the source, which rebuilds the tile provider, clears the in-memory tile cache and
 *    revalidates the zoom.
 * 4. the tiles overlay's colour filter and its `enabled` flag — a filter is applied at draw,
 *    so `HUD Dark` darkens the very tiles `OpenStreetMap` already downloaded, and switching
 *    between them never refetches a planet. Turning the overlay off is how the no-basemap
 *    style asks for *no* tiles: osmdroid's manager skips a disabled tiles overlay entirely
 *    (`DefaultOverlayManager.onDrawHelper`), and the only thing that ever asks the network
 *    for a tile is that overlay's own draw pass (`TilesOverlay`, line 255 of 6.1.20:
 *    `mTileProvider.getMapTile(...)`). Disabled means nothing is fetched — which is the
 *    promise `HUD Ground` makes.
 * 5. the label layers a ground stacks over its tiles, in the style's own order, and then
 *    the ground overlay, re-inserted above them. Order is the whole design: the manager
 *    draws the tiles, then its list, so a label layer lands over the imagery it names and
 *    *under* the wash, which dims a place name exactly as much as it dims the road it sits
 *    on. `Hybrid` is the only ground with any.
 *
 * It also owns the one index that matters downstream: [lineInsertAt]. The manager draws the
 * tiles, then its list in order, and the route lines have always been inserted at the bottom
 * of that list so a later segment cannot paint over the live arrow. With a wash in the list
 * too, "the bottom" is now one past the ground — and one past the label layers when there are
 * any — still below every marker.
 */
internal class MapStyleApplier {

    private var applied: MapStyle? = null
    private var ground: MapGroundOverlay? = null

    /**
     * The label layers currently in the manager, and the providers feeding them — held here
     * because they are the only part of a style that owns something which has to be let go.
     * A tile provider is a download queue with a network receiver behind it, and the door
     * osmdroid uses on its own provider when a view is going away is `detach()` (checked in
     * the 6.1.20 bytecode, `MapView.onDetach`). The Live page's rail changes ground with a
     * thumb press, so a satellite-with-labels ground abandoned mid-drive would leave that
     * queue running at a server for nothing, once per press.
     */
    private val layers = ArrayList<TilesOverlay>()
    private val providers = ArrayList<MapTileProviderBase>()

    /**
     * Where a route line goes in the manager's list: above everything the tile servers draw —
     * the label layers and the wash — and below every marker. A getter, because the count in
     * front of the lines belongs to the ground currently on the view.
     */
    val lineInsertAt: Int get() = layers.size + 1

    /** True when this style is already on the view — the identity check the holders guard with. */
    fun isApplied(style: MapStyle): Boolean = applied === style

    /**
     * Lay [style] on the view. Cheap when it is already there, so a holder can call it from
     * an update lambda that Compose re-runs at frame rate.
     */
    fun apply(view: MapView, style: MapStyle): Boolean {
        if (applied === style) return false
        applied = style
        // The global tile size first (see the class note): half of what DPI scaling alone
        // would make it, which is what buys the extra tile generation every level asks for.
        view.setTilesScaledToDpi(true)
        view.setTilesScaleFactor(MapZoom.TILE_SCALE_FACTOR)
        view.setMinZoomLevel(MapZoom.viewMin(style))
        view.setMaxZoomLevel(MapZoom.viewMax(style))
        view.setTileSource(MapStyles.tileSource(view.context, style))
        view.mapOverlay?.let { tiles ->
            tiles.setColorFilter(MapStyles.tileFilter(style))
            tiles.isEnabled = style.hasBasemap
        }
        val manager = view.overlayManager
        ground?.let { manager.remove(it) }
        dropLayers(view)
        for ((at, layer) in style.overlays.withIndex()) {
            val provider = MapTileProviderBasic(view.context, MapStyles.tileSource(view.context, layer))
            val overlay = TilesOverlay(provider, view.context).apply {
                // A loading slab over aerial imagery reads as a hole in the map. The base
                // tiles may show their grey while they arrive — they are the ground — but a
                // label layer that has nothing yet must draw nothing, not a grey brick.
                setLoadingBackgroundColor(Color.TRANSPARENT)
                setLoadingLineColor(Color.TRANSPARENT)
            }
            providers += provider
            layers += overlay
            manager.add(at, overlay)
        }
        val made = MapGroundOverlay(style, view.resources.displayMetrics.density)
        ground = made
        manager.add(layers.size, made)
        return true
    }

    /**
     * Put the ground — and any label layers it stacks — back after somebody emptied the
     * overlay manager (the trip detail map redraws its track by `clear()`ing the list). Not a
     * style change: nothing else moves, and no provider is rebuilt.
     */
    fun restoreChrome(view: MapView) {
        val made = ground ?: return
        val manager = view.overlayManager
        for ((at, layer) in layers.withIndex()) {
            if (manager.indexOf(layer) < 0) manager.add(at, layer)
        }
        if (manager.indexOf(made) < 0) manager.add(layers.size, made)
    }

    /** The holder is going away; the view and its overlays go with it. */
    fun release() {
        ground = null
        applied = null
        // The view is gone, so there is nothing left to draw these into; `layers` goes with
        // it, but the providers behind them outlive the view unless something says otherwise.
        for (p in providers) p.detach()
        providers.clear()
        layers.clear()
    }

    /** Take the label layers out of the manager and let go of the providers behind them. */
    private fun dropLayers(view: MapView) {
        val manager = view.overlayManager
        for (layer in layers) manager.remove(layer)
        layers.clear()
        for (p in providers) p.detach()
        providers.clear()
    }
}

/**
 * The provider's credit, as a card chip on the map's own ground.
 *
 * It sits in front of the map because it is text: the tile policies these sources are served
 * under ask for the attribution to be **visible where the tiles are**, which is a claim about
 * legibility, not about draw order, and the app's route ink must never be dimmed by the thing
 * that carries its own licence. A chip — the same `hudCard()` skin as the map's RE-CENTER
 * label — because a bare 11px line over aerial imagery is a line nobody can read, and the
 * basemap is exactly the surface this app cannot control.
 *
 * A style with no basemap has no provider to thank, and says so rather than showing an empty
 * chip ([MapStyle.credit]).
 */
@Composable
fun MapCredit(style: MapStyle, modifier: Modifier = Modifier) {
    Box(modifier.hudCard()) {
        Text(
            style.credit,
            color = HudColors.TextMuted,
            style = HudeCfgNote(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
