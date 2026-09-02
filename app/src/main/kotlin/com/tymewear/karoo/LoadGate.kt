package com.tymewear.karoo

import kotlin.math.abs
import kotlin.math.exp

/** One comparable observation: ventilation at a smoothed load and heart rate. */
data class LoadVeSample(val loadW: Double, val hrBpm: Double, val ve: Double)

/**
 * Selects samples where ventilation is answering to a load that has settled.
 *
 * Outdoors, power over any 60 s window swings by 25-45 % (coasting, wind, gradient), so a
 * "power CV < 12 %" rule yields nothing at all — the 0.5.0 estimator produced zero samples
 * on a real ride. VE responds to load with a time constant of roughly a minute, so the load
 * VE is actually answering to is an exponentially weighted power, and the question is
 * whether *that* is stable, not whether the raw power is.
 */
class LoadGate(
    private val tauSeconds: Double = DEFAULT_TAU_SECONDS,
    private val minLoadW: Double = DEFAULT_MIN_LOAD_W,
    private val maxLoadW: Double = DEFAULT_MAX_LOAD_W,
    private val maxLoadChangeFraction: Double = DEFAULT_MAX_LOAD_CHANGE_FRACTION,
    private val lookbackTicks: Int = DEFAULT_LOOKBACK_TICKS,
    private val maxCoastTicks: Int = DEFAULT_MAX_COAST_TICKS,
    private val coastBelowW: Double = DEFAULT_COAST_BELOW_W,
    private val windowTicks: Int = DEFAULT_WINDOW_TICKS,
    private val minWindowFill: Int = DEFAULT_MIN_WINDOW_FILL,
    private val minHrBpm: Double = DEFAULT_MIN_HR_BPM,
    private val warmupTicks: Int = DEFAULT_WARMUP_TICKS,
    private val maxSampleGapMs: Long = DEFAULT_MAX_SAMPLE_GAP_MS,
) {
    private val alpha = 1.0 - exp(-1.0 / tauSeconds)
    private var load: Double? = null
    private val loadHistory = ArrayDeque<Double>()
    private val coastFlags = ArrayDeque<Boolean>()
    private val veWindow = ArrayDeque<Double>()
    private val hrWindow = ArrayDeque<Double>()
    private var ticks = 0
    private var lastSampleMs: Long? = null

    fun onSample(loadW: Double?, hrBpm: Double?, ve: Double?, nowMs: Long): LoadVeSample? {
        val previous = lastSampleMs
        lastSampleMs = nowMs
        if (previous != null && nowMs - previous > maxSampleGapMs) reset(keepClock = true)
        ticks++

        val power = loadW ?: 0.0
        val l = load?.let { it + alpha * (power - it) } ?: power
        load = l
        loadHistory.addLast(l)
        while (loadHistory.size > lookbackTicks + 1) loadHistory.removeFirst()
        coastFlags.addLast(loadW == null || loadW < coastBelowW)
        while (coastFlags.size > lookbackTicks) coastFlags.removeFirst()
        if (ve == null) veWindow.clear() else { veWindow.addLast(ve); while (veWindow.size > windowTicks) veWindow.removeFirst() }
        if (hrBpm != null) { hrWindow.addLast(hrBpm); while (hrWindow.size > windowTicks) hrWindow.removeFirst() }

        if (ticks < warmupTicks) return null
        if (loadHistory.size < lookbackTicks + 1) return null
        if (l < minLoadW || l > maxLoadW) return null
        if (abs(l - loadHistory.first()) / l >= maxLoadChangeFraction) return null
        if (coastFlags.count { it } > maxCoastTicks) return null
        if (veWindow.size < minWindowFill || hrWindow.size < minWindowFill) return null
        val meanVe = veWindow.average()
        val meanHr = hrWindow.average()
        if (meanVe <= 0.0 || meanHr < minHrBpm) return null
        return LoadVeSample(l, meanHr, meanVe)
    }

    fun reset() = reset(keepClock = false)

    private fun reset(keepClock: Boolean) {
        load = null; loadHistory.clear(); coastFlags.clear(); veWindow.clear(); hrWindow.clear(); ticks = 0
        if (!keepClock) lastSampleMs = null
    }

    companion object {
        const val DEFAULT_TAU_SECONDS = 60.0
        const val DEFAULT_MIN_LOAD_W = 60.0
        const val DEFAULT_MAX_LOAD_W = 320.0
        const val DEFAULT_MAX_LOAD_CHANGE_FRACTION = 0.10
        const val DEFAULT_LOOKBACK_TICKS = 30
        const val DEFAULT_MAX_COAST_TICKS = 2
        const val DEFAULT_COAST_BELOW_W = 20.0
        const val DEFAULT_WINDOW_TICKS = 30
        const val DEFAULT_MIN_WINDOW_FILL = 15
        const val DEFAULT_MIN_HR_BPM = 60.0
        const val DEFAULT_WARMUP_TICKS = 120
        const val DEFAULT_MAX_SAMPLE_GAP_MS = 3_000L
    }
}
