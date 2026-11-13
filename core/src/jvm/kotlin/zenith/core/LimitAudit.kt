package zenith.core

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Speed-limit bench on the real road tiles: random drives at exactly the limit of the road driven
 * (slowing down before a lower limit, as a driver who reads the signs), with GPS noise and a steady
 * sideways bias (lane, multipath), through the full pipeline.
 * `./gradlew :core:limits --args="dossier_des_tuiles [trajets] [graine] [tot]"` (tot : « Prévenir dès 3 km/h »).
 * - shown limit compared with the limit of the road really driven, away from the 40 m after a
 *   change (where a short lag is expected) and right at it;
 * - spoken "Limite X" and danger state: the car never goes over the limit, so every one is false.
 */
object LimitAudit {
    private const val CHANGE_ZONE_M = 40.0
    private const val WARMUP_FIXES = 8

    data class Report(
        val drives: Int,
        val instants: Int,
        val right: Int,
        val wrong: Int,
        val wrongLower: Int,
        val missing: Int,
        val ghost: Int,
        val atChange: Int,
        val wrongAtChange: Int,
        val falseWarnings: Int,
        val examples: List<String>,
    )

    fun run(network: RoadNetwork, drives: Int, seed: Int, early: Boolean = false): Report {
        val rnd = Random(seed)
        val starts = network.segments.filter { it.roadClass in FieldAudit.DRIVEN && it.lengthM > 50 }
        var instants = 0
        var right = 0
        var wrong = 0
        var wrongLower = 0
        var missing = 0
        var ghost = 0
        var atChange = 0
        var wrongAtChange = 0
        var falseWarnings = 0
        val examples = mutableListOf<String>()
        repeat(drives) { k ->
            val route = FieldAudit.route(network, starts.random(rnd), rnd)
            val line = Polyline(FieldAudit.geometry(route).takeIf { it.size >= 2 } ?: return@repeat)
            if (line.lengthM < 1_000) return@repeat
            // Truth along the route: limit of each piece, and where it starts.
            val pieces = ArrayList<Pair<Double, Int?>>()
            var acc = 0.0
            for (s in route) {
                pieces += acc to s.segment.limitKmh(s.forward)
                acc += s.segment.lengthM
            }
            fun truthAt(d: Double) = pieces.last { it.first <= d }.second
            fun changeBefore(d: Double): Boolean {
                val i = pieces.indexOfLast { it.first <= d }
                return i > 0 && pieces[i].second != pieces[i - 1].second && d - pieces[i].first < CHANGE_ZONE_M
            }
            fun targetMps(d: Double): Double {
                // Already at the next lower limit 120 m before it, like a driver who reads the sign.
                val ahead = pieces.filter { it.first in d..d + 120 }.mapNotNull { it.second }
                return ((listOfNotNull(truthAt(d)) + ahead).minOrNull() ?: 50) / 3.6
            }
            val bias = (rnd.nextDouble() * 2 - 1) * 5.0
            val frame = LocalFrame(line.pointAt(0.0))
            val fixes = mutableListOf<GpsFix>()
            val along = HashMap<Long, Double>()
            var d = 0.0
            var v = 8.0
            var t = 0L
            while (d < line.lengthM) {
                v += (targetMps(d) - v).coerceIn(-2.5, 2.0)
                val b = line.bearingAt(d) * PI / 180
                // Sideways bias to the right of travel, plus noise.
                val side = Vec2(cos(b), -sin(b)) * bias
                val p = frame.toXY(line.pointAt(d)) + side + Vec2(FieldAudit.gauss(rnd) * 4, FieldAudit.gauss(rnd) * 4)
                fixes += GpsFix(t * 1_000, frame.toLatLon(p), 5.0, (v + FieldAudit.gauss(rnd) * 0.3).coerceAtLeast(0.0), line.bearingAt(d))
                along[t * 1_000] = d
                d += v
                t++
            }
            val session = DrivingSession(network, emptyList(), DriverProfile(earlySpeeding = early))
            for ((n, s) in DrivingSession.replay(fixes, session).withIndex()) {
                for (e in s.events) if (e is AlertEvent.Overspeed && e.level == OverspeedLevel.WARN) {
                    falseWarnings++
                    if (falseWarnings % 40 == 1) examples += "trajet $k fix $n : « Limite ${e.limitKmh} » à ${e.speedKmh} (affiché ${s.speedKmh}, vrai ${truthAt(along[s.timeMs] ?: 0.0)}, ${s.road?.segment?.roadClass} ${s.road?.segment?.limitSource})"
                }
                if (n < WARMUP_FIXES) continue
                val at = along[s.timeMs] ?: continue
                val truth = truthAt(at)
                val shown = s.limitKmh
                instants++
                if (changeBefore(at)) {
                    atChange++
                    if (shown != truth) wrongAtChange++
                    continue
                }
                when {
                    truth == null && shown == null -> right++
                    truth == null -> ghost++
                    shown == null -> missing++
                    shown == truth -> right++
                    else -> {
                        wrong++
                        if (shown < truth) wrongLower++
                        if (wrong % 150 == 1) examples += "trajet $k à ${at.roundToInt()} m : affiché $shown, vrai $truth (${s.road?.segment?.roadClass} ${s.road?.segment?.name})"
                    }
                }
            }
        }
        return Report(drives, instants, right, wrong, wrongLower, missing, ghost, atChange, wrongAtChange, falseWarnings, examples)
    }
}

fun limitAuditMain(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage : limits <dossier des tuiles> [trajets] [graine] [tot]")
        return
    }
    val network = FieldAudit.loadTiles(File(args[0]))
    val r = LimitAudit.run(network, args.getOrNull(1)?.toInt() ?: 200, args.getOrNull(2)?.toInt() ?: 1, early = args.getOrNull(3) == "tot")
    fun pct(x: Int, n: Int) = if (n == 0) "-" else "${(1000.0 * x / n).roundToInt() / 10.0} %"
    println("${r.drives} trajets, ${network.segments.size} segments, ${r.instants} instants")
    println("Limite juste : ${pct(r.right, r.instants - r.atChange)}, fausse : ${r.wrong} (${pct(r.wrong, r.instants - r.atChange)}, dont trop basse ${r.wrongLower})")
    println("Limite absente alors que connue : ${r.missing}, affichée sur une route sans limite : ${r.ghost}")
    println("Juste après un changement : ${r.wrongAtChange} fausses sur ${r.atChange}")
    println("« Limite X » dit à tort (la voiture roule à la limite) : ${r.falseWarnings}")
    r.examples.take(12).forEach { println("  $it") }
}

/** Separate entry point for the `limits` task. */
object LimitAuditMain {
    @JvmStatic
    fun main(args: Array<String>) = limitAuditMain(args)
}
