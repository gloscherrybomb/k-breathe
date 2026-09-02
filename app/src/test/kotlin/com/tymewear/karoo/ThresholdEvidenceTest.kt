package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdEvidenceTest {
    private fun curve(slopes: Triple<Double, Double, Double>, b1: Double, b2: Double, count: Int = 300): List<BinStat> =
        (80..320 step 20).map { p ->
            val x = p.toDouble()
            val ve = 30.0 + slopes.first * (x - 80) + slopes.second * maxOf(0.0, x - b1) + slopes.third * maxOf(0.0, x - b2)
            BinStat(x, ve, count)
        }

    @Test
    fun `recovers two breakpoints from a three-segment curve`() {
        val bp = ThresholdEvidence.estimate(curve(Triple(0.15, 0.25, 0.6), b1 = 160.0, b2 = 240.0))
        assertEquals(160.0, bp.lowerLoadW!!, 10.0); assertEquals(240.0, bp.upperLoadW!!, 10.0)
        assertEquals(42.0, bp.lowerVe!!, 3.0)      // 30 + 0.15*80
        assertEquals(74.0, bp.upperVe!!, 3.0)      // 42 + 0.40*80
    }

    @Test
    fun `a straight line has no breakpoints`() {
        val bp = ThresholdEvidence.estimate(curve(Triple(0.3, 0.0, 0.0), 160.0, 240.0))
        assertNull(bp.lowerLoadW); assertNull(bp.upperLoadW)
    }

    @Test
    fun `a break where the slope decreases is rejected`() {
        val bp = ThresholdEvidence.estimate(curve(Triple(0.6, -0.4, 0.0), 180.0, 300.0))
        assertNull(bp.lowerLoadW)
    }

    @Test
    fun `thin bins are ignored and too few bins yield nothing`() {
        val bins = curve(Triple(0.15, 0.25, 0.6), 160.0, 240.0).map { if (it.centre > 160.0) it.copy(count = 10) else it }
        assertNull(ThresholdEvidence.estimate(bins).lowerLoadW)
    }

    @Test
    fun `pooled indoor fixtures do not yield a confident breakpoint`() {
        // Six rides, thinly sampled above 220 W, with a *flattening* (not steepening) bend at
        // 160 W driven by the 8600-sample 180 W bin. The honest answer is "no evidence yet":
        // the only positive-hinge candidate (200 W) removes ~3 % of weighted SSE against the
        // 25 % gate. Guards against loosening the rule until a wrong threshold gets suggested.
        val b = VeBaseline()
        for (n in VeBaselineTest.INDOOR) { val f = RideFixture.load(n); val g = LoadGate()
            for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { b.update(it.loadW, it.ve) } }
        val bp = ThresholdEvidence.estimate(b.bins(), minCount = 30)
        assertNull(bp.lowerLoadW); assertNull(bp.upperLoadW)
        assertTrue(ThresholdEvidence.suggestions(List(3) { bp }, ZoneThresholds(73.0, 96.0, 112.0, 130.0)).isEmpty())
    }

    @Test
    fun `suggests VT1 when three rides agree and differ from configured by over eight percent`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val hist = listOf(Breakpoints(200.0, 65.0, null, null), Breakpoints(200.0, 67.0, null, null), Breakpoints(220.0, 66.0, null, null))
        val s = ThresholdEvidence.suggestions(hist, cfg)
        assertEquals(listOf(Suggestion(ThresholdKind.VT1, 73.0, 66.0)), s)
    }

    @Test
    fun `no suggestion when rides disagree or the difference is small`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        assertTrue(ThresholdEvidence.suggestions(listOf(Breakpoints(200.0, 60.0, null, null), Breakpoints(200.0, 70.0, null, null), Breakpoints(200.0, 66.0, null, null)), cfg).isEmpty())
        assertTrue(ThresholdEvidence.suggestions(listOf(Breakpoints(200.0, 70.0, null, null), Breakpoints(200.0, 71.0, null, null), Breakpoints(200.0, 70.0, null, null)), cfg).isEmpty())
        assertTrue(ThresholdEvidence.suggestions(listOf(Breakpoints(200.0, 60.0, null, null)), cfg).isEmpty())
    }

    @Test
    fun `a lone break above the midpoint is treated as VT2`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val hist = List(3) { Breakpoints(230.0, 88.0, null, null) }
        assertEquals(listOf(Suggestion(ThresholdKind.VT2, 96.0, 88.0)), ThresholdEvidence.suggestions(hist, cfg))
    }

    @Test
    fun `breakpoints survive a serialisation round trip`() {
        val bp = Breakpoints(200.0, 66.5, 240.0, 91.0)
        assertEquals(bp, Breakpoints.deserialise(bp.serialise()))
        assertEquals(Breakpoints(null, null, null, null), Breakpoints.deserialise(Breakpoints(null, null, null, null).serialise()))
        assertNull(Breakpoints.deserialise("junk"))
    }

    @Test
    fun `a dismissed suggestion stays hidden until the estimate moves by three litres`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val s = listOf(Suggestion(ThresholdKind.VT1, 73.0, 66.0))
        assertTrue(ThresholdEvidence.filterSuggestions(s, cfg, dismissedVt1 = 66.0, dismissedVt2 = null).isEmpty())
        assertTrue(ThresholdEvidence.filterSuggestions(s, cfg, dismissedVt1 = 68.0, dismissedVt2 = null).isEmpty())   // moved 2
        assertEquals(s, ThresholdEvidence.filterSuggestions(s, cfg, dismissedVt1 = 69.0, dismissedVt2 = null))      // moved 3
        assertEquals(s, ThresholdEvidence.filterSuggestions(s, cfg, dismissedVt1 = null, dismissedVt2 = 66.0))      // other kind
    }

    @Test
    fun `suggestions that would put the thresholds out of order are dropped`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val vt2TooHigh = Suggestion(ThresholdKind.VT2, 96.0, 115.0)      // above TopZ4
        val vt1TooHigh = Suggestion(ThresholdKind.VT1, 73.0, 97.0)       // above VT2
        val vt2TooLow = Suggestion(ThresholdKind.VT2, 96.0, 70.0)        // below VT1
        val fine = Suggestion(ThresholdKind.VT2, 96.0, 105.0)
        assertEquals(listOf(fine), ThresholdEvidence.filterSuggestions(listOf(vt2TooHigh, vt1TooHigh, vt2TooLow, fine), cfg, null, null))
    }
}
