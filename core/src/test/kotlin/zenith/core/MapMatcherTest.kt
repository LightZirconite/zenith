package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapMatcherTest {
    // Northbound motorway (130) split at node 2 where a ramp (70) leaves to the east,
    // plus an unconnected two-way service road (50) running 15 m east of the motorway.
    private val network = RoadNetwork(
        listOf(
            Sim.seg(1, 1, 2, 0.0 to 0.0, 0.0 to 500.0, oneway = true, limit = 130, roadClass = "motorway"),
            Sim.seg(2, 2, 3, 0.0 to 500.0, 0.0 to 2_000.0, oneway = true, limit = 130, roadClass = "motorway"),
            Sim.seg(3, 2, 4, 0.0 to 500.0, 12.0 to 700.0, 30.0 to 900.0, 200.0 to 1_300.0, oneway = true, limit = 70, roadClass = "motorway_link"),
            Sim.seg(4, 10, 11, 15.0 to 0.0, 15.0 to 2_000.0, limit = 50, roadClass = "service"),
        ),
    )

    private fun match(fixes: List<GpsFix>): List<RoadCandidate?> {
        val kf = KalmanFilter()
        val mm = MapMatcher(network)
        return fixes.mapNotNull(kf::update).map(mm::update)
    }

    @Test
    fun `holds the motorway despite noise pulling toward a parallel road`() {
        for (seed in 1..20) {
            val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 2_000.0), 30.0, noiseM = 7.0, seed = seed)
            val matched = match(fixes).drop(3)
            val onMotorway = matched.count { it?.segment?.id in setOf(1L, 2L) }
            assertTrue(onMotorway >= matched.size * 0.95, "seed $seed: on motorway $onMotorway/${matched.size}")
            assertEquals(130, matched.last()?.limitKmh)
        }
    }

    @Test
    fun `follows the ramp once it diverges`() {
        val path = listOf(0.0 to 0.0, 0.0 to 500.0, 12.0 to 700.0, 30.0 to 900.0, 200.0 to 1_300.0)
        for (seed in 1..20) {
            val fixes = Sim.drive(path, 20.0, noiseM = 4.0, seed = seed)
            val matched = match(fixes)
            // Once the ramp is 30 m away from the motorway (y ≥ 900) the match must be on it.
            val pastSplit = matched.zip(fixes.drop(fixes.size - matched.size))
                .filter { (_, f) -> Sim.frame.toXY(f.position).y >= 950 }
            assertTrue(pastSplit.all { it.first?.segment?.id == 3L }, "seed $seed")
            assertEquals(70, matched.last()?.limitKmh)
        }
    }

    @Test
    fun `two-way road is matched in the travel direction`() {
        val matched = match(Sim.drive(listOf(15.0 to 1_800.0, 15.0 to 200.0), 13.0, noiseM = 2.0))
        val last = matched.last()!!
        assertEquals(4L, last.segment.id)
        assertEquals(false, last.forward)
    }
}
