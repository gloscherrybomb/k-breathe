package com.tymewear.karoo

/**
 * Drift of a breathing signal against its own early-effort reference.
 *
 * Experienced users pace long efforts this way: when breathing rate climbs more than
 * ~11-15% above where it started for the same work, the effort is no longer sustainable.
 * They currently compute it by hand from lap averages; nothing surfaces it live.
 *
 * The reference is captured once from the early steady period and then held, so a hard
 * finish cannot quietly redefine "normal". Pure; the clock is supplied by the caller.
 */
class DriftTracker(
    private val referenceSeconds: Int = DEFAULT_REFERENCE_SECONDS,
    private val currentSeconds: Int = DEFAULT_CURRENT_SECONDS,
    private val warmupSeconds: Int = DEFAULT_WARMUP_SECONDS,
) {
    private var startMs: Long? = null
    private val referenceValues = ArrayList<Double>()
    private var referenceMean: Double? = null
    private val current = ArrayDeque<Double>()

    fun add(nowMs: Long, value: Double) {
        val start = startMs ?: nowMs.also { startMs = it }
        val elapsedS = (nowMs - start) / 1000

        if (elapsedS < warmupSeconds) return

        if (referenceMean == null) {
            referenceValues.add(value)
            if (elapsedS >= warmupSeconds + referenceSeconds - 1) {
                referenceMean = referenceValues.average()
            }
            return
        }

        current.addLast(value)
        while (current.size > currentSeconds) current.removeFirst()
    }

    /** Percent change of the recent window against the held reference. */
    fun driftPercent(): Double? {
        val ref = referenceMean ?: return null
        if (ref <= 0.0 || current.isEmpty()) return null
        return (current.average() / ref - 1.0) * 100.0
    }

    fun reset() {
        startMs = null
        referenceValues.clear()
        referenceMean = null
        current.clear()
    }

    companion object {
        const val DEFAULT_REFERENCE_SECONDS = 300
        const val DEFAULT_CURRENT_SECONDS = 120
        const val DEFAULT_WARMUP_SECONDS = 60
    }
}
