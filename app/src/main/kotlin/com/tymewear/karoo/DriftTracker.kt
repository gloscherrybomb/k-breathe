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
 *
 * Timestamps are anchored on the newest timestamp ever seen. Samples with timestamps
 * earlier than that newest are rejected to maintain monotonicity — a clock jump backwards
 * (due to sync, etc.) cannot poison the current window with stale data. Eviction happens
 * in `add()` as well as `driftPercent()` to prevent unbounded accumulation.
 */
class DriftTracker(
    private val referenceSeconds: Int = DEFAULT_REFERENCE_SECONDS,
    private val currentSeconds: Int = DEFAULT_CURRENT_SECONDS,
    private val warmupSeconds: Int = DEFAULT_WARMUP_SECONDS,
) {
    private var startMs: Long? = null
    private val referenceValues = ArrayList<Double>()
    private var referenceMean: Double? = null
    private var newestTimestampSeen: Long = Long.MIN_VALUE
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

        // Reject samples with timestamps earlier than the newest seen (clock went backwards)
        if (nowMs < newestTimestampSeen) return

        newestTimestampSeen = nowMs
        current.addLast(nowMs to value)

        // Evict entries older than the current window (eager eviction prevents unbounded growth)
        val windowStartMs = newestTimestampSeen - (currentSeconds * 1000L)
        while (current.isNotEmpty() && current.first().first < windowStartMs) {
            current.removeFirst()
        }

        // Hard size backstop against pathological input
        while (current.size > MAX_BUFFER_SIZE) {
            current.removeFirst()
        }
    }

    /** Percent change of the recent time-scoped window against the held reference. */
    fun driftPercent(): Double? {
        val ref = referenceMean ?: return null
        if (ref <= 0.0 || current.isEmpty()) return null

        // Defensive eviction: use newest timestamp seen (not last element, which may be stale
        // if backwards time arrived). Ensures correctness regardless of call timing.
        val windowStartMs = newestTimestampSeen - (currentSeconds * 1000L)
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
        newestTimestampSeen = Long.MIN_VALUE
        current.clear()
    }

    companion object {
        const val DEFAULT_REFERENCE_SECONDS = 300
        const val DEFAULT_CURRENT_SECONDS = 120
        const val DEFAULT_WARMUP_SECONDS = 60
        // Hard size limit to prevent unbounded growth under pathological input
        private const val MAX_BUFFER_SIZE = 1000
    }
}
