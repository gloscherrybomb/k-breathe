package com.tymewear.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Toggles MI display between percentage and battery modes.
 */
enum class MiDisplayMode { PERCENTAGE, BATTERY }

object MiDisplayState {
    private val _mode = MutableStateFlow(MiDisplayMode.PERCENTAGE)
    val mode = _mode.asStateFlow()

    fun cycle() {
        _mode.value = when (_mode.value) {
            MiDisplayMode.PERCENTAGE -> MiDisplayMode.BATTERY
            MiDisplayMode.BATTERY -> MiDisplayMode.PERCENTAGE
        }
    }
}

class MiDisplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MiDisplayState.cycle()
    }

    companion object {
        const val ACTION = "com.tymewear.karoo.CYCLE_MI_DISPLAY"
    }
}
