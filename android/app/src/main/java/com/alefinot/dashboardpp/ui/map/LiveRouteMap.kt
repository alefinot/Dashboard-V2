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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alefinot.dashboardpp.trips.AccelRun
import com.alefinot.dashboardpp.trips.LiveRoute
import com.alefinot.dashboardpp.trips.TrackPoint
import com.alefinot.dashboardpp.ui.components.LocalEnterActive
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.hudCard
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The recorded drive's route, drawing itself as the recorder appends it (live route plan).
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
 * drawn position sits on the segment between two real positions, so it is a
 * position the drive really passed through, at most a second behind the recorder.
 *
 * The newest position wears an arrow, not a disc: its head points the way the drive is
 * going. [LiveHeading] reads that direction out of the recorded positions plus the newest
 * fix, so nothing new has to come over the radio, and the arrow turns through the segment
 * the way the dot travels it - a corner reads as a turn, not as a jump. A drive that has
 * not moved has no direction to report, and the arrow keeps the last one it honestly had:
 * the one failure an arrow at a traffic light can have is coming round in a slow circle
 * because the receiver's noise cleared six metres.
 *
 * **A pause is a gap, not a line** (2.0.7/179). The route is a list of segments, one per
 * recording stretch: every piece is its own polyline with its own START dot, and a resume
 * begins a second piece instead of joining the two across ground the file never saw. While
 * paused the recorded line freezes at an amber PAUSE dot, but the arrow keeps riding the
 * newest GPS fix - the unit still knows where the car is, and a live map that freezes
 * solid during a pause is no longer live. The route survives the stop: the last written
 * position becomes a red END dot, the camera snaps to it, and the shape stays on both maps
 * until the next START replaces it. That is why the map is mounted for a route, not for an
 * open file: a closed drive that vanished the moment it closed was the one picture the
 * owner actually wanted to keep.
 *
 * **The line is drawn as it happens** (2026-11-18). A fix a second used to mean a line that
 * grew in car-length jumps, and a cap of 256 points per piece used to mean a drive that
 * degenerated into chords before it reached its second highway exit. So a piece is now thinned
 * by geometry instead of by count - a position is dropped only when a straight line between its
 * neighbours already covers it - which keeps 1000 km of route as true as the GPS that wrote
 * it, and the newest position is held back from the polyline and drawn by a two-point tail
 * reaching to the gliding arrow, so what grows is the line under the arrow at the arrow's
 * speed.
 *
 * **A run is drawn where it happened** (2026-11-22). The unit's 0-50 timer reports its state once
 * a second in the very frame that carries the position, so the fixes a launch was made of are
 * already in the phone's hands: every completed run becomes its own line over the drive's cyan, in
 * [HudColors.Yellow] — the section of ground the owner stood on the throttle, at the place it
 * happened. Tapping that line is what reads the result out, and the finding is this file's job
 * rather than osmdroid's clickable-overlay kind: touches in this slot belong to the platform view,
 * so a page can be told where to paint but never what was touched under a finger. A tap is
 * therefore measured against the run lines — nearest within [RunOverlay.TAP_DP], in pixels, at the
 * zoom on screen — and only a miss falls through to the gesture the map already had.
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
 * all â€” every gesture that starts on it belongs to the page, not to the map.
 *
 * **The ground is the owner's choice** (App Settings → Live Map, 2026-11-21). Which tiles
 * this draws on, how they are tinted, how hard the wash over them is pushed and what ink the
 * route is cased in all come from one [MapStyle] — [MapStylePref.current], the app-wide
 * mirror of the stored preference. The wash used to be painted by Compose in front of this
 * slot, which dimmed the drive as much as the basemap; it is an overlay now, sitting under
 * every line this map draws ([MapGroundOverlay]).
 *
 * Degradation is the detail map's, unchanged: no tiles and the vector route plus
 * the dots still render - the route is our recorded data, not the tile server's.
 * It is built while there is a position to draw - a recorded piece, a finished
 * drive, or (since the Live page became a map) a cursor-only trail with no file
 * open at all. An app with no position still fetches no basemap.
 */
@Composable
fun LiveRouteMap(
    route: LiveRoute,
    /**
     * Where the vehicle is: the newest positions the frame lane heard, recorded or not
     * ([com.alefinot.dashboardpp.trips.CursorTrail]). The arrow sits on the last of them and
     * points along the last few hundred metres of them - which is why a pause leaves it
     * moving and turning, and why a drive that has ended can keep an arrow at all.
     */
    trail: List<TrackPoint>,
    /**
     * True while a piece of this drive is actually being written - recording, not paused,
     * not closed. It decides one thing: whether the blue line is allowed to reach forward.
     * While a piece is open, the newest recorded position is drawn by the gliding tail rather
     * than by the polyline, so the line grows under the arrow at the arrow's speed instead of
     * jumping a whole fix once a second. While paused or after a stop, nothing is being
     * written, and a line that grew toward the arrow would draw ground the file never saw.
     */
    writing: Boolean,
    modifier: Modifier = Modifier,
    /** Labels beside the markers; too wide for the 72 dp row map, useful on the card. */
    showLabels: Boolean = true,
    /**
     * The 0-50 runs of the drive on screen ([com.alefinot.dashboardpp.trips.AccelRun]), oldest
     * first. Each is drawn as its own line over the drive's cyan — the same ground, in the ink of
     * a result — and a tap on it is what reads the number out.
     *
     * It is not a field of [route] because the frame lane collects them, not the recorder: a launch
     * the owner made before anybody pressed START is still a launch. It travels beside the route
     * and clears with it, which is the one rule that matters for a picture.
     */
    runs: List<AccelRun> = emptyList(),
    /**
     * The run whose result is on screen, if any. It is the one line that wears a white halo; no
     * overlay moves, and the camera stays where the owner left it.
     */
    selectedRun: AccelRun? = null,
    /**
     * A tap landed on a run, or landed on open ground — the run, or null. That pair is what lets
     * the page open a card on one and close it on the next tap. A map that is not handed a listener
     * keeps the gesture it always had, and the show-only thumbnail never reaches this door at all.
     */
    onRunTap: ((AccelRun?) -> Unit)? = null,
    /**
     * False makes the map a picture of the drive: no pan, no pinch or double-tap zoom,
     * no +/âˆ’ control â€” the view refuses every touch at its dispatch door, so the
     * gesture belongs to the page and the map never even sees it.
     */
    interactive: Boolean = true,
    /**
     * View-local rectangles the HUD glass owns. A touch that starts inside one belongs to
     * Compose, not to the map: the Live page's shelves report their bounds here so the
     * recorder pill, the fuel rail and a swipe across a shelf keep their gestures. Only a
     * DOWN is refused; a pan that began on the open map keeps the whole stream.
     */
    touchRefusals: List<Rect> = emptyList(),
    /**
     * How far the RE-CENTER label sits off the bottom of the map slot. The Live page's
     * bottom shelf covers the map's bottom edge, so the label is lifted above it; on the
     * Trips thumbnail this stays at its 6 dp.
     */
    recenterBottom: Dp = 6.dp,
    /**
     * The page's zoom rail, as two counts. Pressing **+** raises [zoomInTicks] by one and
     * the map travels one level in; the map only ever acts on the difference since the last
     * pass, so a card that recomposes at frame rate for its gliding figures asks for no
     * zoom at all. Counters rather than callbacks because the map view is created inside
     * this composable and the buttons live outside it, over it - and a control painted over
     * a platform view cannot reach into it any other way than by being handed the request.
     * The travel itself is the holder's (`ZOOM_RAMP_MS`), not the library's animated zoom,
     * which would drop every press that lands while another is still moving.
     */
    zoomInTicks: Int = 0,
    zoomOutTicks: Int = 0,
) {
    OsmdroidSetup.ensure(LocalContext.current)
    // The ground the owner chose (App Settings → Live Map). It is read here, as Compose
    // state, for two reasons: a pick on the settings page has to redraw this map while the
    // owner is looking at it, and `build` needs the style in hand to put anything on screen
    // at all — the first frame of a cold start should already be the ground they chose.
    val style = MapStylePref.current
    val holder = remember { LiveMapHolder(showLabels, interactive) }
    // The newest position the vehicle reported, and the history its direction is measured
    // across. Both are keyed by identity: the frame lane hands out a new trail list once a
    // second and the same one in between, so a card that recomposes at frame rate for its
    // gliding figures costs this map nothing.
    val cursor = remember(trail) { trail.lastOrNull()?.let { GeoPoint(it.lat, it.lon) } }
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
            factory = { ctx -> holder.build(ctx, style) },
            update = { map ->
                // First, because it can change the zoom bounds the rail clamps to and the ink
                // every line wears. It returns fast when nothing changed, which is every pass
                // but the one where the owner picked something.
                holder.applyStyle(map, style)
                holder.touchRefusals = touchRefusals
                holder.onRunTap = onRunTap
                holder.zoom(map, zoomInTicks, zoomOutTicks)
                holder.applyRoute(map, route, cursor, trail, writing, inView, runs, selectedRun)
            },
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
                Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = recenterBottom),
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
 * The overlays are created once per drive and then moved: at one update a second there is
 * nothing to gain from rebuilding a [Polyline] and its markers every time, and each
 * segment's first position never moves ([com.alefinot.dashboardpp.trips.LiveTrack]'s rule),
 * so its START dot is placed once and never touched again. A polyline is *appended* to rather
 * than re-set â€” see [mirrors] for why osmdroid makes that a rule and not a preference.
 *
 * The arrow is the exception that proves the rule: it is also placed once and then only
 * turned, and the turn is a float on its icon, so the frame ticker never builds anything.
 * The heading it turns to is opened per fix in [advance] and interpolated per frame in
 * [glide], which keeps the head's rotation on the same clock as the dot's travel.
 *
 * The blue line rides that same clock: while a piece is open its newest fix is held back from
 * the polyline and drawn by [tail] out to the gliding dot, so the line is generated under the
 * arrow instead of ahead of it ([tail]'s note has the arithmetic).
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
    private var driveId = 0L

    /**
     * The ground on this view — source, tint, wash, zoom bounds — and the ground it was last
     * asked for. See [applyStyle].
     */
    private val chrome = MapStyleApplier()

    /** The style the lines were last inked for, so [inkPieces] can be re-run on a change. */
    private var style: MapStyle? = null

    /** One polyline per recorded piece, created once per drive. */
    private val lines = ArrayList<Polyline>()

    /**
     * The same pieces again, wider and underneath, in the ink the ground needs
     * ([MapStyles.casing]): a dark edge on a pale basemap, a heavier one on aerial imagery,
     * a cyan bloom on a dark one. The pair shares one point list, so a casing costs no
     * geometry and no bookkeeping — an append feeds both — only one more stroked path per
     * drawn frame. Insertion order keeps every line above its own casing
     * ([MapStyleApplier.lineInsertAt]).
     */
    private val casings = ArrayList<Polyline>()

    /**
     * The map's own copy of each piece, in the geometry osmdroid draws.
     *
     * It exists because a polyline's points may only be touched through [Polyline.setPoints]
     * and [Polyline.addPoint]: osmdroid caches a piece's projected pixel coordinates and its
     * bounding box and invalidates them from those two methods alone, so writing into the list
     * `getPoints()` hands back (a direct link to its internals) leaves the line frozen in the
     * shape it had when the cache was filled. A mirror lets an update append the new fix or
     * two instead of handing 40 000 of them back every second.
     */
    private val mirrors = ArrayList<ArrayList<GeoPoint>>()

    /**
     * The 0-50 runs this map has drawn.
     *
     * The ink, the width and the tap radius are not here: they are [RunOverlay]'s, because the trip
     * detail page draws the same runs from a recorded file and a run that changes appearance between
     * the two maps teaches the owner two languages for one thing.
     *
     * A run's geometry is finished business the moment the unit closes it — the frame lane hands the
     * same object with the same points for as long as the drive is on screen — so a run's line is
     * built once, fed once, and never touched again until the drive leaves the map. That is what
     * makes an update of the run lines a rebuild over a couple of dozen items rather than a diff of
     * a drive's worth of points, and why the frames that mention no result cost nothing but the
     * reference check in [applyRoute].
     */
    private val runOverlay = RunOverlay()

    /** The run list already drawn, by identity — what lets [applyRoute] skip [RunOverlay.sync]. */
    private var lastRuns: List<AccelRun>? = null

    /**
     * Where a tap landed, as the page wants it: a run, or null for open ground. Read from the view's
     * touch door, like [touchRefusals].
     */
    var onRunTap: ((AccelRun?) -> Unit)? = null

    private val starts = ArrayList<Marker?>()

    /**
     * One dot per pause of this drive, in the order the pauses happened. They are never moved
     * and never removed while the drive is on the maps: each one marks the place the owner
     * stopped writing, and a third pause of a drive reads as three dots, not as the last one
     * (2026-11-18).
     */
    private val pauses = ArrayList<Marker>()
    private var endMarker: Marker? = null
    private var liveArrow: HeadingMarker? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** The route already consumed, by identity - see [applyRoute]. */
    private var lastRoute: LiveRoute? = null

    /** The vehicle's trail already consumed, by identity - see [applyRoute]. */
    private var lastTrail: List<TrackPoint>? = null

    /** True once a closed drive's END dot has been parked under the camera. */
    private var endSnapped = false

    /** True once that parking has really moved the camera - the view may have been unmeasured. */
    private var endCentered = false

    /** False once a finger takes the camera; drives the RE-CENTER pill's visibility. */
    val following = mutableStateOf(true)

    /**
     * View-local rectangles the HUD glass owns (see [LiveRouteMap]'s `touchRefusals`).
     * Read only from the view's touch door, on DOWN; the list is replaced on layout.
     */
    var touchRefusals: List<Rect> = emptyList()

    /** True when a touch starting at [x],[y] belongs to Compose, not to this map. */
    fun refusesAt(x: Float, y: Float): Boolean {
        for (r in touchRefusals) {
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
        }
        return false
    }

    /** The rail's step counts already consumed. Negative until the first pass, which only learns them. */
    private var zoomInSeen = -1
    private var zoomOutSeen = -1

    /** The level a rail step is travelling to, and the level it started from. NaN when settled. */
    private var zoomTo = Double.NaN
    private var zoomFrom = Double.NaN

    /** When the current leg started. */
    private var zoomStartMs = 0L

    /**
     * One frame of a zoom step, and the next one, until the level is reached.
     *
     * The holder owns this travel because the camera has one authority in this file. The
     * library's own animated form (`controller.zoomTo(level, duration)`) is a second
     * authority, and it is also a silent one: `MapController.zoomToFixing` returns `false`
     * and does nothing at all while its animator is still running, so the second and third
     * press of a fast run would simply be dropped (checked against the 6.1.20 source,
     * 2026-11-19). Here a press during a travel re-aims the travel instead.
     *
     * The shape of the travel is [easeEase] - an ease-in-out, the "easy ease" asked for on
     * 2026-11-19: the level leaves the old value gently, crosses the middle at its fastest
     * and is caught at the new one.
     */
    private val zoomRamp = object : Runnable {
        override fun run() {
            val view = map ?: return
            if (zoomFrom.isNaN() || zoomTo.isNaN()) return
            val t = ((SystemClock.uptimeMillis() - zoomStartMs).toDouble() / ZOOM_RAMP_MS)
                .coerceIn(0.0, 1.0)
            view.setZoomLevel(zoomFrom + (zoomTo - zoomFrom) * easeEase(t))
            view.invalidate()
            if (t < 1.0) {
                view.postOnAnimation(this)
            } else {
                zoomFrom = Double.NaN
                zoomTo = Double.NaN
            }
        }
    }

    /**
     * One level of zoom per step the page's rail has asked for since the last pass, travelled
     * to over [ZOOM_RAMP_MS] rather than jumped to.
     *
     * Only the difference is applied, so the map never zooms on a recomposition that asked
     * for nothing - the cards around it glide their figures at frame rate and this lambda
     * runs with them. Everything here stays on the **view's** side of the boundary
     * ([MapZoom]): the level it reads back and the bounds it clamps to are both the view's
     * (style bounds, one tile generation deeper than the levels an owner reads), and a step
     * is one level of ground in either world, so no translation is needed in between. The
     * clamp is what keeps twenty presses at the edge of the world from asking osmdroid for a
     * level its source has no tiles for.
     *
     * A step never touches [following] - asking for more detail is not the same claim as
     * taking the camera.
     */
    fun zoom(view: MapView, zoomInTicks: Int, zoomOutTicks: Int) {
        if (zoomInSeen < 0) {
            zoomInSeen = zoomInTicks
            zoomOutSeen = zoomOutTicks
            return
        }
        val steps = (zoomInTicks - zoomInSeen) - (zoomOutTicks - zoomOutSeen)
        zoomInSeen = zoomInTicks
        zoomOutSeen = zoomOutTicks
        if (steps == 0) return
        // The new target is measured from where the travel is HEADING, not from where the
        // view stands this frame: three fast presses are three levels, not one and a half.
        val base = if (zoomTo.isNaN()) view.zoomLevelDouble else zoomTo
        val level = base.plusClamped(steps, view)
        val from = view.zoomLevelDouble
        view.removeCallbacks(zoomRamp)
        if (level == from) {
            // At the edge of the world: there is nothing to travel to, and a travel already
            // in flight was asked for by a press that had somewhere to go.
            zoomFrom = Double.NaN
            zoomTo = Double.NaN
            return
        }
        zoomFrom = from
        zoomTo = level
        zoomStartMs = SystemClock.uptimeMillis()
        view.postOnAnimation(zoomRamp)
    }

    /** The rail's travel is over: it stops mid-frame, and the level it reached is kept. */
    private fun cancelZoomRamp(view: MapView) {
        view.removeCallbacks(zoomRamp)
        zoomFrom = Double.NaN
        zoomTo = Double.NaN
    }

    /** [other] zoom levels away, held inside the levels this view can actually draw. */
    private fun Double.plusClamped(other: Int, view: MapView): Double =
        (this + other).coerceIn(view.minZoomLevel, view.maxZoomLevel)

    /** Where the dot stands when the newest fix landed - the segment's start. */
    private var segFrom = GeoPoint(0.0, 0.0)

    /** The newest fix - the segment's end, and where the dot lands at t = 1. */
    private var segTo = GeoPoint(0.0, 0.0)

    /**
     * The piece being written, drawn forward to wherever the arrow has got to.
     *
     * The route reaches the phone one fix a second, and a polyline handed that fix grows a
     * whole car-length in one frame: the owner's words were "the blue route should generate
     * under the arrow and smoothly like the arrow so the generation is not visible"
     * (2026-11-18). So while a piece is open the polyline deliberately stops one position short
     * of the drive's newest fix and this two-point line covers the rest, ending exactly at the
     * gliding dot - the same ink, the same width, joined at the point the polyline already
     * holds. When the next fix lands, that held-back position moves into the polyline and the
     * tail moves on with the arrow. Nothing jumps, because the tail's end and the dot are the
     * same number.
     *
     * Only while a piece is open. A pause or a stop writes nothing, and a line that carried on
     * growing toward a moving arrow would draw ground the file never saw.
     */
    private var tail: Polyline? = null

    /** The tail's own casing, created with it and removed with it. */
    private var tailCasing: Polyline? = null

    /** The tail's two ends, reused every frame: where the polyline stops, and the dot. */
    private val tailStart = GeoPoint(0.0, 0.0)
    private val tailTo = GeoPoint(0.0, 0.0)
    private val tailPoints = arrayListOf(tailStart, tailTo)

    /** Whether the last update was told a piece is being written; drives [syncTail]. */
    private var writingNow = false

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
            // The blue line's forward edge travels with the dot, so the growth the eye can
            // see is the arrow's own, not one jump a second. setPoints on two points is a
            // couple of assignments; this is the only per-frame cost the tail has.
            tail?.let {
                tailTo.setCoords(dot.latitude, dot.longitude)
                it.setPoints(tailPoints)
            }
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

    fun build(context: Context, style: MapStyle): MapView {
        // A drag is a drag past this much travel; anything less, and the finger meant a tap.
        val slopPx = 12f * context.resources.displayMetrics.density
        val holder = this
        // The subclass exists for four jobs, plus the show-only door below - all of
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
        //    "give me the car back" gesture and re-centers;
        // 4. a tap that lands on a 0-50 run's line asks about that run, and is read
        //    before it is spent on the camera - which is job 3's own rule: the number
        //    would be a poor answer if the ground it belongs to slid away at the same
        //    time. osmdroid's own clickable overlays are not used for this, because the
        //    answer has to reach a page that cannot see touches in this slot at all.
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
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    holder.refusesAt(event.x, event.y)
                ) {
                    // The HUD shelf under this point owns the gesture. Same door as the
                    // show-only refusal above: Compose's hit path gets the DOWN, so the
                    // recorder pill can be pressed and a swipe across a shelf pages.
                    // Only DOWN is checked - a pan that began on the open map keeps the
                    // rest of its stream even when it crosses glass.
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
                        if (tapped) {
                            // The run line is asked first, while the picture is still the one the
                            // owner aimed at: re-centring and reporting at once would read out a
                            // number and move the ground it belongs to in the same gesture.
                            val hit = holder.runAt(this, event.x, event.y)
                            holder.onRunTap?.invoke(hit)
                            // A hit, or the re-centre the RE-CENTER label promises: either way the
                            // tap is spent here, and the library's own double-tap-to-zoom never sees
                            // the second half of a pair. An open tap while the camera still follows
                            // falls through to the library, exactly as it did before runs existed.
                            if (hit != null) return true
                            if (!following.value) {
                                resume()
                                return true
                            }
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
        if (interactive) {
            view.setMultiTouchControls(true)
        } else {
            // Show-only: no pinch or double-tap zoom from the gesture detector, no +/âˆ’
            // control (on a thumbnail it paints two slabs over the drive anyway), and
            // dispatchTouchEvent above refuses the touch so the page keeps the gesture.
            // The camera is the follow's alone.
            view.setMultiTouchControls(false)
            view.setBuiltInZoomControls(false)
        }
        // Tiles are a bonus, never a dependency - same rule as the detail map. The tile size
        // and the zoom bounds belong to the ground (MapStyleApplier owns them), not to this view.
        view.setUseDataConnection(true)
        // The owner's ground: tile size, source, tint, wash and the zoom bounds the rail
        // clamps to. What used to be a hard-coded MAPNIK with a hard-coded 4..19 on both maps.
        chrome.apply(view, style)
        this.style = style
        map = view
        return view
    }

    /**
     * The owner picked another ground, or this view has just been built: move the style onto
     * it, and re-ink every line the new ground needs.
     *
     * Called from the `update` lambda, which Compose re-runs at frame rate while the cards
     * around the map glide their figures, so the whole thing is an identity compare except on
     * the one pass that matters. On that pass: the view's tile source is swapped (its
     * in-memory tile cache goes with it, and the on-disk cache is keyed by the source's name,
     * so going back to a ground the owner has drawn before is free), the zoom bounds move to
     * the new source's, and each piece's casing changes ink without its points being touched.
     */
    fun applyStyle(view: MapView, style: MapStyle) {
        if (this.style === style && chrome.isApplied(style)) return
        chrome.apply(view, style)
        this.style = style
        inkPieces(view)
        // The run lines wear the same ground ink the drive's pieces do, so they are re-dressed in
        // the same pass - the yellow itself never changes, only the casing under it.
        runOverlay.ink(view, style)
        view.invalidate()
    }

    /**
     * One update of the route: [geo] is every recorded piece as map points, [cursor] the
     * newest fix (recorded or not), and [route] the dots the pieces end at.
     *
     * The cards around this map recompose at frame rate while their figures glide
     * (ui/components/HudFlow.kt), and AndroidView re-runs this lambda on every one
     * of those passes. The geometry is remembered by identity upstream, so the
     * reference test below is the whole filter: re-walking every point sixty times
     * a second to conclude "same drive" is exactly the step this file exists to avoid.
     * Coming back into view is the one change that has to get through the filter.
     */
    fun applyRoute(
        view: MapView,
        route: LiveRoute,
        cursor: GeoPoint?,
        trail: List<TrackPoint>,
        writing: Boolean,
        inView: Boolean,
        runs: List<AccelRun>,
        selectedRun: AccelRun?,
    ) {
        val woke = inView && !active
        active = inView
        if (!active) stopTicker(view)
        val newDrive = route.driveId != driveId
        if (newDrive) reset(view, route.driveId)
        // The selection is outside the route's identity on purpose: a tap does not change the
        // drive, and the run the card is open on is not part of the drive's shape. Two reference
        // compares per pass, and on the one that differs the run lines are re-dressed - no
        // overlay is added, moved or removed, and the camera is not touched.
        if (selectedRun !== runOverlay.selected) {
            runOverlay.selected = selectedRun
            runOverlay.ink(view)
        }
        // All three inputs are compared by identity: the recorder and the frame lane hand out a new
        // route / a new trail / a new run list once a second and the same objects in between, so a
        // page that recomposes at frame rate for its gliding figures costs this map a reference
        // check. `writing` is checked by value because a PAUSE flips it with the route unchanged,
        // and a tail left drawing across a pause is a line over ground that was never recorded.
        val changed = newDrive || woke || route !== lastRoute || trail !== lastTrail ||
            runs !== lastRuns
        if (changed || writing != writingNow) {
            lastRoute = route
            lastTrail = trail
            lastRuns = runs
            writingNow = writing
            syncSegments(view, route.segments, writing)
            syncMarkers(view, route, cursor)
            runOverlay.sync(view, runs, chrome.lineInsertAt + 2 * lines.size, style)
            if (cursor != null) advance(trail, cursor, newDrive)
        }
        if (route.closed) closeAt(view, route.endAt) else {
            endSnapped = false
            endCentered = false
        }
        // Overlay swaps do not invalidate the view by themselves (MapHolder lesson).
        view.invalidate()
        frameAndRun(view)
    }

    /**
     * The half of an update that needs a measured view - also what runs when the layout
     * finally lands, which is why it is not folded into [applyRoute].
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
            // The drive's one zoom outranks a rail travel: this is the camera being placed,
            // once, at the first fix. A press that landed in the frame or two before this
            // asked for detail on a view that did not know where the car was yet, so it is
            // dropped - and dropped cleanly, with no half-finished travel left to fight the
            // level set here.
            cancelZoomRamp(view)
            // The level is the owner's 15; the view works one tile generation deeper (MapZoom).
            view.controller.setZoom(MapZoom.toView(FOLLOW_ZOOM))
            if (!endSnapped) snapCamera(view)
        }
        if (endSnapped && !endCentered) {
            // The stop is the one camera move nobody is chasing: land the view on the END dot.
            // It is a landing, not a lid - a drive that keeps being watched (the app is open,
            // so the frame lane is) keeps moving from here, and the next fix takes the camera
            // with it. Parking the view for good was the old behaviour, and it is what made a
            // stopped drive's map look dead (2026-11-18).
            endCentered = true
            snapCamera(view)
        }
        if (active) startTicker(view)
    }

    /** The drive closed: park the arrow on the END dot and let the camera land there. */
    private fun closeAt(view: MapView, end: TrackPoint?) {
        if (endSnapped || end == null) return
        stopTicker(view)
        dot.setCoords(end.lat, end.lon)
        segFrom.setCoords(end.lat, end.lon)
        segTo.setCoords(end.lat, end.lon)
        hasFix = true
        drawn = GeoPoint(dot)
        following.value = true
        endSnapped = true
        endCentered = false
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
        chrome.release()
        style = null
        lines.clear()
        casings.clear()
        mirrors.clear()
        runOverlay.clear()
        lastRuns = null
        starts.clear()
        pauses.clear()
        endMarker = null
        liveArrow = null
        tail = null
        tailCasing = null
        lastRoute = null
        lastTrail = null
        writingNow = false
        hasFix = false
        framed = false
        endSnapped = false
        endCentered = false
        drawn = null
        heading = 0f
        headingFrom = 0f
        headingTo = 0f
    }

    /**
     * A different drive, as opposed to this one gaining a metre: every overlay is thrown
     * away and the next fix reframes the view. A route that merely went from open to closed
     * keeps its pieces - that is the whole point of the END dot.
     */
    private fun reset(view: MapView, id: Long) {
        stopTicker(view)
        for (line in lines) view.overlayManager.remove(line)
        for (line in casings) view.overlayManager.remove(line)
        // The runs go with the drive they belong to, not with the list they arrived in: a new
        // drive's first fix replaces the picture, and a yellow line from the drive before it would
        // be drawn on ground the new drive never drove.
        runOverlay.removeFrom(view)
        hideTail(view)
        for (marker in starts) marker?.let { view.overlayManager.remove(it) }
        for (marker in pauses) view.overlayManager.remove(marker)
        endMarker?.let { view.overlayManager.remove(it) }
        liveArrow?.let { view.overlayManager.remove(it.marker) }
        lines.clear()
        casings.clear()
        mirrors.clear()
        runOverlay.clear()
        lastRuns = null
        starts.clear()
        pauses.clear()
        endMarker = null
        liveArrow = null
        driveId = id
        lastRoute = null
        lastTrail = null
        writingNow = false
        hasFix = false
        framed = false
        endSnapped = false
        endCentered = false
        drawn = null
        heading = 0f
        headingFrom = 0f
        headingTo = 0f
        following.value = true
    }

    /**
     * The few hundred metres the arrow's direction is measured across: the newest of the
     * positions the vehicle reported, oldest first.
     *
     * This used to walk the *recorded* route, which is a good compass only while the recorder
     * is writing. A pause freezes the route and not the car, so the bearing came to be taken
     * from the pause dot to wherever the car had got to - a head that points back at a decision
     * instead of down the road (2026-11-18). The trail moves when the car moves, in every state
     * a drive can be in, which is the only rule a compass should obey.
     */
    private fun headingPoints(trail: List<TrackPoint>): List<TrackPoint> =
        if (trail.size <= HEADING_POINTS) trail
        else trail.subList(trail.size - HEADING_POINTS, trail.size)

    /**
     * Open a new segment for the newest fix, starting from where the dot stands now.
     *
     * Starting from the previous *fix* would jump the dot backwards whenever a frame
     * arrives before the last segment finished - which is what jitter does. Starting
     * from the dot makes the motion continuous in both directions, at the cost of the
     * dot never quite catching the fix: it is at most one frame behind, which is the
     * whole price of smooth and the right thing to pay on a dash.
     *
     * The segment's length is the arrival interval that was actually measured, not a
     * constant: the unit streams at 1 Hz but the radio delivers when it likes, and a
     * fixed 1000 ms makes a slow frame visibly lag and a fast one visibly snap.
     *
     * The arrow's turn opens here for the same reason the glide does: one fix is one new
     * direction, and the segment that carries the dot is what decides how long the head has
     * to get round to it.
     */
    private fun advance(trail: List<TrackPoint>, newest: GeoPoint, restart: Boolean) {
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
        turnHeading(trail, restart)
    }

    /**
     * Aim the arrow at the drive's newest direction of travel.
     *
     * Two ways to have no direction: the drive has not covered the baseline yet (a trip's
     * first seconds), or it has stopped. Both answer with the last heading, which is the
     * honest answer for a vehicle that has not gone anywhere - and the safe one, because a
     * heading read out of receiver noise is an arrow that slowly circles a parked drive.
     */
    private fun turnHeading(trail: List<TrackPoint>, restart: Boolean) {
        if (restart) {
            heading = 0f
            headingFrom = 0f
            headingTo = 0f
            liveArrow?.setBearing(0f)
            return
        }
        val bearing = LiveHeading.of(headingPoints(trail)) ?: return
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
        // A parked page animates nothing. The level a half-finished travel reached is kept;
        // only the next frame of it is refused.
        cancelZoomRamp(view)
    }

    /** A finger moved the map: the camera is the user's until they ask for the car back. */
    private fun takeCamera() {
        if (following.value) following.value = false
    }

    /**
     * One polyline per recorded piece. New lines are inserted under the markers and the
     * arrow, so a segment that starts later can never paint over the live position - the
     * overlay manager draws in list order, and the order of the pieces among themselves
     * does not matter because they share one ink.
     *
     * An update only ever feeds a line what it has not seen: a piece grows at its end, and
     * [LiveTrack] rewrites its middle only when it re-examines it, which is rare and shows up
     * as the piece having become shorter. That one test tells an append from a rebuild, and an
     * append is [Polyline.addPoint] on a fix or two instead of [Polyline.setPoints] on all of
     * them - osmdroid rebuilds a polyline's projected coordinates either way, and making a
     * 1000 km drive do that every second was the cost this avoids.
     */
    private fun syncSegments(
        view: MapView,
        segments: List<List<TrackPoint>>,
        writing: Boolean,
    ) {
        while (lines.size < segments.size) {
            val piece = newPiece(view)
            lines.add(piece.first)
            casings.add(piece.second)
            mirrors.add(ArrayList())
        }
        for (i in segments.indices) {
            val src = segments[i]
            val mirror = mirrors[i]
            // While a piece is open its newest position belongs to the tail, not to the line.
            val target = if (writing && i == segments.size - 1) src.size - 1 else src.size
            if (target < mirror.size) {
                mirror.clear()
                for (j in 0 until target) mirror.add(GeoPoint(src[j].lat, src[j].lon))
                lines[i].setPoints(mirror)
                casings[i].setPoints(mirror)
            } else {
                for (j in mirror.size until target) {
                    val point = GeoPoint(src[j].lat, src[j].lon)
                    mirror.add(point)
                    lines[i].addPoint(point)
                    casings[i].addPoint(point)
                }
            }
            if (src.isNotEmpty()) ensureStart(view, i, src[0])
        }
        syncTail(view, mirrors.lastOrNull())
    }

    /**
     * Point the tail at the newest gliding position, or take it away.
     *
     * A tail needs a recorded position to grow out of: the last point the polyline holds. With
     * a piece's very first fix there is no such point, and inventing one - growing out of where
     * the arrow came from - would draw straight across a pause gap, which is the one thing this
     * map refuses to do. So the first fix of a piece shows its START dot and waits a second.
     */
    private fun syncTail(view: MapView, mirror: ArrayList<GeoPoint>?) {
        val from = mirror?.lastOrNull()
        if (!writingNow || from == null) {
            hideTail(view)
            return
        }
        val line = tail ?: newTail(view)
        tailStart.setCoords(from.latitude, from.longitude)
        tailTo.setCoords(from.latitude, from.longitude)
        line.setPoints(tailPoints)
        tailCasing?.setPoints(tailPoints)
    }

    private fun hideTail(view: MapView) {
        tail?.let { view.overlayManager.remove(it) }
        tailCasing?.let { view.overlayManager.remove(it) }
        tail = null
        tailCasing = null
    }

    /**
     * The dots.
     *
     * START is one per recorded piece, placed once and never moved - a drive that paused twice
     * says so with two STARTs, because each piece really did begin somewhere. PAUSE is one per
     * pause, kept for the whole life of the drive: they used to be a single dot that a resume
     * deleted, which left a drive that stopped three times showing one pause and a mystery
     * (2026-11-18). END marks where a closed drive's last sample was written.
     *
     * The arrow is the vehicle, not the drive, so it is shown for as long as the phone is being
     * told where the vehicle is - through a pause, and after a stop while the app is watching.
     * The one position it does not need is the END dot itself: parked at the end of a drive with
     * nothing new coming, the dart would just be a second mark on a place the red dot already
     * claims.
     */
    private fun syncMarkers(view: MapView, route: LiveRoute, cursor: GeoPoint?) {
        val atTheEnd = cursor != null && route.endAt != null &&
            cursor.latitude == route.endAt!!.lat && cursor.longitude == route.endAt!!.lon
        if (cursor != null && !atTheEnd) {
            if (liveArrow == null) {
                // Created pointing north; the first fix with six metres behind it aims it.
                val arrow = MapMarkers.heading(
                    view, cursor, HudColors.Green.toArgb(), if (showLabels) "LIVE" else "",
                )
                liveArrow = arrow
                view.overlayManager.add(arrow.marker)
            }
        } else if (liveArrow != null) {
            view.overlayManager.remove(liveArrow!!.marker)
            liveArrow = null
        }
        syncPauses(view, route.pauses)
        endMarker = syncDot(view, endMarker, route.endAt, HudColors.Red.toArgb(), "END")
    }

    /**
     * One orange dot per pause of this drive.
     *
     * Created in order and never repositioned: each marks a place the owner chose and the
     * recorder wrote, so the only pause dot that could ever move is the one that is still in
     * progress - and that one is appended here as soon as it stops moving. Extra dots are the
     * list having been replaced under us (a new drive), and they come off with the rest.
     */
    private fun syncPauses(view: MapView, points: List<TrackPoint>) {
        while (pauses.size > points.size) {
            view.overlayManager.remove(pauses.removeAt(pauses.size - 1))
        }
        for (i in points.indices) {
            if (i < pauses.size) continue
            val created = MapMarkers.labelled(
                view,
                GeoPoint(points[i].lat, points[i].lon),
                HudColors.Amber.toArgb(),
                if (showLabels) "PAUSE" else "",
            )
            view.overlayManager.add(created)
            pauses.add(created)
        }
    }

    /**
     * Create, move or remove one named dot. Created last so it sits over the line it marks;
     * moved in place so a dot that follows a moving thing does not build a marker a second.
     */
    private fun syncDot(
        view: MapView,
        marker: Marker?,
        at: TrackPoint?,
        color: Int,
        label: String,
    ): Marker? {
        if (at == null) {
            marker?.let { view.overlayManager.remove(it) }
            return null
        }
        val point = GeoPoint(at.lat, at.lon)
        val existing = marker
        if (existing == null) {
            val created = MapMarkers.labelled(
                view, point, color, if (showLabels) label else "",
            )
            view.overlayManager.add(created)
            return created
        }
        existing.position = point
        return existing
    }

    private fun ensureStart(view: MapView, index: Int, first: TrackPoint) {
        while (starts.size <= index) starts.add(null)
        if (starts[index] != null) return
        // The piece's first position never moves (LiveTrack's rule), so this dot is
        // placed once when the piece appears and never repositioned.
        val marker = MapMarkers.labelled(
            view, GeoPoint(first.lat, first.lon), HudColors.Green.toArgb(),
            if (showLabels) "START" else "",
        )
        starts[index] = marker
        view.overlayManager.add(marker)
    }

    /**
     * One recorded piece: the route's line and the casing it needs on this ground.
     *
     * Both are dressed by [inkPair] the moment they exist, and from then on fed the same
     * points by [syncSegments] — the casing is a second stroked path over one point list, so
     * it costs no geometry, no mirroring and no bookkeeping.
     */
    private fun newPiece(view: MapView): Pair<Polyline, Polyline> {
        val line = newLine()
        val casing = newLine()
        insertPair(view, line, casing)
        inkPair(view, line, casing)
        return line to casing
    }

    /** The forward tail, as a pair like a piece — the line grows under the arrow cased or not. */
    private fun newTail(view: MapView): Polyline {
        val line = newLine()
        val casing = newLine()
        insertPair(view, line, casing)
        inkPair(view, line, casing)
        tail = line
        tailCasing = casing
        return line
    }

    /**
     * Put a line and its casing into the overlay list as one pair.
     *
     * The pairs start just above the wash ([MapStyleApplier.lineInsertAt]) and grow in
     * creation order, so a piece written later draws over one written earlier — chronology,
     * which is the way the drive happened — and the markers, appended at the end of the list,
     * stay over every line, which is the rule the old `add(0, …)` was there to serve.
     */
    private fun insertPair(view: MapView, line: Polyline, casing: Polyline) {
        val at = chrome.lineInsertAt + 2 * lines.size
        view.overlayManager.add(at, casing)
        view.overlayManager.add(at + 1, line)
    }

    private fun newLine(): Polyline {
        val poly = Polyline()
        val paint = poly.getOutlinePaint()
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        return poly
    }

    /** Re-dress every line this map owns: the ground changed underneath them. */
    private fun inkPieces(view: MapView) {
        for (i in lines.indices) inkPair(view, lines[i], casings[i])
        val line = tail
        val casing = tailCasing
        if (line != null && casing != null) inkPair(view, line, casing)
    }

    /**
     * The pair's ink for the ground they are drawn on: the route is always the app's cyan at
     * [ROUTE_DP], and the casing underneath it is [MapStyles.casing] — wider by that many dp,
     * in the colour the ground needs. Nothing about the points moves: a style change repaints
     * a 1 000 km drive without touching a coordinate of it.
     */
    private fun inkPair(view: MapView, line: Polyline, casing: Polyline) {
        val s = style ?: return
        val density = view.resources.displayMetrics.density
        val route = line.getOutlinePaint()
        route.color = HudColors.Cyan.toArgb()
        route.strokeWidth = ROUTE_DP * density
        val ink = MapStyles.casing(s)
        val outline = casing.getOutlinePaint()
        outline.color = ink.colorArgb
        outline.strokeWidth = (ROUTE_DP + ink.extraDp) * density
    }

    /**
     * The run under `(x, y)`, in view pixels, or null.
     *
     * The measuring is [RunOverlay]'s, shared with the trip detail page: a run is aimed at the same
     * way on both maps, and two hit tests would be two answers.
     */
    fun runAt(view: MapView, x: Float, y: Float): AccelRun? = runOverlay.hit(view, x, y)

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
        /**
         * The route's width in dp, on every ground. Its casing is wider than this by the
         * amount the ground asks for and sits underneath it.
         */
        private const val ROUTE_DP = 5f

        /**
         * A drive's corner while it happens: streets readable, a few blocks deep. An owner's
         * level — what the view is handed goes through [MapZoom].
         */
        private const val FOLLOW_ZOOM = 15.0

        /**
         * How long one step of the zoom rail takes to travel.
         *
         * "Make the zoom smooth" (2026-11-19) is not a taste note: a level set outright
         * replaces every tile on screen in a single frame, which reads as the picture
         * changing rather than as the owner asking for detail. 260 ms is the shortest travel
         * that still lets the eye see which way the ground went, and short enough that a run
         * of presses does not feel like a queue.
         */
        private const val ZOOM_RAMP_MS = 260L

        /** A sample period. The unit streams at 1 Hz (trip plan: one frame a second). */
        private const val SEG_MS = 1000L

        /** A segment shorter than this is jitter, not a drive: do not throw the dot. */
        private const val SEG_MIN_MS = 250L

        /** Longer than this and the stream has stopped; freeze rather than crawl. */
        private const val SEG_MAX_MS = 3000L

        /** Enough positions for a six-metre heading baseline, and no more per fix. */
        private const val HEADING_POINTS = 64
    }
}

/**
 * The shape of a zoom step: **ease-in-out** — what the maintainer called the "easy ease"
 * (2026-11-19).
 *
 * The level leaves the value it is on gently, crosses the middle of the step at its fastest,
 * and is caught at the value it was asked for. The first curve was one-sided (ease-out): it
 * launched at full speed on the first frame and only braked at the end, which reads as a
 * shove rather than as a lens being pushed. A cubic in two mirror halves — the same
 * acceleration into the step as out of it — is the shape of a pressed control: the eye gets
 * time at both ends, and the step has a beginning and an ending instead of a start.
 *
 * This is deliberately NOT the telemetry figures' rule. Those flow across one sample period
 * **linearly and on purpose** (see `ui/components/HudFlow.kt`): a wall of twelve numbers
 * easing in step reads as one pulse with dead air after it. A camera is not a wall of
 * numbers — it is a single object the owner just pushed, and a push has a wind-up.
 *
 * `internal`, not `private`, so `ZoomEaseTest` can measure the shape - the two exact
 * ends, the mirror halves, the slow start and the slow landing - instead of trusting that a
 * cubic written on a screen is the curve that draws.
 */
internal fun easeEase(t: Double): Double =
    if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3) / 2.0
