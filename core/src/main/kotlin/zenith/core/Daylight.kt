package zenith.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Night or day at a place and time, from the sun's height (simple solar position, about 0.1° off,
 * plenty for this). At night the drive screen dims a little more: darker map, quieter Canvas.
 */
object Daylight {
    /** Below this sun height the sky is dark enough (end of civil twilight). */
    const val NIGHT_SUN_DEG = -6.0

    /** How much the map and the Canvas are dimmed at night (multiplied into their opacity). */
    const val NIGHT_DIM = 0.78f

    /** Sun height above the horizon in degrees at [pos], [timeMs] (Unix epoch, UTC). */
    fun sunHeightDeg(pos: LatLon, timeMs: Long): Double {
        val d = timeMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0
        val g = rad(Geo.normalizeDeg(357.529 + 0.98560028 * d))
        val q = Geo.normalizeDeg(280.459 + 0.98564736 * d)
        val l = rad(Geo.normalizeDeg(q + 1.915 * sin(g) + 0.020 * sin(2 * g)))
        val e = rad(23.439 - 0.00000036 * d)
        val ra = deg(atan2(cos(e) * sin(l), cos(l)))
        val dec = asin(sin(e) * sin(l))
        val gmst = Geo.normalizeDeg((18.697374558 + 24.06570982441908 * d) * 15)
        val ha = rad(Geo.normalizeDeg(gmst + pos.lon - ra))
        val lat = rad(pos.lat)
        return deg(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)))
    }

    fun isNight(pos: LatLon, timeMs: Long): Boolean = sunHeightDeg(pos, timeMs) < NIGHT_SUN_DEG
}
