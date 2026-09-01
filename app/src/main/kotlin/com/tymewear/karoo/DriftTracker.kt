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
 * Timestamps are anchored on the newest timestamp ever seen. Small out-of-order samples
 * (within CLOCK_JITTER_TOLERANCE) are rejected as transient jitter (BLE reordering, etc.).
 * Large backwards jumps (> CLOCK_JITTER_TOLERANCE) indicate a genuine clock correction
 * (device boot time wrong, later sync fixed it). Silently stalling on a sustained correction
 * is worse than restarting: we reset all state and rebuild from the corrected time. This
 * ensures the rider sees the drift figure restart rather than go dead. Eviction happens in
 * `add()` as well as `driftPercent()` to prevent unbounded accumulation.
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
        // Detect large backwards clock jump (genuine correction, not transient jitter).
        // A sustained correction means all future samples would be rejected forever,
        // silently stalling the ride. Instead, reset and restart from the corrected time.
        if (newestTimestampSeen != Long.MIN_VALUE && nowMs < newestTimestampSeen &&
            newestTimestampSeen - nowMs > CLOCK_JITTER_TOLERANCE_MS) {
            // Clock was corrected backwards significantly. Reset state to restart from this sample.
            reset()
            startMs = nowMs  // Begin the ride anew from the corrected time
        }

        val start = startMs ?: nowMs.also { startMs = it }
        val elapsedS = (nowMs - start) / 1000

        if (elapsedS < warmupSeconds) return

        if (referenceMean == null) {
            referenceValues.add(value)
            if (elapsedS >= warmupSeconds + referenceSeconds - 1) {
                referenceMean = referenceValues.average()
            }
            // Track latest timestamp for clock-jump detection during reference building
            newestTimestampSeen = maxOf(newestTimestampSeen, nowMs)
            return
        }

        // Reject small backwards jitter (transient out-of-order delivery)
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
        // Threshold between transient jitter and genuine clock correction (milliseconds).
        // BLE out-of-order delivery is typically sub-second; a genuine correction (e.g., device
        // boot time wrong, later sync fixes it) is much larger. Above this threshold, we reset.
        private const val CLOCK_JITTER_TOLERANCE_MS = 2000L
    }
}
