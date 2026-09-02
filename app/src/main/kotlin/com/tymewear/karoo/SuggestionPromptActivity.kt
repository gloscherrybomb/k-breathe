package com.tymewear.karoo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tymewear.karoo.screens.SuggestionCard
import com.tymewear.karoo.theme.AppTheme

/**
 * The ride-end threshold prompt, shown on the Karoo itself.
 *
 * karoo-ext has no dialog API, so the extension starts this Activity when a ride ends
 * with a suggestion the rider has not seen (see `RideEndPrompt`). It offers exactly the
 * choices the settings screen does — the same [SuggestionCard] — plus **Later**, which
 * just closes: nothing is dismissed, so the suggestion is still waiting in the settings
 * app. Once every suggestion has been applied or dismissed there is nothing left to
 * show and the Activity finishes itself.
 */
class SuggestionPromptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var suggestions by remember {
                    mutableStateOf(VentilatoryState.suggestions(applicationContext))
                }

                // Reload rather than remove the handled one: applying VT1 can change what
                // is worth suggesting for VT2, and both apply and dismiss are filtered by
                // prefs that suggestions() re-reads.
                fun refresh() {
                    suggestions = VentilatoryState.suggestions(applicationContext)
                    if (suggestions.isEmpty()) finish()
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (suggestions.size > 1) "New threshold suggestions" else "New threshold suggestion",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                    )
                    for (s in suggestions) {
                        SuggestionCard(
                            s = s,
                            onApply = { VentilatoryState.applySuggestion(applicationContext, s); refresh() },
                            onDismiss = { VentilatoryState.dismissSuggestion(applicationContext, s); refresh() },
                        )
                    }
                    // Full-width so it is an easy target on the Karoo's small screen, and
                    // last so a stray tap lands here rather than on Apply.
                    OutlinedButton(
                        onClick = { finish() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Later", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
