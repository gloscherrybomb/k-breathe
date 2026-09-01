package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android silently refuses BLE scans from an app that starts them too often
 * (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, ~5 starts per 30s). A refused registration
 * returns scannerId=-1 and the scan simply never runs.
 *
 * Observed on a real Karoo: the system called our extension's startScan 10 times in
 * 12 seconds, producing 5 rejected registrations, after which the sensor could not be
 * discovered at all. This throttle keeps us inside the platform budget so a burst of
 * requests degrades into a short delay rather than a dead scanner.
 */
class ScanThrottleTest {

    private val window = 30_000L

    private fun throttle(max: Int = 4) = ScanThrottle(maxStarts = max, windowMs = window)

    @Test
    fun `allows starts up to the budget`() {
        val t = throttle(max = 4)
        assertTrue(t.tryAcquire(0L))
        assertTrue(t.tryAcquire(100L))
        assertTrue(t.tryAcquire(200L))
        assertTrue(t.tryAcquire(300L))
    }

    @Test
    fun `denies the start that would exceed the budget`() {
        val t = throttle(max = 4)
        repeat(4) { i -> assertTrue(t.tryAcquire(i * 100L)) }
        assertFalse("5th start within the window must be refused", t.tryAcquire(400L))
    }

    @Test
    fun `reports no delay while budget remains`() {
        val t = throttle(max = 4)
        t.tryAcquire(0L)
        assertEquals(0L, t.delayUntilAllowedMs(100L))
    }

    @Test
    fun `reports the delay until the oldest start ages out`() {
        val t = throttle(max = 4)
        repeat(4) { i -> t.tryAcquire(i * 100L) }
        // Oldest start was at 0, so a slot frees at 0 + window.
        assertEquals(window - 500L, t.delayUntilAllowedMs(500L))
    }

    @Test
    fun `allows a start again once the window has slid past`() {
        val t = throttle(max = 4)
        repeat(4) { i -> t.tryAcquire(i * 100L) }
        assertFalse(t.tryAcquire(400L))
        // Just after the oldest start leaves the window, a slot is free again.
        assertTrue(t.tryAcquire(window + 1))
    }

    @Test
    fun `frees slots progressively as staggered starts age out`() {
        val t = throttle(max = 4)
        t.tryAcquire(0L)
        t.tryAcquire(10_000L)
        t.tryAcquire(20_000L)
        t.tryAcquire(25_000L)
        assertFalse(t.tryAcquire(26_000L))

        // At window+1 only the first has aged out: exactly one slot.
        assertTrue(t.tryAcquire(window + 1))
        assertFalse(t.tryAcquire(window + 2))
    }

    @Test
    fun `reset clears the budget`() {
        val t = throttle(max = 4)
        repeat(4) { i -> t.tryAcquire(i * 100L) }
        assertFalse(t.tryAcquire(400L))

        t.reset()
        assertTrue(t.tryAcquire(400L))
    }

    @Test
    fun `defaults stay below the platform limit of five per thirty seconds`() {
        // Deliberately one under the platform's 5, to absorb clock skew between our
        // timestamps and the framework's own accounting.
        val t = ScanThrottle()
        repeat(4) { i -> assertTrue(t.tryAcquire(i * 10L)) }
        assertFalse(t.tryAcquire(50L))
    }
}
