package zenith.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import zenith.core.SpotifyDuck
import zenith.core.SpotifyTrack
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the driver's Spotify plays, read from the Web API while a drive runs, for a driver without
 * the notification access (SpotifyPlayer.swift on iOS). Read about every 3 s while it plays with
 * Zenith in front, 5 s behind Waze or Maps, 10 s when nothing plays; a 429 waits as long as Spotify
 * asks. Not read while Spotify's own media session is there: [NowPlaying] shows that one. Commands
 * and the heart go through the same API (the commands need Premium); after one, the player is read
 * again at once. Spotify playing on another device (Connect: the car, a speaker) is lowered under
 * Zenith's sounds through the same account ([duckRemote], [SpotifyDuck]). Main thread; the network
 * on the IO dispatcher.
 */
object SpotifyPlayer {
    /** The item Spotify reported, and where it was when read ([readAtMs]: elapsedRealtime). */
    data class Track(
        /** Stable for the item: its URI. */
        val key: String,
        /** Spotify track id; null for an episode or a local file (no Canvas, no heart). */
        val trackId: String?,
        val title: String,
        val artist: String?,
        val imageUrl: String?,
        val durationMs: Long,
        val positionMs: Long,
        val readAtMs: Long,
        val playing: Boolean,
    ) {
        fun positionAt(nowMs: Long): Long {
            val moved = if (playing) (nowMs - readAtMs).coerceAtLeast(0) else 0L
            val at = (positionMs + moved).coerceAtLeast(0)
            return if (durationMs > 0) at.coerceAtMost(durationMs) else at
        }

        /** The same item at [positionMs] now, playing or not. */
        fun at(positionMs: Long, playing: Boolean) = copy(positionMs = positionMs, readAtMs = SystemClock.elapsedRealtime(), playing = playing)
    }

    var track: Track? = null
        private set
    var art: Bitmap? = null
        private set

    /** In the driver's Liked Songs; null while unknown or for an episode. */
    var liked: Boolean? = null
        private set

    /** Last read that found it playing (elapsedRealtime). */
    var lastPlayingAtMs = 0L
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** When to read the player next (elapsedRealtime). */
    private var dueMs = 0L
    private var poked = false
    private var artUrl: String? = null
    private val covers = LinkedHashMap<String, Bitmap>()

    /** A Connect device Zenith lowered, and the volumes before and after. */
    private data class Ducked(val deviceId: String, val before: Int, val duckedTo: Int)

    private var ducked: Ducked? = null
    /** Lowering and putting back run in the order asked, never crossing. */
    private val duckOrder = Mutex()
    /** Names Spotify may give this phone: its model, or the name set in Android's settings. */
    var phoneNames: List<String> = listOf(Build.MODEL)

    /** Follows the player until [stop]; idempotent, nothing without a client id. */
    fun start() {
        if (loop?.isActive == true || !SpotifyAuth.state.value.configured) return
        loop = scope.launch {
            // Name and Premium again for this drive: a lapsed or new subscription shows.
            SpotifyAuth.refreshProfile()
            NowPlaying.spotifyWebChanged()
            while (isActive) {
                if (SystemClock.elapsedRealtime() >= dueMs) poll()
                val wait = dueMs - SystemClock.elapsedRealtime()
                if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** End of a drive: nothing kept, the first read of the next one comes at once. */
    fun stop() {
        loop?.cancel()
        loop = null
        track = null
        art = null
        artUrl = null
        liked = null
        poked = false
        dueMs = 0L
    }

    /** Read again within [afterMs], whatever was planned. */
    fun poke(afterMs: Long) {
        poked = true
        dueMs = minOf(dueMs, SystemClock.elapsedRealtime() + afterMs)
        wake.trySend(Unit)
    }

    fun playPause() {
        val t = track ?: return
        val play = !t.playing
        // Shown at once; the next read confirms.
        set(t.at(t.positionAt(SystemClock.elapsedRealtime()), play))
        command("PUT", if (play) "/v1/me/player/play" else "/v1/me/player/pause")
    }

    fun next() = command("POST", "/v1/me/player/next")

    fun previous() = command("POST", "/v1/me/player/previous")

    fun seekBy(deltaMs: Long) {
        val t = track ?: return
        if (t.durationMs <= 0) return
        val to = (t.positionAt(SystemClock.elapsedRealtime()) + deltaMs).coerceIn(0, t.durationMs)
        set(t.at(to, t.playing))
        command("PUT", "/v1/me/player/seek", listOf("position_ms" to to.toString()))
    }

    /** Adds the track to Liked Songs or takes it out; put back if Spotify refuses. */
    fun toggleLike() {
        val id = track?.trackId ?: return
        val was = liked ?: return
        liked = !was
        NowPlaying.spotifyWebChanged()
        scope.launch {
            val answer = SpotifyAuth.request(if (was) "DELETE" else "PUT", "/v1/me/library", listOf("uris" to "spotify:track:$id"))
            if (answer != null && answer.status in 200..299) return@launch
            if (track?.trackId != id) return@launch
            liked = was
            NowPlaying.spotifyWebChanged()
        }
    }

    /**
     * Zenith's sounds start ([AudioDucker] takes the focus): Spotify playing on another device is
     * lowered too. Any thread, never waits: the sound plays at once, the volume follows.
     */
    fun duckRemote() {
        scope.launch {
            duckOrder.withLock {
                if (ducked != null || !SpotifyAuth.state.value.connected) return@withLock
                val device = activeDevice() ?: return@withLock
                if (!SpotifyDuck.shouldDuck(
                        device.type, device.name, device.supportsVolume, device.playing,
                        SpotifyAuth.state.value.premium, phoneNames,
                    )
                ) return@withLock
                val before = device.volume ?: return@withLock
                val to = SpotifyDuck.duckedVolume(before)
                if (to >= before) return@withLock
                if (setVolume(device.id, to)) ducked = Ducked(device.id, before, to)
            }
        }
    }

    /** Zenith's sounds are over (the focus is given back): the lowered device goes back, unless the driver changed it. */
    fun restoreRemote() {
        scope.launch {
            duckOrder.withLock {
                val d = ducked ?: return@withLock
                ducked = null
                val now = activeDevice()?.takeIf { it.id == d.deviceId }?.volume
                val back = SpotifyDuck.restoreVolume(d.before, d.duckedTo, now) ?: return@withLock
                setVolume(d.deviceId, back)
            }
        }
    }

    /** The device Spotify plays on (`GET /v1/me/player`). */
    private data class Device(val id: String, val name: String?, val type: String?, val volume: Int?, val supportsVolume: Boolean, val playing: Boolean)

    private suspend fun activeDevice(): Device? {
        val answer = SpotifyAuth.request("GET", "/v1/me/player") ?: return null
        if (answer.status != 200 || answer.body.isBlank()) return null
        return runCatching<Device?> {
            val player = JSONObject(answer.body)
            val device = player.optJSONObject("device") ?: return@runCatching null
            val id = device.text("id") ?: return@runCatching null
            if (!device.optBoolean("is_active", true)) return@runCatching null
            Device(
                id = id,
                name = device.text("name"),
                type = device.text("type"),
                volume = if (device.isNull("volume_percent")) null else device.optInt("volume_percent"),
                supportsVolume = device.optBoolean("supports_volume", false),
                playing = player.optBoolean("is_playing", false),
            )
        }.getOrNull()
    }

    private suspend fun setVolume(deviceId: String, percent: Int): Boolean {
        val answer = SpotifyAuth.request(
            "PUT", "/v1/me/player/volume", listOf("volume_percent" to percent.toString(), "device_id" to deviceId),
        ) ?: return false
        if (answer.status == 403 && answer.reason == "PREMIUM_REQUIRED") SpotifyAuth.notePremium(false)
        if (answer.status in 200..299) SpotifyAuth.notePremium(true)
        return answer.status in 200..299
    }

    // Reading

    /** Reads what plays and plans the next read. */
    private suspend fun poll() {
        poked = false
        if (!SpotifyAuth.state.value.connected || NowPlaying.spotifySessionPresent()) {
            // Not connected, or Spotify's own media session shows it already: no card from here.
            set(null)
            schedule(10_000)
            return
        }
        val sent = SystemClock.elapsedRealtime()
        val answer = SpotifyAuth.request("GET", "/v1/me/player/currently-playing", listOf("additional_types" to "track,episode"))
        if (answer == null) {
            // Offline: the card keeps what it had.
            schedule(10_000)
            return
        }
        // The position was taken halfway through the request.
        val readAt = sent + (SystemClock.elapsedRealtime() - sent) / 2
        when (answer.status) {
            200 -> set(if (answer.body.isBlank()) null else parse(answer.body, readAt))
            204 -> set(null)
            429 -> {
                schedule((answer.retryAfterS ?: 30L).coerceIn(1L, 3_600L) * 1000)
                return
            }
            else -> {
                Log.w("Zenith", "spotify currently playing ${answer.status}")
                schedule(30_000)
                return
            }
        }
        schedule(if (track?.playing == true) (if (MainActivity.inFront) 3_000 else 5_000) else 10_000)
    }

    private fun schedule(afterMs: Long) {
        val next = SystemClock.elapsedRealtime() + afterMs
        dueMs = if (poked) minOf(dueMs, next) else next
        poked = false
    }

    private fun set(new: Track?) {
        if (new?.playing == true) lastPlayingAtMs = SystemClock.elapsedRealtime()
        val old = track
        if (new == old) return
        track = new
        if (new?.trackId != old?.trackId) {
            liked = null
            new?.trackId?.let(::loadLiked)
        }
        if (new?.imageUrl != artUrl) loadArt(new?.imageUrl)
        NowPlaying.spotifyWebChanged()
    }

    private fun command(method: String, path: String, query: List<Pair<String, String>> = emptyList()) {
        scope.launch {
            val answer = SpotifyAuth.request(method, path, query) ?: return@launch
            when {
                answer.status in 200..299 -> SpotifyAuth.notePremium(true)
                answer.status == 403 && answer.reason == "PREMIUM_REQUIRED" -> SpotifyAuth.notePremium(false)
                else -> Log.w("Zenith", "spotify $path ${answer.status} ${answer.reason.orEmpty()}")
            }
            // The controls dim or light up with Premium.
            NowPlaying.spotifyWebChanged()
            // Spotify takes a moment to switch tracks: read again shortly after.
            poke(600)
        }
    }

    private fun loadLiked(id: String) {
        scope.launch {
            val answer = SpotifyAuth.request("GET", "/v1/me/library/contains", listOf("uris" to "spotify:track:$id")) ?: return@launch
            if (answer.status != 200) return@launch
            val first = runCatching { JSONArray(answer.body).getBoolean(0) }.getOrNull() ?: return@launch
            if (track?.trackId != id) return@launch
            liked = first
            NowPlaying.spotifyWebChanged()
        }
    }

    private fun loadArt(url: String?) {
        artUrl = url
        if (url == null) {
            art = null
            return
        }
        covers[url]?.let {
            art = it
            return
        }
        // Never the last track's cover under the new title.
        art = null
        scope.launch {
            val cover = download(url) ?: return@launch
            if (covers.size >= 8) covers.remove(covers.keys.first())
            covers[url] = cover
            if (artUrl != url) return@launch
            art = cover
            NowPlaying.spotifyWebChanged()
        }
    }

    /** The cover, decoded small: the card and the blurred backdrop need no more. */
    private suspend fun download(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching<Bitmap?> {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                if (conn.responseCode != 200) return@runCatching null
                val bytes = conn.inputStream.use { it.readBytes() }
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                if (image.width > NowPlaying.ART_PX) {
                    Bitmap.createScaledBitmap(image, NowPlaying.ART_PX, NowPlaying.ART_PX * image.height / image.width, true)
                } else {
                    image
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** The item of a currently-playing answer; null for an ad or an unreadable answer. */
    private fun parse(body: String, readAtMs: Long): Track? = runCatching<Track?> {
        val now = JSONObject(body)
        val item = now.optJSONObject("item") ?: return@runCatching null
        val name = item.text("name") ?: return@runCatching null
        val artists = item.optJSONArray("artists")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.text("name") }.joinToString(", ")
        }.orEmpty()
        val show = item.optJSONObject("show")
        val album = item.optJSONObject("album")
        val uri = item.text("uri")
        Track(
            key = uri ?: item.text("id") ?: name,
            trackId = SpotifyTrack.idOf(uri),
            title = name,
            artist = artists.ifEmpty { show?.text("name") },
            imageUrl = image(album?.optJSONArray("images") ?: item.optJSONArray("images") ?: show?.optJSONArray("images")),
            durationMs = item.optLong("duration_ms", 0L).coerceAtLeast(0),
            positionMs = now.optLong("progress_ms", 0L).coerceAtLeast(0),
            readAtMs = readAtMs,
            playing = now.optBoolean("is_playing", false),
        )
    }.getOrNull()

    /** The smallest cover still sharp on the card (Spotify gives 640, 300 and 64 px). */
    private fun image(images: JSONArray?): String? {
        if (images == null) return null
        val sized = (0 until images.length()).mapNotNull { i ->
            images.optJSONObject(i)?.let { o -> o.text("url")?.let { it to o.optInt("width", 0) } }
        }
        return (sized.filter { it.second >= 200 }.minByOrNull { it.second } ?: sized.maxByOrNull { it.second })?.first
    }
}
