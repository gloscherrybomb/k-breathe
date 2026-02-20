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

    const val DEFAULT_VT1 = 83f
    const val DEFAULT_VT2 = 111f
    const val DEFAULT_TOP_Z4 = 128f
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
