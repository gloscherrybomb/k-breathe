package com.tymewear.karoo

/**
 * Tracks whether a ride is active and whether it is currently paused, and tells the caller
 * what kind of transition just happened so it can decide whether to reset its pipeline.
 *
 * `RideState` has exactly three variants — Idle, Paused, Recording — so both a fresh start
 * (Idle -> Recording) and a resume from a stop (Paused -> Recording) arrive as the same
 * "Recording" transition. [onRecording] tells them apart. It also always clears [isPaused]:
 * a resume is by definition no longer paused, and clearing it only on a fresh start is
 * exactly the bug this class exists to prevent — [isPaused] would otherwise stay stuck true
 * for the rest of the ride after the first pause, silently discarding every later sample.
 *
 * Pure: no Android imports, no [Constants], no clock.
 */
class RideLifecycle {

    var isActive: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    /**
     * Recording started or resumed.
     *
     * @return `true` for a fresh start — the caller should reset its pipeline (detector,
     * deviation, drift, sample buffer, flows). `false` for a resume — the caller should
     * preserve everything accumulated so far. Clears [isPaused] in both cases.
     */
    fun onRecording(): Boolean {
        val freshStart = !isActive
        isActive = true
        isPaused = false
        return freshStart
    }

    /** Ride paused. Idempotent — pausing an already-paused ride changes nothing. */
    fun onPaused() {
        isPaused = true
    }

    /**
     * Ride stopped.
     *
     * @return `true` if a ride was actually active — the caller should fold its buffered
     * samples into the baseline and persist. `false` if no ride was active, e.g. an Idle
     * replayed by a cold subscribe to the RideState stream with nothing having started;
     * the caller must then do nothing rather than spuriously persist. Clears both flags
     * either way.
     */
    fun onIdle(): Boolean {
        val wasActive = isActive
        isActive = false
        isPaused = false
        return wasActive
    }
}
