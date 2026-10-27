package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KalmanFilterTest {
    @Test
    fun `estimates speed and heading from noisy positions only`() {
        val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 3_000.0), 25.0, noiseM = 5.0, withVelocity = false)
        val kf = KalmanFilter()
        val tail = fixes.mapNotNull(kf::update).takeLast(30)
        // Without Doppler speed a single estimate wanders by ~2 m/s; the mean must be unbiased.
        assertEquals(25.0, tail.map { it.speedMps }.average(), 0.7)
        assertTrue(tail.all { Geo.headingDiffDeg(it.headingDeg!!, 0.0) < 10 })
    }

    @Test
    fun `rejects an isolated jump`() {
        val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 1_000.0), 20.0, noiseM = 3.0).toMutableList()
        val jump = fixes[20]
        fixes[20] = jump.copy(position = Sim.ll(300.0, 400.0), speedMps = null, bearingDeg = null)
        val kf = KalmanFilter()
        val states = fixes.map(kf::update)
        assertNull(states[20])
        val after = assertNotNull(states[21])
        assertTrue(Geo.distanceM(after.position, Sim.ll(0.0, 420.0)) < 15)
    }

    @Test
    fun `stays accurate over a long drive`() {
        val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 30_000.0), 40.0)
        val kf = KalmanFilter()
        val last = fixes.mapNotNull(kf::update).last()
        assertTrue(Geo.distanceM(last.position, fixes.last().position) < 10)
    }
}
