package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Lowers music and navigation audio while Zenith beeps or speaks, like Coyote. Shared by the tones
 * and the voice (a single focus owner, so one never releases the other's), and released a little
 * after the last sound so fast danger beeps do not make the music pump.
 */
class AudioDucker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()
    private val abandon = Runnable { releaseNow() }
    private var holders = 0
    private var held = false

    @Synchronized
    fun acquire() {
        holders++
        handler.removeCallbacks(abandon)
        if (!held) held = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** The music comes back [holdMs] after the last holder is done, unless another sound starts. */
    @Synchronized
    fun release(holdMs: Long = HOLD_MS) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) handler.postDelayed(abandon, holdMs)
    }

    /** Ducks for a sound of [durationMs] starting now. */
    fun duckFor(durationMs: Long) {
        acquire()
        handler.postDelayed({ release() }, durationMs)
    }

    @Synchronized
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        holders = 0
        releaseNow()
    }

    @Synchronized
    private fun releaseNow() {
        if (holders == 0 && held) {
            audio.abandonAudioFocusRequest(focus)
            held = false
        }
    }

    private companion object {
        const val HOLD_MS = 1_500L
    }
}
