package zenith.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import zenith.core.CanvasFinder
import zenith.core.HttpAnswer
import zenith.core.SpotifyTrack
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spotify Canvas videos for the music card and the picture-in-picture window. The video's address
 * comes from [CanvasFinder] (free unofficial services, no account); the MP4 (~3 MB) is downloaded
 * once into the cache and played from there, since a looping stream could fetch it again at each
 * loop. The oldest videos go past [MAX_CACHE_BYTES]. Every failure is silent: the cover stays.
 */
object SpotifyCanvas {
    private const val MAX_CACHE_BYTES = 60L * 1024 * 1024
    private const val MAX_VIDEO_BYTES = 16L * 1024 * 1024
    private const val AGENT = "Zenith (+https://github.com/LightZirconite/zenith)"

    private val finder = CanvasFinder(::ask)

    /** One download at a time: skipping songs fast does not pile them up. */
    private val downloads = Mutex()

    /** The video of [trackId] on the phone, downloaded if needed; null without canvas or on failure. */
    suspend fun video(context: Context, trackId: String): File? = withContext(Dispatchers.IO) {
        if (!SpotifyTrack.isId(trackId)) return@withContext null
        val dir = File(context.cacheDir, "canvas")
        val file = File(dir, "$trackId.mp4")
        try {
            if (file.length() > 0) return@withContext file.also { it.setLastModified(System.currentTimeMillis()) }
            val url = finder.find(trackId) ?: return@withContext null
            downloads.withLock {
                ensureActive()
                if (file.length() == 0L) {
                    download(url, dir, file)
                    trim(dir, file)
                }
            }
            file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Zenith", "canvas $trackId", e)
            finder.forget(trackId)
            null
        }
    }

    /** Forgets a video that would not play, so the next time asks for it afresh. */
    fun discard(trackId: String, file: File) {
        runCatching { file.delete() }
        finder.forget(trackId)
    }

    private fun download(url: String, dir: File, file: File) {
        dir.mkdirs()
        val part = File(dir, file.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", AGENT)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            if (conn.contentLengthLong > MAX_VIDEO_BYTES) throw IOException("too big: ${conn.contentLengthLong}")
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_VIDEO_BYTES) throw IOException("too big")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (!part.renameTo(file)) throw IOException("rename")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    /** Keeps the most recently played videos within [MAX_CACHE_BYTES]. */
    private fun trim(dir: File, keep: File) {
        val videos = dir.listFiles { f -> f.name.endsWith(".mp4") }.orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        for (f in videos) {
            total += f.length()
            if (total > MAX_CACHE_BYTES && f != keep) f.delete()
        }
    }

    /** A canvas service request: status and body, or null when the network failed. */
    private fun ask(url: String): HttpAnswer? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", AGENT)
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            HttpAnswer(status, stream?.bufferedReader()?.use { it.readText().take(64 * 1024) }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w("Zenith", "canvas lookup", it) }.getOrNull()
}

/**
 * The Canvas video of [trackId] once it is on the phone; null without one, while it downloads, or
 * when [enabled] is off. Each screen asks for itself: the second ask is a file already there.
 */
@Composable
fun rememberCanvas(trackId: String?, enabled: Boolean): File? {
    val context = LocalContext.current.applicationContext
    val file by produceState<File?>(null, trackId, enabled) {
        value = null
        if (enabled && trackId != null) value = SpotifyCanvas.video(context, trackId)
    }
    return file
}
