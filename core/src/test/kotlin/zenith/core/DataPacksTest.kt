package zenith.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataPacksTest {
    private val tileText = javaClass.getResource("/demo/roads_85_19.json")!!.readText()

    @Test
    fun `parses a radar pack and skips unknown types`() {
        val json = """
            {"v":1,"generated":"2026-10-27T00:00:00+00:00","radars":[
              {"id":"fr-479","lat":42.48027,"lon":9.48094,"type":"FIXED","limit":80,"dir":171.0,"src":"gouv+osm"},
              {"id":"sec-1","lat":45.0,"lon":5.0,"type":"SECTION_START","limit":110,"dir":10.5,"len":4200,"src":"osm"},
              {"id":"x","lat":45.0,"lon":5.0,"type":"UNKNOWN"},
              {"id":"m","lat":45.1,"lon":5.1,"type":"MOBILE","limit":90,"at":1793107800,"src":"lufop"},
              {"id":"f","lat":45.2,"lon":5.2,"type":"FIXED","limit":110,"at":1793107800,"src":"lufop"}
            ]}
        """.trimIndent()
        val radars = RadarPack.parse(json)
        assertEquals(4, radars.size)
        assertEquals(Radar("fr-479", LatLon(42.48027, 9.48094), RadarType.FIXED, 80, 171.0), radars[0])
        assertEquals(4200.0, radars[1].sectionLengthM)
        assertEquals(1_793_107_800_000L, radars[2].reportedAtMs)
        assertEquals(null, radars[3].reportedAtMs) // a date on a fixed radar is not a live report
    }

    @Test
    fun `parses control zones, absent from older packs`() {
        val json = """
            {"v":1,"radars":[],"zones":[
              {"id":"I_01_001","kind":"ITINERARY","pts":[46.18501,5.24796,46.17739,5.25551,46.17683,5.25595]},
              {"id":"odd","pts":[46.0,5.0,46.1]},
              {"id":"short","pts":[46.0,5.0]}
            ]}
        """.trimIndent()
        val zones = RadarPack.parseAll(json).zones
        assertEquals(listOf("I_01_001"), zones.map { it.id })
        assertEquals(LatLon(46.17739, 5.25551), zones.single().points[1])
        assertEquals(1_100.0, zones.single().lengthM, 50.0)
        assertTrue(RadarPack.parseAll("""{"v":1,"radars":[]}""").zones.isEmpty())
    }

    @Test
    fun `limit source 3 is the default of the place`() {
        val tile = """{"v":1,"segments":[[1000,1,2,0,80,80,3,4,"D 1",[45.0,3.0,45.0,3.01]]]}"""
        assertEquals(LimitSource.AREA, RoadTiles.parse(tile).single().limitSource)
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
