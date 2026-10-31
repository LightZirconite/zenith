package zenith.ios

import zenith.core.ActiveAlert
import zenith.core.AlertEvent
import zenith.core.Announcer
import zenith.core.ControlZone
import zenith.core.DriveSnapshot
import zenith.core.OverspeedLevel
import zenith.core.Radar

// What Swift reads from a drive: plain classes with simple fields, so the Swift side never deals
// with Kotlin's boxed optionals or sealed types. A missing number is -1 (counts, limits, speeds,
// distances, angles are never negative here); a missing object or text is nil in Swift.

/** A radar. [kind]: the RadarType name ("FIXED", "SECTION_END"…). */
class RadarView(
    val radarId: String,
    val lat: Double,
    val lon: Double,
    val kind: String,
    val label: String,
    val limitKmh: Int,
    val directionDeg: Double,
    val measuresSpeed: Boolean,
    val mobile: Boolean,
    val reportedAtMs: Long,
) {
    internal companion object {
        fun of(r: Radar) = RadarView(
            radarId = r.id,
            lat = r.position.lat,
            lon = r.position.lon,
            kind = r.type.name,
            label = r.type.label,
            limitKmh = r.limitKmh ?: -1,
            directionDeg = r.directionDeg ?: -1.0,
            measuresSpeed = r.type.measuresSpeed,
            mobile = r.type.mobile,
            reportedAtMs = r.reportedAtMs ?: -1,
        )
    }
}

/** A radar being announced; [ageMs] -1 unless a dated mobile report. */
class AlertView(val radar: RadarView, val distanceM: Double, val limitKmh: Int, val overLimit: Boolean, val ageMs: Long) {
    internal companion object {
        fun of(a: ActiveAlert) = AlertView(RadarView.of(a.radar), a.distanceM, a.limitKmh ?: -1, a.overLimit, a.ageMs ?: -1)
    }
}

/** The closest radar on the road ahead; [roadName] nil when guessed in a straight line. */
class UpcomingView(val radar: RadarView, val distanceM: Double, val limitKmh: Int, val roadName: String?)

/** Average-speed section under way. */
class SectionView(
    val limitKmh: Int,
    val averageKmh: Double,
    val remainingM: Double,
    val lengthM: Double,
    val targetKmh: Int,
    val lost: Boolean,
)

/** A control itinerary: [points] flat [lat, lon, …]; [remainingM] -1 when not being driven. */
class ZoneView(val zoneId: String, val points: List<Double>, val lengthM: Double, val remainingM: Double) {
    internal companion object {
        fun of(z: ControlZone, remainingM: Double = -1.0) =
            ZoneView(z.id, z.points.flatMap { listOf(it.lat, it.lon) }, z.lengthM, remainingM)
    }
}

/**
 * One alert event. [kind]: approach, brake, passed, sectionStarted, sectionEnded, overspeed,
 * limitChanged, zoneEntered, zoneLeft. [text]: what the voice says, nil for silent events.
 * [level]: overspeed level name (NONE, OVER, WARN).
 */
class EventView(
    val kind: String,
    val timeMs: Long,
    val radar: RadarView?,
    val distanceM: Double,
    val limitKmh: Int,
    val speedKmh: Double,
    val wouldFlash: Boolean,
    val points: Int,
    val averageKmh: Double,
    val level: String,
    val resumed: Boolean,
    val fromKmh: Int,
    val toKmh: Int,
    val text: String?,
) {
    internal companion object {
        fun of(e: AlertEvent): EventView {
            val text = runCatching { Announcer.text(e) }.getOrNull()
            fun v(
                kind: String,
                radar: Radar? = null,
                distanceM: Double = -1.0,
                limitKmh: Int? = null,
                speedKmh: Double = -1.0,
                wouldFlash: Boolean = false,
                points: Int = 0,
                averageKmh: Double = -1.0,
                level: OverspeedLevel = OverspeedLevel.NONE,
                resumed: Boolean = false,
                fromKmh: Int? = null,
                toKmh: Int? = null,
            ) = EventView(
                kind, e.timeMs, radar?.let(RadarView::of), distanceM, limitKmh ?: -1, speedKmh, wouldFlash, points,
                averageKmh, level.name, resumed, fromKmh ?: -1, toKmh ?: -1, text,
            )
            return when (e) {
                is AlertEvent.Approach -> v("approach", e.radar, e.distanceM, e.limitKmh)
                is AlertEvent.Brake -> v("brake", e.radar, e.distanceM, e.limitKmh)
                is AlertEvent.Passed -> v("passed", e.radar, limitKmh = e.limitKmh, speedKmh = e.speedKmh, wouldFlash = e.wouldFlash, points = e.points)
                is AlertEvent.SectionStarted -> v("sectionStarted", e.radar, e.lengthM, e.limitKmh, resumed = e.resumed)
                is AlertEvent.SectionEnded -> v("sectionEnded", limitKmh = e.limitKmh, wouldFlash = e.wouldFlash, averageKmh = e.averageKmh)
                is AlertEvent.Overspeed -> v("overspeed", limitKmh = e.limitKmh, speedKmh = e.speedKmh.toDouble(), level = e.level)
                is AlertEvent.LimitChanged -> v("limitChanged", fromKmh = e.fromKmh, toKmh = e.toKmh)
                is AlertEvent.ZoneEntered -> v("zoneEntered", distanceM = e.remainingM)
                is AlertEvent.ZoneLeft -> v("zoneLeft")
            }
        }
    }
}

/**
 * Everything the screen needs after one fix. [ahead]: road ahead, flat [lat, lon, …].
 * [limitSource]: LimitSource name (EXPLICIT, IMPLICIT, INFERRED, AREA) or nil.
 */
class SnapshotView(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Int,
    val accuracyM: Double,
    val headingDeg: Double,
    val roadName: String?,
    val roadClass: String?,
    val limitKmh: Int,
    val limitSource: String?,
    val alerts: List<AlertView>,
    val section: SectionView?,
    val upcoming: UpcomingView?,
    val limitAheadKmh: Int,
    val limitAheadM: Double,
    val ahead: List<Double>,
    val events: List<EventView>,
    val estimated: Boolean,
    val zone: ZoneView?,
) {
    internal companion object {
        fun of(s: DriveSnapshot) = SnapshotView(
            timeMs = s.timeMs,
            lat = s.position.lat,
            lon = s.position.lon,
            speedKmh = s.speedKmh ?: -1,
            accuracyM = s.gpsAccuracyM ?: -1.0,
            headingDeg = s.headingDeg ?: -1.0,
            roadName = s.road?.segment?.name,
            roadClass = s.road?.segment?.roadClass,
            limitKmh = s.limitKmh ?: -1,
            limitSource = s.limitSource?.name,
            alerts = s.alerts.map(AlertView::of),
            section = s.section?.let { SectionView(it.limitKmh, it.averageKmh, it.remainingM, it.lengthM, it.targetKmh, it.lost) },
            upcoming = s.upcoming?.let { UpcomingView(RadarView.of(it.radar), it.distanceM, it.limitKmh ?: -1, it.roadName) },
            limitAheadKmh = s.limitAhead?.limitKmh ?: -1,
            limitAheadM = s.limitAhead?.distanceM ?: -1.0,
            ahead = s.ahead.flatMap { listOf(it.lat, it.lon) },
            events = s.events.map(EventView::of),
            estimated = s.estimated,
            zone = s.zone?.let { ZoneView.of(it.zone, it.remainingM) },
        )
    }
}
