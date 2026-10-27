package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadHorizonTest {
    private val seeds = 1..10

    private fun drive(network: RoadNetwork, radars: List<Radar>, path: List<Pair<Double, Double>>, speedMps: Double, seed: Int) =
        DrivingSession.replay(Sim.drive(path, speedMps, noiseM = 4.0, seed = seed), DrivingSession(network, radars))

    private fun List<DriveSnapshot>.approached() = flatMap { it.events }.filterIsInstance<AlertEvent.Approach>().map { it.radar.id }

    private fun named(id: Long, from: Long, to: Long, name: String, roadClass: String, oneway: Boolean, limit: Int, vararg xy: Pair<Double, Double>) =
        RoadSegment(id, from, to, xy.map { Sim.ll(it.first, it.second) }, oneway, limit, limit, roadClass = roadClass, name = name)

    // Two unconnected roads 300 m apart, both running north.
    private val parallel = RoadNetwork(
        listOf(
            named(1, 1, 2, "D 1", "primary", false, 90, 0.0 to 0.0, 0.0 to 5_000.0),
            named(2, 3, 4, "D 2", "primary", false, 90, 300.0 to 0.0, 300.0 to 5_000.0),
        ),
    )

    @Test
    fun `radar on a parallel road is neither next nor announced`() {
        val aside = Radar("aside", Sim.ll(300.0, 2_000.0), RadarType.FIXED, 90)
        val mine = Radar("mine", Sim.ll(0.0, 3_500.0), RadarType.FIXED, 90, directionDeg = 0.0)
        for (seed in seeds) {
            val snaps = drive(parallel, listOf(aside, mine), listOf(0.0 to 0.0, 0.0 to 4_000.0), 25.0, seed)
            assertTrue(snaps.none { it.upcoming?.radar?.id == "aside" }, "seed $seed: aside shown as next")
            assertEquals(listOf("mine"), snaps.approached(), "seed $seed")
            val early = snaps.drop(6).first()
            assertEquals("mine", early.upcoming?.radar?.id, "seed $seed")
            assertEquals("D 1", early.upcoming?.roadName)
            val travelled = Sim.frame.toXY(early.position).y
            assertEquals(3_500.0 - travelled, early.upcoming!!.distanceM, 25.0)
        }
    }

    // Northbound motorway A 7 with an exit ramp leaving east at y = 1000.
    private val motorway = RoadNetwork(
        listOf(
            named(1, 1, 2, "A 7", "motorway", true, 130, 0.0 to 0.0, 0.0 to 1_000.0),
            named(2, 2, 3, "A 7", "motorway", true, 130, 0.0 to 1_000.0, 0.0 to 6_000.0),
            RoadSegment(
                3, 2, 4, listOf(0.0 to 1_000.0, 40.0 to 1_300.0, 300.0 to 1_800.0, 800.0 to 2_200.0).map { Sim.ll(it.first, it.second) },
                oneway = true, maxspeedForward = 70, roadClass = "motorway_link",
            ),
        ),
    )
    private val rampRadar = Radar("ramp", Sim.ll(300.0, 1_800.0), RadarType.FIXED, 70)
    private val motorwayRadar = Radar("a7", Sim.ll(0.0, 4_000.0), RadarType.FIXED, 130)

    @Test
    fun `exit ramp radar stays silent for drivers staying on the motorway`() {
        for (seed in seeds) {
            val snaps = drive(motorway, listOf(rampRadar, motorwayRadar), listOf(0.0 to 0.0, 0.0 to 5_000.0), 30.0, seed)
            assertTrue(snaps.drop(6).all { it.upcoming == null || it.upcoming.radar.id == "a7" }, "seed $seed")
            assertEquals(listOf("a7"), snaps.approached(), "seed $seed")
        }
    }

    @Test
    fun `exit ramp radar is announced once on the ramp`() {
        val path = listOf(0.0 to 0.0, 0.0 to 1_000.0, 40.0 to 1_300.0, 300.0 to 1_800.0, 800.0 to 2_200.0)
        for (seed in seeds) {
            val snaps = drive(motorway, listOf(rampRadar, motorwayRadar), path, 25.0, seed)
            assertEquals(listOf("ramp"), snaps.approached(), "seed $seed")
            assertEquals(listOf("ramp"), snaps.flatMap { it.events }.filterIsInstance<AlertEvent.Passed>().map { it.radar.id }, "seed $seed")
        }
    }

    // Dual carriageway: northbound at x = 0, southbound at x = 25.
    private val dual = RoadNetwork(
        listOf(
            named(1, 1, 2, "N 7", "trunk", true, 110, 0.0 to 0.0, 0.0 to 4_000.0),
            named(2, 3, 4, "N 7", "trunk", true, 110, 25.0 to 4_000.0, 25.0 to 0.0),
        ),
    )

    @Test
    fun `radar without direction takes the direction of its one-way carriageway`() {
        val radar = Radar("east", Sim.ll(3.0, 2_000.0), RadarType.FIXED, 110)
        assertEquals(listOf(1L to true), RadarSnapper(dual).snap(radar).map { it.segment.id to it.forward })
        for (seed in seeds) {
            val north = drive(dual, listOf(radar), listOf(0.0 to 0.0, 0.0 to 3_000.0), 30.0, seed)
            assertEquals(listOf("east"), north.approached(), "seed $seed northbound")
            val south = drive(dual, listOf(radar), listOf(25.0 to 4_000.0, 25.0 to 500.0), 30.0, seed)
            assertTrue(south.approached().isEmpty(), "seed $seed southbound")
        }
    }

    @Test
    fun `radar between two carriageways alerts both ways`() {
        val radar = Radar("middle", Sim.ll(12.5, 2_000.0), RadarType.FIXED, 110)
        assertEquals(2, RadarSnapper(dual).snap(radar).size)
        for (seed in seeds) {
            assertEquals(listOf("middle"), drive(dual, listOf(radar), listOf(0.0 to 0.0, 0.0 to 3_000.0), 30.0, seed).approached())
            assertEquals(listOf("middle"), drive(dual, listOf(radar), listOf(25.0 to 4_000.0, 25.0 to 500.0), 30.0, seed).approached())
        }
    }

    @Test
    fun `radar off every road keeps the straight-line alert`() {
        val lost = Radar("lost", Sim.ll(60.0, 2_000.0), RadarType.FIXED, 90)
        for (seed in seeds) {
            assertEquals(listOf("lost"), drive(parallel, listOf(lost), listOf(0.0 to 0.0, 0.0 to 2_500.0), 25.0, seed).approached())
        }
    }

    @Test
    fun `next limit change is read on the road ahead`() {
        val network = RoadNetwork(
            listOf(
                named(1, 1, 2, "D 1", "primary", false, 90, 0.0 to 0.0, 0.0 to 3_000.0),
                named(2, 2, 3, "D 1", "primary", false, 50, 0.0 to 3_000.0, 0.0 to 4_000.0),
            ),
        )
        for (seed in seeds) {
            val snaps = drive(network, emptyList(), listOf(0.0 to 0.0, 0.0 to 3_500.0), 25.0, seed)
            val before = snaps.first { Sim.frame.toXY(it.position).y in 2_000.0..2_100.0 }
            assertEquals(50, before.limitAhead?.limitKmh, "seed $seed")
            assertEquals(3_000.0 - Sim.frame.toXY(before.position).y, before.limitAhead!!.distanceM, 25.0)
            assertNull(snaps.first { Sim.frame.toXY(it.position).y in 500.0..900.0 }.limitAhead, "seed $seed: too far")
        }
    }

    @Test
    fun `horizon stops at a fork without an obvious continuation`() {
        // Y split into two unnamed residential streets at ±30°.
        val network = RoadNetwork(
            listOf(
                Sim.seg(1, 1, 2, 0.0 to 0.0, 0.0 to 500.0, roadClass = "residential"),
                Sim.seg(2, 2, 3, 0.0 to 500.0, -300.0 to 1_000.0, roadClass = "residential"),
                Sim.seg(3, 2, 4, 0.0 to 500.0, 300.0 to 1_000.0, roadClass = "residential"),
            ),
        )
        val from = network.candidates(Sim.ll(0.0, 100.0), 10.0).single { it.forward }
        assertEquals(listOf(1L), RoadHorizon.mostProbablePath(network, from, 5_000.0).map { it.segment.id })
        assertEquals(setOf(1L, 2L, 3L), RoadHorizon.reachable(network, from, 5_000.0).keys.map { it.first }.toSet())
    }

    @Test
    fun `road ahead polyline starts at the car and follows the path`() {
        val network = RoadNetwork(
            listOf(
                named(1, 1, 2, "D 1", "primary", false, 90, 0.0 to 0.0, 0.0 to 1_000.0),
                named(2, 2, 3, "D 1", "primary", false, 90, 0.0 to 1_000.0, 500.0 to 1_000.0),
            ),
        )
        val from = network.candidates(Sim.ll(0.0, 400.0), 5.0).single { it.forward }
        val line = RoadHorizon.polyline(RoadHorizon.mostProbablePath(network, from, 5_000.0), 900.0).map(Sim.frame::toXY)
        assertEquals(400.0, line.first().y, 1.0)
        assertEquals(0.0, line.first().x, 1.0)
        // 600 m up to the corner, then 300 m east.
        assertEquals(300.0, line.last().x, 2.0)
        assertEquals(1_000.0, line.last().y, 2.0)
        val length = line.zipWithNext { a, b -> (b - a).length() }.sum()
        assertEquals(900.0, length, 3.0)
    }

    @Test
    fun `horizon follows a real departmental road`() {
        val network = RoadNetwork(RoadTiles.parse(javaClass.getResource("/roads_85_19.json")!!.readText()))
        val d107 = network.segments.filter { it.name == "D 107" }
        val start = d107.first { s -> network.segmentsAt(s.fromNode).none { it.id != s.id && it.name == "D 107" } }
        val from = network.candidates(start.points.first(), 5.0).single { it.segment.id == start.id && it.forward }
        val path = RoadHorizon.mostProbablePath(network, from, 50_000.0)
        val onD107 = path.takeWhile { it.segment.name == "D 107" }
        assertTrue(onD107.size >= d107.size / 2, "followed ${onD107.size} of ${d107.size} D 107 segments")
        path.zipWithNext { a, b -> assertEquals(a.endM, b.startM, 1e-6) }
    }

    @Test
    fun `overspeed warning is not repeated while hovering around the tolerance`() {
        val network = RoadNetwork(listOf(Sim.seg(1, 1, 2, 0.0 to -100.0, 0.0 to 10_000.0, oneway = true, limit = 80)))
        val engine = AlertEngine(RadarIndex(emptyList()))
        fun at(t: Int, kmh: Double): List<AlertEvent> {
            val y = t * 23.0
            val road = network.candidates(Sim.ll(0.0, y), 5.0).single()
            return engine.step(KinematicState(t * 1_000L, Sim.ll(0.0, y), kmh / 3.6, 0.0), road, network).events
        }
        var t = 0
        val warns = mutableListOf<Int>()
        fun phase(seconds: Int, speed: (Int) -> Double) = repeat(seconds) {
            if (at(t, speed(t)).any { it is AlertEvent.Overspeed && it.level == OverspeedLevel.WARN }) warns += t
            t++
        }
        phase(20) { if (it % 2 == 0) 84.0 else 82.0 } // hovering: one warning
        phase(15) { 70.0 } // calm down
        phase(3) { 86.0 } // new excess: new warning
        assertEquals(2, warns.size, "warnings at $warns")
        assertNotNull(warns.firstOrNull { it >= 35 })
    }
}
