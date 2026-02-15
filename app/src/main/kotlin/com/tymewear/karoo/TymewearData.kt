package com.tymewear.karoo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // Zone thresholds (loaded from prefs)
    var enduranceThreshold = 69.0
        private set
    var vt1Threshold = 83.0
        private set
    var vt2Threshold = 111.0
        private set
    var vo2maxThreshold = 180.0
        private set

    // MI parameters (loaded from prefs)
    var restingBr = 12.0
        private set
    var maxBr = 55.0
        private set
    var maxHr = 190.0
        private set
    var restingHr = 60.0
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
        enduranceThreshold = prefs.getFloat("endurance_threshold", 69f).toDouble()
        vt1Threshold = prefs.getFloat("vt1_threshold", 83f).toDouble()
        vt2Threshold = prefs.getFloat("vt2_threshold", 111f).toDouble()
        vo2maxThreshold = prefs.getFloat("vo2max_threshold", 180f).toDouble()
        restingBr = prefs.getFloat("resting_br", 12f).toDouble()
        maxBr = prefs.getFloat("max_br", 55f).toDouble()
        maxHr = prefs.getFloat("max_hr", 190f).toDouble()
        restingHr = prefs.getFloat("resting_hr", 60f).toDouble()
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
