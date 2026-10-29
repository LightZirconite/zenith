package zenith.core

import kotlin.math.min
import kotlin.math.pow

/**
 * Interface and take-off sounds at an even loudness on every device. They follow the media volume,
 * which the driver raises to hear the alerts (80 % on a phone, near the top on a car head unit with
 * an amplifier): there they came out far louder than wanted. Above about half of the range they are
 * turned down by as much as the volume went up, so they sound as they do at half volume; below,
 * they play as made. Alerts and voice are not touched: they must always be heard.
 */
object Loudness {
    /** Loudest output level, in dB, an interface sound may reach: the media volume at about half of its range. */
    const val CAP_DB = -17.0

    /**
     * Output level of the media stream at [fraction] of its range, in dB: Android's default media
     * curve for the speaker (frameworks/av, default_volume_tables), between its points.
     */
    fun streamDb(fraction: Double): Double {
        if (fraction <= 0.0) return -96.0
        val f = fraction.coerceAtMost(1.0)
        val (a, b) = CURVE.zipWithNext().firstOrNull { (_, hi) -> f <= hi.first } ?: return 0.0
        if (f <= a.first) return a.second
        return a.second + (b.second - a.second) * (f - a.first) / (b.first - a.first)
    }

    /** Gain from 0 to 1 for an interface sound, with the media volume at [volume] out of [max]. */
    fun uiGain(volume: Int, max: Int): Float {
        if (max <= 0) return 1f
        val overDb = streamDb(volume.toDouble() / max) - CAP_DB
        return 10.0.pow(min(0.0, -overDb) / 20).toFloat()
    }

    private val CURVE = listOf(0.01 to -56.0, 0.2 to -34.0, 0.6 to -11.0, 1.0 to 0.0)
}
