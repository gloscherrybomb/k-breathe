package com.tymewear.karoo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLifecycleTest {

    @Test
    fun `a fresh start reports fresh`() {
        val rl = RideLifecycle()
        assertTrue("Idle -> Recording with no prior ride is a fresh start", rl.onRecording())
        assertTrue(rl.isActive)
        assertFalse(rl.isPaused)
    }

    @Test
    fun `pause then Recording reports resume and clears paused`() {
        val rl = RideLifecycle()
        rl.onRecording()
        rl.onPaused()
        assertTrue(rl.isPaused)

        val fresh = rl.onRecording()

        assertFalse("Paused -> Recording is a resume, not a fresh start", fresh)
        assertFalse("resuming must clear paused", rl.isPaused)
        assertTrue(rl.isActive)
    }

    @Test
    fun `Idle after an active ride reports active`() {
        val rl = RideLifecycle()
        rl.onRecording()
        assertTrue("a ride was active, so onIdle should report it ended one", rl.onIdle())
        assertFalse(rl.isActive)
        assertFalse(rl.isPaused)
    }

    @Test
    fun `Idle with no active ride reports not active`() {
        val rl = RideLifecycle()
        assertFalse(
            "a replayed Idle with nothing having started must not report an active ride",
            rl.onIdle(),
        )
        assertFalse(rl.isActive)
    }

    @Test
    fun `a double pause is idempotent`() {
        val rl = RideLifecycle()
        rl.onRecording()
        rl.onPaused()
        rl.onPaused()
        assertTrue(rl.isPaused)
        assertTrue(rl.isActive)
    }

    @Test
    fun `pause resume pause resume leaves the tracker live, not wedged`() {
        // Regression test: with the flag cleared only on a fresh start, isPaused gets
        // stuck true after the first pause and never recovers on later resumes.
        val rl = RideLifecycle()
        rl.onRecording()      // fresh start
        rl.onPaused()
        rl.onRecording()      // resume #1
        rl.onPaused()
        rl.onRecording()      // resume #2

        assertTrue("the ride should still be active after two pause/resume cycles", rl.isActive)
        assertFalse("the ride must not be stuck paused after the second resume", rl.isPaused)
    }
}
