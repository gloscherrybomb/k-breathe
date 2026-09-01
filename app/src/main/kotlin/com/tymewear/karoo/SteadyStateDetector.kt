package com.tymewear.karoo

/** One comparable observation: the ventilation produced at a given load. */
data class LoadVeSample(val loadW: Double, val ve: Double)

/**
 * Selects samples where load has been stable long enough for ventilation to have
 * settled. Ventilation lags load by tens of seconds, so a sample taken mid-surge
 * describes the previous load, not the current one — comparing it against a baseline
 * reads as an efficiency change that never happened.
 *
 * Pure and clock-free so the windowing is deterministic in tests. Assumes it is fed at
 * roughly 1 Hz, which is the rate the Karoo streams power.
 */
class SteadyStateDetector(
    private val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    private val maxCoefficientOfVariation: Double = DEFAULT_MAX_CV,
    private val minSamplesInWindow: Int = DEFAULT_MIN_SAMPLES_IN_WINDOW,
    private val veSmoothingSeconds: Int = DEFAULT_VE_SMOOTHING_SECONDS,
    private val minLoadW: Double = DEFAULT_MIN_LOAD_W,
    private val maxLoadW: Double = DEFAULT_MAX_LOAD_W,
) {
    private val loadWindow = ArrayDeque<Double?>()
    private val veWindow = ArrayDeque<Double>()

    /** Feed one 1 Hz sample. Returns a comparable sample, or null if not steady. */
    fun onSample(loadW: Double?, ve: Double?): LoadVeSample? {
        loadWindow.addLast(loadW)
        while (loadWindow.size > windowSeconds) loadWindow.removeFirst()

        if (ve != null) {
            veWindow.addLast(ve)
            while (veWindow.size > veSmoothingSeconds) veWindow.removeFirst()
        } else {
            // A null VE means the breathing data was stale or absent for this tick
            // (see TymewearData.isDataFresh()). Keeping the pre-dropout window would let
            // onSample keep returning a frozen average for the rest of the dropout — a
            // sample that looks real but describes nothing that is actually happening.
            // No sample is comparable until fresh VE has re-accumulated.
            veWindow.clear()
        }

        if (loadW == null || loadW < minLoadW || loadW > maxLoadW) return null
        if (veWindow.isEmpty()) return null

        val present = loadWindow.filterNotNull()
        if (present.size < minSamplesInWindow) return null

        val mean = present.average()
        if (mean <= 0.0) return null
        val variance = present.sumOf { (it - mean) * (it - mean) } / present.size
        if (Math.sqrt(variance) / mean >= maxCoefficientOfVariation) return null

        return LoadVeSample(loadW, veWindow.average())
    }

    /** Discard accumulated history. Called when a ride restarts so a new effort
     *  is never judged steady against the previous one's tail. */
    fun reset() {
        loadWindow.clear()
        veWindow.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_SECONDS = 60
        const val DEFAULT_MAX_CV = 0.12
        const val DEFAULT_MIN_SAMPLES_IN_WINDOW = 45
        const val DEFAULT_VE_SMOOTHING_SECONDS = 30
        const val DEFAULT_MIN_LOAD_W = 100.0
        const val DEFAULT_MAX_LOAD_W = 240.0
    }
}
