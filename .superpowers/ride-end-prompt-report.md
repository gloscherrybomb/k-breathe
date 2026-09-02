# Ride-end prompt for threshold suggestions

Branch `feat/ride-end-prompt`. Feature: when a ride ends and the Beta has a new VT1/VT2
suggestion, offer Apply / Dismiss / Later on the Karoo itself rather than only in the
settings app.

## What changed

### New: pure decision helper
- `app/src/main/kotlin/com/tymewear/karoo/RideEndPrompt.kt:11` —
  `RideEndPrompt.shouldShow(rideEnded, betaEnabled, autoApply, suggestionCount)`.
  No Android imports, so it is a plain JVM unit under test. The four guards are
  commented in place (replayed `Idle`, Beta off, auto-apply already wrote the value,
  nothing to suggest).
- `app/src/test/kotlin/com/tymewear/karoo/RideEndPromptTest.kt` — 5 tests, one per
  branch of the rule.

### `onRideEnd` now reports whether a real ride ended
- `app/src/main/kotlin/com/tymewear/karoo/VentilatoryState.kt:262` — KDoc added.
- `app/src/main/kotlin/com/tymewear/karoo/VentilatoryState.kt:269` (`!lifecycle.onIdle()`)
  and `:286` (`!enabled`) return `false`.
- `app/src/main/kotlin/com/tymewear/karoo/VentilatoryState.kt:353` — `return true`,
  placed *after* the `synchronized(lock)` block so no Activity start can ever be
  triggered from under the pipeline lock. No other behaviour change; every existing
  ride-end test still passes.

### Extension hook
- `app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt:132` — the `RideState.Idle`
  branch became:
  ```kotlin
  val rideEnded = VentilatoryState.onRideEnd(applicationContext)
  maybePromptForSuggestions(rideEnded)
  ```
  The `Recording` and `Paused` branches are untouched, as is the second `RideState`
  collector in `startFit`.
- `app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt:151` —
  `maybePromptForSuggestions(rideEnded)` gathers the four facts, and on `true` starts
  `SuggestionPromptActivity` with `FLAG_ACTIVITY_NEW_TASK` inside
  `try { ... } catch (e: Exception) { Timber.w(...) }`. The KDoc records why: a Service
  has no task of its own, Android 10+ refuses most background activity starts, Karoo OS
  is Android 8 so it should work, and a refusal must stay silent apart from the log
  because the suggestion is still waiting in settings either way.

### Shared card
- `app/src/main/kotlin/com/tymewear/karoo/screens/SuggestionCard.kt:25` — the settings
  card extracted verbatim as `SuggestionCard(s, onApply, onDismiss)`.
- `app/src/main/kotlin/com/tymewear/karoo/screens/MainScreen.kt:365` — now calls it,
  with the same callbacks as before (`refreshThresholds()` after apply, `suggestions =
  loadSuggestions()` after dismiss). Same wording, same layout. The now-unused
  `androidx.compose.material3.Card` import was dropped.

### The prompt itself
- `app/src/main/kotlin/com/tymewear/karoo/SuggestionPromptActivity.kt` —
  `ComponentActivity` in `AppTheme`, like `MainActivity`. Title "New threshold
  suggestion" / "...suggestions" (>1), one `SuggestionCard` per suggestion loaded from
  `VentilatoryState.suggestions(applicationContext)`, and a full-width **Later**
  `OutlinedButton` that just calls `finish()` — nothing is dismissed, so the suggestion
  stays in settings. Apply and Dismiss both reload the list (applying VT1 can change
  what is worth suggesting for VT2) and `finish()` once it is empty. 16.dp padding,
  scrollable, body text at the M3 `bodyLarge` default of 16sp and Later at 16sp.
- `app/src/main/AndroidManifest.xml:39` — `<activity android:name=".SuggestionPromptActivity"
  android:exported="false" android:excludeFromRecents="true" android:launchMode="singleTop" />`
  (CRLF preserved to match the rest of that file).

### Docs and version
- `README.md:102` — one sentence added to the suggestions bullet: the Karoo shows a new
  suggestion there and then with Apply / Dismiss / Later; Later leaves it in settings.
- `app/build.gradle.kts:15` — `versionCode 12`, `versionName "0.6.1"`.
- `manifest.json` untouched (release is separate).

## TDD evidence

RED — test written before `RideEndPrompt` existed:

```
$ ./gradlew :app:testDebugUnitTest -q --tests 'com.tymewear.karoo.RideEndPromptTest'
e: .../RideEndPromptTest.kt:12:13 Unresolved reference 'RideEndPrompt'.
e: .../RideEndPromptTest.kt:26:13 Unresolved reference 'RideEndPrompt'.
e: .../RideEndPromptTest.kt:38:13 Unresolved reference 'RideEndPrompt'.
e: .../RideEndPromptTest.kt:50:13 Unresolved reference 'RideEndPrompt'.
e: .../RideEndPromptTest.kt:62:13 Unresolved reference 'RideEndPrompt'.
Execution failed for task ':app:compileDebugUnitTestKotlin'.
BUILD FAILED in 1s
```

GREEN — after adding `RideEndPrompt.kt`, same command exited 0 with no output.

## Build / test

```
$ ./gradlew :app:assembleDebug -q      # exit 0
$ ./gradlew :app:testDebugUnitTest -q  # exit 0
```

Tallied from `app/build/test-results/testDebugUnitTest/*.xml` (15 files):
`tests=102 failures=0 errors=0 skipped=0` — the expected 97 + 5.

## Self-review

- Manifest entry present with all three attributes.
- `MainScreen` behaviour unchanged: identical text, identical callbacks; only the
  card's markup moved to a shared composable and one dead import went away.
- The extension's collector still handles `Recording` (re-dispatch `RequestBluetooth`,
  `onRideStart`) and `Paused` (`onRidePause`) exactly as before; only the `Idle` branch
  grew a line.
- Nothing that starts an Activity runs under `VentilatoryState.lock`: `onRideEnd`
  returns after releasing it, and the launch happens in the extension afterwards.

## Concerns

- **Untestable on the JVM, unverified on hardware.** The Activity, the manifest flag and
  the background-activity-start behaviour are all Android-side; no Karoo was attached, so
  this is compile-verified only. The first real ride is the actual test: does the prompt
  appear over the ride-summary screen, and is it dismissible with the Karoo's buttons
  rather than needing a touch?
- **Timing against the Karoo's own ride-end UI.** `RideState.Idle` arrives while the head
  unit is doing its own save/summary flow; the prompt may land on top of that, or be
  pushed behind it. Worth watching on the first ride.
- **`suggestions()` reads prefs on the main thread** inside composition (`remember { … }`)
  and again in each button handler. Consistent with what `MainScreen` already does, and
  the data is tiny, but it is main-thread I/O.
- **No back-press handling beyond the default.** Back finishes the Activity, which is the
  same outcome as Later — fine, but it means back does not dismiss the suggestion, which
  is the intended (conservative) behaviour rather than an accident.
- `versionName` is bumped but `manifest.json` is not, so the in-repo release manifest
  still advertises 0.6.0 until the release step updates it.
