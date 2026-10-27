package zenith.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Alert sounds, synthesized once at start (no audio assets). */
enum class Tone(val notes: List<Note>) {
    /** Radar ahead: bright two-note rising chime. */
    APPROACH(listOf(Note(988.0, 130), Note(1319.0, 220))),

    /** Repeated while over the limit near a radar; repeats faster as the radar gets closer. */
    DANGER(listOf(Note(1568.0, 90))),

    /** Limit exceeded: two short beeps. */
    OVERSPEED(listOf(Note(1175.0, 70), Note(0.0, 60), Note(1175.0, 70))),

    /** Strong braking needed: three fast high beeps. */
    BRAKE(listOf(Note(1760.0, 60), Note(0.0, 40), Note(1760.0, 60), Note(0.0, 40), Note(1760.0, 60))),

    /** Red light or level crossing camera ahead: softer, lower chime (speed is not checked). */
    CHIME(listOf(Note(784.0, 140), Note(988.0, 220))),

    /** Radar passed: soft falling pair. */
    PASSED(listOf(Note(1319.0, 110), Note(988.0, 170))),
}

data class Note(val hz: Double, val ms: Int)

class TonePlayer {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val tracks = Tone.entries.associateWith(::build)

    fun play(tone: Tone) {
        val track = tracks.getValue(tone)
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    /** Total length of [tone], to start speech right after it. */
    fun durationMs(tone: Tone) = tone.notes.sumOf { it.ms }

    fun release() = tracks.values.forEach { it.release() }

    private fun build(tone: Tone): AudioTrack {
        val pcm = synthesize(tone.notes)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    /** Sine with a touch of 2nd harmonic and a short attack/release so notes don't click. */
    private fun synthesize(notes: List<Note>): ShortArray {
        val out = ShortArray(notes.sumOf { it.ms * RATE / 1000 })
        var offset = 0
        for (note in notes) {
            val n = note.ms * RATE / 1000
            val fade = min(n / 4, RATE / 200)
            for (i in 0 until n) {
                if (note.hz == 0.0) continue
                val t = i.toDouble() / RATE
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                val v = sin(2 * PI * note.hz * t) * 0.8 + sin(4 * PI * note.hz * t) * 0.2
                out[offset + i] = (v * env * 0.85 * Short.MAX_VALUE).toInt().toShort()
            }
            offset += n
        }
        return out
    }

    private companion object {
        const val RATE = 44_100
    }
}
