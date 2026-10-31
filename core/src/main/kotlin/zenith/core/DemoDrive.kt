package zenith.core

/** A scripted drive: real road network, made-up radars and zones, and the GPS fixes of the drive. */
data class DemoScenario(
    val network: RoadNetwork,
    val radars: List<Radar>,
    val fixes: List<GpsFix>,
    val zones: List<ControlZone> = emptyList(),
)

/**
 * Hidden demo: about three and a half minutes on the real D 107 (Corsica, OSM tile bundled in
 * core). The driver first speeds past a radar (warning, brake, danger beeps, "too fast"), passes a
 * second one at the limit, enters a control itinerary, slows for the 50 village shown ahead, then
 * drives a section whose start camera is unknown (placed on the road from the end and its length).
 * Fixes go through the normal [DrivingSession].
 */
object DemoDrive {
    const val TILE = "/demo/roads_85_19.json"
    private const val ROAD = "D 107"
    private const val LENGTH_M = 4_150.0
    private const val FIRST_RADAR_M = 900.0
    private const val SECOND_AFTER_M = 900.0
    private const val ZONE_FROM_M = 1_900.0
    private const val ZONE_TO_M = 3_600.0
    private const val SECTION_END_M = 3_950.0
    private const val SECTION_LENGTH_M = 800.0
    private const val OVER_KMH = 18
    private const val ACCEL_MPS2 = 1.5
    private const val BRAKE_MPS2 = 2.0
    private const val DEFAULT_KMH = 70

    fun scenario(tileText: String): DemoScenario {
        val raw = RoadNetwork(RoadTiles.parse(tileText))
        val road = raw.segments.filter { it.name == ROAD }
        val start = road.firstOrNull { s -> raw.segmentsAt(s.fromNode).none { it.id != s.id && it.name == ROAD } }
            ?: error("no end of $ROAD in the demo tile")
        val from = raw.candidates(start.points.first(), 5.0).firstOrNull { it.segment.id == start.id && it.forward }
            ?: error("no start candidate on $ROAD")
        val path = RoadHorizon.mostProbablePath(raw, from, LENGTH_M).takeWhile { it.segment.name == ROAD }
        val line = Polyline(RoadHorizon.polyline(path, LENGTH_M))

        // Parts of the D 107 have no limit tag: the demo gives them the last known one, so the sign shows.
        var last: Int? = null
        val limits = path.associate { step -> step.segment.id to (step.limitKmh ?: last ?: DEFAULT_KMH).also { last = it } }
        val network = RoadNetwork(
            raw.segments.map { seg ->
                val fill = limits[seg.id]?.takeIf { seg.maxspeedForward == null && seg.maxspeedBackward == null }
                if (fill == null) seg else seg.copy(maxspeedForward = fill, maxspeedBackward = fill, limitSource = LimitSource.EXPLICIT)
            },
        )
        fun limitAt(d: Double): Int = limits[path.lastOrNull { it.startM <= d }?.segment?.id] ?: DEFAULT_KMH

        val secondM = (FIRST_RADAR_M + SECOND_AFTER_M).coerceAtMost(line.lengthM - 150)
        val endM = SECTION_END_M.coerceAtMost(line.lengthM - 100)
        val radars = listOf(
            Radar("demo-1", line.pointAt(FIRST_RADAR_M), RadarType.FIXED, limitAt(FIRST_RADAR_M), line.bearingAt(FIRST_RADAR_M)),
            Radar("demo-2", line.pointAt(secondM), RadarType.FIXED, limitAt(secondM), line.bearingAt(secondM)),
            // Section end only, with its length: the app places the start on the road.
            Radar("demo-3", line.pointAt(endM), RadarType.SECTION_END, limitAt(endM), line.bearingAt(endM), sectionLengthM = SECTION_LENGTH_M),
        )
        val zoneTo = ZONE_TO_M.coerceAtMost(line.lengthM)
        val zone = ControlZone("demo-zone", generateSequence(ZONE_FROM_M) { it + 25.0 }.takeWhile { it <= zoneTo }.map(line::pointAt).toList())

        val fixes = mutableListOf<GpsFix>()
        var s = 0.0
        var v = 40 / 3.6
        var t = 0L
        while (s < line.lengthM - 50) {
            // Too fast until just past the first radar, then a careful driver anticipating 300 m ahead.
            val targetKmh = if (s < FIRST_RADAR_M + 150) limitAt(s) + OVER_KMH else minOf(limitAt(s), limitAt(s + 300)) - 2
            v += (targetKmh / 3.6 - v).coerceIn(-BRAKE_MPS2, ACCEL_MPS2)
            fixes += GpsFix(t * 1_000, line.pointAt(s), accuracyM = 4.0, speedMps = v, bearingDeg = line.bearingAt(s))
            s += v
            t++
        }
        return DemoScenario(network, radars, fixes, listOf(zone))
    }
}
