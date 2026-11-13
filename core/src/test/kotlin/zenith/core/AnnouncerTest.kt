package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnouncerTest {
    private val radar = Radar("R", LatLon(45.0, 5.0), RadarType.FIXED, 90)

    @Test
    fun `spoken distances`() {
        assertEquals("50 mètres", Announcer.spokenDistance(10.0))
        assertEquals("700 mètres", Announcer.spokenDistance(708.0))
        assertEquals("1 kilomètre", Announcer.spokenDistance(1_020.0))
        assertEquals("1,5 kilomètre", Announcer.spokenDistance(1_480.0))
        assertEquals("12 kilomètres", Announcer.spokenDistance(12_000.0))
    }

    @Test
    fun `approach and passed texts`() {
        assertEquals("Radar fixe dans 700 mètres, limité à 90", Announcer.text(AlertEvent.Approach(0, radar, 708.0, 90)))
        assertNull(Announcer.text(AlertEvent.Passed(0, radar, 85.0, 90, wouldFlash = false)))
        assertEquals(
            "Radar passé à 108, flash probable, 1 point",
            Announcer.text(AlertEvent.Passed(0, radar, 108.0, 90, wouldFlash = true, points = 1)),
        )
    }

    @Test
    fun `only the audible overspeed level speaks`() {
        assertNull(Announcer.text(AlertEvent.Overspeed(0, OverspeedLevel.OVER, 51, 50)))
        assertEquals("Limite 50", Announcer.text(AlertEvent.Overspeed(0, OverspeedLevel.WARN, 56, 50)))
    }

    @Test
    fun `radars only keeps the radars and drops the rest`() {
        val only = AnnouncePrefs(speeding = false, passResult = false, zones = false)
        val approach = AlertEvent.Approach(0, radar, 500.0, 90)
        val brake = AlertEvent.Brake(0, radar, 200.0, 90)
        val over = AlertEvent.Overspeed(0, OverspeedLevel.WARN, 60, 50)
        val flash = AlertEvent.Passed(0, radar, 104.0, 90, wouldFlash = true, points = 1)
        val ended = AlertEvent.SectionEnded(0, 87.0, 90, wouldFlash = false)
        val zone = AlertEvent.ZoneEntered(0, ControlZone("Z", listOf(LatLon(45.0, 5.0), LatLon(45.1, 5.0))), 12_000.0)
        assertEquals(Cue.APPROACH, Announcer.plan(approach, only)?.cue)
        assertEquals(true, Announcer.plan(brake, only)?.urgent)
        for (e in listOf(over, flash, ended, zone)) {
            assertNull(Announcer.plan(e, only), "$e")
            assertEquals(e is AlertEvent.Overspeed || e is AlertEvent.Passed, Announcer.plan(e)?.urgent, "$e")
        }
        assertEquals("Limite 50", Announcer.plan(over)?.text)
        assertNull(Announcer.plan(AlertEvent.Passed(0, radar, 80.0, 90, wouldFlash = false)))
    }
}
