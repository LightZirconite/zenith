package zenith.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a canvas service said about a track. */
sealed interface CanvasAnswer {
    /** The track has a looping video at [url], an MP4 on Spotify's CDN. */
    data class Video(val url: String) : CanvasAnswer

    /** No canvas for this track, or only a still image (the cover already shows). */
    data object None : CanvasAnswer

    /** A search by title found this Spotify track and its video. */
    data class Found(val match: CanvasMatch) : CanvasAnswer
}

/** An HTTP answer: status code and body. */
data class HttpAnswer(val status: Int, val body: String)

/**
 * Free services that find the Spotify Canvas (the short looping video behind a track) by track id:
 * unofficial, no account, no key, run by other projects. Asked in this order; the next one only
 * when one fails, since "none" is a real answer (about half of the tracks have no canvas).
 */
enum class CanvasService {
    /** Spicy Lyrics' canvas store, which the Spicy Lyrics Android app also asks directly. */
    SPICY_LYRICS {
        override fun url(trackId: String) = "https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId"

        /** `{"data":{"<id>":{"url":"…mp4",…}}}`, or `"<id>":null` when the track has none. */
        override fun parse(answer: HttpAnswer, trackId: String): CanvasAnswer? {
            if (answer.status == 400) return CanvasAnswer.None
            if (answer.status != 200) return null
            val data = json(answer.body)?.get("data") as? JsonObject ?: return null
            return when (val entry = data[trackId]) {
                null -> null
                is JsonNull -> CanvasAnswer.None
                is JsonObject -> if (entry.containsKey("error")) null else video(entry.string("url"))
                else -> null
            }
        }
    },

    /** AuraMusic's canvas server; answers 404 when the track has none. */
    AURA_MUSIC {
        override fun url(trackId: String) = "https://canvas.auramusic.site/api/canvas?trackId=$trackId"

        /** `{"success":true,"trackId":"<id>","canvasUrl":"…mp4","canvasesList":[…]}`. */
        override fun parse(answer: HttpAnswer, trackId: String): CanvasAnswer? {
            if (answer.status == 404 || answer.status == 400) return CanvasAnswer.None
            if (answer.status != 200) return null
            val o = json(answer.body) ?: return null
            // An answer about another track is a failure, never this track's canvas.
            o.string("trackId")?.let { if (it != trackId) return null }
            val first = (o["canvasesList"] as? JsonArray)?.firstOrNull() as? JsonObject
            first?.string("trackUri")?.let { if (SpotifyTrack.idOf(it) != trackId) return null }
            return video(o.string("canvasUrl") ?: o.string("url") ?: first?.string("canvasUrl"))
        }
    };

    abstract fun url(trackId: String): String

    /** The service's answer, or null when it failed (error, unreadable, about another track). */
    abstract fun parse(answer: HttpAnswer, trackId: String): CanvasAnswer?

    companion object {
        /** Only an HTTPS MP4 on Spotify's CDN is downloaded; anything else counts as no canvas. */
        private val VIDEO = Regex("""^https://[A-Za-z0-9.-]+\.scdn\.co/[^?#]+\.mp4(\?[^#]*)?$""")

        fun isVideoUrl(url: String) = VIDEO.matches(url)

        private fun video(url: String?): CanvasAnswer =
            if (url != null && isVideoUrl(url)) CanvasAnswer.Video(url) else CanvasAnswer.None

        private fun json(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}

/** A Spotify track found from a title, with its canvas video. */
data class CanvasMatch(val trackId: String, val url: String)

/**
 * The canvas of a track known only by its words (Deezer, YouTube Music, any player but Spotify):
 * AuraMusic's server finds the Spotify track matching title, artist, album and length, and its
 * canvas. Free, no account; a track without a canvas there simply has none.
 */
object CanvasSearch {
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun url(title: String, artist: String?, album: String?, durationMs: Long?): String = buildString {
        append("https://canvas.auramusic.site/api/canvas?song=").append(enc(title)).append("&title=").append(enc(title))
        artist?.let { append("&artist=").append(enc(it)) }
        album?.let { append("&album=").append(enc(it)) }
        durationMs?.takeIf { it > 0 }?.let { append("&durationMs=").append(it) }
    }

    /** The match, [CanvasAnswer.None] when there is none, null when the service failed. */
    fun parse(answer: HttpAnswer): CanvasAnswer? {
        if (answer.status == 404 || answer.status == 400) return CanvasAnswer.None
        if (answer.status != 200) return null
        val o = runCatching { Json.parseToJsonElement(answer.body) as? JsonObject }.getOrNull() ?: return null
        if ((o["success"] as? JsonPrimitive)?.content == "false") return CanvasAnswer.None
        val id = (o["trackId"] as? JsonPrimitive)?.content?.takeIf(SpotifyTrack::isId)
        val url = ((o["canvasUrl"] ?: o["url"]) as? JsonPrimitive)?.content?.takeIf(CanvasService::isVideoUrl)
        return if (id != null && url != null) CanvasAnswer.Found(CanvasMatch(id, url)) else CanvasAnswer.None
    }

    /** Same song, same memo: case, spaces and accents aside. */
    fun key(title: String, artist: String?): String =
        "?" + listOf(title, artist.orEmpty()).joinToString("|") { java.text.Normalizer.normalize(it.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
}

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
