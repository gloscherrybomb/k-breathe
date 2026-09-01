package com.tymewear.karoo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Owns the ventilatory-state pipeline across a ride: which samples are comparable, what
 * the rider's baseline is, and how today differs from it.
 *
 * The baseline persists between rides — that is what makes the deviation "today versus my
 * recent normal" rather than "today versus the start of this ride".
 */
object VentilatoryState {

    private const val PREFS = "tymewear_prefs"
    private const val KEY_BASELINE = "baseline_bins"
    private const val KEY_UPDATED = "baseline_updated_at"
    private const val KEY_RIDES = "baseline_ride_count"
    private const val KEY_ENABLED = "dynamic_state_enabled"

    private var baseline = VeBaseline()
    private var detector = SteadyStateDetector()
    private var deviationCalc = EfficiencyDeviation(baseline)
    private var drift = DriftTracker()
    private var enabled = false
    private var rideCount = 0

    private val _deviation = MutableStateFlow<Deviation?>(null)
    val deviation: StateFlow<Deviation?> = _deviation.asStateFlow()

    private val _vt1PowerW = MutableStateFlow<Double?>(null)
    val vt1PowerW: StateFlow<Double?> = _vt1PowerW.asStateFlow()

    private val _vt2PowerW = MutableStateFlow<Double?>(null)
    val vt2PowerW: StateFlow<Double?> = _vt2PowerW.asStateFlow()

    private val _driftPercent = MutableStateFlow<Double?>(null)
    val driftPercent: StateFlow<Double?> = _driftPercent.asStateFlow()

    private val _baselineBins = MutableStateFlow(0)
    val baselineBins: StateFlow<Int> = _baselineBins.asStateFlow()

    fun isEnabled(): Boolean = enabled

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        rideCount = prefs.getInt(KEY_RIDES, 0)
        baseline = VeBaseline.deserialise(prefs.getString(KEY_BASELINE, "") ?: "")
        deviationCalc = EfficiencyDeviation(baseline)
        _baselineBins.value = baseline.coveredBins()
        Timber.d("VentilatoryState loaded: enabled=$enabled bins=${baseline.coveredBins()} rides=$rideCount")
    }

    /**
     * Feed one 1 Hz power sample. Breathing values are read from [TymewearData], and are
     * only used when fresh — a stale value is indistinguishable from a real one and would
     * make the deviation confident fiction.
     */
    fun onPowerSample(loadW: Double?) {
        if (!enabled) return
        val fresh = TymewearData.isDataFresh()
        val ve = if (fresh) TymewearData.smoothMinuteVolume.value.takeIf { it > 0.0 } else null
        val br = if (fresh) TymewearData.smoothBreathRate.value.takeIf { it > 0.0 } else null

        if (br != null) {
            drift.add(System.currentTimeMillis(), br)
            _driftPercent.value = drift.driftPercent()
        }

        val sample = detector.onSample(loadW, ve) ?: return
        deviationCalc.add(sample)
        baseline.update(sample)

        val dev = deviationCalc.deviation()
        _deviation.value = dev
        if (dev != null && baseline.coveredBins() >= Constants.STATE_MIN_BASELINE_BINS) {
            val t = TymewearData.currentThresholds()
            _vt1PowerW.value = ThresholdShift.thresholdPowerW(baseline, t.vt1, dev.fraction)
            _vt2PowerW.value = ThresholdShift.thresholdPowerW(baseline, t.vt2, dev.fraction)
        }
        _baselineBins.value = baseline.coveredBins()
    }

    fun onRideStart() {
        detector.reset()
        deviationCalc.reset()
        drift.reset()
        _deviation.value = null
        _vt1PowerW.value = null
        _vt2PowerW.value = null
        _driftPercent.value = null
    }

    fun onRideEnd(context: Context) {
        rideCount += 1
        persist(context)
        Timber.d("VentilatoryState saved: bins=${baseline.coveredBins()} rides=$rideCount")
    }

    fun resetBaseline(context: Context) {
        baseline = VeBaseline()
        deviationCalc = EfficiencyDeviation(baseline)
        rideCount = 0
        _baselineBins.value = 0
        persist(context)
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASELINE, baseline.serialise())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .putInt(KEY_RIDES, rideCount)
            .apply()
    }
}
