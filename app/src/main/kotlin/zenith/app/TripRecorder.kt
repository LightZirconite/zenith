package zenith.app

import android.content.Context
import android.location.Location
import android.util.Log
import zenith.core.GpsFix
import zenith.core.Gpx
import zenith.core.LatLon
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Records the raw fixes of a drive as GPX in the app's external files ("trips" folder), so a
 * trip can be pulled with adb and replayed through DrivingSession. Rewrites the file every
 * [flushEvery] fixes so a crash loses at most that much.
 */
class TripRecorder(context: Context, private val flushEvery: Int = 30) {
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "trips").apply { mkdirs() }
    private val file = File(dir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".gpx")
    private val fixes = mutableListOf<GpsFix>()

    fun add(fix: GpsFix) {
        fixes += fix
        if (fixes.size % flushEvery == 0) flush()
    }

    fun close() {
        if (fixes.isNotEmpty()) flush()
    }

    private fun flush() {
        runCatching { file.bufferedWriter().use { Gpx.write(fixes, it, file.nameWithoutExtension) } }
            .onFailure { Log.e("Zenith", "Could not write ${file.name}", it) }
    }
}

fun Location.toFix() = GpsFix(
    timeMs = time,
    position = LatLon(latitude, longitude),
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
)
