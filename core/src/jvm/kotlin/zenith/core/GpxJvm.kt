package zenith.core

import org.w3c.dom.Element
import java.io.InputStream
import java.io.Writer
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/** Reads a GPX 1.1 trace (JVM only: the XML parser and java.time). */
fun Gpx.read(input: InputStream): List<GpsFix> {
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

/** Writes a whole trace to [out]. */
fun Gpx.write(fixes: List<GpsFix>, out: Writer, name: String = "Zenith") {
    out.write(header(name))
    for (f in fixes) out.write(point(f))
    out.write(Gpx.FOOTER)
    out.flush()
}

/** Text of the first descendant with [localName], searching extensions too. */
private fun Element.child(localName: String): String? {
    val nodes = getElementsByTagNameNS("*", localName)
    return if (nodes.length > 0) nodes.item(0).textContent.trim() else null
}
