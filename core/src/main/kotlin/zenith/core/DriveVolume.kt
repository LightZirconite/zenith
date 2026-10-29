package zenith.core

import kotlin.math.ceil

/**
 * The media volume for a drive: alerts and voice play on it, and on most phones they are only
 * heard well above half of its range. At the start it is raised to [FLOOR] when lower; at the end
 * it goes back to where it was, unless the driver set it themselves meanwhile.
 */
object DriveVolume {
    /** Lowest share of the media volume's range during a drive. */
    const val FLOOR = 0.6

    /** The level to set at the start, null when the volume is already high enough. */
    fun raiseTo(current: Int, max: Int): Int? {
        if (max <= 0) return null
        val floor = ceil(max * FLOOR).toInt().coerceAtMost(max)
        return floor.takeIf { current < it }
    }

    /** The level to restore at the end: the one from before, if the volume is still where Zenith put it. */
    fun restoreTo(before: Int, raisedTo: Int, now: Int): Int? = before.takeIf { now == raisedTo }
}
