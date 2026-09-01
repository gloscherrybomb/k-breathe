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
        repeat(29) { b.update(LoadVeSample(200.0, 60.0)) }
        assertNull("29 samples is below the minimum", b.expectedVe(200.0))
        b.update(LoadVeSample(200.0, 60.0))
        assertNotNull("30 samples reaches the minimum", b.expectedVe(200.0))
    }

    @Test
    fun `expected VE is the mean of the bin`() {
        val b = VeBaseline(minSamplesPerBin = 2)
        b.update(LoadVeSample(200.0, 50.0))
        b.update(LoadVeSample(200.0, 70.0))
        assertEquals(60.0, b.expectedVe(203.0)!!, 0.001)
    }

    @Test
    fun `unknown loads return null rather than extrapolating`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(LoadVeSample(200.0, 60.0))
        assertNull(b.expectedVe(400.0))
    }

    @Test
    fun `built from three real rides it covers the expected bins`() {
        // Golden values from the reference prototype.
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            steady(RideFixture.load(n)).forEach { b.update(it) }
        }
        assertEquals("usable bins", 6, b.coveredBins())
        // Bin means, +/- 1.0 L/min.
        assertEquals(45.43, b.expectedVe(100.0)!!, 1.0)
        assertEquals(48.48, b.expectedVe(120.0)!!, 1.0)
        assertEquals(56.34, b.expectedVe(140.0)!!, 1.0)
        assertEquals(67.30, b.expectedVe(160.0)!!, 1.0)
        assertEquals(68.50, b.expectedVe(180.0)!!, 1.0)
        assertEquals(72.29, b.expectedVe(200.0)!!, 1.0)
        // 220W had only 14 samples in the reference run, below the minimum.
        assertNull("220W bin must remain unusable", b.expectedVe(220.0))
    }

    @Test
    fun `survives a serialisation round trip`() {
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            steady(RideFixture.load(n)).forEach { b.update(it) }
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
        repeat(250) { b.update(LoadVeSample(200.0, 50.0)) }
        assertEquals(100, countOf(b, 200.0))
    }

    @Test
    fun `a saturated bin tracks new values instead of averaging them in`() {
        val cap = 100
        val b = VeBaseline(minSamplesPerBin = 1, maxSamplesPerBin = cap)
        repeat(cap) { b.update(LoadVeSample(200.0, 50.0)) }
        repeat(cap) { b.update(LoadVeSample(200.0, 70.0)) }
        // An uncapped running mean would land exactly on 60.0; the EMA behaviour lands
        // measurably above it (~62.6 for cap=100) — the discriminating assertion.
        val ve = b.expectedVe(200.0)!!
        assertTrue("expected EMA pull above 62.0, was $ve", ve > 62.0)
    }
}
