package com.tymewear.karoo

/**
 * The rider's ventilation thresholds in L/min, under Tymewear's own names (the bike
 * Fitness Profile): the four zone edges Endurance, VT1, VT2 and Top Z4, plus VO2max.
 *
 * Zones are Z1 below Endurance, Z2 Endurance–VT1, Z3 VT1–VT2, Z4 VT2–Top Z4 and Z5 from
 * Top Z4 up. VO2max is the top of Z5, not an edge, so it plays no part in classification.
 */
data class ZoneThresholds(
    val endurance: Double,
    val vt1: Double,
    val vt2: Double,
    val topZ4: Double,
    val vo2max: Double,
)

/** Tymewear's names for the five thresholds, in Fitness Profile order. [label] is what
 *  the rider sees. */
enum class ThresholdKind(val label: String) {
    ENDURANCE("Endurance"),
    VT1("VT1"),
    VT2("VT2"),
    TOP_Z4("Top Z4"),
    VO2MAX("VO2max"),
}

/** The same thresholds expressed in a strap scale [factor] times the configured one. */
fun ZoneThresholds.scaled(factor: Double): ZoneThresholds =
    ZoneThresholds(endurance * factor, vt1 * factor, vt2 * factor, topZ4 * factor, vo2max * factor)

/**
 * The single place a ventilation value becomes a zone.
 *
 * Previously four call sites classified independently from differently-smoothed VE, so a
 * field could display one number and colour itself from another, and the zone written to
 * the FIT file (derived from raw, unsmoothed VE) could differ from what the rider saw.
 * Every caller now passes the value it is actually presenting.
 */
object ZoneClassifier {
    fun zoneFor(ve: Double, thresholds: ZoneThresholds): Int =
        Protocol.veZone(ve, thresholds.endurance, thresholds.vt1, thresholds.vt2, thresholds.topZ4)

    /** Thresholds to classify today's raw VE against: configured × today's strap scale
     *  when one is known, configured otherwise. Dividing VE by the scale would give the
     *  same zone; scaling the thresholds leaves displayed and recorded VE untouched. */
    fun effectiveThresholds(configured: ZoneThresholds, scale: Double?): ZoneThresholds =
        if (scale == null) configured else configured.scaled(scale)
}
