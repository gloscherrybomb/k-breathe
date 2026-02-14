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
import androidx.compose.foundation.text.KeyboardOptions
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
import com.tymewear.karoo.TymewearData

@Composable
fun MainScreen(
    onSave: (sensorId: String, vt1: Float, vt2: Float) -> Unit,
    loadPrefs: () -> Triple<String, Float, Float>,
) {
    var sensorId by remember { mutableStateOf("") }
    var vt1 by remember { mutableStateOf("40") }
    var vt2 by remember { mutableStateOf("70") }
    var saved by remember { mutableStateOf(false) }
    val isConnected by TymewearData.isConnected.collectAsState()

    LaunchedEffect(Unit) {
        val (id, t1, t2) = loadPrefs()
        sensorId = id
        vt1 = t1.toString()
        vt2 = t2.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
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

        Spacer(modifier = Modifier.height(8.dp))

        // Sensor ID
        OutlinedTextField(
            value = sensorId,
            onValueChange = { sensorId = it.take(4); saved = false },
            label = { Text("Sensor ID (4-digit code)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // VT1 Threshold
        OutlinedTextField(
            value = vt1,
            onValueChange = { vt1 = it; saved = false },
            label = { Text("VT1 Threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        // VT2 Threshold
        OutlinedTextField(
            value = vt2,
            onValueChange = { vt2 = it; saved = false },
            label = { Text("VT2 Threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onSave(
                    sensorId,
                    vt1.toFloatOrNull() ?: 40f,
                    vt2.toFloatOrNull() ?: 70f,
                )
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved" else "Save Settings")
        }
    }
}
