# Session Scale and Threshold Evidence — Implementation Plan

> **Names (2026-09-23):** this document predates 0.7.0. Its `vt1`, `vt2`, `topZ4`, `vo2max`
> (and "VT1", "VT2", "TopZ4", "VO2max" where they mean the configured fields) are the zone edges
> Tymewear calls **Endurance, VT1, VT2, Top Z4**. See `2026-09-23-tymewear-names-design.md`.
> The threshold evidence and suggestion feature (Tasks 10–11, spec §6) was removed in 0.7.0; see §5 of `2026-09-23-tymewear-names-design.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 0.5.0 Beta's conflated "efficiency deviation" with a per-session strap-scale correction (so zone colours are right on the day) plus a cleaned day-quality number, add a power field carrying the live VE zone, and suggest threshold updates between rides from pooled, scale-normalised evidence.

**Architecture:** A pure `SessionPipeline` (lag-aware `LoadGate` → two `VeBaseline`s keyed by power and by heart rate → two `BinDeviation`s → `SessionScale`) is owned by the Android shell `VentilatoryState`, which persists baselines and publishes `scale` / `dayQuality` flows. `TymewearData.currentThresholds()` returns configured thresholds × published scale, so every existing zone consumer follows automatically. `ThresholdEvidence` fits breakpoints to the pooled power baseline at ride end and produces suggestions the settings screen shows.

**Tech Stack:** Kotlin 2.0, karoo-ext 1.1.8, JUnit4 + kotlin-test-junit (JVM unit tests with `isReturnDefaultValues=true`), RemoteViews data fields, Jetpack Compose settings screen.

**Spec:** `docs/plans/2026-09-02-session-scale-design.md` — read it first; every task below cites the section it implements.

## Global Constraints

- Pure components (`LoadGate`, `VeBaseline`, `BinDeviation`, `SessionScale`, `SessionPipeline`, `ThresholdEvidence`, `ZoneClassifier`) take clocks/parameters as arguments and must not reference `android.*`, `Constants`, `TymewearData` or `Timber` (spec §5).
- Never display or record a stale breathing value; all live reads go through `TymewearData.isDataFresh()` (spec §1, inherited from 0.5.0).
- Recorded VE/BR/TV in the FIT file stay raw (spec §2.3).
- A new data field needs three registrations: `res/xml/extension_info.xml`, `TymewearDevice.dataTypes`, `res/values/strings.xml` (memory `karoo-extension-registration`).
- Run tests with:
  `export JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home && ./gradlew :app:testDebugUnitTest -q --tests 'com.tymewear.karoo.<Class>'`
  (drop `--tests` for the whole suite). `timeout` does not exist on this Mac; do not prefix commands with it.
- Work on branch `feat/session-scale` off `main`. Commit after every task; do not push or release (release is a separate request).
- Golden values in this plan were computed with the exact rules in the tasks (Python reference, 2026-09-02). Tolerances are stated per assertion; if an implementation lands outside a tolerance, investigate the rule mismatch — do not widen the tolerance.

---

### Task 0: Branch and fixture set

The two outdoor fixtures already exist in the working tree, and the six indoor fixtures have been rewritten with a `heartrate` column (watts/VE verified byte-identical; the existing 78 tests pass). This task commits them and teaches the loader to read heart rate.

**Files:**
- Modify: `app/src/test/kotlin/com/tymewear/karoo/RideFixture.kt`
- Commit (already present): `app/src/test/resources/fixtures/outdoor_easy_2026-09-02.csv`, `outdoor_long_2026-04-25.csv`, and the six modified `ride_2026-*.csv`
- Test: `app/src/test/kotlin/com/tymewear/karoo/RideFixtureTest.kt` (new)

**Interfaces:**
- Produces: `RideFixture.hr: List<Double?>` alongside `watts`, `ve`, `br`; `RideFixture.load(name)`.

- [ ] **Step 1: Create the branch**

```bash
git checkout -b feat/session-scale main
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideFixtureTest {
    @Test
    fun `loads heart rate from both fixture header styles`() {
        val indoor = RideFixture.load("ride_2026-02-24.csv")          // header: time,watts,heartrate,ve,br
        val outdoor = RideFixture.load("outdoor_easy_2026-09-02.csv") // header: time,watts,heartrate,TymeVentilation,TymeBreathRate
        assertEquals(indoor.watts.size, indoor.hr.size)
        assertEquals(outdoor.watts.size, outdoor.hr.size)
        assertTrue("indoor fixture must carry HR", indoor.hr.count { it != null && it > 60 } > 2000)
        assertTrue("outdoor fixture must carry HR", outdoor.hr.count { it != null && it > 60 } > 4000)
    }

    @Test
    fun `outdoor fixtures are clean recordings`() {
        assertTrue(RideFixture.load("outdoor_easy_2026-09-02.csv").qualityRatio() > 0.25)
        assertTrue(RideFixture.load("outdoor_long_2026-04-25.csv").qualityRatio() > 0.15)
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Expected: compile error — `hr` is not a member of `RideFixture`.

- [ ] **Step 4: Add `hr` to the loader**

In `RideFixture.kt`, change the constructor and loader:

```kotlin
class RideFixture(
    val watts: List<Double?>,
    val hr: List<Double?>,
    val ve: List<Double?>,
    val br: List<Double?>,
) {
```
and inside `load`:
```kotlin
            val hIdx = idx("heartrate", "hr")
            ...
            val hr = ArrayList<Double?>()
            for (line in lines.drop(1)) {
                ...
                watts.add(get(wIdx)); hr.add(get(hIdx)); ve.add(get(vIdx)); br.add(get(bIdx))
            }
            return RideFixture(watts, hr, ve, br)
```

- [ ] **Step 5: Run the whole suite**

Expected: all existing tests plus `RideFixtureTest` pass (80 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/test
git commit -m "Add heart rate to ride fixtures and two clean outdoor rides"
```

---

### Task 1: `LoadGate` — outdoor-capable steady sampler (spec §4)

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/LoadGate.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/SteadyStateDetector.kt` (move `LoadVeSample` out; class itself is deleted in Task 6)
- Test: `app/src/test/kotlin/com/tymewear/karoo/LoadGateTest.kt`

**Interfaces:**
- Produces: `data class LoadVeSample(val loadW: Double, val hrBpm: Double, val ve: Double)` (in `LoadGate.kt`); `class LoadGate` with `fun onSample(loadW: Double?, hrBpm: Double?, ve: Double?, nowMs: Long): LoadVeSample?` and `fun reset()`.

Rules (must match exactly — the goldens depend on them):
- `L` = exponentially weighted power, `alpha = 1 − exp(−1/60)`; a null power counts as 0 W. `L` starts at the first sample's value.
- Keep the last 31 `L` values; `L30` is the oldest. Keep the last 30 coast flags (`loadW == null || loadW < 20`).
- VE window: last 30 values; a null VE **clears** it. HR window: last 30 values; a null HR **clears** it too (both windows share the freshness contract).
- A gap `nowMs − previousMs > 3000` resets everything (as `SteadyStateDetector` did).
- Accept when: ticks since reset ≥ 120; 31 `L` values held; `60 ≤ L ≤ 320`; `|L − L30| / L < 0.10`; coast flags ≤ 2; VE and HR windows each hold ≥ 15 values; mean VE > 0; mean HR ≥ 60.
- Emit `LoadVeSample(L, meanHr, meanVe)`.

- [ ] **Step 1: Move `LoadVeSample` and write the failing tests**

Delete `data class LoadVeSample(...)` from `SteadyStateDetector.kt` and create `LoadGate.kt` containing only:
```kotlin
package com.tymewear.karoo

/** One comparable observation: ventilation at a smoothed load and heart rate. */
data class LoadVeSample(val loadW: Double, val hrBpm: Double, val ve: Double)
```
Update `SteadyStateDetector.onSample` to return `LoadVeSample(loadW, 0.0, veWindow.average())` so it still compiles (it is deleted in Task 6).

Test file:
```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGateTest {

    private fun feed(f: RideFixture, g: LoadGate = LoadGate()): List<LoadVeSample> {
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { out.add(it) }
        return out
    }

    @Test
    fun `emits nothing during the first two minutes`() {
        val g = LoadGate()
        repeat(119) { i -> assertNull(g.onSample(200.0, 140.0, 60.0, i * 1000L)) }
    }

    @Test
    fun `emits once load has settled`() {
        val g = LoadGate()
        var last: LoadVeSample? = null
        repeat(200) { i -> last = g.onSample(200.0, 140.0, 60.0, i * 1000L) ?: last }
        assertNotNull(last)
        assertEquals(200.0, last!!.loadW, 0.5)
        assertEquals(140.0, last!!.hrBpm, 0.01)
        assertEquals(60.0, last!!.ve, 0.01)
    }

    @Test
    fun `rejects while the smoothed load is still changing`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(150.0, 130.0, 50.0, i * 1000L) }
        // Step to 250 W: for a while |L - L30|/L exceeds 10%.
        var rejected = 0
        repeat(40) { i -> if (g.onSample(250.0, 150.0, 80.0, (200 + i) * 1000L) == null) rejected++ }
        assertTrue("a step change must be rejected for tens of seconds, got $rejected rejections", rejected >= 30)
    }

    @Test
    fun `rejects when coasting occurred in the last thirty seconds`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(200.0, 140.0, 60.0, i * 1000L) }
        // Three zero-power seconds = 3 coast flags > 2 allowed.
        repeat(3) { i -> g.onSample(0.0, 140.0, 60.0, (200 + i) * 1000L) }
        assertNull(g.onSample(200.0, 140.0, 60.0, 203_000L))
    }

    @Test
    fun `a null VE clears the VE window`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(200.0, 140.0, 60.0, i * 1000L) }
        g.onSample(200.0, 140.0, null, 200_000L)
        assertNull("window must refill before emitting again", g.onSample(200.0, 140.0, 60.0, 201_000L))
    }

    @Test
    fun `requires heart rate`() {
        val g = LoadGate()
        var emitted = 0
        repeat(300) { i -> if (g.onSample(200.0, null, 60.0, i * 1000L) != null) emitted++ }
        assertEquals(0, emitted)
    }

    @Test
    fun `a large clock gap resets the gate`() {
        val g = LoadGate()
        repeat(200) { i -> g.onSample(200.0, 140.0, 60.0, i * 1000L) }
        assertNotNull(g.onSample(200.0, 140.0, 60.0, 200_000L))
        assertNull(g.onSample(200.0, 140.0, 60.0, 320_000L)) // 2-minute gap
    }

    @Test
    fun `usable fractions on real rides match the reference`() {
        // Reference (Python, same rules): indoor 79-96%, outdoor easy 35.8%, outdoor long 59.2%.
        fun frac(name: String): Double { val f = RideFixture.load(name); return feed(f).size.toDouble() / f.watts.size }
        assertEquals(0.358, frac("outdoor_easy_2026-09-02.csv"), 0.02)
        assertEquals(0.592, frac("outdoor_long_2026-04-25.csv"), 0.02)
        assertEquals(0.959, frac("ride_2026-02-24.csv"), 0.02)
        assertEquals(0.792, frac("ride_2026-03-16.csv"), 0.02)
        assertEquals(0.289, frac("ramp_2026-02-21.csv"), 0.02)
    }
}
```

- [ ] **Step 2: Run to see it fail**

Expected: compile error — `LoadGate` not defined.

- [ ] **Step 3: Implement `LoadGate`**

Append to `LoadGate.kt`:
```kotlin
import kotlin.math.abs
import kotlin.math.exp

/**
 * Selects samples where ventilation is answering to a load that has settled.
 *
 * Outdoors, power over any 60 s window swings by 25-45 % (coasting, wind, gradient), so a
 * "power CV < 12 %" rule yields nothing at all — the 0.5.0 estimator produced zero samples
 * on a real ride. VE responds to load with a time constant of roughly a minute, so the load
 * VE is actually answering to is an exponentially weighted power, and the question is
 * whether *that* is stable, not whether the raw power is.
 */
class LoadGate(
    private val tauSeconds: Double = DEFAULT_TAU_SECONDS,
    private val minLoadW: Double = DEFAULT_MIN_LOAD_W,
    private val maxLoadW: Double = DEFAULT_MAX_LOAD_W,
    private val maxLoadChangeFraction: Double = DEFAULT_MAX_LOAD_CHANGE_FRACTION,
    private val lookbackTicks: Int = DEFAULT_LOOKBACK_TICKS,
    private val maxCoastTicks: Int = DEFAULT_MAX_COAST_TICKS,
    private val coastBelowW: Double = DEFAULT_COAST_BELOW_W,
    private val windowTicks: Int = DEFAULT_WINDOW_TICKS,
    private val minWindowFill: Int = DEFAULT_MIN_WINDOW_FILL,
    private val minHrBpm: Double = DEFAULT_MIN_HR_BPM,
    private val warmupTicks: Int = DEFAULT_WARMUP_TICKS,
    private val maxSampleGapMs: Long = DEFAULT_MAX_SAMPLE_GAP_MS,
) {
    private val alpha = 1.0 - exp(-1.0 / tauSeconds)
    private var load: Double? = null
    private val loadHistory = ArrayDeque<Double>()
    private val coastFlags = ArrayDeque<Boolean>()
    private val veWindow = ArrayDeque<Double>()
    private val hrWindow = ArrayDeque<Double>()
    private var ticks = 0
    private var lastSampleMs: Long? = null

    fun onSample(loadW: Double?, hrBpm: Double?, ve: Double?, nowMs: Long): LoadVeSample? {
        val previous = lastSampleMs
        lastSampleMs = nowMs
        if (previous != null && nowMs - previous > maxSampleGapMs) reset(keepClock = true)
        ticks++

        val power = loadW ?: 0.0
        val l = load?.let { it + alpha * (power - it) } ?: power
        load = l
        loadHistory.addLast(l)
        while (loadHistory.size > lookbackTicks + 1) loadHistory.removeFirst()
        coastFlags.addLast(loadW == null || loadW < coastBelowW)
        while (coastFlags.size > lookbackTicks) coastFlags.removeFirst()
        if (ve == null) veWindow.clear() else { veWindow.addLast(ve); while (veWindow.size > windowTicks) veWindow.removeFirst() }
        if (hrBpm != null) { hrWindow.addLast(hrBpm); while (hrWindow.size > windowTicks) hrWindow.removeFirst() }

        if (ticks < warmupTicks) return null
        if (loadHistory.size < lookbackTicks + 1) return null
        if (l < minLoadW || l > maxLoadW) return null
        if (abs(l - loadHistory.first()) / l >= maxLoadChangeFraction) return null
        if (coastFlags.count { it } > maxCoastTicks) return null
        if (veWindow.size < minWindowFill || hrWindow.size < minWindowFill) return null
        val meanVe = veWindow.average()
        val meanHr = hrWindow.average()
        if (meanVe <= 0.0 || meanHr < minHrBpm) return null
        return LoadVeSample(l, meanHr, meanVe)
    }

    fun reset() = reset(keepClock = false)

    private fun reset(keepClock: Boolean) {
        load = null; loadHistory.clear(); coastFlags.clear(); veWindow.clear(); hrWindow.clear(); ticks = 0
        if (!keepClock) lastSampleMs = null
    }

    companion object {
        const val DEFAULT_TAU_SECONDS = 60.0
        const val DEFAULT_MIN_LOAD_W = 60.0
        const val DEFAULT_MAX_LOAD_W = 320.0
        const val DEFAULT_MAX_LOAD_CHANGE_FRACTION = 0.10
        const val DEFAULT_LOOKBACK_TICKS = 30
        const val DEFAULT_MAX_COAST_TICKS = 2
        const val DEFAULT_COAST_BELOW_W = 20.0
        const val DEFAULT_WINDOW_TICKS = 30
        const val DEFAULT_MIN_WINDOW_FILL = 15
        const val DEFAULT_MIN_HR_BPM = 60.0
        const val DEFAULT_WARMUP_TICKS = 120
        const val DEFAULT_MAX_SAMPLE_GAP_MS = 3_000L
    }
}
```

- [ ] **Step 4: Run `LoadGateTest` and the whole suite**

Expected: all pass. If the fraction test misses by more than the tolerance, diff the rules against the list above (most likely: coast flag for null power, or the warm-up count).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/LoadGate.kt app/src/main/kotlin/com/tymewear/karoo/SteadyStateDetector.kt app/src/test/kotlin/com/tymewear/karoo/LoadGateTest.kt
git commit -m "Add LoadGate: lag-aware steady sampler that works outdoors"
```

---

### Task 2: Keyed `VeBaseline` and `BinDeviation` (spec §3, §5)

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/VeBaseline.kt`
- Rename+modify: `EfficiencyDeviation.kt` → `app/src/main/kotlin/com/tymewear/karoo/BinDeviation.kt`
- Modify tests: `VeBaselineTest.kt`, rename `EfficiencyDeviationTest.kt` → `BinDeviationTest.kt`

**Interfaces:**
- Produces: `VeBaseline(binWidth: Double = 20.0, minSamplesPerBin = 30, maxSamplesPerBin = 6000)` with `update(key: Double, ve: Double)`, `expectedVe(key: Double): Double?`, `binCentre(key)`, `coveredBins()`, `bins(): List<BinStat>`, `serialise()`, `VeBaseline.deserialise(text, binWidth, ...)`, `VeBaseline.DEFAULT_HR_BIN_WIDTH = 5.0`; `data class BinStat(val centre: Double, val meanVe: Double, val count: Int)`.
- Produces: `BinDeviation(baseline, binWidth, minSamplesPerBin, minMatchedBins)` with `add(key: Double, ve: Double)`, `deviation(): Deviation?`, `reset()`. `Deviation(fraction, matchedBins)` unchanged.

- [ ] **Step 1: Update the baseline tests**

In `VeBaselineTest.kt` replace every `update(LoadVeSample(x, y))` with `update(x, y)` and add:
```kotlin
    @Test
    fun `works as a heart-rate baseline with a five bpm width`() {
        val b = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH, minSamplesPerBin = 1)
        b.update(137.0, 60.0); b.update(138.0, 62.0)
        assertEquals(135.0, b.binCentre(137.0), 0.001)   // 137/5 = 27.4 -> 27 -> 135
        assertEquals(61.0, b.expectedVe(136.0)!!, 0.001)
    }

    @Test
    fun `bins reports centre mean and count in ascending order`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(180.0, 70.0); b.update(140.0, 50.0); b.update(140.0, 54.0)
        val bins = b.bins()
        assertEquals(listOf(140.0, 180.0), bins.map { it.centre })
        assertEquals(52.0, bins[0].meanVe, 0.001)
        assertEquals(2, bins[0].count)
    }

    @Test
    fun `pooled indoor fixtures reproduce the reference power bins`() {
        // Reference (Python, LoadGate rules, all six indoor fixtures): 160W 63.5, 180W 67.3, 200W 68.5, 220W 89.2.
        val b = VeBaseline()
        for (name in INDOOR) { val f = RideFixture.load(name); val g = LoadGate()
            for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { b.update(it.loadW, it.ve) } }
        assertEquals(63.5, b.expectedVe(160.0)!!, 1.0)
        assertEquals(67.3, b.expectedVe(180.0)!!, 1.0)
        assertEquals(89.2, b.expectedVe(220.0)!!, 1.5)
    }

    companion object {
        val INDOOR = listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv",
            "ride_2026-03-03.csv", "ride_2026-03-16.csv", "ride_2026-03-29.csv")
    }
```
Keep the existing `built from three real rides it covers the expected bins` test but feed it through `LoadGate` the same way (its bin expectations may shift; replace the numbers with `coveredBins() >= 3`).

- [ ] **Step 2: Run to see failures** (compile errors on `update(Double, Double)`, `bins()`, `DEFAULT_HR_BIN_WIDTH`).

- [ ] **Step 3: Generalise `VeBaseline`**

Rename the constructor parameter `binWidthW` → `binWidth` (and `DEFAULT_BIN_WIDTH_W` stays for power). Replace `update(sample: LoadVeSample)` with:
```kotlin
    fun update(key: Double, ve: Double) {
        val bin = bins.getOrPut(binCentre(key)) { Bin() }
        if (bin.count < maxSamplesPerBin) { bin.total += ve; bin.count += 1 }
        else bin.total = bin.total - bin.total / bin.count + ve
    }
```
Rename `binCentre(loadW)` → `binCentre(key)`, `expectedVe(loadW)` → `expectedVe(key)`. Add:
```kotlin
    fun bins(): List<BinStat> = binMap.entries.sortedBy { it.key }.map { (c, b) -> BinStat(c, b.total / b.count, b.count) }
```
Rename the private `bins` HashMap to `binMap` throughout the class (including `deserialise`) so the map and the new `bins()` accessor cannot be confused.
and at file top:
```kotlin
/** One baseline bin: its centre (W or bpm), mean VE and sample count. */
data class BinStat(val centre: Double, val meanVe: Double, val count: Int)
```
Add `const val DEFAULT_HR_BIN_WIDTH = 5.0` to the companion, and update the class doc: "keyed by any numeric load measure — power in 20 W bins, or heart rate in 5 bpm bins".

- [ ] **Step 4: Rename `EfficiencyDeviation` to `BinDeviation`**

```bash
git mv app/src/main/kotlin/com/tymewear/karoo/EfficiencyDeviation.kt app/src/main/kotlin/com/tymewear/karoo/BinDeviation.kt
git mv app/src/test/kotlin/com/tymewear/karoo/EfficiencyDeviationTest.kt app/src/test/kotlin/com/tymewear/karoo/BinDeviationTest.kt
```
In `BinDeviation.kt`: rename the class, rename `binWidthW` → `binWidth`, and replace `add(sample: LoadVeSample)` with
```kotlin
    fun add(key: Double, ve: Double) {
        observed.getOrPut(Math.round(key / binWidth) * binWidth) { ArrayList() }.add(ve)
    }
```
Class doc: "Compares observed ventilation against a [VeBaseline] at matched key (power bin or heart-rate bin). Used twice per ride: once keyed by power, once by heart rate — the pair separates a strap scale shift (both move) from a physiological change (only power moves); see the spec §3.1."

- [ ] **Step 5: Rewrite `BinDeviationTest` goldens**

Replace the body of the old `scores the rider's real rides against their February baseline` and helpers with the leave-one-out goldens (baseline = the other five indoor fixtures, samples via `LoadGate`):
```kotlin
    private data class Gated(val samples: List<LoadVeSample>)
    private fun gated(name: String): List<LoadVeSample> {
        val f = RideFixture.load(name); val g = LoadGate(); val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { out.add(it) }
        return out
    }
    private fun baselines(excluding: String): Pair<VeBaseline, VeBaseline> {
        val p = VeBaseline(); val h = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
        for (n in VeBaselineTest.INDOOR) if (n != excluding) for (s in gated(n)) { p.update(s.loadW, s.ve); h.update(s.hrBpm, s.ve) }
        return p to h
    }
    private fun score(name: String): Pair<Deviation?, Deviation?> {
        val (p, h) = baselines(excluding = name)
        val dp = BinDeviation(p); val dh = BinDeviation(h, binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
        for (s in gated(name)) { dp.add(s.loadW, s.ve); dh.add(s.hrBpm, s.ve) }
        return dp.deviation() to dh.deviation()
    }

    @Test
    fun `power and heart-rate deviations match the reference on real rides`() {
        // Reference (Python, identical rules), +/- 2 points. Pure-scale rides show devP ~ devHR;
        // the 2026-02-24 good day shows devP well below devHR.
        val expected = mapOf(
            "outdoor_easy_2026-09-02.csv" to (-10.35 to -22.49),
            "outdoor_long_2026-04-25.csv" to (22.16 to 16.39),
            "ride_2026-02-24.csv" to (-3.57 to 8.80),
            "ride_2026-03-03.csv" to (-10.10 to -9.48),
            "ride_2026-03-29.csv" to (-3.57 to -5.76),
        )
        for ((name, want) in expected) {
            val (dp, dh) = score(name)
            assertEquals("$name devP", want.first, dp!!.percent, 2.0)
            assertEquals("$name devHR", want.second, dh!!.percent, 2.0)
            assertTrue(dp.matchedBins >= 3); assertTrue(dh.matchedBins >= 3)
        }
    }

    @Test
    fun `the ramp self-scores near zero`() {
        val b = VeBaseline(); val s = gated("ramp_2026-02-21.csv")
        for (x in s) b.update(x.loadW, x.ve)
        val d = BinDeviation(b); for (x in s) d.add(x.loadW, x.ve)
        assertEquals(0.0, d.deviation()!!.percent, 2.0)   // measured -0.26
    }
```
Keep the synthetic tests (`10% more ventilation...`, `reset clears...`, quality-ratio test) adapted to `add(key, ve)`; drop `a ride with no power produces no deviation` if `outdoor_endurance_2026-08-09.csv` is removed, otherwise keep it (it has no watts, so `LoadGate` emits nothing).

- [ ] **Step 6: Run the suite** — expected all green.

- [ ] **Step 7: Commit**

```bash
git add -A app/src/main/kotlin/com/tymewear/karoo/VeBaseline.kt app/src/main/kotlin/com/tymewear/karoo/BinDeviation.kt app/src/test
git commit -m "Key the VE baseline by power or heart rate; rename EfficiencyDeviation to BinDeviation"
```

---

### Task 3: Scaled thresholds in `ZoneClassifier` (spec §3.2)

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/ZoneClassifier.kt`
- Test: `app/src/test/kotlin/com/tymewear/karoo/ZoneClassifierTest.kt`

**Interfaces:**
- Produces: `fun ZoneThresholds.scaled(factor: Double): ZoneThresholds`; `fun ZoneClassifier.effectiveThresholds(configured: ZoneThresholds, scale: Double?): ZoneThresholds`.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun `scaling multiplies every threshold`() {
        val s = t.scaled(0.8)
        assertEquals(58.4, s.vt1, 0.001); assertEquals(76.8, s.vt2, 0.001)
        assertEquals(89.6, s.topZ4, 0.001); assertEquals(104.0, s.vo2max, 0.001)
    }

    @Test
    fun `effective thresholds fall back to configured when no scale is known`() {
        assertEquals(t, ZoneClassifier.effectiveThresholds(t, null))
        assertEquals(t.scaled(1.2), ZoneClassifier.effectiveThresholds(t, 1.2))
    }

    @Test
    fun `a strap reading low moves a value up a zone once corrected`() {
        // Strap reads 20% low: 60 L/min displayed is really 75, above VT1=73.
        assertEquals(1, ZoneClassifier.zoneFor(60.0, t))
        assertEquals(2, ZoneClassifier.zoneFor(60.0, ZoneClassifier.effectiveThresholds(t, 0.8)))
    }
```

- [ ] **Step 2: Run — compile failure.**

- [ ] **Step 3: Implement**

```kotlin
/** The same thresholds expressed in a strap scale [factor] times the configured one. */
fun ZoneThresholds.scaled(factor: Double): ZoneThresholds =
    ZoneThresholds(vt1 * factor, vt2 * factor, topZ4 * factor, vo2max * factor)

object ZoneClassifier {
    fun zoneFor(ve: Double, thresholds: ZoneThresholds): Int = ...existing...

    /** Thresholds to classify today's raw VE against: configured × today's strap scale
     *  when one is known, configured otherwise. Dividing VE by the scale would give the
     *  same zone; scaling the thresholds leaves displayed and recorded VE untouched. */
    fun effectiveThresholds(configured: ZoneThresholds, scale: Double?): ZoneThresholds =
        if (scale == null) configured else configured.scaled(scale)
}
```

- [ ] **Step 4: Run — green. Step 5: Commit** `git commit -am "Add scaled and effective thresholds to ZoneClassifier"`

---

### Task 4: `SessionScale` (spec §3.3)

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/SessionScale.kt`
- Test: `app/src/test/kotlin/com/tymewear/karoo/SessionScaleTest.kt`

**Interfaces:**
- Produces:
```kotlin
sealed class ScaleStatus {
    object Calibrating : ScaleStatus()
    data class Locked(val target: Double) : ScaleStatus()
    data class OutOfRange(val raw: Double) : ScaleStatus()
}
class SessionScale(windowSeconds = 2400, minMatchedBins = 3, minScale = 0.6, maxScale = 1.6, updateIntervalSeconds = 60, easingSeconds = 30) {
    val status: ScaleStatus
    fun offer(devHr: Deviation?, recordingSeconds: Int)
    fun displayed(recordingSeconds: Int): Double?
    fun reset()
}
```
Semantics: `offer` is called once per second. Inside the window (`recordingSeconds <= windowSeconds`) a confident `devHr` (`matchedBins >= minMatchedBins`) sets `raw = 1 + fraction`; if `raw` is outside `[minScale, maxScale]` status becomes `OutOfRange(raw)` and `displayed` returns null; otherwise status becomes `Locked(raw)` — but only on the first lock or when `recordingSeconds − lastUpdateAt >= updateIntervalSeconds`. Outside the window nothing changes. `displayed` eases linearly from the previously displayed value (1.0 before the first lock) to the target over `easingSeconds`.

- [ ] **Step 1: Failing tests**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScaleTest {
    private fun dev(pct: Double, bins: Int = 3) = Deviation(pct / 100.0, bins)

    @Test
    fun `calibrating until a confident heart-rate deviation arrives`() {
        val s = SessionScale()
        s.offer(null, 100); assertNull(s.displayed(100)); assertTrue(s.status is ScaleStatus.Calibrating)
        s.offer(dev(-20.0, bins = 2), 101); assertNull(s.displayed(101))
    }

    @Test
    fun `locks to one plus the heart-rate deviation`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 600)
        assertEquals(ScaleStatus.Locked(0.8), s.status)
    }

    @Test
    fun `eases from one to the target over thirty seconds`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 600)
        assertEquals(1.0, s.displayed(600)!!, 1e-9)
        assertEquals(0.9, s.displayed(615)!!, 1e-9)
        assertEquals(0.8, s.displayed(630)!!, 1e-9)
        assertEquals(0.8, s.displayed(700)!!, 1e-9)
    }

    @Test
    fun `updates at most once a minute inside the window`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 600); s.offer(dev(-10.0), 630)
        assertEquals(ScaleStatus.Locked(0.8), s.status)
        s.offer(dev(-10.0), 660)
        assertEquals(ScaleStatus.Locked(0.9), s.status)
    }

    @Test
    fun `holds after the forty minute window closes`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 2000); s.offer(dev(10.0), 2401)
        assertEquals(ScaleStatus.Locked(0.8), s.status)
    }

    @Test
    fun `never locks if nothing confident arrives inside the window`() {
        val s = SessionScale()
        s.offer(dev(-20.0), 2401)
        assertTrue(s.status is ScaleStatus.Calibrating); assertNull(s.displayed(2401))
    }

    @Test
    fun `an implausible factor is reported not applied`() {
        val s = SessionScale()
        s.offer(dev(80.0), 600)
        assertEquals(ScaleStatus.OutOfRange(1.8), s.status)
        assertNull(s.displayed(600))
    }

    @Test
    fun `reset returns to calibrating`() {
        val s = SessionScale(); s.offer(dev(-20.0), 600); s.reset()
        assertTrue(s.status is ScaleStatus.Calibrating); assertNull(s.displayed(0))
    }
}
```

- [ ] **Step 2: Run — compile failure. Step 3: Implement**

```kotlin
package com.tymewear.karoo

sealed class ScaleStatus {
    object Calibrating : ScaleStatus()
    data class Locked(val target: Double) : ScaleStatus()
    data class OutOfRange(val raw: Double) : ScaleStatus()
}

/**
 * Today's strap scale factor — how far this session's tidal-volume scale sits from the
 * rider's baseline — estimated from the heart-rate-matched VE deviation (spec §3.1) and
 * held for the ride once learned (spec §3.3).
 */
class SessionScale(
    private val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    private val minMatchedBins: Int = DEFAULT_MIN_MATCHED_BINS,
    private val minScale: Double = DEFAULT_MIN_SCALE,
    private val maxScale: Double = DEFAULT_MAX_SCALE,
    private val updateIntervalSeconds: Int = DEFAULT_UPDATE_INTERVAL_SECONDS,
    private val easingSeconds: Int = DEFAULT_EASING_SECONDS,
) {
    var status: ScaleStatus = ScaleStatus.Calibrating
        private set
    private var lastUpdateAt: Int? = null
    private var easeFrom = 1.0
    private var easeStart = 0

    fun offer(devHr: Deviation?, recordingSeconds: Int) {
        if (recordingSeconds > windowSeconds) return
        if (devHr == null || devHr.matchedBins < minMatchedBins) return
        val last = lastUpdateAt
        if (last != null && recordingSeconds - last < updateIntervalSeconds) return
        val raw = 1.0 + devHr.fraction
        if (raw < minScale || raw > maxScale) { status = ScaleStatus.OutOfRange(raw); lastUpdateAt = recordingSeconds; return }
        easeFrom = displayed(recordingSeconds) ?: 1.0
        easeStart = recordingSeconds
        status = ScaleStatus.Locked(raw)
        lastUpdateAt = recordingSeconds
    }

    fun displayed(recordingSeconds: Int): Double? {
        val target = (status as? ScaleStatus.Locked)?.target ?: return null
        val t = ((recordingSeconds - easeStart).toDouble() / easingSeconds).coerceIn(0.0, 1.0)
        return easeFrom + (target - easeFrom) * t
    }

    fun reset() { status = ScaleStatus.Calibrating; lastUpdateAt = null; easeFrom = 1.0; easeStart = 0 }

    companion object {
        const val DEFAULT_WINDOW_SECONDS = 2400
        const val DEFAULT_MIN_MATCHED_BINS = 3
        const val DEFAULT_MIN_SCALE = 0.6
        const val DEFAULT_MAX_SCALE = 1.6
        const val DEFAULT_UPDATE_INTERVAL_SECONDS = 60
        const val DEFAULT_EASING_SECONDS = 30
    }
}
```

- [ ] **Step 4: Run — green. Step 5: Commit** `git add -A && git commit -m "Add SessionScale: lock today's strap scale from the HR-matched deviation"`

---

### Task 5: `SessionPipeline` — the pure per-ride pipeline with end-to-end replay tests (spec §3, §8)

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/SessionPipeline.kt`
- Test: `app/src/test/kotlin/com/tymewear/karoo/SessionPipelineTest.kt`

**Interfaces:**
- Consumes: `LoadGate`, `VeBaseline`, `BinDeviation`, `SessionScale`, `ZoneClassifier.effectiveThresholds`.
- Produces:
```kotlin
data class PipelineOutput(val scale: Double?, val scaleStatus: ScaleStatus, val dayQualityPercent: Double?, val devP: Deviation?, val devHr: Deviation?)
class SessionPipeline(powerBaseline: VeBaseline, hrBaseline: VeBaseline, rideCount: Int, minBaselineBins: Int, minBaselineRides: Int,
                      gate: LoadGate = LoadGate(), scale: SessionScale = SessionScale()) {
    val rideSamples: List<LoadVeSample>
    fun onSample(loadW: Double?, hrBpm: Double?, ve: Double?, nowMs: Long, recordingSeconds: Int): PipelineOutput
    fun reset()
}
```
Confidence: outputs are all null / `Calibrating` unless both baselines have `coveredBins() >= minBaselineBins` and `rideCount >= minBaselineRides`. `dayQualityPercent = devP.percent − devHr.percent` when both non-null. Samples are buffered in `rideSamples` (cap 20 000) for the owner to fold into the baselines at ride end.

- [ ] **Step 1: Failing tests**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPipelineTest {

    private fun baselines(excluding: String? = null): Pair<VeBaseline, VeBaseline> {
        val p = VeBaseline(); val h = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
        for (n in VeBaselineTest.INDOOR) if (n != excluding) {
            val f = RideFixture.load(n); val g = LoadGate()
            for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { p.update(it.loadW, it.ve); h.update(it.hrBpm, it.ve) }
        }
        return p to h
    }

    private class Replay(val outputs: List<PipelineOutput>, val zonesConfigured: List<Int>, val zonesCorrected: List<Int>)

    private fun replay(name: String, excluding: String? = name): Replay {
        val (p, h) = baselines(excluding)
        val pipe = SessionPipeline(p, h, rideCount = 5, minBaselineBins = 3, minBaselineRides = 2)
        val f = RideFixture.load(name)
        val configured = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val outs = ArrayList<PipelineOutput>(); val zc = ArrayList<Int>(); val zs = ArrayList<Int>()
        for (i in f.watts.indices) {
            val o = pipe.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L, recordingSeconds = i)
            outs.add(o)
            val ve = f.ve[i] ?: continue
            zc.add(ZoneClassifier.zoneFor(ve, configured))
            zs.add(ZoneClassifier.zoneFor(ve, ZoneClassifier.effectiveThresholds(configured, o.scale)))
        }
        return Replay(outs, zc, zs)
    }

    @Test
    fun `stays calibrating with a thin baseline`() {
        val pipe = SessionPipeline(VeBaseline(), VeBaseline(binWidth = 5.0), rideCount = 0, minBaselineBins = 3, minBaselineRides = 2)
        val f = RideFixture.load("ride_2026-02-24.csv")
        var last: PipelineOutput? = null
        for (i in f.watts.indices) last = pipe.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L, i)
        assertNull(last!!.scale); assertNull(last.dayQualityPercent); assertTrue(last.scaleStatus is ScaleStatus.Calibrating)
        assertTrue("samples must still be buffered for the baseline", pipe.rideSamples.size > 2000)
    }

    @Test
    fun `easy outdoor ride locks a low strap scale inside the window and moves time into zone two`() {
        val r = replay("outdoor_easy_2026-09-02.csv", excluding = null)
        val firstLock = r.outputs.indexOfFirst { it.scaleStatus is ScaleStatus.Locked }
        // Reference replay (Python, same rules): first lock at 1622 s with 0.834; the window closes at
        // 2400 s so the held scale is 0.837 even though the whole-ride devHR ends at -22.5%.
        assertTrue("must lock inside 40 min (measured 27 min), got $firstLock s", firstLock in 1..2400)
        val finalScale = r.outputs.last().scale!!
        assertEquals(0.837, finalScale, 0.04)
        val z2Configured = r.zonesConfigured.count { it >= 2 }.toDouble() / r.zonesConfigured.size
        val z2Corrected = r.zonesCorrected.count { it >= 2 }.toDouble() / r.zonesCorrected.size
        assertTrue("configured thresholds: <0.5% above Z1, got $z2Configured", z2Configured < 0.005)
        assertTrue("corrected thresholds: >=1% above Z1 (measured 1.4%), got $z2Corrected", z2Corrected >= 0.01)
    }

    @Test
    fun `long outdoor ride locks a high strap scale and pulls time out of the upper zones`() {
        val r = replay("outdoor_long_2026-04-25.csv", excluding = null)
        // Reference replay: first lock at 471 s (1.26), held scale 1.25; Z2+ time 57.0% -> 27.0%.
        assertEquals(1.25, r.outputs.last().scale!!, 0.05)
        val upConfigured = r.zonesConfigured.count { it >= 2 }.toDouble() / r.zonesConfigured.size
        val upCorrected = r.zonesCorrected.count { it >= 2 }.toDouble() / r.zonesCorrected.size
        assertEquals(0.57, upConfigured, 0.03)
        assertEquals(0.27, upCorrected, 0.05)
    }

    @Test
    fun `a good indoor day shows negative day quality with a near-unity scale`() {
        // 2026-02-24 reference replay: dayQuality at ride end -12.4; scale locked in the first 40 min
        // and held at 1.197 (the whole-ride devHR of +8.8% is lower because HR drifted later on).
        val r = replay("ride_2026-02-24.csv")
        val last = r.outputs.last()
        assertEquals(-12.4, last.dayQualityPercent!!, 3.0)
        assertEquals(1.197, last.scale!!, 0.05)
    }

    @Test
    fun `reset clears deviations scale and buffered samples`() {
        val (p, h) = baselines()
        val pipe = SessionPipeline(p, h, 5, 3, 2)
        val f = RideFixture.load("ride_2026-03-03.csv")
        for (i in f.watts.indices) pipe.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L, i)
        assertTrue(pipe.rideSamples.isNotEmpty())
        pipe.reset()
        assertTrue(pipe.rideSamples.isEmpty())
        assertNull(pipe.onSample(200.0, 140.0, 60.0, 0L, 0).scale)
    }
}
```

- [ ] **Step 2: Run — compile failure. Step 3: Implement**

```kotlin
package com.tymewear.karoo

data class PipelineOutput(
    val scale: Double?,
    val scaleStatus: ScaleStatus,
    val dayQualityPercent: Double?,
    val devP: Deviation?,
    val devHr: Deviation?,
)

/**
 * One ride's worth of the ventilatory-state maths, with no Android in it.
 *
 * Feeds every 1 Hz tick through [LoadGate]; each accepted sample is scored against the
 * power baseline and the heart-rate baseline. The heart-rate deviation is today's strap
 * scale; the difference between the two deviations is the physiological "day quality"
 * (spec §3.1). Samples are buffered, not folded into the baselines: the owner does that
 * at ride end, after normalising by the scale (spec §3.4), because scoring a ride against
 * a baseline it is feeding drags its own deviation toward zero.
 */
class SessionPipeline(
    private val powerBaseline: VeBaseline,
    private val hrBaseline: VeBaseline,
    private val rideCount: Int,
    private val minBaselineBins: Int,
    private val minBaselineRides: Int,
    private val gate: LoadGate = LoadGate(),
    private val scale: SessionScale = SessionScale(),
) {
    private val devPower = BinDeviation(powerBaseline)
    private val devHeart = BinDeviation(hrBaseline, binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
    private val _rideSamples = ArrayList<LoadVeSample>()
    val rideSamples: List<LoadVeSample> get() = _rideSamples

    private val confident: Boolean
        get() = powerBaseline.coveredBins() >= minBaselineBins &&
            hrBaseline.coveredBins() >= minBaselineBins &&
            rideCount >= minBaselineRides

    fun onSample(loadW: Double?, hrBpm: Double?, ve: Double?, nowMs: Long, recordingSeconds: Int): PipelineOutput {
        gate.onSample(loadW, hrBpm, ve, nowMs)?.let { s ->
            devPower.add(s.loadW, s.ve)
            devHeart.add(s.hrBpm, s.ve)
            if (_rideSamples.size < MAX_RIDE_SAMPLES) _rideSamples.add(s)
        }
        if (!confident) return PipelineOutput(null, ScaleStatus.Calibrating, null, null, null)
        val dp = devPower.deviation()
        val dh = devHeart.deviation()
        scale.offer(dh, recordingSeconds)
        val dq = if (dp != null && dh != null) dp.percent - dh.percent else null
        return PipelineOutput(scale.displayed(recordingSeconds), scale.status, dq, dp, dh)
    }

    fun reset() { gate.reset(); devPower.reset(); devHeart.reset(); scale.reset(); _rideSamples.clear() }

    companion object { const val MAX_RIDE_SAMPLES = 20_000 }
}
```

- [ ] **Step 4: Run — green** (the replay tests take a few seconds). **Step 5: Commit** `git add -A && git commit -m "Add SessionPipeline with end-to-end replay tests on real rides"`

---

### Task 6: Rewire `VentilatoryState`, effective thresholds everywhere, remove the old estimators (spec §2.1, §3.4, §5)

**Files:**
- Modify: `VentilatoryState.kt` (rewrite), `TymewearData.kt`, `VeGraphDataType.kt`, `TymewearExtension.kt`, `Constants.kt`
- Delete: `SteadyStateDetector.kt`, `ThresholdShift.kt`, `DriftTracker.kt`, `ThresholdPowerDataType.kt`, `BreathingDriftDataType.kt`, `res/layout/view_threshold_power.xml`, `res/layout/view_breathing_drift.xml`, tests `SteadyStateDetectorTest.kt`, `ThresholdShiftTest.kt`, `DriftTrackerTest.kt`
- Modify: `res/xml/extension_info.xml`, `TymewearDevice.kt`, `res/values/strings.xml` (remove the two fields)
- Test: `RideLifecycleTest` stays; no new JVM test (Android shell) — verified by build + Task 12 on-device.

**Interfaces:**
- Produces on `VentilatoryState`: `val scale: StateFlow<Double?>`, `val scaleStatus: StateFlow<ScaleStatus>`, `val dayQuality: StateFlow<Double?>`, `val baselineBins: StateFlow<Int>`, `fun onSample(loadW: Double?, hrBpm: Double?)`, `fun lastRideScale(context): Double?`, `fun summaryForFit(): Pair<Double?, Double?>` (scale, dayQuality — current if riding else last ride's). Existing `load`, `onRideStart`, `onRidePause`, `onRideEnd`, `reloadEnabledFlag`, `resetBaseline`, `persistedStatus`, `isEnabled` keep their signatures.
- Produces on `TymewearData`: `fun configuredThresholds(): ZoneThresholds`; `currentThresholds()` now returns `ZoneClassifier.effectiveThresholds(configuredThresholds(), if (VentilatoryState.isEnabled()) VentilatoryState.scale.value else null)`.

- [ ] **Step 1: Rewrite `VentilatoryState`**

Keep the file's structure and comments where they still apply; the state becomes:
```kotlin
    private const val KEY_BASELINE = "baseline_bins"          // power
    private const val KEY_HR_BASELINE = "baseline_hr_bins"
    private const val KEY_UPDATED = "baseline_updated_at"
    private const val KEY_RIDES = "baseline_ride_count"
    private const val KEY_ENABLED = "dynamic_state_enabled"
    private const val KEY_LAST_SCALE = "last_ride_scale"      // float; 0 = none
    private const val KEY_LAST_DAY_QUALITY = "last_ride_day_quality"

    private var powerBaseline = VeBaseline()
    private var hrBaseline = VeBaseline(binWidth = VeBaseline.DEFAULT_HR_BIN_WIDTH)
    private var pipeline = newPipeline()
    private var rideCount = 0
    private var recordingSeconds = 0
    private var lastRideScale: Double? = null
    private var lastRideDayQuality: Double? = null

    private val _scale = MutableStateFlow<Double?>(null);  val scale: StateFlow<Double?> = _scale.asStateFlow()
    private val _scaleStatus = MutableStateFlow<ScaleStatus>(ScaleStatus.Calibrating); val scaleStatus = _scaleStatus.asStateFlow()
    private val _dayQuality = MutableStateFlow<Double?>(null); val dayQuality: StateFlow<Double?> = _dayQuality.asStateFlow()
    private val _baselineBins = MutableStateFlow(0); val baselineBins: StateFlow<Int> = _baselineBins.asStateFlow()

    private fun newPipeline() = SessionPipeline(powerBaseline, hrBaseline, rideCount,
        Constants.STATE_MIN_BASELINE_BINS, Constants.STATE_MIN_BASELINE_RIDES)
```
`load(context)`: read both baselines; **migration** — if `KEY_HR_BASELINE` is absent but `KEY_BASELINE` is present, the stored baseline came from the 0.5.0 sampler (different rules, never scale-normalised): discard both and set `rideCount = 0`, log `Timber.i("Baseline from 0.5.0 discarded; recalibrating with the session-scale pipeline")`. Read `lastRideScale` (`getFloat(KEY_LAST_SCALE, 0f).takeIf { it > 0f }?.toDouble()`).

`onSample(loadW, hrBpm)`:
```kotlin
    fun onSample(loadW: Double?, hrBpm: Double?) {
        synchronized(lock) {
            if (!enabled || !lifecycle.isActive || lifecycle.isPaused) return
            recordingSeconds++
            val fresh = TymewearData.isDataFresh()
            val ve = if (fresh) TymewearData.smoothMinuteVolume.value.takeIf { it > 0.0 } else null
            val out = pipeline.onSample(loadW, hrBpm?.takeIf { it > 0.0 }, ve, System.currentTimeMillis(), recordingSeconds)
            _scale.value = out.scale
            _scaleStatus.value = out.scaleStatus
            _dayQuality.value = out.dayQualityPercent
        }
    }
```
`onRideStart`: on a fresh start `pipeline = newPipeline(); recordingSeconds = 0;` and null the three flows.
`onRideEnd`:
```kotlin
            if (!lifecycle.onIdle()) return
            val factor = _scale.value ?: 1.0
            for (s in pipeline.rideSamples) { powerBaseline.update(s.loadW, s.ve / factor); hrBaseline.update(s.hrBpm, s.ve / factor) }
            lastRideScale = _scale.value; lastRideDayQuality = _dayQuality.value
            rideCount += 1
            _baselineBins.value = powerBaseline.coveredBins()
            persist(context)
            pipeline = newPipeline()
            _scale.value = null; _scaleStatus.value = ScaleStatus.Calibrating; _dayQuality.value = null
```
`persist` writes both baselines, `KEY_LAST_SCALE` (`(lastRideScale ?: 0.0).toFloat()`), `KEY_LAST_DAY_QUALITY`, rides, updated-at. `resetBaseline` resets both baselines, `rideCount`, `lastRideScale`, and re-creates the pipeline. Add:
```kotlin
    fun lastRideScale(context: Context): Double? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_LAST_SCALE, 0f).takeIf { it > 0f }?.toDouble()

    /** For the FIT session summary: this ride's values while recording, else the last ride's. */
    fun summaryForFit(): Pair<Double?, Double?> = synchronized(lock) {
        if (lifecycle.isActive) _scale.value to _dayQuality.value else lastRideScale to lastRideDayQuality
    }
```
Delete `ThresholdReason`, the power/drift flows, and `BaselineStatus` stays.

- [ ] **Step 2: `TymewearData` effective thresholds**

```kotlin
    /** The rider's entered thresholds, untouched. Settings and evidence use these. */
    fun configuredThresholds(): ZoneThresholds = ZoneThresholds(vt1Threshold, vt2Threshold, topZ4Threshold, vo2maxThreshold)

    /** Thresholds every zone consumer classifies against: configured × today's strap scale
     *  when the Beta is on and the scale has locked (spec §3.2), configured otherwise. */
    fun currentThresholds(): ZoneThresholds =
        ZoneClassifier.effectiveThresholds(configuredThresholds(), if (VentilatoryState.isEnabled()) VentilatoryState.scale.value else null)
```
`zoneFor(ve)` is unchanged and therefore now scale-aware. Note in the doc that `_veZone` is recomputed per packet, so a scale change propagates within one breath.

- [ ] **Step 3: `VeGraphDataType.renderGraph`** — replace the four `TymewearData.*Threshold` reads with `val t = TymewearData.currentThresholds()` and use `t.vt1`, `t.vt2`, `t.topZ4`, `t.vo2max`.

- [ ] **Step 4: `TymewearExtension` power subscription**

```kotlin
                        when (state) {
                            is StreamState.Streaming ->
                                VentilatoryState.onSample(state.dataPoint.singleValue, TymewearData.heartRate.value)
                            else -> VentilatoryState.onSample(null, TymewearData.heartRate.value)
                        }
```
(`TymewearData.powerW` is added in Task 8.)

- [ ] **Step 5: Remove the old estimators and fields**

```bash
git rm app/src/main/kotlin/com/tymewear/karoo/SteadyStateDetector.kt app/src/main/kotlin/com/tymewear/karoo/ThresholdShift.kt app/src/main/kotlin/com/tymewear/karoo/DriftTracker.kt \
       app/src/main/kotlin/com/tymewear/karoo/ThresholdPowerDataType.kt app/src/main/kotlin/com/tymewear/karoo/BreathingDriftDataType.kt \
       app/src/main/res/layout/view_threshold_power.xml app/src/main/res/layout/view_breathing_drift.xml \
       app/src/test/kotlin/com/tymewear/karoo/SteadyStateDetectorTest.kt app/src/test/kotlin/com/tymewear/karoo/ThresholdShiftTest.kt app/src/test/kotlin/com/tymewear/karoo/DriftTrackerTest.kt
```
Remove the `threshold_power` and `br_drift` `<DataType>` blocks from `extension_info.xml`, the two `DataType.dataTypeId(extension, ...)` lines from `TymewearDevice.kt`, their four strings, the two `ThresholdPowerDataType(...)`/`BreathingDriftDataType(...)` entries from the extension's data-type list (`grep -n "ThresholdPowerDataType\|BreathingDriftDataType" app/src/main -r`), and `STATE_DEFAULT_DRIFT_ALERT_PCT` from `Constants.kt` (its UI is removed in Task 7; leave `MainScreen` compiling by removing the drift references there in the same commit — see Task 7 Step 1 for the exact edits, do them now if the build needs them).

- [ ] **Step 6: Build and test**

```bash
./gradlew :app:assembleDebug -q && ./gradlew :app:testDebugUnitTest -q
```
Expected: builds; suite green (the deleted tests are gone, everything else passes).

- [ ] **Step 7: Commit** `git add -A && git commit -m "Drive zone thresholds from today's strap scale; remove threshold-power and drift estimators"`

---

### Task 7: "Vent State" field two-line rendering and settings clean-up (spec §2.1, §2.2 partial)

**Files:**
- Modify: `VentilatoryStateDataType.kt`, `res/layout/view_vent_state.xml`, `res/values/strings.xml`, `screens/MainScreen.kt`, `MainActivity.kt`

**Interfaces:**
- Consumes: `VentilatoryState.dayQuality`, `.scale`, `.scaleStatus`, `.isEnabled()`, `.lastRideScale(context)`.
- Produces: `PrefsData` loses `driftAlertEnabled`/`driftAlertPct`; `MainScreen` gains parameter `loadLastRideScale: () -> Double?`.

- [ ] **Step 1: Settings clean-up**

In `MainScreen.kt` remove the two drift fields from `PrefsData`, their `remember` state, the `LaunchedEffect` lines, the `Switch`/`OutlinedTextField` block, the validation clauses, and the `PrefsData(...)` arguments. In `MainActivity.kt` remove the two `put`/`get` lines. Replace the Beta description text with:
"Learns how your breathing normally relates to power and heart rate, corrects today's zone colours for the strap's session-to-session scale, and shows how today compares with your normal. Requires a power meter and heart rate."
Add after the baseline status text:
```kotlin
        val lastScale = remember { loadLastRideScale() }
        if (lastScale != null) {
            val pct = ((lastScale - 1.0) * 100).roundToInt()
            Text(
                text = "Last ride the strap read ${abs(pct)}% ${if (pct < 0) "low" else "high"}" +
                    if (abs(pct) >= 10) " — check strap tension and position." else ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
```
Wire `loadLastRideScale = { VentilatoryState.lastRideScale(applicationContext) }` in `MainActivity`.

- [ ] **Step 2: Layout** — replace `view_vent_state.xml` body with a vertical `LinearLayout` (gravity center) holding `text_value` (bold, 24sp) above `text_unit` (10sp, `#B3FFFFFF`), inside the `container` FrameLayout.

- [ ] **Step 3: Field rendering**

`startStream`: emit `dayQuality` as before (`NotAvailable` when disabled or null).
`startView`: `combine(VentilatoryState.dayQuality, VentilatoryState.scale, VentilatoryState.scaleStatus) { dq, sc, st -> Triple(dq, sc, st) }.collect { (dq, sc, st) -> ... }` with:
```kotlin
                val enabled = VentilatoryState.isEnabled()
                val value = when { !enabled -> "off"; dq == null -> "cal"; else -> String.format("%+.0f%%", dq) }
                val unit = when {
                    !enabled -> ""
                    st is ScaleStatus.OutOfRange -> "scale n/a"
                    sc != null -> String.format("×%.2f", sc)
                    else -> "learning"
                }
                val colour = when {
                    dq == null -> Constants.NO_DATA_COLOR
                    dq <= -5.0 -> Constants.ZONE_COLORS_SOLID[0]   // fresher than normal
                    dq >= 5.0 -> Constants.ZONE_COLORS_SOLID[2]    // heavier than normal
                    else -> Constants.ZONE_COLORS_SOLID[1]
                }
```
Update `vent_state_desc` to "Beta. Today vs. your normal (after strap-scale correction), and today's strap scale".

- [ ] **Step 4: Build, install on the Karoo, open the field picker**

```bash
./gradlew :app:assembleDebug -q && ~/Library/Android/sdk/platform-tools/adb -s 00442GA241130232 install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: exactly one Beta field (Vent State) in the picker; VT1 Power and BR Drift gone; settings screen shows no drift controls. (If the Karoo is not connected, note it in the ledger and continue; Task 12 covers device verification.)

- [ ] **Step 5: Commit** `git add -A && git commit -m "Show day quality and strap scale on the Vent State field; drop drift settings"`

---

### Task 8: "Power + VE zone" field (spec §2.1)

**Files:**
- Modify: `TymewearData.kt`, `TymewearExtension.kt`, `res/xml/extension_info.xml`, `TymewearDevice.kt`, `res/values/strings.xml`
- Create: `PowerZoneDataType.kt`, `res/layout/view_power_zone.xml`
- Test: `app/src/test/kotlin/com/tymewear/karoo/PowerAverageTest.kt` for the pure 3 s averager

**Interfaces:**
- Produces: `TymewearData.powerW: StateFlow<Double?>`, `TymewearData.updatePower(w: Double?)`; `class PowerAverage(windowSize: Int = 3)` with `fun add(w: Double?): Double?` (null resets, returns mean of the window or null when empty) in `PowerZoneDataType.kt`.

- [ ] **Step 1: Failing test**

```kotlin
package com.tymewear.karoo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class PowerAverageTest {
    @Test fun `averages the last three values`() {
        val a = PowerAverage(3)
        assertEquals(100.0, a.add(100.0)!!, 1e-9); assertEquals(150.0, a.add(200.0)!!, 1e-9)
        assertEquals(200.0, a.add(300.0)!!, 1e-9); assertEquals(300.0, a.add(400.0)!!, 1e-9)
    }
    @Test fun `a gap resets the window`() {
        val a = PowerAverage(3); a.add(100.0); a.add(100.0)
        assertNull(a.add(null)); assertEquals(300.0, a.add(300.0)!!, 1e-9)
    }
}
```

- [ ] **Step 2: Run — compile failure. Step 3: Implement**

`TymewearData`:
```kotlin
    private val _powerW = MutableStateFlow<Double?>(null)
    /** Latest Karoo power sample, null when the power stream is unavailable. Independent of the Beta. */
    val powerW: StateFlow<Double?> = _powerW.asStateFlow()
    fun updatePower(w: Double?) { _powerW.value = w }
```
`TymewearExtension` power collector: call `TymewearData.updatePower(state.dataPoint.singleValue)` / `updatePower(null)` before the `VentilatoryState.onSample` calls.

`view_power_zone.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/container" android:layout_width="match_parent" android:layout_height="match_parent">
    <View android:id="@+id/zone_bar" android:layout_width="match_parent" android:layout_height="6dp"
        android:layout_gravity="top" android:background="#616161" />
    <LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_gravity="center" android:baselineAligned="true" android:orientation="horizontal">
        <TextView android:id="@+id/text_value" android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:text="--" android:textColor="#FFFFFF" android:textSize="24sp" android:textStyle="bold" />
        <TextView android:id="@+id/text_unit" android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginStart="2dp" android:text="W" android:textColor="#B3FFFFFF" android:textSize="10sp" />
    </LinearLayout>
</FrameLayout>
```
`PowerZoneDataType.kt`:
```kotlin
package com.tymewear.karoo

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Rolling mean of the last [windowSize] power samples; a null (stream gap) resets it. */
class PowerAverage(private val windowSize: Int = 3) {
    private val window = ArrayDeque<Double>()
    fun add(w: Double?): Double? {
        if (w == null) { window.clear(); return null }
        window.addLast(w); while (window.size > windowSize) window.removeFirst()
        return window.average()
    }
}

/**
 * 3-second power with a strip in the colour of the rider's *current VE zone* — for riders
 * whose main screen shows power, not VE. The zone is what the lungs say right now, via the
 * same single classification path as every other field (so it is scale-corrected when the
 * Beta is on). It never maps power to a zone.
 */
@OptIn(FlowPreview::class)
class PowerZoneDataType(extension: String) : DataTypeImpl(extension, "power_vz") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val avg = PowerAverage()
        scope.launch {
            TymewearData.powerW.collect { w ->
                val mean = avg.add(w)
                if (mean == null) emitter.onNext(StreamState.NotAvailable)
                else emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to mean))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val avg = PowerAverage()
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f
        scope.launch {
            combine(TymewearData.powerW, TymewearData.veZone) { w, z -> w to z }.sample(1000L).collect { (w, zone) ->
                val mean = avg.add(w)
                val views = RemoteViews(context.packageName, R.layout.view_power_zone)
                views.setTextViewText(R.id.text_value, if (mean == null) "--" else String.format("%.0f", mean))
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                // Grey when breathing is stale: a dropped strap must never leave a stale colour on screen.
                val colour = if (TymewearData.isDataFresh() && zone in 1..5) Constants.ZONE_COLORS_SOLID[zone - 1] else Constants.NO_DATA_COLOR
                views.setInt(R.id.zone_bar, "setBackgroundColor", colour)
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
```
Register: `extension_info.xml` `<DataType typeId="power_vz" displayName="@string/power_vz_name" description="@string/power_vz_desc" graphical="true" icon="@drawable/ic_breathing" />`; `TymewearDevice.dataTypes` add `DataType.dataTypeId(extension, "power_vz")`; strings `power_vz_name` = "Power + VE zone", `power_vz_desc` = "3s power with a strip coloured by your current ventilation zone"; add `PowerZoneDataType(extension)` to the extension's data-type list.

- [ ] **Step 4: Run tests, build, install; add the field to a page** — strip colour follows the VE field's colour; grey when the strap is off; `--` with no power meter.

- [ ] **Step 5: Commit** `git add -A && git commit -m "Add Power + VE zone field"`

---

### Task 9: FIT session fields for scale and day quality (spec §2.3)

**Files:**
- Modify: `Protocol.kt`, `TymewearExtension.kt` (`writeSessionSummary`), `README.md` (FIT section)

- [ ] **Step 1: Define the fields** (numbers 37 and 38 are the next free ones after 36):
```kotlin
    val FIT_FIELD_VE_SCALE = DeveloperField(fieldDefinitionNumber = 37, fitBaseTypeId = FIT_FLOAT32, fieldName = "tyme_ve_scale", units = "")
    val FIT_FIELD_DAY_QUALITY = DeveloperField(fieldDefinitionNumber = 38, fitBaseTypeId = FIT_FLOAT32, fieldName = "tyme_day_quality", units = "%")
```
- [ ] **Step 2: Write them** in `writeSessionSummary`, after the zone fields, only when present:
```kotlin
        val (scale, dayQuality) = VentilatoryState.summaryForFit()
        val extra = ArrayList<FieldValue>()
        if (VentilatoryState.isEnabled() && scale != null) extra.add(FieldValue(Protocol.FIT_FIELD_VE_SCALE, scale))
        if (VentilatoryState.isEnabled() && dayQuality != null) extra.add(FieldValue(Protocol.FIT_FIELD_DAY_QUALITY, dayQuality))
        emitter.onNext(WriteToSessionMesg(zoneFields + extra))
```
(restructure the existing list into `val zoneFields = listOf(...)`). Note `summaryForFit()` falls back to the last ride's values because the Idle transition can clear the live flows before the FIT emitter's cancellable runs.
- [ ] **Step 3: README FIT section** — add the two fields with one line each: "`tyme_ve_scale` — today's strap scale factor (Beta, session)", "`tyme_day_quality` — today's breathing vs. your normal after scale correction, % (Beta, session)".
- [ ] **Step 4: Build; commit** `git add -A && git commit -m "Record strap scale and day quality in the FIT session summary"`

---

### Task 10: `ThresholdEvidence` — breakpoints and suggestions (spec §6)

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/ThresholdEvidence.kt`
- Test: `app/src/test/kotlin/com/tymewear/karoo/ThresholdEvidenceTest.kt`

**Interfaces:**
- Consumes: `BinStat`, `ZoneThresholds`.
- Produces:
```kotlin
data class Breakpoints(val lowerLoadW: Double?, val lowerVe: Double?, val upperLoadW: Double?, val upperVe: Double?) { fun serialise(): String; companion object { fun deserialise(s: String): Breakpoints? } }
enum class ThresholdKind { VT1, VT2 }
data class Suggestion(val kind: ThresholdKind, val currentVe: Double, val suggestedVe: Double)
object ThresholdEvidence {
    fun estimate(bins: List<BinStat>, minCount: Int = 120, minBracketBins: Int = 2, minImprovement: Double = 0.25): Breakpoints
    fun suggestions(history: List<Breakpoints>, configured: ZoneThresholds, agreeRides: Int = 3, agreeToleranceVe: Double = 4.0, minDifferenceFraction: Double = 0.08): List<Suggestion>
}
```
Algorithm for `estimate`: keep bins with `count >= minCount`, sorted by centre; need ≥ 5. Weighted least squares (weight = count) of `ve ≈ a + b·x + c·max(0, x−k1) [+ d·max(0, x−k2)]` for every candidate `k` among bin centres with at least `minBracketBins` bins strictly below and above (for two breaks also `k2 ≥ k1 + 2 bins`). Weighted SSE: `SSE0` (line), best `SSE1`, best `SSE2`. Accept two breaks when `SSE2 <= (1 − minImprovement) · SSE1` and both hinge coefficients `c, d > 0`; else accept one break when `SSE1 <= (1 − minImprovement) · SSE0` and `c > 0`; else no breaks. VE at a break = fitted value there. Solve the 3×3 / 4×4 normal equations with Gaussian elimination (write a private `solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray?` returning null on a singular matrix).
Algorithm for `suggestions`: take the `agreeRides` most recent entries; for VT1 the candidate per entry is `lowerVe` when `upperVe != null`, otherwise `lowerVe` if `lowerVe < (configured.vt1 + configured.vt2) / 2` (a lone break below the midpoint is VT1, above it VT2); for VT2 it is `upperVe`, or a lone `lowerVe` at/above the midpoint. If every entry has a candidate and `max − min <= agreeToleranceVe`, the median is the estimate; suggest when `|estimate − configured| > minDifferenceFraction · configured`.

- [ ] **Step 1: Failing tests**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdEvidenceTest {
    private fun curve(slopes: Triple<Double, Double, Double>, b1: Double, b2: Double, count: Int = 300): List<BinStat> =
        (80..320 step 20).map { p ->
            val x = p.toDouble()
            val ve = 30.0 + slopes.first * (x - 80) + slopes.second * maxOf(0.0, x - b1) + slopes.third * maxOf(0.0, x - b2)
            BinStat(x, ve, count)
        }

    @Test
    fun `recovers two breakpoints from a three-segment curve`() {
        val bp = ThresholdEvidence.estimate(curve(Triple(0.15, 0.25, 0.6), b1 = 160.0, b2 = 240.0))
        assertEquals(160.0, bp.lowerLoadW!!, 10.0); assertEquals(240.0, bp.upperLoadW!!, 10.0)
        assertEquals(42.0, bp.lowerVe!!, 3.0)      // 30 + 0.15*80
        assertEquals(74.0, bp.upperVe!!, 3.0)      // 42 + 0.40*80
    }

    @Test
    fun `a straight line has no breakpoints`() {
        val bp = ThresholdEvidence.estimate(curve(Triple(0.3, 0.0, 0.0), 160.0, 240.0))
        assertNull(bp.lowerLoadW); assertNull(bp.upperLoadW)
    }

    @Test
    fun `a break where the slope decreases is rejected`() {
        val bp = ThresholdEvidence.estimate(curve(Triple(0.6, -0.4, 0.0), 180.0, 300.0))
        assertNull(bp.lowerLoadW)
    }

    @Test
    fun `thin bins are ignored and too few bins yield nothing`() {
        val bins = curve(Triple(0.15, 0.25, 0.6), 160.0, 240.0).map { if (it.centre > 160.0) it.copy(count = 10) else it }
        assertNull(ThresholdEvidence.estimate(bins).lowerLoadW)
    }

    @Test
    fun `pooled indoor rides place the lower break near two hundred watts`() {
        // Reference bins (LoadGate rules, six indoor fixtures): 120:46 140:51 160:63.5 180:67 200:68.5 220:89 240:93 260:89.
        val b = VeBaseline()
        for (n in VeBaselineTest.INDOOR) { val f = RideFixture.load(n); val g = LoadGate()
            for (i in f.watts.indices) g.onSample(f.watts[i], f.hr[i], f.ve[i], i * 1000L)?.let { b.update(it.loadW, it.ve) } }
        val bp = ThresholdEvidence.estimate(b.bins(), minCount = 30)
        assertNotNull(bp.lowerLoadW)
        assertTrue("lower break at ${bp.lowerLoadW}", bp.lowerLoadW!! in 180.0..240.0)
        assertTrue("VE at break ${bp.lowerVe}", bp.lowerVe!! in 60.0..85.0)
    }

    @Test
    fun `suggests VT1 when three rides agree and differ from configured by over eight percent`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val hist = listOf(Breakpoints(200.0, 65.0, null, null), Breakpoints(200.0, 67.0, null, null), Breakpoints(220.0, 66.0, null, null))
        val s = ThresholdEvidence.suggestions(hist, cfg)
        assertEquals(listOf(Suggestion(ThresholdKind.VT1, 73.0, 66.0)), s)
    }

    @Test
    fun `no suggestion when rides disagree or the difference is small`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        assertTrue(ThresholdEvidence.suggestions(listOf(Breakpoints(200.0, 60.0, null, null), Breakpoints(200.0, 70.0, null, null), Breakpoints(200.0, 66.0, null, null)), cfg).isEmpty())
        assertTrue(ThresholdEvidence.suggestions(listOf(Breakpoints(200.0, 70.0, null, null), Breakpoints(200.0, 71.0, null, null), Breakpoints(200.0, 70.0, null, null)), cfg).isEmpty())
        assertTrue(ThresholdEvidence.suggestions(listOf(Breakpoints(200.0, 60.0, null, null)), cfg).isEmpty())
    }

    @Test
    fun `a lone break above the midpoint is treated as VT2`() {
        val cfg = ZoneThresholds(73.0, 96.0, 112.0, 130.0)
        val hist = List(3) { Breakpoints(230.0, 88.0, null, null) }
        assertEquals(listOf(Suggestion(ThresholdKind.VT2, 96.0, 88.0)), ThresholdEvidence.suggestions(hist, cfg))
    }

    @Test
    fun `breakpoints survive a serialisation round trip`() {
        val bp = Breakpoints(200.0, 66.5, 240.0, 91.0)
        assertEquals(bp, Breakpoints.deserialise(bp.serialise()))
        assertEquals(Breakpoints(null, null, null, null), Breakpoints.deserialise(Breakpoints(null, null, null, null).serialise()))
        assertNull(Breakpoints.deserialise("junk"))
    }
}
```

- [ ] **Step 2: Run — compile failure. Step 3: Implement** per the algorithm above. `serialise()` = `"${lowerLoadW ?: ""}:${lowerVe ?: ""}:${upperLoadW ?: ""}:${upperVe ?: ""}"`; `deserialise` splits on `:` expecting 4 parts (empty → null, unparsable non-empty → return null).

- [ ] **Step 4: Run — green. Step 5: Commit** `git add -A && git commit -m "Add ThresholdEvidence: breakpoints from the pooled baseline and threshold suggestions"`

---

### Task 11: Evidence at ride end, suggestion card, auto-apply, change history (spec §2.2, §6)

**Files:**
- Modify: `VentilatoryState.kt`, `screens/MainScreen.kt`, `MainActivity.kt`

**Interfaces:**
- Produces on `VentilatoryState`: `fun suggestions(context): List<Suggestion>`, `fun applySuggestion(context, s: Suggestion)`, `fun dismissSuggestion(context, s: Suggestion)`, `fun changeHistory(context): List<ThresholdChange>`, `fun revert(context, change: ThresholdChange)`, `fun isAutoApply(context): Boolean`; `data class ThresholdChange(val kind: ThresholdKind, val fromVe: Double, val toVe: Double, val atMs: Long)`.
- Prefs keys: `threshold_evidence_history` (last 8 `Breakpoints.serialise()` joined by `|`), `threshold_auto_apply` (bool), `threshold_dismissed_vt1` / `_vt2` (float, the suggested value dismissed), `threshold_change_history` (last 5 `kind:from:to:atMs` joined by `|`).
- `PrefsData` gains `autoApplyThresholds: Boolean`; `MainScreen` gains `loadSuggestions`, `onApplySuggestion`, `onDismissSuggestion`, `loadChangeHistory`, `onRevertChange`.

- [ ] **Step 1: Ride-end evidence in `VentilatoryState.onRideEnd`** (after folding samples, before `persist`):
```kotlin
            val bp = ThresholdEvidence.estimate(powerBaseline.bins())
            evidenceHistory = (evidenceHistory + bp).takeLast(MAX_EVIDENCE)
            if (prefs(context).getBoolean(KEY_AUTO_APPLY, false)) {
                for (s in suggestionsFrom(evidenceHistory, TymewearData.configuredThresholds(), context)) apply(context, s)
            }
```
where `suggestionsFrom` filters out a suggestion whose `suggestedVe` is within 3 L/min of the dismissed value for that kind, and `apply` writes `vt1_threshold`/`vt2_threshold` (Float), appends a `ThresholdChange`, and calls `TymewearData.loadThresholds(context)`. `revert` writes `fromVe` back and removes the entry. Persist/load `evidenceHistory` alongside the baselines.

- [ ] **Step 2: Settings UI** — under the baseline status:
  - Suggestion cards (one per suggestion): `Card` with text "Recent rides put ${kind} near ${suggested} L/min. Configured: ${current}." and two `TextButton`s **Apply** / **Dismiss**. After Apply: `onApplySuggestion(s)`, then reload prefs into the `vt1`/`vt2` text fields and refresh `suggestions` and `changes`.
  - `Switch` "Apply suggestions automatically after each ride" bound to `autoApplyThresholds` (saved via `onSave`).
  - Change history list (most recent first): "${kind} ${from} → ${to}, ${relative time}" with a **Revert** `TextButton`.
  - Keep the copy short; no numbers in prose other than the values themselves.
- [ ] **Step 3: `MainActivity`** — wire the five callbacks to `VentilatoryState`, persist/load `threshold_auto_apply` in `onSave`/`loadPrefs`.
- [ ] **Step 4: Build, install, open settings** — with no evidence yet the section shows only the switch; toggle it, save, reopen, confirm it persisted.
- [ ] **Step 5: Commit** `git add -A && git commit -m "Suggest threshold updates from pooled evidence, with apply, dismiss, auto-apply and revert"`

---

### Task 12: README, version, on-device verification (spec §2, §8)

**Files:**
- Modify: `README.md`, `app/build.gradle.kts` (versionCode 11, versionName "0.6.0"), `res/values/strings.xml` if any copy remains stale

- [ ] **Step 1: README** — rewrite the "Ventilatory State (Beta)" section to describe: strap scale (what it is, why it matters, "×0.80" on the field, "check strap tension" hint), day quality, the Power + VE zone field, threshold suggestions and auto-apply, the indoor/outdoor behaviour (works outdoors; locks later on ragged rides; never on very short ones), requirements (power meter and heart rate), and that recorded VE stays raw. Remove every mention of VT1 Power and BR Drift. Keep it threshold-relative — no hardcoded personal numbers (public app).
- [ ] **Step 2: Version bump**, build release-style debug APK, install.
- [ ] **Step 3: On-device checklist** (record results in the ledger; the estimator needs two rides before it locks, so the first ride verifies the plumbing only):
  1. Field picker lists: VE, VE Graph, BR, TV, MI, MI Battery, VE Zones, Vent State, Power + VE zone. Nothing else.
  2. Settings: Beta on; no drift controls; baseline shows "calibrating (0 of 3 ...)" after the 0.5.0 baseline was discarded (check logcat for "Baseline from 0.5.0 discarded").
  3. Power + VE zone: shows 3 s power with a grey strip until the strap connects, then the VE field's colour.
  4. Vent State: "cal / learning".
  5. After the ride: prefs contain `baseline_bins`, `baseline_hr_bins`, `baseline_ride_count=1`, `threshold_evidence_history`; FIT session has no `tyme_ve_scale` yet (nothing locked). `adb shell run-as com.tymewear.karoo cat /data/data/com.tymewear.karoo/shared_prefs/tymewear_prefs.xml`.
- [ ] **Step 4: Full test suite green; commit** `git add -A && git commit -m "Document the session-scale Beta and bump to 0.6.0"`

---

## Self-review against the spec

- §1 evidence → Task 2/5 goldens. §2.1 fields → Tasks 7, 8; removals → Task 6. §2.2 settings → Tasks 7, 11. §2.3 FIT → Task 9 (raw VE untouched: no task changes the record path). §3.1–3.3 → Tasks 4, 5. §3.4 normalise-before-fold and baseline migration → Task 6. §4 sampler → Task 1. §5 table → every row has a task; `BinDeviation` rename in Task 2. §6 → Tasks 10, 11 (20 W bins per the amended spec). §7 edge cases: no HR → `LoadGate` emits nothing (Task 1) and field shows "cal"; first rides → confidence gate (Task 5); pause → `recordingSeconds` counts only active ticks (Task 6); out-of-range → `ScaleStatus.OutOfRange` (Task 4) rendered "scale n/a" (Task 7); Beta toggled mid-ride → unchanged `reloadEnabledFlag` on fresh start. §8 fixtures/goldens/e2e → Tasks 0, 1, 2, 5, 10; on-device → Task 12.
- Names used consistently: `LoadVeSample(loadW, hrBpm, ve)`, `VeBaseline.update(key, ve)`, `BinDeviation.add(key, ve)`, `SessionScale.offer/displayed/status`, `SessionPipeline.onSample(loadW, hrBpm, ve, nowMs, recordingSeconds)`, `VentilatoryState.onSample(loadW, hrBpm)`, `TymewearData.configuredThresholds()/currentThresholds()/powerW/updatePower`, `ThresholdEvidence.estimate/suggestions`, `Breakpoints(lowerLoadW, lowerVe, upperLoadW, upperVe)`.
- Known judgement calls left to the implementer's reviewer: the Compose layout details in Task 11 and the exact README prose in Task 12.
