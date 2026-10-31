package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import zenith.core.Loudness
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The sounds under the finger, synthesized at launch (no assets) and played by a [SoundPool]: round
 * soft pops gliding in pitch, airy swells, a warm little arpeggio, each with a soft attack and a
 * fade, never a click or a bell. Every press also gives a light tick of the phone's touch feedback
 * (its own setting decides). On the media volume, like the flight: the system sounds' volume is
 * silent on vibrate. No audio focus, the music is never touched. Setting "Sons de l'interface".
 */
object UiSound {
    enum class Kind {
        /** Any button: a soft round pop. */
        TAP,

        /** A switch going on (rising pair) or off (falling pair). */
        ON,
        OFF,

        /** A page or another app opening (rising breath), a page closing (falling). */
        OPEN,
        CLOSE,

        /** Something done: a permission granted, a car added, the demo. A warm rising arpeggio. */
        SUCCESS,

        /** Spotify's heart: a bubble rising, or falling. */
        LIKE,
        UNLIKE,

        /** Play (a pop gliding up) and pause (gliding down). */
        PLAY,
        PAUSE,

        /** A swipe on the music card armed: a tiny tick. Let go: air moving past. */
        TICK,
        SWIPE,

        /** The music takes the screen (a swell rising) or gives it back (falling). */
        MUSIC_UP,
        MUSIC_DOWN,

        /** The map comes back on the car. */
        RECENTER,

        /** Start and Stop: only the touch, the flight has its own sounds. */
        SILENT,
    }

    private const val RATE = 48_000
    private const val TWO_PI = 2 * PI.toFloat()

    @Volatile
    private var pool: SoundPool? = null
    @Volatile
    private var audio: AudioManager? = null
    private val ids = AtomicIntegerArray(Kind.entries.size)

    /** Builds the sounds once per launch, off the main thread; a press before they are ready is silent. */
    fun init(context: Context) {
        if (pool != null) return
        audio = context.applicationContext.getSystemService(AudioManager::class.java)
        val sounds = runCatching {
            SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        }.getOrNull() ?: return
        pool = sounds
        val dir = File(context.cacheDir, "ui-sounds")
        thread(name = "zenith-ui-sounds", isDaemon = true) {
            runCatching { dir.mkdirs() }
            for (kind in Kind.entries) {
                val samples = wave(kind) ?: continue
                runCatching {
                    // Written again at each launch: a new version's sounds never meet an old file.
                    val file = File(dir, "${kind.name.lowercase()}.wav")
                    file.writeBytes(wav(samples))
                    ids.set(kind.ordinal, sounds.load(file.path, 1))
                }
            }
        }
    }

    /** [kind]'s sound (setting on) and the touch's tick on [view]; [long] for a long press. */
    fun play(kind: Kind, view: View? = null, long: Boolean = false) {
        view?.let { runCatching { it.performHapticFeedback(if (long) HapticFeedbackConstants.LONG_PRESS else haptic(kind)) } }
        if (!UserSettings.state.value.uiSounds) return
        val id = ids.get(kind.ordinal)
        if (id == 0) return
        // Turned down when the media volume is high (car head unit): never louder than the alerts.
        val gain = runCatching { audio?.let { Loudness.tapGain(it.getStreamVolume(AudioManager.STREAM_MUSIC), it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) } }
            .getOrNull() ?: 1f
        runCatching { pool?.play(id, gain, gain, 1, 0, 1f) }
    }

    private fun haptic(kind: Kind): Int = when (kind) {
        Kind.TICK -> HapticFeedbackConstants.CLOCK_TICK
        Kind.ON -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
        Kind.OFF -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CONTEXT_CLICK
        Kind.SUCCESS, Kind.SILENT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        Kind.SWIPE -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK
        else -> HapticFeedbackConstants.CONTEXT_CLICK
    }

    private fun wave(kind: Kind): FloatArray? = when (kind) {
        Kind.TAP -> pcm(0.12f) { t -> 0.5f * pop(t, 940f, 600f, 0.024f) + 0.16f * pop(t, 240f, 180f, 0.018f) }
        Kind.ON -> pcm(0.24f) { t -> 0.36f * pop(t, 640f, 520f, 0.03f) + 0.46f * pop(t - 0.075f, 980f, 800f, 0.04f) }
        Kind.OFF -> pcm(0.24f) { t -> 0.4f * pop(t, 900f, 760f, 0.03f) + 0.4f * pop(t - 0.075f, 600f, 480f, 0.04f) }
        Kind.OPEN -> breath(0.34f, 380f, 820f, 0.42f)
        Kind.CLOSE -> breath(0.3f, 760f, 360f, 0.38f)
        // E4, G#4, B4: the lock notes of the flight, quicker and softer.
        Kind.SUCCESS -> pcm(0.75f) { t ->
            0.3f * (note(t, 329.63f, 0.22f) + note(t - 0.075f, 415.3f, 0.24f) + 1.1f * note(t - 0.15f, 493.88f, 0.32f))
        }
        Kind.LIKE -> pcm(0.3f) { t -> 0.5f * pop(t, 480f, 1_100f, 0.07f) + 0.12f * pop(t - 0.05f, 1_480f, 1_650f, 0.05f) }
        Kind.UNLIKE -> pcm(0.24f) { t -> 0.45f * pop(t, 900f, 440f, 0.06f) }
        Kind.PLAY -> pcm(0.2f) { t -> 0.48f * pop(t, 520f, 860f, 0.045f) }
        Kind.PAUSE -> pcm(0.2f) { t -> 0.48f * pop(t, 820f, 500f, 0.04f) }
        Kind.TICK -> pcm(0.05f) { t -> 0.3f * pop(t, 1_500f, 1_250f, 0.007f) }
        Kind.SWIPE -> breath(0.26f, 300f, 700f, 0.5f, tone = 0.1f)
        Kind.MUSIC_UP -> breath(0.42f, 260f, 620f, 0.4f)
        Kind.MUSIC_DOWN -> breath(0.42f, 620f, 260f, 0.4f)
        Kind.RECENTER -> pcm(0.28f) { t -> 0.3f * pop(t, 700f, 560f, 0.03f) + 0.3f * pop(t - 0.06f, 700f, 840f, 0.04f) }
        Kind.SILENT -> null
    }

    /**
     * A soft round pop: a sine gliding quickly from [from] to [to] Hz with a quiet octave, a 3 ms
     * attack and an exponential fade of time constant [decay].
     */
    private fun pop(t: Float, from: Float, to: Float, decay: Float): Float {
        if (t < 0f) return 0f
        val glide = 0.014f
        val phase = TWO_PI * (to * t + (from - to) * glide * (1f - exp(-t / glide)))
        return (t / 0.003f).coerceAtMost(1f) * exp(-t / decay) * (sin(phase) + 0.22f * sin(2 * phase))
    }

    /** A soft sine note with a slow-ish attack and a fade, no bell. */
    private fun note(t: Float, hz: Float, decay: Float): Float {
        if (t < 0f) return 0f
        val w = TWO_PI * hz * t
        return (t / 0.008f).coerceAtMost(1f) * exp(-t / decay) * (sin(w) + 0.15f * sin(2 * w))
    }

    /**
     * Air moving: filtered noise swelling and settling over [seconds], its color and a faint tone
     * gliding from [from] to [to] Hz.
     */
    private fun breath(seconds: Float, from: Float, to: Float, level: Float, tone: Float = 0.18f): FloatArray {
        val random = Random(11)
        var low = 0f
        var band = 0f
        var phase = 0f
        return pcm(seconds) { t ->
            val k = t / seconds
            val hz = from + (to - from) * k * k * (3 - 2 * k)
            // Noise through a one-pole low-pass whose cutoff follows the glide, minus a slower one: a band of air.
            low += (random.nextFloat() * 2 - 1 - low) * (TWO_PI * hz * 2.2f / RATE).coerceAtMost(1f)
            band += (low - band) * (TWO_PI * hz * 0.35f / RATE)
            phase += TWO_PI * hz / RATE
            val env = sin(PI.toFloat() * k).let { it * it }
            level * env * (2.6f * (low - band) + tone * sin(phase))
        }
    }

    private fun pcm(seconds: Float, sample: (Float) -> Float): FloatArray {
        val n = (seconds * RATE).toInt()
        return FloatArray(n) { i ->
            // The last 15 ms down to silence: never a hard end.
            val tail = ((n - i) / (0.015f * RATE)).coerceAtMost(1f)
            (sample(i.toFloat() / RATE) * tail).coerceIn(-1f, 1f)
        }
    }

    /** 16-bit mono WAV, what SoundPool reads. */
    private fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
            .putShort(2).putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        for (v in samples) out.putShort((v * Short.MAX_VALUE).toInt().toShort())
        return out.array()
    }
}
