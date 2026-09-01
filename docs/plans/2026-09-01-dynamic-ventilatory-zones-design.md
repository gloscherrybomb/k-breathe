# Dynamic Ventilatory Zones — Design Spec

**Date:** 2026-09-01
**Status:** Design — awaiting review before implementation planning
**Feature:** Live, on-Karoo dynamic ventilatory threshold detection and zone display — k-breathe's differentiating capability.

---

## 1. Goal & Motivation

Give k-breathe the ability to **detect a rider's ventilatory thresholds (VT1, VT2) on the Karoo itself** from Tymewear breathing data plus the head unit's power stream, and drive live zone display from those detected thresholds — with the rider's manually-entered values as a permanent fallback.

**Why this is the USP.** Investigation (2026-09-01, recorded in memory `dynamic-zones-spike`) established:

- Tymewear's own "Dynamic Zones" (app v1.5.2, ~June 2026) are a **server-side CNN that runs on a completed, uploaded workout** — no bundled model, labelled Beta. It is architecturally incapable of running live on a head unit, which is why neither their Garmin CIQ field nor their July 2026 Wahoo ELEMNT integration shows dynamic zones live. They display only static, manually-entered zones.
- The intervals.icu community repeatedly asks for dynamic zones *live on-device* and cannot get them.
- A **classical, explainable estimator** (three-segment piecewise-linear fit of VE-vs-power) recovers thresholds robustly from the user's own ramp data — see §4 and the validation in §9.

k-breathe doing this **live and on-device** is therefore not a copy of Tymewear; it is a capability their design cannot deliver.

## 2. Scope

**In scope (v1):**

- Tier 1 — on-Karoo threshold detection, two entry points sharing one engine:
  - **Guided test mode:** a data-page field that coaches a clean ramp.
  - **Passive detection:** any ride containing a sustained progressive effort.
- Tier 2 — conservative, confidence-gated live dynamic zones + a new breathing-drift data field.
- Confirmation UX in the app's own settings screen; Beta labelling; static-zone fallback.

**Out of scope (v1):**

- **Running / non-cycling.** The Karoo is a bike computer; detection uses VE-vs-**power**. The estimator interface takes an abstract "load" signal so a future VE-vs-pace variant can slot in, but no running code ships in v1.
- **Continuous mid-ride threshold re-estimation.** Explicitly rejected: it is the noisy regime where even Tymewear's CNN produces spurious readings (forum-confirmed). Thresholds hold during a ride; they change only on a fresh high-confidence detection.
- **Cloud / server round-trips.** All computation is on-device.
- **Auto-apply without confirmation.** v1 always asks; opt-in auto-apply is a documented follow-up.

## 3. Hard Constraints

- **A power meter must be paired to the Karoo** for detection to run. Without power, the app stays on manual/static zones with no regression and no error state — detection simply never triggers.
- The app already consumes BLE breathing data. It must additionally consume the Karoo's **power** stream via karoo-ext (`KarooSystemService.streamDataFlow` for the power data type). This is the one new platform integration.
- No new karoo-ext APIs are required; current dependency (1.1.8) suffices. A separate housekeeping bump to 1.1.9 (manifest tags) is independent of this feature.

## 4. The Estimator (core algorithm)

Ported verbatim in behaviour from the validated Python prototype.

**Input:** a sequence of `(load, ve)` samples from a progressive effort, where `load` = power (W) and `ve` = minute ventilation (L/min).

**Method:**

1. Causal moving-average smooth of VE (default 20 s window), NaN-tolerant.
2. Isolate the ramp: samples up to peak smoothed power.
3. Bin VE by power step (default 10 W); take the median VE per bin → a clean monotonic `(power, VE)` curve.
4. Fit a **continuous three-segment piecewise-linear** model `VE = f(power)` by grid-searching two breakpoints (ordered, ≥5% of the load range apart) and solving hinge-basis least squares at each candidate pair; keep the lowest-SSE fit.
5. **Physiological sanity gates** (a fit failing any of these returns `null`):
   - all three segment slopes positive;
   - slope₂ ≥ 0.8·slope₁ and slope₃ > slope₂ (VE rise accelerates through the thresholds);
   - VT1 power < VT2 power;
   - minimum load spread and minimum sample count/duration.
6. **Output:** `ThresholdResult(vt1Ve, vt2Ve, vt1Power, vt2Power, confidence)`.

**Confidence** is derived from fit quality (residual R² of the piecewise fit), load spread, effort duration, and breakpoint separation, normalised to 0–1. A single `CONFIDENCE_MIN` constant gates whether a detection is offered.

**Determinism:** pure function of its inputs, no I/O, no clock — directly unit-testable against fixtures.

## 5. Components (new Kotlin files, `app/src/main/kotlin/com/tymewear/karoo/`)

| File | Responsibility | Depends on |
|------|----------------|-----------|
| `ThresholdEstimator.kt` | §4 pure estimator. `fun estimate(samples: List<LoadVe>): ThresholdResult?` | nothing (pure) |
| `RampDetector.kt` | Causal online detector: ingest live `(power, VE)` at 1 Hz, decide when a sustained progressive effort has occurred, hand a window to the estimator. Rejects sprint/wind transients (requires sustained rise over minutes, not spikes). | `ThresholdEstimator` |
| `DynamicZones.kt` | "Active threshold" resolver: returns detected thresholds when present & `use_dynamic` & confident, else static prefs. Single source all zone computations read. | prefs |
| `DriftTracker.kt` | Rolling BR/VE drift % vs a reference window (first sustained window / rolling baseline). | nothing (pure) |
| `ThresholdTestDataType.kt` | Guided-test data field: renders stage #, target watts on a stage timer, live VE, progress (bitmap pattern per `VeGraphDataType`). Coaching only. | `RampDetector` |
| `BreathingDriftDataType.kt` | Data field surfacing `DriftTracker` output. | `DriftTracker` |

**Modified existing files:**

- `TymewearData.kt` — zone computation reads `DynamicZones.active()` instead of the static `vt1Threshold…` fields directly; add plumbing for the power stream and detection lifecycle. Static fields remain as the fallback source.
- `TymewearExtension.kt` — subscribe to the Karoo power stream (`karooSystem.streamDataFlow(DataType.Type.POWER)`, same pattern as the existing `HEART_RATE`/`ELAPSED_TIME` subscriptions — confirm the exact `DataType.Type` constant when wiring it); own the `RampDetector`/`DriftTracker` lifecycle across a ride; register the two new data types; route the `startFit` zone through `DynamicZones.active()`.
- `Constants.kt` — new tunables (smoothing window, bin step, confidence floor, min duration/spread, drift reference window, drift alert %).
- `screens/MainScreen.kt` — "Use dynamic zones (Beta)" toggle, active-threshold readout with source/date, the pending-detection confirmation card, the configurable ramp protocol (start/step/stage), and the drift-alert toggle.
- `README.md` — document the new fields, guided test, and the power-meter requirement.

### 5.1 Improvements to existing data fields (in-scope)

Reading the current code surfaced issues that would directly undermine confidence in dynamic zones, so they are fixed as part of this work (not as unrelated refactoring):

- **Unify VE-zone computation (correctness).** VE zone is currently computed in **four** places, from different VE values and threshold reads:
  1. `TymewearData.veZone` — from an 8-breath average (`smoothBr * smoothTv`) against the loaded static thresholds.
  2. `VentilationDataType.startStream` — its own 30 s numeric buffer.
  3. `VentilationDataType.startView` — displays VE smoothed to the tapped mode (instant/15 s/30 s) but colours the background from `TymewearData.veZone` (i.e. a *different* smoothing than the number shown).
  4. `TymewearExtension.startFit` — computes zone from **raw, unsmoothed** `minuteVolume` with its own prefs read; this zone feeds `incrementZoneTime`, so the **time-in-zone chart, FIT per-record `ve_zone`, and the session-summary zone breakdown are all based on raw-VE zone** — which can differ from what the rider saw live.

  Fix: one classification path. Zone is derived from a single defined VE series (the display uses the value it shows; recording uses a consistently-smoothed value) classified against `DynamicZones.active()`. This removes the display/record divergence and is a prerequisite for dynamic zones being trustworthy — both live and in the recorded file.
- **Route all zone lookups through `DynamicZones.active()`.** `VentilationDataType`, `VeGraphDataType`, and `TimeInZonesDataType` currently read the static thresholds (directly or via `TymewearData.veZone`). They move to the resolver so detected thresholds flow through with no per-field change, and so a threshold change (detection applied, or settings edited) is reflected without restarting the ride.
- **Remove the duplicated `zoneStyle` shim** in `VentilationDataType`'s companion (delegates to `Constants.zoneStyle`) once callers use `Constants` directly.
- **VE graph zone bands** redraw against active thresholds so the graph's coloured bands match the live zone.

## 6. Data Flow

```
BLE breathing ─┐
               ├─► TymewearData (VE / BR / TV StateFlows)   [existing]
Karoo power  ──┘             │
   (karoo-ext stream)        ▼
                       RampDetector ──(qualifying window)──► ThresholdEstimator
                             │                                      │
                             ▼                                      ▼
                       DriftTracker                        DetectedThresholds
                             │                            (VE, power, confidence, date)
                             ▼                                      │
                    BreathingDrift field           store as PENDING in prefs; offer in app
                                                                    │  (user Apply)
        DynamicZones.active() ◄──────────────────────────────────── │
                             │                                       │
                             ▼                                       ▼
     VentilationDataType / VeGraphDataType / TimeInZonesDataType   applied dynamic thresholds
                 (colour by active zones — no field-level change)
```

## 7. Detection & Confirmation UX

**Guided test.** Rider adds the *Threshold Test* field to a data page and follows the on-screen ramp. The protocol is **user-configurable** in settings — start watts, watts-per-stage, and stage duration — defaulting to **70 W start, +20 W/stage, 180 s stages** (matching Tymewear's protocol). On stop, the estimator fits the recorded window and stores a *pending* result.

**Passive.** During any ride with a power meter, `RampDetector` watches for a sustained progressive effort (e.g. a long climb) and runs the same fit silently when one completes.

**Confirmation (the pending → apply flow).** When the estimator produces a valid, confident result, k-breathe does **not** change your zones on its own. It stores the result as a *pending* detection and shows a card in `MainScreen` the next time the app is opened:

> **Thresholds detected** — VT1 53 · VT2 80 L/min · from 1 Sep ride · confidence 0.86
> [ Apply ]  [ Dismiss ]

- **Apply** copies the pending values into the *active dynamic thresholds* (and flips `use_dynamic` on if it was off). From then on, zones colour by the detected thresholds.
- **Dismiss** discards the pending result; nothing changes.
- Your manually-entered *static* values are never overwritten, and the Beta toggle always switches back to them.

This confirm-before-apply choice is deliberate: a rider should never discover mid-ride that their zones silently moved. Automatic apply above a confidence bar is a documented opt-in follow-up (§14), not v1 behaviour.

## 8. Tier 2 — Live Behaviour (conservative)

- Live VE field, VE graph, and time-in-zone colour by `DynamicZones.active()`. Because those fields already colour by threshold, this is a resolver swap, not a rendering change.
- Active thresholds **hold for the duration of a ride**; they change only when a new high-confidence detection is applied. No mid-ride re-estimation.
- `BreathingDriftDataType` runs live throughout, implementing the community "BR up >11–15% vs first interval = cooked" heuristic as a first-class field. **Display-only by default**; an optional setting (`drift_alert_enabled`, off by default) turns on an in-ride alert when drift crosses a configurable threshold.

## 9. Validation Evidence (spike, on user's real data)

Estimator run on the user's 2026-02-21 ramp (peak 311 W), via intervals.icu streams (`TymeVentilation` = VE):

| Threshold | Power | VE (L/min) | HR |
|-----------|-------|-----------|-----|
| VT1 | ~109 W | 52.7 | 116 |
| VT2 | ~217 W | ~80 | 150 |

Robustness across smoothing (10/20/30 s) × power-bin (5/10/15 W): **VT1 VE 52.6–53.4 (±0.4), VT2 VE 80–85 (±5).** Causal 30 s classifier on other rides behaved correctly (long easy ride 100% Z1, zero drift; track intervals 81% top-zone, +8% drift). Full detail in memory `dynamic-zones-spike`.

## 10. Persistence

Extend the existing `tymewear_prefs` SharedPreferences:

- Keep: `vt1_threshold`, `vt2_threshold`, `topz4_threshold`, `vo2max_threshold` (static manual, the fallback).
- Add (dynamic thresholds): `dynamic_vt1_ve`, `dynamic_vt2_ve`, `dynamic_detected_at` (epoch), `dynamic_confidence`, `use_dynamic` (bool, default false), and `pending_vt1_ve` / `pending_vt2_ve` / `pending_detected_at` / `pending_confidence` for an unconfirmed detection.
- Add (guided-test config): `ramp_start_w` (default 70), `ramp_step_w` (default 20), `ramp_stage_s` (default 180).
- Add (drift): `drift_alert_enabled` (bool, default false), `drift_alert_pct` (default 12).

Zone computation currently reads only the static keys; after this change it reads them via `DynamicZones.active()`.

## 11. Safety & Error Handling

- **Confidence gating** (min duration, min load spread, R², monotonicity) rejects poor or physiologically-implausible fits before they are ever offered.
- **Transient rejection:** `RampDetector` requires sustained progression, filtering out sprints and wind-driven VE spikes (the failure mode the forum documents for Tymewear's model).
- **Fallback is total:** any missing/low-confidence/toggle-off condition falls back to static zones. Zones are never undefined.
- **No silent overwrite:** detections are pending until the rider applies them; static values are preserved.
- **Beta labelling** throughout the UI, reflecting the honest state of live threshold estimation.

## 12. Testing (TDD)

**Scaffolding note:** the project currently has **no test source set and no test dependencies**. The first implementation task adds a `src/test` JVM unit-test setup (JUnit + Kotlin test) so the pure components (`ThresholdEstimator`, `RampDetector`, `DriftTracker`, `DynamicZones`) — all designed to be free of Android/karoo-ext dependencies — can be tested on the JVM. The estimator's least-squares fit must be implemented in plain Kotlin (no numpy): a 4-column hinge-basis normal-equations solve, verified against the Python prototype's outputs.

The five rider CSVs pulled during the spike become golden-master fixtures under test resources:

- **`ThresholdEstimator`**: ramp fixture → VT1 VE ∈ [52, 54], VT2 VE ∈ [78, 86]; degenerate/steady inputs → `null`.
- **`RampDetector`**: qualifying ramp → detects; steady June ride → no detection; sprinty input → no detection.
- **`DriftTracker`**: known reference vs later window → expected drift %.
- **`DynamicZones`**: resolves static when no detection / toggle off; dynamic when present, confident, and enabled; correct zone boundaries either way.
- **VE-zone consistency (§5.1 regression):** for a given VE value and smoothing mode, the zone a field displays matches the zone implied by the value it shows — the three-way smoothing divergence cannot reappear.

Each component is tested before its implementation is written.

## 13. Rejected Alternatives

- **Continuous live threshold re-estimation** — more "alive" but the documented noisy regime; deferred, possibly permanently.
- **Lifting Tymewear's CNN** — not extractable (server-side), not desirable (black box) and wrong tool for live use. We build our own explainable estimator.
- **Cloud fit** — defeats the on-device USP and adds a dependency/round-trip.

## 14. Open Questions for Implementation Planning

1. Exact confidence formula weighting (R² vs duration vs spread) — tune against the fixture set during implementation.
2. Guided-test field interaction model on the Karoo (pure timer/coaching vs any use of bonus buttons) — confirm against karoo-ext capabilities during the first task.
3. Opt-in **auto-apply** above a confidence bar — deferred past v1 (v1 always confirms; see §7).

---

*Next step: on approval, invoke the writing-plans skill to produce a task-by-task implementation plan.*
