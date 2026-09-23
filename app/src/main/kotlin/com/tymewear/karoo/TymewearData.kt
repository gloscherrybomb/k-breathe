package com.tymewear.karoo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.LinkedList

/** Fixed-capacity rolling buffer for computing moving averages. */
class RollingBuffer(private val capacity: Int) {
    private val buf = LinkedList<Double>()
    fun add(value: Double) {
        buf.addLast(value)
        if (buf.size > capacity) buf.removeFirst()
    }
    fun average(): Double = if (buf.isEmpty()) 0.0 else buf.sum() / buf.size
    fun clear() { buf.clear() }
}

data class ZoneTimes(
    val z1: Long = 0, val z2: Long = 0, val z3: Long = 0,
    val z4: Long = 0, val z5: Long = 0, val total: Long = 0,
) {
    operator fun get(zone: Int): Long = when (zone) {
        1 -> z1; 2 -> z2; 3 -> z3; 4 -> z4; 5 -> z5; else -> 0
    }
    val max: Long get() = maxOf(z1, z2, z3, z4, z5)
}

/**
 * Shared state holder for the latest breathing data from the VitalPro sensor.
 * Used by data types and FIT recording to access current values.
 */
object TymewearData {

    private val _breathRate = MutableStateFlow(0.0)
    val breathRate: StateFlow<Double> = _breathRate.asStateFlow()

    private val _tidalVolume = MutableStateFlow(0.0)
    val tidalVolume: StateFlow<Double> = _tidalVolume.asStateFlow()

    private val _minuteVolume = MutableStateFlow(0.0)
    val minuteVolume: StateFlow<Double> = _minuteVolume.asStateFlow()

    // 8-breath rolling averages for display smoothing (~40s at rest, ~12s at hard effort)
    private val brBuffer = RollingBuffer(8)
    private val tvBuffer = RollingBuffer(8)

    private val _smoothBreathRate = MutableStateFlow(0.0)
    val smoothBreathRate: StateFlow<Double> = _smoothBreathRate.asStateFlow()

    private val _smoothTidalVolume = MutableStateFlow(0.0)
    val smoothTidalVolume: StateFlow<Double> = _smoothTidalVolume.asStateFlow()

    private val _smoothMinuteVolume = MutableStateFlow(0.0)
    val smoothMinuteVolume: StateFlow<Double> = _smoothMinuteVolume.asStateFlow()

    private val _ieRatio = MutableStateFlow(0.0)
    val ieRatio: StateFlow<Double> = _ieRatio.asStateFlow()

    private val _veZone = MutableStateFlow(0)
    val veZone: StateFlow<Int> = _veZone.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Tracks whether the values above reflect live sensor data or a stale last-known
    // reading. A "connected" GATT can stop delivering notifications silently, so
    // isConnected alone is not enough to trust the numbers.
    private val freshness = DataFreshness(Constants.BLE_DATA_STALENESS_TIMEOUT_MS)

    /**
     * True when breathing data is live. Callers that record or display values MUST
     * check this: writing a stale value produces data indistinguishable from a real
     * reading, which is how hours of frozen VE ended up in recorded FIT files.
     */
    fun isDataFresh(nowMs: Long = System.currentTimeMillis()): Boolean = freshness.isFresh(nowMs)

    /** Age of the newest breathing packet, or null if none has arrived. */
    fun dataAgeMs(nowMs: Long = System.currentTimeMillis()): Long? = freshness.ageMs(nowMs)

    private val _batteryPercent = MutableStateFlow(-1)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    fun updateBattery(percent: Int) { _batteryPercent.value = percent }

    private val _powerW = MutableStateFlow<Double?>(null)
    /** Latest Karoo power sample, null when the power stream is unavailable. Independent of the Beta. */
    val powerW: StateFlow<Double?> = _powerW.asStateFlow()
    fun updatePower(w: Double?) { _powerW.value = w }

    // Zone time tracking (live, for TimeInZonesDataType display)
    private val _zoneTimes = MutableStateFlow(ZoneTimes())
    val zoneTimes: StateFlow<ZoneTimes> = _zoneTimes.asStateFlow()

    fun incrementZoneTime(zone: Int) {
        _zoneTimes.update { current ->
            when (zone) {
                1 -> current.copy(z1 = current.z1 + 1, total = current.total + 1)
                2 -> current.copy(z2 = current.z2 + 1, total = current.total + 1)
                3 -> current.copy(z3 = current.z3 + 1, total = current.total + 1)
                4 -> current.copy(z4 = current.z4 + 1, total = current.total + 1)
                5 -> current.copy(z5 = current.z5 + 1, total = current.total + 1)
                else -> current
            }
        }
    }

    fun resetZoneTimes() {
        _zoneTimes.value = ZoneTimes()
    }

    // Zone thresholds under Tymewear's names (loaded from prefs)
    @Volatile
    private var configured = ZoneThresholds(
        endurance = Constants.DEFAULT_ENDURANCE.toDouble(),
        vt1 = Constants.DEFAULT_VT1.toDouble(),
        vt2 = Constants.DEFAULT_VT2.toDouble(),
        topZ4 = Constants.DEFAULT_TOP_Z4.toDouble(),
        vo2max = Constants.DEFAULT_VO2MAX.toDouble(),
    )

    /** The rider's entered thresholds, untouched. Settings shows and edits these; the
     *  zones classify against them (scaled, when the Beta has a strap scale). */
    fun configuredThresholds(): ZoneThresholds = configured

    /**
     * The thresholds every zone consumer classifies against: the configured ones scaled
     * by today's strap scale once the Beta is on and the scale has locked (spec §3.2),
     * the configured ones otherwise.
     *
     * Read per call rather than cached, so a scale that locks or eases mid-ride reaches
     * the colours without anything having to invalidate a cache. [_veZone] in particular
     * is recomputed on every breathing packet, so a scale change propagates within one
     * breath.
     */
    fun currentThresholds(): ZoneThresholds =
        ZoneClassifier.effectiveThresholds(
            configuredThresholds(),
            if (VentilatoryState.isEnabled()) VentilatoryState.scale.value else null,
        )

    /** Zone for a VE value the caller is presenting or recording. Callers pass the value
     *  they actually show, so the number and its zone can never disagree. */
    fun zoneFor(ve: Double): Int = ZoneClassifier.zoneFor(ve, currentThresholds())

    // MI parameters (loaded from prefs)
    var restingBr = Constants.DEFAULT_RESTING_BR.toDouble()
        private set
    var maxBr = Constants.DEFAULT_MAX_BR.toDouble()
        private set
    var maxHr = Constants.DEFAULT_MAX_HR.toDouble()
        private set
    var restingHr = Constants.DEFAULT_RESTING_HR.toDouble()
        private set

    // HR/MI state
    private val _heartRate = MutableStateFlow(0.0)
    val heartRate: StateFlow<Double> = _heartRate.asStateFlow()

    private val _percentHrr = MutableStateFlow(0.0)
    val percentHrr: StateFlow<Double> = _percentHrr.asStateFlow()

    private val _mobilizationIndex = MutableStateFlow(0.0)
    val mobilizationIndex: StateFlow<Double> = _mobilizationIndex.asStateFlow()

    private val _percentBrr = MutableStateFlow(0.0)
    val percentBrr: StateFlow<Double> = _percentBrr.asStateFlow()

    /**
     * Load zone thresholds and MI parameters from SharedPreferences.
     */
    fun loadThresholds(context: Context) {
        ThresholdPrefs.ensureMigrated(context)
        val prefs = context.getSharedPreferences(ThresholdPrefs.PREFS, Context.MODE_PRIVATE)
        configured = ThresholdMigration.read(prefs.all)
        restingBr = prefs.getFloat("resting_br", Constants.DEFAULT_RESTING_BR).toDouble()
        maxBr = prefs.getFloat("max_br", Constants.DEFAULT_MAX_BR).toDouble()
        maxHr = prefs.getFloat("max_hr", Constants.DEFAULT_MAX_HR).toDouble()
        restingHr = prefs.getFloat("resting_hr", Constants.DEFAULT_RESTING_HR).toDouble()
    }

    fun update(data: Protocol.BreathingData) {
        freshness.recordUpdate(System.currentTimeMillis())
        _breathRate.value = data.breathRate
        _tidalVolume.value = data.tidalVolume
        _minuteVolume.value = data.minuteVolume
        _ieRatio.value = data.ieRatio

        // Feed rolling buffers and update smoothed values
        brBuffer.add(data.breathRate)
        tvBuffer.add(data.tidalVolume)
        val smoothBr = brBuffer.average()
        val smoothTv = tvBuffer.average()
        _smoothBreathRate.value = smoothBr
        _smoothTidalVolume.value = smoothTv
        _smoothMinuteVolume.value = smoothBr * smoothTv

        // Zone from the smoothed VE this object publishes, via the one classifier.
        _veZone.value = zoneFor(_smoothMinuteVolume.value)
        _isConnected.value = true
        // Recompute MI if we have HR data
        recomputeMi()
    }

    /**
     * Update heart rate from Karoo system stream and recompute %HRR and MI.
     */
    fun updateHr(hr: Double) {
        _heartRate.value = hr
        // Compute %HRR from raw HR
        val hrRange = maxHr - restingHr
        _percentHrr.value = if (hrRange > 0 && hr > 0) {
            ((hr - restingHr) / hrRange * 100.0).coerceAtLeast(0.0)
        } else {
            0.0
        }
        recomputeMi()
    }

    /**
     * The heart-rate stream reported no reading. Zeroing is the honest answer, and the
     * same rule breathing data already follows: a latched last-known HR is
     * indistinguishable from a live one, so leaving it in place would let a mid-ride
     * dropout feed a frozen value to the Mobilization Index and, through
     * [VentilatoryState.onSample], into the heart-rate deviation the strap scale is
     * estimated from — and thence into the persisted heart-rate baseline at ride end.
     * Callers downstream already read 0.0 as "no data".
     */
    fun clearHr() {
        _heartRate.value = 0.0
        _percentHrr.value = 0.0
        recomputeMi()
    }

    private fun recomputeMi() {
        val br = _smoothBreathRate.value
        val hr = _heartRate.value
        val hrr = _percentHrr.value
        val brRange = maxBr - restingBr
        if (brRange <= 0 || hr <= 0.0 || br <= 0.0) {
            _percentBrr.value = 0.0
            _mobilizationIndex.value = 0.0
            return
        }
        val brr = ((br - restingBr) / brRange) * 100.0
        _percentBrr.value = brr.coerceAtLeast(0.0)
        // Guard against division by very small %HRR (< 1%) to avoid wild MI values
        _mobilizationIndex.value = if (hrr >= 1.0) (brr / hrr) * 100.0 else 0.0
    }

    fun setDisconnected() {
        _isConnected.value = false
        _batteryPercent.value = -1
        freshness.reset()
        brBuffer.clear()
        tvBuffer.clear()
        // Zero the user-facing flows so a dropped connection shows "--" instead
        // of the last cached value (which looks like a freeze).
        _breathRate.value = 0.0
        _tidalVolume.value = 0.0
        _minuteVolume.value = 0.0
        _smoothBreathRate.value = 0.0
        _smoothTidalVolume.value = 0.0
        _smoothMinuteVolume.value = 0.0
        // Otherwise the pre-ride zone chart keeps accruing time against whatever zone
        // was last live before the disconnect — see TimeInZonesDataType.
        _veZone.value = 0
    }
}
