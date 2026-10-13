package com.tymewear.karoo

import fi.nikosavola.karooext.testing.developerValues
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The extension service's own lifecycle: the radio claim, the foreground-service pin,
// process death and restart, and the final FIT summary.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.tymewear.karoo"])
class TymewearLifecycleTest {
    @get:Rule val karoo = FakeKarooRule()

    private val ble = FakeBle(karoo.app)

    @Before
    fun setUp() {
        Protocol.resetState()
        TymewearData.setDisconnected()
        TymewearData.resetZoneTimes()
        ble.grantPermissions()
    }

    private fun startedServices() =
        generateSequence { shadowOf(karoo.app).nextStartedService }.map { it.component!!.className }.toList()

    private fun stoppedServices() =
        generateSequence { shadowOf(karoo.app).nextStoppedService }.map { it.component!!.className }.toList()

    @Test
    fun `two connections hold the foreground service open until the last one closes`() {
        val host = karoo.host<TymewearExtension>()
        val first = host.connectDevice("AA:BB:CC:DD:EE:01")
        val second = host.connectDevice("AA:BB:CC:DD:EE:02")
        assertTrue(startedServices().contains(BleForegroundService::class.java.name))

        // One session left is not enough to drop the pin, even after the debounce.
        host.disconnectDevice(first)
        RobolectricPump.advanceBy(2.seconds)
        assertFalse(stoppedServices().contains(BleForegroundService::class.java.name))

        host.disconnectDevice(second)
        RobolectricPump.advanceBy(2.seconds)
        assertTrue(stoppedServices().contains(BleForegroundService::class.java.name))
    }

    @Test
    fun `reconnecting before the debounce cancels the pending foreground stop`() {
        val host = karoo.host<TymewearExtension>()
        val first = host.connectDevice("AA:BB:CC:DD:EE:01")
        assertTrue(startedServices().contains(BleForegroundService::class.java.name))

        // Drop to zero, then come back before the debounce fires; the service must stay.
        host.disconnectDevice(first)
        host.connectDevice("AA:BB:CC:DD:EE:02")
        RobolectricPump.advanceBy(2.seconds)

        assertFalse(stoppedServices().contains(BleForegroundService::class.java.name))
    }

    @Test
    fun `a restart mid ride recovers into a fresh instance that reconnects`() {
        val strap = ble.vitalPro()
        val host = karoo.host<TymewearExtension>()
        val device = host.connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        strap.awaitSubscribed(Protocol.COMMAND_CHAR_UUID)
        device.awaitConnected()
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(1)))
        karoo.awaitValue { true.takeIf { TymewearData.isDataFresh() } }

        // The Karoo killed the extension process mid-ride.
        val resumed = karoo.restart<TymewearExtension>()

        assertFalse("a dead process forgets its connection", TymewearData.isConnected.value)
        karoo.awaitConsumer(RideState.Params)
        karoo.awaitStreamConsumer(DataType.Type.HEART_RATE)

        // The fresh instance can rediscover and reconnect to the same strap.
        val again = resumed.connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        strap.awaitSubscribed(Protocol.COMMAND_CHAR_UUID)
        again.awaitConnected()
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(2)))
        karoo.awaitValue { true.takeIf { TymewearData.isDataFresh() } }
    }

    @Test
    fun `destroying the extension drops the bluetooth claim`() {
        karoo.host<TymewearExtension>()
        karoo.awaitEffect<RequestBluetooth>()

        karoo.close()

        assertEquals(1, karoo.system.effectsOf<ReleaseBluetooth>().size)
        assertTrue(karoo.system.radiosBalanced())
    }

    @Test
    fun `stopping the fit session writes the final zone summary`() {
        val strap = ble.vitalPro()
        val host = karoo.host<TymewearExtension>()
        val device = host.connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        strap.awaitSubscribed(Protocol.COMMAND_CHAR_UUID)
        device.awaitConnected()
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(1)))
        karoo.awaitValue { true.takeIf { TymewearData.isDataFresh() } }

        val fit = host.startFit()
        karoo.awaitStreamConsumer(DataType.Type.ELAPSED_TIME)
        karoo.system.setRideState(RideState.Recording)
        karoo.system.setDataPoint(DataType.Type.ELAPSED_TIME, 1.0)
        fit.awaitRecord()

        host.stopFit(fit)

        val summary = fit.awaitOf<WriteToSessionMesg>(10_000).developerValues()
        assertEquals(1 / 60.0, summary.getValue("tyme_ve_zone1_time"), 1e-4)
        assertEquals(100.0, summary.getValue("tyme_ve_zone1_percentage"), 1e-4)
    }
}
