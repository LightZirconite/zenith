package zenith.core

/**
 * The alert island: radars are not drawn as cards on the drive screen any more, the island grows out
 * of the camera (the Dynamic Island on iPhone, the punch hole on Android) and says what matters now.
 * Same rules on both phones; the speed ring around the digits follows [Gauge].
 */
object Island {
    /** What the island shows, most urgent first. */
    enum class State { NONE, APPROACH, SLOW_DOWN, SECTION, PASSED }

    /** How long the small green "Radar passé" stays after a radar. */
    const val PASSED_MS = 2_500L

    /**
     * [alert]: a radar alert on screen; [overLimit]: too fast for it; [section]: an average-speed
     * section under way; [passedAgoMs]: time since the last radar was passed, -1 if none.
     */
    fun state(alert: Boolean, overLimit: Boolean, section: Boolean, passedAgoMs: Long): State = when {
        alert && overLimit -> State.SLOW_DOWN
        alert -> State.APPROACH
        section -> State.SECTION
        passedAgoMs in 0 until PASSED_MS -> State.PASSED
        else -> State.NONE
    }

    /** Progress of the approach, 0 at first sight of the radar ([startM]) to 1 on it. */
    fun progress(distanceM: Double, startM: Double): Float =
        if (startM <= 0) 0f else (1 - distanceM / startM).toFloat().coerceIn(0f, 1f)

    /** "18 km/h au-dessus" under "Ralentis"; null when not above the limit. */
    fun overText(speedKmh: Int?, limitKmh: Int?): String? {
        if (speedKmh == null || limitKmh == null || speedKmh <= limitKmh) return null
        return "${speedKmh - limitKmh} km/h au-dessus"
    }
}

/** The speed ring around the digits: it fills with the speed, a tick marks the limit. */
object Gauge {
    /** The ring's full scale: 130 km/h, or more on fast roads so the limit's tick never sits at the end. */
    fun scaleKmh(limitKmh: Int?): Float = maxOf(130f, (limitKmh ?: 0) * 1.25f)

    /** Share of the ring filled at [speedKmh], 0 to 1. */
    fun fill(speedKmh: Int?, limitKmh: Int?): Float = ((speedKmh ?: 0) / scaleKmh(limitKmh)).coerceIn(0f, 1f)

    /** Where the limit's tick sits on the ring, 0 to 1; null without a limit. */
    fun tick(limitKmh: Int?): Float? = limitKmh?.let { (it / scaleKmh(it)).coerceIn(0f, 1f) }

    /** The ring is an arc of this many degrees, open at the bottom where the limit sign sits. */
    const val SWEEP_DEG = 270f

    /** Its start, clockwise from 3 o'clock (bottom left). */
    const val START_DEG = 135f
}
