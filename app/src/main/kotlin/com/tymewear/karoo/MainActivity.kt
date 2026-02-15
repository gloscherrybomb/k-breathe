package com.tymewear.karoo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tymewear.karoo.screens.MainScreen
import com.tymewear.karoo.screens.PrefsData
import com.tymewear.karoo.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MainScreen(
                    onSave = { prefs ->
                        getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                            .edit()
                            .putString("sensor_id", prefs.sensorId)
                            .putFloat("endurance_threshold", prefs.endurance)
                            .putFloat("vt1_threshold", prefs.vt1)
                            .putFloat("vt2_threshold", prefs.vt2)
                            .putFloat("vo2max_threshold", prefs.vo2max)
                            .putFloat("resting_br", prefs.restingBr)
                            .putFloat("max_br", prefs.maxBr)
                            .putFloat("max_hr", prefs.maxHr)
                            .putFloat("resting_hr", prefs.restingHr)
                            .apply()
                        // Reload thresholds for immediate effect
                        TymewearData.loadThresholds(applicationContext)
                    },
                    loadPrefs = {
                        val p = getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                        PrefsData(
                            sensorId = p.getString("sensor_id", "") ?: "",
                            endurance = p.getFloat("endurance_threshold", 69f),
                            vt1 = p.getFloat("vt1_threshold", 83f),
                            vt2 = p.getFloat("vt2_threshold", 111f),
                            vo2max = p.getFloat("vo2max_threshold", 180f),
                            restingBr = p.getFloat("resting_br", 12f),
                            maxBr = p.getFloat("max_br", 55f),
                            maxHr = p.getFloat("max_hr", 190f),
                            restingHr = p.getFloat("resting_hr", 60f),
                        )
                    },
                )
            }
        }
    }
}
