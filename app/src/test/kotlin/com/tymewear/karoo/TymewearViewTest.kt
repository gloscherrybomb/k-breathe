package com.tymewear.karoo

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.ViewRecorder
import fi.nikosavola.karooext.testing.bitmaps
import fi.nikosavola.karooext.testing.hasColorNear
import fi.nikosavola.karooext.testing.inflate
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.karooext.testing.texts
import io.hammerhead.karooext.models.ViewConfig
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Field rendering: the RemoteViews each data type draws under host bounds, read back
// with the library's inspection helpers.
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    // The extension's own clock drives staleness; the SDK's drives its 900 ms view
    // throttle, which would otherwise drop the first frame before the clock advances.
    instrumentedPackages = ["com.tymewear.karoo", "io.hammerhead.karooext.internal"],
)
class TymewearViewTest {
    @get:Rule val karoo = FakeKarooRule()

    private val config = ViewConfig(gridSize = 60 to 15, viewSize = 480 to 200, textSize = 30)

    @Before
    fun setUp() {
        Protocol.resetState()
        TymewearData.setDisconnected()
        VentilatoryState.load(karoo.app)
    }

    private fun breathData(breathRate: Double = 20.0, tidalVolume: Double = 0.5) =
        Protocol.BreathingData(
            breathRate = breathRate,
            tidalVolume = tidalVolume,
            minuteVolume = breathRate * tidalVolume,
            ieRatio = 120.0 / 180.0,
            veZone = 0,
            inhaleDurationCs = 120,
            exhaleDurationCs = 180,
            tvRaw = 50,
            fieldE = 7,
            timestamp40ms = 1,
        )

    /** Advances past the SDK's 900 ms throttle so the view's first frame is delivered. */
    private fun startView(host: FakeKarooHost, typeId: String): ViewRecorder {
        RobolectricPump.advanceBy(1.seconds)
        return host.startView(typeId, config)
    }

    private fun firstFrame(host: FakeKarooHost, typeId: String): View =
        startView(host, typeId).awaitFrame(10_000).inflate(karoo.app)

    private fun containerColor(root: View): Int =
        (root.findViewById<View>(R.id.container).background as ColorDrawable).color

    @Test
    fun `the breathing rate view shows the rounded average`() {
        val host = karoo.host<TymewearExtension>()
        TymewearData.update(breathData(breathRate = 20.0))

        val root = startView(host, "br").awaitFrame(10_000).inflate(karoo.app)

        assertTrue(root.texts().contains("20"))
    }

    @Test
    fun `the tidal volume view shows litres with two decimals`() {
        val host = karoo.host<TymewearExtension>()
        TymewearData.update(breathData(tidalVolume = 0.85))

        val root = startView(host, "tv").awaitFrame(10_000).inflate(karoo.app)

        assertTrue(root.texts().contains("0.85"))
    }

    @Test
    fun `the ventilation view shows the value and its smoothing label`() {
        val host = karoo.host<TymewearExtension>()
        TymewearData.update(breathData())

        val root = startView(host, "ve").awaitFrame(10_000).inflate(karoo.app)

        // One breath sampled into the 30 s window, the default mode.
        val texts = root.texts()
        assertTrue(texts.any { it == "10.0" })
        assertTrue(texts.contains("VE 30s"))
    }

    @Test
    fun `the mi view walks from no hr through idle to a percentage`() {
        val host = karoo.host<TymewearExtension>()
        TymewearData.update(breathData())

        // Breathing but no heart rate yet. A fresh view per state keeps each frame the
        // first on its own emitter, so no frame can be dropped by the SDK's throttle.
        assertTrue(firstFrame(host, "mi").texts().contains("no HR"))

        // Below 10 % of heart-rate reserve the index is meaningless; say "idle".
        TymewearData.updateHr(65.0)
        assertTrue(firstFrame(host, "mi").texts().contains("idle"))

        // 140 bpm lifts %HRR past the floor and the index becomes a real number.
        TymewearData.updateHr(140.0)
        val texts = firstFrame(host, "mi").texts()
        assertTrue(texts.contains("30"))
        assertTrue(texts.contains("%"))
    }

    @Test
    fun `the ventilation view colours its background by the smoothed zone`() {
        val host = karoo.host<TymewearExtension>()

        // 10 L/min is below the default Endurance edge of 73.
        TymewearData.update(breathData())
        assertEquals(Constants.ZONE_COLORS_SOLID[0], containerColor(firstFrame(host, "ve")))

        // 85 L/min sits between Endurance and VT1.
        TymewearData.update(breathData(breathRate = 20.0, tidalVolume = 4.25))
        assertEquals(Constants.ZONE_COLORS_SOLID[1], containerColor(firstFrame(host, "ve")))
    }

    @Test
    fun `the time in zones view draws every zone band`() {
        val host = karoo.host<TymewearExtension>()

        val root = startView(host, "ve_zones").awaitFrame(10_000).inflate(karoo.app)

        val bitmap = root.bitmaps().first()
        assertEquals(Constants.ZONES_BITMAP_WIDTH, bitmap.width)
        assertEquals(Constants.ZONES_BITMAP_HEIGHT, bitmap.height)
        // With no time yet the bars fall back to the minimum height, but each one is
        // still drawn in its zone colour.
        assertTrue(bitmap.hasColorNear(Constants.ZONE_COLORS_SOLID[0]))
    }

    @Test
    fun `the graph view draws a line and labels the value`() {
        val host = karoo.host<TymewearExtension>()
        TymewearData.update(breathData())

        val root = startView(host, "ve_graph").awaitFrame(10_000).inflate(karoo.app)

        assertTrue(root.texts().contains("10.0 30s"))
        assertTrue(root.bitmaps().isNotEmpty())
    }

    @Test
    fun `the ventilatory state view shows off while the beta is disabled`() {
        val host = karoo.host<TymewearExtension>()

        val root = startView(host, "vent_state").awaitFrame(10_000).inflate(karoo.app)

        assertTrue(root.texts().contains("off"))
    }

    @Test
    fun `the ventilatory state view shows calibrating once the beta is enabled`() {
        karoo.app.getSharedPreferences("tymewear_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dynamic_state_enabled", true).commit()
        val host = karoo.host<TymewearExtension>()

        val root = startView(host, "vent_state").awaitFrame(10_000).inflate(karoo.app)

        val texts = root.texts()
        assertTrue(texts.contains("cal"))
        assertTrue(texts.contains("learning"))
    }
}
