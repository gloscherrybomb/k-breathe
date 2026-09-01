package com.tymewear.karoo

/**
 * What ventilation the rider normally produces at a given load, learned from their own
 * recent steady-state riding.
 *
 * Deliberately not derived from a threshold test: the rider's full year of activity
 * contains one ramp, so a test-dependent baseline would rarely be available. A rolling
 * reference also tracks fitness by itself, which makes a deviation from it mean "today
 * versus my recent normal" — the signal this feature exists to show.
 *
 * Stores a running mean and count per load bin. Pure; no Android or clock dependency.
 */
class VeBaseline(
    private val binWidthW: Double = DEFAULT_BIN_WIDTH_W,
    private val minSamplesPerBin: Int = DEFAULT_MIN_SAMPLES_PER_BIN,
) {
    private class Bin(var total: Double = 0.0, var count: Int = 0)

    private val bins = HashMap<Double, Bin>()

    fun binCentre(loadW: Double): Double = Math.round(loadW / binWidthW) * binWidthW

    fun update(sample: LoadVeSample) {
        val bin = bins.getOrPut(binCentre(sample.loadW)) { Bin() }
        bin.total += sample.ve
        bin.count += 1
    }

    /** Mean VE for the bin containing [loadW], or null when coverage is insufficient.
     *  Returning null rather than extrapolating keeps callers honest about range. */
    fun expectedVe(loadW: Double): Double? {
        val bin = bins[binCentre(loadW)] ?: return null
        if (bin.count < minSamplesPerBin) return null
        return bin.total / bin.count
    }

    fun coveredBins(): Int = bins.values.count { it.count >= minSamplesPerBin }

    /** Compact `centre:total:count` triples, joined by ';'. */
    fun serialise(): String =
        bins.entries
            .sortedBy { it.key }
            .joinToString(";") { (centre, bin) -> "$centre:${bin.total}:${bin.count}" }

    companion object {
        const val DEFAULT_BIN_WIDTH_W = 20.0
        const val DEFAULT_MIN_SAMPLES_PER_BIN = 30

        /** Tolerant by design: a corrupt or truncated preference must not crash the
         *  extension, it must simply start recalibrating. */
        fun deserialise(
            text: String,
            binWidthW: Double = DEFAULT_BIN_WIDTH_W,
            minSamplesPerBin: Int = DEFAULT_MIN_SAMPLES_PER_BIN,
        ): VeBaseline {
            val out = VeBaseline(binWidthW, minSamplesPerBin)
            for (part in text.split(";")) {
                val f = part.split(":")
                if (f.size != 3) continue
                val centre = f[0].toDoubleOrNull() ?: continue
                val total = f[1].toDoubleOrNull() ?: continue
                val count = f[2].toIntOrNull() ?: continue
                if (count <= 0) continue
                out.bins[centre] = Bin(total, count)
            }
            return out
        }
    }
}
