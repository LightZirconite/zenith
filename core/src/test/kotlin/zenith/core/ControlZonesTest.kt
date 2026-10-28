package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlZonesTest {
    private val seeds = 1..10

    // 6 km itinerary running north from y = 1000, with a bend drawn coarsely.
    private val zone = ControlZone("I_1", listOf(Sim.ll(0.0, 1_000.0), Sim.ll(0.0, 4_000.0), Sim.ll(30.0, 7_000.0)))

    private fun drive(path: List<Pair<Double, Double>>, seed: Int, speedMps: Double = 25.0) =
        DrivingSession.replay(Sim.drive(path, speedMps, noiseM = 5.0, seed = seed), DrivingSession(null, emptyList(), zones = listOf(zone)))

    @Test
    fun `driving along a zone announces it once with what is left, then leaves it`() {
        for (seed in seeds) {
            val snaps = drive(listOf(0.0 to 0.0, 0.0 to 4_000.0, 30.0 to 7_000.0, 60.0 to 9_000.0), seed)
            val events = snaps.flatMap { it.events }
            val entered = events.filterIsInstance<AlertEvent.ZoneEntered>()
            assertEquals(1, entered.size, "seed $seed")
            assertEquals(6_000.0 - 75.0, entered.single().remainingM, 80.0, "seed $seed")
            assertEquals(1, events.filterIsInstance<AlertEvent.ZoneLeft>().size, "seed $seed")
            val inside = snaps.first { Sim.frame.toXY(it.position).y in 3_000.0..3_100.0 }
            assertEquals(4_000.0, inside.zone!!.remainingM, 120.0, "seed $seed")
            assertNull(snaps.last().zone)
        }
    }

    @Test
    fun `zone counts both ways`() {
        for (seed in seeds) {
            val events = drive(listOf(30.0 to 7_200.0, 30.0 to 7_000.0, 0.0 to 4_000.0, 0.0 to 2_000.0), seed).flatMap { it.events }
            val entered = events.filterIsInstance<AlertEvent.ZoneEntered>().single()
            assertEquals(6_000.0, entered.remainingM, 150.0, "seed $seed")
        }
    }

    @Test
    fun `crossing road and parallel road never enter the zone`() {
        for (seed in seeds) {
            val crossing = drive(listOf(-2_000.0 to 2_500.0, 2_000.0 to 2_500.0), seed)
            assertTrue(crossing.all { it.zone == null } && crossing.none { it.events.any { e -> e is AlertEvent.ZoneEntered } }, "seed $seed")
            val parallel = drive(listOf(300.0 to 1_000.0, 300.0 to 4_000.0), seed)
            assertTrue(parallel.all { it.zone == null }, "seed $seed")
        }
    }

    @Test
    fun `a stop inside the zone keeps it`() {
        val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 2_500.0), 25.0, noiseM = 3.0) +
            List(30) { GpsFix(101_000L + it * 1_000L, Sim.ll(0.0, 2_500.0), accuracyM = 3.0, speedMps = 0.0) }
        val snaps = DrivingSession.replay(fixes, DrivingSession(null, emptyList(), zones = listOf(zone)))
        assertTrue(snaps.last().zone != null)
        assertEquals(1, snaps.flatMap { it.events }.filterIsInstance<AlertEvent.ZoneEntered>().size)
    }
}
