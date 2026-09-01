package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteadyStateDetectorTest {

    private fun feed(f: RideFixture, d: SteadyStateDetector = SteadyStateDetector()): List<LoadVeSample> {
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) {
            d.onSample(f.watts[i], f.ve[i])?.let { out.add(it) }
        }
        return out
    }

    @Test
    fun `emits nothing until the window is populated`() {
        val d = SteadyStateDetector()
        repeat(44) { assertNull(d.onSample(200.0, 60.0)) }
    }

    @Test
    fun `emits once power has been stable for the window`() {
        val d = SteadyStateDetector()
        var last: LoadVeSample? = null
        repeat(60) { last = d.onSample(200.0, 60.0) ?: last }
        assertNotNull("steady power should produce a sample", last)
        assertEquals(200.0, last!!.loadW, 0.001)
    }

    @Test
    fun `rejects samples while power is swinging`() {
        val d = SteadyStateDetector()
        // Alternating 100/300W: mean 200, cv ~0.5, far above the 0.12 threshold.
        var emitted = 0
        repeat(200) { i ->
            if (d.onSample(if (i % 2 == 0) 100.0 else 300.0, 60.0) != null) emitted++
        }
        assertEquals(0, emitted)
    }

    @Test
    fun `ignores samples outside the usable power range`() {
        val d = SteadyStateDetector()
        var emitted = 0
        repeat(200) { if (d.onSample(60.0, 30.0) != null) emitted++ }   // below minLoadW
        assertEquals(0, emitted)
    }

    @Test
    fun `tolerates gaps in the power stream`() {
        val d = SteadyStateDetector()
        var emitted = 0
        repeat(200) { i ->
            val w = if (i % 10 == 0) null else 200.0     // 10% dropouts
            if (d.onSample(w, 60.0) != null) emitted++
        }
        assertTrue("occasional nulls must not disable detection", emitted > 0)
    }

    @Test
    fun `real steady rides yield substantial steady coverage`() {
        // Measured with the reference prototype; these are golden values.
        val expected = mapOf(
            "ride_2026-02-13.csv" to 2347,
            "ride_2026-02-18.csv" to 2937,
            "ride_2026-02-24.csv" to 3110,
            "ride_2026-03-03.csv" to 2679,
            "ride_2026-03-16.csv" to 2471,
            "ride_2026-03-29.csv" to 4289,
        )
        for ((name, want) in expected) {
            val got = feed(RideFixture.load(name)).size
            // Allow a small tolerance for smoothing edge effects.
            assertTrue(
                "$name: expected ~$want steady samples, got $got",
                got in (want - 60)..(want + 60),
            )
        }
    }

    @Test
    fun `a ride with no power yields no steady samples`() {
        val f = RideFixture.load("outdoor_endurance_2026-08-09.csv")
        assertEquals(0, feed(f).size)
    }
}
