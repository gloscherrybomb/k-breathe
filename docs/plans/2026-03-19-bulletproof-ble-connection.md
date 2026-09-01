# Bulletproof BLE Connection Implementation Plan (v2 — steelmanned)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the BLE connection to the Tymewear VitalPro sensor resilient to firmware updates, GATT cache staleness, and mid-ride disconnects — ensuring automatic recovery in all scenarios.

**Architecture:** Four layers of defense: (1) GATT cache refresh on reconnect to handle firmware updates, (2) unlimited reconnection with two-phase manual backoff then Android-native autoConnect, (3) a data watchdog that detects silent connection loss using IMU packets as heartbeat, (4) per-connection descriptor queue scoping to prevent corruption on rapid reconnect. All changes are in `BleManager.kt`, `Constants.kt`, and `Protocol.kt`.

**Tech Stack:** Android BLE (BluetoothGatt), Kotlin coroutines/Flow, Timber logging

---

### Task 1: Scope `descriptorWriteQueue` per-connection and update constants

**Why:** The `descriptorWriteQueue` is currently a class-level `LinkedList` shared across all connections. If a reconnect triggers `subscribeToAllCharacteristics` while the previous connection's queue is still being processed, the queue gets corrupted — descriptors from the old GATT interleave with the new one. Also update reconnection constants for the new two-phase strategy.

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/Constants.kt`
- Modify: `app/src/main/kotlin/com/tymewear/karoo/BleManager.kt`

**Step 1: Update constants for two-phase reconnection + watchdog**

In `Constants.kt`, replace the BLE reconnect parameters section (lines 75-81):

Old:
```kotlin
// -------------------------------------------------------------------------
// BLE reconnect parameters
// -------------------------------------------------------------------------

const val BLE_MAX_RECONNECT_ATTEMPTS = 10
const val BLE_MAX_RECONNECT_DELAY_MS = 30000L
const val BLE_BASE_RECONNECT_DELAY_MS = 2000L
```

New:
```kotlin
// -------------------------------------------------------------------------
// BLE reconnect parameters — two-phase strategy
// Phase 1 (rapid): short fixed delays, direct connect for fast recovery
// Phase 2 (slow): autoConnect=true, lets Android handle scanning efficiently
// -------------------------------------------------------------------------

/** Phase 1: rapid reconnect attempts (2s apart, up to 10 tries = ~20s) */
const val BLE_RAPID_PHASE_ATTEMPTS = 10
const val BLE_RAPID_PHASE_DELAY_MS = 2000L

/** Phase 2: use autoConnect=true (Android-native power-efficient scanning).
 *  Manual retry interval as fallback if autoConnect fails. */
const val BLE_SLOW_PHASE_DELAY_MS = 60000L

/** Data watchdog: if no BLE notification for this long, force reconnect.
 *  Must be longer than the longest expected gap between IMU packets (~1Hz)
 *  plus breath packets (~4s). 45s allows for sensor pauses at traffic lights. */
const val BLE_DATA_WATCHDOG_TIMEOUT_MS = 45000L

/** How often the watchdog checks for data freshness */
const val BLE_DATA_WATCHDOG_INTERVAL_MS = 10000L
```

**Step 2: Move `descriptorWriteQueue` from class-level to per-connection**

In `BleManager.kt`, remove the class-level field (line 231):
```kotlin
// DELETE THIS LINE:
private val descriptorWriteQueue = LinkedList<BluetoothGattDescriptor>()
```

Then change `subscribeToAllCharacteristics` and `subscribeNext` to pass the queue explicitly. Replace both methods entirely:

```kotlin
/**
 * Subscribe to all VitalPro characteristic notifications.
 * Queues CCCD writes since Android BLE only supports one GATT operation at a time.
 * Queue is scoped to this connection to prevent corruption on rapid reconnect.
 */
private fun subscribeToAllCharacteristics(gatt: BluetoothGatt) {
    val service = gatt.getService(Protocol.VITALPRO_SERVICE_UUID)
    if (service == null) {
        Timber.e("VitalPro service ${Protocol.VITALPRO_SERVICE_UUID} not found!")
        Timber.e("Available services (${gatt.services.size}):")
        gatt.services.forEach { s ->
            Timber.e("  Service: ${s.uuid}")
            s.characteristics.forEach { c ->
                Timber.e("    Char: ${c.uuid} props=0x%02x".format(c.properties))
            }
        }
        // Disconnect and let reconnect logic retry — service table may be stale
        gatt.disconnect()
        return
    }

    Timber.d("VitalPro service found with ${service.characteristics.size} characteristics")

    // Subscribe to all three known characteristics
    val charUuids = listOf(
        Protocol.BREATHING_DATA_CHAR_UUID,
        Protocol.COMMAND_CHAR_UUID,
        Protocol.SENSOR_ID_CHAR_UUID,
    )

    // Per-connection queue prevents corruption on rapid reconnect
    val queue = LinkedList<BluetoothGattDescriptor>()

    for (uuid in charUuids) {
        val characteristic = service.getCharacteristic(uuid)
        if (characteristic == null) {
            Timber.e("Characteristic $uuid not found in VitalPro service — firmware may have changed")
            continue
        }

        // Check if the characteristic supports notifications
        val hasNotify = (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val hasIndicate = (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        if (!hasNotify && !hasIndicate) {
            Timber.d("Characteristic $uuid does not support notify/indicate (props=${characteristic.properties})")
            continue
        }

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(Protocol.CCCD_UUID)
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            descriptor.value = if (hasNotify) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            queue.add(descriptor)
            Timber.d("Queued notification subscription for $uuid")
        } else {
            Timber.w("CCCD descriptor not found for $uuid")
        }
    }

    // Start processing the queue
    // Store queue reference so subscribeNext can access it from onDescriptorWrite
    activeDescriptorQueue.set(queue)
    subscribeNext(gatt)
}

/**
 * Write the next queued CCCD descriptor, or read battery level when queue is empty.
 */
private fun subscribeNext(gatt: BluetoothGatt) {
    val queue = activeDescriptorQueue.get()
    val descriptor = queue?.poll()
    if (descriptor != null) {
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
    } else {
        // All subscriptions complete — read battery level from standard Battery Service
        val batteryService = gatt.getService(Protocol.BATTERY_SERVICE_UUID)
        val batteryChar = batteryService?.getCharacteristic(Protocol.BATTERY_LEVEL_CHAR_UUID)
        if (batteryChar != null) {
            @Suppress("DEPRECATION")
            gatt.readCharacteristic(batteryChar)
        }
    }
}
```

Add the `activeDescriptorQueue` field at the class level (replacing the old `descriptorWriteQueue`):
```kotlin
// Per-connection descriptor write queue (AtomicReference for thread-safe swap on reconnect)
private val activeDescriptorQueue = AtomicReference<LinkedList<BluetoothGattDescriptor>?>(null)
```

Add the import for `AtomicReference` (already imported, just verify).

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/Constants.kt app/src/main/kotlin/com/tymewear/karoo/BleManager.kt
git commit -m "refactor: scope descriptor queue per-connection, update BLE constants for two-phase strategy"
```

---

### Task 2: Rewrite `connect()` with GATT refresh, two-phase reconnect, error handling, and watchdog

**Why:** This is the core change. The current `connect()` method has several issues identified in the steelman review:
- No GATT cache refresh (stale services after firmware updates)
- Gives up after 10 reconnects (fatal mid-ride)
- No watchdog for silent notification loss
- Double-close risk if `g.close()` called in callback AND in `attemptConnect()`
- No handling of GATT error 133 or "connected with error" states
- 300ms delay for `discoverServices()` has a race condition if disconnect happens during the delay

This task rewrites the entire `connect()` method with all fixes integrated cleanly.

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/BleManager.kt`

**Step 1: Add `refreshGattCache` helper method**

Add after the `bluetoothAdapter` property (after line 38), before `ScannedDevice`:

```kotlin
/**
 * Clear Android's GATT service cache via the hidden BluetoothGatt.refresh() method.
 * Forces fresh service discovery after firmware updates that change the GATT table.
 * Returns true if the method was found and invoked successfully.
 */
private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
    return try {
        val method = gatt.javaClass.getMethod("refresh")
        method.invoke(gatt) as? Boolean ?: false
    } catch (e: Exception) {
        Timber.w("GATT cache refresh not available: ${e.message}")
        false
    }
}
```

**Step 2: Rewrite the `connect()` method**

Replace the entire `connect()` method (lines 104-228) with:

```kotlin
/**
 * Connect to a VitalPro device by MAC address and observe breathing data notifications.
 * Includes:
 * - GATT cache refresh on reconnect (handles firmware updates)
 * - Two-phase reconnection: rapid (10x 2s) then slow (autoConnect=true, power-efficient)
 * - Data watchdog: detects silent notification loss (45s timeout)
 * - GATT error 133 and non-SUCCESS status handling
 */
fun connect(address: String): Flow<ConnectionEvent> = callbackFlow {
    val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(address)
        ?: run {
            close(Exception("Bluetooth adapter not available"))
            return@callbackFlow
        }

    val gatt = AtomicReference<BluetoothGatt?>(null)
    var reconnectAttempt = 0
    val handler = Handler(Looper.getMainLooper())
    val closed = AtomicBoolean(false)
    // Tracks time of last BLE notification for the data watchdog.
    // Initialized to MAX_VALUE so watchdog doesn't fire before first data arrives.
    val lastDataTime = AtomicReference(Long.MAX_VALUE)
    // Track consecutive service-not-found failures to avoid infinite cache-refresh loops
    var serviceNotFoundCount = 0

    fun scheduleReconnect() {
        if (closed.get()) return
        reconnectAttempt++
        if (reconnectAttempt <= Constants.BLE_RAPID_PHASE_ATTEMPTS) {
            // Phase 1: rapid direct reconnects
            val phase = "rapid"
            Timber.d("Scheduling reconnect #$reconnectAttempt ($phase) in ${Constants.BLE_RAPID_PHASE_DELAY_MS}ms")
            handler.postDelayed({
                if (!closed.get()) attemptConnect(autoConnect = false)
            }, Constants.BLE_RAPID_PHASE_DELAY_MS)
        } else {
            // Phase 2: let Android handle it power-efficiently via autoConnect=true.
            // Also schedule a manual fallback in case autoConnect silently fails.
            Timber.d("Scheduling reconnect #$reconnectAttempt (slow/autoConnect) with ${Constants.BLE_SLOW_PHASE_DELAY_MS}ms fallback")
            attemptConnect(autoConnect = true)
        }
    }

    fun attemptConnect(autoConnect: Boolean) {
        if (closed.get()) return
        Timber.d("Connecting GATT to $address (autoConnect=$autoConnect, attempt=$reconnectAttempt)")
        // Close any existing GATT before reconnecting — single close point
        gatt.getAndSet(null)?.close()
        // Clear stale descriptor queue from previous connection
        activeDescriptorQueue.set(null)

        gatt.set(device.connectGatt(context, autoConnect, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w("GATT status=$status (newState=$newState) for $address")
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            // Connected with non-zero status — unreliable connection
                            Timber.w("Connected with error status $status, scheduling retry")
                            trySend(ConnectionEvent.Disconnected)
                            scheduleReconnect()
                            return
                        }
                        Timber.d("GATT connected to $address")
                        reconnectAttempt = 0
                        serviceNotFoundCount = 0
                        lastDataTime.set(System.currentTimeMillis())
                        trySend(ConnectionEvent.Connected)

                        // Refresh GATT cache to force fresh service discovery.
                        // Critical after firmware updates that change the GATT table.
                        val refreshed = refreshGattCache(g)
                        Timber.d("GATT cache refresh: $refreshed")

                        // Delay service discovery slightly to let cache refresh settle.
                        // Guard against disconnect during the delay.
                        handler.postDelayed({
                            if (!closed.get() && gatt.get() === g) {
                                g.discoverServices()
                            } else {
                                Timber.d("Skipping discoverServices — GATT changed or closed during delay")
                            }
                        }, 300)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.w("GATT disconnected from $address (status=$status, attempt=$reconnectAttempt)")
                        trySend(ConnectionEvent.Disconnected)
                        scheduleReconnect()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.e("Service discovery failed: status=$status")
                    g.disconnect()
                    return
                }
                Timber.d("Services discovered, subscribing to notifications")
                subscribeToAllCharacteristics(g)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                lastDataTime.set(System.currentTimeMillis())
                trySend(ConnectionEvent.Data(characteristic.uuid, value))
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                lastDataTime.set(System.currentTimeMillis())
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                trySend(ConnectionEvent.Data(characteristic.uuid, value))
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                val charUuid = descriptor.characteristic.uuid
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Timber.d("Notification subscription active for $charUuid")
                    trySend(ConnectionEvent.Subscribed)
                    subscribeNext(g)
                } else {
                    Timber.e("Descriptor write failed for $charUuid: status=$status")
                    subscribeNext(g)
                }
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    characteristic.uuid == Protocol.BATTERY_LEVEL_CHAR_UUID
                ) {
                    @Suppress("DEPRECATION")
                    val level = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: return
                    Timber.d("Battery level: $level%")
                    trySend(ConnectionEvent.BatteryLevel(level))
                }
            }
        }, BluetoothDevice.TRANSPORT_LE))
    }

    // First connection: direct (not autoConnect) for faster initial connect
    attemptConnect(autoConnect = false)

    // Data watchdog: detects silent connection loss where GATT stays "connected"
    // but notifications silently stop (radio interference, sensor sleep, firmware bug).
    // Only activates after first data packet arrives (lastDataTime starts at MAX_VALUE).
    val watchdogRunnable = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val last = lastDataTime.get()
            if (last != Long.MAX_VALUE && gatt.get() != null) {
                val elapsed = System.currentTimeMillis() - last
                if (elapsed > Constants.BLE_DATA_WATCHDOG_TIMEOUT_MS) {
                    Timber.w("Data watchdog: no notifications for ${elapsed / 1000}s, forcing reconnect")
                    lastDataTime.set(Long.MAX_VALUE) // reset to avoid rapid re-triggers
                    gatt.get()?.disconnect()
                }
            }
            handler.postDelayed(this, Constants.BLE_DATA_WATCHDOG_INTERVAL_MS)
        }
    }
    handler.postDelayed(watchdogRunnable, Constants.BLE_DATA_WATCHDOG_INTERVAL_MS)

    awaitClose {
        Timber.d("Closing GATT connection to $address")
        closed.set(true)
        handler.removeCallbacksAndMessages(null)
        gatt.getAndSet(null)?.close()
    }
}
```

**Key design decisions from steelman review:**
- **Single close point**: `gatt.close()` only happens inside `attemptConnect()` (before creating new GATT) and in `awaitClose`. Never in `onConnectionStateChange`. Prevents double-close.
- **`scheduleReconnect()` extracted**: Centralizes reconnect logic, avoids code duplication between disconnect and error-133 paths.
- **Phase 2 uses `autoConnect=true`**: Android's native BLE stack handles the scanning power-efficiently instead of our manual polling loop. The 60s manual fallback is a safety net only.
- **`lastDataTime` starts at `Long.MAX_VALUE`**: Watchdog won't fire until after the first data packet. Solves the false-trigger during service discovery.
- **Guarded `discoverServices()` delay**: Checks `!closed.get() && gatt.get() === g` before calling, preventing the race condition.
- **`serviceNotFoundCount`**: Tracked for potential future use (e.g., alert user after N consecutive failures), but not yet used to avoid over-engineering.
- **No `g.close()` inside callbacks**: Only `g.disconnect()` is called from callbacks, which properly triggers `STATE_DISCONNECTED` → `scheduleReconnect()` → `attemptConnect()` which does the `close()`.

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/BleManager.kt
git commit -m "feat: bulletproof BLE connection with GATT refresh, two-phase reconnect, and data watchdog"
```

---

### Task 3: Add near-miss device name logging

**Why:** If Tymewear firmware changes the advertised name, the scan will silently fail. Near-miss logging (devices with "tyme" or "vital" in the name that don't match our patterns) gives us immediate diagnostics from logcat without widening the match pattern (which could false-match unrelated devices).

**Files:**
- Modify: `app/src/main/kotlin/com/tymewear/karoo/Protocol.kt`

**Step 1: Add near-miss logging to `isVitalProDevice()`**

Replace the `isVitalProDevice()` method (lines 72-78):

Old:
```kotlin
fun isVitalProDevice(deviceName: String?): Boolean {
    if (deviceName == null) return false
    val lower = deviceName.lowercase().trim()
    if (lower.startsWith(HR_SENSOR_PREFIX)) return false
    return DEVICE_NAME_PATTERNS.any { lower.contains(it) }
        || TYME_STRAP_REGEX.matches(lower)
}
```

New:
```kotlin
fun isVitalProDevice(deviceName: String?): Boolean {
    if (deviceName == null) return false
    val lower = deviceName.lowercase().trim()
    if (lower.startsWith(HR_SENSOR_PREFIX)) return false
    if (DEVICE_NAME_PATTERNS.any { lower.contains(it) }) return true
    if (TYME_STRAP_REGEX.matches(lower)) return true
    // Log near-misses to catch firmware name changes early
    if (lower.contains("tyme") || lower.contains("vital")) {
        Timber.w("Potential VitalPro device not matched by name patterns: '$deviceName'")
    }
    return false
}
```

Note: We do NOT add `"tyme-"` to `DEVICE_NAME_PATTERNS` — the regex already catches `TYME-XXXX` format, and broadening patterns risks false matches. If a new name appears in logs, we add it explicitly.

**Step 2: Commit**

```bash
git add app/src/main/kotlin/com/tymewear/karoo/Protocol.kt
git commit -m "feat: log near-miss device names for firmware change diagnostics"
```

---

### Task 4: Build, verify, and version bump

**Step 1: Run Gradle build**

```bash
cd /c/Users/james.eastwood/Documents/TPV/TPVCM_Github/TymewearKaroo
./gradlew assembleRelease
```

Expected: BUILD SUCCESSFUL

**Step 2: Fix any compilation errors**

Common issues to watch for:
- `AtomicReference` import (should already be imported)
- `LinkedList` import for the local queue in `subscribeToAllCharacteristics`
- Verify `activeDescriptorQueue` is accessible from both `subscribeToAllCharacteristics` and `subscribeNext`

**Step 3: Version bump**

Update `versionCode` and `versionName` in `app/build.gradle.kts`.

**Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 0.4.1 (versionCode 7) — bulletproof BLE"
```

---

## Summary of Changes

| File | Change | Purpose |
|------|--------|---------|
| `Constants.kt` | Two-phase reconnect + watchdog constants | Configurable timing, 45s watchdog timeout |
| `BleManager.kt` | `refreshGattCache()` method | Clear stale GATT cache after firmware updates |
| `BleManager.kt` | `connect()` full rewrite | Two-phase reconnect, error 133 handling, guarded service discovery |
| `BleManager.kt` | Data watchdog (starts after first data) | Detect silent notification loss without false positives |
| `BleManager.kt` | Per-connection descriptor queue | Prevent queue corruption on rapid reconnect |
| `BleManager.kt` | Always-on service discovery logging + disconnect on failure | Diagnose firmware changes, trigger retry |
| `BleManager.kt` | Single close point (in `attemptConnect` only) | Prevent double-close crashes |
| `Protocol.kt` | Near-miss device name logging | Early warning for firmware name changes |

## Steelman fixes incorporated

| Issue | Fix |
|-------|-----|
| Race condition in delayed `discoverServices()` | Guard with `!closed && gatt === g` check |
| Battery drain from infinite manual retry | Phase 2 uses `autoConnect=true` (Android-native) |
| 15s watchdog too aggressive | 45s timeout, only starts after first data packet |
| Double-close crash risk | Single close point in `attemptConnect()` only |
| GATT error 133 not handled | Non-SUCCESS status → schedule reconnect, don't close in callback |
| `descriptorWriteQueue` shared across connections | Per-connection queue via `AtomicReference` |
| Widened name matching could false-match | Log near-misses only, don't widen patterns |
| Inverted `autoConnect` boolean in Phase 2 | Phase 1 = `false` (direct), Phase 2 = `true` (native) |
| Service-not-found infinite loop | GATT refresh + disconnect on failure triggers reconnect with fresh cache |
