package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against recording fabricated data.
 *
 * Real FIT files showed 34-104 seconds of genuine breathing data followed by hours of
 * a single frozen value, because the recording path wrote the last-known value every
 * second regardless of age. Freshness is what lets callers write nothing instead of
 * writing fiction.
 */
class DataFreshnessTest {

    private val timeout = 10_000L

    @Test
    fun `is not fresh before any data has arrived`() {
        // Critical: a brand-new session must not look fresh, or we would record
        // zeros/defaults as though the sensor were reporting them.
        val f = DataFreshness(timeout)
        assertFalse(f.isFresh(nowMs = 0L))
        assertFalse(f.isFresh(nowMs = 5_000L))
    }

    @Test
    fun `is fresh immediately after data arrives`() {
        val f = DataFreshness(timeout)
        f.recordUpdate(1_000L)
        assertTrue(f.isFresh(1_000L))
    }

    @Test
    fun `is fresh at exactly the timeout boundary`() {
        val f = DataFreshness(timeout)
        f.recordUpdate(1_000L)
        assertTrue(f.isFresh(1_000L + timeout))
    }

    @Test
    fun `goes stale once the timeout is exceeded`() {
        val f = DataFreshness(timeout)
        f.recordUpdate(1_000L)
        assertFalse(f.isFresh(1_000L + timeout + 1))
    }

    @Test
    fun `becomes fresh again when new data arrives`() {
        val f = DataFreshness(timeout)
        f.recordUpdate(0L)
        assertFalse(f.isFresh(timeout + 1))

        f.recordUpdate(timeout + 2)
        assertTrue(f.isFresh(timeout + 2))
    }

    @Test
    fun `reset makes it stale again`() {
        // Called on disconnect, so a reconnect cannot inherit stale freshness.
        val f = DataFreshness(timeout)
        f.recordUpdate(1_000L)
        assertTrue(f.isFresh(1_000L))

        f.reset()
        assertFalse(f.isFresh(1_000L))
    }

    @Test
    fun `age is null before any data and measured afterwards`() {
        val f = DataFreshness(timeout)
        assertNull(f.ageMs(1_000L))

        f.recordUpdate(1_000L)
        assertEquals(250L, f.ageMs(1_250L))
    }
}
