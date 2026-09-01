package com.tymewear.karoo

/**
 * Keeps BLE scan starts inside the platform's rate budget.
 *
 * Android refuses scans from an app that starts them too frequently
 * (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, roughly 5 starts per 30 seconds). A refused
 * registration comes back with `scannerId=-1` and the scan never runs — the sensor
 * simply cannot be discovered, with no error surfaced to the user.
 *
 * This matters because scan requests are not ours to pace: the Karoo drives
 * `KarooExtension.startScan` / `stopScan`, and on a real device was observed calling
 * them 10 times in 12 seconds. Rather than burn the budget on registrations the
 * platform will reject, callers consult this throttle and defer instead.
 *
 * Pure and dependency-free (the clock is passed in) so the sliding-window behaviour is
 * unit-testable. Thread-safe: scan requests arrive on binder threads.
 */
class ScanThrottle(
    private val maxStarts: Int = DEFAULT_MAX_STARTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    private val lock = Any()

    /** Timestamps of recent scan starts, oldest first. */
    private val starts = ArrayDeque<Long>()

    /**
     * Records a scan start and returns true when it fits inside the budget.
     * Returns false without recording anything if starting now would exceed it.
     */
    fun tryAcquire(nowMs: Long): Boolean {
        synchronized(lock) {
            prune(nowMs)
            if (starts.size >= maxStarts) return false
            starts.addLast(nowMs)
            return true
        }
    }

    /**
     * Milliseconds to wait before a start would be permitted; 0 when one is available
     * right now. Callers should reschedule rather than drop the request.
     */
    fun delayUntilAllowedMs(nowMs: Long): Long {
        synchronized(lock) {
            prune(nowMs)
            if (starts.size < maxStarts) return 0L
            val oldest = starts.first()
            return (oldest + windowMs - nowMs).coerceAtLeast(0L)
        }
    }

    /** Number of starts currently counted against the budget. */
    fun recentStarts(nowMs: Long): Int = synchronized(lock) {
        prune(nowMs)
        starts.size
    }

    /** Forget all recorded starts. */
    fun reset() {
        synchronized(lock) { starts.clear() }
    }

    /** Drop starts that have aged out of the sliding window. */
    private fun prune(nowMs: Long) {
        while (starts.isNotEmpty() && nowMs - starts.first() >= windowMs) {
            starts.removeFirst()
        }
    }

    companion object {
        /**
         * One below the platform's limit of 5, so slight disagreement between our clock
         * and the framework's own accounting cannot push us over.
         */
        const val DEFAULT_MAX_STARTS = 4
        const val DEFAULT_WINDOW_MS = 30_000L
    }
}
