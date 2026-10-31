package zenith.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Degrees to radians, exactly as java.lang.Math.toRadians (same constant), on every platform. */
internal fun rad(deg: Double): Double = deg * DEG_TO_RAD

/** Radians to degrees, exactly as java.lang.Math.toDegrees. */
internal fun deg(rad: Double): Double = rad * RAD_TO_DEG

private const val DEG_TO_RAD = 0.017453292519943295
private const val RAD_TO_DEG = 57.29577951308232

/** WGS84 point, degrees. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in meters (haversine). */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees in [0, 360). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val phi1 = rad(a.lat)
        val phi2 = rad(b.lat)
        val dLon = rad(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalizeDeg(deg(atan2(y, x)))
    }

    /** Smallest absolute angle between two headings, degrees in [0, 180]. */
    fun headingDiffDeg(a: Double, b: Double): Double {
        val d = abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180) 360 - d else d
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360) + 360) % 360
}
