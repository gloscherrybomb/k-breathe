package com.tymewear.karoo

/**
 * Selects samples where load has been stable long enough for ventilation to have
 * settled. Ventilation lags load by tens of seconds, so a sample taken mid-surge
 * describes the previous load, not the current one — comparing it against a baseline
 * reads as an efficiency change that never happened.
 *
 * Pure, but not clock-free: the windows are sized by sample *count* (assuming roughly
 * 1 Hz feeding, the rate the Karoo streams power), yet a real power outage can last far
 * longer than one tick — the collector sends a single `onPowerSample(null)` for an
 * outage of any length, whether it was 1 second or 2 minutes. A count-based window alone
 * cannot tell "60 contiguous seconds" from "59 samples from before a 2-minute gap plus 1
 * sample from after it" — the caller must supply [nowMs] so a gap that large can be
 * detected and both windows cleared, rather than stitching pre- and post-gap data into a
 * fabricated "steady" sample.
 */
class SteadyStateDetector(
    private val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    private val maxCoefficientOfVariation: Double = DEFAULT_MAX_CV,
    private val minSamplesInWindow: Int = DEFAULT_MIN_SAMPLES_IN_WINDOW,
    private val veSmoothingSeconds: Int = DEFAULT_VE_SMOOTHING_SECONDS,
    private val minLoadW: Double = DEFAULT_MIN_LOAD_W,
    private val maxLoadW: Double = DEFAULT_MAX_LOAD_W,
    private val maxSampleGapMs: Long = DEFAULT_MAX_SAMPLE_GAP_MS,
) {
    private val loadWindow = ArrayDeque<Double?>()
    private val veWindow = ArrayDeque<Double>()
    private var lastSampleMs: Long? = null

    /** Feed one sample observed at [nowMs]. Returns a comparable sample, or null if not
     *  steady. */
    fun onSample(loadW: Double?, ve: Double?, nowMs: Long): LoadVeSample? {
        val previousMs = lastSampleMs
        lastSampleMs = nowMs
        if (previousMs != null && nowMs - previousMs > maxSampleGapMs) {
            // A gap this large (default a few seconds — well above normal 1 Hz jitter,
            // well below the 60s load window) means whatever we were accumulating no
            // longer describes one continuous period of riding. Discard both windows so
            // the first samples after the gap cannot be stitched to samples from before
            // it into a sample that describes nothing that actually happened.
            loadWindow.clear()
            veWindow.clear()
        }

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

        return LoadVeSample(loadW, 0.0, veWindow.average())
    }

    /** Discard accumulated history. Called when a ride restarts so a new effort
     *  is never judged steady against the previous one's tail. */
    fun reset() {
        loadWindow.clear()
        veWindow.clear()
        lastSampleMs = null
    }

    companion object {
        const val DEFAULT_WINDOW_SECONDS = 60
        const val DEFAULT_MAX_CV = 0.12
        const val DEFAULT_MIN_SAMPLES_IN_WINDOW = 45
        const val DEFAULT_VE_SMOOTHING_SECONDS = 30
        const val DEFAULT_MIN_LOAD_W = 100.0
        const val DEFAULT_MAX_LOAD_W = 240.0

        // A "gap" is a break in the power stream large enough that it can no longer be
        // normal 1 Hz jitter. The Karoo's power stream ticks close to once a second, so
        // 3 seconds already comfortably exceeds any expected inter-sample delay, while
        // staying far below the 60-second load window — a real dropout (the case this
        // guards against) is typically many seconds to minutes long.
        const val DEFAULT_MAX_SAMPLE_GAP_MS = 3_000L
    }
}
