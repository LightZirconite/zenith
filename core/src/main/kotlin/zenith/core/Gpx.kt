package zenith.core

import org.w3c.dom.Element
import java.io.InputStream
import java.io.Writer
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/** Reads and writes GPS traces as GPX 1.1 track points (speed and course optional). */
object Gpx {

    fun read(input: InputStream): List<GpsFix> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val points = doc.getElementsByTagNameNS("*", "trkpt")
        return (0 until points.length).mapNotNull { i ->
            val e = points.item(i) as Element
            val time = e.child("time")?.let { Instant.parse(it).toEpochMilli() } ?: return@mapNotNull null
            GpsFix(
                timeMs = time,
                position = LatLon(e.getAttribute("lat").toDouble(), e.getAttribute("lon").toDouble()),
                accuracyM = e.child("hdop")?.toDoubleOrNull()?.let { it * 5.0 },
                speedMps = e.child("speed")?.toDoubleOrNull(),
                bearingDeg = e.child("course")?.toDoubleOrNull(),
            )
        }.sortedBy { it.timeMs }
    }

    fun write(fixes: List<GpsFix>, out: Writer, name: String = "Zenith") {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<gpx version=\"1.1\" creator=\"Zenith\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.write("<trk><name>${escape(name)}</name><trkseg>\n")
        for (f in fixes) {
            out.write("<trkpt lat=\"${f.position.lat}\" lon=\"${f.position.lon}\">")
            out.write("<time>${Instant.ofEpochMilli(f.timeMs)}</time>")
            f.accuracyM?.let { out.write("<hdop>${it / 5.0}</hdop>") }
            f.speedMps?.let { out.write("<speed>$it</speed>") }
            f.bearingDeg?.let { out.write("<course>$it</course>") }
            out.write("</trkpt>\n")
        }
        out.write("</trkseg></trk>\n</gpx>\n")
        out.flush()
    }

    /** Text of the first descendant with [localName], searching extensions too. */
    private fun Element.child(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0).textContent.trim() else null
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
