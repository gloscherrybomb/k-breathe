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
    val endurance: Float,
    val vt1: Float,
    val vt2: Float,
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
    var endurance by remember { mutableStateOf("69") }
    var vt1 by remember { mutableStateOf("83") }
    var vt2 by remember { mutableStateOf("111") }
    var vo2max by remember { mutableStateOf("180") }
    var restingBr by remember { mutableStateOf("12") }
    var maxBr by remember { mutableStateOf("55") }
    var maxHr by remember { mutableStateOf("190") }
    var restingHr by remember { mutableStateOf("60") }
    var saved by remember { mutableStateOf(false) }
    val isConnected by TymewearData.isConnected.collectAsState()

    LaunchedEffect(Unit) {
        val prefs = loadPrefs()
        sensorId = prefs.sensorId
        endurance = prefs.endurance.toString()
        vt1 = prefs.vt1.toString()
        vt2 = prefs.vt2.toString()
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
            value = endurance,
            onValueChange = { endurance = it; saved = false },
            label = { Text("Endurance threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z1 Recovery below, Z2 Endurance above") },
        )

        OutlinedTextField(
            value = vt1,
            onValueChange = { vt1 = it; saved = false },
            label = { Text("VT1 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z3 Tempo above") },
        )

        OutlinedTextField(
            value = vt2,
            onValueChange = { vt2 = it; saved = false },
            label = { Text("VT2 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z4 Threshold above") },
        )

        OutlinedTextField(
            value = vo2max,
            onValueChange = { vo2max = it; saved = false },
            label = { Text("VO2max threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Z5 VO2max above") },
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

        Button(
            onClick = {
                onSave(
                    PrefsData(
                        sensorId = sensorId,
                        endurance = endurance.toFloatOrNull() ?: Constants.DEFAULT_ENDURANCE,
                        vt1 = vt1.toFloatOrNull() ?: Constants.DEFAULT_VT1,
                        vt2 = vt2.toFloatOrNull() ?: Constants.DEFAULT_VT2,
                        vo2max = vo2max.toFloatOrNull() ?: Constants.DEFAULT_VO2MAX,
                        restingBr = restingBr.toFloatOrNull() ?: Constants.DEFAULT_RESTING_BR,
                        maxBr = maxBr.toFloatOrNull() ?: Constants.DEFAULT_MAX_BR,
                        maxHr = maxHr.toFloatOrNull() ?: Constants.DEFAULT_MAX_HR,
                        restingHr = restingHr.toFloatOrNull() ?: Constants.DEFAULT_RESTING_HR,
                    ),
                )
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved" else "Save Settings")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
