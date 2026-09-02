package com.tymewear.karoo.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tymewear.karoo.Suggestion

/**
 * One threshold suggestion with its Apply / Dismiss choice.
 *
 * Shared by the settings screen and by [com.tymewear.karoo.SuggestionPromptActivity],
 * which shows the same choice on the Karoo when a ride ends — one wording, one layout,
 * so the two can't drift apart.
 */
@Composable
fun SuggestionCard(s: Suggestion, onApply: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Recent rides put ${s.kind} near ${"%.0f".format(s.suggestedVe)} " +
                    "L/min. Configured: ${"%.0f".format(s.currentVe)}.",
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onApply) {
                    Text("Apply")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}
