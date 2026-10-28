package zenith.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real report: at home, not driving, Zenith announced the level crossing camera down the street.
 * A phone that does not travel along the road must announce nothing and show nothing as next; a
 * real drive past the same camera must still be announced.
 */
class StandstillTest {
    private val seeds = 1..12

    // Village street running north, a level crossing camera 150 m up the street.
    private val network = RoadNetwork(listOf(Sim.seg(1, 1, 2, 0.0 to -2_000.0, 0.0 to 2_000.0, limit = 50, roadClass = "residential")))
    private val crossing = Radar("pn", Sim.ll(0.0, 150.0), RadarType.LEVEL_CROSSING)
    private val fixed = Radar("fixed", Sim.ll(0.0, 250.0), RadarType.FIXED, 50)

    private fun session() = DrivingSession(network, listOf(crossing, fixed))

    /**
     * A phone lying still at ([x], [y]) for [seconds]: Gaussian noise of [noiseM], slow random drift
     * like indoor fixes, Doppler speed noise when [doppler].
     */
    private fun still(x: Double, y: Double, seconds: Int, noiseM: Double, seed: Int, doppler: Boolean, driftM: Double = 25.0): List<GpsFix> {
        val rnd = Random(seed)
        var dx = 0.0
        var dy = 0.0
        return (0 until seconds).map { t ->
            dx = (dx + rnd.nextDouble(-3.0, 3.0)).coerceIn(-driftM, driftM)
            dy = (dy + rnd.nextDouble(-3.0, 3.0)).coerceIn(-driftM, driftM)
            GpsFix(
                timeMs = t * 1_000L,
                position = Sim.ll(x + dx + rnd.nextDouble(-noiseM, noiseM), y + dy + rnd.nextDouble(-noiseM, noiseM)),
                accuracyM = noiseM + 5,
                speedMps = if (doppler) rnd.nextDouble(0.0, 1.5) else null,
                bearingDeg = if (doppler) rnd.nextDouble(0.0, 360.0) else null,
            )
        }
    }

    private fun List<DriveSnapshot>.silent(what: String) {
        val events = flatMap { it.events }
        assertTrue(events.none { it is AlertEvent.Approach || it is AlertEvent.Passed || it is AlertEvent.Brake }, "$what: $events")
        assertTrue(all { it.upcoming == null && it.alerts.isEmpty() }, "$what: radar shown")
    }

    @Test
    fun `phone at home next to the street announces nothing`() {
        for (seed in seeds) for (doppler in listOf(true, false)) for (noise in listOf(5.0, 15.0, 30.0)) {
            for (offset in listOf(12.0, 35.0)) {
                val fixes = still(offset, 0.0, 15 * 60, noise, seed, doppler)
                DrivingSession.replay(fixes, session()).silent("seed $seed doppler $doppler noise $noise offset $offset")
            }
        }
    }

    @Test
    fun `walking along the street towards the camera announces nothing`() {
        for (seed in seeds) {
            val fixes = Sim.drive(listOf(5.0 to -300.0, 5.0 to 400.0), 1.4, noiseM = 6.0, seed = seed)
            DrivingSession.replay(fixes, session()).silent("seed $seed")
        }
    }

    @Test
    fun `driving past the same cameras still announces them`() {
        for (seed in seeds) {
            val fixes = Sim.drive(listOf(0.0 to -1_000.0, 0.0 to 600.0), 50 / 3.6, noiseM = 5.0, seed = seed)
            val approached = DrivingSession.replay(fixes, session()).flatMap { it.events }
                .filterIsInstance<AlertEvent.Approach>().map { it.radar.id }
            assertEquals(listOf("pn", "fixed"), approached, "seed $seed")
        }
    }

    @Test
    fun `drive started a few meters before the camera still announces it`() {
        for (seed in seeds) {
            val fixes = Sim.drive(listOf(0.0 to -120.0, 0.0 to 600.0), 40 / 3.6, noiseM = 4.0, seed = seed)
            val approached = DrivingSession.replay(fixes, session()).flatMap { it.events }
                .filterIsInstance<AlertEvent.Approach>().map { it.radar.id }
            assertEquals(listOf("pn", "fixed"), approached, "seed $seed")
        }
    }

    @Test
    fun `parking near a camera hides it, driving off shows it again without a second announcement`() {
        for (seed in seeds) {
            val toward = Sim.drive(listOf(0.0 to -1_000.0, 0.0 to 0.0), 50 / 3.6, noiseM = 4.0, seed = seed)
            val end = toward.last().timeMs
            val parked = still(0.0, 0.0, 120, 4.0, seed, doppler = true, driftM = 3.0).map { it.copy(timeMs = end + 1_000 + it.timeMs) }
            val away = Sim.drive(listOf(0.0 to 0.0, 0.0 to 600.0), 50 / 3.6, noiseM = 4.0, seed = seed)
                .map { it.copy(timeMs = end + 122_000 + it.timeMs) }
            val snaps = DrivingSession.replay(toward + parked + away, session())
            val events = snaps.flatMap { it.events }
            assertEquals(listOf("pn", "fixed"), events.filterIsInstance<AlertEvent.Approach>().map { it.radar.id }, "seed $seed")
            assertTrue(snaps.last { it.timeMs < end + 120_000 }.alerts.isEmpty(), "seed $seed: still shown while parked")
            assertEquals(listOf("pn", "fixed"), events.filterIsInstance<AlertEvent.Passed>().map { it.radar.id }, "seed $seed")
        }
    }
}
