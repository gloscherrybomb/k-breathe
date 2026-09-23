# Dynamic Ventilatory State Implementation Plan

> **Names (2026-09-23):** this document predates 0.7.0. Its `vt1`, `vt2`, `topZ4`, `vo2max`
> (and "VT1", "VT2", "TopZ4", "VO2max" where they mean the configured fields) are the zone edges
> Tymewear calls **Endurance, VT1, VT2, Top Z4**. See `2026-09-23-tymewear-names-design.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the rider, live on the Karoo, how today's ventilatory response differs from their own rolling baseline — as an efficiency percentage and as today's threshold powers.

**Architecture:** Five dependency-free Kotlin components do the work and are unit-tested on the JVM against the rider's real recorded rides: a steady-state detector gates which samples are comparable, a rolling per-power-bin baseline stores what VE the rider normally produces at each power, a deviation calculator compares today against it, a threshold-shift calculator converts that into today's VT1/VT2 power, and a drift tracker implements the community "breathing-rate drift" heuristic. Three new karoo-ext data fields display the results; the extension consumes the Karoo's power stream alongside the existing heart-rate stream.

**Tech Stack:** Kotlin 2.0, Android (minSdk 26, compileSdk 34), karoo-ext 1.1.8, Kotlin coroutines/Flow, RemoteViews data fields, SharedPreferences, JUnit 4 + kotlin-test-junit, Timber.

**Spec:** `docs/plans/2026-09-01-dynamic-ventilatory-state-design.md` — read it before starting; this plan argues from it.

## Global Constraints

- **Pure components must not reference `Constants` or any Android class.** `Constants` initialises `android.graphics.Color` in its object initialiser, which throws in plain JVM unit tests. Pass tunables as constructor parameters with defaults declared in the component's own `companion object`.
- **Pure components take the clock as a parameter** (`nowMs: Long`); never call `System.currentTimeMillis()` inside them, or they cannot be tested deterministically.
- **A power meter is required.** Without power the fields must report unavailable, never a fabricated number. Existing behaviour must not regress when power is absent.
- **Only fresh data may be consumed.** Gate on the existing `TymewearData.isDataFresh()`. Stale breathing values previously corrupted whole rides; a deviation computed over frozen values is confident fiction.
- **Never silently change the rider's stored thresholds.** New fields are additional information only.
- **Feature is opt-in and labelled Beta.** Default `dynamic_state_enabled = false`.
- **Cycling only.** Load signal is power (watts). No running code.
- **Build/verify commands** (Java 21 required):
  `export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home"`
  `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`
- **Existing passing baseline:** 23 unit tests (`DataFreshnessTest` 7, `DataWatchdogTest` 8, `ScanThrottleTest` 8). Do not break them.

---

### Task 1: Fixture loader and steady-state detector

**Why:** VE lags power by tens of seconds, so a sample taken while power is changing is not comparable to a baseline. Everything downstream depends on selecting only stable-power samples. The loader is shared by every later test, so it ships here.

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/SteadyStateDetector.kt`
- Create: `app/src/test/kotlin/com/tymewear/karoo/RideFixture.kt`
- Create: `app/src/test/kotlin/com/tymewear/karoo/SteadyStateDetectorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class LoadVeSample(val loadW: Double, val ve: Double)`
  - `class SteadyStateDetector(windowSeconds: Int = 60, maxCoefficientOfVariation: Double = 0.12, minSamplesInWindow: Int = 45, veSmoothingSeconds: Int = 30, minLoadW: Double = 100.0, maxLoadW: Double = 240.0)`
  - `fun SteadyStateDetector.onSample(loadW: Double?, ve: Double?): LoadVeSample?` — returns a sample only when the trailing window is steady, else null
  - `fun SteadyStateDetector.reset()`
  - Test helper `RideFixture.load(name: String): RideFixture` with `val watts: List<Double?>`, `val ve: List<Double?>`, `val br: List<Double?>`, and `fun qualityRatio(): Double`

- [ ] **Step 1: Write the fixture loader**

Fixture CSVs have differing headers, so read by column name, not position. `ride_*.csv` files have `time,watts,ve,br`; the older fixtures use `TymeVentilation`/`TymeBreathRate` and may lack `watts`.

```kotlin
package com.tymewear.karoo

/** Loads a recorded ride from test resources. Columns are resolved by header name
 *  because the fixtures were exported at different times with different sets. */
class RideFixture(
    val watts: List<Double?>,
    val ve: List<Double?>,
    val br: List<Double?>,
) {
    /** Fraction of distinct VE values. Corrupt rides (a frozen value written every
     *  second) score near zero; genuine per-second data scores 0.5-0.77. */
    fun qualityRatio(): Double {
        val vals = ve.filterNotNull()
        if (vals.isEmpty()) return 0.0
        return vals.toSet().size.toDouble() / vals.size
    }

    companion object {
        fun load(name: String): RideFixture {
            val stream = RideFixture::class.java.classLoader
                .getResourceAsStream("fixtures/$name")
                ?: error("fixture not found: $name")
            val lines = stream.bufferedReader().readLines()
            require(lines.isNotEmpty()) { "empty fixture: $name" }
            val header = lines.first().split(",").map { it.trim() }
            fun idx(vararg candidates: String): Int? =
                candidates.firstNotNullOfOrNull { c ->
                    header.indexOf(c).takeIf { it >= 0 }
                }
            val wIdx = idx("watts")
            val vIdx = idx("ve", "TymeVentilation", "tidal_volume_min")
            val bIdx = idx("br", "TymeBreathRate", "respiration")
            val watts = ArrayList<Double?>()
            val ve = ArrayList<Double?>()
            val br = ArrayList<Double?>()
            for (line in lines.drop(1)) {
                val f = line.split(",")
                fun get(i: Int?): Double? =
                    i?.let { f.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() && s != "None" }?.toDoubleOrNull() }
                watts.add(get(wIdx)); ve.add(get(vIdx)); br.add(get(bIdx))
            }
            return RideFixture(watts, ve, br)
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteadyStateDetectorTest {

    private fun feed(f: RideFixture, d: SteadyStateDetector = SteadyStateDetector()): List<LoadVeSample> {
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) {
            d.onSample(f.watts[i], f.ve[i])?.let { out.add(it) }
        }
        return out
    }

    @Test
    fun `emits nothing until the window is populated`() {
        val d = SteadyStateDetector()
        repeat(44) { assertNull(d.onSample(200.0, 60.0)) }
    }

    @Test
    fun `emits once power has been stable for the window`() {
        val d = SteadyStateDetector()
        var last: LoadVeSample? = null
        repeat(60) { last = d.onSample(200.0, 60.0) ?: last }
        assertNotNull("steady power should produce a sample", last)
        assertEquals(200.0, last!!.loadW, 0.001)
    }

    @Test
    fun `rejects samples while power is swinging`() {
        val d = SteadyStateDetector()
        // Alternating 100/300W: mean 200, cv ~0.5, far above the 0.12 threshold.
        var emitted = 0
        repeat(200) { i ->
            if (d.onSample(if (i % 2 == 0) 100.0 else 300.0, 60.0) != null) emitted++
        }
        assertEquals(0, emitted)
    }

    @Test
    fun `ignores samples outside the usable power range`() {
        val d = SteadyStateDetector()
        var emitted = 0
        repeat(200) { if (d.onSample(60.0, 30.0) != null) emitted++ }   // below minLoadW
        assertEquals(0, emitted)
    }

    @Test
    fun `tolerates gaps in the power stream`() {
        val d = SteadyStateDetector()
        var emitted = 0
        repeat(200) { i ->
            val w = if (i % 10 == 0) null else 200.0     // 10% dropouts
            if (d.onSample(w, 60.0) != null) emitted++
        }
        assertTrue("occasional nulls must not disable detection", emitted > 0)
    }

    @Test
    fun `real steady rides yield substantial steady coverage`() {
        // Measured with the reference prototype; these are golden values.
        val expected = mapOf(
            "ride_2026-02-13.csv" to 2347,
            "ride_2026-02-18.csv" to 2937,
            "ride_2026-02-24.csv" to 3110,
            "ride_2026-03-03.csv" to 2679,
            "ride_2026-03-16.csv" to 2471,
            "ride_2026-03-29.csv" to 4289,
        )
        for ((name, want) in expected) {
            val got = feed(RideFixture.load(name)).size
            // Allow a small tolerance for smoothing edge effects.
            assertTrue(
                "$name: expected ~$want steady samples, got $got",
                got in (want - 60)..(want + 60),
            )
        }
    }

    @Test
    fun `a ride with no power yields no steady samples`() {
        val f = RideFixture.load("outdoor_endurance_2026-08-09.csv")
        assertEquals(0, feed(f).size)
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SteadyStateDetectorTest*'`
Expected: compilation failure — `SteadyStateDetector` and `LoadVeSample` are unresolved.

- [ ] **Step 4: Implement**

```kotlin
package com.tymewear.karoo

/** One comparable observation: the ventilation produced at a given load. */
data class LoadVeSample(val loadW: Double, val ve: Double)

/**
 * Selects samples where load has been stable long enough for ventilation to have
 * settled. Ventilation lags load by tens of seconds, so a sample taken mid-surge
 * describes the previous load, not the current one — comparing it against a baseline
 * reads as an efficiency change that never happened.
 *
 * Pure and clock-free so the windowing is deterministic in tests. Assumes it is fed at
 * roughly 1 Hz, which is the rate the Karoo streams power.
 */
class SteadyStateDetector(
    private val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    private val maxCoefficientOfVariation: Double = DEFAULT_MAX_CV,
    private val minSamplesInWindow: Int = DEFAULT_MIN_SAMPLES_IN_WINDOW,
    private val veSmoothingSeconds: Int = DEFAULT_VE_SMOOTHING_SECONDS,
    private val minLoadW: Double = DEFAULT_MIN_LOAD_W,
    private val maxLoadW: Double = DEFAULT_MAX_LOAD_W,
) {
    private val loadWindow = ArrayDeque<Double?>()
    private val veWindow = ArrayDeque<Double>()

    /** Feed one 1 Hz sample. Returns a comparable sample, or null if not steady. */
    fun onSample(loadW: Double?, ve: Double?): LoadVeSample? {
        loadWindow.addLast(loadW)
        while (loadWindow.size > windowSeconds) loadWindow.removeFirst()

        if (ve != null) {
            veWindow.addLast(ve)
            while (veWindow.size > veSmoothingSeconds) veWindow.removeFirst()
        }

        if (loadW == null || loadW < minLoadW || loadW > maxLoadW) return null
        if (veWindow.isEmpty()) return null

        val present = loadWindow.filterNotNull()
        if (present.size < minSamplesInWindow) return null

        val mean = present.average()
        if (mean <= 0.0) return null
        val variance = present.sumOf { (it - mean) * (it - mean) } / present.size
        if (Math.sqrt(variance) / mean >= maxCoefficientOfVariation) return null

        return LoadVeSample(loadW, veWindow.average())
    }

    fun reset() {
        loadWindow.clear()
        veWindow.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_SECONDS = 60
        const val DEFAULT_MAX_CV = 0.12
        const val DEFAULT_MIN_SAMPLES_IN_WINDOW = 45
        const val DEFAULT_VE_SMOOTHING_SECONDS = 30
        const val DEFAULT_MIN_LOAD_W = 100.0
        const val DEFAULT_MAX_LOAD_W = 240.0
    }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 30 tests total (23 existing + 7 new).

If the golden steady-sample counts are outside tolerance, do **not** widen the tolerance to make it green. The reference values come from `windowSeconds=60`, `maxCV=0.12`, `minSamplesInWindow=45`, `veSmoothingSeconds=30`, range 100-240 W; a mismatch means the implementation diverges from those semantics — most likely the VE smoothing (causal mean over the trailing 30 present values) or the range check.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/SteadyStateDetector.kt \
        app/src/test/kotlin/com/tymewear/karoo/RideFixture.kt \
        app/src/test/kotlin/com/tymewear/karoo/SteadyStateDetectorTest.kt
git commit -m "Add steady-state detector for comparable VE samples"
```

---

### Task 2: Rolling VE baseline

**Why:** The baseline is what "today" is compared against. It is built from steady samples across recent rides rather than a threshold test, because only one ramp exists in a year of the rider's data, and because a rolling reference tracks fitness by itself — making the deviation mean "today versus my recent normal".

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/VeBaseline.kt`
- Create: `app/src/test/kotlin/com/tymewear/karoo/VeBaselineTest.kt`

**Interfaces:**
- Consumes: `LoadVeSample` (Task 1).
- Produces:
  - `class VeBaseline(binWidthW: Double = 20.0, minSamplesPerBin: Int = 30)`
  - `fun update(sample: LoadVeSample)`
  - `fun expectedVe(loadW: Double): Double?` — null when the bin lacks coverage
  - `fun coveredBins(): Int`
  - `fun binCentre(loadW: Double): Double`
  - `fun serialise(): String` / `companion object fun deserialise(text: String, binWidthW: Double = 20.0, minSamplesPerBin: Int = 30): VeBaseline`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeBaselineTest {

    private fun steady(f: RideFixture): List<LoadVeSample> {
        val d = SteadyStateDetector()
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) d.onSample(f.watts[i], f.ve[i])?.let { out.add(it) }
        return out
    }

    @Test
    fun `bins are centred on multiples of the bin width`() {
        val b = VeBaseline()
        assertEquals(200.0, b.binCentre(196.0), 0.001)
        assertEquals(200.0, b.binCentre(205.0), 0.001)
        assertEquals(180.0, b.binCentre(188.0), 0.001)
    }

    @Test
    fun `a bin is unusable until it has enough samples`() {
        val b = VeBaseline(minSamplesPerBin = 30)
        repeat(29) { b.update(LoadVeSample(200.0, 60.0)) }
        assertNull("29 samples is below the minimum", b.expectedVe(200.0))
        b.update(LoadVeSample(200.0, 60.0))
        assertNotNull("30 samples reaches the minimum", b.expectedVe(200.0))
    }

    @Test
    fun `expected VE is the mean of the bin`() {
        val b = VeBaseline(minSamplesPerBin = 2)
        b.update(LoadVeSample(200.0, 50.0))
        b.update(LoadVeSample(200.0, 70.0))
        assertEquals(60.0, b.expectedVe(203.0)!!, 0.001)
    }

    @Test
    fun `unknown loads return null rather than extrapolating`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(LoadVeSample(200.0, 60.0))
        assertNull(b.expectedVe(400.0))
    }

    @Test
    fun `built from three real rides it covers the expected bins`() {
        // Golden values from the reference prototype.
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            steady(RideFixture.load(n)).forEach { b.update(it) }
        }
        assertEquals("usable bins", 6, b.coveredBins())
        // Bin means, +/- 1.0 L/min.
        assertEquals(45.43, b.expectedVe(100.0)!!, 1.0)
        assertEquals(48.48, b.expectedVe(120.0)!!, 1.0)
        assertEquals(56.34, b.expectedVe(140.0)!!, 1.0)
        assertEquals(67.30, b.expectedVe(160.0)!!, 1.0)
        assertEquals(68.50, b.expectedVe(180.0)!!, 1.0)
        assertEquals(72.29, b.expectedVe(200.0)!!, 1.0)
        // 220W had only 14 samples in the reference run, below the minimum.
        assertNull("220W bin must remain unusable", b.expectedVe(220.0))
    }

    @Test
    fun `survives a serialisation round trip`() {
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            steady(RideFixture.load(n)).forEach { b.update(it) }
        }
        val restored = VeBaseline.deserialise(b.serialise())
        assertEquals(b.coveredBins(), restored.coveredBins())
        for (p in listOf(100.0, 120.0, 140.0, 160.0, 180.0, 200.0)) {
            assertEquals(b.expectedVe(p)!!, restored.expectedVe(p)!!, 0.0001)
        }
    }

    @Test
    fun `deserialising junk yields an empty baseline rather than throwing`() {
        val b = VeBaseline.deserialise("not-a-baseline")
        assertEquals(0, b.coveredBins())
        assertTrue(b.serialise().isEmpty() || b.coveredBins() == 0)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*VeBaselineTest*'`
Expected: compilation failure — `VeBaseline` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.tymewear.karoo

/**
 * What ventilation the rider normally produces at a given load, learned from their own
 * recent steady-state riding.
 *
 * Deliberately not derived from a threshold test: the rider's full year of activity
 * contains one ramp, so a test-dependent baseline would rarely be available. A rolling
 * reference also tracks fitness by itself, which makes a deviation from it mean "today
 * versus my recent normal" — the signal this feature exists to show.
 *
 * Stores a running mean and count per load bin. Pure; no Android or clock dependency.
 */
class VeBaseline(
    private val binWidthW: Double = DEFAULT_BIN_WIDTH_W,
    private val minSamplesPerBin: Int = DEFAULT_MIN_SAMPLES_PER_BIN,
) {
    private class Bin(var total: Double = 0.0, var count: Int = 0)

    private val bins = HashMap<Double, Bin>()

    fun binCentre(loadW: Double): Double = Math.round(loadW / binWidthW) * binWidthW

    fun update(sample: LoadVeSample) {
        val bin = bins.getOrPut(binCentre(sample.loadW)) { Bin() }
        bin.total += sample.ve
        bin.count += 1
    }

    /** Mean VE for the bin containing [loadW], or null when coverage is insufficient.
     *  Returning null rather than extrapolating keeps callers honest about range. */
    fun expectedVe(loadW: Double): Double? {
        val bin = bins[binCentre(loadW)] ?: return null
        if (bin.count < minSamplesPerBin) return null
        return bin.total / bin.count
    }

    fun coveredBins(): Int = bins.values.count { it.count >= minSamplesPerBin }

    /** Compact `centre:total:count` triples, joined by ';'. */
    fun serialise(): String =
        bins.entries
            .sortedBy { it.key }
            .joinToString(";") { (centre, bin) -> "$centre:${bin.total}:${bin.count}" }

    companion object {
        const val DEFAULT_BIN_WIDTH_W = 20.0
        const val DEFAULT_MIN_SAMPLES_PER_BIN = 30

        /** Tolerant by design: a corrupt or truncated preference must not crash the
         *  extension, it must simply start recalibrating. */
        fun deserialise(
            text: String,
            binWidthW: Double = DEFAULT_BIN_WIDTH_W,
            minSamplesPerBin: Int = DEFAULT_MIN_SAMPLES_PER_BIN,
        ): VeBaseline {
            val out = VeBaseline(binWidthW, minSamplesPerBin)
            for (part in text.split(";")) {
                val f = part.split(":")
                if (f.size != 3) continue
                val centre = f[0].toDoubleOrNull() ?: continue
                val total = f[1].toDoubleOrNull() ?: continue
                val count = f[2].toIntOrNull() ?: continue
                if (count <= 0) continue
                out.bins[centre] = Bin(total, count)
            }
            return out
        }
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 37 tests total.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/VeBaseline.kt \
        app/src/test/kotlin/com/tymewear/karoo/VeBaselineTest.kt
git commit -m "Add rolling per-load VE baseline with compact persistence"
```

---

### Task 3: Efficiency deviation

**Why:** This is the headline number. It is also where the feature can most easily lie, so the tests pin real per-ride values and assert that corrupt rides are refused rather than scored.

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/EfficiencyDeviation.kt`
- Create: `app/src/test/kotlin/com/tymewear/karoo/EfficiencyDeviationTest.kt`

**Interfaces:**
- Consumes: `LoadVeSample` (Task 1), `VeBaseline` (Task 2).
- Produces:
  - `data class Deviation(val fraction: Double, val matchedBins: Int)` with `val percent: Double get() = fraction * 100.0`
  - `class EfficiencyDeviation(baseline: VeBaseline, binWidthW: Double = 20.0, minSamplesPerBin: Int = 30, minMatchedBins: Int = 3)`
  - `fun add(sample: LoadVeSample)`
  - `fun deviation(): Deviation?` — null until enough matched bins exist
  - `fun reset()`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EfficiencyDeviationTest {

    private fun steady(name: String): List<LoadVeSample> {
        val f = RideFixture.load(name)
        val d = SteadyStateDetector()
        val out = ArrayList<LoadVeSample>()
        for (i in f.watts.indices) d.onSample(f.watts[i], f.ve[i])?.let { out.add(it) }
        return out
    }

    private fun baselineFromFebruary(): VeBaseline {
        val b = VeBaseline()
        for (n in listOf("ride_2026-02-13.csv", "ride_2026-02-18.csv", "ride_2026-02-24.csv")) {
            steady(n).forEach { b.update(it) }
        }
        return b
    }

    private fun score(baseline: VeBaseline, name: String): Deviation? {
        val e = EfficiencyDeviation(baseline)
        steady(name).forEach { e.add(it) }
        return e.deviation()
    }

    @Test
    fun `reports nothing before enough bins are matched`() {
        val e = EfficiencyDeviation(baselineFromFebruary())
        repeat(100) { e.add(LoadVeSample(160.0, 60.0)) }   // one bin only
        assertNull("one matched bin is not enough", e.deviation())
    }

    @Test
    fun `identical ventilation to baseline reads as zero`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(LoadVeSample(140.0, 50.0))
        b.update(LoadVeSample(160.0, 60.0))
        b.update(LoadVeSample(180.0, 70.0))
        val e = EfficiencyDeviation(b, minSamplesPerBin = 1, minMatchedBins = 3)
        e.add(LoadVeSample(140.0, 50.0))
        e.add(LoadVeSample(160.0, 60.0))
        e.add(LoadVeSample(180.0, 70.0))
        assertEquals(0.0, e.deviation()!!.percent, 0.001)
    }

    @Test
    fun `higher ventilation at the same load reads positive`() {
        val b = VeBaseline(minSamplesPerBin = 1)
        listOf(140.0 to 50.0, 160.0 to 60.0, 180.0 to 70.0).forEach { b.update(LoadVeSample(it.first, it.second)) }
        val e = EfficiencyDeviation(b, minSamplesPerBin = 1, minMatchedBins = 3)
        listOf(140.0 to 55.0, 160.0 to 66.0, 180.0 to 77.0).forEach { e.add(LoadVeSample(it.first, it.second)) }
        assertEquals("10% more ventilation for the same work", 10.0, e.deviation()!!.percent, 0.01)
    }

    @Test
    fun `scores the rider's real rides against their February baseline`() {
        // Golden values from the reference prototype, +/- 1.5 percentage points.
        val b = baselineFromFebruary()
        val expected = mapOf(
            "ride_2026-02-13.csv" to 2.83,
            "ride_2026-02-18.csv" to 4.60,
            "ride_2026-02-24.csv" to -9.36,
            "ride_2026-03-03.csv" to -10.82,
            "ride_2026-03-16.csv" to -6.81,
            "ride_2026-03-29.csv" to -6.02,
        )
        for ((name, want) in expected) {
            val d = score(b, name) ?: error("$name produced no deviation")
            assertEquals("$name deviation", want, d.percent, 1.5)
            assertTrue("$name should match >=3 bins", d.matchedBins >= 3)
        }
    }

    @Test
    fun `a corrupt ride is detectable by its quality ratio`() {
        // long_outdoor has a frozen VE stream (quality ratio 0.003) and would otherwise
        // produce a confident, meaningless deviation. EfficiencyDeviation deliberately
        // does not know about data quality — live use is gated by freshness, and
        // historical analysis must gate on this ratio. This test pins the detector so a
        // caller can rely on it, and guards the fixture from being replaced by a clean one.
        val corrupt = RideFixture.load("long_outdoor_2026-06-19.csv")
        assertTrue("corrupt fixture should score far below the gate", corrupt.qualityRatio() < 0.20)
        val good = RideFixture.load("ride_2026-03-29.csv")
        assertTrue("good fixture should score well above the gate", good.qualityRatio() > 0.40)
    }

    @Test
    fun `a ride with no power produces no deviation`() {
        assertNull(score(baselineFromFebruary(), "outdoor_endurance_2026-08-09.csv"))
    }

    @Test
    fun `reset clears accumulated samples`() {
        val b = baselineFromFebruary()
        val e = EfficiencyDeviation(b)
        steady("ride_2026-03-03.csv").forEach { e.add(it) }
        assertTrue(e.deviation() != null)
        e.reset()
        assertNull(e.deviation())
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*EfficiencyDeviationTest*'`
Expected: compilation failure — `EfficiencyDeviation` and `Deviation` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.tymewear.karoo

/** How today's ventilation compares with the baseline. Negative means less ventilation
 *  for the same work: fresher, fitter, or simply a good day. */
data class Deviation(val fraction: Double, val matchedBins: Int) {
    val percent: Double get() = fraction * 100.0
}

/**
 * Compares observed ventilation against a [VeBaseline] at matched load.
 *
 * Bin-wise medians are compared, then the median across bins is taken, so a single odd
 * bin cannot swing the answer. Requires several matched bins before reporting anything:
 * one bin describes one intensity, not the ride.
 *
 * Callers MUST only feed samples derived from fresh sensor data. A frozen VE value
 * produces a confident, entirely fictional number — this is what corrupted ten of the
 * rider's recorded rides before the recording fix.
 */
class EfficiencyDeviation(
    private val baseline: VeBaseline,
    private val binWidthW: Double = VeBaseline.DEFAULT_BIN_WIDTH_W,
    private val minSamplesPerBin: Int = VeBaseline.DEFAULT_MIN_SAMPLES_PER_BIN,
    private val minMatchedBins: Int = DEFAULT_MIN_MATCHED_BINS,
) {
    private val observed = HashMap<Double, MutableList<Double>>()

    fun add(sample: LoadVeSample) {
        val centre = Math.round(sample.loadW / binWidthW) * binWidthW
        observed.getOrPut(centre) { ArrayList() }.add(sample.ve)
    }

    fun deviation(): Deviation? {
        val ratios = ArrayList<Double>()
        for ((centre, values) in observed) {
            if (values.size < minSamplesPerBin) continue
            val expected = baseline.expectedVe(centre) ?: continue
            if (expected <= MIN_MEANINGFUL_VE) continue
            ratios.add(median(values) / expected - 1.0)
        }
        if (ratios.size < minMatchedBins) return null
        return Deviation(median(ratios), ratios.size)
    }

    fun reset() = observed.clear()

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    companion object {
        const val DEFAULT_MIN_MATCHED_BINS = 3
        /** Below this, VE is noise or a sensor artefact and ratios explode. */
        const val MIN_MEANINGFUL_VE = 5.0
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 44 tests total.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/EfficiencyDeviation.kt \
        app/src/test/kotlin/com/tymewear/karoo/EfficiencyDeviationTest.kt
git commit -m "Add efficiency deviation against the rider's VE baseline"
```

---

### Task 4: Threshold shift

**Why:** The deviation percentage is abstract; the power at which you cross VT1 today is actionable. This converts one into the other, and must refuse to extrapolate beyond the loads the baseline actually covers.

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/ThresholdShift.kt`
- Create: `app/src/test/kotlin/com/tymewear/karoo/ThresholdShiftTest.kt`

**Interfaces:**
- Consumes: `VeBaseline` (Task 2), `Deviation` (Task 3).
- Produces: `object ThresholdShift` with
  `fun thresholdPowerW(baseline: VeBaseline, thresholdVe: Double, deviationFraction: Double, minLoadW: Double = 100.0, maxLoadW: Double = 240.0, stepW: Double = 20.0): Double?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdShiftTest {

    /** Baseline where VE rises 10 L/min per 20W step: 140W->50, 160W->60, 180W->70. */
    private fun linearBaseline(): VeBaseline {
        val b = VeBaseline(minSamplesPerBin = 1)
        b.update(LoadVeSample(140.0, 50.0))
        b.update(LoadVeSample(160.0, 60.0))
        b.update(LoadVeSample(180.0, 70.0))
        return b
    }

    @Test
    fun `finds the load where baseline ventilation meets the threshold`() {
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 60.0, deviationFraction = 0.0,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertEquals(160.0, p!!, 0.5)
    }

    @Test
    fun `interpolates between bins`() {
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 55.0, deviationFraction = 0.0,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertEquals("VE 55 sits midway between the 140W and 160W bins", 150.0, p!!, 1.0)
    }

    @Test
    fun `a more efficient day pushes the threshold to a higher power`() {
        // -10%: today's VE at any load is 10% below baseline, so the threshold VE is
        // reached at a higher load than baseline implies.
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 60.0, deviationFraction = -0.10,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertTrue("expected above 160W, got $p", p!! > 160.0)
    }

    @Test
    fun `a worse day pulls the threshold to a lower power`() {
        val p = ThresholdShift.thresholdPowerW(
            baseline = linearBaseline(), thresholdVe = 60.0, deviationFraction = 0.10,
            minLoadW = 140.0, maxLoadW = 180.0,
        )
        assertTrue("expected below 160W, got $p", p!! < 160.0)
    }

    @Test
    fun `refuses to extrapolate above the covered range`() {
        assertNull(
            ThresholdShift.thresholdPowerW(
                baseline = linearBaseline(), thresholdVe = 200.0, deviationFraction = 0.0,
                minLoadW = 140.0, maxLoadW = 180.0,
            ),
        )
    }

    @Test
    fun `refuses to extrapolate below the covered range`() {
        assertNull(
            ThresholdShift.thresholdPowerW(
                baseline = linearBaseline(), thresholdVe = 10.0, deviationFraction = 0.0,
                minLoadW = 140.0, maxLoadW = 180.0,
            ),
        )
    }

    @Test
    fun `returns null when the baseline has no coverage`() {
        assertNull(
            ThresholdShift.thresholdPowerW(
                baseline = VeBaseline(), thresholdVe = 60.0, deviationFraction = 0.0,
            ),
        )
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ThresholdShiftTest*'`
Expected: compilation failure — `ThresholdShift` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.tymewear.karoo

/**
 * Converts a ventilation threshold into the load at which the rider would reach it
 * today, given how far today's ventilation sits from their baseline.
 *
 * A deviation of -10% means today's VE at any load is 10% below baseline, so the
 * threshold VE arrives at a *higher* load: equivalently, we look for the baseline load
 * whose expected VE equals `thresholdVe / (1 + deviation)`.
 *
 * Returns null rather than extrapolating outside the loads the baseline covers — a
 * confidently wrong threshold power is worse than none.
 */
object ThresholdShift {

    fun thresholdPowerW(
        baseline: VeBaseline,
        thresholdVe: Double,
        deviationFraction: Double,
        minLoadW: Double = SteadyStateDetector.DEFAULT_MIN_LOAD_W,
        maxLoadW: Double = SteadyStateDetector.DEFAULT_MAX_LOAD_W,
        stepW: Double = VeBaseline.DEFAULT_BIN_WIDTH_W,
    ): Double? {
        if (thresholdVe <= 0.0) return null
        val target = thresholdVe / (1.0 + deviationFraction)

        // Collect covered bins in ascending load order.
        val points = ArrayList<Pair<Double, Double>>()
        var load = Math.round(minLoadW / stepW) * stepW
        while (load <= maxLoadW) {
            baseline.expectedVe(load)?.let { points.add(load to it) }
            load += stepW
        }
        if (points.size < 2) return null

        // The target must be bracketed by the covered range; otherwise we would be
        // guessing beyond what the rider has actually done.
        val first = points.first().second
        val last = points.last().second
        val lo = minOf(first, last)
        val hi = maxOf(first, last)
        if (target < lo || target > hi) return null

        for (i in 0 until points.size - 1) {
            val (p0, v0) = points[i]
            val (p1, v1) = points[i + 1]
            val within = (target in minOf(v0, v1)..maxOf(v0, v1))
            if (!within) continue
            if (v1 == v0) return p0
            val t = (target - v0) / (v1 - v0)
            return p0 + t * (p1 - p0)
        }
        return null
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 51 tests total.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/ThresholdShift.kt \
        app/src/test/kotlin/com/tymewear/karoo/ThresholdShiftTest.kt
git commit -m "Add threshold-power calculation from baseline and deviation"
```

---

### Task 5: Breathing drift tracker

**Why:** Experienced Tymewear users pace long efforts by breathing-rate drift ("when BR exceeds the first interval by 11-15%, it's time to stop") and currently compute it by hand from lap averages. No head unit surfaces it live.

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/DriftTracker.kt`
- Create: `app/src/test/kotlin/com/tymewear/karoo/DriftTrackerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class DriftTracker(referenceSeconds: Int = 300, currentSeconds: Int = 120, warmupSeconds: Int = 60)`
  - `fun add(nowMs: Long, value: Double)`
  - `fun driftPercent(): Double?` — null until both windows are populated
  - `fun reset()`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftTrackerTest {

    @Test
    fun `reports nothing during warmup`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 3)
        t.add(0L, 20.0)
        assertNull(t.driftPercent())
    }

    @Test
    fun `zero drift when breathing is unchanged`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 40) t.add(s * 1000L, 20.0)
        assertEquals(0.0, t.driftPercent()!!, 0.001)
    }

    @Test
    fun `positive drift when breathing rate climbs`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)          // reference: 20
        for (s in 30 until 40) t.add(s * 1000L, 23.0)         // current: 23
        assertEquals(15.0, t.driftPercent()!!, 0.5)
    }

    @Test
    fun `negative drift when breathing settles`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)
        for (s in 30 until 40) t.add(s * 1000L, 18.0)
        assertTrue(t.driftPercent()!! < 0.0)
    }

    @Test
    fun `the reference window is fixed once captured`() {
        // The reference is the early steady period; later hard efforts must not redefine it.
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 10) t.add(s * 1000L, 20.0)
        for (s in 10 until 60) t.add(s * 1000L, 30.0)
        assertEquals("reference should still be 20", 50.0, t.driftPercent()!!, 1.0)
    }

    @Test
    fun `reset clears both windows`() {
        val t = DriftTracker(referenceSeconds = 10, currentSeconds = 5, warmupSeconds = 0)
        for (s in 0 until 40) t.add(s * 1000L, 20.0)
        t.reset()
        assertNull(t.driftPercent())
    }

    @Test
    fun `real ride breathing rates produce a plausible drift`() {
        val f = RideFixture.load("ride_2026-03-29.csv")
        val t = DriftTracker()
        var s = 0L
        for (v in f.br) { if (v != null && v > 0) t.add(s * 1000L, v); s++ }
        val d = t.driftPercent()
        assertTrue("expected a drift value for a 75-minute ride", d != null)
        assertTrue("drift should be within a sane range, got $d", d!! > -50.0 && d < 100.0)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DriftTrackerTest*'`
Expected: compilation failure — `DriftTracker` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.tymewear.karoo

/**
 * Drift of a breathing signal against its own early-effort reference.
 *
 * Experienced users pace long efforts this way: when breathing rate climbs more than
 * ~11-15% above where it started for the same work, the effort is no longer sustainable.
 * They currently compute it by hand from lap averages; nothing surfaces it live.
 *
 * The reference is captured once from the early steady period and then held, so a hard
 * finish cannot quietly redefine "normal". Pure; the clock is supplied by the caller.
 */
class DriftTracker(
    private val referenceSeconds: Int = DEFAULT_REFERENCE_SECONDS,
    private val currentSeconds: Int = DEFAULT_CURRENT_SECONDS,
    private val warmupSeconds: Int = DEFAULT_WARMUP_SECONDS,
) {
    private var startMs: Long? = null
    private val referenceValues = ArrayList<Double>()
    private var referenceMean: Double? = null
    private val current = ArrayDeque<Double>()

    fun add(nowMs: Long, value: Double) {
        val start = startMs ?: nowMs.also { startMs = it }
        val elapsedS = (nowMs - start) / 1000

        if (elapsedS < warmupSeconds) return

        if (referenceMean == null) {
            referenceValues.add(value)
            if (elapsedS >= warmupSeconds + referenceSeconds - 1) {
                referenceMean = referenceValues.average()
            }
            return
        }

        current.addLast(value)
        while (current.size > currentSeconds) current.removeFirst()
    }

    /** Percent change of the recent window against the held reference. */
    fun driftPercent(): Double? {
        val ref = referenceMean ?: return null
        if (ref <= 0.0 || current.isEmpty()) return null
        return (current.average() / ref - 1.0) * 100.0
    }

    fun reset() {
        startMs = null
        referenceValues.clear()
        referenceMean = null
        current.clear()
    }

    companion object {
        const val DEFAULT_REFERENCE_SECONDS = 300
        const val DEFAULT_CURRENT_SECONDS = 120
        const val DEFAULT_WARMUP_SECONDS = 60
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 58 tests total.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/DriftTracker.kt \
        app/src/test/kotlin/com/tymewear/karoo/DriftTrackerTest.kt
git commit -m "Add breathing-rate drift tracker"
```

---

### Task 6: Consolidate VE-zone classification

**Why (spec §7.1):** VE zone is currently computed in four places from different VE values, so the number a field shows and the colour behind it can disagree, and the FIT-recorded zone is derived from raw unsmoothed VE. Nothing built on top of this is trustworthy until there is one classification path.

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/ZoneClassifier.kt`
- Create: `app/src/test/kotlin/com/tymewear/karoo/ZoneClassifierTest.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/TymewearData.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/VentilationDataType.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt`

**Interfaces:**
- Consumes: existing `Protocol.veZone`.
- Produces:
  - `data class ZoneThresholds(val vt1: Double, val vt2: Double, val topZ4: Double, val vo2max: Double)`
  - `object ZoneClassifier { fun zoneFor(ve: Double, thresholds: ZoneThresholds): Int }`
  - `TymewearData.currentThresholds(): ZoneThresholds`
  - `TymewearData.zoneFor(ve: Double): Int`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneClassifierTest {

    private val t = ZoneThresholds(vt1 = 73.0, vt2 = 96.0, topZ4 = 112.0, vo2max = 130.0)

    @Test
    fun `classifies each zone from a VE value`() {
        assertEquals(1, ZoneClassifier.zoneFor(50.0, t))
        assertEquals(2, ZoneClassifier.zoneFor(80.0, t))
        assertEquals(3, ZoneClassifier.zoneFor(100.0, t))
        assertEquals(4, ZoneClassifier.zoneFor(120.0, t))
        assertEquals(5, ZoneClassifier.zoneFor(140.0, t))
    }

    @Test
    fun `no ventilation is not a zone`() {
        assertEquals(0, ZoneClassifier.zoneFor(0.0, t))
    }

    @Test
    fun `the value a caller displays determines the zone it gets`() {
        // The regression this locks down: a field must not show one VE and colour by a
        // zone derived from a different VE. Same input, same answer, every caller.
        val displayed = 97.5
        assertEquals(ZoneClassifier.zoneFor(displayed, t), ZoneClassifier.zoneFor(displayed, t))
        assertEquals(3, ZoneClassifier.zoneFor(displayed, t))
    }

    @Test
    fun `matches the legacy Protocol implementation across a sweep`() {
        // Guards the refactor: behaviour must be unchanged for every plausible VE.
        var ve = 0.0
        while (ve <= 250.0) {
            assertEquals(
                "VE=$ve",
                Protocol.veZone(ve, t.vt1, t.vt2, t.topZ4, t.vo2max),
                ZoneClassifier.zoneFor(ve, t),
            )
            ve += 0.5
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ZoneClassifierTest*'`
Expected: compilation failure — `ZoneClassifier` and `ZoneThresholds` unresolved.

- [ ] **Step 3: Implement the classifier**

```kotlin
package com.tymewear.karoo

/** The rider's configured ventilation thresholds, in L/min. */
data class ZoneThresholds(
    val vt1: Double,
    val vt2: Double,
    val topZ4: Double,
    val vo2max: Double,
)

/**
 * The single place a ventilation value becomes a zone.
 *
 * Previously four call sites classified independently from differently-smoothed VE, so a
 * field could display one number and colour itself from another, and the zone written to
 * the FIT file (derived from raw, unsmoothed VE) could differ from what the rider saw.
 * Every caller now passes the value it is actually presenting.
 */
object ZoneClassifier {
    fun zoneFor(ve: Double, thresholds: ZoneThresholds): Int =
        Protocol.veZone(ve, thresholds.vt1, thresholds.vt2, thresholds.topZ4, thresholds.vo2max)
}
```

- [ ] **Step 4: Expose thresholds and classification from TymewearData**

In `TymewearData.kt`, add below the existing threshold properties:

```kotlin
    /** The rider's configured thresholds as one value. */
    fun currentThresholds(): ZoneThresholds =
        ZoneThresholds(vt1Threshold, vt2Threshold, topZ4Threshold, vo2maxThreshold)

    /** Zone for a VE value the caller is presenting or recording. Callers pass the value
     *  they actually show, so the number and its zone can never disagree. */
    fun zoneFor(ve: Double): Int = ZoneClassifier.zoneFor(ve, currentThresholds())
```

Then replace the zone computation inside `update()` so it uses the shared path:

```kotlin
        // Zone from the smoothed VE this object publishes, via the one classifier.
        _veZone.value = zoneFor(_smoothMinuteVolume.value)
```

- [ ] **Step 5: Make the VE field classify the value it displays**

In `VentilationDataType.startView`, replace the zone lookup so the colour follows the displayed average rather than a separately-smoothed flow:

```kotlin
                // Colour from the same value shown, so number and zone always agree.
                val zone = if (fresh && avg > 0) TymewearData.zoneFor(avg) else 0
```

- [ ] **Step 6: Make FIT recording use consistently smoothed VE**

In `TymewearExtension.startFit`, the record body currently reads raw `minuteVolume` and computes its own zone from the prefs it read at ride start. Replace the value reads and zone computation with:

```kotlin
                            val br = TymewearData.smoothBreathRate.value
                            val tv = TymewearData.smoothTidalVolume.value
                            val ve = TymewearData.smoothMinuteVolume.value
                            val ie = TymewearData.ieRatio.value
                            val mi = TymewearData.mobilizationIndex.value
                            val brr = TymewearData.percentBrr.value
                            val zone = TymewearData.zoneFor(ve)
```

Then delete the now-unused local threshold reads at the top of `startFit` (`vt1`, `vt2`, `topZ4`, `vo2max` and their `prefs` lookup), since thresholds come from `TymewearData` which loads them in `onCreate`.

- [ ] **Step 7: Run the tests and build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS, 62 tests total, BUILD SUCCESSFUL.

Note the recorded `ve_zone` and time-in-zone now derive from smoothed VE rather than raw. That is the intended correction: it makes the recorded zone match what the rider was shown.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/ZoneClassifier.kt \
        app/src/test/kotlin/com/tymewear/karoo/ZoneClassifierTest.kt \
        app/src/main/kotlin/com/tymewear/karoo/TymewearData.kt \
        app/src/main/kotlin/com/tymewear/karoo/VentilationDataType.kt \
        app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt
git commit -m "Classify VE zones in one place from the value each caller presents"
```

---

### Task 7: Power stream and ventilatory-state lifecycle

**Why:** Nothing above can run without the Karoo's power stream, a place to own the baseline across rides, and persistence. This wires the pure components into the extension.

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/VentilatoryState.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/Constants.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt`

**Interfaces:**
- Consumes: `SteadyStateDetector`, `VeBaseline`, `EfficiencyDeviation`, `ThresholdShift`, `DriftTracker`, `TymewearData.isDataFresh()`.
- Produces:
  - `object VentilatoryState` with
    `val deviation: StateFlow<Deviation?>`,
    `val vt1PowerW: StateFlow<Double?>`,
    `val vt2PowerW: StateFlow<Double?>`,
    `val driftPercent: StateFlow<Double?>`,
    `val baselineBins: StateFlow<Int>`,
    `fun load(context: Context)`, `fun onPowerSample(loadW: Double?)`, `fun onRideStart()`, `fun onRideEnd(context: Context)`, `fun resetBaseline(context: Context)`, `fun isEnabled(): Boolean`

- [ ] **Step 1: Add tunables to Constants**

In `Constants.kt`, after the BLE section:

```kotlin
    // -------------------------------------------------------------------------
    // Ventilatory state (Beta)
    // -------------------------------------------------------------------------

    /** Minimum baseline bins with coverage before deviation is reported. */
    const val STATE_MIN_BASELINE_BINS = 3

    /** Drift percentage that counts as "no longer sustainable" for the optional alert. */
    const val STATE_DEFAULT_DRIFT_ALERT_PCT = 12
```

- [ ] **Step 2: Implement the holder**

```kotlin
package com.tymewear.karoo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Owns the ventilatory-state pipeline across a ride: which samples are comparable, what
 * the rider's baseline is, and how today differs from it.
 *
 * The baseline persists between rides — that is what makes the deviation "today versus my
 * recent normal" rather than "today versus the start of this ride".
 */
object VentilatoryState {

    private const val PREFS = "tymewear_prefs"
    private const val KEY_BASELINE = "baseline_bins"
    private const val KEY_UPDATED = "baseline_updated_at"
    private const val KEY_RIDES = "baseline_ride_count"
    private const val KEY_ENABLED = "dynamic_state_enabled"

    private var baseline = VeBaseline()
    private var detector = SteadyStateDetector()
    private var deviationCalc = EfficiencyDeviation(baseline)
    private var drift = DriftTracker()
    private var enabled = false
    private var rideCount = 0

    private val _deviation = MutableStateFlow<Deviation?>(null)
    val deviation: StateFlow<Deviation?> = _deviation.asStateFlow()

    private val _vt1PowerW = MutableStateFlow<Double?>(null)
    val vt1PowerW: StateFlow<Double?> = _vt1PowerW.asStateFlow()

    private val _vt2PowerW = MutableStateFlow<Double?>(null)
    val vt2PowerW: StateFlow<Double?> = _vt2PowerW.asStateFlow()

    private val _driftPercent = MutableStateFlow<Double?>(null)
    val driftPercent: StateFlow<Double?> = _driftPercent.asStateFlow()

    private val _baselineBins = MutableStateFlow(0)
    val baselineBins: StateFlow<Int> = _baselineBins.asStateFlow()

    fun isEnabled(): Boolean = enabled

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        rideCount = prefs.getInt(KEY_RIDES, 0)
        baseline = VeBaseline.deserialise(prefs.getString(KEY_BASELINE, "") ?: "")
        deviationCalc = EfficiencyDeviation(baseline)
        _baselineBins.value = baseline.coveredBins()
        Timber.d("VentilatoryState loaded: enabled=$enabled bins=${baseline.coveredBins()} rides=$rideCount")
    }

    /**
     * Feed one 1 Hz power sample. Breathing values are read from [TymewearData], and are
     * only used when fresh — a stale value is indistinguishable from a real one and would
     * make the deviation confident fiction.
     */
    fun onPowerSample(loadW: Double?) {
        if (!enabled) return
        val fresh = TymewearData.isDataFresh()
        val ve = if (fresh) TymewearData.smoothMinuteVolume.value.takeIf { it > 0.0 } else null
        val br = if (fresh) TymewearData.smoothBreathRate.value.takeIf { it > 0.0 } else null

        if (br != null) {
            drift.add(System.currentTimeMillis(), br)
            _driftPercent.value = drift.driftPercent()
        }

        val sample = detector.onSample(loadW, ve) ?: return
        deviationCalc.add(sample)
        baseline.update(sample)

        val dev = deviationCalc.deviation()
        _deviation.value = dev
        if (dev != null && baseline.coveredBins() >= Constants.STATE_MIN_BASELINE_BINS) {
            val t = TymewearData.currentThresholds()
            _vt1PowerW.value = ThresholdShift.thresholdPowerW(baseline, t.vt1, dev.fraction)
            _vt2PowerW.value = ThresholdShift.thresholdPowerW(baseline, t.vt2, dev.fraction)
        }
        _baselineBins.value = baseline.coveredBins()
    }

    fun onRideStart() {
        detector.reset()
        deviationCalc.reset()
        drift.reset()
        _deviation.value = null
        _vt1PowerW.value = null
        _vt2PowerW.value = null
        _driftPercent.value = null
    }

    fun onRideEnd(context: Context) {
        rideCount += 1
        persist(context)
        Timber.d("VentilatoryState saved: bins=${baseline.coveredBins()} rides=$rideCount")
    }

    fun resetBaseline(context: Context) {
        baseline = VeBaseline()
        deviationCalc = EfficiencyDeviation(baseline)
        rideCount = 0
        _baselineBins.value = 0
        persist(context)
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASELINE, baseline.serialise())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .putInt(KEY_RIDES, rideCount)
            .apply()
    }
}
```

- [ ] **Step 3: Subscribe to the power stream**

In `TymewearExtension.onCreate`, inside the `if (connected)` block, after the existing heart-rate subscription, add:

```kotlin
                VentilatoryState.load(applicationContext)

                // Power is the load signal for ventilatory state. Same pattern as the
                // heart-rate stream above; absent power simply yields no samples.
                scope.launch {
                    karooSystem.streamDataFlow(DataType.Type.POWER).collect { state ->
                        when (state) {
                            is StreamState.Streaming ->
                                VentilatoryState.onPowerSample(state.dataPoint.singleValue)
                            else -> VentilatoryState.onPowerSample(null)
                        }
                    }
                }
```

- [ ] **Step 4: Hook the ride lifecycle**

In the existing `RideState` consumer in `onCreate`, extend the transition handling:

```kotlin
                            if (state is RideState.Recording) {
                                Timber.d("Recording started — re-dispatching RequestBluetooth")
                                karooSystem.dispatch(RequestBluetooth(extension))
                                VentilatoryState.onRideStart()
                            }
                            if (state is RideState.Idle) {
                                VentilatoryState.onRideEnd(applicationContext)
                            }
```

- [ ] **Step 5: Build and verify the power type name**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

If `DataType.Type.POWER` does not resolve, list the available constants and pick the instantaneous-power one:

```bash
unzip -p ~/.gradle/caches/modules-2/files-2.1/io.hammerhead/karoo-ext/1.1.8/*/karoo-ext-1.1.8.aar classes.jar > /tmp/kx.jar
unzip -l /tmp/kx.jar | grep -i datatype
javap -cp /tmp/kx.jar 'io.hammerhead.karooext.models.DataType$Type' | grep -i power
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/VentilatoryState.kt \
        app/src/main/kotlin/com/tymewear/karoo/Constants.kt \
        app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt
git commit -m "Wire ventilatory-state pipeline to the Karoo power stream"
```

---

### Task 8: Ventilatory state and threshold power data fields

**Why:** The two headline readouts. Both must show an honest empty state rather than a number when the baseline is thin.

**Files:**
- Create: `app/src/main/res/layout/view_vent_state.xml`
- Create: `app/src/main/res/layout/view_threshold_power.xml`
- Create: `app/src/main/kotlin/com/tymewear/karoo/VentilatoryStateDataType.kt`
- Create: `app/src/main/kotlin/com/tymewear/karoo/ThresholdPowerDataType.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt`

**Interfaces:**
- Consumes: `VentilatoryState` flows (Task 7), `Constants.zoneStyle`.
- Produces: `VentilatoryStateDataType(extension)` with id `vent_state`; `ThresholdPowerDataType(extension)` with id `threshold_power`.

- [ ] **Step 1: Add the layouts**

`view_vent_state.xml` — mirrors `view_ventilation.xml` so sizing behaves consistently:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/container"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:baselineAligned="true"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/text_value"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="--"
            android:textColor="#FFFFFF"
            android:textSize="24sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/text_unit"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="2dp"
            android:text="vs base"
            android:textColor="#B3FFFFFF"
            android:textSize="10sp" />

    </LinearLayout>

</FrameLayout>
```

`view_threshold_power.xml` — identical but with `android:text="VT1 today"` on `text_unit`.

- [ ] **Step 2: Implement the ventilatory-state field**

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Today's ventilatory efficiency against the rider's own baseline. Negative means less
 * ventilation for the same power — fresher, fitter, a good day.
 *
 * Shows "cal" while the baseline is still being learned. An honest empty state matters
 * more than a number here: a plausible-looking deviation from a thin baseline is exactly
 * the kind of confident fiction this feature must avoid.
 */
class VentilatoryStateDataType(extension: String) : DataTypeImpl(extension, "vent_state") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
            VentilatoryState.deviation.collect { dev ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to (dev?.percent ?: 0.0)),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f

        scope.launch {
            VentilatoryState.deviation.collect { dev ->
                val views = RemoteViews(context.packageName, R.layout.view_vent_state)
                val text = when {
                    !VentilatoryState.isEnabled() -> "off"
                    dev == null -> "cal"
                    else -> String.format("%+.0f%%", dev.percent)
                }
                views.setTextViewText(R.id.text_value, text)
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                views.setTextViewText(R.id.text_unit, if (dev == null) "calibrating" else "vs base")
                // Lower ventilation for the same work is the good direction.
                val colour = when {
                    dev == null -> Constants.NO_DATA_COLOR
                    dev.percent <= -5.0 -> Constants.ZONE_COLORS_SOLID[0]
                    dev.percent >= 5.0 -> Constants.ZONE_COLORS_SOLID[3]
                    else -> Constants.ZONE_COLORS_SOLID[1]
                }
                views.setInt(R.id.container, "setBackgroundColor", colour)
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
```

- [ ] **Step 3: Implement the threshold-power field**

Same structure, reading `VentilatoryState.vt1PowerW`:

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The power at which the rider would cross VT1 today, given how far today's ventilation
 * sits from their baseline. This is what a rider actually paces by — the deviation
 * percentage is informative, a watts number is actionable.
 */
class ThresholdPowerDataType(extension: String) : DataTypeImpl(extension, "threshold_power") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
            VentilatoryState.vt1PowerW.collect { w ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to (w ?: 0.0)),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f

        scope.launch {
            VentilatoryState.vt1PowerW.collect { w ->
                val views = RemoteViews(context.packageName, R.layout.view_threshold_power)
                views.setTextViewText(
                    R.id.text_value,
                    when {
                        !VentilatoryState.isEnabled() -> "off"
                        w == null -> "cal"
                        else -> String.format("%.0f", w)
                    },
                )
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                views.setTextViewText(R.id.text_unit, if (w == null) "calibrating" else "W VT1 today")
                views.setInt(
                    R.id.container,
                    "setBackgroundColor",
                    if (w == null) Constants.NO_DATA_COLOR else Constants.ZONE_COLORS_SOLID[1],
                )
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
```

- [ ] **Step 4: Register both fields**

In `TymewearExtension.types`, add to the list:

```kotlin
            VentilatoryStateDataType(extension),
            ThresholdPowerDataType(extension),
```

- [ ] **Step 5: Build and install**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS 62 tests, BUILD SUCCESSFUL.

Then, with the Karoo connected:
```bash
export PATH=$PATH:~/Library/Android/sdk/platform-tools
adb install -r app/build/outputs/apk/debug/k-breathe.apk
```
Add both fields to a data page and confirm they render "off" (feature disabled by default) rather than crashing or showing a number.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/view_vent_state.xml \
        app/src/main/res/layout/view_threshold_power.xml \
        app/src/main/kotlin/com/tymewear/karoo/VentilatoryStateDataType.kt \
        app/src/main/kotlin/com/tymewear/karoo/ThresholdPowerDataType.kt \
        app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt
git commit -m "Add ventilatory state and threshold power data fields"
```

---

### Task 9: Breathing drift data field

**Why:** Surfaces the community's drift heuristic live. Display-only by default so it cannot become a nuisance.

**Files:**
- Create: `app/src/main/res/layout/view_breathing_drift.xml`
- Create: `app/src/main/kotlin/com/tymewear/karoo/BreathingDriftDataType.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt`

**Interfaces:**
- Consumes: `VentilatoryState.driftPercent` (Task 7).
- Produces: `BreathingDriftDataType(extension)` with id `br_drift`.

- [ ] **Step 1: Add the layout**

Copy `view_vent_state.xml` to `view_breathing_drift.xml`, changing `text_unit`'s text to `"BR drift"`.

- [ ] **Step 2: Implement the field**

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Breathing-rate drift against the effort's own early reference.
 *
 * Experienced Tymewear users pace long efforts by this: once breathing rate sits
 * 11-15% above where it started for the same work, the effort is no longer sustainable.
 * They compute it by hand from lap averages today; no head unit shows it live.
 */
class BreathingDriftDataType(extension: String) : DataTypeImpl(extension, "br_drift") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
            VentilatoryState.driftPercent.collect { d ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to (d ?: 0.0)),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f
        val alertPct = context
            .getSharedPreferences("tymewear_prefs", Context.MODE_PRIVATE)
            .getInt("drift_alert_pct", Constants.STATE_DEFAULT_DRIFT_ALERT_PCT)

        scope.launch {
            VentilatoryState.driftPercent.collect { d ->
                val views = RemoteViews(context.packageName, R.layout.view_breathing_drift)
                views.setTextViewText(
                    R.id.text_value,
                    if (d == null) "--" else String.format("%+.0f%%", d),
                )
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                views.setInt(
                    R.id.container,
                    "setBackgroundColor",
                    when {
                        d == null -> Constants.NO_DATA_COLOR
                        d >= alertPct -> Constants.ZONE_COLORS_SOLID[4]
                        d >= alertPct * 0.6 -> Constants.ZONE_COLORS_SOLID[3]
                        else -> Constants.ZONE_COLORS_SOLID[0]
                    },
                )
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
```

- [ ] **Step 3: Register it**

Add `BreathingDriftDataType(extension),` to `TymewearExtension.types`.

- [ ] **Step 4: Build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/view_breathing_drift.xml \
        app/src/main/kotlin/com/tymewear/karoo/BreathingDriftDataType.kt \
        app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt
git commit -m "Add breathing-rate drift data field"
```

---

### Task 10: Settings, documentation and version bump

**Why:** The feature is opt-in, so it needs a toggle and a visible baseline status; and a rider needs to know a power meter is required.

The calibration warning discussed in spec §6 (this rider's stored VT1 of 73 sits well above the ~53 their recorded data implies) is **deliberately not in this plan** — spec §14.5 leaves it as an open question. It needs its own decision about how to phrase a claim that the rider's own test values look wrong, which is a bigger conversation than a settings toggle.

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/screens/MainScreen.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/MainActivity.kt`
- Modify: `README.md`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Extend the prefs model**

In `MainScreen.kt`, add to `PrefsData`:

```kotlin
    val dynamicStateEnabled: Boolean,
    val driftAlertEnabled: Boolean,
    val driftAlertPct: Int,
```

Update `MainActivity`'s `loadPrefs`/`onSave` to read and write `dynamic_state_enabled` (default `false`), `drift_alert_enabled` (default `false`) and `drift_alert_pct` (default `Constants.STATE_DEFAULT_DRIFT_ALERT_PCT`), following the pattern already used for the threshold floats.

- [ ] **Step 2: Add the settings UI**

In `MainScreen`, after the MI parameters section:

```kotlin
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Ventilatory State (Beta)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Compares today's breathing against your own baseline. " +
                "Requires a power meter paired to the Karoo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = dynamicStateEnabled,
                onCheckedChange = { dynamicStateEnabled = it; saved = false },
            )
            Text(
                text = "Enable ventilatory state",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        val bins by VentilatoryState.baselineBins.collectAsState()
        Text(
            text = if (bins >= Constants.STATE_MIN_BASELINE_BINS) {
                "Baseline: $bins power ranges learned."
            } else {
                "Baseline: calibrating ($bins of ${Constants.STATE_MIN_BASELINE_BINS} " +
                    "power ranges). Ride steadily with power to build it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = driftAlertEnabled,
                onCheckedChange = { driftAlertEnabled = it; saved = false },
            )
            Text(
                text = "Alert on breathing drift",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
```

Add the corresponding `var dynamicStateEnabled by remember { mutableStateOf(false) }` etc. alongside the existing state, populate them in `LaunchedEffect`, and include them in the `PrefsData` passed to `onSave`. Import `androidx.compose.material3.Switch`.

- [ ] **Step 3: Document in the README**

Add to the data-fields table:

```markdown
| **VE State** | Today's ventilatory efficiency vs your own baseline (Beta). Negative = less breathing for the same power |
| **VT1 Today** | The power at which you'd cross VT1 today, given how your breathing compares with baseline (Beta) |
| **BR Drift** | Breathing-rate drift vs the effort's early reference — a live view of the "11–15% and you're done" rule |
```

And a section:

```markdown
## Ventilatory State (Beta)

Ventilatory thresholds move day to day with fatigue, heat, sleep and freshness. A fixed
zone table asserts one number every day and is wrong on most of them.

With a power meter paired, K-Breathe learns what ventilation you normally produce at each
power and then shows how today differs from it — as a percentage, and as the power at
which you'd cross VT1 today. It needs no threshold test: the baseline builds itself from
steady riding and tracks your fitness as it changes.

Enable it under **Ventilatory State (Beta)** in the app. Until enough steady riding has
accumulated the fields show "calibrating" rather than a number.

**Requires a power meter.** Without power there is no power-to-ventilation relationship
to measure, and the fields report unavailable.
```

- [ ] **Step 4: Bump the version**

In `app/build.gradle.kts`:

```kotlin
        versionCode = 10
        versionName = "0.5.0"
```

- [ ] **Step 5: Full verification**

```bash
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home"
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
```
Expected: PASS all tests, BUILD SUCCESSFUL.

Then on-device: install, enable the toggle, add the three fields to a page, and ride (or spin) steadily with power and the strap connected for long enough to build a baseline. Confirm the fields move from "calibrating" to real numbers, and that removing the sensor returns them to a safe state rather than a frozen value.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/screens/MainScreen.kt \
        app/src/main/kotlin/com/tymewear/karoo/MainActivity.kt \
        README.md app/build.gradle.kts
git commit -m "Add ventilatory state settings, docs and bump to 0.5.0"
```

---

## Notes for the implementer

- **Golden values are derived from a validated reference prototype**, not invented. If one fails, the implementation has diverged from the documented semantics — fix the code, not the assertion. The reference parameters are: steady window 60 s, max power CV 0.12, ≥45 valid samples in window, VE smoothed over 30 samples causally, power range 100–240 W, 20 W bins, ≥30 samples per bin, ≥3 matched bins.
- **The one-ride-per-baseline caveat.** The `-9.36%` for `ride_2026-02-24.csv` is expected even though that ride is *in* the baseline: it was a genuinely low-ventilation day, and a three-ride mean sits above it.
- **Do not gate the feature on a threshold test.** That was the first design and it was wrong; see spec §1.1.
- **Indoor/outdoor is a known unresolved risk** (spec §8). One baseline ships in v1. If deviations correlate with environment rather than physiology in real use, that is the first thing to revisit.

  Spec §8 says the design must allow an environment split "without redesign". This plan achieves that without an unused parameter: `VeBaseline` instances are self-contained and `serialise()`/`deserialise()` are per-instance, so a split becomes "hold two instances and choose the preference key by environment" inside `VentilatoryState` — no change to `VeBaseline`, `EfficiencyDeviation` or `ThresholdShift`. Do not add an environment parameter speculatively.

- **Task ordering matters.** Tasks 1-5 are pure and independently testable in any order, but Task 6 (zone consolidation) should land before Tasks 8-9 add fields, and Task 7 must precede Tasks 8-10 since the fields read its flows.
