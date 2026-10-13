package com.tymewear.karoo

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The data-type streams the Karoo reads: what the extension publishes as each
// TymewearData flow the strap feeds changes, and what it says before there is data.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.tymewear.karoo"])
class TymewearStreamTest {
    @get:Rule val karoo = FakeKarooRule()

    private val ble = FakeBle(karoo.app)
    private val strap = ble.vitalPro()

    @Before
    fun setUp() {
        Protocol.resetState()
        TymewearData.setDisconnected()
        // The Beta's enabled flag is process-wide; a fresh app has it off.
        VentilatoryState.load(karoo.app)
        ble.grantPermissions()
    }

    private fun connectStrap(host: fi.nikosavola.karooext.testing.FakeKarooHost) {
        val device = host.connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        strap.awaitSubscribed(Protocol.COMMAND_CHAR_UUID)
        device.awaitConnected()
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

    private fun streaming(recorder: fi.nikosavola.karooext.testing.StreamRecorder) =
        recorder.await(10_000) { it is StreamState.Streaming } as StreamState.Streaming

    @Test
    fun `the breathing fields start unavailable and then carry the first breath`() {
        val host = karoo.host<TymewearExtension>()
        val br = host.startStream("br")
        val tv = host.startStream("tv")
        val graph = host.startStream("ve_graph")

        // Zeroed source flows must read as absent, never as a real zero reading.
        br.await(10_000) { it is StreamState.NotAvailable }
        tv.await(10_000) { it is StreamState.NotAvailable }
        graph.await(10_000) { it is StreamState.NotAvailable }

        connectStrap(host)
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(1)))

        assertEquals(20.0, streaming(br).dataPoint.singleValue!!, 1e-6)
        // The numeric tidal volume field is in mL, the graphical one in litres.
        assertEquals(500.0, streaming(tv).dataPoint.singleValue!!, 1e-6)
        assertEquals(10.0, streaming(graph).dataPoint.singleValue!!, 1e-6)
    }

    @Test
    fun `a dropped strap turns the fields back to unavailable rather than zeros`() {
        val host = karoo.host<TymewearExtension>()
        val graph = host.startStream("ve_graph")
        connectStrap(host)
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(1)))
        assertEquals(10.0, streaming(graph).dataPoint.singleValue!!, 1e-6)
        val mark = graph.mark()

        strap.disconnect()

        graph.await(10_000, after = mark) { it is StreamState.NotAvailable }
        assertEquals(0.0, TymewearData.minuteVolume.value, 0.0)
    }

    @Test
    fun `the ventilatory state field reports unavailable while the beta is off`() {
        val host = karoo.host<TymewearExtension>()
        val stream = host.startStream("vent_state")

        stream.await(10_000) { it is StreamState.NotAvailable }
    }

    @Test
    fun `a karoo heart rate reading reaches the extension and is cleared when it goes away`() {
        karoo.host<TymewearExtension>()
        karoo.awaitStreamConsumer(DataType.Type.HEART_RATE)

        karoo.system.setDataPoint(DataType.Type.HEART_RATE, 140.0)

        karoo.awaitValue { TymewearData.heartRate.value.takeIf { it == 140.0 } }
        // 140 bpm with the default 60 resting / 190 max is 61.5 % of heart-rate reserve.
        assertEquals(61.538, TymewearData.percentHrr.value, 0.01)

        karoo.system.setStreamState(DataType.Type.HEART_RATE, StreamState.NotAvailable)

        // No reading must not leave the last heart rate latched for the MI to feed on.
        karoo.awaitValue { true.takeIf { TymewearData.heartRate.value == 0.0 } }
        assertEquals(0.0, TymewearData.percentHrr.value, 0.0)
    }

    @Test
    fun `the power field averages the karoo power stream over three samples`() {
        val host = karoo.host<TymewearExtension>()
        karoo.awaitStreamConsumer(DataType.Type.POWER)
        val stream = host.startStream("power_vz")
        stream.await(10_000) { it is StreamState.NotAvailable }

        // Wait for each emission before feeding the next, so the state-flow conflation
        // cannot drop a sample and leave the average under test racing the collector.
        karoo.system.setDataPoint(DataType.Type.POWER, 100.0)
        assertEquals(100.0, (stream.await(10_000) { it is StreamState.Streaming } as StreamState.Streaming).dataPoint.singleValue!!, 1e-6)
        karoo.system.setDataPoint(DataType.Type.POWER, 200.0)
        assertEquals(150.0, (stream.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 150.0 } as StreamState.Streaming).dataPoint.singleValue!!, 1e-6)
        karoo.system.setDataPoint(DataType.Type.POWER, 300.0)
        assertEquals(200.0, (stream.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 200.0 } as StreamState.Streaming).dataPoint.singleValue!!, 1e-6)
    }

    @Test
    fun `a power stream gap drops the power field back to unavailable`() {
        val host = karoo.host<TymewearExtension>()
        karoo.awaitStreamConsumer(DataType.Type.POWER)
        val stream = host.startStream("power_vz")

        karoo.system.setDataPoint(DataType.Type.POWER, 200.0)
        assertEquals(200.0, streaming(stream).dataPoint.singleValue!!, 1e-6)

        val mark = stream.mark()
        karoo.system.setStreamState(DataType.Type.POWER, StreamState.NotAvailable)

        stream.await(10_000, after = mark) { it is StreamState.NotAvailable }
    }

    @Test
    fun `the mobilization index needs both a breath and a heart rate`() {
        val host = karoo.host<TymewearExtension>()
        val stream = host.startStream("mi")
        stream.await(10_000) { it is StreamState.NotAvailable }

        // Fresh breathing but no heart rate yet: the index has nothing to divide by, and
        // the flow stays at its zero so no value update is published.
        TymewearData.update(breathData())
        assertEquals(0.0, TymewearData.mobilizationIndex.value, 0.0)

        TymewearData.updateHr(140.0)

        val mi = streaming(stream).dataPoint.singleValue!!
        // (20-12)/(55-12) %BRR over (140-60)/(190-60) %HRR, times 100.
        assertEquals(30.233, mi, 0.05)
    }

    @Test
    fun `disconnecting zeroes the shared flows the fields read`() {
        TymewearData.update(breathData())
        assertTrue(TymewearData.isDataFresh())

        TymewearData.setDisconnected()

        assertEquals(0.0, TymewearData.smoothBreathRate.value, 0.0)
        assertEquals(0.0, TymewearData.smoothTidalVolume.value, 0.0)
        assertEquals(0.0, TymewearData.smoothMinuteVolume.value, 0.0)
        assertEquals(0, TymewearData.veZone.value)
        assertFalse(TymewearData.isDataFresh())
    }
}
