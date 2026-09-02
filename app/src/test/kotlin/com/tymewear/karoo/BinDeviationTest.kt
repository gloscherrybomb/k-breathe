package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinDeviationTest {

    @Test
    fun `reports nothing before enough bins are matched`() {
        val e = BinDeviation(VeBaseline())
        repeat(100) { e.add(160.0, 60.0) }   // one bin only
        assertNull("one matched bin is not enough", e.deviation())
    }

    @Test
    fun `identical ventilation to baseline reads as zero`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(140.0, 50.0)
        b.update(160.0, 60.0)
        b.update(180.0, 70.0)
        val e = BinDeviation(b, minSamplesPerBin = 1, minMatchedBins = 3)
        e.add(140.0, 50.0)
        e.add(160.0, 60.0)
        e.add(180.0, 70.0)
        assertEquals(0.0, e.deviation()!!.percent, 0.001)
    }

    @Test
    fun `higher ventilation at the same load reads positive`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        listOf(140.0 to 50.0, 160.0 to 60.0, 180.0 to 70.0).forEach { b.update(it.first, it.second) }
        val e = BinDeviation(b, minSamplesPerBin = 1, minMatchedBins = 3)
        listOf(140.0 to 55.0, 160.0 to 66.0, 180.0 to 77.0).forEach { e.add(it.first, it.second) }
        assertEquals("10% more ventilation for the same work", 10.0, e.deviation()!!.percent, 0.01)
    }

    private fun gated(name: String): List<LoadVeSample> {
        val f = RideFixture.load(name); val g = LoadGate(); val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { out.add(it) }
        return out
    }
    private fun baselines(excluding: String): Pair<VeBaseline, VeBaseline> {
        val p = VeBaseline(); val h = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
        for (n in VeBaselineTest.INDOOR) if (n != excluding) for (s in gated(n)) { p.update(s.loadW, s.ve); h.update(s.hrBpm, s.ve) }
        return p to h
    }
    private fun score(name: String): Pair<Deviation?, Deviation?> {
        val (p, h) = baselines(excluding = name)
        val dp = BinDeviation(p); val dh = BinDeviation(h, binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
        for (s in gated(name)) { dp.add(s.loadW, s.ve); dh.add(s.hrBpm, s.ve) }
        return dp.deviation() to dh.deviation()
    }

    @Test
    fun `power and heart-rate deviations match the reference on real rides`() {
        // Reference (Python, identical rules), +/- 2 points. Pure-scale rides show devP ~ devHR;
        // the 2026-02-24 good day shows devP well below devHR.
        val expected = mapOf(
            "outdoor_easy_2026-09-02.csv" to (-10.35 to -22.49),
            "outdoor_long_2026-04-25.csv" to (22.16 to 16.39),
            "ride_2026-02-24.csv" to (-3.57 to 8.80),
            "ride_2026-03-03.csv" to (-10.10 to -9.48),
            "ride_2026-03-29.csv" to (-3.57 to -5.76),
        )
        for ((name, want) in expected) {
            val (dp, dh) = score(name)
            assertEquals("$name devP", want.first, dp!!.percent, 2.0)
            assertEquals("$name devHR", want.second, dh!!.percent, 2.0)
            assertTrue(dp.matchedBins >= 3); assertTrue(dh.matchedBins >= 3)
        }
    }

    @Test
    fun `the ramp self-scores near zero`() {
        val b = VeBaseline(); val s = gated("ramp_2026-02-21.csv")
        for (x in s) b.update(x.loadW, x.ve)
        val d = BinDeviation(b); for (x in s) d.add(x.loadW, x.ve)
        assertEquals(0.0, d.deviation()!!.percent, 2.0)   // measured -0.26
    }

    @Test
    fun `a corrupt ride is detectable by its quality ratio`() {
        // long_outdoor has a frozen VE stream (quality ratio 0.003) and would otherwise
        // produce a confident, meaningless deviation. BinDeviation deliberately does not
        // know about data quality — live use is gated by freshness, and historical
        // analysis must gate on this ratio. This test pins the detector so a caller can
        // rely on it, and guards the fixture from being replaced by a clean one.
        val corrupt = RideFixture.load("long_outdoor_2026-06-19.csv")
        assertTrue("corrupt fixture should score far below the gate", corrupt.qualityRatio() < 0.20)
        val good = RideFixture.load("ride_2026-03-29.csv")
        assertTrue("good fixture should score well above the gate", good.qualityRatio() > 0.40)
    }

    @Test
    fun `a ride with no power produces no deviation`() {
        val d = BinDeviation(VeBaseline())
        for (s in gated("outdoor_endurance_2026-08-09.csv")) d.add(s.loadW, s.ve)
        assertNull(d.deviation())
    }

    @Test
    fun `reset clears accumulated samples`() {
        val (p, _) = baselines(excluding = "ride_2026-03-03.csv")
        val e = BinDeviation(p)
        for (s in gated("ride_2026-03-03.csv")) e.add(s.loadW, s.ve)
        assertTrue(e.deviation() != null)
        e.reset()
        assertNull(e.deviation())
    }
}
