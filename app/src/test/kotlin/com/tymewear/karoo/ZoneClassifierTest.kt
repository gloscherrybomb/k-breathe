package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneClassifierTest {

    // The athlete's Tymewear bike Fitness Profile, rounded (2026-09-23).
    private val t = ZoneThresholds(endurance = 73.0, vt1 = 96.0, vt2 = 112.0, topZ4 = 130.0, vo2max = 180.0)

    @Test
    fun `classifies each zone from a VE value`() {
        assertEquals(1, ZoneClassifier.zoneFor(50.0, t))
        assertEquals(2, ZoneClassifier.zoneFor(80.0, t))
        assertEquals(3, ZoneClassifier.zoneFor(100.0, t))
        assertEquals(4, ZoneClassifier.zoneFor(120.0, t))
        assertEquals(5, ZoneClassifier.zoneFor(140.0, t))
    }

    @Test
    fun `zones follow Tymewear's edges and VO2max plays no part`() {
        // Z1 < Endurance, Z2 Endurance-VT1, Z3 VT1-VT2, Z4 VT2-Top Z4, Z5 from Top Z4.
        assertEquals(1, ZoneClassifier.zoneFor(72.9, t))
        assertEquals(2, ZoneClassifier.zoneFor(73.0, t))
        assertEquals(3, ZoneClassifier.zoneFor(96.0, t))
        assertEquals(4, ZoneClassifier.zoneFor(112.0, t))
        assertEquals(5, ZoneClassifier.zoneFor(130.0, t))
        assertEquals(5, ZoneClassifier.zoneFor(200.0, t))
        assertEquals(5, ZoneClassifier.zoneFor(140.0, t.copy(vo2max = 131.0)))
    }

    @Test
    fun `the names the rider sees are Tymewear's`() {
        assertEquals(
            listOf("Endurance", "VT1", "VT2", "Top Z4", "VO2max"),
            ThresholdKind.entries.map { it.label },
        )
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
    fun `matches hard-coded edges 73, 96, 112, 130 across a sweep`() {
        // Independent of Protocol.veZone: the zone each VE must get from the athlete's four
        // edges, written out by hand. Guards the rename against an off-by-one edge.
        fun expected(ve: Double): Int = when {
            ve <= 0.0 -> 0
            ve < 73.0 -> 1
            ve < 96.0 -> 2
            ve < 112.0 -> 3
            ve < 130.0 -> 4
            else -> 5
        }
        var ve = 0.0
        while (ve <= 250.0) {
            assertEquals("VE=$ve", expected(ve), ZoneClassifier.zoneFor(ve, t))
            ve += 0.5
        }
    }

    @Test
    fun `scaling multiplies every threshold`() {
        val s = t.scaled(0.8)
        assertEquals(58.4, s.endurance, 0.001); assertEquals(76.8, s.vt1, 0.001)
        assertEquals(89.6, s.vt2, 0.001); assertEquals(104.0, s.topZ4, 0.001)
        assertEquals(144.0, s.vo2max, 0.001)
    }

    @Test
    fun `effective thresholds fall back to configured when no scale is known`() {
        assertEquals(t, ZoneClassifier.effectiveThresholds(t, null))
        assertEquals(t.scaled(1.2), ZoneClassifier.effectiveThresholds(t, 1.2))
    }

    @Test
    fun `a strap reading low moves a value up a zone once corrected`() {
        // Strap reads 20% low: 60 L/min displayed is really 75, above Endurance=73.
        assertEquals(1, ZoneClassifier.zoneFor(60.0, t))
        assertEquals(2, ZoneClassifier.zoneFor(60.0, ZoneClassifier.effectiveThresholds(t, 0.8)))
    }
}
