package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** French text-to-speech; [ducker] lowers music and navigation audio while it speaks. */
class Speaker(context: Context, private val ducker: AudioDucker) : TextToSpeech.OnInitListener {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val tts = TextToSpeech(context, this)
    private val pending = mutableListOf<String>()
    private val counter = AtomicInteger(0)
    private var ready = false

    // Called from the drive thread and from the TTS init callback.
    @Synchronized
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.FRANCE
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = ducker.release()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = ducker.release()
        })
        ready = true
        pending.forEach { say(it) }
        pending.clear()
    }

    /** Speaks [text] after [delayMs] of silence (to let an alert tone finish first). */
    @Synchronized
    fun say(text: String, delayMs: Int = 0) {
        if (!ready) {
            pending += text
            return
        }
        ducker.acquire()
        if (delayMs > 0) tts.playSilentUtterance(delayMs.toLong(), TextToSpeech.QUEUE_ADD, null)
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, "zenith-${counter.incrementAndGet()}") != TextToSpeech.SUCCESS) ducker.release()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
