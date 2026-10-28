package zenith.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** False or missed alerts found by an adversarial review, each reproduced over several noise seeds. */
class AlertEdgeCasesTest {
    private val seeds = 1..8

    private fun replay(network: RoadNetwork?, radars: List<Radar>, fixes: List<GpsFix>, zones: List<ControlZone> = emptyList()) =
        DrivingSession.replay(fixes, DrivingSession(network, radars, zones = zones))

    private fun List<DriveSnapshot>.events() = flatMap { it.events }

    @Test
    fun `radar behind the car is not announced through the roundabout ahead`() {
        // Street north, a small one-way loop at y = 300, the street goes on north.
        val network = RoadNetwork(
            listOf(
                Sim.seg(1, 1, 3, 0.0 to -1_500.0, 0.0 to 300.0, limit = 50),
                Sim.seg(2, 3, 4, 0.0 to 300.0, 30.0 to 330.0, oneway = true, limit = 50),
                Sim.seg(3, 4, 5, 30.0 to 330.0, 0.0 to 360.0, -30.0 to 330.0, oneway = true, limit = 50),
                Sim.seg(4, 5, 3, -30.0 to 330.0, 0.0 to 300.0, oneway = true, limit = 50),
                Sim.seg(5, 3, 6, 0.0 to 300.0, 0.0 to 2_000.0, limit = 50),
            ),
        )
        // Measures southbound traffic only.
        val south = Radar("south", Sim.ll(0.0, 100.0), RadarType.FIXED, 50, directionDeg = 180.0)
        for (seed in seeds) {
            val events = replay(network, listOf(south), Sim.drive(listOf(0.0 to -1_000.0, 0.0 to 1_500.0), 14.0, noiseM = 4.0, seed = seed)).events()
            assertTrue(events.none { it is AlertEvent.Approach || it is AlertEvent.Passed }, "seed $seed: $events")
        }
    }

    @Test
    fun `turning off just before a radar is not a flash`() {
        // Junction at y = 0: the street goes on north to the radar 80 m further, the car turns east.
        val network = RoadNetwork(
            listOf(
                Sim.seg(1, 1, 2, 0.0 to -1_500.0, 0.0 to 0.0, limit = 50),
                Sim.seg(2, 2, 3, 0.0 to 0.0, 0.0 to 1_000.0, limit = 50),
                Sim.seg(3, 2, 4, 0.0 to 0.0, 1_000.0 to 0.0, limit = 50),
            ),
        )
        val radar = Radar("R", Sim.ll(0.0, 80.0), RadarType.FIXED, 50)
        for (seed in seeds) {
            val fixes = Sim.drive(listOf(0.0 to -1_000.0, 0.0 to 0.0, 600.0 to 0.0), 70 / 3.6, noiseM = 4.0, seed = seed)
            val events = replay(network, listOf(radar), fixes).events()
            assertTrue(events.none { it is AlertEvent.Passed }, "seed $seed: ${events.filterIsInstance<AlertEvent.Passed>()}")
        }
    }

    @Test
    fun `driving past the radar still reports it passed`() {
        val network = RoadNetwork(listOf(Sim.seg(1, 1, 2, 0.0 to -1_500.0, 0.0 to 1_500.0, limit = 50)))
        val radar = Radar("R", Sim.ll(0.0, 80.0), RadarType.FIXED, 50)
        for (seed in seeds) {
            val fixes = Sim.drive(listOf(0.0 to -1_000.0, 0.0 to 600.0), 70 / 3.6, noiseM = 4.0, seed = seed)
            val passed = replay(network, listOf(radar), fixes).events().filterIsInstance<AlertEvent.Passed>()
            assertEquals(1, passed.size, "seed $seed")
            assertTrue(passed.single().wouldFlash)
        }
    }

    @Test
    fun `a jam inside a section does not end it early`() {
        val network = RoadNetwork(listOf(Sim.seg(1, 1, 2, 0.0 to -500.0, 0.0 to 6_000.0, oneway = true, limit = 90)))
        val start = Radar("S", Sim.ll(0.0, 500.0), RadarType.SECTION_START, 90, 0.0, sectionLengthM = 3_000.0)
        val end = Radar("E", Sim.ll(0.0, 3_500.0), RadarType.SECTION_END, 90, 0.0)
        for (seed in seeds) {
            val rnd = Random(seed)
            val before = Sim.drive(listOf(0.0 to 0.0, 0.0 to 1_500.0), 25.0, noiseM = 4.0, seed = seed)
            val t0 = before.last().timeMs
            // Five minutes stopped, 6 m of noise, no speed.
            val jam = (1..300).map { s ->
                GpsFix(t0 + s * 1_000L, Sim.ll(rnd.nextDouble(-6.0, 6.0), 1_500.0 + rnd.nextDouble(-6.0, 6.0)), 6.0, 0.0, null)
            }
            val after = Sim.drive(listOf(0.0 to 1_500.0, 0.0 to 4_000.0), 25.0, noiseM = 4.0, seed = seed)
                .map { it.copy(timeMs = t0 + 301_000 + it.timeMs) }
            val snaps = replay(network, listOf(start, end), before + jam + after)
            val ended = snaps.first { s -> s.events.any { it is AlertEvent.SectionEnded } }
            assertTrue(Sim.frame.toXY(ended.position).y > 3_350, "seed $seed: ended at ${Sim.frame.toXY(ended.position).y}")
        }
    }

    @Test
    fun `control itinerary is not entered by a phone at home`() {
        val zone = ControlZone("Z", (0..80).map { Sim.ll(0.0, it * 50.0) })
        for (seed in seeds) for (noise in listOf(15.0, 30.0)) {
            val rnd = Random(seed)
            val fixes = (0 until 900).map { t ->
                GpsFix(t * 1_000L, Sim.ll(12 + rnd.nextDouble(-noise, noise), 2_000.0 + rnd.nextDouble(-noise, noise)), noise + 5)
            }
            val events = replay(null, emptyList(), fixes, listOf(zone)).events()
            assertTrue(events.none { it is AlertEvent.ZoneEntered }, "seed $seed noise $noise")
        }
    }

    @Test
    fun `poor announced accuracy still lets a real drive alert`() {
        val radar = Radar("R", Sim.ll(0.0, 1_500.0), RadarType.FIXED, 90, directionDeg = 0.0)
        for (seed in seeds) for (accuracy in listOf(35.0, 60.0)) {
            val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 2_000.0), 70 / 3.6, noiseM = 8.0, seed = seed).map { it.copy(accuracyM = accuracy) }
            val approached = replay(null, listOf(radar), fixes).events().filterIsInstance<AlertEvent.Approach>()
            assertEquals(listOf("R"), approached.map { it.radar.id }, "seed $seed accuracy $accuracy")
        }
    }

    @Test
    fun `a late fix during a section is ignored instead of failing`() {
        val tracker = SectionTracker(1_000.0, 90, 0)
        tracker.advance(100.0, 4_000)
        tracker.advance(30.0, 3_800)
        assertEquals(900.0, tracker.remainingM, 0.1)
    }
}
