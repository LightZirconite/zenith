package zenith.core

/** [measuresSpeed] false: the camera checks a red light, not the speed (no limit, no "too fast"). */
enum class RadarType(val label: String, val mobile: Boolean = false, val measuresSpeed: Boolean = true) {
    FIXED("Radar fixe"),
    DISCRIMINANT("Radar discriminant"),
    RED_LIGHT("Radar feu rouge", measuresSpeed = false),
    LEVEL_CROSSING("Radar passage à niveau", measuresSpeed = false),
    SECTION_START("Début de radar tronçon"),
    SECTION_END("Fin de radar tronçon"),
    WORKZONE("Radar chantier"),
    MOBILE("Radar mobile", mobile = true),
    MOBILE_VEHICLE("Voiture radar", mobile = true),
    POLICE("Contrôle", mobile = true),
}

/**
 * A speed camera or control. [directionDeg] is the traffic heading it measures (null = both ways).
 * Section starts carry the section length; [reportedAtMs] dates mobile reports.
 */
data class Radar(
    val id: String,
    val position: LatLon,
    val type: RadarType,
    val limitKmh: Int? = null,
    val directionDeg: Double? = null,
    val sectionLengthM: Double? = null,
    val reportedAtMs: Long? = null,
)

class RadarIndex(radars: Collection<Radar>) {
    private val index = GridIndex<Radar>(cellDeg = 0.02)

    init {
        for (r in radars) index.insert(BBox(r.position.lat, r.position.lon, r.position.lat, r.position.lon), r)
    }

    fun near(p: LatLon, radiusM: Double): List<Radar> =
        index.query(BBox.around(p, radiusM)).filter { Geo.distanceM(p, it.position) <= radiusM }
}
