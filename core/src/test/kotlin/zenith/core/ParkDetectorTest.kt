package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParkDetectorTest {
    private val min = 60_000L

    /** Feeds one fix per second; returns the second at which the drive ends, or null. */
    private fun run(
        seconds: Int,
        detector: ParkDetector = ParkDetector(0),
        roadClass: String? = "residential",
        charging: Boolean = false,
        at: (Int) -> Pair<Double, Int>,
    ): Int? {
        for (t in 0 until seconds) {
            val (y, kmh) = at(t)
            if (detector.onFix(t * 1_000L, Sim.ll(0.0, y), kmh, roadClass, charging)) return t
        }
        return null
    }

    @Test
    fun `parked car ends the drive after ten minutes`() {
        // 2 minutes of driving, then parked (GPS noise around the spot, a few km/h now and then).
        val end = run(20 * 60) { t -> if (t < 120) t * 15.0 to 54 else 1_800.0 + (t % 7) to if (t % 9 == 0) 4 else 0 }
        // Counted from the last spot 300 m away, at most 20 s of driving before the stop.
        assertTrue(end!! in 120 + 9 * 60..120 + 10 * 60, "ended at $end")
    }

    @Test
    fun `walking away ends the drive, not while charging`() {
        val walk = { t: Int -> if (t < 120) t * 15.0 to 54 else 1_800.0 + (t - 120) * 1.4 to 5 }
        assertEquals((120 + 15 * 60).toDouble(), run(30 * 60, at = walk)!!.toDouble(), 2.0)
        assertEquals(null, run(30 * 60, charging = true, at = walk))
    }

    @Test
    fun `a slow jam keeps the drive`() {
        // 25 minutes crawling, with short pushes to 20 km/h every 3 minutes.
        assertEquals(null, run(25 * 60) { t -> t * 1.2 to if (t % 180 < 10) 20 else 4 })
        // A full stop on a motorway, however long.
        assertEquals(null, run(40 * 60, roadClass = "motorway") { 0.0 to 0 })
    }

    @Test
    fun `gps silence ends the drive, later after a fast last fix`() {
        val slow = ParkDetector(0)
        slow.onFix(0, Sim.ll(0.0, 0.0), 3, null, false)
        assertTrue(!slow.onSilence(9 * min) && slow.onSilence(10 * min))
        val fast = ParkDetector(0)
        fast.onFix(0, Sim.ll(0.0, 0.0), 110, "motorway", false)
        assertTrue(!fast.onSilence(29 * min) && fast.onSilence(30 * min))
        // Never a fix (started indoors).
        assertTrue(ParkDetector(0).onSilence(10 * min))
    }
}
