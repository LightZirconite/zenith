package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoastAndRulesTest {
    private val road = RoadNetwork(listOf(Sim.seg(1, 1, 2, 0.0 to -100.0, 0.0 to 6_000.0, oneway = true, limit = 90)))

    /** Drives north at [speedMps]; no GPS between [gapFromM] and [gapToM], coasting each second there. */
    private fun driveWithGap(radars: List<Radar>, speedMps: Double, gapFromM: Double, gapToM: Double, seed: Int): List<DriveSnapshot> {
        val session = DrivingSession(road, radars)
        return Sim.drive(listOf(0.0 to 0.0, 0.0 to 5_000.0), speedMps, noiseM = 4.0, seed = seed).mapNotNull { fix ->
            val y = Sim.frame.toXY(fix.position).y
            if (y in gapFromM..gapToM) session.coast(fix.timeMs) else session.process(fix)
        }
    }

    @Test
    fun `radar in a tunnel still alerts while the GPS is lost`() {
        val radar = Radar("tunnel", Sim.ll(0.0, 2_600.0), RadarType.FIXED, 90, directionDeg = 0.0)
        for (seed in 1..10) {
            val snaps = driveWithGap(listOf(radar), 25.0, 2_000.0, 3_000.0, seed)
            val events = snaps.flatMap { it.events }
            assertEquals(listOf("tunnel"), events.filterIsInstance<AlertEvent.Approach>().map { it.radar.id }, "seed $seed")
            assertEquals(listOf("tunnel"), events.filterIsInstance<AlertEvent.Passed>().map { it.radar.id }, "seed $seed")
            val coasted = snaps.filter { it.estimated }
            assertTrue(coasted.size >= 35, "seed $seed: ${coasted.size} estimated fixes")
            // Estimated position follows the road: x stays on the axis.
            assertTrue(coasted.all { kotlin.math.abs(Sim.frame.toXY(it.position).x) < 15 }, "seed $seed")
        }
    }

    @Test
    fun `coasting stops after a minute without GPS`() {
        val session = DrivingSession(road, emptyList())
        Sim.drive(listOf(0.0 to 0.0, 0.0 to 500.0), 25.0).forEach { session.process(it) }
        val lastReal = 20_000L
        assertTrue(session.coast(lastReal + 1_000) != null)
        assertNull(session.coast(lastReal + DrivingSession.MAX_COAST_MS + 2_000))
    }

    @Test
    fun `red light radar has no speed limit and never flashes for speed`() {
        val red = Radar("red", Sim.ll(0.0, 1_500.0), RadarType.RED_LIGHT, 50, directionDeg = 0.0)
        val snaps = DrivingSession.replay(Sim.drive(listOf(0.0 to 0.0, 0.0 to 2_500.0), 25.0), DrivingSession(road, listOf(red)))
        val events = snaps.flatMap { it.events }
        assertNull(events.filterIsInstance<AlertEvent.Approach>().single().limitKmh)
        assertEquals(false, events.filterIsInstance<AlertEvent.Passed>().single().wouldFlash)
        assertTrue(events.none { it is AlertEvent.Brake })
    }

    @Test
    fun `rain can start during the drive`() {
        val session = DrivingSession(RoadNetwork(listOf(Sim.seg(1, 1, 2, 0.0 to -100.0, 0.0 to 6_000.0, oneway = true, limit = 130))), emptyList())
        val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 2_000.0), 30.0)
        val before = fixes.take(20).mapNotNull(session::process).last()
        session.profile = DriverProfile(raining = true)
        val after = fixes.drop(20).mapNotNull(session::process).last()
        assertEquals(130, before.limitKmh)
        assertEquals(110, after.limitKmh)
    }

    @Test
    fun `flash announcement tells the points at stake`() {
        val radar = Radar("R", Sim.ll(0.0, 1_500.0), RadarType.FIXED, 90, directionDeg = 0.0)
        val passed = DrivingSession.replay(Sim.drive(listOf(0.0 to 0.0, 0.0 to 2_500.0), 120 / 3.6), DrivingSession(road, listOf(radar)))
            .flatMap { it.events }.filterIsInstance<AlertEvent.Passed>().single()
        assertTrue(passed.wouldFlash)
        assertEquals(2, passed.points) // 120 measured → 114 retained, 24 over 90
    }
}
