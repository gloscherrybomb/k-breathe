# BLE reliability implementation plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship v0.4.2 with initial-connect reliability (scan-assisted), connection priority tuning, decoded logging, and a foreground service that keeps BLE alive across ride-start transitions.

**Architecture:** Keep the existing raw-`connectGatt` code in `BleManager`. Add a scan step before the first connect attempt only. Add `requestConnectionPriority(HIGH)` after services discovered. Add a separate `BleForegroundService` started by `TymewearExtension` on first active connection.

**Tech Stack:** Kotlin, Android `BluetoothLeScanner` / `BluetoothGatt`, hammerhead `karoo-ext` SDK v1.1.8, Timber logging.

**Design reference:** `docs/plans/2026-04-22-ble-reliability-design.md`

**Testing constraint:** No test infra in the project; on-device testing not available during implementation. Verification per task is `./gradlew assembleDebug` succeeding + code review. Integration testing happens when the user gets the Karoo back.

---

## Task 1 — Add `BleStatus` decoder helper

**Purpose:** Pure helper used by subsequent tasks; no behavior change.

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/BleStatus.kt`

**Step 1: Create the file**

```kotlin
package com.tymewear.karoo

/**
 * Decodes Android BLE status and scan error codes into human-readable
 * strings for logcat. Pure helper, no state.
 */
object BleStatus {

    /** Decode a `BluetoothGatt` status code (from `onConnectionStateChange`,
     *  `onServicesDiscovered`, `onDescriptorWrite`, etc.). */
    fun decodeGatt(status: Int): String = when (status) {
        0 -> "SUCCESS"
        8 -> "CONN_TIMEOUT"
        19 -> "REMOTE_DISCONNECT"
        22 -> "LOCAL_DISCONNECT"
        34 -> "LMP_TIMEOUT"
        62 -> "CONN_FAIL_ESTABLISH"
        133 -> "GATT_ERROR"
        137 -> "AUTH_FAIL"
        143 -> "INSUF_ENCRYPT"
        257 -> "TOO_MANY_CONNECTIONS"
        else -> "UNKNOWN"
    }.let { "$it($status)" }

    /** Decode a `ScanCallback.SCAN_FAILED_*` error code. */
    fun decodeScan(errorCode: Int): String = when (errorCode) {
        1 -> "ALREADY_STARTED"
        2 -> "APP_REG_FAILED"
        3 -> "INTERNAL_ERROR"
        4 -> "FEATURE_UNSUPPORTED"
        5 -> "OUT_OF_RESOURCES"
        6 -> "SCANNING_TOO_FREQUENTLY"
        else -> "UNKNOWN"
    }.let { "$it($errorCode)" }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/BleStatus.kt
git commit -m "Add BleStatus helper for decoded GATT and scan error logs"
```

---

## Task 2 — Wire `BleStatus` into existing `BleManager` logs

**Purpose:** Make current log lines identify GATT/scan failures by name. Additive; no behavior change.

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/BleManager.kt`

**Step 1: Replace each raw `status=$status` / `errorCode: $errorCode` log with decoded form**

In `BleManager.kt`, make these exact replacements:

| Line (current) | Replace with |
|---|---|
| `Timber.w("GATT status=$status (newState=$newState) for $address")` | `Timber.w("GATT status=${BleStatus.decodeGatt(status)} newState=$newState for $address")` |
| `Timber.w("Connected with error status $status, scheduling retry")` | `Timber.w("Connected with error status ${BleStatus.decodeGatt(status)}, scheduling retry")` |
| `Timber.w("GATT disconnected from $address (status=$status, attempt=${reconnectAttempt.get()})")` | `Timber.w("GATT disconnected from $address (status=${BleStatus.decodeGatt(status)}, attempt=${reconnectAttempt.get()})")` |
| `Timber.e("Service discovery failed: status=$status")` | `Timber.e("Service discovery failed: status=${BleStatus.decodeGatt(status)}")` |
| `Timber.e("Descriptor write failed for $charUuid: status=$status")` | `Timber.e("Descriptor write failed for $charUuid: status=${BleStatus.decodeGatt(status)}")` |
| `Timber.e("BLE scan failed: $errorCode")` | `Timber.e("BLE scan failed: ${BleStatus.decodeScan(errorCode)}")` |

Use the `Edit` tool for each. `close()` call on the flow stays as a raw int since that's the exception surface.

**Step 2: Add time-to-first-packet logging**

In `connect(address)` near the top, after the existing field declarations, add:

```kotlin
val sessionStartMs = System.currentTimeMillis()
val firstPacketLogged = AtomicBoolean(false)
```

In the `onCharacteristicChanged` (both overloads — the non-deprecated one and the deprecated one), replace the first line `lastDataTime.set(System.currentTimeMillis())` with:

```kotlin
val now = System.currentTimeMillis()
lastDataTime.set(now)
if (firstPacketLogged.compareAndSet(false, true)) {
    Timber.d("First data received after ${now - sessionStartMs}ms")
}
```

**Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/BleManager.kt
git commit -m "Decode GATT and scan status codes in logs; time-to-first-packet"
```

---

## Task 3 — Add `requestConnectionPriority(HIGH)` in `onServicesDiscovered`

**Purpose:** Shorten connection interval to ~11 ms so notifications survive radio contention.

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/BleManager.kt` (inside `onServicesDiscovered`)

**Step 1: Add the call**

Find (around line 228 in the current file):

```kotlin
override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
    if (status != BluetoothGatt.GATT_SUCCESS) {
        Timber.e("Service discovery failed: status=${BleStatus.decodeGatt(status)}")
        g.disconnect()
        return
    }
    Timber.d("Services discovered, subscribing to notifications")
    subscribeToAllCharacteristics(g)
}
```

Replace with:

```kotlin
override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
    if (status != BluetoothGatt.GATT_SUCCESS) {
        Timber.e("Service discovery failed: status=${BleStatus.decodeGatt(status)}")
        g.disconnect()
        return
    }
    val priorityOk = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    Timber.d("requestConnectionPriority(HIGH) = $priorityOk")
    Timber.d("Services discovered, subscribing to notifications")
    subscribeToAllCharacteristics(g)
}
```

**Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/BleManager.kt
git commit -m "Request HIGH connection priority after services discovered"
```

---

## Task 4 — Scan-assisted initial connect

**Purpose:** Replace direct `connectGatt(stale_mac, autoConnect=false)` on first attempt with a targeted MAC scan. On scan hit, connect to fresh `ScanResult.device`. On 8s timeout, escalate to `autoConnect=true`.

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/BleManager.kt` — `connect(address)` function.

**Step 1: Add `activeScanCallback` field**

At the top of `BleManager` class, alongside `activeDescriptorQueue`:

```kotlin
// Per-connection targeted scan callback (for scan-assisted initial connect)
private val activeScanCallback = AtomicReference<ScanCallback?>(null)
```

**Step 2: Refactor `attemptConnect` to accept a `BluetoothDevice`**

Current signature:
```kotlin
lateinit var attemptConnect: (autoConnect: Boolean) -> Unit
```

New signature:
```kotlin
lateinit var attemptConnect: (target: BluetoothDevice, autoConnect: Boolean) -> Unit
```

Inside `attemptConnect`'s body, replace the reference to the outer `device` with the `target` parameter in the `connectGatt` call:

```kotlin
attemptConnect = fn@{ target, autoConnect ->
    if (closed.get()) return@fn
    Timber.d("connectGatt to ${target.address} (autoConnect=$autoConnect, attempt=${reconnectAttempt.get()})")
    gatt.getAndSet(null)?.close()
    activeDescriptorQueue.set(null)

    gatt.set(target.connectGatt(context, autoConnect, object : BluetoothGattCallback() {
        // ... existing callback body unchanged ...
    }, BluetoothDevice.TRANSPORT_LE))
}
```

**Step 3: Update all `attemptConnect(...)` call sites**

Inside `scheduleReconnect` (rapid and slow phases), replace `attemptConnect(false)` with `attemptConnect(device, false)` and `attemptConnect(true)` with `attemptConnect(device, true)`. The outer `device` (from `getRemoteDevice`) is always available via closure.

**Step 4: Add the `scanThenConnect` function and use it for the initial attempt**

Below `attemptConnect = ...`, add:

```kotlin
lateinit var scanThenConnect: () -> Unit
scanThenConnect = {
    if (!closed.get()) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("Scanner unavailable for targeted scan, falling back to autoConnect=true")
            attemptConnect(device, true)
        } else {
            val scanStartMs = System.currentTimeMillis()
            val filter = ScanFilter.Builder().setDeviceAddress(address).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val cb = object : ScanCallback() {
                private val hit = AtomicBoolean(false)
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!hit.compareAndSet(false, true)) return
                    val elapsed = System.currentTimeMillis() - scanStartMs
                    Timber.d("Targeted scan hit for $address in ${elapsed}ms")
                    scanner.stopScan(this)
                    activeScanCallback.compareAndSet(this, null)
                    handler.removeCallbacksAndMessages("scanTimeout")
                    attemptConnect(result.device, false)
                }
                override fun onScanFailed(errorCode: Int) {
                    Timber.e("Targeted scan failed: ${BleStatus.decodeScan(errorCode)}; falling back to autoConnect=true")
                    scanner.stopScan(this)
                    activeScanCallback.compareAndSet(this, null)
                    handler.removeCallbacksAndMessages("scanTimeout")
                    attemptConnect(device, true)
                }
            }
            activeScanCallback.set(cb)

            // Timeout runnable: if no hit in 8s, escalate to autoConnect
            val timeoutRunnable = Runnable {
                if (closed.get()) return@Runnable
                val existing = activeScanCallback.getAndSet(null)
                if (existing === cb) {
                    Timber.w("Targeted scan timeout after 8s, falling back to autoConnect=true")
                    scanner.stopScan(cb)
                    attemptConnect(device, true)
                }
            }
            Timber.d("Starting targeted scan for $address (8s timeout)")
            scanner.startScan(listOf(filter), settings, cb)
            handler.postDelayed(timeoutRunnable, 8000)
        }
    }
}
```

Note: `handler.removeCallbacksAndMessages("scanTimeout")` is wrong API — `Handler.postDelayed(Runnable, delay)` doesn't support tokens without an overload. Use a direct `Runnable` reference instead. Rewrite the cleanup path:

Change `val timeoutRunnable = Runnable { ... }` to a local `val` captured in the scan callback too, and use `handler.removeCallbacks(timeoutRunnable)` in `onScanResult` and `onScanFailed`. Re-order declarations so `timeoutRunnable` is declared before the callback but the callback references it via closure. Since Kotlin requires declare-before-use, use `lateinit var timeoutRunnable: Runnable` at the top of the `scanThenConnect` body and assign before `postDelayed`.

Final structure inside `scanThenConnect`:

```kotlin
scanThenConnect = {
    if (!closed.get()) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("Scanner unavailable for targeted scan, falling back to autoConnect=true")
            attemptConnect(device, true)
        } else {
            val scanStartMs = System.currentTimeMillis()
            val filter = ScanFilter.Builder().setDeviceAddress(address).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            lateinit var timeoutRunnable: Runnable

            val cb = object : ScanCallback() {
                private val hit = AtomicBoolean(false)
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!hit.compareAndSet(false, true)) return
                    val elapsed = System.currentTimeMillis() - scanStartMs
                    Timber.d("Targeted scan hit for $address in ${elapsed}ms")
                    scanner.stopScan(this)
                    activeScanCallback.compareAndSet(this, null)
                    handler.removeCallbacks(timeoutRunnable)
                    attemptConnect(result.device, false)
                }
                override fun onScanFailed(errorCode: Int) {
                    Timber.e("Targeted scan failed: ${BleStatus.decodeScan(errorCode)}; falling back to autoConnect=true")
                    scanner.stopScan(this)
                    activeScanCallback.compareAndSet(this, null)
                    handler.removeCallbacks(timeoutRunnable)
                    attemptConnect(device, true)
                }
            }
            activeScanCallback.set(cb)

            timeoutRunnable = Runnable {
                if (closed.get()) return@Runnable
                val existing = activeScanCallback.getAndSet(null)
                if (existing === cb) {
                    Timber.w("Targeted scan timeout after 8s, falling back to autoConnect=true")
                    scanner.stopScan(cb)
                    attemptConnect(device, true)
                }
            }

            Timber.d("Starting targeted scan for $address (8s timeout)")
            scanner.startScan(listOf(filter), settings, cb)
            handler.postDelayed(timeoutRunnable, 8000)
        }
    }
}
```

**Step 5: Replace the initial connect call**

Currently (near the bottom of `connect`):
```kotlin
// First connection: direct (not autoConnect) for faster initial connect
attemptConnect(false)
```

Replace with:
```kotlin
// First connection: scan-assisted (scan for MAC, connect to fresh handle)
scanThenConnect()
```

**Step 6: Update `awaitClose` to stop any active scan**

Replace:
```kotlin
awaitClose {
    Timber.d("Closing GATT connection to $address")
    closed.set(true)
    handler.removeCallbacksAndMessages(null)
    gatt.getAndSet(null)?.close()
}
```

With:
```kotlin
awaitClose {
    Timber.d("Closing GATT connection to $address")
    closed.set(true)
    handler.removeCallbacksAndMessages(null)
    activeScanCallback.getAndSet(null)?.let {
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {}
    }
    gatt.getAndSet(null)?.close()
}
```

**Step 7: Extract `BluetoothGattCallback` to avoid duplication**

Because `attemptConnect` now has a `target` parameter but re-creates the anonymous `BluetoothGattCallback` on each call, nothing changes in the callback definition itself. Keep the callback as a local `object :` inside `attemptConnect` as it already is. (Only the device reference in `connectGatt(...)` changes.)

**Step 8: Constant extraction**

In `Constants.kt`, add below the existing BLE section:

```kotlin
/** Initial connect: time to wait for device to appear in a targeted scan
 *  before falling back to autoConnect=true. */
const val BLE_INITIAL_SCAN_TIMEOUT_MS = 8000L
```

Then in `BleManager.scanThenConnect`, replace the literal `8000` in `postDelayed` and the `8s` in the log with `Constants.BLE_INITIAL_SCAN_TIMEOUT_MS` and `${Constants.BLE_INITIAL_SCAN_TIMEOUT_MS / 1000}s`.

**Step 9: Verify compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. If any `attemptConnect(` call site still uses the old one-arg form, fix it.

**Step 10: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/BleManager.kt \
        app/src/main/kotlin/com/tymewear/karoo/Constants.kt
git commit -m "Scan-assisted initial BLE connect with autoConnect fallback"
```

---

## Task 5 — Foreground service + manifest + ref-count

**Purpose:** Keep `TymewearExtension` process alive across ride-start so BLE doesn't silently die (issue #6).

**Files:**
- Create: `app/src/main/kotlin/com/tymewear/karoo/BleForegroundService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt`

**Step 1: Create `BleForegroundService.kt`**

```kotlin
package com.tymewear.karoo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import timber.log.Timber

/**
 * Plain foreground service that pins the app process in memory while a BLE
 * connection is active. Started by `TymewearExtension` when the first device
 * connects, stopped when the last one disconnects.
 *
 * Pattern modeled on `timklge/karoo-powerbar`: a separate Service (not the
 * `KarooExtension` itself), `IMPORTANCE_MIN` channel, `CATEGORY_SERVICE`.
 */
class BleForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("BleForegroundService starting")
        ensureChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VitalPro connected")
            .setContentText("Tymewear breathing sensor is active")
            .setSmallIcon(R.drawable.ic_breathing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Android 14+ enforces foregroundServiceType declarations.
            // Ecosystem precedent (timklge/karoo-powerbar) omits the type, so we
            // match. If a future Karoo firmware targets Android 14+ and rejects
            // this, log and continue without foreground promotion — BLE may drop
            // on ride-start as before, but the app stays alive.
            Timber.w(e, "startForeground rejected — running without foreground promotion")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("BleForegroundService stopping")
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tymewear_ble"
        private const val NOTIFICATION_ID = 1001

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "Tymewear BLE",
                        NotificationManager.IMPORTANCE_MIN,
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
                    mgr.createNotificationChannel(ch)
                }
            }
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BleForegroundService::class.java))
        }
    }
}
```

**Step 2: Update `AndroidManifest.xml`**

Add the permission at the top with the other uses-permissions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

Add the service declaration alongside the existing `TymewearExtension` service:

```xml
<service
    android:name=".BleForegroundService"
    android:exported="false" />
```

Do **not** add `foregroundServiceType` or `FOREGROUND_SERVICE_CONNECTED_DEVICE` — matches ecosystem precedent (timklge powerbar). Karoo's Android version predates Android 14's stricter foreground rules.

**Step 3: Ref-count active connections in `TymewearExtension`**

`io.hammerhead.karooext.internal.Emitter` is confirmed as an **interface** (verified via `javap` on `karoo-ext-1.1.8-api.jar`). We wrap it so the decrement callback is chained into whatever cancellable `TymewearDevice.connect` installs — we don't know whether the underlying `setCancellable` chains or replaces, and this wrapper makes that question irrelevant.

At the top of `TymewearExtension` (alongside `bleManager` and `scope`), add:

```kotlin
private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
```

Replace the body of `connectDevice` with:

```kotlin
override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
    Timber.d("Connecting to device: $uid")

    if (activeConnections.incrementAndGet() == 1) {
        Timber.d("First active connection — starting foreground service")
        BleForegroundService.start(applicationContext)
    }

    val wrapped = object : Emitter<DeviceEvent> {
        override fun onNext(event: DeviceEvent) = emitter.onNext(event)
        override fun onError(err: Throwable) = emitter.onError(err)
        override fun onComplete() = emitter.onComplete()
        override fun cancel() = emitter.cancel()
        override fun setCancellable(cancel: () -> Unit) {
            emitter.setCancellable {
                try { cancel() } finally {
                    if (activeConnections.decrementAndGet() == 0) {
                        Timber.d("Last active connection closed — stopping foreground service")
                        BleForegroundService.stop(applicationContext)
                    }
                }
            }
        }
    }

    val device = TymewearDevice(
        extension = extension,
        uid = uid,
        displayName = "VitalPro",
        bleManager = bleManager,
    )
    device.connect(wrapped)
}
```

Note: `Emitter` interface methods (from `javap`): `onNext(T)`, `onError(Throwable)`, `onComplete()`, `setCancellable(Function0<Unit>)`, `cancel()`. All five must be overridden; the syntax is `object : Emitter<DeviceEvent> { ... }` (interface, no parens).

**Step 4: Verify compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

If Option B fails to compile because `Emitter` is not subclassable that way, revert that file change and use Option A. Commit the working version.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/BleForegroundService.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/kotlin/com/tymewear/karoo/TymewearExtension.kt
git commit -m "Add foreground service to pin BLE connection across ride-start"
```

---

## Task 6 — Version bump + manifest

**Purpose:** Ship as v0.4.2.

**Files:**
- Modify: `app/build.gradle.kts` — bump `versionCode` from 7 → 8, `versionName` from "0.4.1" → "0.4.2".
- Modify: `manifest.json` (if it exists at repo root) — bump the version string to match.

**Step 1: Find and update**

Run: `./gradlew assembleDebug` before changing, to confirm clean baseline.

In `app/build.gradle.kts`:
```kotlin
versionCode = 8
versionName = "0.4.2"
```

If `manifest.json` exists at repo root, update its version field to `"0.4.2"` and its apkUrl to point to the 0.4.2 asset URL (check existing file for the URL template).

**Step 2: Verify compile + APK artifact**

Run: `./gradlew assembleRelease`
Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/release/k-breathe.apk` exists.

**Step 3: Commit**

```bash
git add app/build.gradle.kts manifest.json
git commit -m "Bump version to 0.4.2 (versionCode 8)"
```

---

## Verification summary

After all 6 commits, `git log --oneline` should show:

```
<hash> Bump version to 0.4.2 (versionCode 8)
<hash> Add foreground service to pin BLE connection across ride-start
<hash> Scan-assisted initial BLE connect with autoConnect fallback
<hash> Request HIGH connection priority after services discovered
<hash> Decode GATT and scan status codes in logs; time-to-first-packet
<hash> Add BleStatus helper for decoded GATT and scan error logs
<hash> Update default zone thresholds and manifest for v0.4.1 release   ← existing HEAD
```

Each commit should independently pass `./gradlew assembleDebug`. If any commit fails the build, `git rebase -i` to fix it in place; don't merge a broken intermediate state.

## On-device test plan (when Karoo is available)

Follow `docs/plans/2026-04-22-ble-reliability-design.md` § "Testing".

## Notify reviewers on 0.4.2 release

- `pingyijin` (#4) — contention scenario
- `thomasbrunner-spec` (#6) — freeze on Start
- `nottechsavvvvy` (#4) — confirm earlier fix + new changes
