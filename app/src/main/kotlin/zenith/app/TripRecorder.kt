package zenith.app

import android.content.Context
import android.location.Location
import android.util.Log
import zenith.core.GpsFix
import zenith.core.Gpx
import zenith.core.LatLon
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Records the raw fixes of a drive as GPX in the app's external files ("trips" folder), so a
 * trip can be shared from the settings and replayed through DrivingSession. Every [flushEvery]
 * fixes, the new points are written over the closing tags, which are written again after them: the
 * file is always a complete GPX (a crash loses at most that much) and a long drive never rewrites
 * it whole. Keeps the last [KEEP] trips.
 */
class TripRecorder(context: Context, private val flushEvery: Int = 30) {
    private val dir = dir(context).apply { mkdirs() }

    init {
        dir.listFiles { f -> f.extension == "gpx" }?.sortedByDescending { it.name }?.drop(KEEP - 1)?.forEach { it.delete() }
    }

    private val file = File(dir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".gpx")
    private val pending = StringBuilder()
    private var count = 0
    private var started = false

    fun add(fix: GpsFix) {
        pending.append(Gpx.point(fix))
        if (++count % flushEvery == 0) flush()
    }

    fun close() {
        if (pending.isNotEmpty()) flush()
    }

    companion object {
        const val KEEP = 10
        private val FOOTER_BYTES = Gpx.FOOTER.toByteArray()

        fun dir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "trips")
    }

    private fun flush() {
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                if (!started) {
                    raf.setLength(0)
                    raf.write(Gpx.header(file.nameWithoutExtension).toByteArray())
                    started = true
                } else {
                    raf.seek(raf.length() - FOOTER_BYTES.size)
                }
                raf.write(pending.toString().toByteArray())
                raf.write(FOOTER_BYTES)
                raf.setLength(raf.filePointer)
            }
            pending.clear()
        }.onFailure { Log.e("Zenith", "Could not write ${file.name}", it) }
    }
}

/** Last recorded trip, if any. */
fun latestTrip(context: Context): File? =
    TripRecorder.dir(context).listFiles { f -> f.extension == "gpx" && f.length() > 0 }?.maxByOrNull { it.name }

fun Location.toFix() = GpsFix(
    timeMs = time,
    position = LatLon(latitude, longitude),
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
)
