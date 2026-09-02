package com.tymewear.karoo

sealed class ScaleStatus {
    object Calibrating : ScaleStatus()
    data class Locked(val target: Double) : ScaleStatus()
    data class OutOfRange(val raw: Double) : ScaleStatus()
}

/**
 * Today's strap scale factor — how far this session's tidal-volume scale sits from the
 * rider's baseline — estimated from the heart-rate-matched VE deviation (spec §3.1) and
 * held for the ride once learned (spec §3.3). Once locked, any out-of-range offer is ignored
 * and the lock is retained.
 */
class SessionScale(
    private val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    private val minMatchedBins: Int = DEFAULT_MIN_MATCHED_BINS,
    private val minScale: Double = DEFAULT_MIN_SCALE,
    private val maxScale: Double = DEFAULT_MAX_SCALE,
    private val updateIntervalSeconds: Int = DEFAULT_UPDATE_INTERVAL_SECONDS,
    private val easingSeconds: Int = DEFAULT_EASING_SECONDS,
) {
    var status: ScaleStatus = ScaleStatus.Calibrating
        private set
    private var lastUpdateAt: Int? = null
    private var easeFrom = 1.0
    private var easeStart = 0

    fun offer(devHr: Deviation?, recordingSeconds: Int) {
        if (recordingSeconds > windowSeconds) return
        if (devHr == null || devHr.matchedBins < minMatchedBins) return
        val last = lastUpdateAt
        if (last != null && recordingSeconds - last < updateIntervalSeconds) return
        val raw = 1.0 + devHr.fraction
        if (raw < minScale || raw > maxScale) { if (status !is ScaleStatus.Locked) { status = ScaleStatus.OutOfRange(raw); lastUpdateAt = recordingSeconds }; return }
        easeFrom = displayed(recordingSeconds) ?: 1.0
        easeStart = recordingSeconds
        status = ScaleStatus.Locked(raw)
        lastUpdateAt = recordingSeconds
    }

    fun displayed(recordingSeconds: Int): Double? {
        val target = (status as? ScaleStatus.Locked)?.target ?: return null
        val t = ((recordingSeconds - easeStart).toDouble() / easingSeconds).coerceIn(0.0, 1.0)
        return easeFrom + (target - easeFrom) * t
    }

    fun reset() { status = ScaleStatus.Calibrating; lastUpdateAt = null; easeFrom = 1.0; easeStart = 0 }

    companion object {
        const val DEFAULT_WINDOW_SECONDS = 2400
        const val DEFAULT_MIN_MATCHED_BINS = 3
        const val DEFAULT_MIN_SCALE = 0.6
        const val DEFAULT_MAX_SCALE = 1.6
        const val DEFAULT_UPDATE_INTERVAL_SECONDS = 60
        const val DEFAULT_EASING_SECONDS = 30
    }
}
