package zenith.app

import android.app.ActivityOptions
import android.app.PendingIntent
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
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import zenith.core.LikeAction
import zenith.core.PlaybackProgress
import zenith.core.PlayerActions
import zenith.core.SpotifyTrack
import zenith.core.VideoTitle

/** The track playing in another app (Spotify, Deezer, YouTube, YouTube Music…). */
data class MediaInfo(
    val title: String,
    /** Artist, or the channel for a video. */
    val artist: String?,
    val art: Bitmap?,
    val playing: Boolean,
    /** Package of the player, `com.spotify.music` for Spotify. */
    val player: String = "",
    /** Spotify track id (Spotify app only): its Canvas is looked up with it. */
    val spotifyTrackId: String? = null,
    /** Null when the player gives no duration or position (radio, live). */
    val progress: PlaybackProgress? = null,
    /** Heart button, when the player offers one (Spotify's Liked Songs). */
    val like: LikeAction? = null,
    /** A video (YouTube, a wide thumbnail): its picture is shown wide on the card. */
    val video: Boolean = false,
)

/**
 * Music card of the drive screen: Android's media sessions give the track of whatever player is in
 * use, its progress and its controls, with no account, no key and no server. Spotify also puts the
 * track's `spotify:track:<id>` in the media id (for the Canvas) and offers "like" as a custom
 * action. Reading other apps' sessions needs the notification access ([MediaListener] exists only
 * for that); without it nothing shows. Main thread only.
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
    private var likeAction: PlaybackState.CustomAction? = null

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
        likeAction = null
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

    /** Moves within a video by [deltaMs] from where it is now (the position moves on while playing). */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val p = c.playbackState ?: return
        val moved = if (p.state == PlaybackState.STATE_PLAYING) ((SystemClock.elapsedRealtime() - p.lastPositionUpdateTime) * p.playbackSpeed).toLong() else 0L
        runCatching { c.transportControls.seekTo((p.position + moved + deltaMs).coerceAtLeast(0L)) }
    }

    /** Adds the track to the player's likes, or takes it out (the action the player offers now). */
    fun toggleLike() {
        val a = likeAction ?: return
        runCatching { controller?.transportControls?.sendCustomAction(a.action, a.extras) }
    }

    /** Brings the player to the front: its own "open" intent, else its launcher activity. */
    fun open(context: Context) {
        val c = controller ?: return
        val opened = runCatching { c.sessionActivity?.let { send(it) } != null }.getOrDefault(false)
        if (!opened) {
            runCatching {
                context.packageManager.getLaunchIntentForPackage(c.packageName)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.onFailure { Log.w("Zenith", "open player", it) }
        }
    }

    /** Android 14+: an intent made by the player (in the background) starts only with Zenith's own right to. */
    private fun send(intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            intent.send(null, 0, null, null, null, null, options.toBundle())
        } else {
            intent.send()
        }
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
        val playback = c.playbackState
        val actions = playback?.customActions.orEmpty()
        val channel = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        // YouTube: "Artist - Song (Official Video)" by "ArtistVEVO" reads "Song" by "Artist".
        val (shownTitle, shownArtist) = if (c.packageName == VideoTitle.YOUTUBE) VideoTitle.display(title, channel) else title to channel
        val like = PlayerActions.like(actions.map { it.action })
        likeAction = like?.let { l -> actions.firstOrNull { it.action == l.action } }
        _state.value = MediaInfo(
            title = shownTitle,
            artist = shownArtist,
            art = art,
            playing = playback?.state == PlaybackState.STATE_PLAYING,
            player = c.packageName,
            spotifyTrackId = SpotifyTrack.idFromSession(
                c.packageName,
                md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            ),
            // Position as of lastPositionUpdateTime (elapsedRealtime), moving at playbackSpeed.
            progress = playback?.let {
                PlaybackProgress.of(it.position, it.lastPositionUpdateTime, it.playbackSpeed, md.getLong(MediaMetadata.METADATA_KEY_DURATION))
            },
            like = like,
            video = c.packageName == VideoTitle.YOUTUBE || (art?.let { it.width > it.height * 1.3f } ?: false),
        )
    }

    private const val ART_PX = 160
}

/** Only there so Android lets [NowPlaying] read the media sessions; it reads no notification. */
class MediaListener : NotificationListenerService()
