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
        Color.parseColor("#546E7A"),   // Z1 Blue Grey
        Color.parseColor("#0277BD"),   // Z2 Blue
        Color.parseColor("#2E7D32"),   // Z3 Green
        Color.parseColor("#F57F17"),   // Z4 Amber
        Color.parseColor("#C62828"),   // Z5 Red
    )

    /** Semi-transparent zone colors for graph background bands. */
    val ZONE_COLORS_ALPHA = intArrayOf(
        Color.argb(60, 84, 110, 122),   // Z1 Blue Grey
        Color.argb(60, 2, 119, 189),     // Z2 Blue
        Color.argb(60, 46, 125, 50),     // Z3 Green
        Color.argb(60, 245, 127, 23),    // Z4 Amber
        Color.argb(60, 198, 40, 40),     // Z5 Red
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

    const val DEFAULT_ENDURANCE = 69f
    const val DEFAULT_VT1 = 83f
    const val DEFAULT_VT2 = 111f
    const val DEFAULT_VO2MAX = 180f

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
    // BLE reconnect parameters
    // -------------------------------------------------------------------------

    const val BLE_MAX_RECONNECT_ATTEMPTS = 10
    const val BLE_MAX_RECONNECT_DELAY_MS = 30000L
    const val BLE_BASE_RECONNECT_DELAY_MS = 2000L

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
