package com.tymewear.karoo

import fi.nikosavola.karooext.testing.DeviceRecorder
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnManufacturerInfo
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// BleManager, TymewearDevice and Protocol run for real against Robolectric's Bluetooth shadows.
// instrumentedPackages puts the extension's own System.currentTimeMillis on the Robolectric clock,
// so staleness and watchdog timers move with RobolectricPump.advanceBy.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.tymewear.karoo"])
class VitalProConnectionTest {
    @get:Rule val karoo = FakeKarooRule()

    private val ble = FakeBle(karoo.app)
    private lateinit var strap: FakeBlePeripheral

    private val ve = DataType.dataTypeId(EXT, "ve")
    private val br = DataType.dataTypeId(EXT, "br")
    private val tv = DataType.dataTypeId(EXT, "tv")

    @Before
    fun setUp() {
        // Process-wide singletons survive from one test to the next.
        Protocol.resetState()
        TymewearData.setDisconnected()
        ble.grantPermissions()
        strap = ble.vitalPro()
    }

    /** Connects the strap the way a first connection goes: targeted scan hit, then GATT. */
    private fun connectedStrap(): DeviceRecorder {
        val device = karoo.host<TymewearExtension>().connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        strap.awaitSubscribed(Protocol.COMMAND_CHAR_UUID)
        device.awaitConnected()
        return device
    }

    private fun startedServices() =
        generateSequence { shadowOf(karoo.app).nextStartedService }.map { it.component!!.className }.toList()

    private fun stoppedServices() =
        generateSequence { shadowOf(karoo.app).nextStoppedService }.map { it.component!!.className }.toList()

    @Test
    fun `breath packets reach the karoo as ve br and tv data points`() {
        val device = connectedStrap()

        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)))

        val point = device.awaitDataPoint(ve)
        assertEquals(10.0, point.singleValue!!, 1e-6)
        assertEquals(STRAP_ADDRESS, point.sourceId)
        assertEquals(20.0, device.awaitDataPoint(br).singleValue!!, 1e-6)
        assertEquals(0.5, device.awaitDataPoint(tv).singleValue!!, 1e-6)
    }

    @Test
    fun `connecting reports searching then connected with the strap's identity and battery`() {
        val device = connectedStrap()

        val status = device.items.filterIsInstance<OnConnectionStatus>().map { it.status }
        assertEquals(listOf(ConnectionStatus.SEARCHING, ConnectionStatus.CONNECTED), status)
        val info = device.awaitOf<OnManufacturerInfo>(10_000)
        assertEquals("Tymewear", info.info.manufacturer)
        assertEquals("VitalPro", info.info.modelNumber)

        // The battery read starts once every CCCD write has completed.
        val battery = device.awaitOf<OnBatteryStatus>(10_000)
        assertEquals(BatteryStatus.GOOD, battery.status)
        assertEquals(80, TymewearData.batteryPercent.value)
    }

    @Test
    fun `every notifying characteristic of the strap gets subscribed`() {
        connectedStrap()

        strap.awaitSubscribed(Protocol.BREATHING_DATA_CHAR_UUID)
        strap.awaitSubscribed(Protocol.SENSOR_ID_CHAR_UUID)
        assertEquals(
            setOf(
                Protocol.BREATHING_DATA_CHAR_UUID,
                Protocol.COMMAND_CHAR_UUID,
                Protocol.SENSOR_ID_CHAR_UUID,
            ),
            strap.subscribedCharacteristics,
        )
    }

    @Test
    fun `breath rate follows the gap between packets after the first one`() {
        val device = connectedStrap()

        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)))
        // 2.5 s later: 24 breaths/min whatever the inhale and exhale durations say.
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100) + 62, tidalRaw = 60))

        device.awaitDataPoint(br, predicate = { Math.abs(it.singleValue!! - 24.19) < 0.01 })
        val second = device.awaitDataPoint(ve, predicate = { it.singleValue!! > 14.0 })
        assertEquals(60.0 / (62 * 0.04) * 0.6, second.singleValue!!, 1e-6)
    }

    @Test
    fun `imu adc and malformed packets produce no data points`() {
        val device = connectedStrap()

        strap.notify(Protocol.COMMAND_CHAR_UUID, imuPacket())
        strap.notify(Protocol.COMMAND_CHAR_UUID, adcPeakPacket())
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)).copyOf(10))
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(101), inhale = 0, exhale = 0))
        // Over the plausibility limits: 5 L at 20 breaths/min is 100 L/min, so push TV to 30 L.
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(102), tidalRaw = 3000))
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(103)))

        // Packets are handled in order, so once the valid one is out the rest were dropped.
        device.awaitDataPoint(tv)
        assertEquals(1, device.dataPoints(ve).size)
        assertEquals(1, device.dataPoints(br).size)
        assertEquals(1, device.dataPoints(tv).size)
    }

    @Test
    fun `a dropped link reconnects directly and restarts breath rate from a clean slate`() {
        val device = connectedStrap()
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)))
        device.awaitDataPoint(ve)
        val beforeDrop = device.mark()

        strap.disconnect()
        device.awaitStatus(ConnectionStatus.SEARCHING, after = beforeDrop)
        assertFalse(TymewearData.isConnected.value)
        assertEquals(0.0, TymewearData.smoothMinuteVolume.value, 0.0)

        // The first retry is a direct connectGatt after the 2 s rapid-phase delay, with no new scan.
        RobolectricPump.advanceBy(Constants.BLE_RAPID_PHASE_DELAY_MS.milliseconds)
        assertFalse(ble.isScanning)
        strap.connect()
        strap.awaitSubscribed(Protocol.COMMAND_CHAR_UUID)
        device.awaitConnected(after = beforeDrop)

        // One second after the pre-drop packet. Had the parser kept its timestamp, that gap would
        // read as 60 breaths/min; a fresh session estimates 20 from the inhale and exhale durations.
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100) + 25))
        val after = device.awaitDataPoint(br, after = beforeDrop)
        assertEquals(20.0, after.singleValue!!, 1e-6)
    }

    @Test
    fun `cancelling the session closes the gatt and hands bluetooth back`() {
        val host = karoo.host<TymewearExtension>()
        val device = host.connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        device.awaitConnected()
        val gatt = strap.gatt!!
        karoo.awaitEffect<RequestBluetooth>()
        assertTrue(startedServices().contains(BleForegroundService::class.java.name))

        host.disconnectDevice(device)

        karoo.awaitValue { true.takeIf { strap.gatt == null } }
        assertTrue(shadowOf(gatt).isClosed)
        // The foreground pin outlives the session by a debounce, in case another connect follows.
        assertFalse(stoppedServices().contains(BleForegroundService::class.java.name))
        RobolectricPump.advanceBy(2.seconds)
        assertTrue(stoppedServices().contains(BleForegroundService::class.java.name))
        karoo.close()
        assertEquals(1, karoo.system.effectsOf<ReleaseBluetooth>().size)
    }

    @Test
    fun `silent notification loss forces a reconnect once the watchdog timeout passes`() {
        val device = connectedStrap()
        strap.notify(Protocol.COMMAND_CHAR_UUID, breathPacket(ticks(100)))
        device.awaitDataPoint(tv)
        val mark = device.mark()

        // Still inside the 45 s allowance for sensor pauses.
        RobolectricPump.advanceBy(40.seconds)
        assertEquals(ConnectionStatus.CONNECTED, device.connectionStatus)
        assertFalse("the value went stale well before that", TymewearData.isDataFresh())

        RobolectricPump.advanceBy(20.seconds)
        device.awaitStatus(ConnectionStatus.SEARCHING, after = mark)
        assertEquals(0.0, TymewearData.smoothMinuteVolume.value, 0.0)
    }

    @Test
    fun `a strap without the vitalpro service is dropped and retried`() {
        val other = ble.vitalPro("AA:BB:CC:DD:EE:02", withService = false)
        val device = karoo.host<TymewearExtension>().connectDevice(other.address)
        other.advertise()
        val first = other.connect()
        device.awaitConnected()
        val mark = device.mark()

        // Service discovery runs 300 ms after connecting, finds nothing, and disconnects.
        device.awaitStatus(ConnectionStatus.SEARCHING, after = mark)
        RobolectricPump.advanceBy(Constants.BLE_RAPID_PHASE_DELAY_MS.milliseconds)
        val second = other.awaitConnectionRequest()
        assertNotSame(first, second)
    }

    @Test
    fun `a strap that never advertises falls back to autoConnect after the scan window`() {
        val device = karoo.host<TymewearExtension>().connectDevice(STRAP_ADDRESS)
        ble.awaitScan()
        assertEquals(0, strap.connectionAttempts)

        // No advertisement arrives, so the targeted scan times out and the extension
        // connects directly instead of waiting longer.
        RobolectricPump.advanceBy(Constants.BLE_INITIAL_SCAN_TIMEOUT_MS.milliseconds + 1.seconds)

        strap.awaitConnectionRequest()
        assertFalse(ble.isScanning)
        device.awaitStatus(ConnectionStatus.SEARCHING)
    }

    @Test
    fun `after the rapid retries fail the extension makes one slow-phase attempt and waits`() {
        val device = karoo.host<TymewearExtension>().connectDevice(STRAP_ADDRESS)
        strap.advertise()
        strap.connect()
        device.awaitConnected()
        val attempts = { shadowOf(ble.adapter.getRemoteDevice(STRAP_ADDRESS)).bluetoothGatts.size }

        // The link drops with GATT error 133, then every retry fails the same way. Each retry is
        // waited for rather than counted in virtual seconds.
        strap.disconnect(status = 133)
        repeat(Constants.BLE_RAPID_PHASE_ATTEMPTS) {
            RobolectricPump.advanceBy(Constants.BLE_RAPID_PHASE_DELAY_MS.milliseconds)
            strap.awaitConnectionRequest()
            strap.failConnection(status = 133)
        }
        // The next attempt is the slow phase and follows at once: well under the 2 s a rapid retry
        // waits. Robolectric drops the autoConnect flag, so only the pacing can be asserted.
        strap.awaitConnectionRequest(timeoutMs = 500)
        val settled = attempts()
        RobolectricPump.advanceBy(30.seconds)
        assertEquals(settled, attempts())

        RobolectricPump.advanceBy((Constants.BLE_SLOW_PHASE_DELAY_MS / 1000).seconds)
        karoo.awaitValue { true.takeIf { attempts() > settled } }
    }
}
