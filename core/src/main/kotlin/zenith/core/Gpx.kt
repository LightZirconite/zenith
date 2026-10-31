package zenith.core

/**
 * Writes GPS traces as GPX 1.1 track points (speed and course optional). Reading and writing to a
 * java.io stream are JVM extensions (GpxJvm.kt): iOS only writes, through these strings.
 */
object Gpx {

    /** Start of a file; points then [FOOTER] follow. Lets a recorder append instead of rewriting. */
    fun header(name: String = "Zenith"): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"Zenith\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            "<trk><name>${escape(name)}</name><trkseg>\n"

    fun point(f: GpsFix): String = buildString {
        append("<trkpt lat=\"${f.position.lat}\" lon=\"${f.position.lon}\">")
        append("<time>${isoInstant(f.timeMs)}</time>")
        f.accuracyM?.let { append("<hdop>${it / 5.0}</hdop>") }
        f.speedMps?.let { append("<speed>$it</speed>") }
        f.bearingDeg?.let { append("<course>$it</course>") }
        append("</trkpt>\n")
    }

    const val FOOTER = "</trkseg></trk>\n</gpx>\n"

    /**
     * UTC time as java.time.Instant.toString writes it ("2026-10-31T08:05:09Z", milliseconds only
     * when not zero): the same text on the JVM and on iOS.
     */
    fun isoInstant(epochMs: Long): String {
        val days = floorDiv(epochMs, 86_400_000L)
        val msOfDay = epochMs - days * 86_400_000L
        // Civil date from days since 1970-01-01 (H. Hinnant's algorithm).
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val s = msOfDay / 1000
        val ms = msOfDay % 1000
        return buildString {
            append(year.toString().padStart(4, '0')).append('-').append(two(month)).append('-').append(two(day))
            append('T').append(two(s / 3600)).append(':').append(two(s / 60 % 60)).append(':').append(two(s % 60))
            if (ms != 0L) append('.').append(ms.toString().padStart(3, '0'))
            append('Z')
        }
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
