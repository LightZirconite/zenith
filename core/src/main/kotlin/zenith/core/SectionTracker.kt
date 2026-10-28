package zenith.core

/**
 * Average-speed section ("radar tronçon"): tracks progress since the entry point and tells
 * the highest speed to hold until the exit so the average stays at or under the limit.
 */
class SectionTracker(
    val lengthM: Double,
    val limitKmh: Int,
    private val entryTimeMs: Long,
) {
    private var travelledM = 0.0
    var lastTimeMs = entryTimeMs
        private set

    val remainingM: Double get() = (lengthM - travelledM).coerceAtLeast(0.0)

    /** Records [deltaM] meters travelled along the section at [timeMs]; a late, older fix is ignored. */
    fun advance(deltaM: Double, timeMs: Long) {
        if (timeMs < lastTimeMs) return
        travelledM = (travelledM + deltaM).coerceAtMost(lengthM)
        lastTimeMs = timeMs
    }

    /** Average speed since entry, km/h. 0 before any time has elapsed. */
    fun averageKmh(): Double {
        val elapsedS = (lastTimeMs - entryTimeMs) / 1000.0
        return if (elapsedS <= 0) 0.0 else travelledM / elapsedS * 3.6
    }

    /**
     * Highest constant speed for the rest of the section that keeps the final average at the
     * limit, km/h. Null when there is no constraint left: the section is finished, or enough time
     * has already elapsed that any speed keeps the average under the limit.
     */
    fun maxSpeedToFinishKmh(): Double? {
        if (remainingM <= 0) return null
        val elapsedS = (lastTimeMs - entryTimeMs) / 1000.0
        val minTotalS = lengthM / (limitKmh / 3.6)
        val timeLeftS = minTotalS - elapsedS
        if (timeLeftS <= 0) return null
        return remainingM / timeLeftS * 3.6
    }
}
