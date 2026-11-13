package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** When "Limite X" is said: only when really too fast, on a sure limit, once the sign is well passed. */
class SpeedingTest {
    /** Straight road north: [limits] as (start y, limit), each piece 1 m apart from the next node. */
    private fun network(vararg limits: Pair<Double, Int?>, source: LimitSource = LimitSource.EXPLICIT): RoadNetwork {
        val ends = limits.map { it.first } + 20_000.0
        return RoadNetwork(limits.mapIndexed { i, (y, limit) ->
            RoadSegment(
                i + 1L, i + 1L, i + 2L, listOf(Sim.ll(0.0, y), Sim.ll(0.0, ends[i + 1])), oneway = true,
                maxspeedForward = limit, limitSource = source,
            )
        })
    }

    /** Drives north at [kmh] (a function of the distance), 1 Hz; returns the speeds a warning was said at. */
    private fun warnings(network: RoadNetwork, seconds: Int, profile: DriverProfile = DriverProfile(), kmh: (Double) -> Double): List<Pair<Double, Int>> {
        val session = DrivingSession(network, emptyList(), profile)
        var y = 0.0
        val out = mutableListOf<Pair<Double, Int>>()
        repeat(seconds) { t ->
            val v = kmh(y) / 3.6
            val snap = session.process(GpsFix(t * 1_000L, Sim.ll(0.0, y), 4.0, v, 0.0))
            snap?.events?.filterIsInstance<AlertEvent.Overspeed>()?.filter { it.level == OverspeedLevel.WARN }
                ?.forEach { out += y to it.limitKmh }
            y += v
        }
        return out
    }

    @Test
    fun `a little over the limit is not said, past the radar margin it is`() {
        val net = network(0.0 to 80)
        assertTrue(warnings(net, 40) { 84.0 }.isEmpty())
        assertEquals(80, warnings(net, 40) { 88.0 }.single().second)
    }

    @Test
    fun `early warning says it from 3 kmh over`() {
        assertEquals(1, warnings(network(0.0 to 80), 40, DriverProfile(earlySpeeding = true)) { 84.0 }.size)
    }

    @Test
    fun `a lower limit is said only once the driver had time to slow down`() {
        // 90 then 50 from 1 000 m: the car slows from 90 to 50 over the 100 m after the sign.
        val net = network(0.0 to 90, 1_000.0 to 50)
        val slowing = warnings(net, 120) { y -> if (y < 1_000) 90.0 else maxOf(50.0, 90.0 - (y - 1_000) * 0.4) }
        assertTrue(slowing.isEmpty(), "said at $slowing")
        // Still at 90 well after the sign: said.
        assertEquals(50, warnings(net, 120) { 90.0 }.single().second)
    }

    @Test
    fun `speeding up at the end of a 30 zone is not said`() {
        val net = network(0.0 to 30, 400.0 to 50)
        val out = warnings(net, 80) { y -> if (y < 395) 30.0 else 50.0 }
        assertTrue(out.isEmpty(), "said at $out")
    }

    @Test
    fun `a limit inferred from the road class is never said`() {
        assertTrue(warnings(network(0.0 to 50, source = LimitSource.INFERRED), 40) { 70.0 }.isEmpty())
    }

    @Test
    fun `no limit shown for a road far off the fix (car park, private lane)`() {
        val session = DrivingSession(network(0.0 to 50), emptyList())
        val snaps = (0 until 10).mapNotNull { t -> session.process(GpsFix(t * 1_000L, Sim.ll(40.0, t * 8.0), 4.0, 8.0, 0.0)) }
        assertTrue(snaps.all { it.limitKmh == null })
        val near = DrivingSession(network(0.0 to 50), emptyList())
        val shown = (0 until 10).mapNotNull { t -> near.process(GpsFix(t * 1_000L, Sim.ll(8.0, t * 8.0), 4.0, 8.0, 0.0)) }
        assertEquals(50, shown.last().limitKmh)
    }
}
