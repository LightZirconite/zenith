package zenith.core

import java.util.PriorityQueue

/** A radar attached to a directed road segment, [offsetM] meters after the start of travel on it. */
data class RadarOnRoad(val radar: Radar, val segment: RoadSegment, val forward: Boolean, val offsetM: Double)

/**
 * Attaches radars to the roads they stand on. Keeps every drivable direction close to the nearest
 * one (two carriageways at similar distance both get the radar), so a wrong pick never hides an
 * alert. A radar without a measured direction on a one-way road inherits the road's direction.
 * Results are cached: build one snapper per [RoadNetwork] instance.
 */
class RadarSnapper(
    val network: RoadNetwork,
    private val maxDistanceM: Double = 30.0,
    private val tieM: Double = 10.0,
    private val directionToleranceDeg: Double = 50.0,
) {
    private val cache = HashMap<String, List<RadarOnRoad>>()

    fun snap(radar: Radar): List<RadarOnRoad> = cache.getOrPut(radar.id) {
        val cands = network.candidates(radar.position, maxDistanceM).filter { c ->
            radar.directionDeg == null || Geo.headingDiffDeg(c.bearingDeg, radar.directionDeg) <= directionToleranceDeg
        }
        val nearest = cands.minOfOrNull { it.distanceM } ?: return@getOrPut emptyList()
        cands.filter { it.distanceM <= nearest + tieM }.map { RadarOnRoad(radar, it.segment, it.forward, it.offsetM) }
    }
}

/** A directed segment of the road ahead; travel on it starts [startM] meters from the car (negative for the current one). */
data class HorizonStep(val segment: RoadSegment, val forward: Boolean, val startM: Double) {
    val endM: Double get() = startM + segment.lengthM
    val limitKmh: Int? get() = segment.limitKmh(forward)
}

/** Next speed limit change on the road ahead. */
data class LimitAhead(val distanceM: Double, val limitKmh: Int)

/**
 * Electronic horizon: the road the driver will most likely follow, and every road reachable within
 * a short distance. Lets alerts follow the road instead of the straight line, so a radar on a
 * parallel or crossing road is never announced as "next".
 */
object RoadHorizon {

    /**
     * Follows the obvious continuation from [from] (same road name, then same class, then a clearly
     * straightest option) up to [maxM]. Stops at the first fork without a clear continuation.
     */
    fun mostProbablePath(network: RoadNetwork, from: RoadCandidate, maxM: Double): List<HorizonStep> {
        val steps = mutableListOf(HorizonStep(from.segment, from.forward, -from.offsetM))
        val visited = hashSetOf(from.segment.id)
        while (steps.last().endM < maxM) {
            val last = steps.last()
            val (segment, forward) = continuation(network, last) ?: break
            if (!visited.add(segment.id)) break
            steps += HorizonStep(segment, forward, last.endM)
        }
        return steps
    }

    /**
     * Shortest driving distance from the car to the start of every directed segment reachable within
     * [maxM], all branches included. Key: segment id and direction.
     */
    fun reachable(network: RoadNetwork, from: RoadCandidate, maxM: Double): Map<Pair<Long, Boolean>, HorizonStep> {
        val best = HashMap<Pair<Long, Boolean>, HorizonStep>()
        val queue = PriorityQueue<HorizonStep>(compareBy { it.startM })
        queue += HorizonStep(from.segment, from.forward, -from.offsetM)
        while (queue.isNotEmpty()) {
            val step = queue.poll()
            val key = step.segment.id to step.forward
            if (key in best) continue
            best[key] = step
            if (step.endM > maxM) continue
            for ((segment, forward) in network.departuresFrom(exitNode(step))) {
                if (segment.id == step.segment.id) continue
                if ((segment.id to forward) !in best) queue += HorizonStep(segment, forward, step.endM)
            }
        }
        return best
    }

    /** Distance along [steps] to [snap], or null when the radar is not on them or already behind. */
    fun distanceTo(steps: Iterable<HorizonStep>, snap: RadarOnRoad): Double? = steps
        .firstOrNull { it.segment.id == snap.segment.id && it.forward == snap.forward }
        ?.let { it.startM + snap.offsetM }
        ?.takeIf { it >= 0 }

    /** First limit along [path] that differs from the current one, within [maxM]. */
    fun nextLimit(path: List<HorizonStep>, maxM: Double): LimitAhead? {
        val current = path.firstOrNull()?.limitKmh
        for (step in path.drop(1)) {
            if (step.startM > maxM) return null
            val limit = step.limitKmh ?: continue
            if (limit != current) return LimitAhead(step.startM, limit)
        }
        return null
    }

    /** Geometry of [path] from the car onwards, [maxM] meters long at most, in travel order. */
    fun polyline(path: List<HorizonStep>, maxM: Double): List<LatLon> {
        val out = mutableListOf<LatLon>()
        for (step in path) {
            if (step.startM > maxM) break
            val pts = if (step.forward) step.segment.points else step.segment.points.reversed()
            var along = step.startM
            for ((a, b) in pts.zipWithNext()) {
                val len = Geo.distanceM(a, b)
                val end = along + len
                if (end > 0 && along < maxM) {
                    if (out.isEmpty()) out += lerp(a, b, ((0 - along) / len).coerceIn(0.0, 1.0))
                    out += lerp(a, b, ((maxM - along) / len).coerceIn(0.0, 1.0))
                }
                along = end
            }
        }
        return out
    }

    /** Point [d] meters ahead along [path] and the travel bearing there; null off the path or behind the car. */
    fun pointAt(path: List<HorizonStep>, d: Double): Pair<LatLon, Double>? {
        if (d < 0) return null
        val step = path.firstOrNull { d >= it.startM && d <= it.endM } ?: return null
        val pts = if (step.forward) step.segment.points else step.segment.points.reversed()
        var along = step.startM
        for ((a, b) in pts.zipWithNext()) {
            val len = Geo.distanceM(a, b)
            if (along + len >= d) return lerp(a, b, if (len > 0) (d - along) / len else 0.0) to Geo.bearingDeg(a, b)
            along += len
        }
        return pts.last() to Geo.bearingDeg(pts[pts.size - 2], pts.last())
    }

    private fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    private fun exitNode(s: HorizonStep) = if (s.forward) s.segment.toNode else s.segment.fromNode

    private fun continuation(network: RoadNetwork, last: HorizonStep): Pair<RoadSegment, Boolean>? {
        val options = network.departuresFrom(exitNode(last)).filter { it.first.id != last.segment.id }
        if (options.size <= 1) return options.firstOrNull()
        val current = last.segment
        val turn = { o: Pair<RoadSegment, Boolean> -> Geo.headingDiffDeg(exitBearing(last), entryBearing(o)) }
        val named = options.filter { current.name != null && it.first.name == current.name }
        if (named.size == 1) return named.single()
        val pool = named.ifEmpty { options.filter { it.first.roadClass == current.roadClass } }
        if (pool.size == 1) return pool.single().takeIf { turn(it) <= MAX_SINGLE_TURN_DEG }
        val ranked = pool.ifEmpty { options }.sortedBy(turn)
        val first = turn(ranked[0])
        return ranked[0].takeIf { first <= MAX_STRAIGHT_DEG && turn(ranked[1]) - first >= MIN_TURN_GAP_DEG }
    }

    private fun exitBearing(s: HorizonStep): Double {
        val p = s.segment.points
        return if (s.forward) Geo.bearingDeg(p[p.size - 2], p.last()) else Geo.bearingDeg(p[1], p[0])
    }

    private fun entryBearing(o: Pair<RoadSegment, Boolean>): Double {
        val p = o.first.points
        return if (o.second) Geo.bearingDeg(p[0], p[1]) else Geo.bearingDeg(p.last(), p[p.size - 2])
    }

    /** Turn accepted when only one option keeps the road class. */
    private const val MAX_SINGLE_TURN_DEG = 60.0

    /** Straightest option must be this straight and lead the next one by this much. */
    private const val MAX_STRAIGHT_DEG = 45.0
    private const val MIN_TURN_GAP_DEG = 30.0
}
