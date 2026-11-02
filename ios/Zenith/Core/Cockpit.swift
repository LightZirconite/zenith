import Foundation
import ZenithCore

/**
 * What the drive screen gives room to, from the speed: the core's rules (core/Cockpit.kt), the same
 * on Android and iOS, on the drive screen and in the small window.
 */
enum Cockpit {
    /** A tap's choice lasts at most this long without a new tap. */
    static let choiceMs: Int64 = Bridge.shared.choiceMs()

    /** The music's share shown, 0 to 1: the tap's [choice] if any, else the speed; nothing during an alert. */
    static func music(speedKmh: Int?, choice: Bool?, alerting: Bool) -> Double {
        Double(Bridge.shared.music(speedKmh: Int32(speedKmh ?? -1), choice: code(choice), alerting: alerting))
    }

    /** The tap's choice still holding, nil when the speed leads again. */
    static func keepChoice(_ choice: Bool?, speedKmh: Int?, ageMs: Int64) -> Bool? {
        switch Bridge.shared.keepChoice(choice: code(choice), speedKmh: Int32(speedKmh ?? -1), ageMs: ageMs) {
        case 1: return true
        case 0: return false
        default: return nil
        }
    }

    /** A tap on the music card: the opposite of what is shown now. */
    static func toggle(shown: Double) -> Bool { Bridge.shared.toggleChoice(shown: Float(shown)) }

    /** Moving on: the buttons get smaller and the shortcuts fold away; [was] the state so far. */
    static func cruising(speedKmh: Int?, was: Bool) -> Bool {
        Bridge.shared.cruising(speedKmh: Int32(speedKmh ?? -1), was: was)
    }

    /** Gain from 0 to 1 for an interface sound at this output volume (0 to 1): loud volumes turn it down (core/Loudness.kt). */
    static func uiGain(volume: Float) -> Float {
        Bridge.shared.uiGain(volume: Int32((volume * 16).rounded()), max: 16)
    }

    /** Gain for a button sound: [uiGain], 6 dB lower (core Loudness.tapGain). */
    static func tapGain(volume: Float) -> Float {
        Bridge.shared.tapGain(volume: Int32((volume * 16).rounded()), max: 16)
    }

    // MARK: Alert island, speed ring, night (core/Island.kt, core/Daylight.kt)

    /** What the alert island shows; [passedAgoMs] -1 when no radar was passed. */
    static func islandMode(alert: Bool, overLimit: Bool, section: Bool, passedAgoMs: Int64) -> IslandMode {
        IslandMode(rawValue: Int(Bridge.shared.islandState(alert: alert, overLimit: overLimit, section: section, passedAgoMs: passedAgoMs))) ?? .idle
    }

    /** How long the small "Radar passé" stays. */
    static let islandPassedMs: Int64 = Bridge.shared.islandPassedMs()

    /** The approach's progress, 0 at first sight of the radar ([startM]) to 1 on it. */
    static func islandProgress(distanceM: Double, startM: Double) -> Double {
        Double(Bridge.shared.islandProgress(distanceM: distanceM, startM: startM))
    }

    /** "18 km/h au-dessus" under "Ralentis"; nil when not above the limit. */
    static func islandOverText(speedKmh: Int?, limitKmh: Int?) -> String? {
        Bridge.shared.islandOverText(speedKmh: Int32(speedKmh ?? -1), limitKmh: Int32(limitKmh ?? -1))
    }

    /** Share of the speed ring filled, 0 to 1. */
    static func gaugeFill(speedKmh: Int?, limitKmh: Int?) -> Double {
        Double(Bridge.shared.gaugeFill(speedKmh: Int32(speedKmh ?? -1), limitKmh: Int32(limitKmh ?? -1)))
    }

    /** Where the limit's tick sits on the ring, 0 to 1; nil without a limit. */
    static func gaugeTick(limitKmh: Int?) -> Double? {
        let tick = Bridge.shared.gaugeTick(limitKmh: Int32(limitKmh ?? -1))
        return tick < 0 ? nil : Double(tick)
    }

    /** The ring's arc in degrees, and its start clockwise from 3 o'clock (bottom left). */
    static let gaugeSweepDeg = Double(Bridge.shared.gaugeSweepDeg())
    static let gaugeStartDeg = Double(Bridge.shared.gaugeStartDeg())

    /** Night at [position] now: the map and the Canvas dim a little. */
    static func isNight(_ position: GeoPoint, at date: Date = Date()) -> Bool {
        Bridge.shared.isNight(lat: position.lat, lon: position.lon, timeMs: Int64(date.timeIntervalSince1970 * 1000))
    }

    /** Opacity of the map and the Canvas at night. */
    static let nightDim = Double(Bridge.shared.nightDim())

    /** How long the trip summary stays at home. */
    static let tripShownMs: Int64 = Bridge.shared.tripShownMs()

    private static func code(_ choice: Bool?) -> Int32 {
        switch choice {
        case .some(true): return 1
        case .some(false): return 0
        case .none: return -1
        }
    }
}

/** What the alert island shows, most urgent first (core Island.State, same order). */
enum IslandMode: Int {
    case idle = 0
    case approach = 1
    case slowDown = 2
    case section = 3
    case passed = 4
}
