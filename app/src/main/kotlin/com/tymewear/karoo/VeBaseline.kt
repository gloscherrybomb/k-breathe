package com.tymewear.karoo

/** One baseline bin: its centre (W or bpm), mean VE and sample count. */
data class BinStat(val centre: Double, val meanVe: Double, val count: Int)

/**
 * What ventilation the rider normally produces at a given load, learned from their own
 * recent steady-state riding. Keyed by any numeric load measure — power in 20 W bins, or
 * heart rate in 5 bpm bins.
 *
 * Deliberately not derived from a threshold test: the rider's full year of activity
 * contains one ramp, so a test-dependent baseline would rarely be available. A rolling
 * reference also tracks fitness by itself, which makes a deviation from it mean "today
 * versus my recent normal" — the signal this feature exists to show.
 *
 * Stores a running mean and count per load bin. Pure; no Android or clock dependency.
 */
class VeBaseline(
    private val binWidth: Double = DEFAULT_BIN_WIDTH_W,
    private val minSamplesPerBin: Int = DEFAULT_MIN_SAMPLES_PER_BIN,
    private val maxSamplesPerBin: Int = DEFAULT_MAX_SAMPLES_PER_BIN,
) {
    private class Bin(var total: Double = 0.0, var count: Int = 0)

    private val binMap = HashMap<Double, Bin>()

    fun binCentre(key: Double): Double = Math.round(key / binWidth) * binWidth

    /**
     * Folds one sample into its bin.
     *
     * Below [maxSamplesPerBin] this is an exact running mean — byte-identical to a
     * baseline with no cap at all, which is why every existing golden bin value is
     * untouched by the cap's existence. Once a bin saturates, a new sample instead
     * replaces one average-weighted sample already in the mean (`total -= total /
     * count` before adding `ve`, with `count` held fixed), which turns the bin into an
     * exponential moving average with per-sample weight `1 / maxSamplesPerBin`. That is
     * what lets a genuine fitness change show up within a handful of rides rather than
     * a season: an uncapped running mean over ~30 rides moves a bin by only ~3% per new
     * ride.
     *
     * The cap is on *samples*, not rides, so a bin the rider revisits on every ride
     * saturates — and thereafter forgets — sooner, in rides, than one they rarely
     * revisit. That asymmetry is accepted rather than corrected: a rarely-visited bin
     * has too little data to spare for faster decay, so a longer effective half-life
     * there is the right trade, not a bug. The result is a half-life that is uniform in
     * samples but not in power.
     */
    fun update(key: Double, ve: Double) {
        val bin = binMap.getOrPut(binCentre(key)) { Bin() }
        if (bin.count < maxSamplesPerBin) {
            bin.total += ve
            bin.count += 1
        } else {
            bin.total = bin.total - bin.total / bin.count + ve
        }
    }

    /** Mean VE for the bin containing [key], or null when coverage is insufficient.
     *  Returning null rather than extrapolating keeps callers honest about range. */
    fun expectedVe(key: Double): Double? {
        val bin = binMap[binCentre(key)] ?: return null
        if (bin.count < minSamplesPerBin) return null
        return bin.total / bin.count
    }

    fun coveredBins(): Int = binMap.values.count { it.count >= minSamplesPerBin }

    /** All bins with their centre, mean VE and sample count, ascending by centre. */
    fun bins(): List<BinStat> = binMap.entries.sortedBy { it.key }.map { (c, b) -> BinStat(c, b.total / b.count, b.count) }

    /** Compact `centre:total:count` triples, joined by ';'. */
    fun serialise(): String =
        binMap.entries
            .sortedBy { it.key }
            .joinToString(";") { (centre, bin) -> "$centre:${bin.total}:${bin.count}" }

    companion object {
        const val DEFAULT_BIN_WIDTH_W = 20.0
        const val DEFAULT_HR_BIN_WIDTH = 5.0
        const val DEFAULT_MIN_SAMPLES_PER_BIN = 30

        /** Samples per bin at which [update] switches from an exact running mean to an
         *  exponential moving average — see [update]. 6000 gives roughly a 4-6 ride
         *  half-life for a bin the rider visits often, while every bin in today's
         *  three-ride reference baseline (largest: 3470 samples) stays well under it,
         *  so existing golden values are unaffected. */
        const val DEFAULT_MAX_SAMPLES_PER_BIN = 6000

        /** Tolerant by design: a corrupt or truncated preference must not crash the
         *  extension, it must simply start recalibrating. */
        fun deserialise(
            text: String,
            binWidth: Double = DEFAULT_BIN_WIDTH_W,
            minSamplesPerBin: Int = DEFAULT_MIN_SAMPLES_PER_BIN,
            maxSamplesPerBin: Int = DEFAULT_MAX_SAMPLES_PER_BIN,
        ): VeBaseline {
            val out = VeBaseline(binWidth, minSamplesPerBin, maxSamplesPerBin)
            for (part in text.split(";")) {
                val f = part.split(":")
                if (f.size != 3) continue
                val centre = f[0].toDoubleOrNull() ?: continue
                val total = f[1].toDoubleOrNull() ?: continue
                val count = f[2].toIntOrNull() ?: continue
                if (count <= 0) continue
                out.binMap[centre] = Bin(total, count)
            }
            return out
        }
    }
}
