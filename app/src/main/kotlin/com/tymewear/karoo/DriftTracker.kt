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
 *
 * The current window is time-scoped, not sample-scoped: we cannot assume samples arrive
 * at a fixed cadence (they may be 1 Hz, or every 2–4 seconds from a breath-rate stream,
 * or irregular if power delivery stalls). A window defined by sample count would silently
 * stretch to minutes if the data source slowed. Time-scoping ensures predictable latency.
 */
class DriftTracker(
    private val referenceSeconds: Int = DEFAULT_REFERENCE_SECONDS,
    private val currentSeconds: Int = DEFAULT_CURRENT_SECONDS,
    private val warmupSeconds: Int = DEFAULT_WARMUP_SECONDS,
) {
    private var startMs: Long? = null
    private val referenceValues = ArrayList<Double>()
    private var referenceMean: Double? = null
    private val current = ArrayDeque<Pair<Long, Double>>()

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

        current.addLast(nowMs to value)
    }

    /** Percent change of the recent time-scoped window against the held reference. */
    fun driftPercent(): Double? {
        val ref = referenceMean ?: return null
        if (ref <= 0.0 || current.isEmpty()) return null

        // Evict entries older than currentSeconds * 1000L milliseconds relative to newest
        val newest = current.last().first
        val windowStartMs = newest - (currentSeconds * 1000L)
        while (current.isNotEmpty() && current.first().first < windowStartMs) {
            current.removeFirst()
        }

        if (current.isEmpty()) return null
        val avgValue = current.map { it.second }.average()
        return (avgValue / ref - 1.0) * 100.0
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
