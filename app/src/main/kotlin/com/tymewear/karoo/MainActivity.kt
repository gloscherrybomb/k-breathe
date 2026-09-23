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
        // Before the form reads anything: a 0.6.x install still holds its thresholds under
        // the old, one-step-off names.
        ThresholdPrefs.ensureMigrated(applicationContext)
        setContent {
            AppTheme {
                MainScreen(
                    onSave = { prefs ->
                        getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                            .edit()
                            .putString("sensor_id", prefs.sensorId)
                            .putFloat(ThresholdKeys.ENDURANCE, prefs.endurance)
                            .putFloat(ThresholdKeys.VT1, prefs.vt1)
                            .putFloat(ThresholdKeys.VT2, prefs.vt2)
                            .putFloat(ThresholdKeys.TOP_Z4, prefs.topZ4)
                            .putFloat(ThresholdKeys.VO2MAX, prefs.vo2max)
                            .putFloat("resting_br", prefs.restingBr)
                            .putFloat("max_br", prefs.maxBr)
                            .putFloat("max_hr", prefs.maxHr)
                            .putFloat("resting_hr", prefs.restingHr)
                            .putBoolean("dynamic_state_enabled", prefs.dynamicStateEnabled)
                            .apply()
                        // Reload thresholds for immediate effect
                        TymewearData.loadThresholds(applicationContext)
                    },
                    loadPrefs = {
                        val p = getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                        val t = ThresholdMigration.read(p.all)
                        PrefsData(
                            sensorId = p.getString("sensor_id", "") ?: "",
                            endurance = t.endurance.toFloat(),
                            vt1 = t.vt1.toFloat(),
                            vt2 = t.vt2.toFloat(),
                            topZ4 = t.topZ4.toFloat(),
                            vo2max = t.vo2max.toFloat(),
                            restingBr = p.getFloat("resting_br", Constants.DEFAULT_RESTING_BR),
                            maxBr = p.getFloat("max_br", Constants.DEFAULT_MAX_BR),
                            maxHr = p.getFloat("max_hr", Constants.DEFAULT_MAX_HR),
                            restingHr = p.getFloat("resting_hr", Constants.DEFAULT_RESTING_HR),
                            dynamicStateEnabled = p.getBoolean("dynamic_state_enabled", false),
                        )
                    },
                    onResetBaseline = {
                        VentilatoryState.resetBaseline(applicationContext)
                    },
                    loadBaselineStatus = {
                        VentilatoryState.persistedStatus(applicationContext)
                    },
                    loadLastRideScale = {
                        VentilatoryState.lastRideScale(applicationContext)
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
