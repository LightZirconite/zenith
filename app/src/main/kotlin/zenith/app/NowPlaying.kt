package zenith.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The track playing in another app (Spotify, Deezer, YouTube Music…). */
data class MediaInfo(val title: String, val artist: String?, val art: Bitmap?, val playing: Boolean)

/**
 * Music bar of the drive screen: Android's media sessions give the track of whatever player is in
 * use and its controls, with no account, no key and no server. Reading other apps' sessions needs
 * the notification access ([MediaListener] exists only for that); without it nothing shows.
 * Main thread only.
 */
object NowPlaying {
    private val _state = MutableStateFlow<MediaInfo?>(null)
    val state: StateFlow<MediaInfo?> = _state

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    private var artSource: Bitmap? = null
    private var art: Bitmap? = null

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun allowed(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** Android's switch for Zenith's notification access (the screen for Zenith alone on Android 11+). */
    fun accessIntent(context: Context): Intent {
        val name = ComponentName(context, MediaListener::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, name.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    /** Starts following the players; cheap and idempotent, does nothing without the access. */
    fun start(context: Context) {
        if (manager != null || !allowed(context)) return
        val m = context.getSystemService(MediaSessionManager::class.java)
        val name = ComponentName(context, MediaListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessions, name, handler)
            manager = m
            component = name
            pick(m.getActiveSessions(name))
        }.onFailure { Log.w("Zenith", "media sessions", it) }
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessions) }
        controller?.unregisterCallback(callback)
        manager = null
        controller = null
        artSource = null
        art = null
        _state.value = null
    }

    fun playPause() {
        val c = controller ?: return
        runCatching { if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
    }

    fun next() {
        runCatching { controller?.transportControls?.skipToNext() }
    }

    fun previous() {
        runCatching { controller?.transportControls?.skipToPrevious() }
    }

    private fun refresh() {
        val m = manager ?: return
        val name = component ?: return
        runCatching { pick(m.getActiveSessions(name)) }
    }

    /** The player playing, else the most recent one (Android lists them most recent first). */
    private fun pick(list: List<MediaController>) {
        val chosen = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            chosen?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || md == null || title.isNullOrBlank()) {
            _state.value = null
            return
        }
        val source = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (source !== artSource) {
            artSource = source
            // Covers can be large: a small copy is all the bar needs.
            art = source?.let { if (it.width > ART_PX) Bitmap.createScaledBitmap(it, ART_PX, ART_PX * it.height / it.width, true) else it }
        }
        _state.value = MediaInfo(
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            art = art,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private const val ART_PX = 160
}

/** Only there so Android lets [NowPlaying] read the media sessions; it reads no notification. */
class MediaListener : NotificationListenerService()
