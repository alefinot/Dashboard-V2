package com.alefinot.dashboardpp.ui.map

import android.content.Context
import android.os.SystemClock
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
import com.alefinot.dashboardpp.ui.components.LocalEnterActive
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.hudCard
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.sqrt

/**
 * The open drive's route, drawing itself as the recorder appends it (live route plan).
 *
 * The same osmdroid tile map the sealed drive gets on its detail page, with the
 * opposite camera rule. The detail page frames a finished drive once and then keeps
 * its hands off; a drive that is one second younger than the last frame has no
 * shape worth framing, so this one **follows**: the camera points at the newest
 * position. Framing per update would zoom-punch the view on every GPS metre.
 *
 * It follows *smoothly*, which is a second, harder rule. The unit answers once a
 * second, so a naive update moves the dot and the camera one long step per frame
 * and the drive reads as a slideshow. Between two fixes the dot and the camera
 * travel the segment on the Choreographer instead ([LiveMapHolder.glide]) -
 * constant speed, arriving as the next fix lands. Nothing is invented: every
 * drawn position sits on the segment between two recorded points, so it is a
 * position the drive really passed through, at most a second behind the recorder.
 *
 * The newest position wears an arrow, not a disc: its head points the way the drive is
 * going. [LiveHeading] reads that direction out of the very same positions, so nothing new
 * has to come over the radio, and the arrow turns through the segment the way the dot
 * travels it - a corner reads as a turn, not as a jump. A drive that has not moved has no
 * direction to report, and the arrow keeps the last one it honestly had: the one failure an
 * arrow at a traffic light can have is coming round in a slow circle because the receiver's
 * noise cleared six metres.
 *
 * A finger still wins, and now it wins the moment it moves: the map view's own
 * `onTouchEvent` treats travel past the touch slop as "this camera is mine",
 * follow stops, and the resume is a tap anywhere on the map. (It has to be caught
 * there: touches in this slot belong to the platform view, so neither a Compose
 * button painted over the map nor a Compose interop filter in front of it receives
 * anything - the bench proved both, 2026-11-15.) The old version had to infer a pan
 * from the camera sitting somewhere else at the next poll; that inference is gone on
 * purpose, because a camera re-pointed sixty times a second can never be *found*
 * somewhere else - the finger would be fighting it frame by frame.
 *
 * The Trips row's 72 dp thumbnail opts out of the whole gesture surface
 * (`interactive = false`): it is a status picture of the drive that takes no touch at
 * all — every gesture that starts on it belongs to the page, not to the map.
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
    /** Labels beside the markers; too wide for the 72 dp row map, useful on the card. */
    showLabels: Boolean = true,
    /**
     * False makes the map a picture of the drive: no pan, no pinch or double-tap zoom,
     * no +/− control — the view refuses every touch at its dispatch door, so the
     * gesture belongs to the page and the map never even sees it.
     */
    interactive: Boolean = true,
) {
    OsmdroidSetup.ensure(LocalContext.current)
    val holder = remember { LiveMapHolder(showLabels, interactive) }
    val geo = remember(points) { points.map { GeoPoint(it.lat, it.lon) } }
    // The pager keeps its neighbours composed, and a map nobody can look at has no
    // business taking a frame callback away from the page that is on screen - the same
    // rule the trip clock follows (LiveTab). In view is also the only moment a glide
    // has any point being smooth.
    val inView = LocalEnterActive.current
    Box(modifier) {
        AndroidView(
            // Clipped for the same reason RouteMap is: an unclipped interop view
            // paints its basemap over everything the canvas holds, not just its slot.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            factory = { ctx -> holder.build(ctx) },
            update = { map -> holder.applyTrack(map, points, geo, inView) },
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
 * The map's camera, overlays and glide between frames.
 *
 * The overlays are added once and then moved: at one update a second there is
 * nothing to gain from rebuilding a [Polyline] and its markers every time, and
 * the START dot relies on [com.alefinot.dashboardpp.trips.TrackPreview]'s rule
 * that the first position never moves - so it is placed once and never touched
 * again.
 *
 * The arrow is the exception that proves the rule: it is also placed once and then only
 * turned, and the turn is a float on its icon, so the frame ticker never builds anything.
 * The heading it turns to is opened per fix in [advance] and interpolated per frame in
 * [glide], which keeps the head's rotation on the same clock as the dot's travel.
 *
 * The glide state is a handful of fields and one Runnable: the segment's start and
 * end, when it began, how long it may take, and the two headings the arrow turns
 * between. Everything the Runnable touches per frame is reused, because it runs at
 * frame rate for the whole drive.
 */
private class LiveMapHolder(
    private val showLabels: Boolean,
    private val interactive: Boolean,
) {
    private var map: MapView? = null
    private var track: List<GeoPoint> = emptyList()
    private var line: Polyline? = null
    private var startMarker: Marker? = null
    private var liveArrow: HeadingMarker? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** The point list already consumed, by identity - see [applyTrack]. */
    private var lastRaw: List<GeoPoint>? = null

    /** False once a finger takes the camera; drives the RE-CENTER pill's visibility. */
    val following = mutableStateOf(true)

    /** Where the dot stands when the newest fix landed - the segment's start. */
    private var segFrom = GeoPoint(0.0, 0.0)

    /** The newest fix - the segment's end, and where the dot lands at t = 1. */
    private var segTo = GeoPoint(0.0, 0.0)

    /** Has a fix landed at all? Until one does, there is nothing to glide to. */
    private var hasFix = false

    /** Uptime of the newest arrival, and how long its segment is allowed to take. */
    private var segStartMs = 0L
    private var segMs = SEG_MS

    /** Uptime of the previous arrival - two of them measure the stream's real period. */
    private var lastArrivalMs = 0L

    /** Where the arrow's head points, degrees clockwise from north - what it shows right now. */
    private var heading = 0f

    /**
     * The two headings the arrow turns between: where its head was when this segment opened
     * and where it lands at t = 1. Opened by [advance], read by [glide].
     */
    private var headingFrom = 0f
    private var headingTo = 0f

    /** Where the dot is drawn this frame. One object, mutated; never allocated per frame. */
    private val dot = GeoPoint(0.0, 0.0)

    /**
     * Where the dot stood when this ticker last actually drew something.
     *
     * It is also where the camera is pointing, because a frame that draws points the
     * camera at the dot and puts the dot on it - so the distance from here to [dot] is
     * exactly how far the picture has travelled since that frame. See [glide] for what
     * that is used for.
     */
    private var drawn: GeoPoint? = null

    /** Scratch points for the projection calls; frame rate, so never allocated there. */
    private val px1 = android.graphics.Point()
    private val px2 = android.graphics.Point()

    /** True while a frame callback is queued. */
    private var ticking = false

    /** False while the page is off the pager's screen - nothing animates for nobody. */
    private var active = true

    /** The camera has been pointed once (zoom and first centre happen together, once). */
    private var framed = false

    /**
     * One frame of the glide, reposted to the Choreographer until the segment ends.
     *
     * Constant speed, because the arrival of the next fix is the finish line and the
     * next frame's job is only ever to continue. A glide that eased would decelerate
     * into a stop and then lurch, which is a step with extra frames.
     *
     * A frame is only *drawn* when the picture has travelled at least a whole pixel
     * since the last one. That is not a saving of work for the sake of it: redrawing a
     * tile map at 60 Hz for a fifth of a pixel of travel was measured on the bench phone
     * as the Live page's whole frame problem - one second of frames on that page:
     * big map 361 frames / 126 over the deadline, the same page's 72 dp map 361 / 15,
     * a page with no live map at all 360 / 8. The tile map is the page's cost, and a
     * sub-pixel frame pays it for a difference no screen can show.
     *
     * Skipping the draw is not skipping the motion. The dot's true position is kept
     * either way, so the next drawn frame lands exactly where the glide has reached and
     * nothing accumulates as error - the frames just thin out, to as often as a pixel of
     * travel and no less. Parked, the map stops drawing entirely, which is the same
     * result arrived at by arithmetic. The arrow's head obeys the same rule: it turns on the
     * frames that get drawn, so it never rotates for a difference no screen can show.
     */
    private val glide = object : Runnable {
        override fun run() {
            ticking = false
            val view = map ?: return
            if (!active || !hasFix || view.width == 0 || view.height == 0) return
            val now = SystemClock.uptimeMillis()
            val t = ((now - segStartMs).toDouble() / segMs).coerceIn(0.0, 1.0)
            dot.setCoords(
                segFrom.latitude + (segTo.latitude - segFrom.latitude) * t,
                segFrom.longitude + (segTo.longitude - segFrom.longitude) * t,
            )
            // The last point of a segment is always drawn, even a fraction of a pixel
            // out: a glide that stops a pixel short of the fix the recorder actually has
            // would leave the dot sitting on a position the drive never reported.
            val anchor = drawn
            if (t < 1.0 && anchor != null && movedPx(view, anchor) < 1.0) {
                ticking = true
                view.postOnAnimation(this)
                return
            }
            // The head turns on the same clock the dot travels: the same t that carries it
            // along the segment sweeps the arrow through the shortest arc to the newest
            // heading, so a corner reads as a turn the eye can follow instead of a snap.
            heading = LiveHeading.normalizeDeg(
                headingFrom + LiveHeading.shortestArcDeg(headingFrom, headingTo) * t.toFloat(),
            )
            liveArrow?.marker?.setPosition(dot)
            liveArrow?.setBearing(heading)
            // A finger that took the camera keeps it: only the dot keeps riding the route.
            if (following.value) view.controller.setCenter(GeoPoint(dot))
            drawn = GeoPoint(dot)
            view.invalidate()
            if (t < 1.0) {
                ticking = true
                view.postOnAnimation(this)
            }
        }
    }

    /**
     * How far [dot] would move on screen from where it was drawn last, in whole pixels.
     *
     * Whole, deliberately: [org.osmdroid.views.Projection.toPixels] truncates, and that
     * truncation *is* the question being asked - "would any pixel of this view change".
     */
    private fun movedPx(view: MapView, from: GeoPoint): Double {
        val proj = view.projection
        proj.toPixels(from, px1)
        proj.toPixels(dot, px2)
        val dx = (px2.x - px1.x).toDouble()
        val dy = (px2.y - px1.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    fun build(context: Context): MapView {
        // A drag is a drag past this much travel; anything less, and the finger meant a tap.
        val slopPx = 12f * context.resources.displayMetrics.density
        val holder = this
        // The subclass exists for three jobs, plus the show-only door below - all of
        // them at the ViewGroup level, the one place that reliably owns the slot's
        // touches (a Compose pill painted over an interactive map receives nothing,
        // bench, 2026-11-15):
        // 1. while a finger is down on the map, the scrolling page underneath must
        //    not steal the drag - osmdroid itself never asks for that, and both
        //    surfaces this draws on sit inside a scroller;
        // 2. travel past the slop is the user taking the camera: follow stops on the
        //    spot, not at the next poll (see the file's note on why the old drift
        //    inference cannot work against a camera that is re-pointed per frame);
        // 3. while follow is paused, a tap (down and up, barely moved) is the
        //    "give me the car back" gesture and re-centers.
        val view = object : MapView(context) {
            private var downX = 0f
            private var downY = 0f
            private var tapCandidate = false

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (!holder.interactive) {
                    // A show-only map refuses the touch at the door: the thumbnail is
                    // a status picture, and a picture must not hold a gesture it will
                    // not spend - neither the pager nor the list ever owes it one.
                    // (The row turned out to be a fixed header ABOVE the trip list, so
                    // drags on it scroll nothing anyway; the refusal is what makes the
                    // inertness real, whatever the row ever sits in.)
                    //
                    // It belongs HERE, not in onTouchEvent below: Compose's interop
                    // asks `view.dispatchTouchEvent(...)`, and osmdroid answers yes by
                    // itself inside that override - its dispatch feeds the overlays,
                    // the multitouch detector and its GestureDetector directly and
                    // never consults `super.onTouchEvent`, so a guard there would
                    // never run (shipped 6.1.20 bytecode, 2026-11-15). Refused at this
                    // door, the view logs prove the gesture then reaches Compose's
                    // hit path unconsumed and the map never sees another event.
                    return false
                }
                return super.dispatchTouchEvent(event)
            }

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
                        if (tapCandidate && dx * dx + dy * dy > slopPx * slopPx) {
                            tapCandidate = false
                            holder.takeCamera()
                        }
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
        if (interactive) {
            view.setMultiTouchControls(true)
        } else {
            // Show-only: no pinch or double-tap zoom from the gesture detector, no +/−
            // control (on a thumbnail it paints two slabs over the drive anyway), and
            // dispatchTouchEvent above refuses the touch so the page keeps the gesture.
            // The camera is the follow's alone.
            view.setMultiTouchControls(false)
            view.setBuiltInZoomControls(false)
        }
        // Tiles are a bonus, never a dependency - same rule as the detail map.
        view.setUseDataConnection(true)
        view.setTilesScaledToDpi(true)
        view.setMaxZoomLevel(19.0)
        view.setMinZoomLevel(4.0)
        map = view
        return view
    }

    /**
     * One update of the drive's shape: [fixes] is what the recorder holds, [geo] the same
     * positions as map points. Both are handed over because the arrow needs the drive as the
     * recorder made it - its direction is a distance walked back through real fixes - while
     * everything that gets painted works in projected points.
     */
    fun applyTrack(view: MapView, fixes: List<TrackPoint>, geo: List<GeoPoint>, inView: Boolean) {
        // The cards around this map recompose at frame rate while their figures glide
        // (ui/components/HudFlow.kt), and AndroidView re-runs this lambda on every one
        // of those passes. The point list is remembered by identity upstream, so the
        // reference test below is the whole filter: re-walking 256 points sixty times
        // a second to conclude "same drive" is exactly the step this file exists to avoid.
        // Coming back into view is the one change that has to get through the filter.
        val woke = inView && !active
        active = inView
        if (!active) stopTicker(view)
        if (geo === lastRaw && !woke) return
        lastRaw = geo
        val usable = MapFit.usable(geo)
        if (!sameAs(usable)) {
            val restart = newDrive(usable)
            track = usable
            ensureLine(view)
            line?.setPoints(track)
            ensureMarkers(view)
            advance(fixes, restart)
        }
        // Overlay swaps do not invalidate the view by themselves (MapHolder lesson).
        view.invalidate()
        frameAndRun(view)
    }

    /**
     * A different drive, as opposed to this one gaining a metre.
     *
     * [com.alefinot.dashboardpp.trips.TrackPreview] never moves its first position, so a
     * first point that changed is another file being recorded. Only the arrow cares: a new
     * drive gets north back, because carrying the last drive's heading into the first metres
     * of the next one would be a claim about a vehicle that has not moved yet.
     */
    private fun newDrive(next: List<GeoPoint>): Boolean {
        val first = next.firstOrNull() ?: return false
        val was = track.firstOrNull() ?: return true
        return first.latitude != was.latitude || first.longitude != was.longitude
    }

    /**
     * The half of an update that needs a measured view - also what runs when the layout
     * finally lands, which is why it is not folded into [applyTrack].
     */
    private fun frameAndRun(view: MapView) {
        if (view.width == 0 || view.height == 0) {
            awaitSize(view)
            return
        }
        if (!framed && hasFix) {
            framed = true
            // One zoom, once, at the drive's first fix: after that the camera only
            // travels, and a zoom that came along every second would be the punch
            // this file exists to prevent.
            view.controller.setZoom(FOLLOW_ZOOM)
            snapCamera(view)
        }
        if (active) startTicker(view)
    }

    /**
     * Re-follow **now**, the gesture the RE-CENTER note promises: the camera is
     * deliberately sitting where the user left it, so it is snapped onto the car
     * once and only then handed back to the glide - a glide from wherever the pan
     * left it would spend a second sliding across the map to catch up, which the
     * bench read as a RE-CENTER that shrugs (2026-11-15).
     */
    fun resume() {
        following.value = true
        val view = map ?: return
        snapCamera(view)
        startTicker(view)
    }

    fun release() {
        map?.run {
            stopTicker(this)
            detachListener(this)
            onPause()
            onDetach()
        }
        map = null
        track = emptyList()
        line = null
        startMarker = null
        liveArrow = null
        lastRaw = null
        hasFix = false
        framed = false
        drawn = null
        heading = 0f
        headingFrom = 0f
        headingTo = 0f
    }

    /** Decimation can drop a middle point, but the newest one says what moved. */
    private fun sameAs(points: List<GeoPoint>): Boolean =
        points.size == track.size &&
            points.lastOrNull()?.latitude == track.lastOrNull()?.latitude &&
            points.lastOrNull()?.longitude == track.lastOrNull()?.longitude

    /**
     * Open a new segment for the newest fix, starting from where the dot stands now.
     *
     * Starting from the previous *fix* would jump the dot backwards whenever a frame
     * arrives before the last segment finished - which is what jitter does. Starting
     * from the dot makes the motion continuous in both directions, at the cost of the
     * dot never quite catching the recorder: it is at most one frame behind, which is
     * the whole price of smooth and the right thing to pay on a dash.
     *
     * The segment's length is the arrival interval that was actually measured, not a
     * constant: the unit streams at 1 Hz but the radio delivers when it likes, and a
     * fixed 1000 ms makes a slow frame visibly lag and a fast one visibly snap.
     *
     * The arrow's turn opens here for the same reason the glide does: one fix is one new
     * direction, and the segment that carries the dot is what decides how long the head has
     * to get round to it.
     */
    private fun advance(fixes: List<TrackPoint>, restart: Boolean) {
        val newest = track.lastOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        if (lastArrivalMs > 0L) segMs = (now - lastArrivalMs).coerceIn(SEG_MIN_MS, SEG_MAX_MS)
        lastArrivalMs = now
        if (hasFix) {
            segFrom.setCoords(dot.latitude, dot.longitude)
        } else {
            segFrom.setCoords(newest.latitude, newest.longitude)
            dot.setCoords(newest.latitude, newest.longitude)
        }
        segTo.setCoords(newest.latitude, newest.longitude)
        segStartMs = now
        hasFix = true
        turnHeading(fixes, restart)
    }

    /**
     * Aim the arrow at the drive's newest direction of travel.
     *
     * Two ways to have no direction: the drive has not covered the baseline yet (a trip's
     * first seconds), or it has stopped. Both answer with the last heading, which is the
     * honest answer for a vehicle that has not gone anywhere - and the safe one, because a
     * heading read out of receiver noise is an arrow that slowly circles a parked drive.
     */
    private fun turnHeading(fixes: List<TrackPoint>, restart: Boolean) {
        if (restart) {
            heading = 0f
            headingFrom = 0f
            headingTo = 0f
            liveArrow?.setBearing(0f)
            return
        }
        val bearing = LiveHeading.of(fixes) ?: return
        // From where the head actually is, not where the last segment meant to put it: an
        // arrival early on its segment means the previous turn is still in progress, and a
        // turn that restarts mid-sweep is continuous; one that restarts from the endpoint is
        // a step.
        headingFrom = heading
        headingTo = bearing.toFloat()
    }

    /** Point the camera at the dot, once, right now - the glide's only non-frame moves. */
    private fun snapCamera(view: MapView) {
        if (!hasFix) return
        // `setCenter`, not `animateTo` / `setMapCenter`: this one is immediate (it ends in
        // MapView.setExpectedCenter, a scroll and a projection reset). The animated forms
        // start a transition of their own, and two authorities moving one camera is exactly
        // the stutter this file was written to remove.
        view.controller.setCenter(GeoPoint(dot))
    }

    private fun startTicker(view: MapView) {
        if (ticking || !active || !hasFix) return
        ticking = true
        view.postOnAnimation(glide)
    }

    private fun stopTicker(view: MapView) {
        ticking = false
        view.removeCallbacks(glide)
    }

    /** A finger moved the map: the camera is the user's until they ask for the car back. */
    private fun takeCamera() {
        if (following.value) following.value = false
    }

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
        if (liveArrow == null) {
            // The arrow, not a dot: this is the one marker on these maps that stands for a
            // vehicle rather than a place, so it gets the head that says which way it is
            // facing. Created pointing north; the first fix with six metres behind it aims it.
            val arrow = MapMarkers.heading(
                view, last, HudColors.Green.toArgb(), if (showLabels) "LIVE" else "",
            )
            liveArrow = arrow
            view.getOverlayManager().add(arrow.marker)
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

    private fun awaitSize(view: MapView) {
        if (layoutListener != null) return
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.width > 0 && view.height > 0) {
                    detachListener(view)
                    frameAndRun(view)
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

        /** A sample period. The unit streams at 1 Hz (trip plan: one frame a second). */
        private const val SEG_MS = 1000L

        /** A segment shorter than this is jitter, not a drive: do not throw the dot. */
        private const val SEG_MIN_MS = 250L

        /** Longer than this and the stream has stopped; freeze rather than crawl. */
        private const val SEG_MAX_MS = 3000L
    }
}
