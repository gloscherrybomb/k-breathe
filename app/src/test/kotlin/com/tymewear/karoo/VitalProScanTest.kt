package com.tymewear.karoo

import android.os.Looper
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import io.hammerhead.karooext.models.DataType
import java.time.Duration
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.tymewear.karoo"])
class VitalProScanTest {
    @get:Rule val karoo = FakeKarooRule()

    private val ble = FakeBle(karoo.app)

    @Before
    fun setUp() {
        ble.grantPermissions()
    }

    private fun awaitScanStopped() {
        karoo.awaitValue { true.takeIf { !ble.isScanning } }
    }

    @Test
    fun `scan lists vitalpro straps and ignores other sensors`() {
        val straps = listOf(
            ble.vitalPro("AA:BB:CC:00:00:01", name = "VitalPro R"),
            ble.vitalPro("AA:BB:CC:00:00:02", name = "TYME-1A2B"),
        )
        val others = listOf(
            ble.peripheral("AA:BB:CC:00:00:03", name = "TymeHR 1234567"),
            ble.peripheral("AA:BB:CC:00:00:04", name = "Garmin HRM-Pro"),
            ble.peripheral("AA:BB:CC:00:00:05"),
            // Four hex digits only: the strap name regex is exact.
            ble.peripheral("AA:BB:CC:00:00:06", name = "TYME-1A2B3"),
        )
        val scan = karoo.host<TymewearExtension>().startScan()

        others.forEach { it.advertise() }
        straps.forEach { it.advertise() }

        scan.await(10_000) { it.uid == "AA:BB:CC:00:00:02" }
        assertEquals(listOf("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:02"), scan.items.map { it.uid })
        assertEquals(listOf("VitalPro R", "TYME-1A2B"), scan.items.map { it.displayName })
    }

    @Test
    fun `a found strap offers every breathing data type to the karoo`() {
        val scan = karoo.host<TymewearExtension>().startScan()

        ble.vitalPro().advertise()

        val device = scan.await(10_000) { true }
        assertEquals(EXT, device.extension)
        assertEquals(
            setOf("ve", "ve_graph", "br", "tv", "mi", "mi_bat", "ve_zones", "vent_state", "power_vz")
                .map { DataType.dataTypeId(EXT, it) }
                .toSet(),
            device.dataTypes.toSet(),
        )
    }

    @Test
    fun `a strap that keeps advertising is reported once`() {
        val strap = ble.vitalPro()
        val scan = karoo.host<TymewearExtension>().startScan()

        repeat(3) { strap.advertise() }
        ble.vitalPro("AA:BB:CC:00:00:09", name = "VitalPro S").advertise()

        scan.await(10_000) { it.uid == "AA:BB:CC:00:00:09" }
        assertEquals(listOf(STRAP_ADDRESS, "AA:BB:CC:00:00:09"), scan.items.map { it.uid })
    }

    @Test
    fun `a configured sensor id narrows the scan to that strap`() {
        karoo.app.getSharedPreferences("tymewear_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("sensor_id", "9F9F").commit()
        val mine = ble.vitalPro("AA:BB:CC:00:00:0A", name = "TYME-9F9F")
        val neighbour = ble.vitalPro("AA:BB:CC:00:00:0B", name = "TYME-1A2B")
        val scan = karoo.host<TymewearExtension>().startScan()

        neighbour.advertise()
        mine.advertise()

        scan.await(10_000) { true }
        assertEquals(listOf(mine.address), scan.items.map { it.uid })
    }

    @Test
    fun `stopping the scan stops the radio scan`() {
        val host = karoo.host<TymewearExtension>()
        val scan = host.startScan()
        ble.awaitScan()

        host.stopScan(scan)

        awaitScanStopped()
    }

    @Test
    fun `a karoo that restarts the scan over and over stays inside the platform scan budget`() {
        val host = karoo.host<TymewearExtension>()
        repeat(ScanThrottle.DEFAULT_MAX_STARTS) {
            val scan = host.startScan()
            ble.awaitScan()
            host.stopScan(scan)
            awaitScanStopped()
        }

        host.startScan()
        // The fifth start inside the window would be silently refused by Android, so it is
        // parked on the main looper. Wait for that, since the extension sets it up on IO.
        karoo.awaitValue {
            shadowOf(Looper.getMainLooper()).nextScheduledTaskTime.takeIf { it > Duration.ZERO }
        }
        assertFalse(ble.isScanning)

        RobolectricPump.advanceBy(35.seconds)
        assertTrue(ble.isScanning)
    }

    @Test
    fun `a scan deferred by the rate budget never starts if it is cancelled first`() {
        val host = karoo.host<TymewearExtension>()
        repeat(ScanThrottle.DEFAULT_MAX_STARTS) {
            val scan = host.startScan()
            ble.awaitScan()
            host.stopScan(scan)
            awaitScanStopped()
        }
        val deferred = host.startScan()
        // The fifth start is parked on the main looper rather than issued.
        karoo.awaitValue {
            shadowOf(Looper.getMainLooper()).nextScheduledTaskTime.takeIf { it > Duration.ZERO }
        }

        host.stopScan(deferred)
        // Let the cancellation tear the scan flow down before the deferred start would
        // have been due, so advancing the clock cannot race it.
        karoo.awaitValue {
            true.takeIf { shadowOf(Looper.getMainLooper()).nextScheduledTaskTime == Duration.ZERO }
        }
        RobolectricPump.advanceBy(35.seconds)

        assertFalse(ble.isScanning)
    }
}
