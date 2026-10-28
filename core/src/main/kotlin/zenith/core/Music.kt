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

/**
 * What video players (YouTube) publish, made readable on the music card: the video title carries
 * the artist and tags like "(Official Video)", the "artist" is the channel ("NinhoVEVO",
 * "Ninho - Topic"). Other players already give clean metadata and are left as they are.
 */
object VideoTitle {
    /** YouTube itself; YouTube Music gives real artists and titles. */
    const val YOUTUBE = "com.google.android.youtube"

    private val TAG_WORDS = Regex(
        """(?i)\b(official|officiel(le)?|clip|video|vidéo|audio|lyrics?|paroles|visuali[sz]er|hd|4k|hq|remaster(ed)?|music video)\b""",
    )
    private val BRACKETED = Regex("""\s*[(\[]([^()\[\]]*)[)\]]""")
    private val CHANNEL_SUFFIX = Regex("""(?i)(\s*-\s*(topic|thème|sujet)|vevo|\s+official|\s+officiel)\s*$""")

    /** The title without its tags in brackets: "Ninho - Jefe (Clip officiel)" → "Ninho - Jefe". */
    fun clean(title: String): String =
        BRACKETED.replace(title) { m -> if (TAG_WORDS.containsMatchIn(m.groupValues[1])) "" else m.value }.trim().ifEmpty { title.trim() }

    /** The channel as an artist name: "NinhoVEVO" → "Ninho", "Ninho - Topic" → "Ninho". */
    fun channel(name: String?): String? = name?.let { CHANNEL_SUFFIX.replace(it, "").trim() }?.ifEmpty { null }

    /**
     * Title and artist for the card from a video's [title] and [channel]: "Artist - Song" is split
     * when it starts with the channel's artist (or the channel is a VEVO / Topic one).
     */
    fun display(title: String, channel: String?): Pair<String, String?> {
        val cleaned = clean(title)
        val artist = channel(channel)
        val dash = cleaned.indexOf(" - ")
        if (dash > 0) {
            val left = cleaned.substring(0, dash).trim()
            val right = cleaned.substring(dash + 3).trim()
            val labelChannel = channel != null && channel.trim() != artist
            if (right.isNotEmpty() && (artist == null || labelChannel || left.equals(artist, ignoreCase = true))) return right to left
        }
        return cleaned to artist
    }
}
