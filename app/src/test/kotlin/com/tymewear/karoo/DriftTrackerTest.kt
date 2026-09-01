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
        assertNull(t.driftPercent(0L))
    }

    @Test
    fun `zero drift when breathing is unchanged`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 40) t.add(s * 1000L, 20.0)
        assertEquals(0.0, t.driftPercent(39_000L)!!, 0.001)
    }

    @Test
    fun `positive drift when breathing rate climbs`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)          // reference: 20
        for (s in 30 until 40) t.add(s * 1000L, 23.0)         // current: 23
        assertEquals(15.0, t.driftPercent(39_000L)!!, 0.5)
    }

    @Test
    fun `negative drift when breathing settles`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)
        for (s in 30 until 40) t.add(s * 1000L, 18.0)
        assertTrue(t.driftPercent(39_000L)!! < 0.0)
    }

    @Test
    fun `the reference window is fixed once captured`() {
        // The reference is the early steady period; later hard efforts must not redefine it.
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)
        for (s in 10 until 60) t.add(s * 1000L, 30.0)
        assertEquals("reference should still be 20", 50.0, t.driftPercent(59_000L)!!, 1.0)
    }

    @Test
    fun `reset clears both windows`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 40) t.add(s * 1000L, 20.0)
        t.reset()
        assertNull(t.driftPercent(39_000L))
    }

    @Test
    fun `real ride breathing rates produce a plausible drift`() {
        val f = RideFixture.load("ride_2026-03-29.csv")
        val t = DriftTracker()
        var s = 0L
        var lastMs = 0L
        for (v in f.br) { if (v != null && v > 0) { t.add(s * 1000L, v); lastMs = s * 1000L }; s++ }
        val d = t.driftPercent(lastMs)
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

        val drift = t.driftPercent(36000L)
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
    fun `backwards timestamp within tolerance is rejected as jitter`() {
        // When system clock has transient jitter (BLE reordering, etc.), a sample with
        // a slightly older timestamp must be rejected as jitter, not added to window.
        // This proves the tolerance mechanism works: small jumps rejected, large ones reset.
        val t = DriftTracker(referenceSeconds = 2, currentSeconds = 5, warmupSeconds = 0)

        // Build and seal reference with samples at t=0, t=4s, mean = 20
        t.add(0L, 20.0)
        t.add(4000L, 20.0)

        // Add current samples: t=8s, t=12s, t=16s, t=20s (all value 20)
        t.add(8000L, 20.0)
        t.add(12000L, 20.0)
        t.add(16000L, 20.0)
        t.add(20000L, 20.0)

        // At t=20s, drift should be 0 (all value 20)
        var drift = t.driftPercent(20000L)
        assertEquals("drift should be 0 with homogeneous current window", 0.0, drift!!, 0.001)

        // Simulate transient backwards time jitter: sample at t=19.5s (500ms backward)
        // This is well within CLOCK_JITTER_TOLERANCE (2000ms), so rejected, not reset
        t.add(19500L, 50.0)

        // Drift should still be 0 because the jittered sample was rejected
        drift = t.driftPercent(20000L)
        assertEquals(
            "backwards timestamp within tolerance should be rejected as jitter",
            0.0, drift!!, 0.001
        )
    }

    @Test
    fun `sustained clock correction after reporting triggers reset and recovery`() {
        // Build reference and current window reporting normally
        val t = DriftTracker(referenceSeconds = 2, currentSeconds = 5, warmupSeconds = 0)

        // Build reference at t=0,4s with value 20
        t.add(0L, 20.0)
        t.add(4000L, 20.0)

        // Current phase reporting normally at t=8s, 12s, 16s, 20s with value 25
        t.add(8000L, 25.0)
        t.add(12000L, 25.0)
        t.add(16000L, 25.0)
        t.add(20000L, 25.0)

        // Verify we're reporting drift
        var drift = t.driftPercent(20000L)
        assertTrue("tracker should report drift before clock jump", drift != null && drift > 0)

        // Sustained clock correction: time jumps back by 10 seconds (well above 2s tolerance)
        // This simulates device clock corrected downward after sync (e.g., was far in future)
        // Feed samples at the corrected time: now t=10s (was 20s, jumped back 10s)
        // Continue with new samples at 14s, 18s, 22s (all at corrected time, far in past)
        t.add(10000L, 30.0)  // Jump back triggers reset; this sample starts fresh
        t.add(14000L, 30.0)
        t.add(18000L, 30.0)
        t.add(22000L, 30.0)

        // Tracker should have recovered and rebuilt reference, now reporting new drift
        drift = t.driftPercent(22000L)
        assertTrue(
            "tracker should recover from sustained clock correction and report again (not null forever)",
            drift != null
        )
        // New reference built from samples after reset; new current window reports drift from new reference
        // Reference is now ~30, current is 30, so drift should be ~0%
        assertEquals("after recovery, drift should stabilize at new reference level", 0.0, drift!!, 5.0)
    }

    @Test
    fun `clock reset during reference building triggers recovery`() {
        // Test that a large backwards jump during reference building triggers reset,
        // allowing the reference to be rebuilt from the corrected time.
        val t = DriftTracker(referenceSeconds = 2, currentSeconds = 5, warmupSeconds = 0)

        // Build and seal reference: samples at t=0, t=4s (seals at elapsedS >= 1)
        t.add(0L, 20.0)
        t.add(4000L, 20.0)  // elapsedS=4, reference seals (4 >= 1)

        // Add current samples
        t.add(8000L, 20.0)
        t.add(12000L, 20.0)

        // Verify tracker is reporting
        var drift = t.driftPercent(12000L)
        assertTrue("tracker should be reporting initially", drift != null)

        // Sustained clock correction: sample arrives at t=1000 (after already seeing t=12000)
        // Difference is 11000ms > 2000ms tolerance, triggers reset
        t.add(1000L, 20.0)

        // After reset, startMs = 1000, newestTimestampSeen = Long.MIN_VALUE
        // This sample is now at elapsedS = 0, which is < warmupSeconds, so dropped

        // Next sample at t=5000: elapsedS = 4 >= 1, reference seals again
        t.add(5000L, 20.0)

        // Current sample at t=9000
        t.add(9000L, 30.0)

        // Tracker should have recovered and be reporting new drift
        drift = t.driftPercent(9000L)
        assertTrue("tracker should recover and report after clock reset", drift != null)
        // New reference is 20, new current is 30, so drift = 50%
        assertEquals("drift should reflect new data after recovery", 50.0, drift!!, 1.0)
    }

    @Test
    fun `a stalled tracker reports null instead of the last average`() {
        // A strap dropout stops feeding add(), but the caller keeps calling driftPercent()
        // on its own clock. Once the newest sample we ever saw is older than the current
        // window span, the window no longer describes "recent" — report absence, not a
        // frozen figure from before the dropout.
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)          // reference: 20
        for (s in 30 until 40) t.add(s * 1000L, 23.0)         // current: 23, newest at 39_000L

        // Immediately after: still fresh, reports normally.
        assertEquals(15.0, t.driftPercent(39_000L)!!, 0.5)

        // Caller keeps polling on its own clock while the sensor is dead. Once "now" is
        // more than the 5s window past the newest sample we ever saw, it's stale.
        assertNull(t.driftPercent(39_000L + 5_001L))
        assertNull(t.driftPercent(39_000L + 60_000L))
    }
}
