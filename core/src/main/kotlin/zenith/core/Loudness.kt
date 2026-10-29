package zenith.core

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Interface and take-off sounds at an even loudness on every device. They follow the media volume,
 * which a car head unit keeps near its top (an amplifier behind it): there they came out far louder
 * than the alerts. Above a level they are turned down by as much as the volume went up; below it
 * they play as made. Alerts and voice are not touched: they must always be heard.
 */
object Loudness {
    /** Loudest output level, in dB, an interface sound may reach. */
    const val CAP_DB = -10.0

    /** Rough output level of the media stream at [fraction] of its range: Android's curves are close to its square. */
    fun streamDb(fraction: Double): Double = if (fraction <= 0.0) -96.0 else 40 * log10(fraction.coerceAtMost(1.0))

    /** Gain from 0 to 1 for an interface sound, with the media volume at [volume] out of [max]. */
    fun uiGain(volume: Int, max: Int): Float {
        if (max <= 0) return 1f
        val overDb = streamDb(volume.toDouble() / max) - CAP_DB
        return 10.0.pow(min(0.0, -overDb) / 20).toFloat()
    }
}
