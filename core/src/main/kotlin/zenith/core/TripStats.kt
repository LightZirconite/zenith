package zenith.core

import kotlin.math.roundToInt

/** A finished drive in a few numbers, shown for a moment back home. */
data class TripSummary(val distanceM: Double, val durationMs: Long, val maxKmh: Int, val radarsPassed: Int) {
    /** "23 km · 32 min" (one decimal under 10 km). */
    fun headline(): String {
        val km = distanceM / 1_000
        val dist = if (km < 10) Weather.oneDecimal(km).replace('.', ',') + " km" else "${km.roundToInt()} km"
        val min = (durationMs / 60_000).toInt()
        val time = if (min < 60) "$min min" else "${min / 60} h ${(min % 60).toString().padStart(2, '0')}"
        return "$dist · $time"
    }

    /** "4 radars passés · 92 km/h au plus". */
    fun details(): String {
        val radars = when (radarsPassed) {
            0 -> "aucun radar"
            1 -> "1 radar passé"
            else -> "$radarsPassed radars passés"
        }
        return "$radars · $maxKmh km/h au plus"
    }
}

/**
 * Adds up a drive from its snapshots: distance driven (GPS jumps and standing noise left out), time,
 * top speed and radars passed. The demo counts too.
 */
class TripStats {
    private var startMs = -1L
    private var lastMs = -1L
    private var last: LatLon? = null
    private var distanceM = 0.0
    private var maxKmh = 0
    private var passed = 0

    fun add(timeMs: Long, position: LatLon?, speedKmh: Int?, radarsPassed: Int) {
        if (startMs < 0) startMs = timeMs
        lastMs = maxOf(lastMs, timeMs)
        passed += radarsPassed
        if (speedKmh != null) maxKmh = maxOf(maxKmh, speedKmh)
        if (position == null) return
        val before = last
        if (before != null && (speedKmh ?: 0) >= MOVING_KMH) {
            val step = Geo.distanceM(before, position)
            if (step < JUMP_M) distanceM += step
        }
        if (before == null || (speedKmh ?: 0) >= MOVING_KMH) last = position
    }

    /** Null for a drive too short to be worth a word (a false start, a few metres). */
    fun summary(): TripSummary? {
        if (startMs < 0 || distanceM < MIN_DISTANCE_M) return null
        return TripSummary(distanceM, lastMs - startMs, maxKmh, passed)
    }

    companion object {
        const val MOVING_KMH = 5
        /** More than this between two fixes is a GPS jump, not driving. */
        const val JUMP_M = 400.0
        const val MIN_DISTANCE_M = 500.0
        /** How long the summary stays at home before it fades. */
        const val SHOWN_MS = 9_000L
    }
}
