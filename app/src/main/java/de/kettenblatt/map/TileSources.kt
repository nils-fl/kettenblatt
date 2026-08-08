package de.kettenblatt.map

import android.content.Context
import de.kettenblatt.prep.TileSource
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * Map tile sources, online and offline.
 *
 * Note what is deliberately *absent*: any in-app bulk download. osmdroid's
 * Mapnik source carries `FLAG_NO_BULK`, so constructing a `CacheManager`
 * against it throws -- osmdroid enforcing the OSM Foundation's tile policy. Tile
 * packs are built by `prep/TilePack.kt` instead, from a
 * source that permits it. Browsing the map online still caches normally.
 */
object TileSources {

    /**
     * The name embedded in a generated `.mbtiles`.
     *
     * MBTiles has no field for a tile source name, so `IArchiveFile.getTileSources()`
     * comes back empty and osmdroid cannot discover it. The name therefore has to
     * be supplied here, and osmdroid falls back to the archive's only table
     * regardless of what it is called.
     *
     * It must also stay distinct from every [TileSource.key], because osmdroid's
     * disk cache is one table keyed by `(tile, provider)` where provider is the
     * source *name* -- and a pack sharing a namespace with an online cache is how
     * a pack starts serving tiles it does not contain.
     */
    private const val OFFLINE_SOURCE_NAME = "kettenblatt-offline"

    /**
     * The live map for a style.
     *
     * OSM Standard is deliberately osmdroid's own MAPNIK rather than a templated
     * source of ours: it already carries the tile policy the OSM Foundation asks
     * for, and reproducing that here would only be a worse copy.
     *
     * A style that needs a key and has not been given one falls back too. A
     * keyless Thunderforest request comes back as an error image, which renders
     * as a uniformly grey map with nothing in the log to explain it; a familiar
     * map plus the warning in Settings beats no map on a handlebar.
     */
    fun online(style: TileSource, apiKey: String? = null): ITileSource = when {
        style.key == TileSource.OSM_STANDARD_KEY -> TileSourceFactory.MAPNIK
        style.needsKey && apiKey.isNullOrBlank() -> TileSourceFactory.MAPNIK
        else -> TemplatedTileSource(style, apiKey)
    }

    private fun offlineSource(minZoom: Int, maxZoom: Int): ITileSource =
        XYTileSource(OFFLINE_SOURCE_NAME, minZoom, maxZoom, 256, ".png", emptyArray())

    /**
     * A provider backed by a sideloaded tile pack, or null if it cannot be read.
     *
     * Returning null rather than throwing lets the caller fall back to online
     * tiles: a corrupt pack should degrade the map, not prevent navigating.
     */
    fun offline(context: Context, mbtiles: File): Pair<MapTileProviderBase, ITileSource>? =
        runCatching {
            val provider = OfflineTileProvider(
                SimpleRegisterReceiver(context),
                arrayOf(mbtiles),
            )
            val bounds = MbtilesMeta.read(mbtiles)
            provider to offlineSource(bounds.minZoom, bounds.maxZoom)
        }.getOrNull()
}

/**
 * A live map source built from one of the app's own [TileSource] templates.
 *
 * osmdroid's `XYTileSource` builds `baseUrl + z/x/y + extension`, which has
 * nowhere to put a `?apikey=` query string and nowhere to put a `{s}` inside the
 * host -- its subdomain support rotates whole base URLs instead. Only one method
 * needs to differ, so it delegates to [TileSource.url] rather than growing a
 * second URL builder that could drift from the downloader's. The live map and a
 * pack of the same style therefore fetch byte-identical URLs.
 *
 * The name handed to osmdroid is [TileSource.key], because the disk cache is one
 * table keyed by `(tile, provider)` where provider is the source name: two
 * styles sharing a name would serve each other's tiles, and a rider switching
 * style would keep seeing the old map until the cache turned over.
 */
private class TemplatedTileSource(
    private val style: TileSource,
    private val apiKey: String?,
) : OnlineTileSourceBase(
    style.key,
    MIN_ZOOM,
    style.maxZoom,
    TILE_SIZE_PX,
    ".png",
    // Unused: getTileURLString below never calls getBaseUrl().
    emptyArray(),
    style.attribution,
    COURTEOUS,
) {
    // osmdroid packs zoom, x and y into one long, and MapTileIndex is the only
    // thing that knows the layout -- so never unpack it by hand.
    override fun getTileURLString(pMapTileIndex: Long): String = style.url(
        MapTileIndex.getZoom(pMapTileIndex),
        MapTileIndex.getX(pMapTileIndex),
        MapTileIndex.getY(pMapTileIndex),
        apiKey,
    )
}

/**
 * The same manners osmdroid applies to tile.openstreetmap.org.
 *
 * NO_PREVENTIVE is the one that matters here: OpenTopoMap's servers are
 * volunteer-run, and preventive fetching speculatively pulls a ring of tiles
 * around the viewport that nobody asked to see. The two user-agent flags make
 * osmdroid refuse to send anything at all under its default agent -- the app
 * already loads Mapnik tiles under exactly these flags with the agent
 * `Kettenblatt.kt` sets, so they are known to pass here too.
 */
private val COURTEOUS = TileSourcePolicy(
    2,
    TileSourcePolicy.FLAG_NO_PREVENTIVE or
        TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
        TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
)

private const val MIN_ZOOM = 0

/** Every style here serves 256 px tiles; MBTiles has no field to say otherwise. */
private const val TILE_SIZE_PX = 256

/** The zoom range a tile pack actually contains, read from its metadata table. */
data class MbtilesMeta(val minZoom: Int, val maxZoom: Int) {
    companion object {
        fun read(file: File): MbtilesMeta = runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                    var min = 0
                    var max = 20
                    while (c.moveToNext()) {
                        when (c.getString(0)) {
                            "minzoom" -> min = c.getString(1).toIntOrNull() ?: min
                            "maxzoom" -> max = c.getString(1).toIntOrNull() ?: max
                        }
                    }
                    MbtilesMeta(min, max)
                }
            }
        }.getOrElse { MbtilesMeta(0, 20) }
    }
}
