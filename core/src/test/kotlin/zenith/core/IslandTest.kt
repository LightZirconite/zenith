package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IslandTest {
    @Test
    fun `the most urgent thing wins`() {
        assertEquals(Island.State.SLOW_DOWN, Island.state(alert = true, overLimit = true, section = true, passedAgoMs = 100))
        assertEquals(Island.State.APPROACH, Island.state(alert = true, overLimit = false, section = true, passedAgoMs = 100))
        assertEquals(Island.State.SECTION, Island.state(alert = false, overLimit = false, section = true, passedAgoMs = 100))
        assertEquals(Island.State.PASSED, Island.state(alert = false, overLimit = false, section = false, passedAgoMs = 100))
        assertEquals(Island.State.NONE, Island.state(alert = false, overLimit = false, section = false, passedAgoMs = Island.PASSED_MS))
        assertEquals(Island.State.NONE, Island.state(alert = false, overLimit = false, section = false, passedAgoMs = -1))
    }

    @Test
    fun `approach progress and the words under Ralentis`() {
        assertEquals(0f, Island.progress(500.0, 500.0))
        assertEquals(0.5f, Island.progress(250.0, 500.0))
        assertEquals(1f, Island.progress(0.0, 500.0))
        assertEquals(0f, Island.progress(600.0, 500.0))
        assertEquals("18 km/h au-dessus", Island.overText(88, 70))
        assertNull(Island.overText(70, 70))
        assertNull(Island.overText(null, 70))
    }

    @Test
    fun `the limit tick never sits at the end of the ring`() {
        for (limit in listOf(20, 30, 50, 70, 80, 90, 110, 130)) {
            val tick = Gauge.tick(limit)!!
            assertTrue(tick in 0.1f..0.81f, "limit $limit: $tick")
        }
        assertNull(Gauge.tick(null))
        assertEquals(0f, Gauge.fill(null, 70))
        assertEquals(1f, Gauge.fill(250, 130))
        assertEquals(0.5f, Gauge.fill(65, 50), 0.001f)
    }
}

class DaylightTest {
    private val paris = LatLon(48.857, 2.352)

    @Test
    fun `day and night in Paris`() {
        // 2026-06-21 12:00 UTC: high summer sun.
        assertTrue(Daylight.sunHeightDeg(paris, 1_782_043_200_000) > 60)
        // 2026-11-02 17:44 UTC (18:44 in Paris): dark.
        assertTrue(Daylight.isNight(paris, 1_793_641_440_000))
        // 2026-11-02 12:00 UTC: day, sun low (about 26°).
        val noon = Daylight.sunHeightDeg(paris, 1_793_620_800_000)
        assertTrue(noon in 22.0..30.0, "noon sun $noon")
        // 2026-12-21 00:00 UTC: deep night.
        assertTrue(Daylight.sunHeightDeg(paris, 1_797_811_200_000) < -50)
    }

    @Test
    fun `midnight sun and polar night`() {
        val tromso = LatLon(69.65, 18.96)
        // 2026-06-21 23:00 UTC: the sun stays up.
        assertTrue(!Daylight.isNight(tromso, 1_782_082_800_000))
        // 2026-12-21 22:00 UTC: night.
        assertTrue(Daylight.isNight(tromso, 1_797_890_400_000))
    }
}

class TripStatsTest {
    @Test
    fun `adds up a drive, leaves out jumps and standing noise`() {
        val t = TripStats()
        var p = LatLon(45.0, 5.0)
        // Standing still for a minute with GPS noise: nothing counted.
        for (i in 0 until 60) t.add(i * 1_000L, LatLon(45.0 + (i % 3) * 0.00002, 5.0), 0, 0)
        // Then 20 minutes at 72 km/h (20 m/s) going north.
        for (i in 0 until 1_200) {
            p = LatLon(p.lat + 20.0 / 111_195.0, p.lon)
            t.add(60_000L + i * 1_000L, p, if (i == 600) 92 else 72, if (i % 300 == 0) 1 else 0)
        }
        // A GPS jump of 5 km: left out.
        t.add(1_261_000, LatLon(p.lat + 0.045, p.lon), 72, 0)
        val s = t.summary()!!
        assertEquals(24_000.0, s.distanceM, 100.0)
        assertEquals(92, s.maxKmh)
        assertEquals(4, s.radarsPassed)
        assertEquals("24 km · 21 min", s.headline())
        assertEquals("4 radars passés · 92 km/h au plus", s.details())
    }

    @Test
    fun `too short a drive says nothing`() {
        val t = TripStats()
        t.add(0, LatLon(45.0, 5.0), 10, 0)
        t.add(10_000, LatLon(45.001, 5.0), 10, 0)
        assertNull(t.summary())
        assertNull(TripStats().summary())
    }

    @Test
    fun `short and long texts`() {
        assertEquals("3,4 km · 7 min", TripSummary(3_420.0, 7 * 60_000L, 50, 0).headline())
        assertEquals("aucun radar · 50 km/h au plus", TripSummary(3_420.0, 7 * 60_000L, 50, 0).details())
        assertEquals("1 radar passé · 50 km/h au plus", TripSummary(3_420.0, 7 * 60_000L, 50, 1).details())
        assertEquals("130 km · 1 h 05", TripSummary(130_000.0, 65 * 60_000L, 131, 2).headline())
    }
}
