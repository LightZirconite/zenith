package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Makes room for Zenith's sounds, like a navigation app. The music ducks under the beeps and the
 * voice: Android slides it down and back up smoothly, a little after the last sound so close alerts
 * do not make it pump. A voice may pause it instead (setting): the music slides down first, then
 * stops, and plays on ducked if beeps go on once the voice is done; beeps alone never pause it.
 * One focus owner for the tones and the voice, so one never releases the other's.
 */
class AudioDucker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val duckFocus = focus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    private val pauseFocus = focus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    private val abandon = Runnable { releaseNow() }
    private val toPause = Runnable { pauseNow() }
    private val toDuck = Runnable { duckNow() }
    private var holders = 0
    private var pausers = 0
    private var held: AudioFocusRequest? = null
    private var duckedAtMs = 0L

    /** [pause]: the music must stop rather than duck (spoken alerts, setting; the start of a drive). */
    @Synchronized
    fun acquire(pause: Boolean = false) {
        holders++
        handler.removeCallbacks(abandon)
        if (held == null) request(duckFocus)
        if (!pause) return
        pausers++
        handler.removeCallbacks(toDuck)
        if (held !== pauseFocus) {
            // Ducked first: the music is already low when it stops, never a hard cut.
            handler.removeCallbacks(toPause)
            handler.postDelayed(toPause, (duckedAtMs + FADE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
    }

    /**
     * The music comes back [holdMs] after the last holder is done, unless another sound starts. The
     * last pausing holder gone while others remain, it plays on ducked after the same wait.
     */
    @Synchronized
    fun release(pause: Boolean = false, holdMs: Long = HOLD_MS) {
        holders = (holders - 1).coerceAtLeast(0)
        if (pause) pausers = (pausers - 1).coerceAtLeast(0)
        if (pausers == 0) handler.removeCallbacks(toPause)
        when {
            holders == 0 -> {
                handler.removeCallbacks(toDuck)
                handler.postDelayed(abandon, holdMs)
            }
            pause && pausers == 0 -> handler.postDelayed(toDuck, holdMs)
        }
    }

    /** Ducks for a sound of [durationMs] starting now, the music back [holdMs] after it. */
    fun duckFor(durationMs: Long, holdMs: Long = HOLD_MS) {
        acquire()
        handler.postDelayed({ release(holdMs = holdMs) }, durationMs)
    }

    /**
     * A Bluetooth output wakes up on the first samples and swallows them: sounds then start after a
     * short silence. Checked at each sound, the car can connect during the drive.
     */
    fun bluetooth(): Boolean = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH }
    }.getOrDefault(false)

    @Synchronized
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        holders = 0
        pausers = 0
        releaseNow()
    }

    @Synchronized
    private fun releaseNow() {
        if (holders == 0) {
            held?.let(audio::abandonAudioFocusRequest)
            held = null
        }
    }

    @Synchronized
    private fun pauseNow() {
        if (pausers > 0 && held !== pauseFocus) request(pauseFocus)
    }

    /**
     * Back from pause to duck. A paused player only plays again when it gets the focus back: give
     * it back, then duck at once; Android ducks a player that starts while another app ducks.
     */
    @Synchronized
    private fun duckNow() {
        if (pausers > 0 || holders == 0 || held !== pauseFocus) return
        audio.abandonAudioFocusRequest(pauseFocus)
        held = null
        request(duckFocus)
    }

    private fun request(wanted: AudioFocusRequest) {
        if (audio.requestAudioFocus(wanted) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (wanted === duckFocus) duckedAtMs = SystemClock.uptimeMillis()
        held = wanted
    }

    private fun focus(gain: Int) = AudioFocusRequest.Builder(gain).setAudioAttributes(attributes).build()

    companion object {
        private const val HOLD_MS = 1_500L

        /** Android's own duck ramp takes about half a second: the pause waits for the bottom of it. */
        private const val FADE_MS = 650L

        /** Silence played before a sound on Bluetooth. */
        const val BLUETOOTH_LEAD_MS = 300

        private val BLUETOOTH = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
