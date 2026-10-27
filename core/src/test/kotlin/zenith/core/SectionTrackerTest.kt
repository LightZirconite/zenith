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
}
