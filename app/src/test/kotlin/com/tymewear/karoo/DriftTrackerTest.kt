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
        // At sparse cadence (every 4000ms), a count-based window retains far more wall-clock
        // time than intended. This test verifies time-scoping actually bounds the window.
        //
        // Setup: referenceSeconds=1 (seals quickly), currentSeconds=8 (8000ms), samples every 4s.
        // At t=36000ms:
        //   Count-based (8 samples): [t=8s:20, t=12s:20, t=16s:20, t=20s:20, t=24s:20, t=28s:20, t=32s:20, t=36s:30]
        //     Average = (20*7 + 30)/8 = 21.25, drift = (21.25/20 - 1)*100 = 6.25%
        //   Time-based (8s window [28s, 36s]): [t=28s:20, t=32s:20, t=36s:30]
        //     Average = (20+20+30)/3 = 23.33, drift = (23.33/20 - 1)*100 = 16.67%
        // They differ by ~10 percentage points, proving count-based vs time-based.
        val t = DriftTracker(referenceSeconds = 1, currentSeconds = 8, warmupSeconds = 0)

        // Build reference: seals at t=4000ms with mean 20
        for (i in 0 until 2) t.add(i * 4000L, 20.0)

        // Fill current phase with value 20 for 7 samples (t=8s through t=32s)
        for (i in 2 until 9) t.add(i * 4000L, 20.0)

        // Switch to value 30 at t=36000ms
        t.add(36000L, 30.0)

        val drift = t.driftPercent()
        assertTrue("expected drift to be computed", drift != null)

        // Time-based window bounds to 8 seconds, holding ~3 samples.
        // Count-based would hold 8 samples spanning ~28 seconds, mixing old 20s with new 30.
        // This test passes ONLY if time-scoping is real; count-based would give ~6%, time-based ~17%.
        assertEquals(
            "time-scoped window (~17%) vs count-based (~6%) must differ; got $drift",
            16.67, drift!!, 1.5
        )
    }

    @Test
    fun `backwards timestamp is rejected to prevent stale data in window`() {
        // When system clock jumps backwards (sync, etc.), a sample with an older timestamp
        // must not corrupt the window. This test feeds normal samples, then a backwards one.
        val t = DriftTracker(referenceSeconds = 2, currentSeconds = 5, warmupSeconds = 0)

        // Build and seal reference with samples at t=0, t=4s, mean = 20
        t.add(0L, 20.0)
        t.add(4000L, 20.0)

        // Add current samples: t=8s, t=12s, t=16s (all value 20)
        t.add(8000L, 20.0)
        t.add(12000L, 20.0)
        t.add(16000L, 20.0)

        // At t=20s, drift should be 0 (all value 20)
        t.add(20000L, 20.0)
        var drift = t.driftPercent()
        assertEquals("drift should be 0 with homogeneous current window", 0.0, drift!!, 0.001)

        // Simulate backwards time jump: sample at t=10s (earlier than t=20s)
        // This should be rejected, not added to current window
        t.add(10000L, 50.0)

        // Drift should still be 0 because the t=10s:50.0 sample was rejected
        drift = t.driftPercent()
        assertEquals(
            "backwards timestamp should be rejected; drift must remain 0, not become contaminated",
            0.0, drift!!, 0.001
        )
    }
}
