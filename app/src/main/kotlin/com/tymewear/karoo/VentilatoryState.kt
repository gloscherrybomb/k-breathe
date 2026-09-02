package com.tymewear.karoo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** Baseline status read straight from prefs, for callers (the settings screen) that may
 *  run in a process where [VentilatoryState.load] has never executed. */
data class BaselineStatus(
    val coveredBins: Int,
    val coveredHrBins: Int,
    val rideCount: Int,
    val updatedAtMs: Long,
)

/** One applied threshold change, both VE values in L/min, for the settings screen's
 *  revertible history. */
data class ThresholdChange(val kind: ThresholdKind, val fromVe: Double, val toVe: Double, val atMs: Long) {
    fun serialise(): String = "${kind.name}:$fromVe:$toVe:$atMs"

    companion object {
        /** Tolerant by design, like [Breakpoints.deserialise]: a corrupt preference must
         *  leave the rider with no history entry, never crash the extension. */
        fun deserialise(s: String): ThresholdChange? {
            val f = s.split(":")
            if (f.size != 4) return null
            val kind = runCatching { ThresholdKind.valueOf(f[0]) }.getOrNull() ?: return null
            val fromVe = f[1].toDoubleOrNull() ?: return null
            val toVe = f[2].toDoubleOrNull() ?: return null
            val atMs = f[3].toLongOrNull() ?: return null
            return ThresholdChange(kind, fromVe, toVe, atMs)
        }
    }
}

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
    private const val KEY_EVIDENCE_HISTORY = "threshold_evidence_history"
    private const val KEY_AUTO_APPLY = "threshold_auto_apply"
    private const val KEY_DISMISSED_VT1 = "threshold_dismissed_vt1"
    private const val KEY_DISMISSED_VT2 = "threshold_dismissed_vt2"
    private const val KEY_CHANGE_HISTORY = "threshold_change_history"

    private const val MAX_EVIDENCE = 8
    private const val MAX_CHANGES = 5

    private val lock = Any()

    private var powerBaseline = VeBaseline()
    private var hrBaseline = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
    private var rideCount = 0
    private var pipeline = newPipeline()

    /** Last [MAX_EVIDENCE] rides' pooled-curve breakpoints (spec §6), oldest first. */
    private var evidenceHistory: List<Breakpoints> = emptyList()

    /** 1 Hz ticks this ride has been recording for — pause does not advance it, so the
     *  pipeline's scale window counts recording time only (spec §7). */
    private var recordingSeconds = 0

    private var lastRideScale: Double? = null
    private var lastRideDayQuality: Double? = null

    /** True when [lastRideScale] holds a raw *out-of-range* factor rather than a locked
     *  scale. The settings hint wants that number (spec §7), but the FIT session must not:
     *  the recorded zones were never corrected by it, and spec §2.3 leaves
     *  `tyme_ve_scale` out whenever the scale did not lock. In memory only, because it
     *  only ever describes the ride that just ended in this process. */
    private var lastRideScaleOutOfRange = false

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
            val prefs = prefs(context)
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
                lastRideScaleOutOfRange = false
                lastRideDayQuality = null
                evidenceHistory = emptyList()
                Timber.i("Baseline from 0.5.0 discarded; recalibrating with the session-scale pipeline")
            } else {
                powerBaseline = VeBaseline.deserialise(prefs.getString(KEY_BASELINE, "") ?: "")
                hrBaseline = VeBaseline.deserialise(
                    prefs.getString(KEY_HR_BASELINE, "") ?: "",
                    binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH,
                )
                lastRideScale = prefs.getFloat(KEY_LAST_SCALE, 0f).takeIf { it > 0f }?.toDouble()
                lastRideDayQuality = prefs.getString(KEY_LAST_DAY_QUALITY, null)?.toDoubleOrNull()
                evidenceHistory = loadEvidenceHistoryFrom(prefs)
            }
            pipeline = newPipeline()
            // A discarded 0.5.0 baseline has no history behind it, so it must not claim
            // one: writing "updated just now" would have the settings screen report a
            // baseline that is 0 minutes old and empty. 0 reads as "never" there.
            if (migrating) persist(context, updatedAtMs = 0L)
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
     * contract internally for both streams: it clears its VE window and its heart-rate
     * window on a null reading, so a sensor or strap dropout can never surface a frozen
     * pre-dropout average as a real sample.
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
            enabled = prefs(context).getBoolean(KEY_ENABLED, false)
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
            // The lifecycle transition above is consumed either way, but a Beta-off ride
            // must not count: onSample fed nothing into the pipeline, so folding in and
            // advancing rideCount would credit the baseline with a ride that contributed
            // no samples. A handful of those would walk rideCount past
            // STATE_MIN_BASELINE_RIDES, and the second Beta-on ride would then be scored
            // against a "baseline" that is really just one other day — exactly what that
            // gate exists to prevent. It would also rewrite baseline_updated_at, telling
            // the settings screen the baseline is fresher than it is.
            if (!enabled) return
            // A measured factor outside SessionScale's clamp means the strap was probably
            // not worn correctly (spec §3.3, §7), so this ride is known-bad data and none
            // of it may reach the baselines: folding in a ride's worth of mis-scaled
            // samples would corrupt the one internal reference every future scale estimate
            // is measured against, and counting the ride would walk rideCount toward the
            // confidence gate on evidence that is worthless. The raw figure is still
            // recorded and persisted below, because telling the rider "check the strap" is
            // exactly what §7 asks for.
            //
            // Unreachable on the calibration rides: OutOfRange requires a confident devHR,
            // which SessionPipeline only produces once the baseline holds
            // STATE_MIN_BASELINE_RIDES rides, so the first two rides always end Calibrating.
            val outOfRange = _scaleStatus.value as? ScaleStatus.OutOfRange
            if (outOfRange == null) {
                // Normalise out today's strap scale before folding in, so the baselines stay
                // on one internal reference (spec §3.4). A ride whose scale never locked is
                // folded in as-is: 1.0 is the honest assumption when nothing was measured.
                val factor = _scale.value ?: 1.0
                for (s in pipeline.rideSamples) {
                    powerBaseline.update(s.loadW, s.ve / factor)
                    hrBaseline.update(s.hrBpm, s.ve / factor)
                }
                rideCount += 1
                // Threshold evidence (spec §6): fit this ride's pooled curve, fold it into
                // the rolling history, and — only if the rider opted in — silently apply
                // whatever the updated history now agrees on. Inside the same guard as the
                // fold-in on purpose: the fit is taken from the pooled baseline, so after an
                // out-of-range ride (which changed nothing) it would only re-append the
                // previous ride's breakpoints and pad §6's "three rides agree" window with a
                // duplicate.
                val bp = ThresholdEvidence.estimate(powerBaseline.bins())
                evidenceHistory = (evidenceHistory + bp).takeLast(MAX_EVIDENCE)
                if (isAutoApply(context)) {
                    // Reload the configured thresholds first, for the same reason
                    // suggestions() does: in a process where the rider has edited them since
                    // onCreate, TymewearData's in-memory copy is stale, and a suggestion
                    // compared against a stale value can be silently applied over a number
                    // the rider just chose.
                    TymewearData.loadThresholds(context)
                    for (s in suggestionsFrom(evidenceHistory, TymewearData.configuredThresholds(), context)) {
                        apply(context, s)
                    }
                }
            }
            // For an out-of-range ride the last-ride record is the *raw* measured factor,
            // not null: lastRideScale(context) and the settings hint ("read N % high/low —
            // check strap tension and position") are the only place the rider ever learns
            // the strap misread, so throwing the number away would hide the one thing worth
            // acting on (spec §7).
            lastRideScale = outOfRange?.raw ?: _scale.value
            lastRideScaleOutOfRange = outOfRange != null
            lastRideDayQuality = _dayQuality.value
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
            lastRideScaleOutOfRange = false
            lastRideDayQuality = null
            // Evidence was fitted from the baseline this just discarded; keeping it
            // around would let a stale breakpoint keep suggesting changes against data
            // that no longer exists.
            evidenceHistory = emptyList()
            pipeline = newPipeline()
            // Mirrors onRideStart: a reset can land mid-ride, and the published flows and
            // the scale window's clock all describe numbers derived from the baseline that
            // was just discarded. Leaving them set would keep a locked scale on screen —
            // and correcting the zone colours by it — with nothing left behind it.
            recordingSeconds = 0
            _scale.value = null
            _scaleStatus.value = ScaleStatus.Calibrating
            _dayQuality.value = null
            persist(context)
        }
    }

    /**
     * Baseline status read straight from prefs rather than in-memory state, for a
     * settings-screen launch that has no running extension in this process to have run
     * [load] — a cold start from the launcher icon after process death, most commonly.
     * Deliberately does not call [load] itself: that would overwrite the in-memory
     * baselines out from under a ride that is actively recording in this same process.
     */
    fun persistedStatus(context: Context): BaselineStatus {
        val prefs = prefs(context)
        val persisted = VeBaseline.deserialise(prefs.getString(KEY_BASELINE, "") ?: "")
        val persistedHr = VeBaseline.deserialise(
            prefs.getString(KEY_HR_BASELINE, "") ?: "",
            binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH,
        )
        return BaselineStatus(
            coveredBins = persisted.coveredBins(),
            coveredHrBins = persistedHr.coveredBins(),
            rideCount = prefs.getInt(KEY_RIDES, 0),
            updatedAtMs = prefs.getLong(KEY_UPDATED, 0L),
        )
    }

    /** The strap scale the last completed ride settled on, read from prefs for the same
     *  reason as [persistedStatus]. Null when no ride has locked a scale yet. */
    fun lastRideScale(context: Context): Double? =
        prefs(context).getFloat(KEY_LAST_SCALE, 0f).takeIf { it > 0f }?.toDouble()

    /**
     * Suggestions the persisted evidence history currently supports, filtered against
     * whatever the rider has dismissed. Reads straight from prefs — like
     * [persistedStatus] — so it works from a cold process the settings screen may open
     * with no running extension in it.
     */
    fun suggestions(context: Context): List<Suggestion> = synchronized(lock) {
        // TymewearData's configured thresholds are plain in-memory fields, populated by
        // the extension's onCreate — absent in a process that cold-started straight into
        // this settings screen. Reload them from prefs first so a suggestion is never
        // compared against a stale default instead of what the rider actually entered.
        TymewearData.loadThresholds(context)
        suggestionsFrom(loadEvidenceHistoryFrom(prefs(context)), TymewearData.configuredThresholds(), context)
    }

    /** Applies one suggestion: writes the configured threshold, records the change, and
     *  clears that kind's dismissed value so a later, different suggestion is not
     *  filtered by an unrelated dismissal. */
    fun applySuggestion(context: Context, s: Suggestion) {
        synchronized(lock) { apply(context, s) }
    }

    /** Remembers [s]'s suggested value as dismissed for its kind, so it is filtered out
     *  by [suggestionsFrom] until the estimate moves by
     *  [ThresholdEvidence.DEFAULT_DISMISS_TOLERANCE_VE]. */
    fun dismissSuggestion(context: Context, s: Suggestion) {
        synchronized(lock) {
            val key = if (s.kind == ThresholdKind.VT1) KEY_DISMISSED_VT1 else KEY_DISMISSED_VT2
            prefs(context).edit().putFloat(key, s.suggestedVe.toFloat()).apply()
        }
    }

    /** Most-recently-applied change last, per [MAX_CHANGES]. */
    fun changeHistory(context: Context): List<ThresholdChange> = synchronized(lock) {
        loadChangeHistoryFrom(prefs(context))
    }

    /** Undoes one applied change: writes its `fromVe` back, removes it from the history,
     *  and dismisses `toVe` for that kind — otherwise the same suggestion the rider just
     *  undid would reappear on the next ride-end evidence update, since the pooled
     *  evidence that produced it hasn't gone anywhere. */
    fun revert(context: Context, change: ThresholdChange) {
        synchronized(lock) {
            val prefs = prefs(context)
            val key = if (change.kind == ThresholdKind.VT1) "vt1_threshold" else "vt2_threshold"
            val dismissKey = if (change.kind == ThresholdKind.VT1) KEY_DISMISSED_VT1 else KEY_DISMISSED_VT2
            val changes = loadChangeHistoryFrom(prefs).filterNot { it == change }
            prefs.edit()
                .putFloat(key, change.fromVe.toFloat())
                .putFloat(dismissKey, change.toVe.toFloat())
                .putString(KEY_CHANGE_HISTORY, changes.joinToString("|") { it.serialise() })
                .apply()
            TymewearData.loadThresholds(context)
        }
    }

    fun isAutoApply(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_APPLY, false)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadEvidenceHistoryFrom(prefs: SharedPreferences): List<Breakpoints> {
        val raw = prefs.getString(KEY_EVIDENCE_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").mapNotNull { Breakpoints.deserialise(it) }
    }

    private fun loadChangeHistoryFrom(prefs: SharedPreferences): List<ThresholdChange> {
        val raw = prefs.getString(KEY_CHANGE_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").mapNotNull { ThresholdChange.deserialise(it) }
    }

    /** [ThresholdEvidence.suggestions] on [history]/[configured], with whatever the
     *  rider has dismissed for each kind and anything that would break threshold
     *  ordering filtered back out — both pure rules, delegated to
     *  [ThresholdEvidence.filterSuggestions] so they are testable without preferences. */
    private fun suggestionsFrom(history: List<Breakpoints>, configured: ZoneThresholds, context: Context): List<Suggestion> {
        val prefs = prefs(context)
        return ThresholdEvidence.filterSuggestions(
            ThresholdEvidence.suggestions(history, configured),
            configured,
            dismissedVe(prefs, ThresholdKind.VT1),
            dismissedVe(prefs, ThresholdKind.VT2),
        )
    }

    private fun dismissedVe(prefs: SharedPreferences, kind: ThresholdKind): Double? {
        val key = if (kind == ThresholdKind.VT1) KEY_DISMISSED_VT1 else KEY_DISMISSED_VT2
        return if (prefs.contains(key)) prefs.getFloat(key, 0f).toDouble() else null
    }

    /** Writes one suggestion's threshold, appends the change to history, clears that
     *  kind's dismissal, and reloads [TymewearData] so the new threshold takes effect
     *  immediately. Called both from the settings screen (via [applySuggestion]) and,
     *  under auto-apply, from [onRideEnd] — both already hold [lock] when this runs.
     *  Safe to call while holding it: [TymewearData.loadThresholds] only reads prefs
     *  and plain [TymewearData] fields, it never calls back into [VentilatoryState].
     *
     *  Re-checks the ordering rule against the *current* configured thresholds rather
     *  than trusting the caller's filtered list: the configuration can have moved since
     *  the suggestion was computed — most concretely, an earlier suggestion in the same
     *  auto-apply batch may have just changed the other threshold. Silently skips (with
     *  a log) rather than writing a value that would break `VT1 < VT2 < TopZ4`. */
    private fun apply(context: Context, s: Suggestion) {
        val prefs = prefs(context)
        TymewearData.loadThresholds(context)
        val configured = TymewearData.configuredThresholds()
        if (ThresholdEvidence.filterSuggestions(listOf(s), configured, null, null).isEmpty()) {
            Timber.w("Skipping threshold suggestion $s: would break ordering against current thresholds $configured")
            return
        }
        val key = if (s.kind == ThresholdKind.VT1) "vt1_threshold" else "vt2_threshold"
        val dismissKey = if (s.kind == ThresholdKind.VT1) KEY_DISMISSED_VT1 else KEY_DISMISSED_VT2
        val change = ThresholdChange(s.kind, s.currentVe, s.suggestedVe, System.currentTimeMillis())
        val changes = (loadChangeHistoryFrom(prefs) + change).takeLast(MAX_CHANGES)
        prefs.edit()
            .putFloat(key, s.suggestedVe.toFloat())
            .remove(dismissKey)
            .putString(KEY_CHANGE_HISTORY, changes.joinToString("|") { it.serialise() })
            .apply()
        TymewearData.loadThresholds(context)
    }

    /** For the FIT session summary: this ride's values while recording, else the last
     *  ride's — the summary is written after the Idle transition has already cleared the
     *  live flows. */
    fun summaryForFit(): Pair<Double?, Double?> = synchronized(lock) {
        if (lifecycle.isActive) {
            _scale.value to _dayQuality.value
        } else {
            (if (lastRideScaleOutOfRange) null else lastRideScale) to lastRideDayQuality
        }
    }

    /** [updatedAtMs] is overridable only for the 0.5.0 migration, which persists an empty
     *  baseline and must not stamp it as freshly updated. */
    private fun persist(context: Context, updatedAtMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString(KEY_BASELINE, powerBaseline.serialise())
            .putString(KEY_HR_BASELINE, hrBaseline.serialise())
            .putLong(KEY_UPDATED, updatedAtMs)
            .putInt(KEY_RIDES, rideCount)
            .putFloat(KEY_LAST_SCALE, (lastRideScale ?: 0.0).toFloat())
            // Stored as text, not a float: 0 % day quality is a real reading ("exactly my
            // normal"), so it cannot double as the absent marker the way scale 0 can.
            .putString(KEY_LAST_DAY_QUALITY, lastRideDayQuality?.toString())
            .putString(KEY_EVIDENCE_HISTORY, evidenceHistory.joinToString("|") { it.serialise() })
            .apply()
    }
}
