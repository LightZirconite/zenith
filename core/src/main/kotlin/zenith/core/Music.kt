package zenith.core

/** Spotify track ids as the Spotify app publishes them in its media session. */
object SpotifyTrack {
    /** Only the Spotify app itself: the canvas is shown for its tracks alone. */
    const val PACKAGE = "com.spotify.music"

    private val URI = Regex("""^spotify:track:([0-9A-Za-z]{22})$""")
    private val LINK = Regex("""^https?://open\.spotify\.com/(?:intl-[a-z-]+/)?track/([0-9A-Za-z]{22})(?:[/?#].*)?$""")

    /** A Spotify track id: 22 base-62 characters. */
    fun isId(s: String) = s.length == 22 && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /**
     * The track id in `spotify:track:<id>` or `https://open.spotify.com/track/<id>`; null for
     * anything else (episodes, local files, ads, other players' ids).
     */
    fun idOf(value: String?): String? {
        val v = value?.trim() ?: return null
        return (URI.find(v) ?: LINK.find(v))?.groupValues?.get(1)
    }

    /**
     * The id of the track the Spotify app is playing, from its session's media id or media URI
     * (both seen carrying `spotify:track:<id>`); null for any other player.
     */
    fun idFromSession(packageName: String?, mediaId: String?, mediaUri: String?): String? =
        if (packageName != PACKAGE) null else idOf(mediaId) ?: idOf(mediaUri)
}

/**
 * Where a track was at [updatedAtMs] (monotonic clock), moving at [speed] while it plays: enough to
 * draw a progress line between the player's own updates, which come only on play, pause and seek.
 */
data class PlaybackProgress(val positionMs: Long, val updatedAtMs: Long, val speed: Float, val durationMs: Long) {
    /** Position at [nowMs], within the track. */
    fun positionAt(nowMs: Long, playing: Boolean): Long {
        val moved = if (playing) ((nowMs - updatedAtMs).coerceAtLeast(0) * speed.coerceAtLeast(0f)).toLong() else 0L
        return (positionMs + moved).coerceIn(0, durationMs)
    }

    /** Share of the track played at [nowMs], 0 to 1. */
    fun fractionAt(nowMs: Long, playing: Boolean): Float = positionAt(nowMs, playing).toFloat() / durationMs

    /**
     * Where a looping video of [loopMs] stands if it started with the track and never stopped: a
     * canvas that follows the song, the same after a pause or back in the app.
     */
    fun loopPositionAt(nowMs: Long, playing: Boolean, loopMs: Long): Long =
        if (loopMs <= 0) 0 else Math.floorMod(positionAt(nowMs, playing), loopMs)

    companion object {
        /**
         * Progress from a playback state; null when the player gives no position or no duration
         * (radio, live stream, some video players): no line rather than a wrong one.
         */
        fun of(positionMs: Long, updatedAtMs: Long, speed: Float, durationMs: Long?): PlaybackProgress? {
            if (durationMs == null || durationMs <= 0 || positionMs < 0) return null
            return PlaybackProgress(positionMs, updatedAtMs, if (speed.isFinite()) speed else 1f, durationMs)
        }
    }
}

/** A "like" the player offers among its custom actions: [action] to send, and whether the track is liked now. */
data class LikeAction(val action: String, val liked: Boolean)

/** Custom actions of the media sessions that Zenith knows how to show. */
object PlayerActions {
    /**
     * Spotify offers the opposite of the current state: "ADD_TO_COLLECTION" while the track is not
     * in Liked Songs, "REMOVE_FROM_COLLECTION" once it is.
     */
    fun like(actions: List<String>): LikeAction? = when {
        "REMOVE_FROM_COLLECTION" in actions -> LikeAction("REMOVE_FROM_COLLECTION", liked = true)
        "ADD_TO_COLLECTION" in actions -> LikeAction("ADD_TO_COLLECTION", liked = false)
        else -> null
    }
}
