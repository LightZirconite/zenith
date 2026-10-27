package zenith.core

import kotlin.math.abs

/**
 * Online HMM map matcher (Newson & Krumm): Gaussian emission on the distance to the road plus a
 * heading term, exponential transition on |route distance - straight distance|. Keeps one Viterbi
 * column and returns the best current candidate, so a single noisy fix cannot flip the match onto a
 * parallel road.
 */
class MapMatcher(
    /** Current road network; may change as tiles load while driving (segment ids stay stable). */
    private val network: () -> RoadNetwork?,
    private val sigmaZ: Double = 8.0,
    private val beta: Double = 10.0,
    private val sigmaHeadingDeg: Double = 35.0,
    private val searchRadiusM: Double = 50.0,
    /** Log-score lead a different road needs before the reported match switches to it. */
    private val switchMargin: Double = 3.0,
    /** Log penalty for moving onto a road of another class (motorway → link): drivers mostly stay. */
    private val classChangePenalty: Double = 2.0,
    /** Fixes after a (re)start during which the plain best candidate is reported, without hysteresis. */
    private val warmupFixes: Int = 5,
) {
    private var column: List<Pair<RoadCandidate, Double>> = emptyList()
    private var lastPosition: LatLon? = null
    private var reported: RoadCandidate? = null
    private var fixesSinceReset = 0

    constructor(network: RoadNetwork) : this({ network })

    fun reset() {
        column = emptyList()
        lastPosition = null
        reported = null
        fixesSinceReset = 0
    }

    /** Matches [state]; null when no road is within reach. */
    fun update(state: KinematicState): RoadCandidate? {
        val net = network() ?: run {
            reset()
            return null
        }
        val cands = net.candidates(state.position, searchRadiusM)
        if (cands.isEmpty()) {
            reset()
            return null
        }
        val heading = state.headingDeg.takeIf { state.speedMps > 2.0 }
        val straight = lastPosition?.let { Geo.distanceM(it, state.position) }

        var scored = cands.map { c ->
            val emission = emission(c, heading)
            val transition = if (straight == null) 0.0 else column.maxOfOrNull { (prev, score) ->
                score + transition(prev, c, straight)
            } ?: 0.0
            c to emission + transition
        }
        if (scored.maxOf { it.second } < -1e6) {
            // Every path became impossible: restart from emissions alone.
            scored = cands.map { it to emission(it, heading) }
        }
        val top = scored.maxOf { it.second }
        column = scored.map { it.first to it.second - top }.filter { it.second > -60 }
        lastPosition = state.position
        val best = column.maxBy { it.second }
        // Hysteresis: at a split the two roads overlap for a while, so keep the current road
        // until another one clearly wins instead of flickering between their limits.
        fixesSinceReset++
        val current = reported?.takeIf { fixesSinceReset > warmupFixes }?.let { r ->
            column.firstOrNull { it.first.segment.id == r.segment.id && it.first.forward == r.forward }
                ?: column.filter { it.first.entryNode == r.exitNode }.maxByOrNull { it.second }
        }
        val chosen = if (current != null && best.second - current.second < switchMargin) current else best
        reported = chosen.first
        return chosen.first
    }

    private fun emission(c: RoadCandidate, heading: Double?): Double {
        val z = c.distanceM / sigmaZ
        var log = -0.5 * z * z
        if (heading != null) {
            val h = Geo.headingDiffDeg(heading, c.bearingDeg) / sigmaHeadingDeg
            log -= 0.5 * h * h
        }
        return log
    }

    private fun transition(a: RoadCandidate, b: RoadCandidate, straight: Double): Double {
        val route = routeDistance(a, b) ?: return IMPOSSIBLE
        val classChange = a.segment.id != b.segment.id && a.segment.roadClass != b.segment.roadClass
        return -abs(route - straight) / beta - if (classChange) classChangePenalty else 0.0
    }

    /** Driving distance from [a] to [b] when reachable within two segment hops, else null. */
    private fun routeDistance(a: RoadCandidate, b: RoadCandidate): Double? {
        val net = network() ?: return null
        if (a.segment.id == b.segment.id) {
            if (a.forward != b.forward) return if (a.distanceM < 15 && b.distanceM < 15) 40.0 else null // U-turn
            val d = b.offsetM - a.offsetM
            return if (d >= -5) maxOf(d, 0.0) else null
        }
        if (a.exitNode == b.entryNode) return a.remainingM + b.offsetM
        for ((mid, forward) in net.departuresFrom(a.exitNode)) {
            if (mid.id == a.segment.id || mid.id == b.segment.id) continue
            val midExit = if (forward) mid.toNode else mid.fromNode
            if (midExit == b.entryNode) return a.remainingM + mid.lengthM + b.offsetM
        }
        return null
    }

    private companion object {
        const val IMPOSSIBLE = -1e9
    }
}
