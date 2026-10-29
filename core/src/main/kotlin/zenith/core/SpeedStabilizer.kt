package zenith.core

import kotlin.math.roundToInt

/**
 * Speed shown to the driver. Prefers the receiver's Doppler speed (far more precise than position
 * differences) and shows it unsmoothed when the fix is accurate, so it follows the car without lag;
 * a poor fix's Doppler is smoothed lightly. Shows nothing until the GPS is accurate enough, and pins 0 at
 * standstill with hysteresis so a parked car never reads 1–6 km/h.
 */
class SpeedStabilizer(
    private val startKmh: Double = 5.0,
    private val stopKmh: Double = 2.5,
    private val maxAccuracyM: Double = 30.0,
    private val alpha: Double = 0.6,
) {
    private var ready = false
    private var moving = false
    private var smoothedKmh: Double? = null

    /** Displayed km/h, or null while waiting for a usable GPS fix. */
    fun update(fix: GpsFix, filteredMps: Double): Int? {
        val accurate = (fix.accuracyM ?: 0.0) <= maxAccuracyM
        if (!ready && !accurate) return null
        ready = true
        val raw = (fix.speedMps ?: filteredMps).coerceAtLeast(0.0) * 3.6
        val prev = smoothedKmh
        // Accurate Doppler is already clean and the filtered speed already smooth: shown as is, any
        // more smoothing only makes the number lag behind the car. Only a poor Doppler is smoothed.
        val smoothed = if (prev == null || accurate || fix.speedMps == null) raw else prev + alpha * (raw - prev)
        smoothedKmh = smoothed
        moving = if (moving) smoothed >= stopKmh else smoothed > startKmh
        return if (moving) smoothed.roundToInt() else 0
    }
}
