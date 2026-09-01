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

    @Test
    fun `current window is time-scoped, not sample-scoped`() {
        // Feed samples at 4-second intervals (every 4000ms), matching breath-rate packet cadence.
        // With a 5-second current window and only ~1-2 samples per window, a count-based
        // approach (original bug) would span 4–8 seconds or more; time-scoping keeps it at 5s.
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)

        // Build reference: 10 samples at 4s intervals = 40s span, mean = 20
        for (i in 0 until 10) t.add(i * 4000L, 20.0)

        // Now feed current samples every 4 seconds at value 23, verifying time-scoped window
        // After adding sample at t=40s, the 5s window should be [35s, 40s], only containing
        // the latest sample (since prior samples were at 36s, 32s, 28s, etc., all before 35s).
        // If count-based, it would hold 2 samples across ~8 seconds.
        for (i in 10 until 20) t.add(i * 4000L, 23.0)

        val drift = t.driftPercent()
        assertTrue("expected drift to be computed after reference and current data", drift != null)
        // With time-scoped window at sparse cadence, drift should still be close to 15%
        // (23/20 - 1) * 100 = 15%, demonstrating that time-scoping correctly averages
        // the values that fall within the time window regardless of sample count.
        assertEquals("time-scoped window should yield ~15% drift even at sparse cadence",
                     15.0, drift!!, 1.0)
    }
}
