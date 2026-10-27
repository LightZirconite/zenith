package zenith.core

import kotlin.math.roundToInt

/** French spoken texts for alert events. Null for events that stay silent. */
object Announcer {

    fun text(event: AlertEvent): String? = when (event) {
        is AlertEvent.Approach -> buildString {
            append(event.radar.type.label)
            append(" dans ").append(spokenDistance(event.distanceM))
            event.limitKmh?.let { append(", limité à ").append(it) }
        }
        is AlertEvent.Brake -> "Ralentis, ${event.limitKmh}"
        is AlertEvent.Passed -> if (event.wouldFlash) {
            "Radar passé à ${event.speedKmh.roundToInt()}, flash probable" +
                if (event.points > 0) ", ${event.points} point" + if (event.points > 1) "s" else "" else ""
        } else {
            null
        }
        is AlertEvent.SectionStarted -> "Tronçon de ${spokenDistance(event.lengthM)}, moyenne à ${event.limitKmh}"
        is AlertEvent.SectionEnded ->
            "Fin du tronçon, moyenne ${event.averageKmh.roundToInt()}" + if (event.wouldFlash) ", au-dessus de la limite" else ""
        is AlertEvent.Overspeed -> if (event.level == OverspeedLevel.WARN) "Limite ${event.limitKmh}" else null
        is AlertEvent.LimitChanged -> null
    }

    /** Distance rounded the way it is said: 50 m steps under 1 km, then tenths of km. */
    fun spokenDistance(m: Double): String = when {
        m < 1_000 -> "${(m / 50).roundToInt().coerceAtLeast(1) * 50} mètres"
        else -> {
            val km = (m / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            "$text kilomètre" + if (km >= 2) "s" else ""
        }
    }
}
