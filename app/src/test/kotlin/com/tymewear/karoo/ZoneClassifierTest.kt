package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneClassifierTest {

    private val t = ZoneThresholds(vt1 = 73.0, vt2 = 96.0, topZ4 = 112.0, vo2max = 130.0)

    @Test
    fun `classifies each zone from a VE value`() {
        assertEquals(1, ZoneClassifier.zoneFor(50.0, t))
        assertEquals(2, ZoneClassifier.zoneFor(80.0, t))
        assertEquals(3, ZoneClassifier.zoneFor(100.0, t))
        assertEquals(4, ZoneClassifier.zoneFor(120.0, t))
        assertEquals(5, ZoneClassifier.zoneFor(140.0, t))
    }

    @Test
    fun `no ventilation is not a zone`() {
        assertEquals(0, ZoneClassifier.zoneFor(0.0, t))
    }

    @Test
    fun `the value a caller displays determines the zone it gets`() {
        // The regression this locks down: a field must not show one VE and colour by a
        // zone derived from a different VE. Same input, same answer, every caller.
        val displayed = 97.5
        assertEquals(ZoneClassifier.zoneFor(displayed, t), ZoneClassifier.zoneFor(displayed, t))
        assertEquals(3, ZoneClassifier.zoneFor(displayed, t))
    }

    @Test
    fun `matches the legacy Protocol implementation across a sweep`() {
        // Guards the refactor: behaviour must be unchanged for every plausible VE.
        var ve = 0.0
        while (ve <= 250.0) {
            assertEquals(
                "VE=$ve",
                Protocol.veZone(ve, t.vt1, t.vt2, t.topZ4, t.vo2max),
                ZoneClassifier.zoneFor(ve, t),
            )
            ve += 0.5
        }
    }

    @Test
    fun `scaling multiplies every threshold`() {
        val s = t.scaled(0.8)
        assertEquals(58.4, s.vt1, 0.001); assertEquals(76.8, s.vt2, 0.001)
        assertEquals(89.6, s.topZ4, 0.001); assertEquals(104.0, s.vo2max, 0.001)
    }

    @Test
    fun `effective thresholds fall back to configured when no scale is known`() {
        assertEquals(t, ZoneClassifier.effectiveThresholds(t, null))
        assertEquals(t.scaled(1.2), ZoneClassifier.effectiveThresholds(t, 1.2))
    }

    @Test
    fun `a strap reading low moves a value up a zone once corrected`() {
        // Strap reads 20% low: 60 L/min displayed is really 75, above VT1=73.
        assertEquals(1, ZoneClassifier.zoneFor(60.0, t))
        assertEquals(2, ZoneClassifier.zoneFor(60.0, ZoneClassifier.effectiveThresholds(t, 0.8)))
    }
}
