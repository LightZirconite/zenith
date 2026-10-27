package zenith.core

import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpxTest {
    @Test
    fun `reads points with namespaces and extensions`() {
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="urn:ext">
              <trk><trkseg>
                <trkpt lat="45.1" lon="5.2"><time>2026-10-27T10:00:01Z</time><extensions><x:speed>12.5</x:speed></extensions></trkpt>
                <trkpt lat="45.0" lon="5.0"><time>2026-10-27T10:00:00Z</time></trkpt>
                <trkpt lat="45.0" lon="5.0"></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val fixes = Gpx.read(xml.byteInputStream())
        assertEquals(2, fixes.size) // the point without time is dropped
        assertEquals(LatLon(45.0, 5.0), fixes[0].position)
        assertNull(fixes[0].speedMps)
        assertEquals(12.5, fixes[1].speedMps)
    }

    @Test
    fun `write then read round trip`() {
        val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 300.0), 15.0, noiseM = 2.0)
        val out = StringWriter()
        Gpx.write(fixes, out)
        assertEquals(fixes, Gpx.read(out.toString().byteInputStream()))
    }

    @Test
    fun `replaying a recorded trace gives the same alerts as live`() {
        val radar = Radar("R", Sim.ll(0.0, 800.0), RadarType.FIXED, 50, 0.0)
        val fixes = Sim.drive(listOf(0.0 to 0.0, 0.0 to 1_200.0), 20.0, noiseM = 4.0)
        val out = StringWriter()
        Gpx.write(fixes, out)
        val live = DrivingSession.replay(fixes, DrivingSession(null, listOf(radar))).flatMap { it.events }
        val replayed = DrivingSession.replay(Gpx.read(out.toString().byteInputStream()), DrivingSession(null, listOf(radar)))
            .flatMap { it.events }
        assertEquals(live, replayed)
        assertEquals(true, replayed.filterIsInstance<AlertEvent.Passed>().single().wouldFlash) // 72 km/h on 50
    }
}
