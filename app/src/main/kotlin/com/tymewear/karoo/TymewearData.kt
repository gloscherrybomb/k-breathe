package com.tymewear.karoo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    private val _ieRatio = MutableStateFlow(0.0)
    val ieRatio: StateFlow<Double> = _ieRatio.asStateFlow()

    private val _veZone = MutableStateFlow(0)
    val veZone: StateFlow<Int> = _veZone.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

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

    // Zone thresholds (loaded from prefs)
    var enduranceThreshold = Constants.DEFAULT_ENDURANCE.toDouble()
        private set
    var vt1Threshold = Constants.DEFAULT_VT1.toDouble()
        private set
    var vt2Threshold = Constants.DEFAULT_VT2.toDouble()
        private set
    var vo2maxThreshold = Constants.DEFAULT_VO2MAX.toDouble()
        private set

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
        val prefs = context.getSharedPreferences("tymewear_prefs", Context.MODE_PRIVATE)
        enduranceThreshold = prefs.getFloat("endurance_threshold", Constants.DEFAULT_ENDURANCE).toDouble()
        vt1Threshold = prefs.getFloat("vt1_threshold", Constants.DEFAULT_VT1).toDouble()
        vt2Threshold = prefs.getFloat("vt2_threshold", Constants.DEFAULT_VT2).toDouble()
        vo2maxThreshold = prefs.getFloat("vo2max_threshold", Constants.DEFAULT_VO2MAX).toDouble()
        restingBr = prefs.getFloat("resting_br", Constants.DEFAULT_RESTING_BR).toDouble()
        maxBr = prefs.getFloat("max_br", Constants.DEFAULT_MAX_BR).toDouble()
        maxHr = prefs.getFloat("max_hr", Constants.DEFAULT_MAX_HR).toDouble()
        restingHr = prefs.getFloat("resting_hr", Constants.DEFAULT_RESTING_HR).toDouble()
    }

    fun update(data: Protocol.BreathingData) {
        _breathRate.value = data.breathRate
        _tidalVolume.value = data.tidalVolume
        _minuteVolume.value = data.minuteVolume
        _ieRatio.value = data.ieRatio
        // Compute zone from current thresholds
        _veZone.value = Protocol.veZone(
            data.minuteVolume,
            enduranceThreshold,
            vt1Threshold,
            vt2Threshold,
            vo2maxThreshold,
        )
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

    private fun recomputeMi() {
        val br = _breathRate.value
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
    }
}
