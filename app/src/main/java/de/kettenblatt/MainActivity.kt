package de.kettenblatt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import de.kettenblatt.ui.theme.NaviTheme
import de.kettenblatt.data.GpxExport
import de.kettenblatt.data.Ride
import de.kettenblatt.data.RideStore
import de.kettenblatt.data.Route
import de.kettenblatt.data.RouteMeta
import de.kettenblatt.data.Backup
import de.kettenblatt.data.SettingsCodec
import de.kettenblatt.data.RouteStore
import de.kettenblatt.prep.Network
import de.kettenblatt.prep.PrepStage
import de.kettenblatt.prep.RoutePreparer
import de.kettenblatt.prep.TilePack
import de.kettenblatt.prep.TileProgress
import de.kettenblatt.prep.TileSource
import de.kettenblatt.prep.Valhalla
import de.kettenblatt.ui.PrepState
import de.kettenblatt.ui.formatBytes
import de.kettenblatt.ui.plural
import de.kettenblatt.data.SettingsStore
import de.kettenblatt.nav.NavigationRepository
import de.kettenblatt.nav.NavigationService
import de.kettenblatt.ui.ArrivedScreen
import de.kettenblatt.ui.NavigationScreen
import de.kettenblatt.ui.RideHistoryScreen
import de.kettenblatt.ui.RouteListScreen
import de.kettenblatt.ui.RoutePreviewScreen
import de.kettenblatt.ui.SettingsScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var store: RouteStore
    private var pendingImport by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        pendingImport = intent?.extractRouteUri()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            NaviTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App(store, pendingImport) { pendingImport = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.extractRouteUri()?.let { pendingImport = it }
    }

    /** A route arriving from the share sheet or a file manager. */
    private fun Intent.extractRouteUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}

/** How long an interrupted ride stays offerable as a resume. */
private const val RESUME_WINDOW_MS = 6 * 60 * 60 * 1000L

/** What a match run produced, whichever screen asked for it. */
private data class MatchOutcome(
    val prepared: de.kettenblatt.prep.Prepared,
    /** Refreshed meta, or null when the result was not worth keeping. */
    val updated: RouteMeta?,
    val kept: Boolean,
)

/**
 * Match a route against OpenStreetMap and rewrite its stored bundle.
 *
 * Deliberately says nothing about which screen asked. The preview drives a
 * progress card from [onStage]; an automatic match on import has only a chip on
 * the route's row. One code path, two surfaces -- and the rule that a failed
 * match must never replace cues already on the phone lives here, where a second
 * caller cannot skip it.
 */
private suspend fun matchRoute(
    store: RouteStore,
    valhallaUrl: String,
    meta: RouteMeta,
    route: Route,
    onStage: (PrepStage) -> Unit = {},
): MatchOutcome = withContext(Dispatchers.IO) {
    val prepared = RoutePreparer(Valhalla(valhallaUrl), onStage).prepare(route)
    // Never trade working guidance for none. Matching is best-effort when a
    // route has no cues yet, but a re-match that fails -- no signal, a server
    // having a bad day -- must not wipe the 70 turns already on the phone.
    val kept = prepared.route.hasGuidance || !route.hasGuidance
    // Preparing rewrites the stored file under a new name, so any meta held
    // elsewhere is stale from here on -- and a stale one points at a file that
    // no longer exists.
    val updated = if (kept) store.replaceBundle(meta.id, prepared.bundle) else null
    MatchOutcome(prepared, updated, kept)
}

@Composable
private fun App(store: RouteStore, incoming: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    var routes by remember { mutableStateOf(store.list()) }
    var active by remember { mutableStateOf<RouteMeta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val route by NavigationRepository.route.collectAsState()
    val navState by NavigationRepository.state.collectAsState()
    val arrival by NavigationRepository.arrival.collectAsState()

    // Reading a route means pulling a whole file through a content provider and
    // parsing it. A cloud-backed URI is a network round-trip and a recorded GPX
    // is tens of thousands of points, so none of it belongs on the main thread.
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val settingsStore = remember { SettingsStore(context) }
    val settings by settingsStore.state.collectAsState()

    // Where the rider is in the app: list -> preview -> navigating.
    var preview by remember { mutableStateOf<RouteMeta?>(null) }
    var previewRoute by remember { mutableStateOf<Route?>(null) }
    var previewReversed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val rideStore = remember { RideStore(File(context.filesDir, "rides")) }
    var rides by remember { mutableStateOf(emptyList<Ride>()) }
    var showHistory by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Ride?>(null) }

    // An unfinished ride means the app died mid-ride. Offer to pick it up while
    // it is still plausibly the same outing; otherwise file it into history.
    var resumable by remember { mutableStateOf<Ride?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val active = rideStore.active()
            val fresh = active != null &&
                System.currentTimeMillis() - active.startedAtMs < RESUME_WINDOW_MS
            if (!fresh) rideStore.finaliseAbandoned(System.currentTimeMillis())
            resumable = if (fresh) active else null
            rides = rideStore.list()
        }
    }

    // Preparing a route: matching against OpenStreetMap, and building an
    // offline map pack. Both were desktop jobs until now.
    var prep by remember { mutableStateOf(PrepState()) }
    var cancelTiles by remember { mutableStateOf(false) }

    fun prepareRoute(meta: RouteMeta, route: Route) {
        prep = PrepState(stage = PrepStage.MATCHING)
        scope.launch {
            runCatching {
                matchRoute(store, settings.valhallaUrl, meta, route) { stage ->
                    prep = prep.copy(stage = stage)
                }
            }.onSuccess { outcome ->
                routes = withContext(Dispatchers.IO) { store.list() }
                outcome.updated?.let { preview = it }
                if (outcome.kept) previewRoute = outcome.prepared.route
                prep = PrepState(
                    warnings = outcome.prepared.warnings,
                    done = when {
                        outcome.prepared.route.hasGuidance ->
                            "${outcome.prepared.route.maneuvers.size} turns" +
                                if (outcome.prepared.route.hasReverseGuidance) {
                                    ", ${outcome.prepared.route.reverseManeuvers.size} the other way"
                                } else ""
                        outcome.kept -> "No usable match; the route still works on geometry alone."
                        else -> "Matching failed, so the cues already on this route were kept."
                    },
                )
            }.onFailure { e ->
                prep = PrepState(error = e.message ?: "Could not prepare this route")
            }
        }
    }

    // Routes waiting for or undergoing an automatic match, head first.
    //
    // A list rather than a set because it is also a queue: the public Valhalla
    // asks for roughly a call a second and a route costs four, so two imports in
    // a row are matched one after the other rather than at once.
    var autoMatching by remember { mutableStateOf(emptyList<String>()) }
    val navigating = route != null

    // Keyed on the head, so finishing one starts the next; and on whether a ride
    // is under way, because nothing prepares a route while its owner is on the
    // road. Declared above the early returns below on purpose -- a LaunchedEffect
    // under one of them is torn down the moment the rider opens a route, which
    // would cancel the match mid-flight.
    LaunchedEffect(autoMatching.firstOrNull(), navigating) {
        if (navigating) return@LaunchedEffect
        val id = autoMatching.firstOrNull() ?: return@LaunchedEffect
        val meta = withContext(Dispatchers.IO) { store.find(id) }
        if (meta != null) {
            runCatching {
                val loaded = withContext(Dispatchers.IO) { store.load(meta) }
                matchRoute(store, settings.valhallaUrl, meta, loaded)
            }.onSuccess { outcome ->
                // The preview may be open on this very route. Matching rewrote
                // the stored file under a new name, so the meta that screen is
                // holding now points at a file that no longer exists -- which
                // turns Start ride into "could not open route".
                if (preview?.id == id) {
                    outcome.updated?.let { preview = it }
                    if (outcome.kept) previewRoute = outcome.prepared.route
                }
            }
            // Silent on failure by design: the route already navigates, and the
            // preview still offers to match it by hand with the reason shown.
            routes = withContext(Dispatchers.IO) { store.list() }
        }
        autoMatching = autoMatching.drop(1)
    }

    fun downloadTiles(meta: RouteMeta, route: Route) {
        val source = settings.mapStyle
        // The button is disabled for these, but the rule belongs with the
        // download rather than with whatever happens to be able to start one.
        if (!source.canDownload) {
            prep = PrepState(
                error = "${source.name} cannot be packed for offline use. " +
                    "Choose ${TileSource.DOWNLOADABLE.joinToString(" or ") { it.name }} in Settings.",
            )
            return
        }

        cancelTiles = false
        prep = PrepState(tiles = TileProgress(0, 0, 0))
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val plan = TilePack.plan(
                        points = route.points,
                        source = source,
                        zoomMin = settings.tileZoomMin,
                        zoomMax = settings.tileZoomMax,
                        bufferM = settings.tileBufferM,
                    )
                    // What it will cost, before it costs it. A rider taps
                    // Offline map without first reading a zoom slider, and the
                    // deepest level alone is most of the pack.
                    prep = prep.copy(
                        tiles = TileProgress(0, plan.tiles.size, 0),
                        tilePlanSummary = "${plan.tiles.size} tiles, about " +
                            "${formatBytes(plan.estimatedBytes)} at " +
                            "z${plan.zoomMin}–${plan.zoomMax}",
                    )

                    val file = store.tilesFileFor(meta.id)
                    TilePack.download(
                        plan = plan,
                        out = file,
                        routeName = route.name,
                        bbox = listOf(
                            route.points.minOf { it.lat }, route.points.minOf { it.lon },
                            route.points.maxOf { it.lat }, route.points.maxOf { it.lon },
                        ),
                        apiKey = settings.tileApiKey,
                        shouldContinue = { !cancelTiles },
                        onProgress = { prep = prep.copy(tiles = it) },
                    )
                    plan to store.attachTilesFile(meta.id)
                }
            }

            outcome.onSuccess { (plan, updated) ->
                routes = withContext(Dispatchers.IO) { store.list() }
                updated?.let { preview = it }
                val done = prep.tiles?.done ?: 0
                prep = PrepState(
                    done = if (cancelTiles) {
                        "Stopped with $done of ${plan.tiles.size} tiles. " +
                            "Starting again picks up from here."
                    } else {
                        "Offline map ready — $done tiles, ${formatBytes(store.tilesFileFor(meta.id).length())}."
                    },
                )
            }.onFailure { e ->
                prep = PrepState(error = e.message ?: "Could not download the map")
            }
        }
    }

    /** Run file work off the main thread, with the spinner up while it lasts. */
    fun withStore(onFailureMessage: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { work() }
                .onFailure { error = it.message ?: onFailureMessage }
            routes = withContext(Dispatchers.IO) { store.list() }
            busy = false
        }
    }

    /**
     * Ask for cues on a freshly imported route, if the moment is right.
     *
     * Silent when it declines. An import that lands on mobile data, or with this
     * switched off, is not a failure -- the route works as it is, and the preview
     * still offers to match it.
     */
    fun enqueueAutoMatch(meta: RouteMeta) {
        if (!settings.autoMatchOnImport) return
        // A bundle that arrived with cues has nothing to gain, and a re-match
        // could only lose them. Read off the meta, so nothing is loaded to decide.
        if (meta.hasGuidance) return
        // Four Valhalla calls belong on wifi, not on someone's data allowance.
        if (!Network.isUnmetered(context)) return
        if (meta.id !in autoMatching) autoMatching = autoMatching + meta.id
    }

    fun importFrom(uri: Uri) = withStore("Unrecognised file") {
        val imported = withContext(Dispatchers.IO) { store.import(uri, System.currentTimeMillis()) }
        // A plain GPX is navigable as it stands; cues are the only thing missing,
        // so ask for them here rather than making the rider find the button. Both
        // the picker and the share sheet funnel through here, which is why the
        // hook goes here and not at the two call sites.
        enqueueAutoMatch(imported)
    }

    // A route shared into the app is imported as soon as it arrives.
    LaunchedEffect(incoming) {
        incoming?.let {
            importFrom(it)
            onIncomingHandled()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFrom(it) } }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val ride = exporting
        exporting = null
        if (uri != null && ride != null) {
            withStore("Could not export ride") {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { GpxExport.write(ride, it) }
                }
            }
        }
    }

    // Which route a picked .mbtiles should attach to.
    var attachingTilesTo by remember { mutableStateOf<String?>(null) }
    val tilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val routeId = attachingTilesTo
        attachingTilesTo = null
        if (uri != null && routeId != null) {
            withStore("Could not read tile pack") {
                withContext(Dispatchers.IO) { store.importTiles(routeId, uri) }
            }
        }
    }

    var backupStatus by remember { mutableStateOf<String?>(null) }

    val backupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            backupStatus = "Writing…"
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            Backup.write(
                                routesDir = File(context.filesDir, "routes"),
                                ridesDir = File(context.filesDir, "rides"),
                                settings = SettingsCodec.encode(settingsStore.current),
                                nowMs = System.currentTimeMillis(),
                                out = out,
                            )
                        } ?: throw IllegalStateException("Could not open that file for writing")
                    }
                }.onSuccess {
                    backupStatus = "Backed up ${plural(it.routes, "route")} and ${plural(it.rides, "ride")}."
                }.onFailure { e ->
                    backupStatus = e.message ?: "Could not write the backup"
                }
            }
        }
    }

    val backupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupStatus = "Restoring…"
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            Backup.restore(
                                input = input,
                                routesDir = File(context.filesDir, "routes"),
                                ridesDir = File(context.filesDir, "rides"),
                                currentSettings = SettingsCodec.encode(settingsStore.current),
                                applySettings = { values ->
                                    settingsStore.update { SettingsCodec.decode { k -> values[k] } }
                                },
                            )
                        } ?: throw IllegalStateException("Could not open that file")
                    }
                }.onSuccess { summary ->
                    routes = withContext(Dispatchers.IO) { store.list() }
                    rides = withContext(Dispatchers.IO) { rideStore.list() }
                    backupStatus = buildString {
                        if (summary.addedAnything) {
                            append("Restored ${plural(summary.routesAdded, "route")}")
                            append(" and ${plural(summary.ridesAdded, "ride")}.")
                        } else {
                            append("Nothing new — everything in that backup is already here.")
                        }
                        if (summary.routesSkipped + summary.ridesSkipped > 0 && summary.addedAnything) {
                            append(" ${plural(summary.routesSkipped + summary.ridesSkipped, "item")} already present.")
                        }
                        if (summary.settingsApplied) append(" Settings restored too.")
                    }
                }.onFailure { e ->
                    backupStatus = e.message ?: "Could not read that backup"
                }
            }
        }
    }

    /** Load a route and hand it to the service, off the main thread. */
    fun beginNavigation(meta: RouteMeta, reversed: Boolean) {
        if (busy) return
        busy = true
        active = meta
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Re-read rather than trusting the meta the screen is
                    // holding: a background match rewrites the stored file under
                    // a new name, and a meta captured before it points at a file
                    // that has since been deleted.
                    store.load(store.find(meta.id) ?: meta)
                }
            }
                .onSuccess {
                    NavigationRepository.start(meta.id, if (reversed) it.reversed() else it)
                    NavigationService.start(context)
                    preview = null
                    previewRoute = null
                }
                .onFailure {
                    error = it.message ?: "Could not open route"
                    active = null
                }
            busy = false
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val meta = active ?: preview
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true && meta != null) {
            beginNavigation(meta, previewReversed)
        } else {
            error = "Location permission is required to navigate"
            active = null
        }
    }

    // The screen has to stay awake while navigating; that is the whole point of
    // having it mounted on a handlebar.
    KeepScreenOn(enabled = route != null && settings.keepScreenOn)

    // Ahead of the navigation screen, and outside it: the service stops itself a
    // minute after the finish, and the summary has to survive that rather than
    // vanish from under the rider mid-read.
    val finished = arrival
    if (finished != null) {
        ArrivedScreen(
            ride = finished,
            units = settings.units,
            stillNavigating = route != null,
            onDone = {
                NavigationService.stop(context)
                NavigationRepository.clearArrival()
                active = null
                // The ride has just been written; without this the history list
                // is missing the one ride the rider is most likely to open.
                scope.launch { rides = withContext(Dispatchers.IO) { rideStore.list() } }
            },
            onKeepRiding = { NavigationRepository.clearArrival() },
        )
        return
    }

    val current = route
    if (current != null && active != null) {
        NavigationScreen(
            route = current,
            state = navState,
            offlineTiles = active?.let { store.tilesFile(it) },
            settings = settings,
            onStop = {
                NavigationService.stop(context)
                active = null
            },
            onReverse = { NavigationRepository.replaceRoute(current.reversed()) },
        )
        return
    }

    // Rides are read once at launch, but one gets recorded every time navigation
    // stops -- so without this the ride you have just finished is missing from
    // the list until the app is restarted.
    LaunchedEffect(showHistory) {
        if (showHistory) rides = withContext(Dispatchers.IO) { rideStore.list() }
    }

    if (showHistory) {
        RideHistoryScreen(
            rides = rides,
            units = settings.units,
            onExport = { ride ->
                exporting = ride
                exportPicker.launch(GpxExport.suggestedFileName(ride))
            },
            onDelete = { ride ->
                withStore("Could not delete ride") {
                    withContext(Dispatchers.IO) { rideStore.delete(ride.id) }
                    rides = withContext(Dispatchers.IO) { rideStore.list() }
                }
            },
            onBack = { showHistory = false },
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            settings = settings,
            backupStatus = backupStatus,
            onChange = { settingsStore.update(it) },
            onExportBackup = {
                backupStatus = null
                backupExporter.launch(Backup.suggestedFileName(System.currentTimeMillis()))
            },
            onRestoreBackup = {
                backupStatus = null
                // Many providers report a zip as octet-stream, so the filter has
                // to be broad; the manifest check is what actually validates it.
                backupImporter.launch(arrayOf("application/zip", "*/*"))
            },
            onReset = { settingsStore.reset() },
            onBack = { showSettings = false; backupStatus = null },
        )
        return
    }

    val previewing = preview
    val previewLoaded = previewRoute
    if (previewing != null && previewLoaded != null) {
        RoutePreviewScreen(
            route = previewLoaded,
            reversed = previewReversed,
            offlineTiles = store.tilesFile(previewing),
            settings = settings,
            prep = prep,
            onSetReversed = { previewReversed = it },
            onPrepare = { prepareRoute(previewing, previewLoaded) },
            onDownloadTiles = { downloadTiles(previewing, previewLoaded) },
            onCancelPrep = { cancelTiles = true },
            onDismissPrepMessage = { prep = PrepState() },
            onStart = {
                // Permissions are asked for here rather than on the list tap, so
                // nothing spins up the GPS until the rider commits to riding.
                val missing = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                ).filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    beginNavigation(previewing, previewReversed)
                } else {
                    permission.launch(missing.toTypedArray())
                }
            },
            onBack = { preview = null; previewRoute = null; prep = PrepState() },
        )
        // The list screen owns the error dialog, so a failure raised from here
        // -- a route that will not load, most likely -- would otherwise be set
        // and never seen. Show it where it happened.
        error?.let {
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("Something went wrong") },
                text = { Text(it) },
                confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            )
        }
        return
    }

    RouteListScreen(
        routes = routes,
        error = error,
        busy = busy,
        units = settings.units,
        matching = autoMatching,
        onImport = {
            // Many providers report .gpx and .navi.json as octet-stream, so the
            // filter has to be broad; the extension check happens on import.
            picker.launch(arrayOf("application/gpx+xml", "application/json", "*/*"))
        },
        onOpen = { meta ->
            preview = meta
            previewReversed = false
            withStore("Could not open route") {
                previewRoute = withContext(Dispatchers.IO) { store.load(meta) }
            }
        },
        onOpenSettings = { showSettings = true },
        onOpenHistory = { showHistory = true },
        resumable = resumable,
        onResume = { ride ->
            store.find(ride.routeId)?.let { meta ->
                previewReversed = ride.reversed
                beginNavigation(meta, ride.reversed)
            } ?: run { error = "That route is no longer in the list" }
            resumable = null
        },
        onDiscardResumable = {
            withStore("Could not close the ride") {
                withContext(Dispatchers.IO) { rideStore.finaliseAbandoned(System.currentTimeMillis()) }
                rides = withContext(Dispatchers.IO) { rideStore.list() }
            }
            resumable = null
        },
        onAttachTiles = { meta ->
            attachingTilesTo = meta.id
            tilePicker.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onRemoveTiles = { meta ->
            withStore("Could not remove the offline map") {
                withContext(Dispatchers.IO) { store.removeTiles(meta.id) }
            }
        },
        onRename = { meta, name ->
            withStore("Could not rename route") {
                withContext(Dispatchers.IO) { store.rename(meta.id, name) }
            }
        },
        onToggleFavourite = { meta ->
            withStore("Could not update route") {
                withContext(Dispatchers.IO) { store.setFavourite(meta.id, !meta.favourite) }
            }
        },
        onDelete = { meta ->
            withStore("Could not delete route") {
                withContext(Dispatchers.IO) { store.delete(meta.id) }
            }
        },
        onDismissError = { error = null },
    )
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(enabled) {
        val window = (context as? ComponentActivity)?.window ?: return@LaunchedEffect
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
