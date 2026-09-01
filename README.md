# K-Breathe

A Karoo extension that connects to the [Tymewear VitalPro](https://www.tymewear.com/) breathing sensor, displaying live breathing metrics and recording them to FIT files.

I really like coffee, so if this enhances your life, please buy me one :)

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
| **MI %** | Mobilization Index — ratio of breathing reserve to heart rate reserve. 100% = fully mobilized (limit) |
| **MI Batt** | Mobilization Index as a battery gauge — shows remaining reserve, 0% = empty |
| **VE Zones** | Time-in-zone bar chart (5 zones, matching Karoo's built-in HR/Power zone style) |
| **VE State** | Today's ventilatory efficiency vs your own baseline (Beta). Negative = less breathing for the same power |
| **VT1 Today** | The power at which you'd cross VT1 today, given how your breathing compares with baseline (Beta) |
| **BR Drift** | Breathing-rate drift vs the effort's early reference (Beta). Requires a power meter paired to the Karoo — a live view of the "11–15% and you're done" rule |

## Ventilation Zones

Five zones, bounded by your own ventilatory thresholds:

| Zone | Name | Range | Color |
|------|------|-------|-------|
| Z1 | Endurance | below VT1 | Teal |
| Z2 | VT1 | VT1 – VT2 | Blue |
| Z3 | VT2 | VT2 – Top Z4 | Amber |
| Z4 | Top Z4 | Top Z4 – VO2max | Orange |
| Z5 | VO2Max | above VO2max | Red |

**Set your own thresholds before relying on the zones.** Ventilation is highly
individual — the VE at which you cross VT1 depends on your physiology, not on a
number that suits everyone. The app ships with placeholder values purely so the
fields render something on first run; they are not a recommendation.

Get your values from a [Tymewear threshold test](https://www.tymewear.com/blogs/startup-guides/threshold-test),
then enter them under **Ventilation Zone Thresholds** in the K-Breathe app
(VT1, VT2, Top Z4, VO2max, in L/min). Thresholds drift with fitness, so retest
periodically — Tymewear suggest every 6–8 weeks.

## Ventilatory State (Beta)

Ventilatory thresholds move day to day with fatigue, heat, sleep and freshness. A fixed
zone table asserts one number every day and is wrong on most of them.

With a power meter paired, K-Breathe learns what ventilation you normally produce at each
power and then shows how today differs from it — as a percentage, and as the power at
which you'd cross VT1 today. It needs no threshold test: the baseline builds itself from
steady riding and tracks your fitness as it changes.

Enable it under **Ventilatory State (Beta)** in the app. Until enough steady riding has
accumulated the fields show "calibrating" rather than a number. A **Reset baseline**
button in the same section discards what has been learned so far and starts calibration
over — use it if a baseline was built under unrepresentative conditions (illness, a bike
fit change, a long break).

**Requires a power meter.** Without power there is no power-to-ventilation relationship
to measure, and the fields report unavailable.

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

- Karoo 2 or Karoo 3 cycling computer
- Tymewear VitalPro breathing sensor

### Karoo 3 installation

#### Option 1: Companion App (recommended)

1. On your phone, open the [latest release](https://github.com/gloscherrybomb/k-breathe/releases) in your browser
2. Long-press the `k-breathe.apk` link and share it with the **Hammerhead Companion** app
3. The Karoo will show an install prompt — tap **Install**
4. Open the K-Breathe app once to grant Bluetooth permissions
5. Future updates can be installed from the app details page on the Karoo

#### Option 2: ADB

```bash
adb install -r k-breathe.apk
adb shell pm grant com.tymewear.karoo android.permission.BLUETOOTH_SCAN
adb shell pm grant com.tymewear.karoo android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.tymewear.karoo android.permission.ACCESS_FINE_LOCATION
```

#### Pair the Sensor

1. On the Karoo, go to **Profiles** > **Data Pages** > **Add Data Field**
2. Select any K-Breathe field (e.g. VE, BR) from the **Extensions** list
3. The Karoo will prompt you to pair the sensor — the VitalPro strap appears as `TYME-XXXX`
4. **Important:** Add the sensor from the Extensions list, not the Bluetooth settings

### Karoo 2 installation

The Karoo Companion app does not support the Karoo 2, and K-Breathe does not yet appear in
the Karoo 2's **Extensions** store, so the app needs to be sideloaded via ADB.

For easy-to-follow instructions for both Mac and Windows, see DC Rainmaker's article
[How to Sideload Android Apps On Your Hammerhead Karoo 1/2/3](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html).

#### ADB installation

Download the latest `k-breathe.apk` from the [releases page](https://github.com/gloscherrybomb/k-breathe/releases).

```bash
adb install -r k-breathe.apk
adb shell pm grant com.tymewear.karoo android.permission.ACCESS_FINE_LOCATION
```

The Karoo 2 runs an older Android release in which the `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`
permissions do not exist, so they cannot (and need not) be granted. K-Breathe runs fine without them.

#### Pair the Sensor

1. On the Karoo, go to **Sensors**.
2. Tap **+**, then across the top tap **Extensions** and pick Tymewear.

## Building from Source

Requires Java 21 and Android SDK.

```bash
./gradlew assembleRelease
```

The APK is output to `app/build/outputs/apk/release/k-breathe.apk`.

### Dependencies

- [karoo-ext SDK](https://github.com/hammerheadnav/karoo-ext) v1.1.8
- Kotlin 2.0.0
- Android Gradle Plugin 8.2.2

## License

MIT
