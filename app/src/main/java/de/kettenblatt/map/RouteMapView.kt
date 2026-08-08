package de.kettenblatt.map

import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.kettenblatt.data.Route
import de.kettenblatt.geo.Geo
import de.kettenblatt.nav.NavState
import de.kettenblatt.prep.TileSource
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.exp
import kotlin.math.hypot

/** How the map frames the route. Cycles in this order from the map button. */
enum class MapMode {
    /** The whole route at once, north up -- for planning and orientation. */
    OVERVIEW,

    /** Following the rider, course-up, far enough to read the next few streets. */
    NAVIGATION,

    /** Same, but tight in -- for picking the right exit at a busy junction. */
    NAVIGATION_CLOSE;

    val followsRider: Boolean get() = this != OVERVIEW

    fun next(): MapMode = when (this) {
        OVERVIEW -> NAVIGATION
        NAVIGATION -> NAVIGATION_CLOSE
        NAVIGATION_CLOSE -> OVERVIEW
    }
}

/**
 * The osmdroid map, wrapped for Compose.
 *
 * The route is drawn as two polylines split at the rider's position, so the part
 * already covered recedes and the part still to ride stands out -- much easier to
 * read at a glance than one uniform line.
 *
 * While following, the camera and the chevron are driven frame by frame from
 * [SmoothCamera] rather than jumping to each fix; see there for why.
 */
@Composable
fun RouteMapView(
    route: Route,
    state: NavState?,
    mode: MapMode,
    follow: Boolean,
    offlineTiles: File?,
    /**
     * Which rendering to draw. Required rather than defaulted: a new call site
     * silently getting a different map from the rest of the app is exactly the
     * bug this parameter exists to end.
     */
    style: TileSource,
    styleApiKey: String?,
    onUserPan: () -> Unit,
    navigationZoom: Double = NAVIGATION_ZOOM,
    closeZoom: Double = NAVIGATION_CLOSE_ZOOM,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val liveSource = remember(style, styleApiKey) { TileSources.online(style, styleApiKey) }

    // Zooming past what a sideloaded pack contains does not fall back to the
    // network -- osmdroid upscales the deepest tile it has, and the map turns
    // into blocky mush.
    //
    // The normal mode stays at the pack's own maximum so it is always crisp. The
    // close mode is allowed one level beyond, because a single upscale is still
    // readable and being able to zoom in at a junction is worth more than
    // perfect sharpness. The default pack now reaches OpenTopoMap's own deepest
    // level, so with the default zooms neither mode upscales at all; the
    // allowance is what keeps Close useful on a shallower sideloaded pack.
    val zooms = remember(offlineTiles, navigationZoom, closeZoom) {
        val packMax = offlineTiles
            ?.takeIf { it.exists() }
            ?.let { MbtilesMeta.read(it).maxZoom.toDouble() }
        if (packMax == null) {
            navigationZoom to closeZoom
        } else {
            minOf(navigationZoom, packMax) to minOf(closeZoom, packMax + 1)
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            // The zoom buttons overlap the stats panel and duplicate pinch-zoom.
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // Read inside an unkeyed remember on purpose: a MapView needs a
            // source before its first layout, and the effect below owns every
            // change after that.
            setTileSource(liveSource)

            // Ground with no tile -- outside a sideloaded pack, or still loading
            // online -- is drawn by osmdroid as a grey cross-hatch, which reads
            // as a broken image rather than as the edge of the map.
            //
            // An offline pack covers the route corridor, and a preview card is
            // wider than a route that runs north to south: the reference route
            // is 5.3 km across in a viewport 11.3 km wide, so nearly 3 km either
            // side is legitimately blank. Filling that with tiles would roughly
            // double the pack for ground nobody rides through, so the honest fix
            // is for blank to look deliberate.
            usePaperForBlankTiles()
        }
    }

    // Overlays are built once per route and then mutated in place.
    //
    // Rebuilding them on every fix meant allocating a GeoPoint per track point
    // twice a second on the main thread. Harmless for a 606-point planned route,
    // but a recorded activity imported as GPX runs to tens of thousands of
    // points, and that much churn at 1 Hz is what makes a map stutter.
    val overlays = remember(route) { RouteOverlays(route) }

    // One effect owns the tile provider, keyed on both inputs, because they
    // interact. A pack replaces the provider outright, so a separate style
    // effect firing afterwards would point the *archive* provider at a network
    // source -- a blank map claiming to be online. Two effects would get this
    // right only by declaration order, which is not a thing to depend on.
    DisposableEffect(offlineTiles, liveSource) {
        // A broken pack is ignored so the map still works.
        val pack = offlineTiles
            ?.takeIf { it.exists() }
            ?.let { TileSources.offline(context, it) }

        if (pack != null) {
            val (provider, source) = pack
            mapView.tileProvider.detach()
            mapView.tileProvider = provider
            mapView.setTileSource(source)
        } else {
            // A pack may have been in place before, and an archive provider
            // never fetches, so the network one has to come back with the style.
            if (mapView.tileProvider !is MapTileProviderBasic) {
                mapView.tileProvider.detach()
                mapView.tileProvider = MapTileProviderBasic(context, liveSource)
            }
            mapView.setTileSource(liveSource)
        }

        // Swapping the provider rebuilds the tiles overlay, taking the
        // blank-tile colours with it.
        mapView.usePaperForBlankTiles()
        onDispose { }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    ApplyMapMode(
        mapView = mapView,
        route = route,
        mode = mode,
        state = state,
        zoom = if (mode == MapMode.NAVIGATION_CLOSE) zooms.second else zooms.first,
    )

    val following = mode.followsRider && follow
    val pose = state?.let { RiderPose(it.snappedLat, it.snappedLon, it.routeBearingDeg) }

    // A fresh camera each time following resumes, so recentring or coming back
    // from the overview arrives at once. Gliding would be a slow crawl across
    // however far the map was dragged.
    val camera = remember(following) { SmoothCamera() }

    // One glide per fix. Cancelling this mid-flight -- which is what the next fix
    // does -- loses nothing, because the camera keeps its position in a field
    // and the replacement carries on from exactly where this one had got to.
    LaunchedEffect(camera, mapView, overlays, pose) {
        if (!following || pose == null) return@LaunchedEffect
        if (camera.placeAt(pose)) {
            camera.applyTo(mapView, overlays)
            return@LaunchedEffect
        }

        var previousNanos = withFrameNanos { it }
        while (true) {
            val nanos = withFrameNanos { it }
            val arrived = camera.advance(pose, (nanos - previousNanos) / NANOS_PER_SECOND)
            previousNanos = nanos
            camera.applyTo(mapView, overlays)
            // Standing still settles within a second or so; the loop then stops
            // asking for frames until the rider moves again.
            if (arrived) return@LaunchedEffect
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                // Detach follow-mode on a real finger drag.
                //
                // Deliberately driven from touch rather than osmdroid's scroll
                // events: centring, rotating and offsetting the map all raise
                // scroll events too, so a listener cannot tell the rider's drag
                // from the app's own camera work and follow mode switches itself
                // off on the first fix.
                var downX = 0f
                var downY = 0f
                var dragging = false
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            dragging = false
                        }

                        MotionEvent.ACTION_MOVE ->
                            if (!dragging && hypot(event.x - downX, event.y - downY) > slop) {
                                dragging = true
                                onUserPan()
                            }
                    }
                    false // never consume; the map still handles the gesture
                }

                overlays.attachIfNeeded(this)
                fitRouteWhenReady(route, animated = false)
            }
        },
        update = { map ->
            // Also here, not just in the factory: a new route means new overlays,
            // and the factory does not run again for an existing MapView.
            overlays.attachIfNeeded(map)
            overlays.updateCoverage(map, state)

            // While following, the camera above moves the chevron every frame;
            // moving it here as well would snap it back to the raw fix for the
            // one frame after each fix lands, which is a visible twitch.
            if (!following) {
                state?.let { overlays.showRider(it.snappedLat, it.snappedLon, it.routeBearingDeg) }
            }

            map.invalidate()
        },
    )
}

/**
 * The map overlays for one route, created once and updated in place.
 *
 * Only two things actually change while riding -- where the line splits between
 * covered and remaining, and where the rider is -- so only those are touched.
 */
private class RouteOverlays(private val route: Route) {

    /** Built once; the polylines below take sublists of this. */
    private val geoPoints: List<GeoPoint> = route.points.map { GeoPoint(it.lat, it.lon) }

    private val remaining = remainingLine()

    /**
     * One polyline per covered stretch.
     *
     * Coverage is not a single prefix of the route: skip a section, or detour
     * off and rejoin, and what you rode is several disjoint runs. Drawing them
     * separately is what lets a missed stretch stay bright.
     */
    private val travelled = ArrayList<Polyline>()
    private var rider: Marker? = null

    private var appliedCoverage = -1
    private var attachedTo: MapView? = null

    fun attachIfNeeded(map: MapView) {
        if (attachedTo === map) return
        attachedTo = map

        map.overlays.clear()
        map.overlays.add(remaining)

        route.waypoints.forEach { wp ->
            map.overlays.add(waypointMarker(map, GeoPoint(wp.lat, wp.lon), wp.name ?: wp.sym))
        }

        // Which way round the route goes. Two dots at the ends cannot say that
        // on a loop, where they sit almost on top of each other.
        DirectionArrows.along(route).forEach { arrow ->
            map.overlays.add(directionMarker(map, geoPoints[arrow.index], arrow.bearingDeg))
        }

        map.overlays.add(endpointMarker(map, geoPoints.first(), "Start", start = true))
        map.overlays.add(endpointMarker(map, geoPoints.last(), "Finish", start = false))

        rider = positionMarker(map, geoPoints.first()).also {
            // Before the first fix the chevron stands at the start; pointing it
            // north there would contradict the arrows it sits among.
            DirectionArrows.bearingAt(route, 0)?.let { b -> it.rotation = -b.toFloat() }
            map.overlays.add(it)
        }

        travelled.clear()
        appliedCoverage = -1
        remaining.setPoints(geoPoints)
    }

    /** Redraw the ridden stretches, but only when a new segment has been covered. */
    fun updateCoverage(map: MapView, state: NavState?) {
        val coveredCount = state?.covered?.coveredCount ?: 0
        if (coveredCount == appliedCoverage) return
        appliedCoverage = coveredCount
        applyCoverage(map, state?.covered)
    }

    /**
     * Put the chevron somewhere and point it along the route.
     *
     * Takes a bare position rather than a [NavState] because it is called both
     * with a fix and with the eased position between fixes.
     */
    fun showRider(lat: Double, lon: Double, bearingDeg: Double?) {
        rider?.let { marker ->
            marker.position = GeoPoint(lat, lon)
            bearingDeg?.let { marker.rotation = -it.toFloat() }
        }
    }

    private fun applyCoverage(map: MapView, covered: de.kettenblatt.nav.CoveredSegments?) {
        val runs = covered?.runs().orEmpty()

        // Reuse the polylines already on the map; only add or drop when the
        // number of disjoint covered stretches actually changes.
        while (travelled.size < runs.size) {
            travelledLine().also {
                travelled.add(it)
                // Above the route line, below the markers: the remaining line
                // spans the whole route, so anything underneath it is invisible.
                map.overlays.add(map.overlays.indexOf(remaining) + 1, it)
            }
        }
        while (travelled.size > runs.size) {
            map.overlays.remove(travelled.removeAt(travelled.lastIndex))
        }

        runs.forEachIndexed { i, run ->
            // Sublists share the backing list, so no coordinates are re-allocated.
            val from = run.first.coerceIn(0, geoPoints.lastIndex)
            val to = (run.last + 1).coerceIn(from + 1, geoPoints.size)
            travelled[i].setPoints(geoPoints.subList(from, to))
        }
    }
}

/** Where the camera is being asked to sit. Changes once per fix. */
private data class RiderPose(val lat: Double, val lon: Double, val bearingDeg: Double?)

/**
 * Eases the map toward the rider instead of jumping to each fix.
 *
 * Fixes land once a second and the map has to cover the second in between. What
 * was there before jumped: the camera was recentred on the nearest *track point*,
 * so it sat still and then lurched 29 m, and the rotation was applied outright
 * once the heading had drifted 3 degrees. On a course-up map that reads as a
 * twitch a second, which is most of what "abrupt" means here.
 *
 * This closes a fixed fraction of the remaining gap per frame instead.
 * Continuous by construction: there is nothing to start or finish, a late fix
 * only means the glide runs on a little longer, and because the position lives
 * in a field rather than inside an animator, being interrupted mid-glide costs
 * nothing -- the next one carries on from here.
 *
 * It moves the chevron as well as the camera. Both have to run on the same clock
 * or the marker slides about the screen while the map catches up behind it.
 *
 * The lag traded for all this is about [SETTLE_TIME_CONSTANT_S] seconds of
 * travel -- some 2 m at 20 km/h, comfortably inside GPS error.
 */
private class SmoothCamera {
    private var lat = 0.0
    private var lon = 0.0
    private var bearingDeg = 0.0
    private var placed = false

    /** Take up [pose] outright. True when there was nothing to glide from. */
    fun placeAt(pose: RiderPose): Boolean {
        if (placed) return false
        lat = pose.lat
        lon = pose.lon
        bearingDeg = pose.bearingDeg ?: 0.0
        placed = true
        return true
    }

    /**
     * Close part of the gap to [pose]. True once there is nothing left worth
     * moving for.
     */
    fun advance(pose: RiderPose, seconds: Double): Boolean {
        // Exponential, so the step is proportional to what remains: quick while
        // the gap is wide, imperceptible as it closes. Deriving it from the
        // elapsed time rather than counting frames is what makes a dropped frame
        // harmless -- the next one simply takes a larger step.
        val closed = 1.0 - exp(-seconds.coerceIn(0.0, LONGEST_USEFUL_FRAME_S) / SETTLE_TIME_CONSTANT_S)
        lat += (pose.lat - lat) * closed
        lon += (pose.lon - lon) * closed
        pose.bearingDeg?.let {
            // Kept inside 0..360 so the shortest-way-round arithmetic below
            // cannot drift out of range over a long ride.
            bearingDeg = (bearingDeg + shortestTurn(bearingDeg, it) * closed + 360.0) % 360.0
        }

        val toGo = Geo.haversine(lat, lon, pose.lat, pose.lon)
        val toTurn = pose.bearingDeg?.let { Geo.bearingDelta(bearingDeg, it) } ?: 0.0
        return toGo < SETTLED_M && toTurn < SETTLED_DEG
    }

    fun applyTo(map: MapView, overlays: RouteOverlays) {
        // A fresh GeoPoint each frame because setExpectedCenter keeps the
        // reference rather than copying it; a shared mutable one would be
        // rewritten under the map between frames.
        map.controller.setCenter(GeoPoint(lat, lon))
        // Negated because turning the map anticlockwise is what brings a
        // clockwise-from-north bearing to the top of the screen. This redraws on
        // its own, which is what carries the marker below with it.
        map.setMapOrientation(-bearingDeg.toFloat())
        overlays.showRider(lat, lon, bearingDeg)
    }
}

/** Degrees from [from] to [to], signed, never the long way round. */
private fun shortestTurn(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

/**
 * How long the camera takes to close most of the gap to a new fix.
 *
 * Sized against the 1 s default fix interval: long enough that the motion never
 * looks stepped, short enough that the map is all but caught up by the time the
 * next fix lands, so the lag never accumulates.
 */
private const val SETTLE_TIME_CONSTANT_S = 0.45

/**
 * Longest frame gap treated as a glide.
 *
 * Coming back from a paused map -- a dialog, the dim overlay, a stall -- the gap
 * is seconds, and easing across it in one step is a jump either way. Capping it
 * keeps the arithmetic honest rather than pretending to interpolate.
 */
private const val LONGEST_USEFUL_FRAME_S = 0.25

/** Close enough to stop asking for frames. Below this nothing is visible anyway. */
private const val SETTLED_M = 0.5
private const val SETTLED_DEG = 0.2

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * The tone of the map's own paper, for ground with no tile.
 *
 * Deliberately keyed to the tiles rather than to the app theme: the raster map
 * is light in both themes, so a dark fill here would read as a hole punched in
 * the map rather than as its edge.
 *
 * Sampled from OpenTopoMap's own background, and left as one constant across all
 * the styles offered: they all render on a warm off-white within a few units of
 * this, and the fill only ever shows at the edge of coverage, where the
 * difference is invisible. Four constants for that would be four to keep true.
 */
private const val MAP_PAPER = 0xFFF2F1EE.toInt()

/**
 * Draw ground with no tile as plain map paper rather than a cross-hatch.
 *
 * osmdroid's placeholder is a grey hatch, which reads as a broken image. Blank
 * is legitimate here -- a preview card is wider than a north-south route, and an
 * offline pack only covers the corridor -- so it should look like the edge of the
 * map, not like a failure.
 */
private fun MapView.usePaperForBlankTiles() {
    overlayManager.tilesOverlay.apply {
        setLoadingBackgroundColor(MAP_PAPER)
        setLoadingLineColor(MAP_PAPER)
    }
    setBackgroundColor(MAP_PAPER)
}

/**
 * Apply a mode change once, rather than on every recomposition.
 *
 * Keyed on whether a fix exists as well as the mode: navigation mode has nothing
 * to centre on until the first fix arrives, so until then it shows the overview.
 */
@Composable
private fun ApplyMapMode(
    mapView: MapView,
    route: Route,
    mode: MapMode,
    state: NavState?,
    zoom: Double,
) {
    val hasFix = state != null
    LaunchedEffect(mode, hasFix, zoom) {
        if (!mode.followsRider || !hasFix) {
            mapView.setMapOrientation(0f)
            mapView.setMapCenterOffset(0, 0)
            // Animate when the rider asked for the overview, but cut straight
            // there when there is no fix yet. That second case includes swapping
            // the route on a reverse, where animating means sliding across the
            // country from a viewport that no longer means anything.
            mapView.fitRouteWhenReady(route, animated = hasFix)
        } else {
            // Sit the rider low on the screen so most of the map shows the road
            // ahead rather than the road already ridden. A positive offset moves
            // the centred point down the screen.
            mapView.setMapCenterOffset(0, (mapView.height * POSITION_DROP).toInt())
            mapView.controller.setZoom(zoom)
            // Centre and rotation belong to SmoothCamera, which starts a fresh
            // one whenever following resumes and so puts the map on the rider on
            // its next frame. Doing it here as well would be two things steering
            // the same camera.
        }
    }
}

/**
 * Fit the whole route, waiting for layout first.
 *
 * `zoomToBoundingBox` does not fail on a zero-width view -- it spins inside
 * Projection.getCloserPixel and hangs the main thread, which reads as the app
 * freezing and takes the navigation service down with it.
 */
private fun MapView.fitRouteWhenReady(route: Route, animated: Boolean) {
    if (width > 0 && height > 0) {
        zoomToBoundingBox(route.boundingBox(), animated, MAP_PADDING_PX)
    } else {
        addOnFirstLayoutListener { _, _, _, _, _ ->
            zoomToBoundingBox(route.boundingBox(), false, MAP_PADDING_PX)
        }
    }
}

private const val MAP_PADDING_PX = 80

/** Close enough to read street layout at cycling speed, wide enough to see ahead. */
private const val NAVIGATION_ZOOM = 16.0

/**
 * Junction detail: roughly an 800 m-wide view, and the deepest level the default
 * style renders. Must agree with `Settings.closeZoom`.
 */
private const val NAVIGATION_CLOSE_ZOOM = 17.0

/** Fraction of the screen height the rider sits below centre in navigation mode. */
private const val POSITION_DROP = 0.22

private fun Route.boundingBox(): BoundingBox {
    val lats = points.map { it.lat }
    val lons = points.map { it.lon }
    return BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
}

/**
 * The part already ridden: a solid slate, thinner than the road ahead.
 *
 * It was previously drawn at 43% alpha, which over a busy raster map composited
 * to something almost indistinguishable from the map's own greys -- so the split
 * that is supposed to show progress showed nothing. Contrast comes from being a
 * different colour and weight, not from being faint.
 */
private fun travelledLine() = Polyline().apply {
    outlinePaint.apply {
        color = Color.argb(225, 122, 133, 150)
        // Matches the route line rather than sitting inside it; a narrower line
        // would leave a bright blue fringe along every ridden stretch.
        strokeWidth = 13f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

private fun remainingLine() = Polyline().apply {
    outlinePaint.apply {
        color = Color.argb(255, 29, 111, 242)
        strokeWidth = 13f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

private fun endpointMarker(map: MapView, at: GeoPoint, label: String, start: Boolean) =
    Marker(map).apply {
        position = at
        title = label
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = if (start) Markers.start(map.context) else Markers.finish(map.context)
    }

private fun waypointMarker(map: MapView, at: GeoPoint, label: String?) = Marker(map).apply {
    position = at
    title = label
    // Anchored at the tip, because a pin points at the place it marks.
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    icon = Markers.waypoint(map.context)
}

private fun directionMarker(map: MapView, at: GeoPoint, bearingDeg: Double) = Marker(map).apply {
    position = at
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    icon = Markers.directionArrow(map.context)
    rotation = -bearingDeg.toFloat()
    isFlat = true
    // Context, not a target: tapping one should do nothing at all.
    setOnMarkerClickListener { _, _ -> true }
    setInfoWindow(null)
}

private fun positionMarker(map: MapView, at: GeoPoint) = Marker(map).apply {
    position = at
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    icon = Markers.position(map.context)
    // Flat means the chevron turns with the map. Combined with course-up that
    // cancels out to "always pointing up the screen", and in north-up overview
    // it points along the direction of travel. Both are what you want.
    isFlat = true
}
