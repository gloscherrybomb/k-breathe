package com.tymewear.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GraphSmoothingMode(val windowSize: Int, val label: String) {
    SMOOTH_15(15, "15s"),
    SMOOTH_30(30, "30s"),
    SMOOTH_60(60, "60s"),
}

object GraphSmoothingState {
    private val _mode = MutableStateFlow(GraphSmoothingMode.SMOOTH_30)
    val mode = _mode.asStateFlow()

    fun cycle() {
        _mode.value = when (_mode.value) {
            GraphSmoothingMode.SMOOTH_15 -> GraphSmoothingMode.SMOOTH_30
            GraphSmoothingMode.SMOOTH_30 -> GraphSmoothingMode.SMOOTH_60
            GraphSmoothingMode.SMOOTH_60 -> GraphSmoothingMode.SMOOTH_15
        }
    }
}

class GraphSmoothingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        GraphSmoothingState.cycle()
    }
}
