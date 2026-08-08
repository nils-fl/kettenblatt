package de.kettenblatt

import de.kettenblatt.data.Settings
import de.kettenblatt.data.SettingsCodec
import de.kettenblatt.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings mapping, tested through the codec so no Context is needed.
 *
 * The behaviour that matters here is what happens to *bad* input: a preferences
 * file must never be able to stop the app opening, or leave the rider stuck in a
 * state they cannot get out of on the road.
 */
class SettingsTest {

    private fun roundTrip(s: Settings): Settings {
        val encoded = SettingsCodec.encode(s)
        return SettingsCodec.decode { encoded[it] }
    }

    @Test
    fun `defaults survive a round trip`() {
        assertEquals(Settings(), roundTrip(Settings()))
    }

    @Test
    fun `every field round trips`() {
        val custom = Settings(
            units = Units.IMPERIAL,
            offRouteEnterM = 70.0,
            offRouteExitM = 30.0,
            autoDimEnabled = false,
            autoDimDelayMs = 45_000,
            autoDimWakeAheadM = 500.0,
            navigationZoom = 15.0,
            closeZoom = 19.0,
            keepScreenOn = false,
            fixIntervalMs = 3_000,
            valhallaUrl = "https://valhalla.example/route",
            tileSource = "thunderforest-outdoors",
            tileZoomMin = 11,
            tileZoomMax = 18,
            tileBufferM = 900.0,
            thunderforestKey = "abc123",
            autoMatchOnImport = false,
        )
        assertEquals(custom, roundTrip(custom))
    }

    // --- defaults tied to the data, not to literals -----------------------

    @Test
    fun `a pack goes as deep as the chosen style renders`() {
        // Stopping one level short is what made Close mode upscale, which on a
        // style that outlines every building reads as a broken map. Asserted
        // against the style rather than against 17, so if OpenTopoMap's ceiling
        // ever moves this names the value that has to move with it.
        assertEquals(Settings().mapStyle.maxZoom, Settings().tileZoomMax)
    }

    @Test
    fun `close mode never asks for more than the style renders`() {
        assertTrue(Settings().closeZoom <= Settings().mapStyle.maxZoom.toDouble())
        assertTrue(Settings().navigationZoom <= Settings().closeZoom)
    }

    @Test
    fun `auto-matching is on out of the box`() {
        // Recorded as a decision rather than left to whoever reads the default:
        // an existing install has no such key, so it decodes to on and gets the
        // new behaviour on upgrade.
        assertTrue(Settings().autoMatchOnImport)
        assertTrue(SettingsCodec.decode { null }.autoMatchOnImport)
    }

    @Test
    fun `an unreadable auto-match flag falls back to on`() {
        val stored = mapOf(SettingsCodec.AUTO_MATCH to "perhaps")
        assertTrue(SettingsCodec.decode { stored[it] }.autoMatchOnImport)
    }

    @Test
    fun `the default style can be packed`() {
        // The app is offline-first; a default that cannot be downloaded would
        // leave the Offline map button disabled out of the box.
        assertTrue(Settings().mapStyle.canDownload)
    }

    @Test
    fun `an out-of-range position interval is clamped, not discarded`() {
        // Both ends are real settings a backup could carry from a future build,
        // and the direction the rider wanted is still worth honouring.
        val tooFast = SettingsCodec.decode { mapOf(SettingsCodec.FIX_INTERVAL to "0")[it] }
        assertEquals(Settings.FIX_INTERVAL_MS.first, tooFast.fixIntervalMs)

        val tooSlow = SettingsCodec.decode { mapOf(SettingsCodec.FIX_INTERVAL to "60000")[it] }
        assertEquals(Settings.FIX_INTERVAL_MS.last, tooSlow.fixIntervalMs)
    }

    @Test
    fun `nothing stored gives the tuned defaults`() {
        assertEquals(Settings(), SettingsCodec.decode { null })
    }

    @Test
    fun `unreadable values fall back per field`() {
        // A half-written or hand-edited file must not take the app down, and one
        // bad key must not discard the rest.
        val stored = mapOf(
            SettingsCodec.UNITS to "PARSECS",
            SettingsCodec.NAV_ZOOM to "not a number",
            SettingsCodec.AUTO_DIM to "perhaps",
            SettingsCodec.AUTO_DIM_DELAY to "20000",
        )
        val decoded = SettingsCodec.decode { stored[it] }

        assertEquals(Settings().units, decoded.units)
        assertEquals(Settings().navigationZoom, decoded.navigationZoom, 0.0)
        assertEquals(Settings().autoDimEnabled, decoded.autoDimEnabled)
        // The one readable value is still honoured.
        assertEquals(20_000L, decoded.autoDimDelayMs)
    }

    @Test
    fun `an inverted off-route pair is refused`() {
        // Clearing further out than it alerts would latch the alarm on with no
        // way back, which on the road is worse than ignoring the setting.
        val stored = mapOf(
            SettingsCodec.OFF_ROUTE_ENTER to "20.0",
            SettingsCodec.OFF_ROUTE_EXIT to "80.0",
        )
        val decoded = SettingsCodec.decode { stored[it] }

        assertTrue(decoded.isHysteresisSane)
        assertEquals(Settings().offRouteEnterM, decoded.offRouteEnterM, 0.0)
        assertEquals(Settings().offRouteExitM, decoded.offRouteExitM, 0.0)
    }

    @Test
    fun `a sane custom hysteresis pair is kept`() {
        val stored = mapOf(
            SettingsCodec.OFF_ROUTE_ENTER to "100.0",
            SettingsCodec.OFF_ROUTE_EXIT to "60.0",
        )
        val decoded = SettingsCodec.decode { stored[it] }

        assertEquals(100.0, decoded.offRouteEnterM, 0.0)
        assertEquals(60.0, decoded.offRouteExitM, 0.0)
    }
}
