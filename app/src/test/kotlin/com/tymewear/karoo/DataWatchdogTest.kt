package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog exists to detect silent notification loss: GATT reports "connected"
 * but breath packets stop arriving.
 *
 * The regression these tests lock down: the original implementation poisoned its own
 * clock on firing (`lastDataTime.set(Long.MAX_VALUE)`) while its guard required
 * `last != Long.MAX_VALUE`. Since only an incoming notification restored the clock,
 * a failed recovery disabled the watchdog for the rest of the ride — the exact
 * behaviour observed in real FIT files (30-100s of data, then hours of silence).
 */
class DataWatchdogTest {

    private val timeout = 45_000L

    @Test
    fun `never fires before the first packet has ever arrived`() {
        val wd = DataWatchdog(timeout)
        // A long time passes at startup while we are still waiting to connect.
        assertFalse(wd.shouldForceReconnect(nowMs = 10 * timeout))
    }

    @Test
    fun `does not fire while data is arriving`() {
        val wd = DataWatchdog(timeout)
        wd.onData(1_000L)
        assertFalse(wd.shouldForceReconnect(1_000L + timeout - 1))
    }

    @Test
    fun `does not fire at exactly the timeout boundary`() {
        val wd = DataWatchdog(timeout)
        wd.onData(1_000L)
        assertFalse(wd.shouldForceReconnect(1_000L + timeout))
    }

    @Test
    fun `fires once the timeout is exceeded with no data`() {
        val wd = DataWatchdog(timeout)
        wd.onData(1_000L)
        assertTrue(wd.shouldForceReconnect(1_000L + timeout + 1))
    }

    @Test
    fun `keeps firing when recovery fails and data never returns`() {
        // THE REGRESSION TEST. The old implementation fired exactly once here.
        val wd = DataWatchdog(timeout)
        wd.onData(0L)

        var now = timeout + 1
        assertTrue("first attempt should fire", wd.shouldForceReconnect(now))

        // Recovery failed: no onData() call. The watchdog must try again, not give up.
        now += timeout + 1
        assertTrue("second attempt should fire after another timeout", wd.shouldForceReconnect(now))

        now += timeout + 1
        assertTrue("third attempt should fire", wd.shouldForceReconnect(now))
    }

    @Test
    fun `does not fire again immediately after firing`() {
        // Retries are spaced by the timeout, so we don't spin on disconnect().
        val wd = DataWatchdog(timeout)
        wd.onData(0L)
        assertTrue(wd.shouldForceReconnect(timeout + 1))
        assertFalse(wd.shouldForceReconnect(timeout + 2))
    }

    @Test
    fun `stops firing once data resumes`() {
        val wd = DataWatchdog(timeout)
        wd.onData(0L)
        assertTrue(wd.shouldForceReconnect(timeout + 1))

        // Recovery worked: packets flow again.
        wd.onData(timeout + 2)
        assertFalse(wd.shouldForceReconnect(timeout + 3))
    }

    @Test
    fun `counts consecutive failed recoveries for diagnostics`() {
        val wd = DataWatchdog(timeout)
        wd.onData(0L)
        assertEquals(0, wd.consecutiveFailures)

        var now = timeout + 1
        wd.shouldForceReconnect(now)
        assertEquals(1, wd.consecutiveFailures)

        now += timeout + 1
        wd.shouldForceReconnect(now)
        assertEquals(2, wd.consecutiveFailures)

        // A successful recovery clears the count.
        wd.onData(now + 1)
        assertEquals(0, wd.consecutiveFailures)
    }
}
