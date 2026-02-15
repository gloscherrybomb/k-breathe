# K-Breathe

A Karoo extension that connects to the [Tymewear VitalPro](https://www.tymewear.com/) breathing sensor, displaying live breathing metrics and recording them to FIT files.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jeastwood)

## Screenshots

<p align="center">
  <img src="docs/screenshots/az_recorder_20260215_104848.jpg" width="300" alt="Data fields: VE, BR, MI Battery, TV, MI%">
  <img src="docs/screenshots/az_recorder_20260215_104858.jpg" width="300" alt="VE Graph with zone background">
</p>

## Data Fields

| Field | Description |
|-------|-------------|
| **VE** | Minute ventilation (L/min) with zone-colored background. Tap to cycle smoothing: Instant / 15s / 30s |
| **VE Graph** | Rolling VE graph with ventilation zone background bands |
| **BR** | Breathing rate (breaths/min) |
| **TV** | Tidal volume per breath (L) |
| **MI %** | Mobilization Index — ratio of breathing reserve to heart rate reserve |
| **MI Batt** | Mobilization Index as a battery gauge visualization |
| **VE Zones** | Time-in-zone bar chart (5 zones, matching Karoo's built-in HR/Power zone style) |

## Ventilation Zones

Five zones based on ventilation thresholds:

| Zone | Name | Default VE (L/min) | Color |
|------|------|---------------------|-------|
| Z1 | Recovery | < 69 | Blue-grey |
| Z2 | Endurance | 69 - 83 | Blue |
| Z3 | Tempo (VT1) | 83 - 111 | Green |
| Z4 | Threshold (VT2) | 111 - 180 | Yellow |
| Z5 | VO2max | > 180 | Red |

Zone thresholds are configurable via SharedPreferences.

## FIT Recording

All breathing data is recorded to FIT files as developer fields during rides:

- `tyme_breath_rate` (brpm)
- `tyme_tidal_volume` (vol/br)
- `tyme_minute_volume` (vol/min)
- `tyme_inhale_exhale_ratio` (sec/sec)
- `tyme_ve_zone`
- `tyme_mobilization_index` (%)
- `tyme_percent_brr` (%)

Session summary includes time and percentage spent in each VE zone. FIT files are compatible with the [Tymewear Dashboard](https://dashboard.tymewear.com/).

## Installation

### Prerequisites

- Karoo 3 cycling computer
- Tymewear VitalPro breathing sensor

### Option 1: Companion App (recommended)

1. On your phone, open the [latest release](https://github.com/gloscherrybomb/k-breathe/releases) in your browser
2. Long-press the `app-release.apk` link and share it with the **Hammerhead Companion** app
3. The Karoo will show an install prompt — tap **Install**
4. Future updates can be installed from the app details page on the Karoo

### Option 2: ADB

```bash
adb install -r app-release.apk
adb shell pm grant com.tymewear.karoo android.permission.BLUETOOTH_SCAN
adb shell pm grant com.tymewear.karoo android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.tymewear.karoo android.permission.ACCESS_FINE_LOCATION
```

### Pair the Sensor

1. Go to **Sensors** on the Karoo
2. The VitalPro strap should appear as `TYME-XXXX`
3. Pair the sensor
4. Add K-Breathe data fields to your ride pages

## Building from Source

Requires Java 21 and Android SDK.

```bash
./gradlew assembleRelease
```

The APK is output to `app/build/outputs/apk/release/app-release.apk`.

### Dependencies

- [karoo-ext SDK](https://github.com/hammerheadnav/karoo-ext) v1.1.8
- Kotlin 2.0.0
- Android Gradle Plugin 8.2.2

## License

MIT
