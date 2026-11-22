package com.alefinot.dashboardpp.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
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
import com.alefinot.dashboardpp.trips.AccelRun
import org.osmdroid.config.Configuration
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

    /**
     * Empties the tile cache and reports how many bytes went with it.
     *
     * Bytes, not a tile count, because the cache is not a folder of images: osmdroid 6.1.20
     * keeps every tile in one SQLite file — `<tile cache>/cache.db`, one row per tile, keyed
     * by the `z/x/y` index and tagged with the style's [MapStyle.sourceName] in its `provider`
     * column. The whole store is two files (bench, 2026-11-21: 2,533 tiles in a 27.4 MB
     * `cache.db`), so a count of files would tell the owner "2 tiles" about a quarter of a
     * gigabyte of imagery. Bytes is what this can actually measure and what the owner can act
     * on. Deleting what is on screen is safe: the view's in-memory cache still holds every
     * tile it is drawing, and the next pass over that ground fetches it again — the store is
     * rebuilt on the next write, and a style switch reaches a fresh provider anyway.
     */
    fun clearTileCache(): Long {
        val dir = Configuration.getInstance().getOsmdroidTileCache() ?: return 0L
        var freed = 0L
        dir.walkBottomUp().forEach { file ->
            if (file.isFile) {
                val size = file.length()
                if (file.delete()) freed += size
            }
        }
        // Any leftover folder from a pre-database layout; walkBottomUp has been inside each
        // one already, so this only has to remove the empty directory.
        dir.listFiles()?.forEach { folder -> if (folder.isDirectory) folder.delete() }
        return freed
    }
}

/**
 * The logged track drawn over the ground the owner chose (App Settings → Live Map).
 *
 * Degradation is deliberate (plan §6): if tiles cannot be fetched — no data
 * connection, tile host blocked, cache empty — osmdroid paints its empty canvas
 * and the vector track, start and end markers still render. The route is our
 * recorded data, not the tile server's, so an offline phone still sees the shape
 * of the drive; only the basemap is missing. The `HUD Ground` style makes that
 * state a choice rather than a failure: no tiles at all, on a ground the app draws
 * itself.
 */
@Composable
fun RouteMap(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    /**
     * The drive's 0-50 runs, painted in yellow over the route — the same lines the live map drew
     * while the drive was happening, recovered from the file (see [com.alefinot.dashboardpp.trips.runsFrom]).
     * Empty is the common case and costs nothing: the detail page of a drive with no launches looks
     * exactly the way it did before runs existed.
     */
    runs: List<AccelRun> = emptyList(),
    /** The run whose result the page is showing: the one line dressed in white. */
    selectedRun: AccelRun? = null,
    /** A run's line was tapped. Not called for open ground — a miss is just a map tap. */
    onRunTap: (AccelRun) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OsmdroidSetup.ensure(context)
    val holder = remember { MapHolder() }
    // The ground the owner chose, read as Compose state so a pick on the settings page
    // redraws a detail page that is already open.
    val style = MapStylePref.current
    AndroidView(
        // Clipped here, not in the host: osmdroid sizes its tile painting from
        // the canvas it is handed (TilesOverlay keeps a mCanvasRect taken from
        // Canvas.getClipBounds), and Compose draws an interop View into its own
        // canvas without clipping it to the slot. Left alone, the basemap paints
        // over the whole screen — status bar, cards, summary cells — while the
        // track and markers stay inside the view's real bounds. On top of the
        // visual mess it also fetches and decodes tiles nobody can see.
        modifier = modifier.clipToBounds(),
        factory = { ctx -> holder.build(ctx, style) },
        update = { map ->
            holder.onRunTap = onRunTap
            holder.applyTrack(map, points, style, runs)
            holder.selectRun(map, selectedRun)
        },
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

    /** The ground on the view — source, tint, wash, zoom bounds. See [applyStyle]. */
    private val chrome = MapStyleApplier()

    /** The style the track was last inked for, and its casing. */
    private var style: MapStyle? = null

    /** The drive's casing: the same point list again, wider, in the ink the ground needs. */
    private var casing: Polyline? = null

    /** The route's own line, kept so a style change can re-ink it without redrawing the track. */
    private var trackLine: Polyline? = null

    /**
     * The drive's 0-50 runs, over the route.
     *
     * The same object the live map wears ([RunOverlay]), so a run cannot learn two looks or two tap
     * radii depending on which page the owner is asking about it on.
     */
    private val runOverlay = RunOverlay()

    /** Where a run tap lands. Written from the update lambda, like the live map's holder. */
    var onRunTap: ((AccelRun) -> Unit)? = null

    fun build(context: Context, style: MapStyle): MapView {
        // A tap is read here rather than through osmdroid's clickable overlays, for the reason the
        // live map gives at length: touches in this slot belong to the platform view, so the page
        // can be told where to paint but never what was touched under a finger. This map has no tap
        // gesture of its own to win against — no follow to resume, no page scroll to serve — so a
        // hit on a run is the whole story: the number goes up, and the library's double-tap zoom
        // does not fire under a finger that was asking about a run. A miss falls through untouched.
        val view = object : MapView(context) {
            private val slopPx = 12f * context.resources.displayMetrics.density
            private var downX = 0f
            private var downY = 0f
            private var tapCandidate = false

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x; downY = event.y; tapCandidate = true
                    }
                    // Two fingers is a pinch, not a question.
                    MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = false
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (tapCandidate && dx * dx + dy * dy > slopPx * slopPx) tapCandidate = false
                    }
                    MotionEvent.ACTION_UP -> {
                        val tapped = tapCandidate
                        tapCandidate = false
                        if (tapped) {
                            val hit = runOverlay.hit(this, event.x, event.y)
                            if (hit != null) {
                                onRunTap?.invoke(hit)
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> tapCandidate = false
                }
                return super.onTouchEvent(event)
            }
        }
        view.setMultiTouchControls(true)
        // Data connection on: tiles are a bonus, never a dependency (see above).
        view.setUseDataConnection(true)
        // The owner's ground, including the zoom bounds the pinch runs out against and the
        // tile size every level is measured in (MapStyleApplier owns both): what used to be a
        // hard-coded MAPNIK and a hard-coded 4..19 on both maps.
        chrome.apply(view, style)
        this.style = style
        map = view
        return view
    }

    /**
     * Move the owner's choice onto this view. Cheap when nothing changed — the update lambda
     * runs on every recomposition of the detail page.
     */
    fun applyStyle(view: MapView, style: MapStyle) {
        if (this.style === style && chrome.isApplied(style)) return
        chrome.apply(view, style)
        this.style = style
        inkTrack(view)
        // The run lines wear the same ground ink the route does, so they are re-dressed in the same
        // pass — the yellow itself never changes, only the casing under it.
        runOverlay.ink(view, style)
        view.invalidate()
    }

    /**
     * The run whose result the page is showing, or null for none: the one line dressed in white.
     *
     * Kept apart from [applyTrack] because a tap is not a change of drive — it must not re-fit the
     * camera, and it must not rebuild the route.
     */
    fun selectRun(view: MapView, run: AccelRun?) {
        if (run === runOverlay.selected) return
        runOverlay.selected = run
        runOverlay.ink(view)
    }

    /**
     * The track is drawn as soon as the view exists; framing waits for a real
     * size, because a `AndroidView` update block runs before layout and framing
     * an unmeasured map is not merely wrong — see [MapFit].
     *
     * [runs] rides along with it because the two belong to one picture: a run is a section of the
     * route, so it is drawn when the route is drawn and never on a map that has no route.
     */
    fun applyTrack(view: MapView, points: List<GeoPoint>, style: MapStyle, runs: List<AccelRun> = emptyList()) {
        applyStyle(view, style)
        track = MapFit.usable(points)
        drawTrack(view, runs)
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

    private fun drawTrack(view: MapView, runs: List<AccelRun>) {
        val manager = view.getOverlayManager()
        manager.clear()
        // `clear()` takes the ground wash, and any label layers the ground stacks, with
        // everything else — they live in the same list.
        chrome.restoreChrome(view)
        if (track.isEmpty()) return

        val line = Polyline()
        trackLine = line
        // Points must arrive through setPoints(): in osmdroid 6.1.20 the older
        // getPoints() accessor is deprecated and hands back a *copy* of the
        // ring's list, so adding to it draws nothing at all - which is exactly
        // what the trip map did (two markers, no route) until this was found.
        line.setPoints(track)
        // The casing goes in first: the manager draws its list in order, so the route
        // painted after it lands on top of its own edge.
        val outline = Polyline()
        outline.setPoints(track)
        casing = outline
        inkTrack(view)
        manager.add(outline)
        manager.add(line)

        // The runs go between the route and its dots, which is the order the live map keeps for the
        // same reason: a run is a mark painted *on* the drive, so it has to lie over the cyan, and
        // the START/END dots stay over every line.
        //
        // The wipe above took the run lines with it — [RunOverlay.sync] answers "these are already
        // on screen" by identity, and after a `clear()` that answer is a lie: the polylines it is
        // remembering are no longer in this manager. So the bookkeeping is dropped here, where the
        // wipe happens, and the sync below rebuilds them. The page re-asserts the selection straight
        // after (its `update` lambda), so the forgotten one is back on the same frame; the live map,
        // which never wipes its whole manager, keeps its guard working.
        runOverlay.clear()
        runOverlay.sync(view, runs, manager.size, style)

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

    /**
     * The route's ink for the ground under it: the app's cyan, cased in what that ground
     * needs — a dark edge on a pale map, a heavier one on aerial imagery, a cyan bloom on a
     * dark one. The drive itself is the same cyan on every style.
     */
    private fun inkTrack(view: MapView) {
        val s = style ?: return
        val line = trackLine ?: return
        val outline = casing ?: return
        val density = view.resources.displayMetrics.density
        val route = line.getOutlinePaint()
        route.color = HudColors.Cyan.toArgb()
        route.strokeWidth = 5f * density
        route.strokeCap = Paint.Cap.ROUND
        route.strokeJoin = Paint.Join.ROUND
        val ink = MapStyles.casing(s)
        val edge = outline.getOutlinePaint()
        edge.color = ink.colorArgb
        edge.strokeWidth = (5f + ink.extraDp) * density
        edge.strokeCap = Paint.Cap.ROUND
        edge.strokeJoin = Paint.Join.ROUND
    }

    private fun fit(view: MapView) {
        // MapFit thinks in the levels an owner reads; the view's bounds are one tile
        // generation deeper than that (MapZoom), so they are translated on the way in.
        val plan = MapFit.plan(
            track,
            view.width,
            view.height,
            MapZoom.fromView(view.maxZoomLevel),
        ) ?: return
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
            view.controller.setZoom(MapZoom.toView(plan.zoom))
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
     * resource: see [MapMarkers], which the live route map draws its dots from too.
     */
    private fun labelledMarker(view: MapView, point: GeoPoint, color: Int, label: String): Marker =
        MapMarkers.labelled(view, point, color, label)

    fun release() {
        map?.run {
            detachListener(this)
            onPause()
            onDetach()
        }
        map = null
        chrome.release()
        style = null
        trackLine = null
        casing = null
        track = emptyList()
        runOverlay.clear()
        onRunTap = null
        fitted = null
    }
}

/**
 * The map package's own marker art: a filled disc with a dark ring (legible on
 * light and dark basemaps, consistent with the HUD palette) and a permanent
 * label. A Marker's title only appears in an info window that a tap opens, so it
 * cannot answer "which end is this?" while the map is just looked at - and the
 * dots therefore swallow that tap outright, so osmdroid never opens the window.
 *
 * Two kinds of art live here: a disc for a position a drive visited
 * ([labelled] - START, END), and a dart for the position it is at right now
 * ([heading] - LIVE), whose head turns with the direction of travel.
 *
 * Drawn in code, not loaded from a resource, and shared by the detail map's
 * START/END dots and the live map's LIVE/START dots so the two can never drift
 * apart in size or ink. An empty label draws just the dot — what the small row
 * map can actually show.
 */
internal object MapMarkers {
    /** A fixed position: a disc with its name beside it. */
    fun labelled(view: MapView, point: GeoPoint, color: Int, label: String): Marker {
        val icon = dotWithLabel(view.context, view.resources.displayMetrics.density, color, label)
        val marker = Marker(view, view.context)
        marker.setPosition(point)
        marker.setTitle(label)
        marker.setAnchor(icon.anchorU, icon.anchorV)
        marker.setIcon(icon.drawable)
        // Swallow the dot's tap. osmdroid's default marker-click handler is
        // showInfoWindow(): the grey title callout popping out of the dot - a callout this
        // app never asked for, because the word beside the dot is *drawn into the bitmap*,
        // permanent and read at a glance. On the live maps it is worse than useless: a tap
        // is the resume-follow gesture, so every re-center aimed near the car popped one.
        // Returning true means "handled, do nothing".
        marker.setOnMarkerClickListener { _, _ -> true }
        return marker
    }

    /**
     * The live position as an arrow, its head turned to the direction of travel.
     *
     * Same ink, same label, same tap policy as [labelled] - the live marker is the one dot on
     * these maps that is not a place, so it is the one dot allowed to rotate. The rotation
     * lives inside the icon ([HeadingArrowDrawable]), not in `Marker.setRotation`: that one
     * feeds its bearing through osmdroid's own screen-rotation maths (`-mBearing` on a flat
     * marker, `-orientation - mBearing` otherwise), so which way the head ends up pointing
     * depends on a library internal this app has no reason to depend on. A dart that turns
     * itself is honest arithmetic: degrees clockwise from north, on a map that is always
     * north-up.
     */
    fun heading(view: MapView, point: GeoPoint, color: Int, label: String): HeadingMarker {
        val icon = HeadingArrowDrawable(view.resources.displayMetrics.density, color, label)
        val marker = Marker(view, view.context)
        marker.setPosition(point)
        marker.setTitle(label)
        marker.setAnchor(icon.anchorU, icon.anchorV)
        marker.setIcon(icon)
        // Swallow the tap for the same reason the dots do: on these maps a tap is the
        // resume-follow gesture, aimed wherever the car happens to be.
        marker.setOnMarkerClickListener { _, _ -> true }
        return HeadingMarker(marker, icon)
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
}

/**
 * The live position's marker: the [marker] osmdroid draws, and the one door the map's frame
 * ticker uses to turn its head.
 */
internal class HeadingMarker(
    val marker: Marker,
    private val icon: HeadingArrowDrawable,
) {
    /**
     * Degrees clockwise from north. Called at frame rate, so it only stores a number: the
     * arrow turns on the draw the map performs anyway.
     */
    fun setBearing(degrees: Float) {
        icon.bearingDeg = degrees
    }
}

/**
 * The dart that stands where the drive is and points the way it is going.
 *
 * Three rules the drawing obeys:
 * - The head turns, the word beside it does not. A rotated `LIVE` is unreadable, and these
 *   maps are north-up, so the label's upright is the map's upright - which is exactly why the
 *   rotation is here in the icon and around the dart's own centre, rather than the whole
 *   marker's.
 * - Nothing is built per frame. The path is made once, in pixel offsets around the origin;
 *   the ticker moves one float and the map draws.
 * - The tip never touches the label: 15 dp of dart inside an 11 dp box with a 5 dp gap leaves
 *   3 dp of clear air at the worst rotation.
 */
internal class HeadingArrowDrawable(
    density: Float,
    color: Int,
    private val label: String,
) : Drawable() {

    /** Degrees clockwise from north: 0 points up the map. */
    var bearingDeg: Float = 0f

    private val pad = 3f * density

    /** The box the dart lives in; the dart is longer than it is wide, so it can read as a point. */
    private val headW = 11f * density
    private val headL = 15f * density
    private val gap = 5f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = AndroidColor.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.75f * density
        strokeJoin = Paint.Join.ROUND
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = 10f * density
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.08f
        // The same dark halo the labelled dots use: the word has to survive the pale parts
        // of a raster basemap.
        setShadowLayer(3f * density, 0f, 0f, AndroidColor.BLACK)
    }

    private val labelled = label.isNotEmpty()
    private val textWidth = if (labelled) text.measureText(label) else 0f
    private val textHeight = text.fontMetrics.let { it.descent - it.ascent }

    private val widthPx = ceil(pad * 2 + headW + if (labelled) gap + textWidth else 0f).toInt()
    private val heightPx = ceil(pad * 2 + max(headL, if (labelled) textHeight else 0f)).toInt()

    /** Fraction of the icon that sits on the coordinate: the dart's centre. */
    val anchorU = (pad + headW / 2f) / widthPx

    /** The dart is centred vertically in the icon, label or no label. */
    val anchorV = 0.5f

    /** The dart, in pixel offsets around (0, 0), pointing north. Built once, never rebuilt. */
    private val dart = Path().apply {
        moveTo(0f, -headL / 2f)          // the tip
        lineTo(headW / 2f, headL / 2f)   // right wing
        lineTo(0f, headL / 4f)           // tail notch
        lineTo(-headW / 2f, headL / 2f)  // left wing
        close()
    }

    override fun getIntrinsicWidth(): Int = widthPx

    override fun getIntrinsicHeight(): Int = heightPx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.left + pad + headW / 2f
        val cy = (b.top + b.bottom) / 2f
        canvas.save()
        canvas.rotate(bearingDeg, cx, cy)
        canvas.translate(cx, cy)
        canvas.drawPath(dart, fill)
        canvas.drawPath(dart, ring)
        canvas.restore()
        if (labelled) {
            val metrics = text.fontMetrics
            val baseline = cy - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, b.left + pad + headW + gap, baseline, text)
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        ring.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        ring.colorFilter = colorFilter
        text.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
