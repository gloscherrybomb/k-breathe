package com.tymewear.karoo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tymewear.karoo.screens.MainScreen
import com.tymewear.karoo.screens.PrefsData
import com.tymewear.karoo.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
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
                            endurance = p.getFloat("endurance_threshold", Constants.DEFAULT_ENDURANCE),
                            vt1 = p.getFloat("vt1_threshold", Constants.DEFAULT_VT1),
                            vt2 = p.getFloat("vt2_threshold", Constants.DEFAULT_VT2),
                            vo2max = p.getFloat("vo2max_threshold", Constants.DEFAULT_VO2MAX),
                            restingBr = p.getFloat("resting_br", Constants.DEFAULT_RESTING_BR),
                            maxBr = p.getFloat("max_br", Constants.DEFAULT_MAX_BR),
                            maxHr = p.getFloat("max_hr", Constants.DEFAULT_MAX_HR),
                            restingHr = p.getFloat("resting_hr", Constants.DEFAULT_RESTING_HR),
                        )
                    },
                )
            }
        }
    }

    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }
}
