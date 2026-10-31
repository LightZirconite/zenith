import CoreLocation
import Foundation
import ZenithCore

// Swift side of a drive: plain value types for SwiftUI, built from the Kotlin core's simple views
// (core-ios/src/iosMain/kotlin/zenith/ios/Views.kt). Only this file and the drive engine import
// ZenithCore: the screens never see a Kotlin type.

/** A point, degrees. */
struct GeoPoint: Equatable, Hashable {
    let lat: Double
    let lon: Double

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lon) }
}

/** Radar types, as the core names them. */
enum RadarKind: String {
    case fixed = "FIXED"
    case discriminant = "DISCRIMINANT"
    case redLight = "RED_LIGHT"
    case levelCrossing = "LEVEL_CROSSING"
    case sectionStart = "SECTION_START"
    case sectionEnd = "SECTION_END"
    case workzone = "WORKZONE"
    case mobile = "MOBILE"
    case mobileVehicle = "MOBILE_VEHICLE"
    case police = "POLICE"
}

/** A speed camera or control. */
struct RadarInfo: Identifiable, Equatable, Hashable {
    let id: String
    let position: GeoPoint
    let kind: RadarKind
    /** "Radar fixe", "Radar tronçon"… */
    let label: String
    let limitKmh: Int?
    /** Traffic heading it measures; nil both ways. */
    let directionDeg: Double?
    /** False: red light or level crossing (no limit, no "too fast"). */
    let measuresSpeed: Bool
    let mobile: Bool
    let reportedAt: Date?
}

/** A radar being announced. */
struct AlertInfo: Equatable {
    let radar: RadarInfo
    let distanceM: Double
    let limitKmh: Int?
    let overLimit: Bool
    /** Age of a dated mobile report. */
    let ageMs: Int64?
}

/** Closest radar on the road ahead; [roadName] nil when guessed in a straight line. */
struct UpcomingInfo: Equatable {
    let radar: RadarInfo
    let distanceM: Double
    let limitKmh: Int?
    let roadName: String?
}

/** Average-speed section under way. */
struct SectionInfo: Equatable {
    let limitKmh: Int
    let averageKmh: Double
    let remainingM: Double
    let lengthM: Double
    /** Speed to hold until the end. */
    let targetKmh: Int
    /** The average can no longer end under the limit. */
    let lost: Bool
}

/** A control itinerary; [remainingM] nil when not being driven. */
struct ZoneInfo: Identifiable, Equatable {
    let id: String
    let points: [GeoPoint]
    let lengthM: Double
    let remainingM: Double?
}

/** How a speed limit was obtained; [isGuess]: shown with a grey ring, never spoken. */
enum LimitSourceKind: String {
    case explicit = "EXPLICIT"
    case implicit = "IMPLICIT"
    case inferred = "INFERRED"
    case area = "AREA"

    var isGuess: Bool { self == .inferred || self == .area }
}

enum OverspeedLevelKind: String {
    case none = "NONE"
    case over = "OVER"
    case warn = "WARN"
}

/** Next limit change on the road ahead. */
struct LimitAheadInfo: Equatable {
    let distanceM: Double
    let limitKmh: Int
}

/** One alert event of a fix. */
struct DriveEvent: Equatable {
    enum Kind: String {
        case approach, brake, passed, sectionStarted, sectionEnded, overspeed, limitChanged, zoneEntered, zoneLeft
    }

    let kind: Kind
    let timeMs: Int64
    let radar: RadarInfo?
    let distanceM: Double?
    let limitKmh: Int?
    let speedKmh: Double?
    let wouldFlash: Bool
    /** Licence points at stake when [wouldFlash]. */
    let points: Int
    let averageKmh: Double?
    let level: OverspeedLevelKind
    /** Section picked up while already inside. */
    let resumed: Bool
    let fromKmh: Int?
    let toKmh: Int?
    /** What the voice says; nil for silent events. */
    let text: String?
}

/** Everything the screens need after one fix. */
struct Snapshot: Equatable {
    let timeMs: Int64
    let position: GeoPoint
    /** Speed to display; nil until the GPS is accurate enough. */
    let speedKmh: Int?
    let accuracyM: Double?
    let headingDeg: Double?
    let roadName: String?
    let roadClass: String?
    /** Limit after rain and licence rules. */
    let limitKmh: Int?
    let limitSource: LimitSourceKind?
    let alerts: [AlertInfo]
    let section: SectionInfo?
    let upcoming: UpcomingInfo?
    let limitAhead: LimitAheadInfo?
    /** Road the driver most likely follows, from the car onwards. */
    let ahead: [GeoPoint]
    let events: [DriveEvent]
    /** No GPS (tunnel): position estimated along the road. */
    let estimated: Bool
    /** Control itinerary being driven. */
    let zone: ZoneInfo?
}

// MARK: - From the Kotlin views

private func optInt(_ v: Int32) -> Int? { v >= 0 ? Int(v) : nil }
private func optDouble(_ v: Double) -> Double? { v >= 0 ? v : nil }

private func geoPoints(_ flat: [KotlinDouble]) -> [GeoPoint] {
    var out: [GeoPoint] = []
    out.reserveCapacity(flat.count / 2)
    var i = 0
    while i + 1 < flat.count {
        out.append(GeoPoint(lat: flat[i].doubleValue, lon: flat[i + 1].doubleValue))
        i += 2
    }
    return out
}

extension RadarInfo {
    init(_ r: CoreRadar) {
        self.init(
            id: r.radarId,
            position: GeoPoint(lat: r.lat, lon: r.lon),
            kind: RadarKind(rawValue: r.kind) ?? .fixed,
            label: r.label,
            limitKmh: optInt(r.limitKmh),
            directionDeg: optDouble(r.directionDeg),
            measuresSpeed: r.measuresSpeed,
            mobile: r.mobile,
            reportedAt: r.reportedAtMs >= 0 ? Date(timeIntervalSince1970: Double(r.reportedAtMs) / 1000) : nil
        )
    }
}

extension ZoneInfo {
    init(_ z: CoreZone) {
        self.init(id: z.zoneId, points: geoPoints(z.points), lengthM: z.lengthM, remainingM: optDouble(z.remainingM))
    }
}

extension DriveEvent {
    init(_ e: CoreEvent) {
        self.init(
            kind: Kind(rawValue: e.kind) ?? .limitChanged,
            timeMs: e.timeMs,
            radar: e.radar.map(RadarInfo.init),
            distanceM: optDouble(e.distanceM),
            limitKmh: optInt(e.limitKmh),
            speedKmh: optDouble(e.speedKmh),
            wouldFlash: e.wouldFlash,
            points: Int(e.points),
            averageKmh: optDouble(e.averageKmh),
            level: OverspeedLevelKind(rawValue: e.level) ?? .none,
            resumed: e.resumed,
            fromKmh: optInt(e.fromKmh),
            toKmh: optInt(e.toKmh),
            text: e.text
        )
    }
}

extension Snapshot {
    init(_ s: CoreSnapshot) {
        self.init(
            timeMs: s.timeMs,
            position: GeoPoint(lat: s.lat, lon: s.lon),
            speedKmh: optInt(s.speedKmh),
            accuracyM: optDouble(s.accuracyM),
            headingDeg: optDouble(s.headingDeg),
            roadName: s.roadName,
            roadClass: s.roadClass,
            limitKmh: optInt(s.limitKmh),
            limitSource: s.limitSource.flatMap(LimitSourceKind.init(rawValue:)),
            alerts: s.alerts.map {
                AlertInfo(radar: RadarInfo($0.radar), distanceM: $0.distanceM, limitKmh: optInt($0.limitKmh), overLimit: $0.overLimit, ageMs: $0.ageMs >= 0 ? $0.ageMs : nil)
            },
            section: s.section.map {
                SectionInfo(limitKmh: Int($0.limitKmh), averageKmh: $0.averageKmh, remainingM: $0.remainingM, lengthM: $0.lengthM, targetKmh: Int($0.targetKmh), lost: $0.lost)
            },
            upcoming: s.upcoming.map {
                UpcomingInfo(radar: RadarInfo($0.radar), distanceM: $0.distanceM, limitKmh: optInt($0.limitKmh), roadName: $0.roadName)
            },
            limitAhead: s.limitAheadKmh >= 0 ? LimitAheadInfo(distanceM: s.limitAheadM, limitKmh: Int(s.limitAheadKmh)) : nil,
            ahead: geoPoints(s.ahead),
            events: s.events.map(DriveEvent.init),
            estimated: s.estimated,
            zone: s.drivenZone.map(ZoneInfo.init)
        )
    }
}
