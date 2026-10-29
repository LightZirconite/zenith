package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeedStabilizerTest {
    private fun fix(speedKmh: Double?, accuracy: Double = 5.0) =
        GpsFix(0, LatLon(45.0, 5.0), accuracy, speedKmh?.let { it / 3.6 })

    @Test
    fun `nothing is shown until the GPS is accurate`() {
        val s = SpeedStabilizer()
        assertNull(s.update(fix(6.0, accuracy = 80.0), 0.0))
        assertNull(s.update(fix(3.0, accuracy = 45.0), 0.0))
        assertEquals(0, s.update(fix(0.5), 0.0))
    }

    @Test
    fun `parked car jitter reads 0`() {
        val s = SpeedStabilizer()
        val shown = listOf(0.0, 1.2, 3.8, 0.4, 4.6, 2.0, 1.0).map { s.update(fix(it), 0.0) }
        assertEquals(List(7) { 0 }, shown)
    }

    @Test
    fun `driving off and stopping`() {
        val s = SpeedStabilizer()
        val shown = listOf(0.0, 8.0, 20.0, 30.0, 30.0, 10.0, 2.0, 0.0, 0.0).map { s.update(fix(it), 0.0) }
        assertEquals(listOf(0, 8, 20, 30, 30, 10, 0, 0, 0), shown)
    }

    @Test
    fun `a poor fix is smoothed`() {
        val s = SpeedStabilizer()
        assertEquals(50, s.update(fix(50.0), 0.0))
        assertEquals(62, s.update(fix(70.0, accuracy = 40.0), 0.0))
    }

    @Test
    fun `falls back to the filtered speed without Doppler`() {
        val s = SpeedStabilizer()
        assertEquals(90, s.update(fix(null), 25.0))
    }
}
