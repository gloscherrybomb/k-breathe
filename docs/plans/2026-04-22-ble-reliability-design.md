# BLE reliability design — TymewearKaroo

Date: 2026-04-22
Status: approved, ready for implementation plan

## Problem

Open GitHub issues on `gloscherrybomb/k-breathe`:

- **#4 "Not connecting"** — two distinct reports:
  - `nottechsavvvvy`: connects then drops. Later resolved by leaving sensor_id blank (likely already fixed by `feb6a72`).
  - `pingyijin`: works indoors with 3 paired BT devices; fails outdoors with 8 (HR, power, 4× SRAM AXS, radar, strap). Classic BLE concurrency starvation.
- **#6 "Freeze"** — `thomasbrunner-spec`: live data shows in data fields pre-ride, values freeze the moment the Start button is pressed.

Primary maintainer symptom: initial connect usually fails on a fresh cold start.

## Root cause hypotheses

1. **Initial-connect failure (issue #4, maintainer):** current code calls `getRemoteDevice(mac).connectGatt(autoConnect=false)` immediately. If the device is not actively advertising at that instant, Android returns status 133 and the 10× rapid retries all fail the same way against the same stale handle.
2. **Radio contention (issue #4, pingyijin):** on a crowded BT stack, the default `BALANCED` connection interval (~30–50 ms) is scheduled around higher-priority peripherals; the strap's notifications get starved. No `requestConnectionPriority(HIGH)` call in current code.
3. **Freeze on Start (issue #6):** `TymewearExtension` is a regular bound `Service`. When the user presses Start, the sensor-list UI unbinds. With no active clients, Android is free to reclaim the process — BLE silently drops, but `TymewearData` retains the last value on screen. That matches the "freeze" symptom.

## Ecosystem validation

Surveyed `hammerheadnav/karoo-ext` (official SDK sample), `timklge/karoo-powerbar`, `timklge/karoo-headwind`, `timklge/karoo-reminder`, `timklge/awesome-karoo`.

- Official sample uses Nordic's `no.nordicsemi.kotlin.ble` library, which internally does scan-then-connect with `Direct` connection options (retry=10, retryDelay=12s, timeout=120s). Validates scan-assisted pattern.
- `karoo-powerbar` uses a **separate** `ForegroundService` class (not promoted from the main `KarooExtension`). Notification uses `IMPORTANCE_MIN`, `CATEGORY_SERVICE`, `setOngoing(true)`. Manifest declares only `FOREGROUND_SERVICE` — no `foregroundServiceType`, no `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- `RequestBluetooth` is dispatched exactly once in the official sample (inside the `karooSystem.connect` callback). No precedent for re-dispatching on ride state transitions.

## Scope

In scope:

1. **Scan-assisted initial connect** — replace the direct `connectGatt(stale_mac, autoConnect=false)` with a targeted MAC scan, then connect to the fresh `ScanResult.device`. On scan timeout (~8s), escalate to `connectGatt(autoConnect=true)`. Only on the first connect attempt of a flow session — mid-session reconnects continue to use the existing rapid/slow logic (avoids Android's 5-scans-per-30s throttling).
2. **`requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`** inside `onServicesDiscovered`, immediately before `subscribeToAllCharacteristics`.
3. **Decoded status-code logging** — new `BleStatus` helper that maps common GATT and scan error codes to human-readable names; used in `onConnectionStateChange`, `onServicesDiscovered`, `onDescriptorWrite`, `onScanFailed`. Also log time-to-first-packet after connect.
4. **Separate foreground service** — new `BleForegroundService` class that calls `startForeground(id, notification)` with an `IMPORTANCE_MIN` channel and `CATEGORY_SERVICE` notification. Started from `TymewearExtension` on first active BLE connection, stopped on last disconnect. Manifest adds only `FOREGROUND_SERVICE`.

Not in scope:

- **Migration to Nordic's BLE library.** The official SDK sample uses this and it is a better long-term foundation than raw `connectGatt` — handles scan/retry/MTU/bond/priority internally. Worth a separate project. For this round we stick with the existing raw-GATT code and add the targeted fixes.
- `requestMtu()` — breath packets are ≤17 bytes; default MTU fits. YAGNI.
- Bond-state handling — no evidence strap uses pairing. Add only if logs show status 137.
- Rewriting the two-phase reconnect (rapid/slow). Current logic is reasonable for mid-session drops.
- Re-dispatching `RequestBluetooth` on `RideState.Recording`. No evidence needed; official sample dispatches once.
- Changes to parser, zones, UI, or datatype providers.

## Success criteria

- Initial connect succeeds on the first attempt from a cold Karoo state, even with 7+ paired peripherals. Target: <10s from tap to first breath packet.
- Live breathing data continues flowing across the Start-button press in issue #6.
- Failed connections produce a logcat line that identifies which BLE layer failed (scan / connect / services / descriptor).

## Architecture

### `BleManager.connect(address: String): Flow<ConnectionEvent>`

First-attempt path changes from direct connect to scan-assisted:

```
connect(address) entered
  └─> scanThenConnect():
        startScan(filter MAC==address, LOW_LATENCY)
        arm 8s timeout on main handler
        ├─ on ScanResult hit   → stopScan, attemptConnect(result.device, autoConnect=false)
        ├─ on 8s timeout        → stopScan, attemptConnect(remoteDevice, autoConnect=true)
        └─ on onScanFailed      → attemptConnect(remoteDevice, autoConnect=true)
```

Mid-session disconnect → existing `scheduleReconnect()` (unchanged). `onServicesDiscovered` gains a single `requestConnectionPriority(HIGH)` call before subscription.

New state on `BleManager`:

- `activeScanCallback: AtomicReference<ScanCallback?>` — tracked so `awaitClose` can call `stopScan` on it.

`attemptConnect` signature changes: `(device: BluetoothDevice, autoConnect: Boolean)` instead of `(autoConnect: Boolean)`, so the targeted-scan result's fresh `BluetoothDevice` handle can be passed through. Reconnect paths pass the original `device` handle.

### `BleStatus` (new file)

Pure helper object, no state. Two `decode` functions: one for GATT status codes (0=SUCCESS, 8=CONN_TIMEOUT, 19=REMOTE_DISCONNECT, 22=LOCAL_DISCONNECT, 34=LMP_TIMEOUT, 62=CONN_FAIL, 133=GATT_ERROR, 137=AUTH_FAIL, 143=INSUF_ENCRYPT, 257=TOO_MANY_CONNECTIONS) and one for `ScanCallback.SCAN_FAILED_*` codes.

### `BleForegroundService` (new file)

Plain `Service`. `onStartCommand` creates the notification channel (if not already present) and calls `startForeground(1001, notification)`. `onDestroy` calls `stopForeground(STOP_FOREGROUND_REMOVE)`. Channel: `"tymewear_ble"`, `IMPORTANCE_MIN`. Notification: app icon, `"VitalPro connected"`, `CATEGORY_SERVICE`, `setOngoing(true)`, no sound, no vibration.

Ref-counted from `TymewearExtension`:

- `AtomicInteger` tracking active `connectDevice()` emitters.
- Increment on `connectDevice`, decrement on emitter `setCancellable`.
- `startForegroundService(Intent(..., BleForegroundService::class.java))` on 0→1 transition.
- `stopService(...)` on 1→0 transition.

### Manifest changes

Add:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<service android:name=".BleForegroundService" android:exported="false" />
```

No `foregroundServiceType` attribute. No `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Matches ecosystem precedent.

## Rollout

Single release (0.4.2), five atomic commits for bisectability:

1. Add `BleStatus.kt` helper.
2. Wire decoded status codes + time-to-first-packet into `BleManager` logs (no behavior change).
3. Add `requestConnectionPriority(HIGH)` in `onServicesDiscovered`.
4. Implement scan-assisted initial connect in `BleManager`.
5. Add `BleForegroundService` + manifest + ref-count in `TymewearExtension`.

## Testing (on-device)

- Cold connect with many peripherals paired → <10s to first data.
- Ride test: connect strap, press Start, confirm BR field keeps updating past the Start transition.
- Single-device indoor scenario → no regression from the 0–8s scan delay.

## Reviewers to notify when 0.4.2 ships

- `pingyijin` (#4) — contention scenario
- `thomasbrunner-spec` (#6) — freeze on Start
- `nottechsavvvvy` (#4) — confirm earlier empty-sensor_id fix plus new changes

## Risks

| Risk | Mitigation |
|---|---|
| Scan adds 0–8s to initial connect | Only first attempt; scoped; matches official SDK behavior |
| Foreground notification visible on Karoo screen | `IMPORTANCE_MIN` + `CATEGORY_SERVICE` matches powerbar precedent, likely hidden by Karoo UI |
| `requestConnectionPriority(HIGH)` increases strap battery ~10–20% | Only during active connection; acceptable for ride durations |
| Targeted scan conflicts with Karoo-host BLE scan | `BluetoothLeScanner` supports concurrent scans per app; Karoo's scan is a separate process |
| Manifest change forces reinstall, not upgrade | Already the norm — app is sideloaded via adb |
