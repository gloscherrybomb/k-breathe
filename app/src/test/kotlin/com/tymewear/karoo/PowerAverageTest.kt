package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerAverageTest {
    @Test fun `averages the last three values`() {
        val a = PowerAverage(3)
        assertEquals(100.0, a.add(100.0)!!, 1e-9); assertEquals(150.0, a.add(200.0)!!, 1e-9)
        assertEquals(200.0, a.add(300.0)!!, 1e-9); assertEquals(300.0, a.add(400.0)!!, 1e-9)
    }
    @Test fun `a gap resets the window`() {
        val a = PowerAverage(3); a.add(100.0); a.add(100.0)
        assertNull(a.add(null)); assertEquals(300.0, a.add(300.0)!!, 1e-9)
    }
}
