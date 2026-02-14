package com.tymewear.karoo

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

    fun update(data: Protocol.BreathingData) {
        _breathRate.value = data.breathRate
        _tidalVolume.value = data.tidalVolume
        _minuteVolume.value = data.minuteVolume
        _ieRatio.value = data.ieRatio
        _veZone.value = data.veZone
        _isConnected.value = true
    }

    fun setDisconnected() {
        _isConnected.value = false
    }
}
