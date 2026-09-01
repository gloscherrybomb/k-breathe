package com.tymewear.karoo

/**
 * Tracks how recently sensor data arrived so callers can distinguish live data from
 * a stale last-known value.
 *
 * Recording paths must write nothing rather than write a value that stopped changing:
 * a frozen value is indistinguishable from real data downstream, which silently
 * corrupts FIT files and any analysis built on them.
 *
 * Deliberately free of Android and [Constants] dependencies so it is unit-testable on
 * the JVM; the timeout is injected. Thread-safe: updated from BLE callback threads and
 * read from recording/view coroutines.
 */
class DataFreshness(private val stalenessTimeoutMs: Long) {

    @Volatile
    private var lastUpdateMs: Long? = null

    /** Record that data arrived at [nowMs]. */
    fun recordUpdate(nowMs: Long) {
        lastUpdateMs = nowMs
    }

    /**
     * True when data has arrived and is no older than the staleness timeout.
     * False before the first packet ever arrives, so defaults are never mistaken
     * for readings.
     */
    fun isFresh(nowMs: Long): Boolean {
        val last = lastUpdateMs ?: return false
        return nowMs - last <= stalenessTimeoutMs
    }

    /** Age of the newest data, or null if none has arrived. */
    fun ageMs(nowMs: Long): Long? = lastUpdateMs?.let { nowMs - it }

    /** Forget any recorded data. Called on disconnect so a reconnect starts clean. */
    fun reset() {
        lastUpdateMs = null
    }
}
