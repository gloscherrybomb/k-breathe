package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdShiftTest {

    /** Baseline where VE rises 10 L/min per 20W step: 140W->50, 160W->60, 180W->70. */
    private fun linearBaseline(): VeBaseline {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(LoadVeSample(140.0, 0.0, 50.0))
        b.update(LoadVeSample(160.0, 0.0, 60.0))
        b.update(LoadVeSample(180.0, 0.0, 70.0))
        return b
    }

    @Test
    fun `finds the load where baseline ventilation meets the threshold`() {
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 60.0, deviationFraction = 0.0,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertEquals(160.0, p!!, 0.5)
    }

    @Test
    fun `interpolates between bins`() {
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 55.0, deviationFraction = 0.0,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertEquals("VE 55 sits midway between the 140W and 160W bins", 150.0, p!!, 1.0)
    }

    @Test
    fun `a more efficient day pushes the threshold to a higher power`() {
        // -10%: today's VE at any load is 10% below baseline, so the threshold VE is
        // reached at a higher load than baseline implies.
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 60.0, deviationFraction = -0.10,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertTrue("expected above 160W, got $p", p!! > 160.0)
    }

    @Test
    fun `a worse day pulls the threshold to a lower power`() {
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 60.0, deviationFraction = 0.10,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertTrue("expected below 160W, got $p", p!! < 160.0)
    }

    @Test
    fun `refuses to extrapolate above the covered range`() {
        assertNull(
            ThresholdShift.thresholdPowerW(
                baseline = linearBaseline(), thresholdVe = 200.0, deviationFraction = 0.0,
                minLoadW = 140.0, maxLoadW = 180.0,
            ),
        )
    }

    @Test
    fun `refuses to extrapolate below the covered range`() {
        assertNull(
            ThresholdShift.thresholdPowerW(
                baseline = linearBaseline(), thresholdVe = 10.0, deviationFraction = 0.0,
                minLoadW = 140.0, maxLoadW = 180.0,
            ),
        )
    }

    @Test
    fun `returns null when the baseline has no coverage`() {
        assertNull(
            ThresholdShift.thresholdPowerW(
                baseline = VeBaseline(), thresholdVe = 60.0, deviationFraction = 0.0,
            ),
        )
    }
}
