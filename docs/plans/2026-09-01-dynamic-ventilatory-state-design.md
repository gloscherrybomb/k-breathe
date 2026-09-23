# Dynamic Ventilatory State — Design Spec

> **Names (2026-09-23):** this document predates 0.7.0. Its `vt1`, `vt2`, `topZ4`, `vo2max`
> (and "VT1", "VT2", "TopZ4", "VO2max" where they mean the configured fields) are the zone edges
> Tymewear calls **Endurance, VT1, VT2, Top Z4**. See `2026-09-23-tymewear-names-design.md`.

**Date:** 2026-09-01 (rewritten — supersedes the first draft of the same date)
**Status:** Design — awaiting review before implementation planning
**Feature:** Live, on-Karoo measurement of how today's ventilatory response differs from
the rider's own baseline — k-breathe's differentiating capability.

---

## 1. What this feature is

Ventilatory thresholds are not fixed. They move day to day with fatigue, heat, sleep,
illness, altitude and freshness. A static zone table asserts one number every day and is
therefore wrong on most of them.

This feature surfaces **how today differs from your normal**, live on the Karoo:

- **Ventilatory efficiency vs baseline** — one number, e.g. *"−13%: lower ventilatory
  cost than your baseline at this power. Good day."*
- **Today's threshold powers** — *"VT1 today ≈ 122 W"* instead of the 109 W your last
  test implies, or 95 W when you are cooked. This is what you actually pace by.

### 1.1 What it is not (correcting the first draft)

The first draft of this spec aimed at re-deriving *static* threshold boundaries from a
ramp effort — a better calibration tool. That was the wrong target. Re-testing produces
another single fixed number; it does not show variation, which is the entire point.

Two findings from the investigation killed that approach on its own terms:

- **Breakpoint detection on ordinary rides produces confident nonsense.** Sliding the
  ramp estimator over non-ramp rides yielded 125 "valid" fits on one endurance ride, all
  degenerate (VT1 and VT2 both landing at VE ≈29.4 against a true 52.7/80). Gates strict
  enough to reject those (VE separation ≥8, VE rise ≥15, R² ≥0.80) also reject
  *everything* in a year of ordinary riding — zero qualifying windows across ~156.
- **Ramps are rare.** The rider's entire year of activity contains exactly **one**
  ramp test with Tymewear data.

The deviation approach needs neither: steady riding, previously useless for finding
breakpoints, is the *ideal* input for measuring deviation from a baseline.

## 2. Why it is the USP

Tymewear's own "Dynamic Zones" (app v1.5.2, ~June 2026) is a **server-side CNN that runs
on a completed, uploaded workout** — confirmed by decompiling the app: no bundled model,
`cnnIndexToZone` / `confidence` / `blendPoints` fed from `api.tymewear.com`, labelled
Beta. Its architecture cannot run live on a head unit, which is why neither their Garmin
Connect IQ field nor their July 2026 Wahoo ELEMNT integration shows dynamic zones during
a ride — both display only static, manually-entered values.

The intervals.icu community asks for exactly this and cannot get it: *"Until you can see
the dynamic zones in your garmin it's kinda hard to utilize this."*

Doing it live and on-device is therefore not a copy of Tymewear. It is a capability their
design cannot deliver.

## 3. The measured quantity

The first draft also had the wrong *quantity*. **VE at a given threshold is comparatively
stable physiology; what moves day to day is the power at which you arrive there.**

So the primitive is the **VE-versus-power relationship**, and the signal is its
displacement from the rider's own baseline:

```
deviation = median over matched power bins of ( VE_observed(P) / VE_baseline(P) ) - 1
```

- **Negative** = less ventilation for the same power = more efficient, fresher, fitter.
- **Positive** = more ventilation for the same power = fatigued, hot, ill, detrained.

From the same relationship, today's threshold power falls out directly: the power at
which today's VE curve crosses the rider's VE-at-VT1.

## 4. Validation on real rider data

Measured across the rider's own history (steady-state samples, matched power 100–240 W,
against a February ramp baseline), after excluding corrupt rides (§5):

| | Deviation from baseline |
|---|---|
| Range | **−17.2% to +32.9%** |
| IQR | −13.2% to +1.2% |
| Std dev | 11.9 points |
| **The baseline ride itself** | **+0.4%** ← self-consistency check |

Two things make this credible. The baseline ride scores +0.4% against its own baseline,
so the method is calibrated. And deviations arrive in **coherent multi-day blocks** —
Feb 11–21 near zero, Feb 24–Mar 3 at −13% to −17%, Jan 29 at +33% — not random scatter.
That is a legible physiological trajectory (early-season high ventilatory cost improving
into February), which is precisely the day-to-day variation this feature exists to show.

**Caveat:** 16 of the 17 usable rides are indoor (`VirtualRide`). Outdoor viability is
unproven, and the community reports outdoor VE running 5–8% higher at matched power. See
§8.

## 5. Data-quality prerequisite (load-bearing)

**10 of 27 candidate rides had to be discarded as corrupt**, and an early version of this
analysis reported confident nonsense from them before the corruption was spotted.

The cause was k-breathe's own recording: it wrote the last-known breathing value — or
`0.0` — into every FIT record regardless of age, producing hours of fabricated constants
indistinguishable from real data. Fixed and verified on-device on 2026-09-01 (commit
`627865c`): records now omit breathing fields when data is stale, and the data watchdog
retries instead of firing once and going silent. See memory `fit-recording-freeze-bug`.

Two consequences bind this design:

1. **The fix is a prerequisite.** A deviation metric computed over frozen values yields a
   plausible-looking number that is pure fiction. The live feature must consume only
   fresh data, gated on `TymewearData.isDataFresh()`.
2. **Historical data needs a quality gate.** Any analysis over recorded rides must reject
   a ride whose distinct-VE count is under ~20% of its sample count. Good rides score
   0.50–0.77; corrupt ones score 0.000–0.008.

## 6. Baseline: rolling, self-maintaining, no test required

The baseline is **not** a ramp test. It is a rolling reference built on-device from
steady-state samples across recent rides: for each power bin, the typical VE the rider
has recently produced there.

This choice matters:

- **No ramp needed.** Only one exists in a year of the rider's data; a feature requiring
  ramps would rarely work.
- **Self-maintaining.** It tracks fitness automatically, so "deviation" means "today
  versus my recent normal" — exactly the intended signal.
- **Degrades gracefully.** Before enough history accumulates the field reports
  "calibrating" rather than a wrong number.

The rider's manually-entered VT1/VT2 (in VE) remain the anchor for *absolute* threshold
values; the rolling baseline supplies the *relationship* that converts them into today's
threshold powers. No threshold test is required for the deviation number itself.

**Worth surfacing:** this rider's stored VT1 is 73 L/min, while their February ramp
implies ≈52.7. That is a large discrepancy, and a calibration warning ("your configured
VT1 looks inconsistent with your recorded data") is a natural, cheap by-product.

## 7. Components

Pure components take injected clocks/parameters and avoid Android and `Constants` —
`Constants` initialises `android.graphics.Color`, which throws in plain JVM unit tests.

| File | Responsibility |
|------|----------------|
| `SteadyStateDetector.kt` | Identifies stable-power windows (rolling 60 s, power CV < 12%). Only steady samples are comparable; VE lags power. |
| `VeBaseline.kt` | Rolling per-power-bin VE reference (20 W bins). Update from steady samples; query expected VE at a power; report coverage/confidence. Serialises compactly to prefs. |
| `EfficiencyDeviation.kt` | Live deviation from baseline over matched bins, with a confidence derived from matched-bin count and sample counts. |
| `ThresholdShift.kt` | Converts the rider's VE-at-VT1/VT2 plus today's relationship into today's threshold powers. |
| `VentilatoryStateDataType.kt` | Data field: deviation % with an interpretation cue, or "calibrating". |
| `ThresholdPowerDataType.kt` | Data field: today's VT1/VT2 power. |
| `DriftTracker.kt` + `BreathingDriftDataType.kt` | Live BR/VE drift vs a reference window — the community's "BR up >11–15% = cooked" heuristic. Display-only by default; optional alert (`drift_alert_enabled`, off; `drift_alert_pct`, 12). |

**Modified:** `TymewearExtension.kt` (consume `DataType.Type.POWER` alongside the existing
`HEART_RATE`/`ELAPSED_TIME` subscriptions; own the detector/baseline lifecycle; register
new fields), `TymewearData.kt` (expose steady-state stream; route zone through one path),
`screens/MainScreen.kt` (Beta toggle, baseline status/reset, calibration warning, drift
settings), `Constants.kt` (tunables), `README.md`.

### 7.1 Existing-field cleanup (in scope)

VE zone is currently computed in **four** places from different VE values: an 8-breath
average in `TymewearData.veZone`; a separate 30 s buffer in
`VentilationDataType.startStream`; the tapped smoothing mode in `startView` (which then
colours from `TymewearData.veZone`, i.e. a *different* smoothing than the number shown);
and **raw, unsmoothed** VE in `TymewearExtension.startFit`, which also feeds
`incrementZoneTime` — so the time-in-zone chart, FIT `ve_zone` and session summary derive
from raw-VE zone and can disagree with what the rider saw.

Consolidate to one classification path. A field's zone must come from the same VE value
it displays. This is a prerequisite for any of this being trustworthy.

## 8. Indoor versus outdoor

Community measurement puts outdoor VE 5–8% above indoor at matched power (one rider:
VT2 112 indoor vs 104 outdoor over 36 rides), and Tymewear attribute it to breathing
mechanics differing with bike stability. Wind also inflates VE.

Because this is the same magnitude as the signal we are measuring, a single baseline
risks reporting environment as physiology. **v1 keeps one baseline and labels the
feature Beta**, because our own outdoor data was too corrupt to validate a split. The
`VeBaseline` interface takes an environment key from the outset so a split can be added
without redesign. Revisit once clean outdoor rides exist — which the recording fix now
makes possible.

## 9. Safety and failure modes

- **Freshness gate.** Deviation consumes only fresh samples; stale data contributes
  nothing (§5).
- **Steady-state gate.** Transients, sprints and surges are excluded, since VE lags
  power and would otherwise read as inefficiency.
- **Confidence gate + honest empty state.** Below a minimum matched-bin count the field
  shows "calibrating", never a number.
- **No silent zone changes.** Displayed VE zones continue to use the rider's configured
  thresholds. The deviation and today's-threshold-power fields are *additional*
  information; they never rewrite stored settings.
- **Beta labelling**, reflecting that even Tymewear's server-side model is Beta.

## 10. Persistence

Extend `tymewear_prefs`:

- Unchanged: `vt1_threshold`, `vt2_threshold`, `topz4_threshold`, `vo2max_threshold`,
  MI parameters, `sensor_id`.
- Baseline: `baseline_bins` (compact serialised per-bin VE reference),
  `baseline_updated_at`, `baseline_ride_count`.
- Settings: `dynamic_state_enabled` (bool, default false — Beta opt-in),
  `drift_alert_enabled` (false), `drift_alert_pct` (12).

## 11. Testing

Fixtures are the rider's real rides, already committed under
`app/src/test/resources/fixtures/`, split into good-quality and corrupt sets.

- **`SteadyStateDetector`**: identifies known steady blocks; rejects interval surges.
- **`VeBaseline`**: builds from steady samples; returns expected VE; reports low
  confidence when coverage is thin; round-trips through serialisation.
- **`EfficiencyDeviation`**: **the baseline ride must self-check within ±2%** (measured:
  +0.4%); a known −13% ride must report ≈−13%; a corrupt ride must be rejected, not
  scored.
- **`ThresholdShift`**: given a baseline and a deviation, returns the expected threshold
  power; refuses to extrapolate outside covered bins.
- **`DriftTracker`**: known reference vs later window gives the expected drift %.
- **VE-zone consistency (§7.1 regression)**: the zone a field shows matches the value it
  displays; the four-way divergence cannot reappear.

Each component is tested before implementation. Note the project had no test source set
until 2026-09-01; JVM unit tests now exist with 23 passing.

## 12. Hard constraints

- **A power meter must be paired to the Karoo.** Without power there is no
  VE-versus-power relationship and the fields report unavailable — no regression to
  existing behaviour. (Note one of the rider's recent outdoor rides carried no power at
  all, so this is a real limitation, not a hypothetical.)
- **Cycling only.** The Karoo is a bike computer and power is the clean load signal. The
  interface takes an abstract load so a pace-based variant could follow, but no running
  code ships.
- **On-device only.** No cloud round-trip; that is the whole point.
- karoo-ext 1.1.8 suffices; no new SDK APIs required.

## 13. Rejected alternatives

- **Re-deriving static thresholds from ramps** (the first draft) — wrong target, and
  ramps are too rare. See §1.1.
- **Passive breakpoint detection on ordinary rides** — produced 125 confidently wrong
  fits on a single ride; safe gates reject everything.
- **Lifting Tymewear's CNN** — server-side, not extractable, a black box, and the wrong
  tool for live use.
- **Continuous mid-ride re-estimation of absolute thresholds** — the noisy regime where
  Tymewear's own model produces spurious readings.

## 14. Open questions for implementation planning

1. Bin width and baseline horizon (20 W bins, last N rides or an EWMA half-life) — tune
   against the fixture set.
2. Exact confidence formula weighting matched-bin count against per-bin sample counts.
3. How to present the deviation: raw % versus a small number of qualitative bands.
4. Whether `ThresholdPowerDataType` shows VT1 only, or VT1 and VT2 in one field.
5. Whether the calibration warning (§6) belongs in v1 or follows.

---

*Next step: on approval, invoke the writing-plans skill to produce a task-by-task
implementation plan.*
