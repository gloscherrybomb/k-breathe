package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThresholdChangeTest {

    @Test
    fun `serialise then deserialise round-trips`() {
        val change = ThresholdChange(ThresholdKind.VT2, fromVe = 73.0, toVe = 66.0, atMs = 1_700_000_000_000L)
        assertEquals(change, ThresholdChange.deserialise(change.serialise()))
    }

    @Test
    fun `malformed entries are rejected rather than crashing`() {
        assertNull(ThresholdChange.deserialise("garbage"))
        assertNull(ThresholdChange.deserialise("VT1:not-a-number:66:123"))
        assertNull(ThresholdChange.deserialise("NOT_A_KIND:73:66:123"))
    }
}
