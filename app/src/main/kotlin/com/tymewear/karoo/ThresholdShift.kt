package com.tymewear.karoo

/**
 * Converts a ventilation threshold into the load at which the rider would reach it
 * today, given how far today's ventilation sits from their baseline.
 *
 * A deviation of -10% means today's VE at any load is 10% below baseline, so the
 * threshold VE arrives at a *higher* load: equivalently, we look for the baseline load
 * whose expected VE equals `thresholdVe / (1 + deviation)`.
 *
 * Returns null rather than extrapolating outside the loads the baseline covers — a
 * confidently wrong threshold power is worse than none.
 */
object ThresholdShift {

    fun thresholdPowerW(
        baseline: VeBaseline,
        thresholdVe: Double,
        deviationFraction: Double,
        minLoadW: Double = SteadyStateDetector.DEFAULT_MIN_LOAD_W,
        maxLoadW: Double = SteadyStateDetector.DEFAULT_MAX_LOAD_W,
        stepW: Double = VeBaseline.DEFAULT_BIN_WIDTH_W,
    ): Double? {
        if (thresholdVe <= 0.0) return null
        val target = thresholdVe / (1.0 + deviationFraction)

        // Collect covered bins in ascending load order.
        val points = ArrayList<Pair<Double, Double>>()
        var load = Math.round(minLoadW / stepW) * stepW
        while (load <= maxLoadW) {
            baseline.expectedVe(load)?.let { points.add(load to it) }
            load += stepW
        }
        if (points.size < 2) return null

        // The target must be bracketed by the covered range; otherwise we would be
        // guessing beyond what the rider has actually done.
        val first = points.first().second
        val last = points.last().second
        val lo = minOf(first, last)
        val hi = maxOf(first, last)
        if (target < lo || target > hi) return null

        for (i in 0 until points.size - 1) {
            val (p0, v0) = points[i]
            val (p1, v1) = points[i + 1]
            val within = (target in minOf(v0, v1)..maxOf(v0, v1))
            if (!within) continue
            if (v1 == v0) return p0
            val t = (target - v0) / (v1 - v0)
            return p0 + t * (p1 - p0)
        }
        return null
    }
}
