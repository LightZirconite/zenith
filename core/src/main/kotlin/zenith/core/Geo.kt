package zenith.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 point, degrees. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in meters (haversine). */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees in [0, 360). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalizeDeg(Math.toDegrees(atan2(y, x)))
    }

    /** Smallest absolute angle between two headings, degrees in [0, 180]. */
    fun headingDiffDeg(a: Double, b: Double): Double {
        val d = abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180) 360 - d else d
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360) + 360) % 360
}
