package zenith.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Small sounds of the interface, synthesized (no files): a soft round tap on every button, a rising
 * or falling pair for a switch, a tick when a swipe on the music card is armed and a breath of air
 * when it changes track. Quiet, soft attack and fade, never a click or a bell. They play on the
 * system sounds' volume, so the phone's own setting for touch sounds keeps them down or off, and
 * never touch the music (no audio focus).
 */
object UiSound {
    enum class Kind { TAP, ON, OFF, ARMED, SWIPE }

    private const val RATE = 22_050
    private val tracks = HashMap<Kind, AudioTrack>()

    fun play(kind: Kind) {
        runCatching {
            val track = synchronized(tracks) { tracks.getOrPut(kind) { build(wave(kind)) } }
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun tap() = play(Kind.TAP)

    fun toggle(on: Boolean) = play(if (on) Kind.ON else Kind.OFF)

    private fun wave(kind: Kind): FloatArray = when (kind) {
        // A round wooden tock: low sine with its octave, 3 ms attack, gone in 60 ms.
        Kind.TAP -> pcm(0.09f) { t -> 0.22f * soft(t, 0.003f) * exp(-t / 0.018f) * (sin(TWO_PI * 420 * t) + 0.35f * sin(TWO_PI * 840 * t)) }
        Kind.ON -> pair(523f, 784f)
        Kind.OFF -> pair(784f, 523f)
        Kind.ARMED -> pcm(0.06f) { t -> 0.12f * soft(t, 0.002f) * exp(-t / 0.012f) * sin(TWO_PI * 660 * t) }
        // Air moving past: noise swelling and settling, a low tone under it.
        Kind.SWIPE -> {
            val rnd = Random(7)
            var lp = 0f
            pcm(0.32f) { t ->
                lp += 0.18f * (rnd.nextFloat() * 2 - 1 - lp)
                val env = sin(PI.toFloat() * (t / 0.32f)).let { it * it }
                env * (0.5f * lp + 0.06f * sin(TWO_PI * 220 * t))
            }
        }
    }

    /** Two soft notes 70 ms apart, the second a little louder: a switch going on (rising) or off. */
    private fun pair(first: Float, second: Float) = pcm(0.22f) { t ->
        val a = soft(t, 0.004f) * exp(-t / 0.05f) * sin(TWO_PI * first * t)
        val d = t - 0.07f
        val b = if (d < 0) 0f else soft(d, 0.004f) * exp(-d / 0.07f) * sin(TWO_PI * second * d)
        0.13f * a + 0.16f * b
    }

    private fun soft(t: Float, attack: Float) = (t / attack).coerceIn(0f, 1f)

    private fun pcm(seconds: Float, sample: (Float) -> Float): FloatArray {
        val n = (seconds * RATE).toInt()
        return FloatArray(n) { i ->
            val t = i.toFloat() / RATE
            // Last 10 ms down to silence: never a click.
            val tail = ((n - i) / (0.01f * RATE)).coerceAtMost(1f)
            (sample(t) * tail).coerceIn(-1f, 1f)
        }
    }

    private fun build(samples: FloatArray): AudioTrack {
        val pcm = ShortArray(samples.size) { (samples[it] * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private const val TWO_PI = 2 * PI.toFloat()
}
