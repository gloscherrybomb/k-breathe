package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftTrackerTest {

    @Test
    fun `reports nothing during warmup`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 3)
        t.add(0L, 20.0)
        assertNull(t.driftPercent())
    }

    @Test
    fun `zero drift when breathing is unchanged`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 40) t.add(s * 1000L, 20.0)
        assertEquals(0.0, t.driftPercent()!!, 0.001)
    }

    @Test
    fun `positive drift when breathing rate climbs`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)          // reference: 20
        for (s in 30 until 40) t.add(s * 1000L, 23.0)         // current: 23
        assertEquals(15.0, t.driftPercent()!!, 0.5)
    }

    @Test
    fun `negative drift when breathing settles`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)
        for (s in 30 until 40) t.add(s * 1000L, 18.0)
        assertTrue(t.driftPercent()!! < 0.0)
    }

    @Test
    fun `the reference window is fixed once captured`() {
        // The reference is the early steady period; later hard efforts must not redefine it.
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)
        for (s in 10 until 60) t.add(s * 1000L, 30.0)
        assertEquals("reference should still be 20", 50.0, t.driftPercent()!!, 1.0)
    }

    @Test
    fun `reset clears both windows`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 40) t.add(s * 1000L, 20.0)
        t.reset()
        assertNull(t.driftPercent())
    }

    @Test
    fun `real ride breathing rates produce a plausible drift`() {
        val f = RideFixture.load("ride_2026-03-29.csv")
        val t = DriftTracker()
        var s = 0L
        for (v in f.br) { if (v != null && v > 0) t.add(s * 1000L, v); s++ }
        val d = t.driftPercent()
        assertTrue("expected a drift value for a 75-minute ride", d != null)
        assertTrue("drift should be within a sane range, got $d", d!! > -50.0 && d < 100.0)
    }
}
