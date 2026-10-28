package zenith.core

/**
 * What the drive screen gives room to, from the speed: the same rules on the phone and in the small
 * window. Slow (stopped, a jam, a light), the music's Canvas rises up the screen; from town speeds
 * on, the map has it. An alert or a section always gives the map back. A tap on the music card
 * overrides the speed until the speed agrees with it again: big at a stop, then the car drives off
 * and it shrinks; small on the road, then the car stops and it grows.
 */
object Cockpit {
    /** Under this speed the music has the screen… */
    const val MUSIC_FULL_UNDER_KMH = 8

    /** …and from this one the map, smoothly in between. */
    const val MUSIC_NONE_FROM_KMH = 40

    /** The buttons step back from this speed… */
    const val CRUISE_FROM_KMH = 30

    /** …and come back under this one (apart, so they do not flicker in town). */
    const val CRUISE_UNDER_KMH = 12

    /** The music's share of the screen by [speedKmh] alone, 0 to 1; unknown speed: the map. */
    fun musicBySpeed(speedKmh: Int?): Float {
        val v = speedKmh ?: return 0f
        val k = ((MUSIC_NONE_FROM_KMH - v).toFloat() / (MUSIC_NONE_FROM_KMH - MUSIC_FULL_UNDER_KMH)).coerceIn(0f, 1f)
        return k * k * (3 - 2 * k)
    }

    /** The music's share shown: the tap's [choice] if any, else the speed; nothing during an alert. */
    fun music(speedKmh: Int?, choice: Boolean?, alerting: Boolean): Float = when {
        alerting -> 0f
        choice != null -> if (choice) 1f else 0f
        else -> musicBySpeed(speedKmh)
    }

    /** A tap's choice lasts until the speed alone gives the same; then the speed leads again (null). */
    fun keepChoice(choice: Boolean?, speedKmh: Int?): Boolean? = when {
        choice == true && musicBySpeed(speedKmh) >= 1f -> null
        choice == false && speedKmh != null && musicBySpeed(speedKmh) <= 0f -> null
        else -> choice
    }

    /** A tap on the music card: the opposite of what is shown now. */
    fun toggle(shown: Float): Boolean = shown < 0.5f

    /** Moving on: the buttons get smaller and the shortcuts fold away; [was] the state so far. */
    fun cruising(speedKmh: Int?, was: Boolean): Boolean = when {
        speedKmh == null -> was
        speedKmh >= CRUISE_FROM_KMH -> true
        speedKmh <= CRUISE_UNDER_KMH -> false
        else -> was
    }
}
