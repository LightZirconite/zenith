package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SectionWithoutStartTest {
    private val seeds = 1..5

    // Northbound motorway carriageway; the section ends at y = 5000 and is 3 km long.
    private val motorway = RoadNetwork(
        listOf(
            RoadSegment(1, 1, 2, listOf(Sim.ll(0.0, -100.0), Sim.ll(0.0, 2_500.0)), oneway = true, maxspeedForward = 130,
                roadClass = "motorway", name = "A 7"),
            RoadSegment(2, 2, 3, listOf(Sim.ll(0.0, 2_500.0), Sim.ll(0.0, 7_000.0)), oneway = true, maxspeedForward = 130,
                roadClass = "motorway", name = "A 7"),
        ),
    )
    private val end = Radar("E", Sim.ll(2.0, 5_000.0), RadarType.SECTION_END, 110, sectionLengthM = 3_000.0)

    private fun drive(network: RoadNetwork, fromY: Double, seed: Int) = DrivingSession.replay(
        Sim.drive(listOf(0.0 to fromY, 0.0 to 5_600.0), 120 / 3.6, noiseM = 4.0, seed = seed),
        DrivingSession(network, listOf(end)),
    )

    @Test
    fun `start is placed on the road behind the end and the average is tracked`() {
        for (seed in seeds) {
            val snaps = drive(motorway, 0.0, seed)
            val events = snaps.flatMap { it.events }
            val approach = events.filterIsInstance<AlertEvent.Approach>()
            assertEquals(listOf(RadarType.SECTION_START, RadarType.SECTION_END), approach.map { it.radar.type }, "seed $seed")
            assertEquals(2_000.0, Sim.frame.toXY(approach.first().radar.position).y, 15.0)
            val started = events.filterIsInstance<AlertEvent.SectionStarted>().single()
            assertEquals(3_000.0, started.lengthM, 1.0)
            assertTrue(!started.resumed)
            val ended = events.filterIsInstance<AlertEvent.SectionEnded>().single()
            assertEquals(120.0, ended.averageKmh, 3.0, "seed $seed")
            assertTrue(ended.wouldFlash)
            // Known as the next radar long before: the virtual start, then the end.
            val early = snaps.drop(5).first()
            assertEquals(RadarType.SECTION_START, early.upcoming?.radar?.type, "seed $seed")
        }
    }

    @Test
    fun `section already under way is picked up`() {
        for (seed in seeds) {
            val events = drive(motorway, 3_000.0, seed).flatMap { it.events }
            val started = events.filterIsInstance<AlertEvent.SectionStarted>().single()
            assertTrue(started.resumed, "seed $seed")
            assertEquals(2_000.0, started.lengthM, 60.0)
            assertTrue(events.filterIsInstance<AlertEvent.Approach>().none { it.radar.type == RadarType.SECTION_START })
            assertEquals(1, events.filterIsInstance<AlertEvent.SectionEnded>().size)
        }
    }

    @Test
    fun `no virtual section without a known direction`() {
        val twoWay = RoadNetwork(
            listOf(RoadSegment(1, 1, 2, listOf(Sim.ll(0.0, -100.0), Sim.ll(0.0, 7_000.0)), maxspeedForward = 110, maxspeedBackward = 110)),
        )
        for (seed in seeds) {
            val events = drive(twoWay, 0.0, seed).flatMap { it.events }
            assertTrue(events.none { it is AlertEvent.SectionStarted }, "seed $seed")
            // The end camera itself is still announced.
            assertEquals(listOf("E"), events.filterIsInstance<AlertEvent.Approach>().map { it.radar.id })
        }
    }
}
