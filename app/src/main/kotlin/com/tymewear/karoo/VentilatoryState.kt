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
 * recent normal" rather than "today versus the start of this ride". Concretely, this means
 * a ride's own steady samples are buffered, not folded into the baseline, until the ride
 * ends: scoring a ride against a baseline that this same ride has been feeding all along
 * would drag the reported deviation toward zero over exactly the long steady efforts the
 * feature exists to evaluate.
 *
 * `onPowerSample` runs on the power-stream collector coroutine; `onRideStart`/`onRideEnd`
 * run on the RideState collector coroutine. Both are dispatched on `Dispatchers.IO`, a
 * multi-threaded pool, so the two can run concurrently — every mutating entry point is
 * guarded by [lock].
 */
object VentilatoryState {

    private const val PREFS = "tymewear_prefs"
    private const val KEY_BASELINE = "baseline_bins"
    private const val KEY_UPDATED = "baseline_updated_at"
    private const val KEY_RIDES = "baseline_ride_count"
    private const val KEY_ENABLED = "dynamic_state_enabled"

    /** Generous cap on this ride's buffered steady samples — a ride yields roughly
     *  2000-4300 of them, so this is never expected to bind; it only stops unbounded
     *  growth if a ride runs unusually long. */
    private const val MAX_RIDE_SAMPLES = 20000

    private val lock = Any()

    private var baseline = VeBaseline()
    private var detector = SteadyStateDetector()
    private var deviationCalc = EfficiencyDeviation(baseline)
    private var drift = DriftTracker()
    private var enabled = false
    private var rideCount = 0

    /** This ride's steady samples, held back from the baseline until the ride ends. */
    private val rideSamples = ArrayList<LoadVeSample>()

    /** True from a real ride start until that ride ends; a resume from pause must not
     *  reset the pipeline, and an end must not fire without a matching start (guards
     *  against consumerFlow<RideState>() replaying an Idle on a cold subscribe). */
    private var rideActive = false

    /** True while the ride is paused: samples must not reach the pipeline or the
     *  baseline while the rider is stopped. */
    private var paused = false

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
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            enabled = prefs.getBoolean(KEY_ENABLED, false)
            rideCount = prefs.getInt(KEY_RIDES, 0)
            baseline = VeBaseline.deserialise(prefs.getString(KEY_BASELINE, "") ?: "")
            deviationCalc = EfficiencyDeviation(baseline)
            _baselineBins.value = baseline.coveredBins()
            Timber.d("VentilatoryState loaded: enabled=$enabled bins=${baseline.coveredBins()} rides=$rideCount")
        }
    }

    /**
     * Feed one 1 Hz power sample. Breathing values are read from [TymewearData], and are
     * only used when fresh — a stale value is indistinguishable from a real one and would
     * make the deviation confident fiction. [SteadyStateDetector] enforces the same
     * freshness contract internally: it clears its VE window on a null reading so a
     * sensor dropout can never surface a frozen pre-dropout average as a "steady" sample.
     */
    fun onPowerSample(loadW: Double?) {
        synchronized(lock) {
            if (!enabled || paused) return
            val fresh = TymewearData.isDataFresh()
            val ve = if (fresh) TymewearData.smoothMinuteVolume.value.takeIf { it > 0.0 } else null
            val br = if (fresh) TymewearData.smoothBreathRate.value.takeIf { it > 0.0 } else null

            if (br != null) {
                drift.add(System.currentTimeMillis(), br)
                _driftPercent.value = drift.driftPercent()
            }

            val sample = detector.onSample(loadW, ve) ?: return
            deviationCalc.add(sample)
            // Buffered, not folded into the baseline yet — see the class doc. The
            // baseline is only updated with this ride's samples in onRideEnd().
            if (rideSamples.size < MAX_RIDE_SAMPLES) rideSamples.add(sample)

            val dev = deviationCalc.deviation()
            _deviation.value = dev
            if (dev != null && baseline.coveredBins() >= Constants.STATE_MIN_BASELINE_BINS) {
                val t = TymewearData.currentThresholds()
                _vt1PowerW.value = ThresholdShift.thresholdPowerW(baseline, t.vt1, dev.fraction)
                _vt2PowerW.value = ThresholdShift.thresholdPowerW(baseline, t.vt2, dev.fraction)
            }
        }
    }

    /**
     * A real ride start resets the pipeline for a fresh effort. A resume from pause
     * (RideState goes Paused -> Recording, which also passes through here) must not
     * reset anything — [rideActive] already being true is how the two are told apart.
     */
    fun onRideStart() {
        synchronized(lock) {
            if (rideActive) return
            rideActive = true
            paused = false
            detector.reset()
            deviationCalc.reset()
            drift.reset()
            rideSamples.clear()
            _deviation.value = null
            _vt1PowerW.value = null
            _vt2PowerW.value = null
            _driftPercent.value = null
        }
    }

    /** Stops samples from reaching the pipeline or the baseline while the rider is
     *  stopped, without discarding what has already been buffered for this ride. */
    fun onRidePause() {
        synchronized(lock) { paused = true }
    }

    fun onRideEnd(context: Context) {
        synchronized(lock) {
            // No-op unless a ride is actually active: guards against
            // consumerFlow<RideState>() replaying the current state on a cold
            // subscribe, which would otherwise fire an Idle transition (and so this
            // method) with nothing having started, spuriously incrementing rideCount
            // and re-persisting an unchanged baseline.
            if (!rideActive) return
            for (sample in rideSamples) baseline.update(sample)
            rideSamples.clear()
            _baselineBins.value = baseline.coveredBins()
            rideCount += 1
            rideActive = false
            paused = false
            persist(context)
            Timber.d("VentilatoryState saved: bins=${baseline.coveredBins()} rides=$rideCount")
        }
    }

    fun resetBaseline(context: Context) {
        synchronized(lock) {
            baseline = VeBaseline()
            deviationCalc = EfficiencyDeviation(baseline)
            rideCount = 0
            _baselineBins.value = 0
            persist(context)
        }
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASELINE, baseline.serialise())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .putInt(KEY_RIDES, rideCount)
            .apply()
    }
}
