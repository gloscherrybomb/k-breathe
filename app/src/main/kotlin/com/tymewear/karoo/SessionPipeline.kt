package com.tymewear.karoo

data class PipelineOutput(
    val scale: Double?,
    val scaleStatus: ScaleStatus,
    val dayQualityPercent: Double?,
    val devP: Deviation?,
    val devHr: Deviation?,
)

/**
 * One ride's worth of the ventilatory-state maths, with no Android in it.
 *
 * Feeds every 1 Hz tick through [LoadGate]; each accepted sample is scored against the
 * power baseline and the heart-rate baseline. The heart-rate deviation is today's strap
 * scale; the difference between the two deviations is the physiological "day quality"
 * (spec §3.1). Samples are buffered, not folded into the baselines: the owner does that
 * at ride end, after normalising by the scale (spec §3.4), because scoring a ride against
 * a baseline it is feeding drags its own deviation toward zero.
 */
class SessionPipeline(
    private val powerBaseline: VeBaseline,
    private val hrBaseline: VeBaseline,
    private val rideCount: Int,
    private val minBaselineBins: Int,
    private val minBaselineRides: Int,
    private val gate: LoadGate = LoadGate(),
    private val scale: SessionScale = SessionScale(),
) {
    private val devPower = BinDeviation(powerBaseline)
    private val devHeart = BinDeviation(hrBaseline, binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
    private val _rideSamples = ArrayList<LoadVeSample>()
    val rideSamples: List<LoadVeSample> get() = _rideSamples

    private val confident: Boolean
        get() = powerBaseline.coveredBins() >= minBaselineBins &&
            hrBaseline.coveredBins() >= minBaselineBins &&
            rideCount >= minBaselineRides

    fun onSample(loadW: Double?, hrBpm: Double?, ve: Double?, nowMs: Long, recordingSeconds: Int): PipelineOutput {
        gate.onSample(loadW, hrBpm, ve, nowMs)?.let { s ->
            devPower.add(s.loadW, s.ve)
            devHeart.add(s.hrBpm, s.ve)
            if (_rideSamples.size < MAX_RIDE_SAMPLES) _rideSamples.add(s)
        }
        if (!confident) return PipelineOutput(null, ScaleStatus.Calibrating, null, null, null)
        val dp = devPower.deviation()
        val dh = devHeart.deviation()
        scale.offer(dh, recordingSeconds)
        val dq = if (dp != null && dh != null) dp.percent - dh.percent else null
        return PipelineOutput(scale.displayed(recordingSeconds), scale.status, dq, dp, dh)
    }

    fun reset() { gate.reset(); devPower.reset(); devHeart.reset(); scale.reset(); _rideSamples.clear() }

    companion object { const val MAX_RIDE_SAMPLES = 20_000 }
}
