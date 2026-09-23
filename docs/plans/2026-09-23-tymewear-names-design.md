# Tymewear's Names — Design and Plan

**Date:** 2026-09-23
**Status:** Implemented on branch `tymewear-names`
**Release:** 0.7.0 (versionCode 13), from 0.6.1 (12)

## 1. Goal

K-Breathe must use Tymewear's own names for the ventilation thresholds and zones
(athlete's ruling, 2026-09-23). It inherited names that are one step off: its four
"thresholds" `vt1`, `vt2`, `topZ4`, `vo2max` are really the four zone **edges** that
Tymewear's Fitness Profile calls **Endurance, VT1, VT2, Top Z4**. Tymewear also shows
**VO2max**, the top of zone 5 — not an edge.

Proof: the athlete's Tymewear bike Fitness Profile reads Endurance 73.2, VT1 96.0,
VT2 112.0, Top Z4 129.6, VO2max 182.3. Their K-Breathe settings hold
vt1=73, vt2=96, topZ4=112, vo2max=130.

## 2. The mapping

| Old K-Breathe field | Old pref key | Tymewear name | New field | New pref key |
|---|---|---|---|---|
| `vt1` | `vt1_threshold` | Endurance | `endurance` | `zone_endurance` |
| `vt2` | `vt2_threshold` | VT1 | `vt1` | `zone_vt1` |
| `topZ4` | `topz4_threshold` | VT2 | `vt2` | `zone_vt2` |
| `vo2max` | `vo2max_threshold` | Top Z4 | `topZ4` | `zone_top_z4` |
| — | — | VO2max | `vo2max` (new) | `zone_vo2max` |

Defaults become 73 / 96 / 112 / 130 / 180 — the same four edges as before, plus
VO2max 180: `ZoneThresholds(endurance, vt1, vt2, topZ4, vo2max)`.

Zones, in Tymewear's labels:

| Zone | Range |
|---|---|
| Z1 | below Endurance |
| Z2 | Endurance – VT1 |
| Z3 | VT1 – VT2 |
| Z4 | VT2 – Top Z4 |
| Z5 | Top Z4 and above (VO2max is the top of Z5) |

## 3. What changes

- **Model.** `ZoneThresholds(endurance, vt1, vt2, topZ4, vo2max)`. `scaled()` scales all
  five. `Protocol.veZone(ve, endurance, vt1, vt2, topZ4)` — classification takes the four
  edges, so the effect is unchanged: same edges, same zone numbers 1–5. VO2max plays no
  part in classification.
- **Settings screen.** Five fields in order: "Endurance (L/min)", "VT1 (L/min)",
  "VT2 (L/min)", "Top Z4 (L/min)", "VO2max (L/min)". Supporting text in Tymewear's zone
  terms ("Z1 below, Z2 above", …, "Top of Z5"). Validation:
  Endurance < VT1 < VT2 < Top Z4 < VO2max, message worded the same way.
- **VE Graph.** Bands and lines drawn at Endurance, VT1, VT2, Top Z4 (as before, the same
  four values). Line labels become `END`, `VT1`, `VT2`, `TZ4`. The old labels put "VT1" on
  the Endurance line, "VT2" on the VT1 line and "TZ4" on the VT2 line. Y-axis headroom stays
  `Top Z4 × 1.2` (the old `vo2max × 1.2`), so the graph looks the same.
- **Zone labels and comments.** Colour comments and README zone table say Z1–Z5 with
  Tymewear's edge names for the ranges. The data fields already show `Z1`…`Z5`.
- **Threshold suggestions removed** — see §5.
- **README and docs** use Tymewear's names.
- **Version** 0.6.1 (12) → 0.7.0 (13) in `app/build.gradle.kts`. `manifest.json` is the
  published release manifest and is left for the release step.

## 4. Prefs migration

A pure function, `ThresholdMigration.migrate(stored: Map<String, *>)`, returns the writes
and removals, or null when there is nothing to do. `ThresholdPrefs.ensureMigrated(context)`
applies them. It runs before anything reads settings: `TymewearData.loadThresholds`,
`MainActivity.onCreate`, and every `VentilatoryState` prefs access.

**Thresholds.** This part runs only when **none** of the new `zone_*` keys exist and at
least one old key does, so it runs once and never over values the new version wrote.

- Each old threshold key that is present moves one name down (`vt1_threshold` →
  `zone_endurance`, `vt2_threshold` → `zone_vt1`, `topz4_threshold` → `zone_vt2`,
  `vo2max_threshold` → `zone_top_z4`). Old keys that are absent stay absent and the new
  default applies. The old defaults were the same four numbers, so nothing moves.
- `zone_vo2max` is written as 180. If the migrated Top Z4 is already 180 or more, VO2max
  becomes Top Z4 × 180/130 instead, so the order still holds and the settings screen will save.
- The old keys are removed, so no old name is left holding a value under a meaning it no
  longer has. They are deleted whenever present, even on an install already on the new names,
  so a stale old key cannot linger beside them.

**Suggestion keys** (§5). Every key the removed feature ever wrote is deleted whenever it
is present, independent of the threshold part:

- 0.6.x: `threshold_auto_apply`, `threshold_change_history`, `threshold_dismissed_vt1`,
  `threshold_dismissed_vt2`, `threshold_evidence_history`.
- Keys this branch introduced before the removal: `threshold_changes` and
  `suggestion_dismissed_{endurance,vt1,vt2,top_z4,vo2max}`.

Nothing that remains reads the breakpoint evidence history, so it goes too.

## 5. Threshold suggestions: removed

**Ruling (athlete, 2026-09-23):** K-Breathe keeps the thresholds the rider enters in Settings,
taken from their Tymewear Fitness Profile. Tymewear is where zones are set. K-Breathe no
longer proposes, auto-applies, records or reverts threshold changes.

History, for the record. 0.6.x fitted breakpoints in the pooled VE-vs-power baseline and
suggested updates to the fields then called `vt1`/`vt2`. Those fields held Tymewear's
Endurance and VT1. This branch first retargeted the suggestions by a fixed rule (5eb58f2),
then by nearest marker (5feba95). Neither was satisfactory: the only real pooled break
(near 70 L/min) disagreed with the design's VT1/VT2 reading, and the athlete did not want to
decide which threshold a breakpoint is. The feature was then removed.

Removed:

- `ThresholdEvidence`: the breakpoint fit, the suggestion rules and the ordering filter.
  The fit fed nothing but the suggestions.
- `Breakpoints` and the evidence history.
- The ride-end `SuggestionPromptActivity` and its manifest entry, `RideEndPrompt`, and
  `SuggestionCard`.
- The auto-apply switch.
- `ThresholdChange`, the change history and Revert.
- The tests for all of these.

Kept:

- The VE baselines, strap scale, day quality and every data field.
- Tymewear's names and Z1–Z5.
- The Settings order check (Endurance < VT1 < VT2 < Top Z4 < VO2max).
- `ThresholdKind`, now only the list of labels for Settings.

## 6. What does NOT change

- FIT developer field names and numbers (`tyme_ve_zone`, `tyme_ve_zone1_time`, …) and the
  recorded zone numbers 1–5: they are Tymewear's own FIT schema.
- Zone colours, zone numbers, and which VE falls in which zone for the same four edge values.
- The strap-scale Beta: baselines, scale, day quality.

## 7. Testing

TDD, JVM unit tests:

- `ThresholdMigrationTest`: full old set → shifted keys + VO2max 180 + old keys removed;
  partial old set; nothing old → no-op; new keys present → thresholds untouched (runs once);
  Top Z4 ≥ 180 bumps VO2max; every suggestion key (0.6.x and branch names) deleted on upgrade
  and on an already-migrated install; other settings left alone.
- `ZoneClassifierTest`: five-field thresholds, a sweep against hand-written edges
  73/96/112/130, `scaled` covers VO2max, and the user-visible names are exactly Tymewear's.
- Whole suite plus `./gradlew :app:assembleDebug`.
