package zenith.core

/**
 * Same song, same memo: case, spaces and accents aside. JVM only (java.text.Normalizer); iOS folds
 * its keys with Foundation the same way.
 */
fun CanvasSearch.key(title: String, artist: String?): String =
    "?" + listOf(title, artist.orEmpty()).joinToString("|") { java.text.Normalizer.normalize(it.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }

/**
 * Canvas lookups for the music card. Services are asked in order and their answers kept per track
 * (a video for 3 days, "none" for a day: a canvas may be added later). When every service fails,
 * nothing is asked for a pause that doubles from 1 to 30 minutes, so a dead service costs nothing
 * on each new song. [get] makes the request (null on a network failure); it is never called under
 * the lock. Thread-safe; [find] blocks, so it runs off the main thread.
 */
class CanvasFinder(
    private val get: (String) -> HttpAnswer?,
    private val now: () -> Long = System::currentTimeMillis,
    private val services: List<CanvasService> = CanvasService.entries,
) {
    private class Memo(val url: String?, val atMs: Long, val trackId: String? = null) {
        fun match() = if (url != null && trackId != null) CanvasMatch(trackId, url) else null
    }

    private val memo = object : LinkedHashMap<String, Memo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Memo>?) = size > MAX_MEMO
    }
    private var failedAtMs = 0L
    private var pauseMs = 0L

    /** The canvas video URL of [trackId]; null when it has none, or nobody could be asked. */
    fun find(trackId: String): String? {
        if (!SpotifyTrack.isId(trackId)) return null
        synchronized(this) {
            val t = now()
            memo[trackId]?.let { m -> if (t - m.atMs < (if (m.url != null) VIDEO_TTL_MS else NONE_TTL_MS)) return m.url }
            if (pauseMs > 0 && t - failedAtMs < pauseMs) return null
        }
        val answer = ask(trackId)
        synchronized(this) {
            if (answer == null) {
                pauseMs = (pauseMs * 2).coerceIn(FIRST_PAUSE_MS, MAX_PAUSE_MS)
                failedAtMs = now()
                return null
            }
            pauseMs = 0
            val url = (answer as? CanvasAnswer.Video)?.url
            memo[trackId] = Memo(url, now())
            return url
        }
    }

    /**
     * The canvas of a track known by its words: the Spotify match and its video, remembered like
     * [find] (and under the Spotify id too); null when none or nobody could be asked.
     */
    fun search(title: String, artist: String?, album: String?, durationMs: Long?): CanvasMatch? {
        if (title.isBlank()) return null
        val key = CanvasSearch.key(title, artist)
        synchronized(this) {
            val t = now()
            memo[key]?.let { m -> if (t - m.atMs < (if (m.url != null) VIDEO_TTL_MS else NONE_TTL_MS)) return m.match() }
            if (pauseMs > 0 && t - failedAtMs < pauseMs) return null
        }
        val answer = runCatching { get(CanvasSearch.url(title, artist, album, durationMs)) }.getOrNull()?.let(CanvasSearch::parse)
        synchronized(this) {
            if (answer == null) {
                pauseMs = (pauseMs * 2).coerceIn(FIRST_PAUSE_MS, MAX_PAUSE_MS)
                failedAtMs = now()
                return null
            }
            pauseMs = 0
            val match = (answer as? CanvasAnswer.Found)?.match
            memo[key] = Memo(match?.url, now(), match?.trackId)
            match?.let { memo[it.trackId] = Memo(it.url, now()) }
            return match
        }
    }

    /** Drops what is known of [trackId] (its video would not download or play): asked again next time. */
    fun forget(trackId: String) {
        synchronized(this) { memo.remove(trackId) }
    }

    private fun ask(trackId: String): CanvasAnswer? {
        for (service in services) {
            val answer = runCatching { get(service.url(trackId)) }.getOrNull() ?: continue
            service.parse(answer, trackId)?.let { return it }
        }
        return null
    }

    companion object {
        const val VIDEO_TTL_MS = 3 * 24 * 3_600_000L
        const val NONE_TTL_MS = 24 * 3_600_000L
        const val FIRST_PAUSE_MS = 60_000L
        const val MAX_PAUSE_MS = 30 * 60_000L
        private const val MAX_MEMO = 500
    }
}
