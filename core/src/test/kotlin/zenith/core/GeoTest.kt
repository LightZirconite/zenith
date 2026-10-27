package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    private val paris = LatLon(48.8566, 2.3522)
    private val lyon = LatLon(45.7640, 4.8357)

    @Test
    fun `distance Paris to Lyon is about 392 km`() {
        assertEquals(391_500.0, Geo.distanceM(paris, lyon), 1_500.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, Geo.distanceM(paris, paris), 1e-9)
    }

    @Test
    fun `bearing due north and due east`() {
        assertEquals(0.0, Geo.bearingDeg(LatLon(45.0, 5.0), LatLon(46.0, 5.0)), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(LatLon(0.0, 5.0), LatLon(0.0, 6.0)), 1e-6)
    }

    @Test
    fun `heading difference wraps around north`() {
        assertEquals(20.0, Geo.headingDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.headingDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(5.0, Geo.headingDiffDeg(-5.0, 0.0), 1e-9)
    }
}
