package com.tymewear.karoo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** Baseline status read straight from prefs, for callers (the settings screen) that may
 *  run in a process where [VentilatoryState.load] has never executed. */
data class BaselineStatus(
    val coveredBins: Int,
    val rideCount: Int,
    val updatedAtMs: Long,
)

/**
 * Owns the session-scale pipeline across a ride: the Android-side shell around
 * [SessionPipeline] — persistence, the ride lifecycle, thread safety and the flows the
 * data fields collect.
 *
 * Two baselines persist between rides, one keyed by power and one by heart rate. That is
 * what makes today's numbers "today versus my recent normal" rather than "today versus
 * the start of this ride". Concretely, a ride's own samples are buffered by the pipeline,
 * not folded into the baselines, until the ride ends: scoring a ride against a baseline
 * that this same ride has been feeding all along would drag the reported deviation toward
 * zero over exactly the long steady efforts the feature exists to evaluate. At ride end
 * each buffered sample is divided by the ride's strap scale before folding in, so the
 * baselines stay on one internal scale reference (spec §3.4).
 *
 * The two published numbers come apart deliberately: [scale] is the sensor's volume scale
 * for today, estimated at matched heart rate, and [dayQuality] is what is left once that
 * is removed — the physiological signal (spec §3.1).
 *
 * `onSample` runs on the power-stream collector coroutine; `onRideStart`/`onRideEnd` run
 * on the RideState collector coroutine. Both are dispatched on `Dispatchers.IO`, a
 * multi-threaded pool, so the two can run concurrently — every mutating entry point is
 * guarded by [lock].
 */
object VentilatoryState {

    private const val PREFS = "tymewear_prefs"
    private const val KEY_BASELINE = "baseline_bins"          // power, 20 W bins
    private const val KEY_HR_BASELINE = "baseline_hr_bins"    // heart rate, 5 bpm bins
    private const val KEY_UPDATED = "baseline_updated_at"
    private const val KEY_RIDES = "baseline_ride_count"
    private const val KEY_ENABLED = "dynamic_state_enabled"
    private const val KEY_LAST_SCALE = "last_ride_scale"      // float; 0 = none
    private const val KEY_LAST_DAY_QUALITY = "last_ride_day_quality"

    private val lock = Any()

    private var powerBaseline = VeBaseline()
    private var hrBaseline = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
    private var rideCount = 0
    private var pipeline = newPipeline()

    /** 1 Hz ticks this ride has been recording for — pause does not advance it, so the
     *  pipeline's scale window counts recording time only (spec §7). */
    private var recordingSeconds = 0

    private var lastRideScale: Double? = null
    private var lastRideDayQuality: Double? = null

    // Read without the lock from data-field coroutines via isEnabled(); written under
    // `lock` from load()/reloadEnabledFlag(). @Volatile makes the unsynchronised read safe.
    @Volatile
    private var enabled = false

    /** Tracks active/paused across Idle/Paused/Recording transitions and tells apart a
     *  fresh start from a resume — pure logic, unit-tested on its own in
     *  RideLifecycleTest since a resume must clear pause without resetting the pipeline,
     *  and getting both right at once is exactly where a prior fix regressed. */
    private val lifecycle = RideLifecycle()

    /** Today's strap scale, e.g. 0.80 for a strap reading 20 % low. Null until it locks. */
    private val _scale = MutableStateFlow<Double?>(null)
    val scale: StateFlow<Double?> = _scale.asStateFlow()

    private val _scaleStatus = MutableStateFlow<ScaleStatus>(ScaleStatus.Calibrating)
    val scaleStatus: StateFlow<ScaleStatus> = _scaleStatus.asStateFlow()

    /** Today versus the rider's normal for the same effort, in %, once the strap scale
     *  has been removed. Negative is the good direction. */
    private val _dayQuality = MutableStateFlow<Double?>(null)
    val dayQuality: StateFlow<Double?> = _dayQuality.asStateFlow()

    private val _baselineBins = MutableStateFlow(0)
    val baselineBins: StateFlow<Int> = _baselineBins.asStateFlow()

    private fun newPipeline() = SessionPipeline(
        powerBaseline,
        hrBaseline,
        rideCount,
        Constants.STATE_MIN_BASELINE_BINS,
        Constants.STATE_MIN_BASELINE_RIDES,
    )

    fun isEnabled(): Boolean = enabled

    fun load(context: Context) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            enabled = prefs.getBoolean(KEY_ENABLED, false)
            rideCount = prefs.getInt(KEY_RIDES, 0)
            // A stored power baseline with no heart-rate baseline beside it came from the
            // 0.5.0 sampler: different steady-state rules, and never scale-normalised, so
            // its bins are not comparable with what this pipeline produces. Recalibrating
            // from scratch is the only honest option — a mixed-scale baseline would make
            // every strap-scale estimate confidently wrong. Persisted immediately so the
            // settings screen (which reads prefs, not this object) stops reporting bins
            // that no longer exist.
            val migrating = !prefs.contains(KEY_HR_BASELINE) && prefs.contains(KEY_BASELINE)
            if (migrating) {
                powerBaseline = VeBaseline()
                hrBaseline = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
                rideCount = 0
                lastRideScale = null
                lastRideDayQuality = null
                Timber.i("Baseline from 0.5.0 discarded; recalibrating with the session-scale pipeline")
            } else {
                powerBaseline = VeBaseline.deserialise(prefs.getString(KEY_BASELINE, "") ?: "")
                hrBaseline = VeBaseline.deserialise(
                    prefs.getString(KEY_HR_BASELINE, "") ?: "",
                    binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH,
                )
                lastRideScale = prefs.getFloat(KEY_LAST_SCALE, 0f).takeIf { it > 0f }?.toDouble()
                lastRideDayQuality = prefs.getString(KEY_LAST_DAY_QUALITY, null)?.toDoubleOrNull()
            }
            pipeline = newPipeline()
            _baselineBins.value = powerBaseline.coveredBins()
            if (migrating) persist(context)
            Timber.d(
                "VentilatoryState loaded: enabled=$enabled powerBins=${powerBaseline.coveredBins()} " +
                    "hrBins=${hrBaseline.coveredBins()} rides=$rideCount",
            )
        }
    }

    /**
     * Feed one 1 Hz tick: the Karoo's power, the rider's heart rate, and breathing read
     * from [TymewearData].
     *
     * Breathing is only used when fresh — a stale value is indistinguishable from a real
     * one and would make the numbers confident fiction. [LoadGate] enforces the same
     * contract internally: it clears its VE window on a null reading so a sensor dropout
     * can never surface a frozen pre-dropout average as a real sample.
     */
    fun onSample(loadW: Double?, hrBpm: Double?) {
        synchronized(lock) {
            // lifecycle.isActive guards against accumulating samples while no ride is
            // recording — e.g. a trainer idling with power streaming. Without it the
            // pipeline grows unboundedly and publishes numbers that onRideStart then
            // wipes, so the rider sees a value vanish the moment they press record.
            if (!enabled || !lifecycle.isActive || lifecycle.isPaused) return
            recordingSeconds++
            val fresh = TymewearData.isDataFresh()
            val ve = if (fresh) TymewearData.smoothMinuteVolume.value.takeIf { it > 0.0 } else null
            val out = pipeline.onSample(
                loadW,
                hrBpm?.takeIf { it > 0.0 },
                ve,
                System.currentTimeMillis(),
                recordingSeconds,
            )
            _scale.value = out.scale
            _scaleStatus.value = out.scaleStatus
            _dayQuality.value = out.dayQualityPercent
        }
    }

    /**
     * A real ride start resets the pipeline for a fresh effort. A resume from pause
     * (RideState goes Paused -> Recording, which also passes through here) must not
     * reset anything — [RideLifecycle.onRecording] is what tells the two apart.
     *
     * [context] is optional so pure-logic callers (tests) can exercise the lifecycle
     * transition without an Android [Context]. When provided, a fresh start also
     * re-reads the enabled flag — see [reloadEnabledFlag] for why that is necessary.
     */
    fun onRideStart(context: Context? = null) {
        synchronized(lock) {
            val freshStart = lifecycle.onRecording()
            if (!freshStart) return
            if (context != null) reloadEnabledFlag(context)
            pipeline = newPipeline()
            recordingSeconds = 0
            _scale.value = null
            _scaleStatus.value = ScaleStatus.Calibrating
            _dayQuality.value = null
        }
    }

    /**
     * Re-reads only the enabled flag from prefs, leaving the baselines and in-flight ride
     * state untouched.
     *
     * [load] runs exactly once, in the extension's `onCreate`. If the rider flips the
     * "Enable ventilatory state" toggle in the settings app, that change lives only in
     * SharedPreferences until something re-reads it — without this, the toggle would
     * silently do nothing until the extension process happened to restart, which looks
     * indistinguishable from a broken toggle. Calling this at the start of every fresh
     * ride (see [onRideStart]) closes that gap without the blunt, state-destroying
     * effect of re-running [load] (which would also reset the in-memory baselines to
     * whatever was last persisted, clobbering anything accumulated since).
     */
    fun reloadEnabledFlag(context: Context) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            enabled = prefs.getBoolean(KEY_ENABLED, false)
        }
    }

    /** Stops samples from reaching the pipeline or the baselines while the rider is
     *  stopped, without discarding what has already been buffered for this ride. */
    fun onRidePause() {
        synchronized(lock) { lifecycle.onPaused() }
    }

    fun onRideEnd(context: Context) {
        synchronized(lock) {
            // No-op unless a ride is actually active: guards against
            // consumerFlow<RideState>() replaying the current state on a cold
            // subscribe, which would otherwise fire an Idle transition (and so this
            // method) with nothing having started, spuriously incrementing rideCount
            // and re-persisting an unchanged baseline.
            if (!lifecycle.onIdle()) return
            // Normalise out today's strap scale before folding in, so the baselines stay
            // on one internal reference (spec §3.4). A ride whose scale never locked is
            // folded in as-is: 1.0 is the honest assumption when nothing was measured.
            val factor = _scale.value ?: 1.0
            for (s in pipeline.rideSamples) {
                powerBaseline.update(s.loadW, s.ve / factor)
                hrBaseline.update(s.hrBpm, s.ve / factor)
            }
            lastRideScale = _scale.value
            lastRideDayQuality = _dayQuality.value
            rideCount += 1
            _baselineBins.value = powerBaseline.coveredBins()
            persist(context)
            pipeline = newPipeline()
            // These held this ride's numbers while it was recording; leaving them set
            // between rides would show the last ride's scale and day quality as if they
            // were current while the rider is standing still. summaryForFit() reads the
            // saved copies instead, so the FIT session summary is unaffected.
            _scale.value = null
            _scaleStatus.value = ScaleStatus.Calibrating
            _dayQuality.value = null
            Timber.d(
                "VentilatoryState saved: powerBins=${powerBaseline.coveredBins()} " +
                    "hrBins=${hrBaseline.coveredBins()} rides=$rideCount scale=$lastRideScale",
            )
        }
    }

    fun resetBaseline(context: Context) {
        synchronized(lock) {
            powerBaseline = VeBaseline()
            hrBaseline = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
            rideCount = 0
            lastRideScale = null
            lastRideDayQuality = null
            pipeline = newPipeline()
            _baselineBins.value = 0
            persist(context)
        }
    }

    /**
     * Baseline status read straight from prefs rather than in-memory state, for a
     * settings-screen launch that has no running extension in this process to have
     * populated [baselineBins] via [load] — a cold start from the launcher icon after
     * process death, most commonly. Deliberately does not call [load]: that would
     * overwrite the in-memory baselines out from under a ride that is actively recording
     * in this same process.
     */
    fun persistedStatus(context: Context): BaselineStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val persisted = VeBaseline.deserialise(prefs.getString(KEY_BASELINE, "") ?: "")
        return BaselineStatus(
            coveredBins = persisted.coveredBins(),
            rideCount = prefs.getInt(KEY_RIDES, 0),
            updatedAtMs = prefs.getLong(KEY_UPDATED, 0L),
        )
    }

    /** The strap scale the last completed ride settled on, read from prefs for the same
     *  reason as [persistedStatus]. Null when no ride has locked a scale yet. */
    fun lastRideScale(context: Context): Double? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_LAST_SCALE, 0f).takeIf { it > 0f }?.toDouble()

    /** For the FIT session summary: this ride's values while recording, else the last
     *  ride's — the summary is written after the Idle transition has already cleared the
     *  live flows. */
    fun summaryForFit(): Pair<Double?, Double?> = synchronized(lock) {
        if (lifecycle.isActive) _scale.value to _dayQuality.value else lastRideScale to lastRideDayQuality
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASELINE, powerBaseline.serialise())
            .putString(KEY_HR_BASELINE, hrBaseline.serialise())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .putInt(KEY_RIDES, rideCount)
            .putFloat(KEY_LAST_SCALE, (lastRideScale ?: 0.0).toFloat())
            // Stored as text, not a float: 0 % day quality is a real reading ("exactly my
            // normal"), so it cannot double as the absent marker the way scale 0 can.
            .putString(KEY_LAST_DAY_QUALITY, lastRideDayQuality?.toString())
            .apply()
    }
}
