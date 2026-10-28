package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoDriveTest {
    private val demo = DemoDrive.load()
    private val snaps = DrivingSession.replay(demo.fixes, DrivingSession(demo.network, demo.radars, zones = demo.zones))
    private val events = snaps.flatMap { it.events }

    @Test
    fun `lasts about three minutes on the D 107`() {
        assertTrue(demo.fixes.size in 150..270, "${demo.fixes.size} s")
        val onRoad = snaps.drop(5).count { it.road?.segment?.name == "D 107" }
        assertTrue(onRoad >= (snaps.size - 5) * 0.9, "on D 107: $onRoad/${snaps.size}")
    }

    @Test
    fun `first radar is passed too fast, second one cleanly`() {
        assertTrue(events.any { it is AlertEvent.Overspeed && it.level == OverspeedLevel.WARN })
        assertEquals(listOf("demo-1", "demo-2"), events.filterIsInstance<AlertEvent.Approach>().map { it.radar.id }.take(2))
        assertTrue(events.any { it is AlertEvent.Brake && it.radar.id == "demo-1" })
        val passed = events.filterIsInstance<AlertEvent.Passed>().associate { it.radar.id to it.wouldFlash }
        assertEquals(mapOf("demo-1" to true, "demo-2" to false, "demo-3>start" to false, "demo-3" to false), passed)
        assertTrue(events.any { it is AlertEvent.LimitChanged && it.fromKmh == 70 && it.toKmh == 50 })
        assertTrue(snaps.any { it.limitAhead?.limitKmh == 50 })
    }

    @Test
    fun `control itinerary and a section without start camera`() {
        val entered = events.filterIsInstance<AlertEvent.ZoneEntered>().single()
        assertEquals(1_700.0, entered.remainingM, 120.0)
        assertTrue(snaps.any { it.zone != null })
        val approaches = events.filterIsInstance<AlertEvent.Approach>().map { it.radar.id }
        assertEquals(listOf("demo-1", "demo-2", "demo-3>start", "demo-3"), approaches)
        val started = events.filterIsInstance<AlertEvent.SectionStarted>().single()
        assertEquals(800.0, started.lengthM, 1.0)
        val ended = events.filterIsInstance<AlertEvent.SectionEnded>().single()
        assertTrue(!ended.wouldFlash && ended.averageKmh in 55.0..70.0, "average ${ended.averageKmh}")
    }

    @Test
    fun `next radar is shown on the road before its alert`() {
        assertTrue(snaps.any { it.upcoming?.radar?.id == "demo-1" && it.upcoming.roadName == "D 107" && it.alerts.isEmpty() })
    }
}
