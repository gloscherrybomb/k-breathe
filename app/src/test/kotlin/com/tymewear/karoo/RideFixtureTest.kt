package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideFixtureTest {
    @Test
    fun `loads heart rate from both fixture header styles`() {
        val indoor = RideFixture.load("ride_2026-02-24.csv")          // header: time,watts,heartrate,ve,br
        val outdoor = RideFixture.load("outdoor_easy_2026-09-02.csv") // header: time,watts,heartrate,TymeVentilation,TymeBreathRate
        assertEquals(indoor.watts.size, indoor.hr.size)
        assertEquals(outdoor.watts.size, outdoor.hr.size)
        assertTrue("indoor fixture must carry HR", indoor.hr.count { it != null && it > 60 } > 2000)
        assertTrue("outdoor fixture must carry HR", outdoor.hr.count { it != null && it > 60 } > 4000)
    }

    @Test
    fun `outdoor fixtures are clean recordings`() {
        assertTrue(RideFixture.load("outdoor_easy_2026-09-02.csv").qualityRatio() > 0.25)
        assertTrue(RideFixture.load("outdoor_long_2026-04-25.csv").qualityRatio() > 0.15)
    }
}
