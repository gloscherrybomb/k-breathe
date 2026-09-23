# Session Scale and Threshold Evidence — Design Spec

> **Names (2026-09-23):** this document predates 0.7.0. Its `vt1`, `vt2`, `topZ4`, `vo2max`
> (and "VT1", "VT2", "TopZ4", "VO2max" where they mean the configured fields) are the zone edges
> Tymewear calls **Endurance, VT1, VT2, Top Z4**. See `2026-09-23-tymewear-names-design.md`.
> The threshold evidence and suggestion feature (§6 here) was removed in 0.7.0; see §5 of `2026-09-23-tymewear-names-design.md`.

**Date:** 2026-09-02
**Status:** Design — awaiting review before implementation planning
**Supersedes:** the live-estimation parts of `2026-09-01-dynamic-ventilatory-state-design.md`.
The reliability work and the steady-sample / baseline machinery from that spec stay.

---

## 1. The problem this solves

k-breathe colours every zone from the sensor's minute ventilation (VE) against fixed
thresholds in L/min. The first real outdoor ride on 0.5.0 (2026-09-02) and a re-analysis
of 17 clean historical rides showed that **the sensor's volume scale changes from
session to session by up to ±25 %**, and that this, not physiology, is the dominant
error in the zone colours on any given day.

Evidence, at a steady 140–160 W across 15 clean rides:

| Quantity | Range across rides |
|---|---|
| Heart rate | 128–145 bpm |
| Breathing rate | 26–34 brpm |
| Minute ventilation | 44–82 L/min |
| Tidal volume | 1.43–2.61 L |

Heart rate and breathing rate are stable; VE nearly doubles, and all of the spread is
tidal volume. The 2026-09-02 ride had the lowest tidal volume ever recorded (1.43 L)
with a normal heart rate and breathing rate, the day after a battery change.

The discriminating test: a scale shift moves VE equally at matched *power* and at
matched *heart rate*; a genuinely fresh or hard day moves VE at matched power but not
at matched heart rate, because heart rate moved with it. Against a leave-one-out
baseline of the other indoor rides:

| Ride | VE vs baseline at matched power | at matched HR | Reading |
|---|---|---|---|
| 2026-09-02 (outdoor, easy) | −19 % | −22 % | scale |
| 2026-04-25 (outdoor, 215 min) | +21 % | +27 % | scale |
| 2026-02-21 (ramp test day) | +13 % | +13 % | scale |
| 2026-02-27 | −24 % | −21 % | scale |
| 2026-02-24, 03-03, 03-29 | −12 to −14 % | 0 to +4 % | physiology (good days) |
| 2026-01-29 | +42 % | +21 % | both |

Consequences that bind this design:

1. Static VE zones can be a whole zone wrong on a given day. On 2026-04-25 (strap
   reading ~16 % high) static thresholds put 57 % of the ride in Zone 2 or above; the
   corrected thresholds put 36 % there. On 2026-09-02 (strap reading ~22 % low) the
   effect ran the other way but the ride was easy, so only ~6 % of it moves from Zone 1
   to Zone 2 once corrected.
2. The 0.5.0 Beta's "efficiency deviation" conflated the two signals. Its threshold-power
   output rested on the conflated number, and it produced **zero** samples outdoors
   because its steadiness rule (60 s power CV < 12 %) is never met on the road.
3. Both outdoor rides are pure scale shifts in opposite directions, so "outdoor VE runs
   higher" is not a regime effect and no separate outdoor baseline is needed.
4. The rider's configured thresholds came from Tymewear's analysis of the ramp-test day,
   which read +13 %. They are therefore ~13 % high in the rider's typical scale. That is
   precisely the kind of error §6 exists to catch.

Root cause of the scale variance: k-breathe converts the strap's raw stretch reading to
litres with one fixed constant (`Protocol.TV_CALIBRATION = 0.01`). Verified unchanged
since v0.4.3, and the recorded tidal-volume stream equals VE ÷ BR exactly, so this is not
a k-breathe regression. The Tymewear app has a calibration layer k-breathe lacks
("Set User Calibration" commands to the strap, "calibrate resting values", "calibrate max
ventilation", "VitalPro too loose / too tight" checks). Reproducing that layer is out of
scope; correcting for its absence is what this spec does.

## 2. What changes for the rider

### 2.1 During a ride (Beta on)

- **VE field, VE Graph, VE Zones chart** — same fields. Once the session scale is known,
  their zone colours, bands and bars use today's corrected thresholds. Until then, and
  with the Beta off, they behave exactly as today.
- **One Beta field, "Vent State", replaces three.** Large number: **day quality**, how
  today's breathing compares with the rider's normal for the same effort *after* the
  strap scale is removed, e.g. `−8%` (fresher than usual, green cue) or `+9%` (heavier,
  amber cue). Small line: the **strap scale**, e.g. `×0.80`. The two lines learn
  separately, so while the day quality is still unknown the large line reads `cal` and
  the small line reads `learning`; the small line reads `scale n/a` for an out-of-range
  factor, and with the Beta off the large line reads `off` with the small line blank.
- **New field "Power + VE zone"** (`power_vz`). For riders whose main screen shows power
  rather than VE: the large number is **3-second average power** from the Karoo's power
  stream, and a **coloured bar** along the top edge shows the **current VE zone** —
  the zone the rider's breathing puts them in right now, classified through the same
  single path as every other zone (so it uses corrected thresholds when the Beta is on,
  configured thresholds otherwise). The bar is grey when breathing data is stale or
  absent, and the number shows `--` when power is unavailable. Not a Beta-only field:
  it is useful with static thresholds too. It does **not** map power to zones; the zone
  is always what the lungs say.
- **Threshold Power and BR Drift are removed.** The first rested on the conflated
  deviation; the second takes its reference from ride minutes 1–6 regardless of effort,
  and on 2026-09-02 (an 88 W warm-up) it would have read +24 % drift, above its 12 %
  alert level, for 91 % of an easy ride.

How a ride reads: the opening minutes are warm-up and learning, colours from the
configured thresholds. The scale locks once three heart-rate bins have enough steady
samples — about 10 minutes on a trainer, 27 minutes on the easy, stop-start 2026-09-02
ride, and never on a ride too short or too ragged, in which case nothing changes. Then
the colours correct themselves and the day-quality number says whether to hold the plan
or back off. From there the rider rides by colour as now, with the colour right for today.

### 2.2 Between rides

Settings, under the Beta toggle:

- Baseline status (bins covered, rides, last updated) and reset — as today.
- **Last ride's strap scale**, e.g. "Last ride read 20 % low — check strap tension and
  position." This is actionable: it tells the rider whether the strap moved.
- **Threshold suggestion card**, shown when §6's evidence rule is met: "Recent rides put
  VT1 near 66 L/min. Configured: 73. **Apply** / **Dismiss**." Dismiss suppresses that
  suggestion until the estimate moves by ≥ 3 L/min. An **auto-apply** switch (default
  off) applies suggestions silently and records each change in a small history list with
  one-tap revert.

### 2.3 In the FIT file

- Recorded VE, BR and tidal volume stay **what the sensor reported**. History is never
  rewritten by an estimate.
- The per-second `tyme_ve_zone` field and the session time-in-zone fields use the
  corrected thresholds when the Beta is on (what the rider saw is what is recorded).
- Two new session fields: `tyme_ve_scale` (dimensionless, e.g. 0.80) and
  `tyme_day_quality` (%). Both absent when the Beta is off. Otherwise they appear
  independently, because they are gated on different things: day quality is published
  whenever both deviations are confident, the scale only once it has locked. So a ride
  can record `tyme_day_quality` with no `tyme_ve_scale` beside it — a ride whose scale
  never locked, or landed outside the clamp, is exactly that case.

## 3. The model

Definitions, for one ride:

- **Steady sample** — a (load W, HR bpm, VE) triple selected by §4's sampler.
- **VE(P) baseline** — rolling mean VE per 20 W bin, from steady samples of previous
  rides, each ride folded in *after* normalising by its own scale factor (§3.3).
- **VE(HR) baseline** — the same, per 5 bpm heart-rate bin.
- **devP** — median over matched power bins of `VE_today(P) / VE_baseline(P) − 1`.
- **devHR** — the same over matched heart-rate bins.

### 3.1 Decomposition

```
scale        = 1 + devHR                 (strap reads high when > 1)
dayQuality   = devP − devHR              (negative = less ventilation than normal for the
                                          same power, after removing the strap scale)
```

Validated on the table in §1: pure-scale rides give devP ≈ devHR (dayQuality ≈ 0); the
late-February good days give devHR ≈ 0 with devP ≈ −13 %; 2026-01-29 splits into +21 %
scale and +21 % day.

### 3.2 Corrected thresholds

`threshold_today = threshold_configured × scale` for all four thresholds. Equivalent to
dividing today's VE by the scale before classifying, but leaves the displayed and
recorded VE untouched.

### 3.3 Locking the scale

- Scale is estimated from steady samples taken in the **first 40 minutes** of recording
  only, to keep long-ride cardiac drift and heat (which raise HR at a given power and
  would read as a mild downward scale) out of the estimate.
- Published when devHR has **≥ 3 matched HR bins with ≥ 30 samples each**, and the
  baseline has ≥ `STATE_MIN_BASELINE_BINS` bins from ≥ `STATE_MIN_BASELINE_RIDES` rides.
- Once published it is **held for the rest of the ride** (recomputed only while still in
  the 40-minute window; updates are rate-limited to one per 60 s and eased over 30 s so
  colours don't flicker). A ride that never meets the rule stays on configured
  thresholds and reports `cal`.
- **Clamp** to [0.6, 1.6]. Outside that range the strap is probably not worn correctly;
  publish `n/a` and leave thresholds uncorrected rather than colour from a guess. This
  applies only while nothing is locked yet: an out-of-range reading arriving *after* a
  lock is ignored and the lock is kept, so one noisy heart-rate bin cannot undo a
  learned scale.

Day quality is published whenever both devP and devHR are confident, and keeps updating
for the whole ride (it is a live signal, unlike the scale).

### 3.4 Baseline scale convention

The baseline's scale is whatever the first two rides established. Each subsequent ride is
normalised to it before folding in, so it converges to a consistent internal reference.
The configured thresholds are *not* on that reference (see §1, consequence 4). The
mismatch is deliberately left to §6 to resolve, because the alternative — rescaling the
rider's entered numbers behind their back — is exactly what §2.3 forbids.

## 4. Sampling that works outdoors

Replace `SteadyStateDetector`'s CV rule with a **lag-aware load gate**, evaluated each
second:

- `L` = exponentially weighted power, τ = 60 s (a first-order model of VE's response
  to load, so `L` is the load the current VE is actually answering to).
- Accept the sample when: `60 ≤ L ≤ 320 W`; `|L − L(30 s ago)| / L < 10 %`; less than
  10 % of the last 30 s spent below 20 W (no coasting); fresh VE (30 s mean) and fresh
  HR (30 s mean) both present; ≥ 120 s since recording (re)started.
- Existing rules kept: time-gap reset (`DEFAULT_MAX_SAMPLE_GAP_MS`), clear on stale VE.

Measured on the fixtures: usable seconds go from 0 % to 26–48 % on 2026-09-02 and 61 %
on 2026-04-25, with within-bin scatter (~10–12 %) comparable to indoor rides (8–12 %).
Indoor rides are unaffected (96 % usable either way).

## 5. Components

Pure components take injected clocks and avoid Android and `Constants` (JVM tests).

| File | Change |
|---|---|
| `LoadGate.kt` (new; replaces `SteadyStateDetector.kt`) | §4 sampler. Emits `LoadVeSample(loadW, hrBpm, ve)`. |
| `VeBaseline.kt` | Generalise to a keyed bin baseline usable for both power (20 W) and HR (5 bpm); `update(key, ve)`, `expected(key)`, serialise both. Capped running mean unchanged. |
| `EfficiencyDeviation.kt` → `BinDeviation.kt` | Same median-of-bins logic, parameterised by key; used twice. |
| `SessionScale.kt` (new) | §3.1–3.3: takes devP/devHR streams, applies window, confidence, clamp, hold, easing. Pure. |
| `ZoneClassifier.kt` | `ZoneThresholds.scaled(factor)`; `effectiveThresholds(configured, scale?)`. |
| `TymewearData.kt` | `currentThresholds()` returns effective thresholds (configured × published scale when Beta on). Every existing consumer (VE field, live zone, time-in-zones, FIT zone) follows with no change. |
| `VeGraphDataType.kt` | Read `currentThresholds()` instead of the four raw fields. |
| `VentilatoryState.kt` | Own `LoadGate`, two baselines, two deviations, `SessionScale`; publish `scale`, `dayQuality`, `lastRideScale`; normalise ride samples by scale before folding into baselines at ride end; drop power-threshold and drift flows. |
| `ThresholdEvidence.kt` (new) | §6. Pure: pooled bins in → suggestion out. |
| `VentilatoryStateDataType.kt` | Two-line rendering (§2.1). |
| `PowerZoneDataType.kt` (new) + `view_power_zone.xml` | `power_vz`: 3 s mean of `TymewearData.powerW`, zone bar from `TymewearData.veZone`, grey when `!isDataFresh()`. Registered in `extension_info.xml`, `Device.dataTypes`, strings (all three — see memory `karoo-extension-registration`). |
| `TymewearData.kt` (also) | New `powerW: StateFlow<Double?>` fed from the extension's existing `DataType.Type.POWER` subscription, independent of the Beta flag, null on `NotAvailable`. |
| `ThresholdPowerDataType.kt`, `BreathingDriftDataType.kt`, `ThresholdShift.kt`, `DriftTracker.kt` | Delete, with their tests and `extension_info.xml` / `Device.dataTypes` / strings entries. |
| `TymewearExtension.kt` | Feed HR into `VentilatoryState` alongside power; write the two session fields. |
| `screens/MainScreen.kt` | §2.2 UI: last-ride scale, suggestion card, auto-apply switch, change history. |
| `README.md`, `Constants.kt`, `strings.xml` | Tunables and copy. |

## 6. Threshold evidence between rides

Runs at ride end, on-device, from the pooled scale-normalised VE(P) baseline plus a
per-ride record of the last 8 rides' pooled-curve breakpoints.

- **Fit** a continuous two-segment (one breakpoint) then three-segment (two breakpoint)
  weighted least-squares line to the baseline's 20 W bin means with ≥ 120 samples
  (reusing the existing bins rather than keeping a second, finer structure; a
  breakpoint is therefore placed to ± 10 W). Accept a
  breakpoint only if it is bracketed by ≥ 40 W of covered bins on each side and the
  slope increases across it.
- **VT1 candidate** = VE at the lower accepted breakpoint; **VT2 candidate** = VE at the
  upper one. TopZ4 and VO2max are not estimated (steady riding never covers them).
- **Stability**: a candidate is offered only when the last 3 rides' fits agree within
  ± 4 L/min. Caveat: consecutive ride-end estimates are each fitted from the *cumulative*
  pooled baseline, one ride richer than the last, so they are not independent samples —
  by the time the baseline holds a dozen rides one more moves it very little. The "three
  rides agree" gate therefore mostly guards against offering a fit that has not settled
  yet, not against ride-to-ride variability.
- **Suggest** when the candidate differs from the configured value by more than 8 %.
  Suggestions are for **VT1 and VT2 only**; the rider keeps TopZ4 and VO2max manual (or
  scales them by the same ratio via a one-tap option on the card).

On the rider's full Jan–Mar indoor history (14 rides, earlier sampler) the pooled curve
showed a break near 205 W / 70 L/min. On the six indoor rides kept as test fixtures the
fitter correctly reports **no** breakpoint: the curve flattens above 160 W, the 180 W bin
holds 8600 raw samples (weighted at the 6000 cap), and everything above 220 W is thinly sampled, so no candidate
clears the 25 % improvement gate. That refusal is the intended behaviour and is pinned by
a test; the estimate will be revisited as the on-device baseline grows. The February
ramp's piecewise fit (VT1 53 / VT2 80) was
biased low by warm-up stages and is **not** used as ground truth; 30-minute sustained VE
of 92–101 L/min on two rides rules out VT2 = 80.

## 7. Edge cases

- **No HR stream**: no scale can be estimated; behave as Beta-off for colours, field
  shows `cal`. No power: as today (nothing accumulates).
- **First two rides** (baseline building): `cal`, configured thresholds throughout.
- **Strap swap mid-ride / reconnect**: scale is held; VE freshness gating already clears
  windows on dropout.
- **Pause / resume**: the 40-minute window counts recording time only.
- **Scale outside clamp**: `n/a`, uncorrected thresholds, settings shows the raw figure
  with the "check strap" hint.
- **Beta toggled mid-ride**: takes effect at next ride start (as today).
- **Trainer ride with heater / very hot day**: devHR reads slightly negative → scale
  slightly < 1 → colours slightly conservative (read a little low). Accepted bias.

## 8. Validation and tests

- **Fixtures**: add `outdoor_easy_2026-09-02.csv` and `outdoor_long_2026-04-25.csv`
  (from intervals.icu; both clean). Keep existing indoor fixtures.
- **Golden values** are computed against a baseline built from the *repository's* six
  indoor fixtures (leave-one-out for indoor rides), so they differ from the §1 table,
  which used the rider's full history. With the §4 sampler exactly as specified
  (tolerance ± 2 points): 2026-09-02 devP −10.4 / devHR −22.5 (scale 0.775);
  2026-04-25 devP +22.2 / devHR +16.4 (scale 1.164); 2026-02-24 devP −3.6 / devHR +8.8;
  2026-03-03 devP −10.1 / devHR −9.5. Usable-sample fractions: 2026-09-02 35.8 %,
  2026-04-25 59.2 %, indoor fixtures 79–96 %. The ramp self-scores −0.3 %.
- `SessionScale`: window, hold, easing, clamp, confidence gates, `n/a` path.
- `ThresholdEvidence`: synthetic curves with known breakpoints; refuses unbracketed and
  decreasing-slope breaks; stability rule.
- `ZoneClassifier`: scaled thresholds; effective = configured when scale null or Beta off.
- `PowerZoneDataType`: 3 s mean over a 1 Hz stream with gaps; bar colour follows
  `veZone` and goes grey on stale breathing data while power keeps displaying; `--` on
  power `NotAvailable`.
- End-to-end JVM test: replay 2026-09-02 through the pure pipeline against a baseline
  built from the indoor fixtures; assert the scale locks before the 40-minute window
  closes (measured: 27 min, first value 0.834, held at 0.837) and that time in Zone 2
  under corrected thresholds rises from ~0 % to ≥ 1 % (measured 1.4 % — the ride was
  easy and two-thirds of it was corrected). Replay 2026-04-25 and assert the held scale
  is ≈ 1.25 (locked at 8 min) and Zone 2+ time falls from 57 % to 27 %. Note the held
  scale differs from the whole-ride heart-rate deviation (§1 table) by design: §3.3 fixes
  it from the first 40 minutes.
- **On-device**: install, ride once indoors and once outdoors, confirm `×` factor appears
  and `tyme_ve_scale` lands in the FIT session.

## 9. Out of scope

- Reproducing Tymewear's strap calibration protocol (needs their BLE command format).
- Estimating TopZ4 / VO2max from ride data.
- Live re-estimation of *power* at threshold (removed; may return once the scale
  correction has been ridden for a few weeks and the day-quality signal is trusted).
- Any change to recorded VE/BR/TV values.

## 10. Open questions for the rider

1. Was anything different about the strap on 2026-09-02 (tension, position, over/under
   bibs, calibration in the Tymewear app after the battery change)? Not needed for the
   design, but it would confirm the mechanism.
2. The Tymewear app's Thresholds screen shows HR, power and VE targets per zone. Those
   figures are the best available ground truth for §6 and should be copied into the
   README's validation notes.
