package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** French text-to-speech that ducks music and navigation audio while it speaks. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val tts = TextToSpeech(context, this)
    private val pending = mutableListOf<String>()
    private val speaking = AtomicInteger(0)
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.FRANCE
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished()
        })
        ready = true
        pending.forEach(::say)
        pending.clear()
    }

    fun say(text: String) {
        if (!ready) {
            pending += text
            return
        }
        if (speaking.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        audio.abandonAudioFocusRequest(focus)
    }

    private fun finished() {
        if (speaking.decrementAndGet() <= 0) {
            speaking.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
