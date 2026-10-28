package zenith.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/**
 * A Spotify Canvas: [file] played muted and looping, center-cropped, on a TextureView (it blends
 * with the Compose gradients drawn over it). It plays only while [playing] and while Zenith is
 * started: screen on, in front or in picture-in-picture (paused but visible, like the map). The
 * player is released with the composable. [onShown] comes with the first frame; [onFailed] when
 * the video cannot play, never a crash.
 */
@Composable
fun CanvasVideo(file: File, playing: Boolean, modifier: Modifier = Modifier, onShown: () -> Unit = {}, onFailed: () -> Unit = {}) {
    val shown by rememberUpdatedState(onShown)
    val failed by rememberUpdatedState(onFailed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A new file is a new view and a new player: AndroidView never calls its factory twice.
    key(file) {
        val loop = remember { LoopPlayer(file, { shown() }, { failed() }) }
        DisposableEffect(loop, lifecycle) {
            // Brought up to the current state at once (ON_START if already started).
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> loop.started = true
                    Lifecycle.Event.ON_STOP -> loop.started = false
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                loop.release()
            }
        }
        SideEffect { loop.playing = playing }
        AndroidView(factory = loop::view, modifier = modifier)
    }
}

/** Owns the TextureView and its MediaPlayer; main thread only, every call safe in any state. */
private class LoopPlayer(private val file: File, private val onShown: () -> Unit, private val onFailed: () -> Unit) :
    TextureView.SurfaceTextureListener {
    private var view: TextureView? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var failed = false
    private var released = false
    private var videoWidth = 0
    private var videoHeight = 0

    var started = false
        set(value) {
            field = value
            sync()
        }
    var playing = false
        set(value) {
            field = value
            sync()
        }

    fun view(context: Context): View = TextureView(context).also {
        it.isOpaque = false
        it.surfaceTextureListener = this
        view = it
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (failed || released) return
        try {
            val s = Surface(texture)
            surface = s
            player = MediaPlayer().apply {
                setOnPreparedListener {
                    prepared = true
                    sync()
                }
                setOnVideoSizeChangedListener { _, w, h ->
                    videoWidth = w
                    videoHeight = h
                    crop()
                }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onShown()
                    false
                }
                setOnErrorListener { _, what, extra ->
                    fail("error $what/$extra")
                    true
                }
                setSurface(s)
                setDataSource(file.path)
                isLooping = true
                setVolume(0f, 0f)
                prepareAsync()
            }
        } catch (e: Exception) {
            fail(e.toString())
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        released = true
        releasePlayer()
    }

    /** Plays only while wanted and visible; the decoder rests otherwise. */
    private fun sync() {
        val p = player ?: return
        if (!prepared) return
        try {
            if (started && playing) {
                if (!p.isPlaying) p.start()
            } else if (p.isPlaying) {
                p.pause()
            }
        } catch (e: Exception) {
            fail(e.toString())
        }
    }

    /** Fills the view like ContentScale.Crop: the TextureView stretches the video, the matrix undoes it. */
    private fun crop() {
        val v = view ?: return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 0f || h <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val scale = maxOf(w / videoWidth, h / videoHeight)
        runCatching { v.setTransform(Matrix().apply { setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2, h / 2) }) }
    }

    private fun releasePlayer() {
        val p = player
        player = null
        prepared = false
        runCatching { p?.release() }
        runCatching { surface?.release() }
        surface = null
    }

    private fun fail(why: String) {
        Log.w("Zenith", "canvas video: $why")
        failed = true
        releasePlayer()
        onFailed()
    }
}
