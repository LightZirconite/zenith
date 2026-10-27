package zenith.core

import kotlin.math.floor

/** French speed enforcement rules (Code de la route). Speeds in km/h. */
object SpeedRules {

    /**
     * Speed retained after the technical margin: 5 km/h below 100, 5 % from 100 up
     * for fixed radars; 10 km/h / 10 % for radars operating from a moving vehicle.
     */
    fun retainedSpeed(measuredKmh: Double, movingRadar: Boolean = false): Double {
        val (absolute, ratio) = if (movingRadar) 10.0 to 0.10 else 5.0 to 0.05
        val retained = if (measuredKmh < 100) measuredKmh - absolute else measuredKmh * (1 - ratio)
        return floor(retained.coerceAtLeast(0.0))
    }

    /** Points lost for an excess of [excessKmh] over the limit (retained speed). 0 when not speeding. */
    fun pointsLost(excessKmh: Double): Int = when {
        excessKmh <= 0 -> 0
        excessKmh < 20 -> 1
        excessKmh < 30 -> 2
        excessKmh < 40 -> 3
        excessKmh < 50 -> 4
        else -> 6
    }

    /** Limit lowered by rain: 130 → 110, 110 → 100, 90 and 80 → 80. */
    fun rainLimit(limitKmh: Int): Int = when {
        limitKmh >= 130 -> 110
        limitKmh >= 110 -> 100
        limitKmh >= 80 -> 80
        else -> limitKmh
    }

    /** Limit for a probationary licence: 130 → 110, 110 → 100, 90 → 80. */
    fun probationaryLimit(limitKmh: Int): Int = when {
        limitKmh >= 130 -> 110
        limitKmh >= 110 -> 100
        limitKmh >= 90 -> 80
        else -> limitKmh
    }

    /** Effective limit for a driver, applying the strictest of rain and probationary rules. */
    fun effectiveLimit(limitKmh: Int, raining: Boolean, probationary: Boolean): Int {
        var limit = limitKmh
        if (raining) limit = minOf(limit, rainLimit(limitKmh))
        if (probationary) limit = minOf(limit, probationaryLimit(limitKmh))
        return limit
    }
}
