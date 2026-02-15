package com.tymewear.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SmoothingMode(val windowSize: Int, val label: String) {
    INSTANT(1, "VE"),
    SMOOTH_15(15, "VE 15s"),
    SMOOTH_30(30, "VE 30s"),
}

object SmoothingState {
    private val _mode = MutableStateFlow(SmoothingMode.SMOOTH_30)
    val mode = _mode.asStateFlow()

    fun cycle() {
        _mode.value = when (_mode.value) {
            SmoothingMode.INSTANT -> SmoothingMode.SMOOTH_15
            SmoothingMode.SMOOTH_15 -> SmoothingMode.SMOOTH_30
            SmoothingMode.SMOOTH_30 -> SmoothingMode.INSTANT
        }
    }
}

class SmoothingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SmoothingState.cycle()
    }

    companion object {
        const val ACTION = "com.tymewear.karoo.CYCLE_SMOOTHING"
    }
}
