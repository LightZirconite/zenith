package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertEngineTest {
    private val north = listOf(0.0 to 0.0, 0.0 to 2_500.0)

    private fun run(radars: List<Radar>, speedMps: Double, network: RoadNetwork? = null, profile: DriverProfile = DriverProfile()) =
        DrivingSession.replay(Sim.drive(north, speedMps), DrivingSession(network, radars, profile))

    private fun List<DriveSnapshot>.events() = flatMap { it.events }

    private val fixed90 = Radar("R1", Sim.ll(0.0, 1_500.0), RadarType.FIXED, limitKmh = 90, directionDeg = 0.0)

    @Test
    fun `radar ahead is announced once, at the speed-based distance, then passed`() {
        val events = run(listOf(fixed90), 25.0).events()
        val approach = events.filterIsInstance<AlertEvent.Approach>()
        assertEquals(1, approach.size)
        // 25 m/s: 20 s ahead + 208 m comfortable braking ≈ 708 m.
        assertEquals(708.0, approach.single().distanceM, 30.0)
        val passed = events.filterIsInstance<AlertEvent.Passed>().single()
        assertEquals("R1", passed.radar.id)
        assertEquals(false, passed.wouldFlash) // 90 measured → 85 retained
        assertTrue(events.none { it is AlertEvent.Brake })
    }

    @Test
    fun `next radar ahead is known long before its alert`() {
        val far = Radar("far", Sim.ll(0.0, 2_400.0), RadarType.FIXED, 90, directionDeg = 0.0)
        val first = run(listOf(far), 25.0).first()
        assertEquals("far", first.upcoming?.radar?.id)
        assertEquals(2_400.0, first.upcoming!!.distanceM, 5.0)
        assertTrue(first.alerts.isEmpty())
    }

    @Test
    fun `ignores radars for the other direction or off the road axis`() {
        val other = listOf(
            Radar("south", Sim.ll(0.0, 1_200.0), RadarType.FIXED, 90, directionDeg = 180.0),
            Radar("aside", Sim.ll(600.0, 1_000.0), RadarType.FIXED, 90),
        )
        assertTrue(run(other, 25.0).events().none { it is AlertEvent.Approach || it is AlertEvent.Passed })
    }

    @Test
    fun `too fast triggers brake warning and flash prediction`() {
        val events = run(listOf(fixed90), 30.0).events() // 108 km/h
        val brake = events.filterIsInstance<AlertEvent.Brake>().single()
        assertTrue(brake.distanceM in 60.0..200.0, "brake at ${brake.distanceM}")
        assertTrue(events.filterIsInstance<AlertEvent.Passed>().single().wouldFlash)
    }

    @Test
    fun `expired mobile report is ignored, fresh one is announced`() {
        val old = Radar("old", Sim.ll(0.0, 1_000.0), RadarType.MOBILE, 90, reportedAtMs = -4 * 3_600_000L)
        val fresh = Radar("fresh", Sim.ll(0.0, 2_000.0), RadarType.MOBILE, 90, reportedAtMs = -600_000L)
        val ids = run(listOf(old, fresh), 25.0).events().filterIsInstance<AlertEvent.Approach>().map { it.radar.id }
        assertEquals(listOf("fresh"), ids)
    }

    @Test
    fun `section radar computes the average speed`() {
        val start = Radar("S", Sim.ll(0.0, 500.0), RadarType.SECTION_START, 110, 0.0, sectionLengthM = 1_000.0)
        val end = Radar("E", Sim.ll(0.0, 1_500.0), RadarType.SECTION_END, 110, 0.0)
        val snaps = run(listOf(start, end), 120 / 3.6)
        val events = snaps.events()
        assertEquals(1, events.filterIsInstance<AlertEvent.SectionStarted>().size)
        val ended = events.filterIsInstance<AlertEvent.SectionEnded>().single()
        assertEquals(120.0, ended.averageKmh, 2.0)
        assertTrue(ended.wouldFlash) // 120 → 114 retained > 110
        val mid = snaps.mapNotNull { it.section }.first { it.remainingM < 600 }
        assertTrue(mid.maxSpeedToFinishKmh!! < 110)
    }

    @Test
    fun `same camera from two sources is announced once`() {
        val twin = Radar("R1-osm", Sim.ll(3.0, 1_600.0), RadarType.FIXED, limitKmh = 90, directionDeg = 0.0)
        val events = run(listOf(fixed90, twin), 30.0).events()
        assertEquals(1, events.filterIsInstance<AlertEvent.Approach>().size)
        assertEquals(1, events.filterIsInstance<AlertEvent.Passed>().size)
        assertEquals(1, events.filterIsInstance<AlertEvent.Brake>().size)
    }

    @Test
    fun `two different radars close together are both announced`() {
        val redLight = Radar("F1", Sim.ll(0.0, 1_600.0), RadarType.RED_LIGHT, directionDeg = 0.0)
        val ids = run(listOf(fixed90, redLight), 25.0).events().filterIsInstance<AlertEvent.Approach>().map { it.radar.id }
        assertEquals(setOf("R1", "F1"), ids.toSet())
    }

    @Test
    fun `section cameras never predict a flash on the spot`() {
        val start = Radar("S", Sim.ll(0.0, 500.0), RadarType.SECTION_START, 90, 0.0, sectionLengthM = 1_000.0)
        val end = Radar("E", Sim.ll(0.0, 1_500.0), RadarType.SECTION_END, 90, 0.0)
        val events = run(listOf(start, end), 40.0).events() // 144 km/h
        assertEquals(2, events.filterIsInstance<AlertEvent.Passed>().size)
        assertTrue(events.filterIsInstance<AlertEvent.Passed>().none { it.wouldFlash })
        assertTrue(events.filterIsInstance<AlertEvent.SectionEnded>().single().wouldFlash)
    }

    @Test
    fun `limit guessed from the place is shown but never spoken nor lent to a radar`() {
        val area = RoadSegment(1, 1, 2, listOf(Sim.ll(0.0, -100.0), Sim.ll(0.0, 3_000.0)), oneway = true,
            maxspeedForward = 80, limitSource = LimitSource.AREA)
        val bare = Radar("bare", Sim.ll(0.0, 1_500.0), RadarType.FIXED, directionDeg = 0.0)
        val snaps = run(listOf(bare), 100 / 3.6, RoadNetwork(listOf(area)))
        assertEquals(80, snaps.last().limitKmh)
        assertEquals(LimitSource.AREA, snaps.last().limitSource)
        val events = snaps.events()
        assertTrue(events.filterIsInstance<AlertEvent.Overspeed>().none { it.level == OverspeedLevel.WARN })
        assertEquals(null, events.filterIsInstance<AlertEvent.Approach>().single().limitKmh)
    }

    @Test
    fun `road limit follows rain rule and overspeed escalates`() {
        val network = RoadNetwork(listOf(Sim.seg(1, 1, 2, 0.0 to -100.0, 0.0 to 3_000.0, oneway = true, limit = 130)))
        val snaps = run(emptyList(), 120 / 3.6, network, DriverProfile(raining = true))
        assertEquals(110, snaps.last().limitKmh)
        val over = snaps.events().filterIsInstance<AlertEvent.Overspeed>()
        assertEquals(OverspeedLevel.WARN, over.last().level)
    }
}
