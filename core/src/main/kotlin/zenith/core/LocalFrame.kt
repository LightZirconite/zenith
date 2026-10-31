package zenith.core

import kotlin.math.cos

/** Planar x/y in meters (x east, y north) around [origin]. Accurate within a few km. */
class LocalFrame(val origin: LatLon) {
    private val mPerDegLat = rad(1.0) * Geo.EARTH_RADIUS_M
    private val mPerDegLon = mPerDegLat * cos(rad(origin.lat))

    fun toXY(p: LatLon): Vec2 = Vec2((p.lon - origin.lon) * mPerDegLon, (p.lat - origin.lat) * mPerDegLat)

    fun toLatLon(v: Vec2): LatLon = LatLon(origin.lat + v.y / mPerDegLat, origin.lon + v.x / mPerDegLon)
}

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun length() = kotlin.math.hypot(x, y)

    /** Compass heading of this vector, degrees in [0, 360). */
    fun headingDeg() = Geo.normalizeDeg(deg(kotlin.math.atan2(x, y)))

    companion object {
        /** Unit vector pointing to compass [headingDeg]. */
        fun fromHeading(headingDeg: Double): Vec2 {
            val r = rad(headingDeg)
            return Vec2(kotlin.math.sin(r), cos(r))
        }
    }
}
