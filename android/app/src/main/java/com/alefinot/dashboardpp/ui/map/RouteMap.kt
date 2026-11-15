package com.alefinot.dashboardpp.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.ViewTreeObserver
import kotlin.math.ceil
import kotlin.math.max
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.alefinot.dashboardpp.ui.theme.HudColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * osmdroid needs one global setup before any MapView exists: where tiles are
 * cached, and a User-Agent that identifies us. The tile policy at
 * openstreetmap.org/robots requires a real contact in the UA header — anonymous
 * or generic agents get throttled or blocked — so the app's own repository is
 * named there. The cache goes under the app's external files dir rather than
 * osmdroid's default so it is removed cleanly on uninstall.
 */
object OsmdroidSetup {
    @Volatile
    private var done = false

    const val USER_AGENT =
        "Dashboard++forESP32/1.1.0 (Android; https://github.com/alefinot/Dashboard-V2)"

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            val base = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val cache = File(base, "osmtiles")
            if (!cache.exists()) cache.mkdirs()
            Configuration.getInstance().apply {
                // Base path first: its getter falls back to asking a Context
                // that osmdroid only knows after Configuration.load(), which we
                // never call — so leaving it unset logs a caught NPE on every
                // MapView construction.
                setOsmdroidBasePath(base)
                // Explicit setters: the getter for the tile cache is overloaded
                // (with and without a Context), so the synthetic property syntax
                // would not resolve.
                setOsmdroidTileCache(cache)
                setUserAgentValue(USER_AGENT)
            }
            done = true
        }
    }
}

/**
 * The logged track drawn over OSM raster tiles.
 *
 * Degradation is deliberate (plan §6): if tiles cannot be fetched — no data
 * connection, tile host blocked, cache empty — osmdroid paints its empty canvas
 * and the vector track, start and end markers still render. The route is our
 * recorded data, not the tile server's, so an offline phone still sees the shape
 * of the drive; only the basemap is missing.
 */
@Composable
fun RouteMap(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OsmdroidSetup.ensure(context)
    val holder = remember { MapHolder() }
    AndroidView(
        // Clipped here, not in the host: osmdroid sizes its tile painting from
        // the canvas it is handed (TilesOverlay keeps a mCanvasRect taken from
        // Canvas.getClipBounds), and Compose draws an interop View into its own
        // canvas without clipping it to the slot. Left alone, the basemap paints
        // over the whole screen — status bar, cards, summary cells — while the
        // track and markers stay inside the view's real bounds. On top of the
        // visual mess it also fetches and decodes tiles nobody can see.
        modifier = modifier.clipToBounds(),
        factory = { ctx -> holder.build(ctx) },
        update = { map -> holder.applyTrack(map, points) },
    )
    DisposableEffect(Unit) {
        onDispose { holder.release() }
    }
}

private class MapHolder {
    private var map: MapView? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /**
     * Framing is applied once per point set and viewport size. Re-fitting on
     * every recomposition would yank the viewport back while the user is
     * panning around the route.
     */
    private var fitted: Long? = null

    /** Filtered track, kept so framing can run after the first layout. */
    private var track: List<GeoPoint> = emptyList()

    fun build(context: Context): MapView {
        val view = MapView(context)
        view.setTileSource(TileSourceFactory.MAPNIK)
        view.setMultiTouchControls(true)
        // Data connection on: tiles are a bonus, never a dependency (see above).
        view.setUseDataConnection(true)
        view.setTilesScaledToDpi(true)
        // OSM's own maximum for the standard layer is z19; below z4 the whole
        // world is useless for a drive, so stop the pinch there.
        view.setMaxZoomLevel(19.0)
        view.setMinZoomLevel(4.0)
        map = view
        return view
    }

    /**
     * The track is drawn as soon as the view exists; framing waits for a real
     * size, because a `AndroidView` update block runs before layout and framing
     * an unmeasured map is not merely wrong — see [MapFit].
     */
    fun applyTrack(view: MapView, points: List<GeoPoint>) {
        track = MapFit.usable(points)
        drawTrack(view)
        if (view.width > 0 && view.height > 0) fit(view) else awaitSize(view)
    }

    private fun awaitSize(view: MapView) {
        if (layoutListener != null) return
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.width > 0 && view.height > 0) {
                    detachListener(view)
                    fit(view)
                }
            }
        }
        layoutListener = listener
        if (observer.isAlive) observer.addOnGlobalLayoutListener(listener)
    }

    private fun detachListener(view: MapView) {
        layoutListener?.let { l ->
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(l)
        }
        layoutListener = null
    }

    private fun drawTrack(view: MapView) {
        val manager = view.getOverlayManager()
        manager.clear()
        if (track.isEmpty()) return

        val density = view.resources.displayMetrics.density
        val line = Polyline()
        // Points must arrive through setPoints(): in osmdroid 6.1.20 the older
        // getPoints() accessor is deprecated and hands back a *copy* of the
        // ring's list, so adding to it draws nothing at all - which is exactly
        // what the trip map did (two markers, no route) until this was found.
        line.setPoints(track)
        val paint = line.getOutlinePaint()
        paint.color = HudColors.Cyan.toArgb()
        paint.strokeWidth = 5f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        manager.add(line)

        if (track.size >= 2) {
            manager.add(labelledMarker(view, track.first(), HudColors.Green.toArgb(), "START"))
            manager.add(labelledMarker(view, track.last(), HudColors.Red.toArgb(), "END"))
        } else {
            manager.add(labelledMarker(view, track.first(), HudColors.Cyan.toArgb(), "POSITION"))
        }
        // Overlay swaps do not invalidate the view by themselves; without this
        // a re-fitted track waits for the next pan to appear.
        view.invalidate()
    }

    private fun fit(view: MapView) {
        val plan = MapFit.plan(track, view.width, view.height, view.maxZoomLevel) ?: return
        val key = fitKey(view)
        if (key == fitted) return
        fitted = key
        if (plan.useBoundingBox) {
            val density = view.resources.displayMetrics.density
            // strokeSize is the pixel padding kept around the track so the start
            // and end markers are never flush with the map edge.
            view.zoomToBoundingBox(
                BoundingBox.fromGeoPoints(track),
                true,
                (28 * density).toInt(),
            )
        } else {
            // A single position, or a map that has no size to fit into: point
            // the camera at it and leave the zoom decision to us.
            view.controller.setZoom(plan.zoom)
            view.controller.setCenter(plan.center)
        }
        view.invalidate()
    }

    /** Identity of "this track in this viewport", used to fit exactly once. */
    private fun fitKey(view: MapView): Long {
        var h = (view.width.toLong() shl 20) xor view.height.toLong()
        h = h * 31 + track.size
        if (track.isNotEmpty()) {
            h = h * 31 + track.first().latitude.hashCode()
            h = h * 31 + track.last().longitude.hashCode()
        }
        return h
    }

    /**
     * A dot with its name beside it, drawn in code rather than loaded from a
     * resource: a filled disc with a dark ring (legible on light and dark
     * basemaps, consistent with the HUD palette) and a permanent label. A
     * Marker's title only appears in an info window that a tap opens, so it
     * cannot answer "which end is this?" while the map is just looked at.
     */
    private fun labelledMarker(view: MapView, point: GeoPoint, color: Int, label: String): Marker {
        val icon = dotWithLabel(view.context, view.resources.displayMetrics.density, color, label)
        val marker = Marker(view, view.context)
        marker.setPosition(point)
        marker.setTitle(label)
        marker.setAnchor(icon.anchorU, icon.anchorV)
        marker.setIcon(icon.drawable)
        return marker
    }

    private class MarkerIcon(
        val drawable: BitmapDrawable,
        /** Fraction of the bitmap that sits on the coordinate: the dot's centre. */
        val anchorU: Float,
        val anchorV: Float,
    )

    private fun dotWithLabel(context: Context, density: Float, color: Int, label: String): MarkerIcon {
        val pad = 3f * density
        val dot = 14f * density
        val gap = 5f * density
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 10f * density
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
            // Dark halo so the label survives the pale parts of a raster basemap.
            setShadowLayer(3f * density, 0f, 0f, AndroidColor.BLACK)
        }
        val metrics = text.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val width = ceil(pad * 2 + dot + gap + text.measureText(label)).toInt()
        val height = ceil(pad * 2 + max(dot, textHeight)).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cy = height / 2f
        val dotCx = pad + dot / 2f
        canvas.drawCircle(dotCx, cy, dot / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        canvas.drawCircle(
            dotCx, cy, dot / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = AndroidColor.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
            },
        )
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, pad + dot + gap, baseline, text)

        return MarkerIcon(
            BitmapDrawable(context.resources, bitmap),
            anchorU = dotCx / width,
            anchorV = cy / height,
        )
    }

    fun release() {
        map?.run {
            detachListener(this)
            onPause()
            onDetach()
        }
        map = null
        track = emptyList()
        fitted = null
    }
}
