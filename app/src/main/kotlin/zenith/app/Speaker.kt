package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * French text-to-speech. [ducker] lowers (or pauses) the music while it speaks; an urgent alert
 * drops what is still queued instead of waiting behind it, so the driver never hears a stale one.
 */
class Speaker(context: Context, private val ducker: AudioDucker) : TextToSpeech.OnInitListener {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val tts = TextToSpeech(context, this)
    /** Said before the engine was ready: replayed as asked (after the tone, urgent or not) once it is. */
    private val pending = mutableListOf<() -> Unit>()
    private val counter = AtomicInteger(0)
    /** Utterances queued or speaking → whether each one paused the music. */
    private val queued = ConcurrentHashMap<String, Boolean>()
    private var ready = false
    /** No usable French voice: nothing is spoken (the beeps still sound). */
    private var muted = false

    /** A spoken alert is queued or under way (danger beeps wait for it). */
    val speaking: Boolean get() = queued.isNotEmpty()

    // Called from the drive thread and from the TTS init callback.
    @Synchronized
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            frenchMissing.value = true
            mute()
            return
        }
        // A device without a French voice (car head units): only the beeps, never French read
        // out by a foreign voice. Said in the settings.
        val french = listOf(Locale.FRANCE, Locale.FRENCH).firstOrNull {
            runCatching { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        }
        frenchMissing.value = french == null
        if (french == null) {
            mute()
            return
        }
        tts.language = french
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
        })
        ready = true
        pending.forEach { it() }
        pending.clear()
    }

    /**
     * Speaks [text] after [delayMs] of silence (to let an alert tone finish first). [urgent] drops
     * the queue; [pauseMusic] pauses the music rather than lowering it.
     */
    @Synchronized
    fun say(text: String, delayMs: Int = 0, urgent: Boolean = false, pauseMusic: Boolean = false) {
        if (muted) return
        if (!ready) {
            pending += { say(text, delayMs, urgent, pauseMusic) }
            return
        }
        val id = "zenith-${counter.incrementAndGet()}"
        queued[id] = pauseMusic
        ducker.acquire(pause = pauseMusic)
        // Without a tone before it, a Bluetooth output needs a moment to wake up.
        val wait = if (delayMs == 0 && ducker.bluetooth()) AudioDucker.BLUETOOTH_LEAD_MS else delayMs
        var mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (wait > 0) {
            tts.playSilentUtterance(wait.toLong(), mode, null)
            mode = TextToSpeech.QUEUE_ADD
        }
        if (tts.speak(text, mode, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    fun shutdown() {
        pending.clear()
        tts.stop()
        tts.shutdown()
        queued.clear()
    }

    private fun mute() {
        muted = true
        pending.clear()
    }

    companion object {
        /** No French voice on this device (seen at the last drive): the settings say how to get one. */
        val frenchMissing = MutableStateFlow(false)
    }

    private fun finished(utteranceId: String) {
        val paused = queued.remove(utteranceId) ?: return
        ducker.release(pause = paused)
    }
}
