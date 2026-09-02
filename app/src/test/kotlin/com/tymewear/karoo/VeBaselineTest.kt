package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeBaselineTest {

    private fun steady(f: RideFixture): List<LoadVeSample> {
        val d = SteadyStateDetector()
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) d.onSample(f.watts[i], f.ve[i], i * 1000L)?.let { out.add(it) }
        return out
    }

    @Test
    fun `bins are centred on multiples of the bin width`() {
        val b = VeBaseline()
        assertEquals(200.0, b.binCentre(196.0), 0.001)
        assertEquals(200.0, b.binCentre(205.0), 0.001)
        assertEquals(180.0, b.binCentre(188.0), 0.001)
    }

    @Test
    fun `a bin is unusable until it has enough samples`() {
        val b = VeBaseline(minSamplesPerBin = 30)
        repeat(29) { b.update(200.0, 60.0) }
        assertNull("29 samples is below the minimum", b.expectedVe(200.0))
        b.update(200.0, 60.0)
        assertNotNull("30 samples reaches the minimum", b.expectedVe(200.0))
    }

    @Test
    fun `expected VE is the mean of the bin`() {
        val b = VeBaseline(minSamplesPerBin = 2)
        b.update(200.0, 50.0)
        b.update(200.0, 70.0)
        assertEquals(60.0, b.expectedVe(203.0)!!, 0.001)
    }

    @Test
    fun `unknown loads return null rather than extrapolating`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(200.0, 60.0)
        assertNull(b.expectedVe(400.0))
    }

    @Test
    fun `built from three real rides it covers the expected bins`() {
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            val f = RideFixture.load(n)
            val g = LoadGate()
            for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { b.update(it.loadW, it.ve) }
        }
        assertTrue("usable bins", b.coveredBins() >= 3)
    }

    @Test
    fun `survives a serialisation round trip`() {
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            steady(RideFixture.load(n)).forEach { b.update(it.loadW, it.ve) }
        }
        val restored = VeBaseline.deserialise(b.serialise())
        assertEquals(b.coveredBins(), restored.coveredBins())
        for (p in listOf(100.0, 120.0, 140.0, 160.0, 180.0, 200.0)) {
            assertEquals(b.expectedVe(p)!!, restored.expectedVe(p)!!, 0.0001)
        }
    }

    @Test
    fun `deserialising junk yields an empty baseline rather than throwing`() {
        val b = VeBaseline.deserialise("not-a-baseline")
        assertEquals(0, b.coveredBins())
        assertTrue(b.serialise().isEmpty() || b.coveredBins() == 0)
    }

    private fun countOf(b: VeBaseline, loadW: Double): Int {
        val centre = b.binCentre(loadW)
        val triple = b.serialise().split(";").first { it.startsWith("$centre:") }
        return triple.split(":")[2].toInt()
    }

    @Test
    fun `count stops growing at the cap`() {
        val b = VeBaseline(minSamplesPerBin = 1, maxSamplesPerBin = 100)
        repeat(250) { b.update(200.0, 50.0) }
        assertEquals(100, countOf(b, 200.0))
    }

    @Test
    fun `a saturated bin tracks new values instead of averaging them in`() {
        val cap = 100
        val b = VeBaseline(minSamplesPerBin = 1, maxSamplesPerBin = cap)
        repeat(cap) { b.update(200.0, 50.0) }
        repeat(cap) { b.update(200.0, 70.0) }
        // An uncapped running mean would land exactly on 60.0; the EMA behaviour lands
        // measurably above it (~62.6 for cap=100) — the discriminating assertion.
        val ve = b.expectedVe(200.0)!!
        assertTrue("expected EMA pull above 62.0, was $ve", ve > 62.0)
    }

    @Test
    fun `works as a heart-rate baseline with a five bpm width`() {
        val b = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH, minSamplesPerBin = 1)
        // 138.0 would cross the bin boundary at 137.5 (-> bin 140), so use 136.0 to keep
        // both samples in bin 135 alongside 137.0 (see task-2 self-review deviation note).
        b.update(137.0, 60.0); b.update(136.0, 62.0)
        assertEquals(135.0, b.binCentre(137.0), 0.001)   // 137/5 = 27.4 -> 27 -> 135
        assertEquals(61.0, b.expectedVe(136.0)!!, 0.001)
    }

    @Test
    fun `bins reports centre mean and count in ascending order`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(180.0, 70.0); b.update(140.0, 50.0); b.update(140.0, 54.0)
        val bins = b.bins()
        assertEquals(listOf(140.0, 180.0), bins.map { it.centre })
        assertEquals(52.0, bins[0].meanVe, 0.001)
        assertEquals(2, bins[0].count)
    }

    @Test
    fun `pooled indoor fixtures reproduce the reference power bins`() {
        // Reference (Python, LoadGate rules, all six indoor fixtures): 160W 63.5, 180W 67.3, 200W 68.5, 220W 89.2.
        val b = VeBaseline()
        for (name in INDOOR) { val f = RideFixture.load(name); val g = LoadGate()
            for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { b.update(it.loadW, it.ve) } }
        assertEquals(63.5, b.expectedVe(160.0)!!, 1.0)
        assertEquals(67.3, b.expectedVe(180.0)!!, 1.0)
        assertEquals(89.2, b.expectedVe(220.0)!!, 1.5)
    }

    companion object {
        val INDOOR = listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv",
            "ride_2026-03-03.csv", "ride_2026-03-16.csv", "ride_2026-03-29.csv")
    }
}
