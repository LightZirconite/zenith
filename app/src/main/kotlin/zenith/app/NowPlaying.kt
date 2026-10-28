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
import zenith.core.PlayerChoice
import zenith.core.PlayerSession
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
    /** Any other music player (Deezer, YouTube Music, Apple Music…): its Canvas is looked up by title. */
    val canvasQuery: CanvasQuery? = null,
)

/** A track known by its words, to find its Spotify twin and Canvas. */
data class CanvasQuery(val title: String, val artist: String, val album: String?, val durationMs: Long?)

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
    /** Every active session, all watched: one starting to play can take the card. */
    private var sessionsSeen: List<MediaController> = emptyList()
    private var artSource: Bitmap? = null
    private var art: Bitmap? = null
    private var likeAction: PlaybackState.CustomAction? = null

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = choose()
        override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
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
        sessionsSeen.forEach { runCatching { it.unregisterCallback(callback) } }
        sessionsSeen = emptyList()
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

    /** New list of sessions (Android lists them most recent first): watches them all, then chooses. */
    private fun pick(list: List<MediaController>) {
        val known = sessionsSeen.map { it.sessionToken }.toSet()
        val now = list.map { it.sessionToken }.toSet()
        sessionsSeen.filter { it.sessionToken !in now }.forEach { runCatching { it.unregisterCallback(callback) } }
        list.filter { it.sessionToken !in known }.forEach { runCatching { it.registerCallback(callback, handler) } }
        sessionsSeen = list
        choose()
    }

    /** The card follows [PlayerChoice]: what plays, music first; else the last music app. */
    private fun choose() {
        val candidates = sessionsSeen.map { c ->
            val md = c.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            PlayerSession(idOf(c), c.packageName, c.playbackState?.state == PlaybackState.STATE_PLAYING, !title.isNullOrBlank())
        }
        val chosen = PlayerChoice.pick(candidates, controller?.let(::idOf))?.let { p -> sessionsSeen.firstOrNull { idOf(it) == p.id } }
        if (chosen?.sessionToken != controller?.sessionToken) controller = chosen
        publish()
    }

    private fun idOf(c: MediaController) = c.sessionToken.hashCode().toString()

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
            // Spotify gives its own id; a YouTube video is no song.
            canvasQuery = shownArtist?.takeIf { c.packageName != SpotifyTrack.PACKAGE && c.packageName != VideoTitle.YOUTUBE }?.let {
                CanvasQuery(shownTitle, it, md.getString(MediaMetadata.METADATA_KEY_ALBUM), md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { d -> d > 0 })
            },
        )
    }

    private const val ART_PX = 160
}

/** Only there so Android lets [NowPlaying] read the media sessions; it reads no notification. */
class MediaListener : NotificationListenerService()
