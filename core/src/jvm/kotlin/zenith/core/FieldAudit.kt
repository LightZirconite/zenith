package zenith.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Bench on real data: random drives on the real road tiles with the real radars.json, through the
 * full pipeline with GPS noise, compared with the truth of the route actually driven.
 * `./gradlew :core:audit --args="radars.json dossier_des_tuiles [trajets] [graine]"`.
 * - missed: a radar standing on the route (snapped to a driven direction) never announced before
 *   the car reached it, although the route ran at least [LEAD_M] before it;
 * - off route: a radar announced that the route never drives past (side branches within 500 m by
 *   road included: they are listed apart, as alerts kept on purpose when the road is in doubt);
 * - wrong next: share of the time the "next radar" shown is not on the route ahead.
 */
object FieldAudit {
    private const val LEAD_M = 600.0
    private const val ROUTE_M = 6_000.0

    data class Step(val segment: RoadSegment, val forward: Boolean)

    data class Report(
        val drives: Int,
        val onRoute: Int,
        val announced: Int,
        val missed: List<String>,
        val offRoute: List<String>,
        val branch: Int,
        val snapshots: Int,
        val wrongNext: Int,
        val falsePassedFlash: List<String>,
        val wrongExamples: List<String> = emptyList(),
    )

    fun run(network: RoadNetwork, radars: List<Radar>, drives: Int, seed: Int): Report {
        val rnd = Random(seed)
        val snapper = RadarSnapper(network)
        val index = RadarIndex(radars.filter { !it.type.mobile })
        val starts = network.segments.filter { it.roadClass in DRIVEN && it.lengthM > 50 }
        var onRoute = 0
        var announced = 0
        var branch = 0
        var snapshots = 0
        var wrongNext = 0
        val missed = mutableListOf<String>()
        val offRoute = mutableListOf<String>()
        val falseFlash = mutableListOf<String>()
        val wrongExamples = mutableListOf<String>()
        repeat(drives) { k ->
            val route = route(network, starts.random(rnd), rnd)
            val line = Polyline(geometry(route).takeIf { it.size >= 2 } ?: return@repeat)
            if (line.lengthM < 2 * LEAD_M) return@repeat
            // Truth: where each radar of the route stands along it (first time the route drives by it).
            val startAt = HashMap<Pair<Long, Boolean>, Double>()
            var acc = 0.0
            for (s in route) {
                startAt.putIfAbsent(s.segment.id to s.forward, acc)
                acc += s.segment.lengthM
            }
            val near = sample(line).flatMap { index.near(it, 120.0) }.distinctBy { it.id }
            val alongRadar = near.mapNotNull { r ->
                snapper.snap(r).mapNotNull { s -> startAt[s.segment.id to s.forward]?.plus(s.offsetM) }.minOrNull()?.let { r to it }
            }.toMap()
            val along = alongRadar.mapKeys { it.key.id }
            val fixes = drive(line, route, rnd)
            val session = DrivingSession(network, radars.filter { !it.type.mobile })
            val snaps = DrivingSession.replay(fixes, session)
            val firstSeen = HashMap<String, Double>()
            val travelled = fixes.associate { it.timeMs to (it.speedMps ?: 0.0) }
            var done = 0.0
            var lastT: Long? = null
            for (s in snaps) {
                lastT?.let { done += (travelled[s.timeMs] ?: 0.0) * (s.timeMs - it) / 1000.0 }
                lastT = s.timeMs
                for (e in s.events) {
                    when (e) {
                        is AlertEvent.Approach -> if (e.radar.id !in firstSeen) {
                            firstSeen[e.radar.id] = done
                            announced++
                            if (e.radar.id !in along && !e.radar.id.endsWith(">start") && done + e.distanceM < line.lengthM - 50) {
                                val far = e.distanceM > 500
                                if (far) offRoute += "trajet $k : ${e.radar.id} ${e.radar.type} annoncé à ${e.distanceM.toInt()} m" else branch++
                            }
                        }
                        is AlertEvent.Passed -> if (e.wouldFlash && e.radar.id !in along) {
                            falseFlash += "trajet $k : ${e.radar.id} ${e.radar.type}"
                        }
                        else -> Unit
                    }
                }
                s.upcoming?.let { u ->
                    // Beyond the end of the simulated route the truth is unknown.
                    if (u.radar.id.endsWith(">start") || done + u.distanceM > line.lengthM - 100) return@let
                    snapshots++
                    val at = along[u.radar.id]
                    if (at == null || at < done - 50) {
                        wrongNext++
                        if (wrongNext % 200 == 1) wrongExamples += "trajet $k : ${u.radar.id} à ${u.distanceM.toInt()} m, parcouru ${done.toInt()} m, sur la route à ${at?.toInt()}"
                    }
                }
            }
            for ((r, at) in alongRadar) {
                if (at < LEAD_M || at > line.lengthM - 50) continue
                onRoute++
                val twins = alongRadar.keys.filter { it.type == r.type && Geo.distanceM(it.position, r.position) <= 150 }.map { it.id } + r.id
                val seen = twins.mapNotNull { firstSeen[it] }.minOrNull()
                if (seen == null || seen > at) missed += "trajet $k : ${r.id} ${r.type} à ${at.toInt()} m du départ" +
                    (seen?.let { " (annoncé à ${it.toInt()} m)" } ?: "")
            }
        }
        return Report(drives, onRoute, announced, missed, offRoute, branch, snapshots, wrongNext, falseFlash, wrongExamples)
    }

    /** A drive that follows the road where it obviously goes on, as people do, and picks at random at real forks. Never a U-turn. */
    private fun route(network: RoadNetwork, start: RoadSegment, rnd: Random): List<Step> {
        val steps = mutableListOf(Step(start, start.oneway || rnd.nextBoolean()))
        var length = start.lengthM
        val used = HashSet<Pair<Long, Boolean>>()
        while (length < ROUTE_M) {
            val last = steps.last()
            used += last.segment.id to last.forward
            val node = if (last.forward) last.segment.toNode else last.segment.fromNode
            val options = network.departuresFrom(node)
                .filter { it.first.id != last.segment.id && it.first.roadClass in DRIVEN && (it.first.id to it.second) !in used }
            if (options.isEmpty()) break
            val obvious = RoadHorizon.continuation(network, HorizonStep(last.segment, last.forward, 0.0))
            val (segment, forward) = obvious?.takeIf { it in options } ?: options.random(rnd)
            steps += Step(segment, forward)
            length += segment.lengthM
        }
        return steps
    }

    private fun geometry(route: List<Step>): List<LatLon> = route.flatMapIndexed { i, s ->
        val pts = if (s.forward) s.segment.points else s.segment.points.reversed()
        if (i == 0) pts else pts.drop(1)
    }

    private fun sample(line: Polyline): List<LatLon> = generateSequence(0.0) { it + 80.0 }.takeWhile { it <= line.lengthM }.map(line::pointAt).toList()

    /** 1 Hz fixes at the limit of each piece of road (50 without one), 4 m of noise, Doppler speed. */
    private fun drive(line: Polyline, route: List<Step>, rnd: Random): List<GpsFix> {
        val limits = ArrayList<Pair<Double, Double>>()
        var acc = 0.0
        for (s in route) {
            limits += acc to ((s.segment.limitKmh(s.forward) ?: 50) / 3.6)
            acc += s.segment.lengthM
        }
        val frame = LocalFrame(line.pointAt(0.0))
        val fixes = mutableListOf<GpsFix>()
        var d = 0.0
        var v = 8.0
        var t = 0L
        while (d < line.lengthM) {
            val target = limits.last { it.first <= d }.second
            v += (target - v).coerceIn(-2.5, 2.0)
            val p = frame.toXY(line.pointAt(d)) + Vec2(gauss(rnd) * 4, gauss(rnd) * 4)
            fixes += GpsFix(t * 1_000, frame.toLatLon(p), 5.0, v + gauss(rnd) * 0.2, line.bearingAt(d))
            d += v
            t++
        }
        return fixes
    }

    private fun gauss(rnd: Random) = sqrt(-2 * ln(rnd.nextDouble().coerceAtLeast(1e-12))) * cos(2 * Math.PI * rnd.nextDouble())

    private val DRIVEN = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )

    fun loadTiles(dir: File): RoadNetwork = RoadNetwork(
        dir.listFiles { f -> f.name.startsWith("roads_") }.orEmpty().flatMap { f ->
            val text = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() } else f.readText()
            RoadTiles.parse(text)
        }.distinctBy { it.id },
    )
}

fun auditMain(args: Array<String>) {
    if (args.size < 2) {
        println("Usage : audit <radars.json> <dossier des tuiles> [trajets] [graine]")
        return
    }
    val radars = RadarPack.parse(File(args[0]).readText())
    val network = FieldAudit.loadTiles(File(args[1]))
    val r = FieldAudit.run(network, radars, args.getOrNull(2)?.toInt() ?: 200, args.getOrNull(3)?.toInt() ?: 1)
    println("${r.drives} trajets de 6 km, ${network.segments.size} segments, ${radars.size} radars")
    println("Radars sur la route : ${r.onRoute}, ratés : ${r.missed.size}")
    println("Annonces : ${r.announced}, hors route (> 500 m) : ${r.offRoute.size}, branches proches (doute gardé) : ${r.branch}")
    println("« Prochain radar » faux : ${r.wrongNext} sur ${r.snapshots} instants")
    println("« Flash probable » sur un radar hors route : ${r.falsePassedFlash.size}")
    (r.missed.take(15) + r.offRoute.take(15) + r.falsePassedFlash.take(10) + r.wrongExamples.take(10)).forEach { println("  $it") }
}

/** Separate entry point for the `audit` task. */
object FieldAuditMain {
    @JvmStatic
    fun main(args: Array<String>) = auditMain(args)
}
