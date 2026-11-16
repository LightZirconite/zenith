package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlideTest {
    private fun snap(lat: Double, speedKmh: Int?, heading: Double? = 0.0, ahead: List<LatLon> = emptyList()) = DriveSnapshot(
        timeMs = 0, position = LatLon(lat, 2.0), speedKmh = speedKmh, gpsAccuracyM = 5.0, headingDeg = heading, road = null,
        limitKmh = null, limitSource = null, alerts = emptyList(), section = null, upcoming = null, limitAhead = null,
        ahead = ahead, events = emptyList(),
    )

    @Test
    fun `between two fixes the arrow moves on at the car's speed`() {
        val g = Glide()
        g.fix(snap(48.0, 72), 0)
        val half = assertNotNull(g.at(500))
        // 72 km/h = 20 m/s: 10 m north after half a second.
        assertEquals(10.0, Geo.distanceM(LatLon(48.0, 2.0), half.position), 0.3)
        assertTrue(half.position.lat > 48.0)
        // Never more than 1.8 s ahead of the last fix.
        assertEquals(36.0, Geo.distanceM(LatLon(48.0, 2.0), g.at(10_000)!!.position), 0.5)
    }

    @Test
    fun `it follows the road and stays put when stopped`() {
        val east = LatLon(48.0, 2.001)
        val g = Glide()
        g.fix(snap(48.0, 36, heading = 0.0, ahead = listOf(LatLon(48.0, 2.0), east)), 0)
        val p = g.at(1_000)!!
        assertEquals(10.0, Geo.distanceM(LatLon(48.0, 2.0), p.position), 0.3)
        assertEquals(48.0, p.position.lat, 1e-9)
        assertEquals(90.0, p.headingDeg!!, 0.5)
        val stopped = Glide()
        stopped.fix(snap(48.0, 2), 0)
        assertEquals(LatLon(48.0, 2.0), stopped.at(1_000)!!.position)
    }

    @Test
    fun `a new fix never makes the arrow jump`() {
        val g = Glide()
        g.fix(snap(48.0, 72), 0)
        val before = g.at(1_000)!!.position
        // The fix lands 8 m behind the prediction: the arrow starts where it was, then catches up.
        val behind = LatLon(before.lat - 8 / 111_320.0, 2.0)
        g.fix(snap(behind.lat, 72), 1_000)
        assertEquals(0.0, Geo.distanceM(before, g.at(1_000)!!.position), 0.05)
        val settled = g.at(1_000 + Glide.BLEND_MS)!!.position
        assertEquals(0.6 * 20, Geo.distanceM(behind, settled), 0.3)
    }
}
