package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPipelineTest {

    private fun baselines(excluding: String? = null): Pair<VeBaseline, VeBaseline> {
        val p = VeBaseline(); val h = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
        for (n in VeBaselineTest.INDOOR) if (n != excluding) {
            val f = RideFixture.load(n); val g = LoadGate()
            for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { p.update(it.loadW, it.ve); h.update(it.hrBpm, it.ve) }
        }
        return p to h
    }

    private class Replay(val outputs: List<PipelineOutput>, val zonesConfigured: List<Int>, val zonesCorrected: List<Int>)

    private fun replay(name: String, excluding: String? = name): Replay {
        val (p, h) = baselines(excluding)
        val pipe = SessionPipeline(p, h, rideCount = 5, minBaselineBins = 3, minBaselineRides = 2)
        val f = RideFixture.load(name)
        val configured = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val outs = ArrayList<PipelineOutput>(); val zc = ArrayList<Int>(); val zs = ArrayList<Int>()
        for (i in f.watts.indices) {
            val o = pipe.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L, recordingSeconds = i)
            outs.add(o)
            val ve = f.ve[i] ?: continue
            zc.add(ZoneClassifier.zoneFor(ve, configured))
            zs.add(ZoneClassifier.zoneFor(ve, ZoneClassifier.effectiveThresholds(configured, o.scale)))
        }
        return Replay(outs, zc, zs)
    }

    @Test
    fun `stays calibrating with a thin baseline`() {
        val pipe = SessionPipeline(VeBaseline(), VeBaseline(binWidth = 5.0), rideCount = 0, minBaselineBins = 3, minBaselineRides = 2)
        val f = RideFixture.load("ride_2026-02-24.csv")
        var last: PipelineOutput? = null
        for (i in f.watts.indices) last = pipe.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L, i)
        assertNull(last!!.scale); assertNull(last.dayQualityPercent); assertTrue(last.scaleStatus is ScaleStatus.Calibrating)
        assertTrue("samples must still be buffered for the baseline", pipe.rideSamples.size > 2000)
    }

    @Test
    fun `easy outdoor ride locks a low strap scale inside the window and moves time into zone two`() {
        val r = replay("outdoor_easy_2026-09-02.csv", excluding = null)
        val firstLock = r.outputs.indexOfFirst { it.scaleStatus is ScaleStatus.Locked }
        // Reference replay (Python, same rules): first lock at 1622 s with 0.834; the window closes at
        // 2400 s so the held scale is 0.837 even though the whole-ride devHR ends at -22.5%.
        assertTrue("must lock inside 40 min (measured 27 min), got $firstLock s", firstLock in 1..2400)
        val finalScale = r.outputs.last().scale!!
        assertEquals(0.837, finalScale, 0.04)
        val z2Configured = r.zonesConfigured.count { it >= 2 }.toDouble() / r.zonesConfigured.size
        val z2Corrected = r.zonesCorrected.count { it >= 2 }.toDouble() / r.zonesCorrected.size
        assertTrue("configured thresholds: <0.5% above Z1, got $z2Configured", z2Configured < 0.005)
        assertTrue("corrected thresholds: >=1% above Z1 (measured 1.4%), got $z2Corrected", z2Corrected >= 0.01)
    }

    @Test
    fun `long outdoor ride locks a high strap scale and pulls time out of the upper zones`() {
        val r = replay("outdoor_long_2026-04-25.csv", excluding = null)
        // Reference replay: first lock at 471 s (1.26), held scale 1.25; Z2+ time 57.0% -> 27.0%.
        assertEquals(1.25, r.outputs.last().scale!!, 0.05)
        val upConfigured = r.zonesConfigured.count { it >= 2 }.toDouble() / r.zonesConfigured.size
        val upCorrected = r.zonesCorrected.count { it >= 2 }.toDouble() / r.zonesCorrected.size
        assertEquals(0.57, upConfigured, 0.03)
        assertEquals(0.27, upCorrected, 0.05)
    }

    @Test
    fun `a good indoor day shows negative day quality independent of the strap scale`() {
        // 2026-02-24 reference replay: dayQuality at ride end -12.4; scale locked in the first 40 min
        // and held at 1.197 (the whole-ride devHR of +8.8% is lower because HR drifted later on).
        val r = replay("ride_2026-02-24.csv")
        val last = r.outputs.last()
        assertEquals(-12.4, last.dayQualityPercent!!, 3.0)
        assertEquals(1.197, last.scale!!, 0.05)
    }

    @Test
    fun `reset clears deviations scale and buffered samples`() {
        val (p, h) = baselines()
        val pipe = SessionPipeline(p, h, 5, 3, 2)
        val f = RideFixture.load("ride_2026-03-03.csv")
        for (i in f.watts.indices) pipe.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L, i)
        assertTrue(pipe.rideSamples.isNotEmpty())
        pipe.reset()
        assertTrue(pipe.rideSamples.isEmpty())
        assertNull(pipe.onSample(200.0, 140.0, 60.0, 0L, 0).scale)
    }
}
