package com.tymewear.karoo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tymewear.karoo.Constants
import com.tymewear.karoo.TymewearData

data class PrefsData(
    val sensorId: String,
    val vt1: Float,
    val vt2: Float,
    val topZ4: Float,
    val vo2max: Float,
    val restingBr: Float,
    val maxBr: Float,
    val maxHr: Float,
    val restingHr: Float,
)

@Composable
fun MainScreen(
    onSave: (PrefsData) -> Unit,
    loadPrefs: () -> PrefsData,
) {
    var sensorId by remember { mutableStateOf("") }
    var vt1 by remember { mutableStateOf("83") }
    var vt2 by remember { mutableStateOf("111") }
    var topZ4 by remember { mutableStateOf("128") }
    var vo2max by remember { mutableStateOf("180") }
    var restingBr by remember { mutableStateOf("12") }
    var maxBr by remember { mutableStateOf("55") }
    var maxHr by remember { mutableStateOf("190") }
    var restingHr by remember { mutableStateOf("60") }
    var saved by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val isConnected by TymewearData.isConnected.collectAsState()

    LaunchedEffect(Unit) {
        val prefs = loadPrefs()
        sensorId = prefs.sensorId
        vt1 = prefs.vt1.toString()
        vt2 = prefs.vt2.toString()
        topZ4 = prefs.topZ4.toString()
        vo2max = prefs.vo2max.toString()
        restingBr = prefs.restingBr.toString()
        maxBr = prefs.maxBr.toString()
        maxHr = prefs.maxHr.toString()
        restingHr = prefs.restingHr.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tymewear VitalPro",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Connection status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (isConnected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                        shape = MaterialTheme.shapes.small,
                    ),
            )
            Text(
                text = if (isConnected) "Connected" else "Not connected",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Sensor ID
        OutlinedTextField(
            value = sensorId,
            onValueChange = { sensorId = it.take(4); saved = false },
            label = { Text("Sensor ID (4-digit code)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Ventilation Zone Thresholds ---
        Text(
            text = "Ventilation Zone Thresholds",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OutlinedTextField(
            value = vt1,
            onValueChange = { vt1 = it; saved = false },
            label = { Text("VT1 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z1 Endurance below, Z2 VT1 above") },
        )

        OutlinedTextField(
            value = vt2,
            onValueChange = { vt2 = it; saved = false },
            label = { Text("VT2 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z3 VT2 above") },
        )

        OutlinedTextField(
            value = topZ4,
            onValueChange = { topZ4 = it; saved = false },
            label = { Text("Top Z4 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z4 Top Z4 above") },
        )

        OutlinedTextField(
            value = vo2max,
            onValueChange = { vo2max = it; saved = false },
            label = { Text("VO2max threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z5 VO2Max above") },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Mobilization Index Parameters ---
        Text(
            text = "Mobilization Index",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OutlinedTextField(
            value = restingBr,
            onValueChange = { restingBr = it; saved = false },
            label = { Text("Resting BR (bpm)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        OutlinedTextField(
            value = maxBr,
            onValueChange = { maxBr = it; saved = false },
            label = { Text("Max BR (bpm)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        OutlinedTextField(
            value = maxHr,
            onValueChange = { maxHr = it; saved = false },
            label = { Text("Max HR (bpm)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        OutlinedTextField(
            value = restingHr,
            onValueChange = { restingHr = it; saved = false },
            label = { Text("Resting HR (bpm)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (validationError != null) {
            Text(
                text = validationError!!,
                color = Color(0xFFEF5350),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Button(
            onClick = {
                val v1Val = vt1.toFloatOrNull()
                val v2Val = vt2.toFloatOrNull()
                val tz4Val = topZ4.toFloatOrNull()
                val voVal = vo2max.toFloatOrNull()
                val rBrVal = restingBr.toFloatOrNull()
                val mBrVal = maxBr.toFloatOrNull()
                val mHrVal = maxHr.toFloatOrNull()
                val rHrVal = restingHr.toFloatOrNull()

                val error = when {
                    v1Val == null || v2Val == null || tz4Val == null || voVal == null ||
                        rBrVal == null || mBrVal == null || mHrVal == null || rHrVal == null ->
                        "All fields must be valid numbers."
                    v1Val <= 0 || v2Val <= 0 || tz4Val <= 0 || voVal <= 0 ||
                        rBrVal <= 0 || mBrVal <= 0 || mHrVal <= 0 || rHrVal <= 0 ->
                        "All values must be positive."
                    v1Val >= v2Val || v2Val >= tz4Val || tz4Val >= voVal ->
                        "Thresholds must be in order: VT1 < VT2 < Top Z4 < VO2max."
                    rBrVal >= mBrVal ->
                        "Resting BR must be less than Max BR."
                    rHrVal >= mHrVal ->
                        "Resting HR must be less than Max HR."
                    else -> null
                }

                if (error != null) {
                    validationError = error
                    saved = false
                } else {
                    validationError = null
                    onSave(
                        PrefsData(
                            sensorId = sensorId,
                            vt1 = v1Val!!,
                            vt2 = v2Val!!,
                            topZ4 = tz4Val!!,
                            vo2max = voVal!!,
                            restingBr = rBrVal!!,
                            maxBr = mBrVal!!,
                            maxHr = mHrVal!!,
                            restingHr = rHrVal!!,
                        ),
                    )
                    saved = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved" else "Save Settings")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
