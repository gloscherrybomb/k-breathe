package com.tymewear.karoo

/** The rider's configured ventilation thresholds, in L/min. */
data class ZoneThresholds(
    val vt1: Double,
    val vt2: Double,
    val topZ4: Double,
    val vo2max: Double,
)

/** The same thresholds expressed in a strap scale [factor] times the configured one. */
fun ZoneThresholds.scaled(factor: Double): ZoneThresholds =
    ZoneThresholds(vt1 * factor, vt2 * factor, topZ4 * factor, vo2max * factor)

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
        Protocol.veZone(ve, thresholds.vt1, thresholds.vt2, thresholds.topZ4, thresholds.vo2max)

    /** Thresholds to classify today's raw VE against: configured × today's strap scale
     *  when one is known, configured otherwise. Dividing VE by the scale would give the
     *  same zone; scaling the thresholds leaves displayed and recorded VE untouched. */
    fun effectiveThresholds(configured: ZoneThresholds, scale: Double?): ZoneThresholds =
        if (scale == null) configured else configured.scaled(scale)
}
