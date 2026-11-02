package zenith.core

import java.io.File
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Bench radar by radar: every radar of the tiles is driven to from every direction it measures,
 * through a random road behind it (turns at real forks included), with variants of the driver and
 * of the phone: speed under or over the limit, GPS noise, missing Doppler, a stop on the way,
 * lost fixes. Each drive is checked like a driver would: announced in time, once, never after it.
 * `./gradlew :core:sweep --args="radars.json dossier_des_tuiles [variantes] [graine] [id d'un radar à suivre seconde par seconde]"`.
 */
object RadarSweep {
    private const val AFTER_M = 400.0

    /** Below this, an announcement leaves too little time at the speed of the drive. */
    private const val LATE_S = 6.0

    data class Variant(
        val speedFactor: Double,
        val noiseM: Double,
        val doppler: Boolean,
        val stop: Boolean,
        val dropRate: Double,
        /** Drive started only a few hundred meters before the radar. */
        val lateStart: Boolean = false,
    ) {
        override fun toString() = "vitesse ×${"%.2f".format(speedFactor)}, bruit ${noiseM.toInt()} m" +
            (if (doppler) "" else ", sans Doppler") + (if (stop) ", arrêt" else "") + (if (dropRate > 0) ", fixes perdus" else "") + (if (lateStart) ", départ juste avant" else "")
    }

    data class Outcome(val kind: String, val radar: Radar, val detail: String)

    data class Report(val drives: Int, val radars: Int, val outcomes: List<Outcome>)

    /** [only]: a single radar, its drives traced second by second. */
    fun run(network: RoadNetwork, radars: List<Radar>, variants: Int, seed: Int, only: String? = null): Report {
        val rnd = Random(seed)
        val fixed = radars.filter { !it.type.mobile }
        val snapper = RadarSnapper(network)
        val segmentIds = network.segments.mapTo(HashSet()) { it.id }
        if (network.segments.isEmpty()) return Report(0, 0, emptyList())
        val pts = network.segments.asSequence().flatMap { it.points.asSequence() }
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }
        val maxLon = pts.maxOf { it.lon }
        // Margin: a radar at the edge of the tiles cannot be driven to from every side.
        val targets = fixed.filter {
            it.position.lat in (minLat + 0.03)..(maxLat - 0.03) && it.position.lon in (minLon + 0.04)..(maxLon - 0.04)
        }
        val outcomes = mutableListOf<Outcome>()
        var drives = 0
        var tested = 0
        for (r in targets) {
            if (only != null && r.id != only) continue
            val snaps = snapper.snap(r).filter { it.segment.id in segmentIds && it.segment.roadClass in DRIVEN }
            if (snaps.isEmpty()) continue
            tested++
            for (snap in snaps) repeat(variants) {
                val variant = Variant(
                    speedFactor = if (rnd.nextDouble() < 0.2) 1.1 + rnd.nextDouble() * 0.2 else 0.55 + rnd.nextDouble() * 0.45,
                    noiseM = listOf(2.0, 4.0, 8.0, 12.0).random(rnd),
                    doppler = rnd.nextDouble() > 0.15,
                    stop = rnd.nextDouble() < 0.3,
                    dropRate = if (rnd.nextDouble() < 0.2) 0.15 else 0.0,
                    lateStart = rnd.nextDouble() < 0.15,
                )
                val leadM = if (variant.lateStart) 350.0 + rnd.nextDouble() * 350 else 900.0 + rnd.nextDouble() * 1_600
                val route = routeThrough(network, snap, leadM, rnd) ?: return@repeat
                drives++
                outcomes += check(network, fixed, r, route, variant, rnd, trace = only != null)
            }
        }
        return Report(drives, tested, outcomes)
    }

    private class Route(val steps: List<FieldAudit.Step>, val line: Polyline, val radarAtM: Double)

    /** A road that reaches [snap] after about [leadM], picked backwards at random at every fork, then goes on a little. */
    private fun routeThrough(network: RoadNetwork, snap: RadarOnRoad, leadM: Double, rnd: Random): Route? {
        val before = ArrayDeque<FieldAudit.Step>()
        var lead = snap.offsetM
        var first = FieldAudit.Step(snap.segment, snap.forward)
        val used = hashSetOf(first.segment.id)
        while (lead < leadM) {
            val entry = if (first.forward) first.segment.fromNode else first.segment.toNode
            val options = arrivalsAt(network, entry).filter { it.segment.id !in used && it.segment.roadClass in DRIVEN }
            if (options.isEmpty()) break
            val prev = options.random(rnd)
            used += prev.segment.id
            before.addFirst(prev)
            lead += prev.segment.lengthM
            first = prev
        }
        if (lead < minOf(leadM, 700.0)) return null
        val steps = before.toMutableList()
        steps += FieldAudit.Step(snap.segment, snap.forward)
        var after = snap.segment.lengthM - snap.offsetM
        while (after < AFTER_M) {
            val last = steps.last()
            val node = if (last.forward) last.segment.toNode else last.segment.fromNode
            val options = network.departuresFrom(node).filter { it.first.id !in used && it.first.roadClass in DRIVEN }
            if (options.isEmpty()) break
            val obvious = RoadHorizon.continuation(network, HorizonStep(last.segment, last.forward, 0.0))
            val (s, f) = obvious?.takeIf { it in options } ?: options.random(rnd)
            used += s.id
            steps += FieldAudit.Step(s, f)
            after += s.lengthM
        }
        val pts = steps.flatMapIndexed { i, s ->
            val p = if (s.forward) s.segment.points else s.segment.points.reversed()
            if (i == 0) p else p.drop(1)
        }
        if (pts.size < 2) return null
        return Route(steps, Polyline(pts), lead)
    }

    /** Directed segments that end at [node]. */
    private fun arrivalsAt(network: RoadNetwork, node: Long): List<FieldAudit.Step> = network.segmentsAt(node).mapNotNull { s ->
        when {
            s.toNode == node -> FieldAudit.Step(s, true)
            s.fromNode == node && !s.oneway -> FieldAudit.Step(s, false)
            else -> null
        }
    }

    private fun check(network: RoadNetwork, radars: List<Radar>, r: Radar, route: Route, variant: Variant, rnd: Random, trace: Boolean): List<Outcome> {
        val (fixes, truth) = drive(route, variant, rnd)
        val snaps = DrivingSession.replay(fixes, DrivingSession(network, radars))
        val twins = radars.filter { it.id != r.id && sameFamily(it, r) && Geo.distanceM(it.position, r.position) <= 150 }.map { it.id }.toSet()
        var announcedAt: Double? = null
        var announcedD: Double? = null
        var count = 0
        var tracked = false
        var speedAtAnnounce = 0.0
        val said = mutableListOf<String>()
        val out = mutableListOf<Outcome>()
        val detail = { extra: String -> "${r.id} ${r.type} ${r.limitKmh ?: "-"} dir=${r.directionDeg?.toInt()} · $variant · $extra · annonces $said" }
        for (s in snaps) {
            val done = truth[s.timeMs] ?: 0.0
            if (s.alerts.any { it.radar.id == r.id || it.radar.id in twins }) tracked = true
            if (trace) {
                val a = s.alerts.firstOrNull { it.radar.id == r.id }
                println("  t=${s.timeMs / 1000} reste=${(route.radarAtM - done).toInt()} v=${s.speedKmh} route=${s.road?.segment?.name} alerte=${a?.distanceM?.toInt()} " +
                    "prochain=${s.upcoming?.radar?.id}@${s.upcoming?.distanceM?.toInt()} ${s.events.filterIsInstance<AlertEvent.Approach>().map { it.radar.id + "@" + it.distanceM.toInt() }}")
            }
            for (e in s.events) {
                if (e !is AlertEvent.Approach || (e.radar.id != r.id && e.radar.id !in twins)) continue
                count++
                said += "${e.radar.id}@${e.distanceM.toInt()}/${(route.radarAtM - done).toInt()}"
                // Said much nearer than along this drive: a side road close by reaches it (doubt kept on purpose).
                if (e.distanceM < route.radarAtM - done - 150) {
                    out += Outcome("alerte de route de côté", r, detail("dit ${e.distanceM.toInt()} m, ${(route.radarAtM - done).toInt()} m par ce trajet"))
                    continue
                }
                if (announcedAt == null) {
                    announcedAt = done
                    announcedD = e.distanceM
                    speedAtAnnounce = (s.speedKmh ?: 0) / 3.6
                } else if (e.radar.id == r.id) {
                    out += Outcome("en double", r, detail("de nouveau à ${(route.radarAtM - done).toInt()} m"))
                }
                if (done > route.radarAtM + 30) out += Outcome("après le radar", r, detail("${(done - route.radarAtM).toInt()} m après"))
            }
        }
        val at = announcedAt
        when {
            at == null -> out += Outcome(if (tracked) "suivi sans annonce" else "jamais annoncé", r, detail("approche ${route.radarAtM.toInt()} m"))
            at > route.radarAtM -> {}
            else -> {
                val left = route.radarAtM - at
                if (speedAtAnnounce > 8 && left / speedAtAnnounce < LATE_S) {
                    out += Outcome("annoncé tard", r, detail("à ${left.toInt()} m (dit ${announcedD?.toInt()} m), ${(speedAtAnnounce * 3.6).toInt()} km/h"))
                }
                // Said further than it is: the driver thinks there is more time. (Said nearer: the drive took a longer way.)
                if (announcedD != null && announcedD - left > maxOf(100.0, left * 0.3)) {
                    out += Outcome("distance fausse", r, detail("dit ${announcedD.toInt()} m, vrai ${left.toInt()} m"))
                }
            }
        }
        if (out.isEmpty()) out += Outcome("ok", r, "")
        if (trace) println("=> ${out.joinToString { it.kind }} · $variant · ${detail("")}")
        return out
    }

    private fun sameFamily(a: Radar, b: Radar): Boolean {
        fun f(t: RadarType) = if (t == RadarType.DISCRIMINANT) RadarType.FIXED else t
        return f(a.type) == f(b.type) &&
            (a.directionDeg == null || b.directionDeg == null || Geo.headingDiffDeg(a.directionDeg, b.directionDeg) <= 25)
    }

    /**
     * Speed: a share of the limit of each piece of road, slow start, an optional stop before the
     * radar. Returns the fixes and the true distance driven at each second.
     */
    private fun drive(route: Route, v: Variant, rnd: Random): Pair<List<GpsFix>, Map<Long, Double>> {
        val truth = HashMap<Long, Double>()
        val limits = ArrayList<Pair<Double, Double>>()
        var acc = 0.0
        for (s in route.steps) {
            limits += acc to ((s.segment.limitKmh(s.forward) ?: 50) * v.speedFactor / 3.6)
            acc += s.segment.lengthM
        }
        val stopAt = if (v.stop) route.radarAtM * (0.3 + rnd.nextDouble() * 0.5) else -1.0
        var stopLeftS = if (v.stop) 15 + rnd.nextInt(30) else 0
        val frame = LocalFrame(route.line.pointAt(0.0))
        val fixes = mutableListOf<GpsFix>()
        var d = 0.0
        var speed = 0.0
        var t = 0L
        var stopped = false
        while (d < route.line.lengthM) {
            val target = limits.last { it.first <= d }.second
            if (!stopped && stopAt in d..(d + 40)) {
                // Brake to a stop (red light, jam), wait, then go on.
                speed = maxOf(0.0, speed - 3.0)
                if (speed == 0.0) {
                    if (stopLeftS-- <= 0) stopped = true
                }
            } else {
                speed += (target - speed).coerceIn(-2.5, 2.0)
            }
            truth[t * 1_000] = d
            if (rnd.nextDouble() >= v.dropRate) {
                val p = frame.toXY(route.line.pointAt(d)) + Vec2(gauss(rnd) * v.noiseM, gauss(rnd) * v.noiseM)
                fixes += GpsFix(
                    t * 1_000, frame.toLatLon(p), maxOf(3.0, v.noiseM * 1.2),
                    if (v.doppler) maxOf(0.0, speed + gauss(rnd) * 0.2) else null,
                    if (v.doppler && speed > 1) route.line.bearingAt(d) else null,
                )
            }
            d += speed
            t++
        }
        return fixes to truth
    }

    private fun gauss(rnd: Random) = sqrt(-2 * ln(rnd.nextDouble().coerceAtLeast(1e-12))) * cos(2 * Math.PI * rnd.nextDouble())

    private val DRIVEN = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )
}

fun sweepMain(args: Array<String>) {
    if (args.size < 2) {
        println("Usage : sweep <radars.json> <dossier des tuiles> [variantes] [graine]")
        return
    }
    val radars = RadarPack.parse(File(args[0]).readText())
    val network = FieldAudit.loadTiles(File(args[1]))
    val r = RadarSweep.run(network, radars, args.getOrNull(2)?.toInt() ?: 4, args.getOrNull(3)?.toInt() ?: 1, args.getOrNull(4))
    println("${r.radars} radars, ${r.drives} arrivées simulées")
    r.outcomes.groupBy { it.kind }.toSortedMap().forEach { (kind, list) ->
        println("$kind : ${list.size} (${list.map { it.radar.id }.distinct().size} radars)")
    }
    r.outcomes.filter { it.kind != "ok" }.groupBy { it.kind }.forEach { (kind, list) ->
        println("== $kind")
        list.groupBy { it.radar.type }.forEach { (t, l) -> println("  $t : ${l.size}") }
        list.distinctBy { it.radar.id }.take(25).forEach { println("  ${it.detail}") }
    }
}

/** Separate entry point for the `sweep` task. */
object RadarSweepMain {
    @JvmStatic
    fun main(args: Array<String>) = sweepMain(args)
}
