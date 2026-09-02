package com.tymewear.karoo

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * with a suggestion waiting (see [RideEndPrompt]). It offers exactly the choices the
 * settings screen does — the same [SuggestionCard] — plus **Later**, which just closes:
 * nothing is dismissed, so the suggestion is still waiting in the settings app, and a
 * later ride end will offer it again.
 */
class SuggestionPromptActivity : ComponentActivity() {

    // Activity-scoped rather than remembered in the composition so [onNewIntent] can
    // refresh it: launchMode is singleTop, so a second ride end reuses this instance
    // instead of creating a new one, and without this the rider would be looking at the
    // previous ride's list.
    private var suggestions by mutableStateOf<List<Suggestion>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reload()
        setContent {
            AppTheme {
                val current = suggestions

                // One code path for every way the list can end up empty: handled by the
                // rider here, or already empty on arrival because auto-apply was switched
                // on — or the suggestion dismissed in settings — between the extension's
                // check and this Activity actually starting. A titled panel offering only
                // "Later" would be a puzzle, so close instead.
                LaunchedEffect(current.isEmpty()) {
                    if (current.isEmpty()) finish()
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
                        text = if (current.size > 1) "New threshold suggestions" else "New threshold suggestion",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                    )
                    for (s in current) {
                        SuggestionCard(
                            s = s,
                            onApply = { VentilatoryState.applySuggestion(applicationContext, s); reload() },
                            onDismiss = { VentilatoryState.dismissSuggestion(applicationContext, s); reload() },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: the extension's second launch lands here, not in onCreate.
        reload()
    }

    // Reload rather than drop the handled entry: applying VT1 can change what is worth
    // suggesting for VT2, and both apply and dismiss are filtered by prefs that
    // suggestions() re-reads.
    private fun reload() {
        suggestions = VentilatoryState.suggestions(applicationContext)
    }
}
