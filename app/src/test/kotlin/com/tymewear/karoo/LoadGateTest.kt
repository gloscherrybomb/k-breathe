package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGateTest {

    private fun feed(f: RideFixture, g: LoadGate = LoadGate()): List<LoadVeSample> {
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { out.add(it) }
        return out
    }

    @Test
    fun `emits nothing during the first two minutes`() {
        val g = LoadGate()
        repeat(119) { i -> assertNull(g.onSample(200.0, 140.0, 60.0, i * 1000L)) }
    }

    @Test
    fun `emits once load has settled`() {
        val g = LoadGate()
        var last: LoadVeSample? = null
        repeat(200) { i -> last = g.onSample(200.0, 140.0, 60.0, i * 1000L) ?: last }
        assertNotNull(last)
        assertEquals(200.0, last!!.loadW, 0.5)
        assertEquals(140.0, last!!.hrBpm, 0.01)
        assertEquals(60.0, last!!.ve, 0.01)
    }

    @Test
    fun `rejects while the smoothed load is still changing`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(150.0, 130.0, 50.0, i * 1000L) }
        // Step to 250 W: for a while |L - L30|/L exceeds 10%.
        var rejected = 0
        repeat(40) { i -> if (g.onSample(250.0, 150.0, 80.0, (200 + i) * 1000L) == null) rejected++ }
        assertTrue("a step change must be rejected for tens of seconds, got $rejected rejections", rejected >= 30)
    }

    @Test
    fun `rejects when coasting occurred in the last thirty seconds`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(200.0, 140.0, 60.0, i * 1000L) }
        // Three zero-power seconds = 3 coast flags > 2 allowed.
        repeat(3) { i -> g.onSample(0.0, 140.0, 60.0, (200 + i) * 1000L) }
        assertNull(g.onSample(200.0, 140.0, 60.0, 203_000L))
    }

    @Test
    fun `a null VE clears the VE window`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(200.0, 140.0, 60.0, i * 1000L) }
        g.onSample(200.0, 140.0, null, 200_000L)
        assertNull("window must refill before emitting again", g.onSample(200.0, 140.0, 60.0, 201_000L))
    }

    @Test
    fun `a null heart rate clears the heart-rate window`() {
        // A strap dropout (TymewearData.clearHr() yields 0 -> null) must not leave the
        // gate averaging the last pre-dropout heart rates: a frozen HR would go straight
        // into the scale estimate and the persisted HR baseline.
        val g = LoadGate()
        repeat(200) { i -> g.onSample(200.0, 140.0, 60.0, i * 1000L) }
        assertNotNull(g.onSample(200.0, 140.0, 60.0, 199_000L))
        g.onSample(200.0, null, 60.0, 200_000L)
        // The window has to refill to DEFAULT_MIN_WINDOW_FILL (15) before anything is emitted.
        for (n in 1..14) {
            assertNull(
                "window must refill before emitting again, emitted after $n good ticks",
                g.onSample(200.0, 140.0, 60.0, (200 + n) * 1000L),
            )
        }
        assertNotNull("emission must resume once 15 heart rates are back", g.onSample(200.0, 140.0, 60.0, 215_000L))
    }

    @Test
    fun `requires heart rate`() {
        val g = LoadGate()
        var emitted = 0
        repeat(300) { i -> if (g.onSample(200.0, null, 60.0, i * 1000L) != null) emitted++ }
        assertEquals(0, emitted)
    }

    @Test
    fun `a large clock gap resets the gate`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(200.0, 140.0, 60.0, i * 1000L) }
        assertNotNull(g.onSample(200.0, 140.0, 60.0, 200_000L))
        assertNull(g.onSample(200.0, 140.0, 60.0, 320_000L)) // 2-minute gap
    }

    @Test
    fun `usable fractions on real rides match the reference`() {
        // Reference (Python, same rules): indoor 79-96%, outdoor easy 35.8%, outdoor long 59.2%.
        fun frac(name: String): Double { val f = RideFixture.load(name); return feed(f).size.toDouble() / f.watts.size }
        assertEquals(0.358, frac("outdoor_easy_2026-09-02.csv"), 0.02)
        assertEquals(0.592, frac("outdoor_long_2026-04-25.csv"), 0.02)
        assertEquals(0.959, frac("ride_2026-02-24.csv"), 0.02)
        assertEquals(0.792, frac("ride_2026-03-16.csv"), 0.02)
        assertEquals(0.289, frac("ramp_2026-02-21.csv"), 0.02)
    }
}
