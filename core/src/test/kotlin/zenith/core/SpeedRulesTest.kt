package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedRulesTest {
    @Test
    fun `fixed radar margin is 5 kmh below 100 and 5 percent above`() {
        assertEquals(85.0, SpeedRules.retainedSpeed(90.0))
        assertEquals(94.0, SpeedRules.retainedSpeed(99.0))
        assertEquals(95.0, SpeedRules.retainedSpeed(100.0))
        assertEquals(133.0, SpeedRules.retainedSpeed(140.0))
    }

    @Test
    fun `moving radar margin is 10 kmh below 100 and 10 percent above`() {
        assertEquals(80.0, SpeedRules.retainedSpeed(90.0, movingRadar = true))
        assertEquals(126.0, SpeedRules.retainedSpeed(140.0, movingRadar = true))
    }

    @Test
    fun `points scale`() {
        assertEquals(0, SpeedRules.pointsLost(0.0))
        assertEquals(1, SpeedRules.pointsLost(19.0))
        assertEquals(2, SpeedRules.pointsLost(20.0))
        assertEquals(3, SpeedRules.pointsLost(35.0))
        assertEquals(4, SpeedRules.pointsLost(49.0))
        assertEquals(6, SpeedRules.pointsLost(50.0))
    }

    @Test
    fun `rain and probationary limits`() {
        assertEquals(listOf(110, 100, 80, 80, 50), listOf(130, 110, 90, 80, 50).map(SpeedRules::rainLimit))
        assertEquals(listOf(110, 100, 80, 80, 50), listOf(130, 110, 90, 80, 50).map(SpeedRules::probationaryLimit))
        assertEquals(130, SpeedRules.effectiveLimit(130, raining = false, probationary = false))
        assertEquals(110, SpeedRules.effectiveLimit(130, raining = true, probationary = true))
        assertEquals(50, SpeedRules.effectiveLimit(50, raining = true, probationary = true))
    }
}
