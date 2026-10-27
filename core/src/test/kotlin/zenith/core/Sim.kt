package zenith.core

import kotlin.random.Random

/** Synthetic roads and drives on a local plane around (45, 5). */
object Sim {
    val frame = LocalFrame(LatLon(45.0, 5.0))

    fun ll(x: Double, y: Double) = frame.toLatLon(Vec2(x, y))

    fun seg(
        id: Long, from: Long, to: Long, vararg xy: Pair<Double, Double>,
        oneway: Boolean = false, limit: Int? = null, roadClass: String? = null,
    ) = RoadSegment(id, from, to, xy.map { ll(it.first, it.second) }, oneway, limit, limit, roadClass = roadClass)

    /** 1 Hz fixes along [path] at constant [speedMps], with Gaussian position noise. */
    fun drive(
        path: List<Pair<Double, Double>>,
        speedMps: Double,
        noiseM: Double = 0.0,
        seed: Int = 1,
        withVelocity: Boolean = true,
    ): List<GpsFix> {
        val pts = path.map { Vec2(it.first, it.second) }
        val lengths = pts.zipWithNext { a, b -> (b - a).length() }
        val total = lengths.sum()
        val rnd = Random(seed)
        val fixes = mutableListOf<GpsFix>()
        var t = 0
        while (speedMps * t <= total) {
            var s = speedMps * t
            var i = 0
            while (i < lengths.size - 1 && s > lengths[i]) {
                s -= lengths[i]
                i++
            }
            val dir = (pts[i + 1] - pts[i]) * (1 / lengths[i])
            val truth = pts[i] + dir * s
            val noisy = truth + Vec2(gauss(rnd) * noiseM, gauss(rnd) * noiseM)
            fixes += GpsFix(
                timeMs = t * 1000L,
                position = frame.toLatLon(noisy),
                accuracyM = maxOf(noiseM, 3.0),
                speedMps = speedMps.takeIf { withVelocity },
                bearingDeg = dir.headingDeg().takeIf { withVelocity },
            )
            t++
        }
        return fixes
    }

    private fun gauss(rnd: Random): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12)
        val v = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }
}
