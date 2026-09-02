package com.tymewear.karoo

/**
 * Decides whether a ride end is worth interrupting the rider for.
 *
 * Kept free of Android types so the rule itself is testable on the JVM: the extension
 * gathers the four facts (see `TymewearExtension.maybePromptForSuggestions`) and this
 * object only says yes or no.
 */
object RideEndPrompt {

    /** True when a real ride just ended with the Beta on, auto-apply is off, and there is something to decide. */
    fun shouldShow(rideEnded: Boolean, betaEnabled: Boolean, autoApply: Boolean, suggestionCount: Int): Boolean =
        // rideEnded: `consumerFlow<RideState>()` replays the current state on a cold
        // subscribe, so an Idle transition can arrive with no ride having happened —
        // VentilatoryState.onRideEnd reports false for those, and a prompt over the
        // rider's map for a ride that never started would be pure noise.
        rideEnded &&
            // The Beta owns thresholds; with it off there is nothing behind a suggestion.
            betaEnabled &&
            // Auto-apply has already written the new value, so there is no choice left to
            // offer — the change history's Revert is the right place for second thoughts.
            !autoApply &&
            suggestionCount > 0
}
