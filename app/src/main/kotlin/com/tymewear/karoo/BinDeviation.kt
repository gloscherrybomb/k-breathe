package com.tymewear.karoo

/** How today's ventilation compares with the baseline. Negative means less ventilation
 *  for the same work: fresher, fitter, or simply a good day. */
data class Deviation(val fraction: Double, val matchedBins: Int) {
    val percent: Double get() = fraction * 100.0
}

/**
 * Compares observed ventilation against a [VeBaseline] at matched key (power bin or
 * heart-rate bin). Used twice per ride: once keyed by power, once by heart rate — the
 * pair separates a strap scale shift (both move) from a physiological change (only power
 * moves); see the spec §3.1.
 *
 * Bin-wise medians are compared, then the median across bins is taken, so a single odd
 * bin cannot swing the answer. Requires several matched bins before reporting anything:
 * one bin describes one intensity, not the ride.
 *
 * Callers MUST only feed samples derived from fresh sensor data. A frozen VE value
 * produces a confident, entirely fictional number — this is what corrupted ten of the
 * rider's recorded rides before the recording fix.
 */
class BinDeviation(
    private val baseline: VeBaseline,
    private val binWidth: Double = VeBaseline.DEFAULT_BIN_WIDTH_W,
    private val minSamplesPerBin: Int = VeBaseline.DEFAULT_MIN_SAMPLES_PER_BIN,
    private val minMatchedBins: Int = DEFAULT_MIN_MATCHED_BINS,
) {
    private val observed = HashMap<Double, MutableList<Double>>()

    fun add(key: Double, ve: Double) {
        observed.getOrPut(Math.round(key / binWidth) * binWidth) { ArrayList() }.add(ve)
    }

    fun deviation(): Deviation? {
        val ratios = ArrayList<Double>()
        for ((centre, values) in observed) {
            if (values.size < minSamplesPerBin) continue
            val expected = baseline.expectedVe(centre) ?: continue
            if (expected <= MIN_MEANINGFUL_VE) continue
            ratios.add(median(values) / expected - 1.0)
        }
        if (ratios.size < minMatchedBins) return null
        return Deviation(median(ratios), ratios.size)
    }

    fun reset() = observed.clear()

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    companion object {
        const val DEFAULT_MIN_MATCHED_BINS = 3
        /** Below this, VE is noise or a sensor artefact and ratios explode. */
        const val MIN_MEANINGFUL_VE = 5.0
    }
}
