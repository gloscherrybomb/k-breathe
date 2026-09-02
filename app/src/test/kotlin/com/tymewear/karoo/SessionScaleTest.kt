package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScaleTest {
    private fun dev(pct: Double, bins: Int = 3) = Deviation(pct / 100.0, bins)

    @Test
    fun `calibrating until a confident heart-rate deviation arrives`() {
        val s = SessionScale()
        s.offer(null, 100); assertNull(s.displayed(100)); assertTrue(s.status is ScaleStatus.Calibrating)
        s.offer(dev(-20.0, bins = 2), 101); assertNull(s.displayed(101))
    }

    @Test
    fun `locks to one plus the heart-rate deviation`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 600)
        assertEquals(ScaleStatus.Locked(0.8), s.status)
    }

    @Test
    fun `eases from one to the target over thirty seconds`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 600)
        assertEquals(1.0, s.displayed(600)!!, 1e-9)
        assertEquals(0.9, s.displayed(615)!!, 1e-9)
        assertEquals(0.8, s.displayed(630)!!, 1e-9)
        assertEquals(0.8, s.displayed(700)!!, 1e-9)
    }

    @Test
    fun `updates at most once a minute inside the window`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 600); s.offer(dev(-10.0), 630)
        assertEquals(ScaleStatus.Locked(0.8), s.status)
        s.offer(dev(-10.0), 660)
        assertEquals(ScaleStatus.Locked(0.9), s.status)
    }

    @Test
    fun `holds after the forty minute window closes`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 2000); s.offer(dev(10.0), 2401)
        assertEquals(ScaleStatus.Locked(0.8), s.status)
    }

    @Test
    fun `never locks if nothing confident arrives inside the window`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 2401)
        assertTrue(s.status is ScaleStatus.Calibrating); assertNull(s.displayed(2401))
    }

    @Test
    fun `an implausible factor is reported not applied`() {
        val s = SessionScale()
        s.offer(dev(80.0), 600)
        assertEquals(ScaleStatus.OutOfRange(1.8), s.status)
        assertNull(s.displayed(600))
    }

    @Test
    fun `reset returns to calibrating`() {
        val s = SessionScale(); s.offer(dev(-20.0), 600); s.reset()
        assertTrue(s.status is ScaleStatus.Calibrating); assertNull(s.displayed(0))
    }
}
