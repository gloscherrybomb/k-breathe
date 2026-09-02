package com.tymewear.karoo

import kotlin.math.abs

/**
 * Where the rider's pooled VE-versus-load baseline bends, in the baseline's own scale.
 *
 * Both loads are watts and both VE values L/min; a field is null when the fit did not
 * support that break. `lowerLoadW`/`lowerVe` describe the first (lower) break, so a
 * one-break result fills the lower pair and leaves the upper pair null.
 */
data class Breakpoints(
    val lowerLoadW: Double?,
    val lowerVe: Double?,
    val upperLoadW: Double?,
    val upperVe: Double?,
) {
    fun serialise(): String = "${lowerLoadW ?: ""}:${lowerVe ?: ""}:${upperLoadW ?: ""}:${upperVe ?: ""}"

    companion object {
        /** Tolerant by design: a corrupt preference must leave the rider without a
         *  suggestion, never crash the extension. */
        fun deserialise(s: String): Breakpoints? {
            val f = s.split(":")
            if (f.size != 4) return null
            val v = arrayOfNulls<Double>(4)
            for (i in 0..3) {
                if (f[i].isEmpty()) continue
                v[i] = f[i].toDoubleOrNull() ?: return null
            }
            return Breakpoints(v[0], v[1], v[2], v[3])
        }
    }
}

enum class ThresholdKind { VT1, VT2 }

/** A proposed change to one configured threshold, both values in L/min. */
data class Suggestion(val kind: ThresholdKind, val currentVe: Double, val suggestedVe: Double)

/**
 * Fits ventilatory thresholds out of the pooled baseline, and turns estimates that
 * several rides agree on into suggestions.
 *
 * The baseline is a set of 20 W bins of mean VE built from steady riding across many
 * rides. Plotted against load it is roughly piecewise linear: ventilation rises gently,
 * steepens once at VT1 and again at VT2. So the thresholds are recoverable as the knots
 * of a continuous piecewise-linear fit — no ramp test and no lab required, which matters
 * because a rider's year of activity contains at most one ramp.
 *
 * Fitting is a weighted least squares over the hinge basis `1, x, max(0, x-k1)
 * [, max(0, x-k2)]`, with the knots chosen by exhaustive search over bin centres. The
 * hinge basis is what makes the fit continuous by construction, so a knot cannot be
 * "explained" by a jump. Weighting by sample count lets a bin the rider visits for
 * minutes outvote one they passed through for seconds.
 *
 * Two guards keep noise from becoming a threshold: an extra break must cut the weighted
 * SSE by [DEFAULT_MIN_IMPROVEMENT] (more parameters always fit better, so unpenalised SSE
 * would always pick two breaks), and each hinge coefficient must be positive — a
 * ventilatory threshold is where the curve steepens, so a knot where the slope flattens
 * is an artefact of the rider's power distribution and is refused.
 *
 * Pure: no Android, no clock, no logging.
 */
object ThresholdEvidence {
    const val DEFAULT_MIN_COUNT = 120
    const val DEFAULT_MIN_BRACKET_BINS = 2
    const val DEFAULT_MIN_IMPROVEMENT = 0.25

    /** A suggestion is suppressed once it lands this close to the value the rider last
     *  dismissed for that threshold — dismissing is "not this number", not "never ask
     *  again". */
    const val DEFAULT_DISMISS_TOLERANCE_VE = 3.0

    /** Below this a hinge coefficient is floating-point residue from fitting a knot to
     *  data that has none, not a real steepening. Physically negligible: 1e-9 L/min per
     *  additional watt is 3e-7 L/min across the whole power range. */
    private const val SLOPE_EPSILON = 1e-9

    private val NONE = Breakpoints(null, null, null, null)

    /**
     * Best-supported breakpoints for [bins], or all-null when the data does not support
     * any. [minCount] drops bins too thin to trust; [minBracketBins] keeps a knot away
     * from the ends (and the two knots apart), so no segment is fitted from one or two
     * bins; [minImprovement] is the fraction of weighted SSE an extra break must remove.
     */
    fun estimate(
        bins: List<BinStat>,
        minCount: Int = DEFAULT_MIN_COUNT,
        minBracketBins: Int = DEFAULT_MIN_BRACKET_BINS,
        minImprovement: Double = DEFAULT_MIN_IMPROVEMENT,
    ): Breakpoints {
        val used = bins.filter { it.count >= minCount }.sortedBy { it.centre }
        if (used.size < 5) return NONE
        val x = DoubleArray(used.size) { used[it].centre }
        val y = DoubleArray(used.size) { used[it].meanVe }
        val w = DoubleArray(used.size) { used[it].count.toDouble() }

        val line = fit(x, y, w, null, null) ?: return NONE
        val last = used.size - 1 - minBracketBins

        var best1: Fit? = null
        for (i in minBracketBins..last) {
            val f = fit(x, y, w, x[i], null) ?: continue
            if (best1 == null || f.sse < best1.sse) best1 = f
        }
        var best2: Fit? = null
        for (i in minBracketBins..last) {
            for (j in (i + minBracketBins)..last) {
                val f = fit(x, y, w, x[i], x[j]) ?: continue
                if (best2 == null || f.sse < best2.sse) best2 = f
            }
        }

        if (best2 != null && best1 != null &&
            best2.sse <= (1.0 - minImprovement) * best1.sse &&
            best2.coefficients[2] > SLOPE_EPSILON && best2.coefficients[3] > SLOPE_EPSILON
        ) {
            return Breakpoints(best2.k1, best2.valueAt(best2.k1!!), best2.k2, best2.valueAt(best2.k2!!))
        }
        if (best1 != null &&
            best1.sse <= (1.0 - minImprovement) * line.sse &&
            best1.coefficients[2] > SLOPE_EPSILON
        ) {
            return Breakpoints(best1.k1, best1.valueAt(best1.k1!!), null, null)
        }
        return NONE
    }

    /**
     * Suggestions the last [agreeRides] entries of [history] (oldest first) agree on.
     *
     * Agreement across whole rides, not a single fit's confidence, is the evidence bar:
     * one ride's breakpoint moves with what the rider happened to do that day, so a
     * threshold is only proposed when independent rides land within [agreeToleranceVe]
     * of each other. [minDifferenceFraction] then suppresses churn — re-tuning zones for
     * a 2% shift costs the rider more than it tells them.
     *
     * A fit with both breaks names them directly. A lone break is ambiguous, and is read
     * against the midpoint of the configured thresholds: below it, it is VT1; at or above
     * it, VT2.
     */
    fun suggestions(
        history: List<Breakpoints>,
        configured: ZoneThresholds,
        agreeRides: Int = 3,
        agreeToleranceVe: Double = 4.0,
        minDifferenceFraction: Double = 0.08,
    ): List<Suggestion> {
        if (history.size < agreeRides || agreeRides <= 0) return emptyList()
        val recent = history.subList(history.size - agreeRides, history.size)
        val midpoint = (configured.vt1 + configured.vt2) / 2.0

        fun candidate(bp: Breakpoints, kind: ThresholdKind): Double? = when (kind) {
            ThresholdKind.VT1 -> if (bp.upperVe != null) bp.lowerVe else bp.lowerVe?.takeIf { it < midpoint }
            ThresholdKind.VT2 -> bp.upperVe ?: bp.lowerVe?.takeIf { it >= midpoint }
        }

        val out = ArrayList<Suggestion>(2)
        for ((kind, current) in listOf(ThresholdKind.VT1 to configured.vt1, ThresholdKind.VT2 to configured.vt2)) {
            val values = recent.mapNotNull { candidate(it, kind) }
            if (values.size < agreeRides) continue
            if (values.max() - values.min() > agreeToleranceVe) continue
            val estimate = median(values)
            if (abs(estimate - current) > minDifferenceFraction * current) {
                out.add(Suggestion(kind, current, estimate))
            }
        }
        return out
    }

    /** Drops suggestions the rider dismissed at (nearly) this value and any that would put the
     *  thresholds out of order. Pure, so the two rules are testable without preferences. */
    fun filterSuggestions(
        suggestions: List<Suggestion>,
        configured: ZoneThresholds,
        dismissedVt1: Double?,
        dismissedVt2: Double?,
        dismissToleranceVe: Double = DEFAULT_DISMISS_TOLERANCE_VE,
    ): List<Suggestion> = suggestions.filter { s ->
        val dismissed = if (s.kind == ThresholdKind.VT1) dismissedVt1 else dismissedVt2
        val notDismissed = dismissed == null || abs(s.suggestedVe - dismissed) >= dismissToleranceVe
        val inOrder = when (s.kind) {
            ThresholdKind.VT1 -> s.suggestedVe < configured.vt2
            ThresholdKind.VT2 -> s.suggestedVe > configured.vt1 && s.suggestedVe < configured.topZ4
        }
        notDismissed && inOrder
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** One weighted piecewise-linear fit: its knots, coefficients `[a, b, c, d]` and
     *  weighted SSE. Absent knots carry a zero coefficient so [valueAt] is uniform. */
    private class Fit(val k1: Double?, val k2: Double?, val coefficients: DoubleArray, val sse: Double) {
        fun valueAt(x: Double): Double =
            coefficients[0] + coefficients[1] * x +
                coefficients[2] * maxOf(0.0, x - (k1 ?: x)) +
                coefficients[3] * maxOf(0.0, x - (k2 ?: x))
    }

    /** Weighted least squares over `1, x` plus one hinge per non-null knot. Null when
     *  the normal equations are singular, which the caller treats as "this knot does not
     *  fit" and skips. */
    private fun fit(x: DoubleArray, y: DoubleArray, w: DoubleArray, k1: Double?, k2: Double?): Fit? {
        val knots = listOfNotNull(k1, k2)
        val n = 2 + knots.size
        val a = Array(n) { DoubleArray(n) }
        val rhs = DoubleArray(n)
        val basis = DoubleArray(n)
        for (p in x.indices) {
            basis[0] = 1.0
            basis[1] = x[p]
            for (q in knots.indices) basis[2 + q] = maxOf(0.0, x[p] - knots[q])
            for (i in 0 until n) {
                rhs[i] += w[p] * basis[i] * y[p]
                for (j in 0 until n) a[i][j] += w[p] * basis[i] * basis[j]
            }
        }
        val solved = solve(a, rhs) ?: return null
        val coefficients = DoubleArray(4)
        solved.copyInto(coefficients)
        var sse = 0.0
        for (p in x.indices) {
            var predicted = coefficients[0] + coefficients[1] * x[p]
            for (q in knots.indices) predicted += coefficients[2 + q] * maxOf(0.0, x[p] - knots[q])
            val r = y[p] - predicted
            sse += w[p] * r * r
        }
        return Fit(k1, k2, coefficients, sse)
    }

    /**
     * Gaussian elimination with partial pivoting; null when the matrix is singular.
     *
     * The singularity test is relative to the largest entry, not absolute: these are
     * normal equations in raw watts weighted by sample counts, so entries reach ~1e9 and
     * any fixed epsilon would either never fire or fire on a healthy system. A degenerate
     * knot (one at the last bin, where its hinge column is all zeros) is what this
     * catches, and the caller simply skips that candidate.
     */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val r = b.copyOf()
        var scale = 0.0
        for (row in m) for (v in row) scale = maxOf(scale, abs(v))
        if (scale == 0.0) return null
        val tolerance = 1e-12 * scale
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            }
            if (abs(m[pivot][col]) < tolerance) return null
            if (pivot != col) {
                val t = m[pivot]; m[pivot] = m[col]; m[col] = t
                val s = r[pivot]; r[pivot] = r[col]; r[col] = s
            }
            for (row in col + 1 until n) {
                val factor = m[row][col] / m[col][col]
                if (factor == 0.0) continue
                for (k in col until n) m[row][k] -= factor * m[col][k]
                r[row] -= factor * r[col]
            }
        }
        val out = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var acc = r[row]
            for (k in row + 1 until n) acc -= m[row][k] * out[k]
            out[row] = acc / m[row][row]
        }
        return out
    }
}
