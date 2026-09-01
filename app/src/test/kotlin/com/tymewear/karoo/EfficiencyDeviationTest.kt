package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EfficiencyDeviationTest {

    private fun steady(name: String): List<LoadVeSample> {
        val f = RideFixture.load(name)
        val d = SteadyStateDetector()
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) d.onSample(f.watts[i], f.ve[i])?.let { out.add(it) }
        return out
    }

    private fun baselineFromFebruary(): VeBaseline {
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            steady(n).forEach { b.update(it) }
        }
        return b
    }

    private fun score(baseline: VeBaseline, name: String): Deviation? {
        val e = EfficiencyDeviation(baseline)
        steady(name).forEach { e.add(it) }
        return e.deviation()
    }

    @Test
    fun `reports nothing before enough bins are matched`() {
        val e = EfficiencyDeviation(baselineFromFebruary())
        repeat(100) { e.add(LoadVeSample(160.0, 60.0)) }   // one bin only
        assertNull("one matched bin is not enough", e.deviation())
    }

    @Test
    fun `identical ventilation to baseline reads as zero`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(LoadVeSample(140.0, 50.0))
        b.update(LoadVeSample(160.0, 60.0))
        b.update(LoadVeSample(180.0, 70.0))
        val e = EfficiencyDeviation(b, minSamplesPerBin = 1, minMatchedBins = 3)
        e.add(LoadVeSample(140.0, 50.0))
        e.add(LoadVeSample(160.0, 60.0))
        e.add(LoadVeSample(180.0, 70.0))
        assertEquals(0.0, e.deviation()!!.percent, 0.001)
    }

    @Test
    fun `higher ventilation at the same load reads positive`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        listOf(140.0 to 50.0, 160.0 to 60.0, 180.0 to 70.0).forEach { b.update(LoadVeSample(it.first, it.second)) }
        val e = EfficiencyDeviation(b, minSamplesPerBin = 1, minMatchedBins = 3)
        listOf(140.0 to 55.0, 160.0 to 66.0, 180.0 to 77.0).forEach { e.add(LoadVeSample(it.first, it.second)) }
        assertEquals("10% more ventilation for the same work", 10.0, e.deviation()!!.percent, 0.01)
    }

    @Test
    fun `scores the rider's real rides against their February baseline`() {
        // Golden values from the reference prototype, +/- 1.5 percentage points.
        val b = baselineFromFebruary()
        val expected = mapOf(
            "ride_2026-02-13.csv" to 2.83,
            "ride_2026-02-18.csv" to 4.60,
            "ride_2026-02-24.csv" to -9.36,
            "ride_2026-03-03.csv" to -10.82,
            "ride_2026-03-16.csv" to -6.81,
            "ride_2026-03-29.csv" to -6.02,
        )
        for ((name, want) in expected) {
            val d = score(b, name) ?: error("$name produced no deviation")
            assertEquals("$name deviation", want, d.percent, 1.5)
            assertTrue("$name should match >=3 bins", d.matchedBins >= 3)
        }
    }

    @Test
    fun `a corrupt ride is detectable by its quality ratio`() {
        // long_outdoor has a frozen VE stream (quality ratio 0.003) and would otherwise
        // produce a confident, meaningless deviation. EfficiencyDeviation deliberately
        // does not know about data quality — live use is gated by freshness, and
        // historical analysis must gate on this ratio. This test pins the detector so a
        // caller can rely on it, and guards the fixture from being replaced by a clean one.
        val corrupt = RideFixture.load("long_outdoor_2026-06-19.csv")
        assertTrue("corrupt fixture should score far below the gate", corrupt.qualityRatio() < 0.20)
        val good = RideFixture.load("ride_2026-03-29.csv")
        assertTrue("good fixture should score well above the gate", good.qualityRatio() > 0.40)
    }

    @Test
    fun `a ride with no power produces no deviation`() {
        assertNull(score(baselineFromFebruary(), "outdoor_endurance_2026-08-09.csv"))
    }

    @Test
    fun `reset clears accumulated samples`() {
        val b = baselineFromFebruary()
        val e = EfficiencyDeviation(b)
        steady("ride_2026-03-03.csv").forEach { e.add(it) }
        assertTrue(e.deviation() != null)
        e.reset()
        assertNull(e.deviation())
    }
}
