package zenith.core

/** How a speed limit was obtained, from most to least trustworthy. */
enum class LimitSource { EXPLICIT, IMPLICIT, INFERRED }

/** A road piece between two graph nodes. Forward = from [fromNode] to [toNode]. */
data class RoadSegment(
    val id: Long,
    val fromNode: Long,
    val toNode: Long,
    val points: List<LatLon>,
    val oneway: Boolean = false,
    val maxspeedForward: Int? = null,
    val maxspeedBackward: Int? = null,
    val limitSource: LimitSource = LimitSource.EXPLICIT,
    /** OSM highway class (motorway, motorway_link, primary…), used to prefer staying on the same road. */
    val roadClass: String? = null,
) {
    init {
        require(points.size >= 2) { "segment $id needs at least 2 points" }
    }

    val lengthM: Double = points.zipWithNext { a, b -> Geo.distanceM(a, b) }.sum()

    fun limitKmh(forward: Boolean): Int? =
        if (forward) maxspeedForward ?: maxspeedBackward else maxspeedBackward ?: maxspeedForward
}

/** A segment travelled in a given direction, with the vehicle projected onto it. */
data class RoadCandidate(
    val segment: RoadSegment,
    val forward: Boolean,
    /** Distance from the start of travel on this segment to the projected point, meters. */
    val offsetM: Double,
    /** Distance from the fix to the projected point, meters. */
    val distanceM: Double,
    /** Travel direction at the projected point, degrees. */
    val bearingDeg: Double,
    val point: LatLon,
) {
    val entryNode: Long get() = if (forward) segment.fromNode else segment.toNode
    val exitNode: Long get() = if (forward) segment.toNode else segment.fromNode
    val remainingM: Double get() = segment.lengthM - offsetM
    val limitKmh: Int? get() = segment.limitKmh(forward)
}

class RoadNetwork(segments: Collection<RoadSegment>) {
    private val index = GridIndex<RoadSegment>()
    private val byNode = HashMap<Long, MutableList<RoadSegment>>()
    val segments: List<RoadSegment> = segments.toList()

    init {
        for (s in this.segments) {
            index.insert(BBox.of(s.points), s)
            byNode.getOrPut(s.fromNode) { mutableListOf() }.add(s)
            byNode.getOrPut(s.toNode) { mutableListOf() }.add(s)
        }
    }

    /** Segments touching [node]. */
    fun segmentsAt(node: Long): List<RoadSegment> = byNode[node].orEmpty()

    /** Directed segments leaving [node] that may legally be driven. */
    fun departuresFrom(node: Long): List<Pair<RoadSegment, Boolean>> = segmentsAt(node).mapNotNull { s ->
        when {
            s.fromNode == node -> s to true
            s.toNode == node && !s.oneway -> s to false
            else -> null
        }
    }

    /** Every drivable direction of every segment within [radiusM] of [p]. */
    fun candidates(p: LatLon, radiusM: Double): List<RoadCandidate> {
        val frame = LocalFrame(p)
        val out = mutableListOf<RoadCandidate>()
        for (s in index.query(BBox.around(p, radiusM))) {
            val proj = project(frame, s) ?: continue
            if (proj.distance > radiusM) continue
            out += RoadCandidate(s, true, proj.offset, proj.distance, proj.bearing, proj.point)
            if (!s.oneway) {
                out += RoadCandidate(s, false, s.lengthM - proj.offset, proj.distance, Geo.normalizeDeg(proj.bearing + 180), proj.point)
            }
        }
        return out
    }

    private class Projection(val offset: Double, val distance: Double, val bearing: Double, val point: LatLon)

    /** Closest point of [s] to the frame origin, with forward offset and bearing. */
    private fun project(frame: LocalFrame, s: RoadSegment): Projection? {
        var best: Projection? = null
        var along = 0.0
        val pts = s.points.map(frame::toXY)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val ab = pts[i + 1] - a
            val len2 = ab.dot(ab)
            val len = kotlin.math.sqrt(len2)
            if (len2 == 0.0) continue
            val t = ((Vec2(0.0, 0.0) - a).dot(ab) / len2).coerceIn(0.0, 1.0)
            val q = a + ab * t
            val d = q.length()
            if (best == null || d < best.distance) {
                best = Projection(along + t * len, d, ab.headingDeg(), frame.toLatLon(q))
            }
            along += len
        }
        // Planar lengths differ slightly from haversine lengths; rescale offsets to the latter.
        return best?.let { if (along > 0) Projection(it.offset * s.lengthM / along, it.distance, it.bearing, it.point) else it }
    }
}
