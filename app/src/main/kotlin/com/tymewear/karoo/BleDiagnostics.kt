package com.tymewear.karoo

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Field diagnostics for the ride-start data-loss failure.
 *
 * Real FIT files show breathing data flowing for only 34-104 seconds after recording
 * starts and then stopping for the rest of the ride. These counters make that visible
 * in logs so the cause can be pinned down: whether packets simply stop, whether the
 * watchdog notices, whether reconnects are attempted, and whether they recover.
 *
 * Counters are process-wide and cheap (atomics, no allocation on the packet path).
 * A summary is logged periodically rather than per packet.
 */
object BleDiagnostics {

    private val packets = AtomicLong(0)
    private val lastPacketMs = AtomicLong(0)
    private val watchdogFires = AtomicInteger(0)
    private val reconnects = AtomicInteger(0)
    private val staleRecordsSkipped = AtomicLong(0)
    private val lastSummaryMs = AtomicLong(0)
    private val scanStarts = AtomicInteger(0)
    private val scanDeferrals = AtomicInteger(0)
    private val scanFailures = AtomicInteger(0)

    /** Packets counted since the last summary, to show whether flow has stopped. */
    private val packetsSinceSummary = AtomicLong(0)

    fun onPacket(nowMs: Long) {
        packets.incrementAndGet()
        packetsSinceSummary.incrementAndGet()
        lastPacketMs.set(nowMs)
        maybeLogSummary(nowMs)
    }

    fun onWatchdogFire(consecutiveFailures: Int) {
        watchdogFires.incrementAndGet()
        Timber.w(
            "DIAG watchdog fire #${watchdogFires.get()} " +
                "(consecutive failures: $consecutiveFailures, packets so far: ${packets.get()})",
        )
    }

    fun onReconnectAttempt(attempt: Int, autoConnect: Boolean) {
        reconnects.incrementAndGet()
        Timber.d("DIAG reconnect attempt #$attempt (autoConnect=$autoConnect)")
    }

    /**
     * Periodic heartbeat from the watchdog thread. The summary must not depend on
     * packets arriving, or it falls silent during exactly the dropouts it exists to
     * document.
     */
    fun tick(nowMs: Long) {
        maybeLogSummary(nowMs)
    }

    fun onScanStart() {
        scanStarts.incrementAndGet()
    }

    /**
     * A scan start held back to stay inside Android's scan-rate budget. Exceeding it
     * makes the platform refuse to scan silently, so these are worth seeing.
     */
    fun onScanDeferred(waitMs: Long) {
        val n = scanDeferrals.incrementAndGet()
        Timber.w("DIAG scan deferred #$n (waiting ${waitMs}ms for scan-rate budget)")
    }

    fun onScanFailed(errorCode: Int) {
        scanFailures.incrementAndGet()
        Timber.w(
            "DIAG scan failure #${scanFailures.get()} code=$errorCode " +
                "(${BleStatus.decodeScan(errorCode)}); starts=${scanStarts.get()} " +
                "deferrals=${scanDeferrals.get()}",
        )
    }

    /** A FIT record that omitted breathing fields because the data was stale. */
    fun onStaleRecordSkipped() {
        val n = staleRecordsSkipped.incrementAndGet()
        // Log the first, then sparsely — this can otherwise fire once per second.
        if (n == 1L || n % 60L == 0L) {
            Timber.w("DIAG stale FIT records skipped: $n (breathing data not fresh)")
        }
    }

    fun lastPacketAgeMs(nowMs: Long): Long? =
        lastPacketMs.get().takeIf { it > 0 }?.let { nowMs - it }

    /**
     * Log the BLE/data state at a ride lifecycle transition. The freeze always begins
     * shortly after recording starts, so this pins down the state at that moment.
     */
    fun logRideTransition(state: String, nowMs: Long = System.currentTimeMillis()) {
        val age = lastPacketAgeMs(nowMs)
        Timber.i(
            "DIAG ride transition -> $state | packets=${packets.get()} " +
                "lastPacketAge=${age?.let { "${it}ms" } ?: "never"} " +
                "watchdogFires=${watchdogFires.get()} reconnects=${reconnects.get()} " +
                "staleSkipped=${staleRecordsSkipped.get()}",
        )
    }

    private fun maybeLogSummary(nowMs: Long) {
        val last = lastSummaryMs.get()
        if (nowMs - last < SUMMARY_INTERVAL_MS) return
        if (!lastSummaryMs.compareAndSet(last, nowMs)) return
        if (last == 0L) return // skip the first, no interval to report over
        val inWindow = packetsSinceSummary.getAndSet(0)
        val age = lastPacketAgeMs(nowMs)
        Timber.d(
            "DIAG ${SUMMARY_INTERVAL_MS / 1000}s summary: packets=$inWindow " +
                "lastPacketAge=${age?.let { "${it}ms" } ?: "never"} " +
                "(total=${packets.get()}) watchdogFires=${watchdogFires.get()} " +
                "reconnects=${reconnects.get()} staleSkipped=${staleRecordsSkipped.get()} " +
                "scans=${scanStarts.get()}/deferred=${scanDeferrals.get()}/failed=${scanFailures.get()}",
        )
    }

    /** Reset for a new connection session. */
    fun reset() {
        packets.set(0)
        packetsSinceSummary.set(0)
        lastPacketMs.set(0)
        watchdogFires.set(0)
        reconnects.set(0)
        staleRecordsSkipped.set(0)
        lastSummaryMs.set(0)
        scanStarts.set(0)
        scanDeferrals.set(0)
        scanFailures.set(0)
    }

    private const val SUMMARY_INTERVAL_MS = 60_000L
}
