package com.tymewear.karoo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideEndPromptTest {

    @Test
    fun `prompts when a real ride ended with the Beta on and something to decide`() {
        assertTrue(
            RideEndPrompt.shouldShow(
                rideEnded = true,
                betaEnabled = true,
                autoApply = false,
                suggestionCount = 1,
            ),
        )
    }

    @Test
    fun `stays quiet when no real ride ended`() {
        // consumerFlow<RideState>() replays Idle on a cold subscribe; onRideEnd reports
        // false for that, and nothing should pop up over the rider's screen.
        assertFalse(
            RideEndPrompt.shouldShow(
                rideEnded = false,
                betaEnabled = true,
                autoApply = false,
                suggestionCount = 1,
            ),
        )
    }

    @Test
    fun `stays quiet when the Beta is off`() {
        assertFalse(
            RideEndPrompt.shouldShow(
                rideEnded = true,
                betaEnabled = false,
                autoApply = false,
                suggestionCount = 1,
            ),
        )
    }

    @Test
    fun `stays quiet when auto-apply already applied the suggestion`() {
        assertFalse(
            RideEndPrompt.shouldShow(
                rideEnded = true,
                betaEnabled = true,
                autoApply = true,
                suggestionCount = 1,
            ),
        )
    }

    @Test
    fun `stays quiet when there is nothing to suggest`() {
        assertFalse(
            RideEndPrompt.shouldShow(
                rideEnded = true,
                betaEnabled = true,
                autoApply = false,
                suggestionCount = 0,
            ),
        )
    }
}
