package zenith.core

/** A line indexed by distance from its first point, meters. */
class Polyline(private val points: List<LatLon>) {
    init {
        require(points.size >= 2) { "a polyline needs at least 2 points" }
    }

    private val cumulative = points.zipWithNext { a, b -> Geo.distanceM(a, b) }.runningFold(0.0) { acc, d -> acc + d }
    val lengthM: Double = cumulative.last()

    fun pointAt(d: Double): LatLon {
        val i = index(d)
        val len = cumulative[i + 1] - cumulative[i]
        val t = if (len <= 0) 0.0 else ((d - cumulative[i]) / len).coerceIn(0.0, 1.0)
        val a = points[i]
        val b = points[i + 1]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    fun bearingAt(d: Double): Double = index(d).let { Geo.bearingDeg(points[it], points[it + 1]) }

    private fun index(d: Double): Int =
        (cumulative.indexOfFirst { it > d } - 1).let { if (it < 0) points.size - 2 else it }.coerceIn(0, points.size - 2)
}
