package de.kettenblatt

import de.kettenblatt.data.GpxImport
import de.kettenblatt.prep.TilePack
import de.kettenblatt.prep.TileSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * Tile selection, against numbers verified against the Python original.
 *
 * The corridor has to cover exactly the ground the route runs through -- one
 * tile narrower is a blank strip beside the line, discovered in a field with no
 * signal.
 */
class TilePackTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }

    private val expected by lazy {
        json.parseToJsonElement(resource("venlo_tiles_expected.json").bufferedReader().readText())
            .jsonObject
    }

    private val route by lazy { GpxImport.parse(resource("venlo.gpx"), "venlo", KXmlParser()) }

    @Test
    fun `tile addressing matches the python implementation`() {
        val cases = expected["deg2tile"]!!.jsonObject
        assertTrue("no cases loaded", cases.isNotEmpty())
        cases.forEach { (key, value) ->
            val (lat, lon, z) = key.split(",")
            val gold = value.jsonArray.map { it.jsonPrimitive.content.toInt() }

            val (x, y) = TilePack.deg2tile(lat.toDouble(), lon.toDouble(), z.toInt())
            assertEquals("x for $key", gold[0], x)
            assertEquals("y for $key", gold[1], y)
        }
    }

    @Test
    fun `web mercator clamps rather than running off the projection`() {
        // Beyond ~85 degrees the projection has no bottom; a raw formula gives
        // a row index outside the tile grid and the pack ends up unreadable.
        val n = 1 shl 5
        val (_, far) = TilePack.deg2tile(89.9, 10.0, 5)
        val (_, south) = TilePack.deg2tile(-89.9, 10.0, 5)
        assertTrue(far in 0 until n)
        assertTrue(south in 0 until n)
    }

    @Test
    fun `corridor and bbox counts match python, and the cheaper one wins`() {
        val counts = expected["counts"]!!.jsonObject
        counts.forEach { (zoom, value) ->
            val z = zoom.toInt()
            val gold = value.jsonObject
            val corridor = TilePack.corridorTiles(route.points, z, 500.0)
            val box = TilePack.bboxTiles(route.points, z)
            val (chosen, shape) = TilePack.tilesFor(route.points, z, 500.0)

            assertEquals("corridor at z$z", gold["corridor"]!!.jsonPrimitive.content.toInt(), corridor.size)
            assertEquals("bbox at z$z", gold["bbox"]!!.jsonPrimitive.content.toInt(), box.size)
            assertEquals("chosen at z$z", gold["n"]!!.jsonPrimitive.content.toInt(), chosen.size)
            assertEquals("shape at z$z", gold["shape"]!!.jsonPrimitive.content, shape)
        }
    }

    @Test
    fun `a plan covers the same tiles the desktop pack would`() {
        val plan = TilePack.plan(
            route.points, TileSource.byKey("opentopomap"), zoomMin = 12, zoomMax = 16, bufferM = 500.0,
        )
        assertEquals(expected["total_12_16"]!!.jsonPrimitive.content.toInt(), plan.tiles.size)
        assertEquals(12, plan.zoomMin)
        assertEquals(16, plan.zoomMax)
        assertTrue(plan.tiles.distinct().size == plan.tiles.size)
    }

    @Test
    fun `the size warning lands near what a pack actually weighs`() {
        // Two packs of this route have been built and measured: OpenTopoMap
        // z12-16 came to 9 MB (21 KB a tile), OpenCycleMap z12-17 to 20 MB
        // (15 KB a tile). One constant cannot fit both closely -- the weight of
        // a tile depends on the style *and* on how much of the pack is sparse
        // deep zoom -- so the bar is the right order of magnitude, which is what
        // the number is for. The old 35 KB a tile predicted 49 MB for that 20 MB
        // pack, which is worse than saying nothing.
        val topo = TileSource.byKey("opentopomap")
        fun near(estimate: Long, measuredMb: Long) {
            val measured = measuredMb * 1024 * 1024
            assertTrue(
                "estimate ${estimate / 1024 / 1024} MB vs measured $measuredMb MB",
                estimate in (measured * 3 / 5)..(measured * 7 / 5),
            )
        }

        near(TilePack.plan(route.points, topo, 12, 16, 500.0).estimatedBytes, 9)
        near(TilePack.plan(route.points, topo, 12, 17, 500.0).estimatedBytes, 20)
    }

    @Test
    fun `the default depth reaches OpenTopoMap's own deepest level`() {
        // z17 is what stops Close mode upscaling, and it is not free: these are
        // the numbers the README quotes and the download warning is sized
        // against, so they are pinned rather than described.
        val topo = TileSource.byKey("opentopomap")
        val plan = TilePack.plan(route.points, topo, zoomMin = 12, zoomMax = 17, bufferM = 500.0)
        val shallower = TilePack.plan(route.points, topo, zoomMin = 12, zoomMax = 16, bufferM = 500.0)

        assertEquals(17, plan.zoomMax)
        assertEquals(plan.tiles.size - shallower.tiles.size, plan.tiles.count { it.first == 17 })
        assertTrue(plan.tiles.distinct().size == plan.tiles.size)

        // Roughly three times the pack for one more level -- worth saying out
        // loud, since a rider taps Offline map without reading a zoom slider.
        assertTrue("z17 should be about 3x z16", plan.tiles.size > shallower.tiles.size * 2)
        assertTrue(plan.tiles.size < shallower.tiles.size * 4)
    }

    @Test
    fun `the corridor finally beats the bounding box at the deepest level`() {
        // Every shallower zoom takes the box, because padding a corridor by a
        // whole tile either side costs more than the rectangle does. z17 is
        // where the strip is finally the cheaper shape -- which is also why the
        // pack stops growing quite as fast as the fourfold rule suggests.
        assertEquals("corridor", TilePack.tilesFor(route.points, 17, 500.0).second)
        assertEquals("bbox", TilePack.tilesFor(route.points, 16, 500.0).second)

        val plan = TilePack.plan(
            route.points, TileSource.byKey("opentopomap"), zoomMin = 12, zoomMax = 17, bufferM = 500.0,
        )
        assertEquals("bbox/corridor", plan.shape)
    }

    @Test
    fun `a zoom beyond the source's maximum is clamped, not requested`() {
        // Asking OpenTopoMap for zoom 20 returns nothing useful; the pack would
        // simply be missing its deepest levels with no explanation.
        val plan = TilePack.plan(
            route.points, TileSource.byKey("opentopomap"), zoomMin = 12, zoomMax = 20, bufferM = 500.0,
        )
        assertEquals(17, plan.zoomMax)
        assertTrue(plan.tiles.all { it.first <= 17 })
    }

    @Test
    fun `a bigger buffer never covers less ground`() {
        val narrow = TilePack.corridorTiles(route.points, 15, 200.0)
        val wide = TilePack.corridorTiles(route.points, 15, 1_000.0)
        assertTrue(wide.size > narrow.size)
        assertTrue("a wider corridor must contain the narrower one", wide.containsAll(narrow))
    }

    @Test
    fun `tile urls fill in every placeholder`() {
        val topo = TileSource.byKey("opentopomap")
        val url = topo.url(14, 8472, 5454, null)
        assertEquals("https://a.tile.opentopomap.org/14/8472/5454.png", url)
        assertTrue("no placeholder may survive", !url.contains("{"))

        val thunder = TileSource.byKey("thunderforest-cycle")
        assertTrue(thunder.needsKey)
        assertTrue(thunder.url(14, 1, 2, "abc123").endsWith("apikey=abc123"))
    }

    @Test
    fun `subdomains spread the load across the volunteer servers`() {
        val topo = TileSource.byKey("opentopomap")
        val hosts = (0 until 6).map { topo.url(14, it, 0, null).substringAfter("//").substringBefore('.') }
        assertEquals(setOf("a", "b", "c"), hosts.toSet())
    }

    @Test
    fun `an unknown source falls back rather than failing a download`() {
        assertEquals("opentopomap", TileSource.byKey("something-else").key)
        // And the fallback must always be packable, or an unreadable settings
        // file would leave the download button pointing at a style that refuses.
        assertTrue(TileSource.byKey("something-else").canDownload)
    }

    // --- styles -----------------------------------------------------------

    @Test
    fun `every style says whether it may be packed`() {
        // The list is not all downloadable, and the one that is not has to be in
        // it: leaving OSM Standard out would make the picker omit the map most
        // riders already know.
        assertTrue(TileSource.ALL.any { !it.canDownload })
        assertFalse(TileSource.byKey(TileSource.OSM_STANDARD_KEY).canDownload)
        assertTrue(TileSource.DOWNLOADABLE.isNotEmpty())
        assertTrue(TileSource.DOWNLOADABLE.all { it.canDownload })
    }

    @Test
    fun `the picker cannot show two identical labels`() {
        // The labels were cut from the full name at the first space, which gave
        // two segments both reading "Thunderforest" and no way to tell them
        // apart on the screen where you choose between them.
        assertEquals(TileSource.ALL.size, TileSource.ALL.map { it.shortName }.toSet().size)
        assertTrue(TileSource.ALL.all { it.shortName.isNotBlank() })
    }

    @Test
    fun `an online-only style still describes itself fully`() {
        // It is never fetched from -- the live map uses osmdroid's own Mapnik --
        // but half an entry is how a picker ends up showing a blank attribution.
        val standard = TileSource.byKey(TileSource.OSM_STANDARD_KEY)
        assertEquals(19, standard.maxZoom)
        assertTrue(standard.attribution.isNotBlank())
        assertTrue(!standard.url(14, 8472, 5454, null).contains("{"))
    }
}
