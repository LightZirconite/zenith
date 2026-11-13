package zenith.core

import kotlin.math.roundToInt

/**
 * What the driver chose to hear besides the radars, which are always announced (radar ahead,
 * "Ralentis", section start). Some only want the radars: each of these can be turned off.
 */
data class AnnouncePrefs(
    /** "Limite 80" when too fast for the road, away from any radar. */
    val speeding: Boolean = true,
    /** After a radar: "Radar passé à 92, flash probable"; after a section: the average. */
    val passResult: Boolean = true,
    /** "Itinéraire de contrôle sur 12 kilomètres, radar possible". */
    val zones: Boolean = true,
)

/** Sound played before the voice; each app has a tone of the same name. */
enum class Cue { APPROACH, CHIME, BRAKE, OVERSPEED, PASSED }

/** [urgent]: changes the driver's speed now, so it goes first and older announcements still queued are dropped. */
data class Announcement(val cue: Cue?, val text: String?, val urgent: Boolean)

/** French spoken texts for alert events. Null for events that stay silent. */
object Announcer {

    /** Sound and text for [event] as the driver chose ([prefs]); null when nothing is heard. */
    fun plan(event: AlertEvent, prefs: AnnouncePrefs = AnnouncePrefs()): Announcement? {
        val cue = when (event) {
            is AlertEvent.Approach -> if (event.radar.type.measuresSpeed) Cue.APPROACH else Cue.CHIME
            is AlertEvent.SectionStarted -> Cue.APPROACH
            is AlertEvent.Brake -> Cue.BRAKE
            is AlertEvent.Overspeed -> Cue.OVERSPEED.takeIf { event.level == OverspeedLevel.WARN && prefs.speeding }
            is AlertEvent.Passed -> Cue.PASSED.takeIf { event.wouldFlash && prefs.passResult }
            is AlertEvent.SectionEnded -> Cue.PASSED.takeIf { prefs.passResult }
            is AlertEvent.ZoneEntered -> Cue.CHIME.takeIf { prefs.zones }
            is AlertEvent.LimitChanged, is AlertEvent.ZoneLeft -> null
        } ?: return null
        val urgent = event is AlertEvent.Brake || event is AlertEvent.Overspeed || (event is AlertEvent.Passed && event.wouldFlash)
        return Announcement(cue, text(event), urgent)
    }

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
        is AlertEvent.SectionStarted ->
            if (event.resumed) "Radar tronçon en cours, limite ${event.limitKmh} en moyenne"
            else "Radar tronçon sur ${spokenDistance(event.lengthM)}, limite ${event.limitKmh} en moyenne"
        is AlertEvent.SectionEnded ->
            "Fin du tronçon, ta moyenne : ${event.averageKmh.roundToInt()}" + if (event.wouldFlash) ", trop rapide" else ", c'est bon"
        is AlertEvent.Overspeed -> if (event.level == OverspeedLevel.WARN) "Limite ${event.limitKmh}" else null
        is AlertEvent.LimitChanged -> null
        is AlertEvent.ZoneEntered -> "Itinéraire de contrôle sur ${spokenDistance(event.remainingM)}, radar possible"
        is AlertEvent.ZoneLeft -> null
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
