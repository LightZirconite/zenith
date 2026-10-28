package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Makes room for Zenith's sounds, like a navigation app: the music ducks under a beep and pauses
 * while the voice speaks (an alert must be understood, over the car's Bluetooth too), then comes
 * back a little after the last sound so close alerts do not make it pump. One focus owner for the
 * tones and the voice, so one never releases the other's.
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
    private var holders = 0
    private var pausers = 0
    private var held: AudioFocusRequest? = null

    /** [pause]: the music must stop rather than duck (spoken alerts). */
    @Synchronized
    fun acquire(pause: Boolean = false) {
        holders++
        if (pause) pausers++
        handler.removeCallbacks(abandon)
        val wanted = if (pausers > 0) pauseFocus else duckFocus
        // Never step down from pause to duck while a holder remains: the music would flap.
        if (held == null || (wanted === pauseFocus && held !== pauseFocus)) {
            if (audio.requestAudioFocus(wanted) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) held = wanted
        }
    }

    /** The music comes back [holdMs] after the last holder is done, unless another sound starts. */
    @Synchronized
    fun release(pause: Boolean = false, holdMs: Long = HOLD_MS) {
        holders = (holders - 1).coerceAtLeast(0)
        if (pause) pausers = (pausers - 1).coerceAtLeast(0)
        if (holders == 0) handler.postDelayed(abandon, holdMs)
    }

    /** Ducks for a sound of [durationMs] starting now. */
    fun duckFor(durationMs: Long) {
        acquire()
        handler.postDelayed({ release() }, durationMs)
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

    private fun focus(gain: Int) = AudioFocusRequest.Builder(gain).setAudioAttributes(attributes).build()

    companion object {
        private const val HOLD_MS = 1_500L

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
