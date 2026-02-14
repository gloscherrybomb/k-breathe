# VitalPro BLE Protocol Specification

**Status**: Phase 1 — UUIDs confirmed, data encoding needs validation

## Overview

The Tymewear VitalPro is a chest-worn breathing sensor that communicates breathing metrics (ventilation, breathing rate, tidal volume) over BLE. The HR sensor communicates separately via ANT+.

## Source Material

Protocol details extracted from the Tymewear Android APK (`com.app.tymewear.demo` v1.3.0) via:
1. JADX decompilation of the Java layer (Flutter platform channel bridge)
2. Binary string extraction from compiled Dart code (`libapp.so`, 12.8MB ARM64)

**Key finding**: The Tymewear app is a Flutter app. All BLE logic (UUIDs, data parsing, commands) is in AOT-compiled Dart code (`libapp.so`), not in the Java layer. The Java layer only contains the `flutter_blue_plus` plugin acting as a platform channel bridge.

## BLE Device Advertisement

| Field | Value | Source |
|-------|-------|--------|
| Device Name | Contains `"vitalpro"` or `"tymewear"` (case-insensitive) | libapp.so strings |
| Advertised Service | `40B50000-30B5-11E5-A151-FEFF819CDC90` | libapp.so strings |

### Known Device Name Patterns

From libapp.so binary analysis:
- `VitalPro R` — Regular size strap
- `VitalPro BR` — (variant)
- `VitalPro S` — Small size strap
- `vitalpro-hw8-small` — Hardware v8, small size
- `vitalpro-hw8-regular` — Hardware v8, regular size
- `tymewear-strap-l-staging-firmware-hw7` — Large strap, HW7, staging
- `tymewear-strap-s-ext-staging-firmware` — Small strap, extended, staging
- `tymewear-strap-staging-firmware` — Generic staging
- `tymewear-strap-ext-production-firmware` — Extended production

## GATT Service Tree

### Primary Custom Service (VitalPro Strap)

```
Service: 40B50000-30B5-11E5-A151-FEFF819CDC90
  ├── Char: 40B50001-30B5-11E5-A151-FEFF819CDC90  (Breathing Data — Notify)
  │         Context: primary data stream, near "_transformBLEResponseToStrapData"
  │
  ├── Char: 40B50004-30B5-11E5-A151-FEFF819CDC90  (Commands — Write)
  │         Context: near "run_workout" in Dart code
  │         Used for: start/stop recording, strap mode, strap size commands
  │
  └── Char: 40B50007-30B5-11E5-A151-FEFF819CDC90  (Sensor ID — Read)
            Context: near "get:vitalProId" in Dart code
            Used to: read the 4-digit sensor ID visible on the device
```

### Secondary Custom Service

```
Service: 4610c40a-c4ff-410d-b5db-abdd19f704a7
  └── (Purpose unknown — possibly firmware update related)
```

### Standard Bluetooth SIG Services

```
Service: 00001801-0000-1000-8000-00805f9b34fb  (Generic Attribute)
Service: 0000180f-0000-1000-8000-00805f9b34fb  (Battery Service)
  └── Char: 00002a19-0000-1000-8000-00805f9b34fb  (Battery Level)
Service: 00001826-0000-1000-8000-00805f9b34fb  (Fitness Machine)
Descriptor: 00002902-0000-1000-8000-00805f9b34fb  (CCCD — Client Characteristic Config)
Char: 00002a24-0000-1000-8000-00805f9b34fb  (Model Number String)
Char: 00002a26-0000-1000-8000-00805f9b34fb  (Firmware Revision String)
Char: 00002a29-0000-1000-8000-00805f9b34fb  (Manufacturer Name String)
Service: 0000fe59-0000-1000-8000-00805f9b34fb  (Google LLC — possibly Fast Pair)
```

## Connection Flow

1. Scan for devices advertising service UUID `40B50000-30B5-11E5-A151-FEFF819CDC90`
2. Filter by device name containing `"vitalpro"` or `"tymewear"` (case-insensitive)
3. Connect GATT with `TRANSPORT_LE`
4. Discover services
5. Enable notifications on characteristics `40B50001`, `40B50004`, `40B50007`
   - Write `ENABLE_NOTIFICATION_VALUE` to CCCD descriptor (`00002902`)
6. Read Battery Service (`0x180F`) for power level
7. Receive breathing data notifications on `40B50001`
8. Optionally send commands via `40B50004`

## Data Encoding

**STATUS: NEEDS VALIDATION** — The exact byte layout of notifications from `40B50001` is unknown. The Dart function `_transformBLEResponseToStrapData@1700344857` handles parsing, but since it's AOT-compiled, the byte format must be validated via nRF Connect or HCI snoop log.

### Known Data Fields (from Dart symbol names)

- `breathing_rate` — breaths per minute
- `tidal_vol` — tidal volume per breath
- `ventilation` — minute ventilation (VE, L/min)
- `breath` — individual breath detection
- `breathTimestamps` — breath event timestamps
- `imuProcessedList` — processed IMU data
- `strap_imu_period` — IMU sampling period

### Suspected Encoding (placeholder, needs validation)

Likely one of:
1. **Little-endian float32 at fixed offsets** (most probable for real-time data)
2. **Scaled uint16** (value / 100.0 for decimals)
3. **JSON string** (unlikely for real-time, but `strapDataJson` symbol exists)

Current placeholder assumption (17 bytes minimum):
```
Offset  Type      Field
[0..3]  float32   breath rate (brpm)
[4..7]  float32   tidal volume (vol/br)
[8..11] float32   minute volume (L/min)
[12..15] float32  IE ratio (sec/sec)
[16]    uint8     VE zone (0-3)
```

### Validation Steps

1. **nRF Connect**: Connect to VitalPro, enable notify on `40B50001`, observe raw hex data
2. **HCI Snoop Log**: Enable in Android Developer Options, run Tymewear app during activity, capture `btsnoop_hci.log`, open in Wireshark, filter `btatt.opcode == 0x1b`
3. **Correlate**: Match raw bytes against displayed values in the Tymewear app

## Key Dart Functions (from libapp.so)

| Function | Library ID | Purpose |
|----------|-----------|---------|
| `_transformBLEResponseToStrapData` | 1700344857 | **Data parsing** — converts raw BLE bytes to breathing data |
| `_manageSensorServicesAndCharacteristics` | 1566119163 | GATT service/characteristic setup |
| `_handleNotification` | 1693167057 | BLE notification handler |
| `_isValidStrapDevice` | 1566119163 | Device name validation |
| `_getStrapServiceUUIDs` | 1566119163 | Returns strap service UUIDs |
| `_hasStrapServiceUUIDs` | 1566119163 | Checks for strap services |
| `_hasAnyServiceUUID` | 1566119163 | General UUID checking |
| `_getExternalSensorServiceUUIDs` | 1566119163 | External sensor service UUIDs |
| `sensorTypesFromServiceUUIDs` | — | Maps UUIDs to sensor types |

---

## APK Decompilation Notes

### Architecture

The app uses Flutter with the `flutter_blue_plus` plugin for BLE access:

```
[Dart/Flutter Layer] ← BLE logic, data parsing, UI
       ↓ Platform Channel
[Java Layer: flutter_blue_plus] ← Raw BLE API bridge (h3/l.java)
       ↓
[Android BLE APIs]
```

### Java Layer (JADX searchable)

The Java layer contains:
- `flutter_blue_plus` plugin at `h3/l.java` — platform channel bridge
- `MainActivity.java` — Flutter activity with method channels for foreground service, settings
- `AWSFirmwareDownload` / `UploadService` — firmware update and data upload services
- Method channels: `com.tyme.push`, `com.tyme.upload`, `com.tyme.s3`, `foregroundService`, `openSettings`

### Dart Layer (compiled in libapp.so)

All BLE business logic is in the compiled Dart binary. Searchable via binary string extraction:
- BLE UUIDs as string constants
- Function names preserved in AOT symbols
- Data field names (`breathing_rate`, `tidal_vol`, etc.)
- API endpoints: `api.tymewear.com`, `api-dev.tymewear.com`

---

## FIT File Developer Fields (Confirmed)

Extracted from a real Tymewear FIT file (`normal_activity_2026-02-14.fit`).

### Developer Application ID

```
0101020358030d69223759ffff7962ff
```

### Record Fields (Per-Second, Index 0)

All fields use **float32** (base type 136) in **record messages** (native_mesg_num=20).

| Field # | Name | Units | Description |
|---------|------|-------|-------------|
| 0 | `tyme_breath_rate` | brpm | Breathing rate |
| 1 | `tyme_tidal_volume` | vol/br | Tidal volume per breath |
| 2 | `tyme_minute_volume` | vol/min | Minute ventilation (VE) |
| 3 | `tyme_inhale_exhale_ratio` | sec/sec | Inhale/exhale ratio |
| 4 | `tyme_cadence` | spm | Running cadence |
| 5 | `tyme_ground_contact` | sec | Ground contact time |
| 6 | `tyme_air_time` | sec | Flight time |
| 7 | `tyme_bike_cadence` | rot/min | Cycling cadence |
| 8 | `tyme_player_load` | N/s/kg | Player load |
| 9 | `tyme_protocol` | mph / watts | Protocol value |
| 10 | `tyme_gps_speed` | mph | GPS speed |
| 11 | `tyme_gps_pace` | min/mi | GPS pace |
| 12 | `tyme_equivalent_0_grade_speed` | mph | Grade-adjusted speed |
| 13 | `tyme_elevation` | m | Elevation |
| 14 | `tyme_distance` | m | Distance |
| 15 | `tyme_heart_rate` | bpm | Heart rate |
| 16 | `tyme_power` | watts | Power |
| 17 | `tyme_ve_zone` | — | VE zone (0-3) |

### Threshold Fields (Session, Index 1)

String type (base type 7) in **session messages** (native_mesg_num=3).

| Field # | Name | Description |
|---------|------|-------------|
| 18 | `tyme_running_threshold_vt1` | Running VT1 threshold |
| 19 | `tyme_running_threshold_balance_point` | Running balance point |
| 20 | `tyme_running_threshold_vt1` | (duplicate of 18?) |
| 21 | `tyme_running_threshold_vt2` | Running VT2 threshold |
| 22 | `tyme_running_threshold_vo2max` | Running VO2max threshold |
| 23 | `tyme_bike_threshold_vt1` | Bike VT1 threshold |
| 24 | `tyme_bike_threshold_balance_point` | Bike balance point |
| 25 | `tyme_bike_threshold_vt2` | Bike VT2 threshold |
| 26 | `tyme_bike_threshold_vo2max` | Bike VO2max threshold |

### Session Summary Fields (Session, Index 2)

Float32 (base type 136) in **session messages** (native_mesg_num=18).

| Field # | Name | Units | Description |
|---------|------|-------|-------------|
| 27 | `tyme_ve_zone1_time` | min | Time in Zone 1 |
| 28 | `tyme_ve_zone1_percentage` | % | % in Zone 1 |
| 29 | `tyme_ve_zone2_time` | min | Time in Zone 2 |
| 30 | `tyme_ve_zone2_percentage` | % | % in Zone 2 |
| 31 | `tyme_ve_zone3_time` | min | Time in Zone 3 |
| 32 | `tyme_ve_zone3_percentage` | % | % in Zone 3 |

### Sample Data

```
Record:
  tyme_breath_rate: 22.4 brpm
  tyme_tidal_volume: 1.8 vol/br
  tyme_minute_volume: 40.3 vol/min
  tyme_heart_rate: 142.0 bpm
  tyme_ve_zone: 2.0

Session Summary:
  tyme_ve_zone1_time: 63.8 min (50%)
  tyme_ve_zone2_time: 63.2 min (48%)
  tyme_ve_zone3_time: 3.0 min (2%)
```
