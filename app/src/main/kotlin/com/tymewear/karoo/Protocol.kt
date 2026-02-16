package com.tymewear.karoo

import io.hammerhead.karooext.models.DeveloperField
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import timber.log.Timber

/**
 * VitalPro BLE protocol constants and data parsing.
 *
 * UUIDs extracted from Tymewear APK v1.3.0 (com.app.tymewear.demo) via
 * JADX decompilation + libapp.so string analysis.
 *
 * Data encoding format still needs validation via nRF Connect or HCI snoop log.
 */
object Protocol {

    // -------------------------------------------------------------------------
    // BLE Service & Characteristic UUIDs (extracted from libapp.so)
    // -------------------------------------------------------------------------

    // VitalPro breathing strap custom service
    val VITALPRO_SERVICE_UUID: UUID =
        UUID.fromString("40B50000-30B5-11E5-A151-FEFF819CDC90")

    // Breathing data notification characteristic
    // Context: primary data stream from strap
    val BREATHING_DATA_CHAR_UUID: UUID =
        UUID.fromString("40B50001-30B5-11E5-A151-FEFF819CDC90")

    // Data stream characteristic — despite being labeled "command" in the APK,
    // this is the PRIMARY data channel. Sends three packet types:
    //   0x01 = per-breath summary, 0x02 = IMU data, 0x06 = ADC peaks.
    val COMMAND_CHAR_UUID: UUID =
        UUID.fromString("40B50004-30B5-11E5-A151-FEFF819CDC90")

    // Sensor identity characteristic (near "get:vitalProId" in Dart code)
    // Used to read the 4-digit sensor ID visible on the device
    val SENSOR_ID_CHAR_UUID: UUID =
        UUID.fromString("40B50007-30B5-11E5-A151-FEFF819CDC90")

    // Secondary service (purpose unclear — possibly firmware update related)
    val SECONDARY_SERVICE_UUID: UUID =
        UUID.fromString("4610c40a-c4ff-410d-b5db-abdd19f704a7")

    // Standard Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Standard Battery Service
    val BATTERY_SERVICE_UUID: UUID =
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR_UUID: UUID =
        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // -------------------------------------------------------------------------
    // Device identification (from Dart binary strings)
    // -------------------------------------------------------------------------

    // Device names observed: "VitalPro R", "VitalPro BR", "VitalPro S",
    // "vitalpro-hw8-small", "vitalpro-hw8-regular", "TYME-XXXX"
    private val DEVICE_NAME_PATTERNS = listOf("vitalpro", "tymewear")
    private const val HR_SENSOR_PREFIX = "tymehr"
    private val TYME_STRAP_REGEX = Regex("^tyme-[0-9a-f]{4}$")

    /**
     * Check if a BLE device name matches the VitalPro breathing sensor.
     * Matches breathing straps (VitalPro, Tymewear, TYME-XXXX) but
     * excludes HR sensors (TymeHR XXXXXXX).
     */
    fun isVitalProDevice(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val lower = deviceName.lowercase().trim()
        if (lower.startsWith(HR_SENSOR_PREFIX)) return false
        return DEVICE_NAME_PATTERNS.any { lower.contains(it) }
            || TYME_STRAP_REGEX.matches(lower)
    }

    /**
     * Check if a device name matches a specific sensor ID (4-digit code).
     */
    fun matchesSensorId(deviceName: String?, sensorId: String): Boolean {
        if (deviceName == null || sensorId.isBlank()) return false
        return deviceName.contains(sensorId, ignoreCase = true)
    }

    // -------------------------------------------------------------------------
    // Data parsing — validated against live BLE capture from TYME-XXXX strap
    // -------------------------------------------------------------------------

    // ALL breathing data arrives on characteristic 40B50004 (not 40B50001).
    // Three packet types identified by the first byte:
    //   0x01 (17 bytes): Per-breath summary — arrives every 2-4 seconds
    //   0x02 (13 bytes): IMU/accelerometer data — arrives every ~1 second
    //   0x06 (17 bytes): Raw ADC peak data — paired with type 0x01
    //
    // Type 0x06 contains two ADC readings (peaks) whose difference equals
    // the tidal volume field (C) in the paired type 0x01 packet. Confirmed
    // by L - I = C for every captured sample.

    /** Packet type identifiers */
    const val PKT_BREATH = 0x01
    const val PKT_IMU = 0x02
    const val PKT_ADC_PEAKS = 0x06

    /**
     * Parsed breathing data from a type 0x01 BLE notification.
     */
    data class BreathingData(
        val breathRate: Double,      // breaths per minute (from inter-packet timestamp delta)
        val tidalVolume: Double,     // tidal volume (tv_raw × calibration factor)
        val minuteVolume: Double,    // minute ventilation VE = BR × TV
        val ieRatio: Double,         // inhale/exhale ratio (dimensionless)
        val veZone: Int,             // ventilation zone (0=none, 1-5)
        val inhaleDurationCs: Int,   // inhale duration in raw packet units
        val exhaleDurationCs: Int,   // exhale duration in raw packet units
        val tvRaw: Int,              // raw tidal volume (ADC delta)
        val fieldE: Int,             // unknown field E (for debugging/calibration)
        val timestamp40ms: Long,     // packet timestamp in 40ms ticks
    )

    /**
     * TV calibration factor: converts raw ADC delta to approximate volume units.
     * Validated by cross-referencing Karoo FIT output against Tymewear FIT files
     * (9886 records across two rides): VE = BR × tv_raw × ~0.01, consistently.
     * tv_raw values are confirmed identical between apps (ratio 1.002).
     */
    private const val TV_CALIBRATION = 0.01

    /** Seconds per timestamp tick (packet timestamp field). */
    private const val TICK_SECONDS = 0.040

    /** Minimum plausible BR — below this the timestamp delta likely spans a dropped packet. */
    private const val MIN_BREATH_RATE = 4.0

    /** Previous packet timestamp for inter-packet BR calculation. */
    private var prevTimestamp40ms: Long = -1

    /**
     * Reset parser state (e.g. on disconnect / new session).
     */
    fun resetState() {
        prevTimestamp40ms = -1
    }

    /**
     * Parse a notification from characteristic 40B50004 into breathing data.
     * Only type 0x01 packets produce BreathingData; other types return null.
     *
     * BR is computed from the timestamp delta between consecutive 0x01 packets,
     * which captures the full breath period including inter-breath pauses.
     * This matches the Tymewear app's BR values (validated via FIT comparison).
     * Falls back to inhale+exhale duration for the first packet only.
     *
     * Type 0x01 packet layout (17 bytes, uint16 LE):
     *   [0]      type = 0x01
     *   [1..4]   timestamp (uint32 LE, 40ms ticks)
     *   [5..6]   A = inhale duration (raw units)
     *   [7..8]   B = exhale duration (raw units)
     *   [9..10]  C = tidal volume (raw ADC delta)
     *   [11..12] D = C repeated
     *   [13..14] E = unknown metric
     *   [15..16] F = E repeated
     */
    fun parseNotification(bytes: ByteArray): BreathingData? {
        if (bytes.size < 2) return null
        val type = bytes[0].toInt() and 0xFF

        if (type != PKT_BREATH || bytes.size < 17) return null

        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val timestamp = buf.getInt(1).toLong() and 0xFFFFFFFFL  // uint32
            val a = buf.getShort(5).toInt() and 0xFFFF  // inhale duration
            val b = buf.getShort(7).toInt() and 0xFFFF  // exhale duration
            val c = buf.getShort(9).toInt() and 0xFFFF  // tv_raw
            val e = buf.getShort(13).toInt() and 0xFFFF // unknown

            if (a == 0 && b == 0) return null

            val ieRatio = if (b > 0) a.toDouble() / b.toDouble() else 0.0

            // Compute BR from inter-packet timestamp delta (full breath period).
            // Falls back to inhale+exhale sum for the first packet in a session.
            val prev = prevTimestamp40ms
            prevTimestamp40ms = timestamp

            val breathRate: Double
            if (prev >= 0 && timestamp > prev) {
                val deltaTicks = timestamp - prev
                val deltaSeconds = deltaTicks * TICK_SECONDS
                val tsBr = 60.0 / deltaSeconds
                if (tsBr >= MIN_BREATH_RATE) {
                    breathRate = tsBr
                } else {
                    // Delta too large — likely a dropped packet; use inhale+exhale fallback
                    val totalRaw = a + b
                    breathRate = if (totalRaw > 0) 6000.0 / totalRaw else return null
                    Timber.d("Timestamp BR %.1f too low (delta=%d ticks), using fallback %.1f",
                        tsBr, deltaTicks, breathRate)
                }
            } else {
                // First packet — use inhale+exhale as initial estimate
                val totalRaw = a + b
                breathRate = if (totalRaw > 0) 6000.0 / totalRaw else return null
            }

            val tvLiters = c * TV_CALIBRATION
            val minuteVolume = breathRate * tvLiters

            // Range validation
            if (breathRate > Constants.MAX_BREATHING_RATE ||
                minuteVolume > Constants.MAX_MINUTE_VENTILATION
            ) {
                Timber.w(
                    "Out of range: BR=%.1f, TV=%.3f, VE=%.1f L/min",
                    breathRate, tvLiters, minuteVolume,
                )
                return null
            }

            BreathingData(
                breathRate = breathRate,
                tidalVolume = tvLiters,
                minuteVolume = minuteVolume,
                ieRatio = ieRatio,
                veZone = 0,  // zone computed later with user thresholds
                inhaleDurationCs = a,
                exhaleDurationCs = b,
                tvRaw = c,
                fieldE = e,
                timestamp40ms = timestamp,
            )
        } catch (ex: Exception) {
            Timber.w(ex, "Failed to parse breath packet")
            null
        }
    }

    /**
     * Return the packet type byte, or -1 if empty.
     */
    fun packetType(bytes: ByteArray): Int {
        return if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else -1
    }

    // -------------------------------------------------------------------------
    // FIT developer field definitions (from real Tymewear FIT file analysis)
    // -------------------------------------------------------------------------

    private const val FIT_FLOAT32: Short = 136

    val FIT_FIELD_BREATH_RATE = DeveloperField(
        fieldDefinitionNumber = 0,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_breath_rate",
        units = "brpm",
    )

    val FIT_FIELD_TIDAL_VOLUME = DeveloperField(
        fieldDefinitionNumber = 1,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_tidal_volume",
        units = "vol/br",
    )

    val FIT_FIELD_MINUTE_VOLUME = DeveloperField(
        fieldDefinitionNumber = 2,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_minute_volume",
        units = "vol/min",
    )

    val FIT_FIELD_IE_RATIO = DeveloperField(
        fieldDefinitionNumber = 3,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_inhale_exhale_ratio",
        units = "sec/sec",
    )

    val FIT_FIELD_VE_ZONE = DeveloperField(
        fieldDefinitionNumber = 17,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone",
        units = "",
    )

    val FIT_FIELD_MOBILIZATION_INDEX = DeveloperField(
        fieldDefinitionNumber = 4,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_mobilization_index",
        units = "%",
    )

    val FIT_FIELD_PERCENT_BRR = DeveloperField(
        fieldDefinitionNumber = 5,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_percent_brr",
        units = "%",
    )

    // Session summary fields
    val FIT_FIELD_VE_ZONE1_TIME = DeveloperField(
        fieldDefinitionNumber = 27,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone1_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE1_PCT = DeveloperField(
        fieldDefinitionNumber = 28,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone1_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE2_TIME = DeveloperField(
        fieldDefinitionNumber = 29,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone2_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE2_PCT = DeveloperField(
        fieldDefinitionNumber = 30,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone2_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE3_TIME = DeveloperField(
        fieldDefinitionNumber = 31,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone3_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE3_PCT = DeveloperField(
        fieldDefinitionNumber = 32,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone3_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE4_TIME = DeveloperField(
        fieldDefinitionNumber = 33,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone4_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE4_PCT = DeveloperField(
        fieldDefinitionNumber = 34,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone4_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE5_TIME = DeveloperField(
        fieldDefinitionNumber = 35,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone5_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE5_PCT = DeveloperField(
        fieldDefinitionNumber = 36,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone5_percentage",
        units = "%",
    )

    // -------------------------------------------------------------------------
    // Ventilation zone thresholds (Tymewear 5-zone model, Feb 2025 update)
    // -------------------------------------------------------------------------
    //
    // Zone 1: Recovery      — below Endurance threshold
    // Zone 2: Endurance     — Endurance to VT1
    // Zone 3: Tempo         — VT1 to VT2
    // Zone 4: Threshold     — VT2 to VO2max
    // Zone 5: VO2max+       — above VO2max

    /**
     * Determine VE zone from minute ventilation value and user thresholds.
     * Returns 0 if no data, 1-5 for active zones.
     */
    fun veZone(
        minuteVolume: Double,
        endurance: Double,
        vt1: Double,
        vt2: Double,
        vo2max: Double,
    ): Int {
        return when {
            minuteVolume <= 0.0 -> 0
            minuteVolume < endurance -> 1
            minuteVolume < vt1 -> 2
            minuteVolume < vt2 -> 3
            minuteVolume < vo2max -> 4
            else -> 5
        }
    }
}
