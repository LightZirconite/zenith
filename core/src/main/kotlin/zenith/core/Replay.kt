package zenith.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Replays a recorded trip (GPX shared from the app) and lists what Zenith said, second by second:
 * `./gradlew :core:replay --args="trajet.gpx [radars.json] [dossier des tuiles]"`.
 */
object Replay {
    fun timeline(fixes: List<GpsFix>, network: RoadNetwork?, radars: List<Radar>): List<String> {
        val start = fixes.firstOrNull()?.timeMs ?: return emptyList()
        return DrivingSession.replay(fixes, DrivingSession(network, radars)).flatMap { s ->
            s.events.mapNotNull { e -> Announcer.text(e)?.let { line(s, start, it) } ?: (e as? AlertEvent.LimitChanged)?.let { line(s, start, "limite ${it.toKmh ?: "inconnue"}") } }
        }
    }

    private fun line(s: DriveSnapshot, startMs: Long, text: String): String {
        val t = ((s.timeMs - startMs) / 1000).toInt()
        val clock = "%02d:%02d".format(t / 60, t % 60)
        return "$clock  ${s.speedKmh?.toString()?.padStart(3) ?: "  -"} km/h  ${s.road?.segment?.name.orEmpty().take(24).padEnd(24)}  $text"
    }

    /** Road tiles (roads_<i>_<j>.json or .json.gz) from [dir] that the trip goes through. */
    fun loadNetwork(dir: File, fixes: List<GpsFix>): RoadNetwork? {
        val keys = fixes.flatMap { RoadTiles.keysAround(it.position, 2_000.0) }.toSet()
        val segments = keys.flatMap { key ->
            val gz = File(dir, key.fileName)
            val plain = File(dir, key.fileName.removeSuffix(".gz"))
            when {
                gz.exists() -> RoadTiles.parse(GZIPInputStream(gz.inputStream()).bufferedReader().use { it.readText() })
                plain.exists() -> RoadTiles.parse(plain.readText())
                else -> emptyList()
            }
        }
        return if (segments.isEmpty()) null else RoadNetwork(segments.distinctBy { it.id })
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage : replay <trajet.gpx> [radars.json] [dossier des tuiles]")
        return
    }
    val fixes = File(args[0]).inputStream().use(Gpx::read)
    val radars = args.getOrNull(1)?.let { RadarPack.parse(File(it).readText()) }.orEmpty()
    val network = args.getOrNull(2)?.let { Replay.loadNetwork(File(it), fixes) }
    println("${fixes.size} points, ${radars.size} radars, ${network?.segments?.size ?: 0} segments de route")
    Replay.timeline(fixes, network, radars).forEach(::println)
    val top = fixes.mapNotNull { it.speedMps }.maxOrNull()?.let { (it * 3.6).roundToInt() }
    println("Vitesse max : ${top ?: "-"} km/h")
}
