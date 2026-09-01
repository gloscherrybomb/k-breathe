package com.tymewear.karoo

/**
 * Decides when to force a BLE reconnect because notifications have silently stopped
 * (GATT still reports "connected" but no packets arrive).
 *
 * The decision is separated from the Android BLE plumbing so the retry behaviour can be
 * unit-tested. The behaviour that matters, and which the previous inline implementation
 * got wrong: **if a forced reconnect fails to restore data, the watchdog must keep
 * trying.** The old code marked its clock as "never" when firing, and its own guard
 * skipped that sentinel, so one failed recovery disabled it for the rest of the ride.
 *
 * Thread-safe: [onData] is called from BLE callback threads, [shouldForceReconnect]
 * from the watchdog handler thread.
 */
class DataWatchdog(private val timeoutMs: Long) {

    private val lock = Any()

    /** Time of the last packet, or of the last forced reconnect (the retry clock). */
    private var clockMs: Long? = null

    private var failures: Int = 0

    /** Consecutive forced reconnects that have not yet been followed by data. */
    val consecutiveFailures: Int
        get() = synchronized(lock) { failures }

    /** Record that a notification arrived at [nowMs]; clears any pending recovery. */
    fun onData(nowMs: Long) {
        synchronized(lock) {
            clockMs = nowMs
            failures = 0
        }
    }

    /**
     * True when data has been absent longer than the timeout and a reconnect should be
     * forced now. Returns false before the first packet has ever arrived (nothing to
     * recover yet).
     *
     * Firing advances the retry clock rather than clearing it, so a failed recovery
     * simply fires again one timeout later.
     */
    fun shouldForceReconnect(nowMs: Long): Boolean {
        synchronized(lock) {
            val since = clockMs ?: return false
            if (nowMs - since <= timeoutMs) return false
            clockMs = nowMs
            failures++
            return true
        }
    }

    /** Forget all state. Called when a connection is torn down for good. */
    fun reset() {
        synchronized(lock) {
            clockMs = null
            failures = 0
        }
    }
}
