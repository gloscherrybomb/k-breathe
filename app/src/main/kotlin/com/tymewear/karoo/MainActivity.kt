package com.tymewear.karoo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tymewear.karoo.screens.MainScreen
import com.tymewear.karoo.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MainScreen(
                    onSave = { sensorId, vt1, vt2 ->
                        getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                            .edit()
                            .putString("sensor_id", sensorId)
                            .putFloat("vt1_threshold", vt1)
                            .putFloat("vt2_threshold", vt2)
                            .apply()
                    },
                    loadPrefs = {
                        val prefs = getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                        Triple(
                            prefs.getString("sensor_id", "") ?: "",
                            prefs.getFloat("vt1_threshold", 40f),
                            prefs.getFloat("vt2_threshold", 70f),
                        )
                    },
                )
            }
        }
    }
}
