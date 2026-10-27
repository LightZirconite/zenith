package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataPacksTest {
    private val tileText = javaClass.getResource("/roads_85_19.json")!!.readText()

    @Test
    fun `parses a radar pack and skips unknown types`() {
        val json = """
            {"v":1,"generated":"2026-10-27T00:00:00+00:00","radars":[
              {"id":"fr-479","lat":42.48027,"lon":9.48094,"type":"FIXED","limit":80,"dir":171.0,"src":"gouv+osm"},
              {"id":"sec-1","lat":45.0,"lon":5.0,"type":"SECTION_START","limit":110,"dir":10.5,"len":4200,"src":"osm"},
              {"id":"x","lat":45.0,"lon":5.0,"type":"UNKNOWN"}
            ]}
        """.trimIndent()
        val radars = RadarPack.parse(json)
        assertEquals(2, radars.size)
        assertEquals(Radar("fr-479", LatLon(42.48027, 9.48094), RadarType.FIXED, 80, 171.0), radars[0])
        assertEquals(4200.0, radars[1].sectionLengthM)
    }

    @Test
    fun `parses a real road tile`() {
        val segments = RoadTiles.parse(tileText)
        assertEquals(138, segments.size)
        val borgo = segments.single { it.id == 376117621000 }
        assertEquals("Route des Marines de Borgo", borgo.name)
        assertEquals(70, borgo.limitKmh(true))
        assertEquals(LimitSource.EXPLICIT, borgo.limitSource)
        assertEquals("tertiary", borgo.roadClass)
    }

    @Test
    fun `tile keys around a point`() {
        assertEquals(RoadTiles.Key(85, 19), RoadTiles.keyOf(LatLon(42.6, 9.5)))
        // 42.99 is 1.1 km from the 43.0 edge: a 3 km margin also needs the tile above.
        assertEquals(setOf(RoadTiles.Key(85, 19), RoadTiles.Key(86, 19)), RoadTiles.keysAround(LatLon(42.99, 9.7), 3_000.0))
    }

    @Test
    fun `drive on a real road reads its real limit`() {
        val network = RoadNetwork(RoadTiles.parse(tileText))
        val road = network.segments.single { it.id == 376117621000 }
        val frame = LocalFrame(road.points.first())
        val path = road.points.map { frame.toXY(it) }.map { it.x to it.y }
        val snaps = DrivingSession.replay(Sim.drive(path, 20.0, noiseM = 4.0, frame = frame), DrivingSession(network, emptyList()))
            .drop(5)
        val onRoad = snaps.count { it.road?.segment?.id == road.id && it.limitKmh == 70 }
        assertTrue(onRoad >= snaps.size * 0.9, "on road with 70: $onRoad/${snaps.size}")
    }
}
