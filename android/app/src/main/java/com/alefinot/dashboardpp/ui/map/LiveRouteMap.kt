package com.alefinot.dashboardpp.ui.map

import android.content.Context
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alefinot.dashboardpp.trips.TrackPoint
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.hudCard
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The open drive's route, drawing itself as the recorder appends it (live route plan).
 *
 * The same osmdroid tile map the sealed drive gets on its detail page, with the
 * opposite camera rule. The detail page frames a finished drive once and then keeps
 * its hands off; a drive that is one second younger than the last frame has no
 * shape worth framing, so this one **follows**: the camera points at the newest
 * position and every update drags it along. Framing per update would zoom-punch the
 * view on every GPS metre.
 *
 * A finger still wins. The holder remembers where it last pointed the camera, so a
 * centre sitting somewhere else can only have been moved by the user; follow stops
 * and a `RE-CENTER` note appears - and the resume is a tap anywhere on the map,
 * caught in the map view's own `onTouchEvent`, at the zoom the user chose. (It has
 * to be caught there: touches in this slot belong to the platform view, so neither
 * a Compose button painted over the map nor a Compose interop filter in front of
 * it receives anything - the bench proved both, 2026-11-15.)
 *
 * Degradation is the detail map's, unchanged: no tiles and the vector route plus
 * the dots still render - the route is our recorded data, not the tile server's.
 * And it is built only while a trip file is open, so an idle app never fetches a
 * basemap for a drive that does not exist.
 */
@Composable
fun LiveRouteMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    /** Labels beside the dots; too wide for the 72 dp row map, useful on the card. */
    showLabels: Boolean = true,
) {
    OsmdroidSetup.ensure(LocalContext.current)
    val holder = remember { LiveMapHolder(showLabels) }
    val geo = remember(points) { points.map { GeoPoint(it.lat, it.lon) } }
    Box(modifier) {
        AndroidView(
            // Clipped for the same reason RouteMap is: an unclipped interop view
            // paints its basemap over everything the canvas holds, not just its slot.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            factory = { ctx -> holder.build(ctx) },
            update = { map -> holder.applyTrack(map, geo) },
        )
        val following by holder.following
        if (!following) {
            // Not a button - the press lands on the map and the map answers it:
            // while paused, a tap anywhere in the slot re-centers (the holder's
            // own view catches it; a Compose pill over the map receives nothing -
            // the platform view takes touches in this slot at the ViewGroup
            // level, and an interop filter never sees a consumed DOWN either.
            // Both were bench-proven wrong, 2026-11-15.)
            Box(
                Modifier.fillMaxSize().padding(6.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Box(
                    Modifier.hudCard().padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("RE-CENTER", color = HudColors.Cyan, style = HudeSectionCaps())
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { holder.release() }
    }
}

/**
 * The map's camera and overlays between frames.
 *
 * The overlays are added once and then moved: at one update a second there is
 * nothing to gain from rebuilding a [Polyline] and its markers every time, and
 * the START dot relies on [com.alefinot.dashboardpp.trips.TrackPreview]'s rule
 * that the first position never moves - so it is placed once and never touched
 * again.
 */
private class LiveMapHolder(private val showLabels: Boolean) {
    private var map: MapView? = null
    private var track: List<GeoPoint> = emptyList()
    private var line: Polyline? = null
    private var startMarker: Marker? = null
    private var liveMarker: Marker? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** Where the camera was pointed last; null until the first position lands. */
    private var placed: GeoPoint? = null

    /** False once the user pans away; drives the RE-CENTER pill's visibility. */
    val following = mutableStateOf(true)

    fun build(context: Context): MapView {
        // A drag is a drag past this much travel; anything less, and the finger meant a tap.
        val slopPx = 12f * context.resources.displayMetrics.density
        // The subclass exists for two jobs, both at the ViewGroup level where this
        // view owns its slot's touches (Compose modifiers see nothing - the bench
        // proved a pill and an interop filter both dead, 2026-11-15):
        // 1. while a finger is down on the map, the scrolling page underneath must
        //    not steal the drag - osmdroid itself never asks for that, and both
        //    surfaces this draws on sit inside a scroller;
        // 2. while follow is paused, a tap (down and up, barely moved) is the
        //    "give me the car back" gesture and re-centers.
        val view = object : MapView(context) {
            private var downX = 0f
            private var downY = 0f
            private var tapCandidate = false

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        downX = event.x; downY = event.y; tapCandidate = true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = false
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (tapCandidate && dx * dx + dy * dy > slopPx * slopPx) tapCandidate = false
                    }
                    MotionEvent.ACTION_UP -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        val tapped = tapCandidate
                        tapCandidate = false
                        if (tapped && !following.value) {
                            resume()
                            return true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        tapCandidate = false
                    }
                }
                return super.onTouchEvent(event)
            }
        }
        view.setTileSource(TileSourceFactory.MAPNIK)
        view.setMultiTouchControls(true)
        // Tiles are a bonus, never a dependency - same rule as the detail map.
        view.setUseDataConnection(true)
        view.setTilesScaledToDpi(true)
        view.setMaxZoomLevel(19.0)
        view.setMinZoomLevel(4.0)
        map = view
        return view
    }

    fun applyTrack(view: MapView, points: List<GeoPoint>) {
        val usable = MapFit.usable(points)
        if (sameAs(usable)) return
        track = usable
        ensureLine(view)
        line?.setPoints(track)
        ensureMarkers(view)
        liveMarker?.setPosition(track.lastOrNull())
        // Overlay swaps do not invalidate the view by themselves (MapHolder lesson).
        view.invalidate()
        if (view.width > 0 && view.height > 0) follow(view) else awaitSize(view)
    }

    /**
     * Re-follow **now**, the gesture the RE-CENTER note promises: the camera is
     * deliberately sitting where the user put it, so the very drift that paused
     * follow is still there, and a plain `follow` pass would read it as a fresh
     * pan and pause itself again on the tap (bench, 2026-11-15). The [force]
     * recenter is what makes the tap answer instead of shrug.
     */
    fun resume() {
        following.value = true
        map?.let { follow(it, force = true) }
    }

    fun release() {
        map?.run {
            detachListener(this)
            onPause()
            onDetach()
        }
        map = null
        track = emptyList()
        placed = null
        line = null
        startMarker = null
        liveMarker = null
    }

    /** Decimation can drop a middle point, but the newest one says what moved. */
    private fun sameAs(points: List<GeoPoint>): Boolean =
        points.size == track.size &&
            points.lastOrNull()?.latitude == track.lastOrNull()?.latitude &&
            points.lastOrNull()?.longitude == track.lastOrNull()?.longitude

    private fun ensureLine(view: MapView) {
        if (line != null) return
        val poly = Polyline()
        // setPoints(), not getPoints().add() - see the MapHolder note: the old
        // accessor hands back a copy and the line draws nothing at all.
        val paint = poly.getOutlinePaint()
        paint.color = HudColors.Cyan.toArgb()
        val density = view.resources.displayMetrics.density
        paint.strokeWidth = 5f * density
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        line = poly
        view.getOverlayManager().add(poly)
    }

    private fun ensureMarkers(view: MapView) {
        val last = track.lastOrNull() ?: return
        if (liveMarker == null) {
            liveMarker = MapMarkers.labelled(
                view, last, HudColors.Green.toArgb(), if (showLabels) "LIVE" else "",
            )
            view.getOverlayManager().add(liveMarker)
        }
        // The first position never moves (TrackPreview's rule), so this dot is
        // placed once when the route becomes a line and never repositioned.
        val first = track.firstOrNull()
        if (startMarker == null && first != null && track.size >= 2) {
            startMarker = MapMarkers.labelled(
                view, first, HudColors.Green.toArgb(), if (showLabels) "START" else "",
            )
            view.getOverlayManager().add(startMarker)
        }
    }

    /**
     * Follow the newest position, unless the user has taken the camera.
     *
     * The camera only sits where [placed] put it until a finger moves it, so a
     * centre that has drifted from there by more than a GPS-noise step is the
     * user, not the drive - follow pauses rather than yanking the map back under
     * a finger that is trying to look at where the drive came from. [force] is
     * the tap on RE-CENTER: it re-points the camera over the user's pan instead
     * of re-reading that pan as a new one.
     */
    private fun follow(view: MapView, force: Boolean = false) {
        val last = track.lastOrNull() ?: return
        val anchor = placed
        if (anchor == null) {
            placed = GeoPoint(last)
            view.controller.setZoom(FOLLOW_ZOOM)
            view.controller.setCenter(last)
            return
        }
        if (!force) {
            if (!following.value) return
            if (anchor.distanceToAsDouble(view.mapCenter) > FOLLOW_BREAK_M) {
                following.value = false
                return
            }
        }
        view.controller.setCenter(last)
        placed = GeoPoint(last)
    }

    private fun awaitSize(view: MapView) {
        if (layoutListener != null) return
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.width > 0 && view.height > 0) {
                    detachListener(view)
                    follow(view)
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

    companion object {
        /** A drive's corner while it happens: streets readable, a few blocks deep. */
        private const val FOLLOW_ZOOM = 15.0

        /** Past this drift from where we pointed it, a finger was on the map. */
        private const val FOLLOW_BREAK_M = 60.0
    }
}
