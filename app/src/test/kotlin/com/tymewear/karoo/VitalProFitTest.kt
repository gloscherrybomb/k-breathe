package com.tymewear.karoo

import fi.nikosavola.karooext.testing.developerValues
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// From strap bytes on the radio to developer fields in the ride file.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.tymewear.karoo"])
class VitalProFitTest {
    @get:Rule val karoo = FakeKarooRule()

    private val ble = FakeBle(karoo.app)
    private val strap = ble.vitalPro()

    @Before
    fun setUp() {
        Protocol.resetState()
        TymewearData.setDisconnected()
        TymewearData.resetZoneTimes()
        ble.grantPermissions()
    }

    private fun connectStrap(host: fi.nikosavola.karooext.testing.FakeKarooHost) {
        val device = host.connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        strap.awaitSubscribed(Protocol.COMMAND_CHAR_UUID)
        device.awaitConnected()
    }

    // Notifications reach the extension through the main looper, so wait until one has landed
    // before a tick that depends on it.
    private fun awaitFresh() {
        karoo.awaitValue { true.takeIf { TymewearData.isDataFresh() } }
    }

    private fun tick(seconds: Double) = karoo.system.setDataPoint(DataType.Type.ELAPSED_TIME, seconds)

    @Test
    fun `a fresh breath is recorded with its zone every second`() {
        val host = karoo.host<TymewearExtension>()
        connectStrap(host)
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)))
        awaitFresh()
        val fit = host.startFit()
        karoo.awaitStreamConsumer(DataType.Type.ELAPSED_TIME)
        karoo.system.setRideState(RideState.Recording)

        tick(1.0)

        val record = fit.awaitRecord { it.containsKey("tyme_minute_volume") }
        assertEquals(20.0, record.getValue("tyme_breath_rate"), 1e-4)
        assertEquals(0.5, record.getValue("tyme_tidal_volume"), 1e-4)
        assertEquals(10.0, record.getValue("tyme_minute_volume"), 1e-4)
        assertEquals(120.0 / 180.0, record.getValue("tyme_inhale_exhale_ratio"), 1e-4)
        // 10 L/min is below the default endurance edge of 73.
        assertEquals(1.0, record.getValue("tyme_ve_zone"), 0.0)
    }

    @Test
    fun `no record is written once the strap has gone quiet`() {
        val host = karoo.host<TymewearExtension>()
        connectStrap(host)
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)))
        awaitFresh()
        val fit = host.startFit()
        karoo.awaitStreamConsumer(DataType.Type.ELAPSED_TIME)
        karoo.system.setRideState(RideState.Recording)
        tick(1.0)
        fit.awaitRecord()
        val mark = fit.mark()

        // Past the 10 s staleness limit, still inside the 45 s watchdog allowance.
        RobolectricPump.advanceBy(15.seconds)
        tick(16.0)
        fit.awaitQuiet(quietMs = 300, timeoutMs = 10_000)
        assertEquals(mark, fit.mark())

        // Fresh data brings records back. Fields are 8-breath averages, so 0.8 L blends with 0.5 L.
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(110), tidalRaw = 80))
        awaitFresh()
        tick(17.0)
        val record = fit.awaitRecord(after = mark) { it.containsKey("tyme_minute_volume") }
        assertEquals(0.65, record.getValue("tyme_tidal_volume"), 1e-4)
    }

    @Test
    fun `pausing writes the time spent in each ventilation zone to the session`() {
        val host = karoo.host<TymewearExtension>()
        connectStrap(host)
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)))
        awaitFresh()
        val fit = host.startFit()
        karoo.awaitStreamConsumer(DataType.Type.ELAPSED_TIME)
        karoo.system.setRideState(RideState.Recording)
        tick(1.0)
        fit.awaitRecord()
        tick(2.0)
        fit.awaitRecord(after = 1)

        karoo.system.setRideState(RideState.Paused(auto = false))

        val summary = fit.awaitOf<WriteToSessionMesg>(10_000).developerValues()
        assertEquals(2 / 60.0, summary.getValue("tyme_ve_zone1_time"), 1e-4)
        assertEquals(100.0, summary.getValue("tyme_ve_zone1_percentage"), 1e-4)
        assertNotNull(summary["tyme_ve_zone5_time"])
    }
}
