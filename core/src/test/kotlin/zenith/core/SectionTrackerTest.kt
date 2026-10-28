package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SectionTrackerTest {
    // 10 km at 110 km/h: minimum legal time is 327.27 s.
    private fun tracker() = SectionTracker(lengthM = 10_000.0, limitKmh = 110, entryTimeMs = 0)

    @Test
    fun `average speed from distance and time`() {
        val t = tracker()
        t.advance(5_000.0, 150_000) // 5 km in 150 s = 120 km/h
        assertEquals(120.0, t.averageKmh(), 1e-9)
        assertEquals(5_000.0, t.remainingM, 1e-9)
    }

    @Test
    fun `too fast at start forces a slower finish`() {
        val t = tracker()
        t.advance(5_000.0, 150_000)
        // 177.27 s left for 5 km -> 101.54 km/h
        assertEquals(101.54, t.maxSpeedToFinishKmh()!!, 0.01)
    }

    @Test
    fun `slow start allows a faster finish`() {
        val t = tracker()
        t.advance(5_000.0, 200_000) // 90 km/h so far
        // 127.27 s left for 5 km -> 141.43 km/h
        assertEquals(141.43, t.maxSpeedToFinishKmh()!!, 0.01)
    }

    @Test
    fun `no constraint once enough time has elapsed or section is done`() {
        val slow = tracker().apply { advance(1_000.0, 400_000) }
        assertNull(slow.maxSpeedToFinishKmh())
        val done = tracker().apply { advance(10_000.0, 330_000) }
        assertNull(done.maxSpeedToFinishKmh())
    }

    @Test
    fun `advice never goes above the limit`() {
        // Plenty of time left: hold the limit, not the faster speed the average would allow.
        assertEquals(70, SectionStatus(70, 50.0, 500.0, 95.0, 1_000.0).targetKmh)
        assertEquals(70, SectionStatus(70, 50.0, 500.0, null, 1_000.0).targetKmh)
        // Too fast so far: slower than the limit until the end.
        val behind = SectionStatus(70, 80.0, 500.0, 61.7, 1_000.0)
        assertEquals(61, behind.targetKmh)
        assertEquals(false, behind.lost)
        assertEquals(true, SectionStatus(70, 120.0, 100.0, 12.0, 1_000.0).lost)
    }
}
