package com.tymewear.karoo

import android.graphics.Color
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

/**
 * Single source of truth for constants used across multiple files.
 */
object Constants {

    // -------------------------------------------------------------------------
    // Zone colors
    // -------------------------------------------------------------------------

    /** Solid zone colors for backgrounds and bar charts. */
    val ZONE_COLORS_SOLID = intArrayOf(
        Color.parseColor("#4DB6AC"),   // Z1 Teal (Endurance)
        Color.parseColor("#0277BD"),   // Z2 Blue (VT1)
        Color.parseColor("#F57F17"),   // Z3 Amber (VT2)
        Color.parseColor("#EF6C00"),   // Z4 Orange (Top Z4)
        Color.parseColor("#C62828"),   // Z5 Red (VO2Max)
    )

    /** Semi-transparent zone colors for graph background bands. */
    val ZONE_COLORS_ALPHA = intArrayOf(
        Color.argb(120, 77, 182, 172),   // Z1 Teal (Endurance)
        Color.argb(120, 2, 119, 189),    // Z2 Blue (VT1)
        Color.argb(120, 245, 127, 23),   // Z3 Amber (VT2)
        Color.argb(120, 239, 108, 0),    // Z4 Orange (Top Z4)
        Color.argb(120, 198, 40, 40),    // Z5 Red (VO2Max)
    )

    /** Background color when no data / no zone. */
    val NO_DATA_COLOR = Color.parseColor("#424242")

    /**
     * Get zone style (label + solid color) for a ventilation zone.
     * Zone 0 or invalid returns NO_DATA_COLOR.
     */
    fun zoneStyle(zone: Int): Pair<String, Int> = when (zone) {
        in 1..5 -> "Z$zone" to ZONE_COLORS_SOLID[zone - 1]
        else -> "" to NO_DATA_COLOR
    }

    // -------------------------------------------------------------------------
    // Default thresholds
    // -------------------------------------------------------------------------

    const val DEFAULT_VT1 = 73f
    const val DEFAULT_VT2 = 96f
    const val DEFAULT_TOP_Z4 = 112f
    const val DEFAULT_VO2MAX = 130f

    // -------------------------------------------------------------------------
    // Default MI parameters
    // -------------------------------------------------------------------------

    const val DEFAULT_RESTING_BR = 12f
    const val DEFAULT_MAX_BR = 55f
    const val DEFAULT_MAX_HR = 190f
    const val DEFAULT_RESTING_HR = 60f

    // -------------------------------------------------------------------------
    // Bitmap sizes
    // -------------------------------------------------------------------------

    const val VE_GRAPH_WIDTH = 400
    const val VE_GRAPH_HEIGHT = 200
    const val MI_BATTERY_WIDTH = 300
    const val MI_BATTERY_HEIGHT = 150
    const val ZONES_BITMAP_WIDTH = 400
    const val ZONES_BITMAP_HEIGHT = 200

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

    /** Breathing data older than this is stale: never recorded to FIT and never
     *  displayed as a live value. Must exceed the longest normal gap between breath
     *  packets (~4s at rest) with margin, while staying short enough that a dropout
     *  is caught within a few seconds. */
    const val BLE_DATA_STALENESS_TIMEOUT_MS = 10000L

    /** Initial connect: time to wait for device to appear in a targeted scan
     *  before falling back to autoConnect=true. */
    const val BLE_INITIAL_SCAN_TIMEOUT_MS = 8000L

    // -------------------------------------------------------------------------
    // Ventilatory state (Beta)
    // -------------------------------------------------------------------------

    /** Minimum baseline bins with coverage before deviation is reported. */
    const val STATE_MIN_BASELINE_BINS = 3

    /** Drift percentage that counts as "no longer sustainable" for the optional alert. */
    const val STATE_DEFAULT_DRIFT_ALERT_PCT = 12

    // -------------------------------------------------------------------------
    // Data bounds (for protocol validation)
    // -------------------------------------------------------------------------

    const val MAX_BREATHING_RATE = 120.0
    const val MAX_TIDAL_VOLUME_L = 5.0
    const val MAX_MINUTE_VENTILATION = 250.0

    // -------------------------------------------------------------------------
    // Coroutine error handler
    // -------------------------------------------------------------------------

    val coroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "Uncaught coroutine exception")
    }
}
