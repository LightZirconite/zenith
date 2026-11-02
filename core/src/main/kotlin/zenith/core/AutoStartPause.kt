package zenith.core

/**
 * "Arrêter" touched on a drive that started by itself (a passenger, a driving lesson in someone
 * else's car): no automatic start again until the trip is over. The apps keep when it was
 * touched ([sinceMs], 0 for none); the end of the trip (out of the vehicle, car disconnected) or a
 * start by hand clears it, and it lapses by itself after [MAX_MS] in case no end ever comes.
 */
object AutoStartPause {
    const val MAX_MS = 12 * 3_600_000L

    /** A stop by hand pauses the automatic start only when the drive started by itself. */
    fun pausesOnStop(startedByItself: Boolean): Boolean = startedByItself

    /** May a drive start by itself now? A clock set back counts as no pause. */
    fun allows(sinceMs: Long, nowMs: Long): Boolean = sinceMs <= 0 || nowMs < sinceMs || nowMs - sinceMs >= MAX_MS
}
