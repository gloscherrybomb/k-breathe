package com.tymewear.karoo

import io.hammerhead.karooext.models.DeveloperField
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

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

    // Command/control characteristic (near "run_workout" in Dart code)
    // Used for start/stop recording, strap mode, strap size commands
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
    // "vitalpro-hw8-small", "vitalpro-hw8-regular"
    private val DEVICE_NAME_PATTERNS = listOf("vitalpro", "tymewear")

    /**
     * Check if a BLE device name matches the VitalPro breathing sensor.
     * Matches both "VitalPro" and "Tymewear" prefixed names.
     */
    fun isVitalProDevice(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val lower = deviceName.lowercase()
        return DEVICE_NAME_PATTERNS.any { lower.contains(it) }
    }

    /**
     * Check if a device name matches a specific sensor ID (4-digit code).
     */
    fun matchesSensorId(deviceName: String?, sensorId: String): Boolean {
        if (deviceName == null || sensorId.isBlank()) return false
        return deviceName.contains(sensorId, ignoreCase = true)
    }

    // -------------------------------------------------------------------------
    // Data parsing
    // -------------------------------------------------------------------------

    // The Dart function `_transformBLEResponseToStrapData` handles parsing.
    // Exact byte layout needs validation via nRF Connect / HCI snoop log.
    //
    // Known data fields from Dart symbols:
    //   breathing_rate, tidal_vol, ventilation, breath, breathTimestamps,
    //   imuProcessedList, strap_imu_period
    //
    // Characteristics to subscribe for notifications:
    //   - 40B50001 (primary data stream)
    //   - Possibly also 40B50004 and 40B50007

    /**
     * Parsed breathing data from a single BLE notification.
     */
    data class BreathingData(
        val breathRate: Double,      // breaths per minute
        val tidalVolume: Double,     // volume per breath
        val minuteVolume: Double,    // L/min (VE)
        val ieRatio: Double,         // inhale/exhale ratio
        val veZone: Int,             // ventilation zone (0=none, 1-3)
    )

    /**
     * Parse raw BLE notification bytes into breathing data.
     *
     * IMPORTANT: This byte layout is a best guess and MUST be validated
     * against real device traffic using nRF Connect or HCI snoop log.
     *
     * The Dart function `_transformBLEResponseToStrapData` (at library ID
     * 1700344857) is where the real parsing happens. Since the Dart code is
     * AOT-compiled, we need to observe actual BLE traffic to confirm the
     * byte encoding format.
     *
     * Likely encoding patterns:
     * - Little-endian float32 at fixed byte offsets
     * - Scaled uint16 (value / 100.0 to get decimal)
     * - JSON string (unlikely for real-time data, but "strapDataJson" exists)
     */
    fun parseBreathingData(bytes: ByteArray): BreathingData? {
        if (bytes.isEmpty()) return null

        // TODO: Validate byte layout against real BLE traffic capture.
        // Placeholder assuming 4x float32 little-endian:
        //   [0..3]  = breath rate (float32)
        //   [4..7]  = tidal volume (float32)
        //   [8..11] = minute volume (float32)
        //   [12..15] = IE ratio (float32)
        //   [16]    = VE zone (uint8)
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 17) return null
            BreathingData(
                breathRate = buf.getFloat(0).toDouble(),
                tidalVolume = buf.getFloat(4).toDouble(),
                minuteVolume = buf.getFloat(8).toDouble(),
                ieRatio = buf.getFloat(12).toDouble(),
                veZone = bytes[16].toInt() and 0xFF,
            )
        } catch (e: Exception) {
            null
        }
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

    // -------------------------------------------------------------------------
    // Ventilation zone thresholds
    // -------------------------------------------------------------------------

    /**
     * Determine VE zone from minute ventilation value and user thresholds.
     * Zone 1: below VT1, Zone 2: VT1-VT2, Zone 3: above VT2
     */
    fun veZone(minuteVolume: Double, vt1: Double, vt2: Double): Int {
        return when {
            minuteVolume <= 0.0 -> 0
            minuteVolume < vt1 -> 1
            minuteVolume < vt2 -> 2
            else -> 3
        }
    }
}
