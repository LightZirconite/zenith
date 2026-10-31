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

    private static func code(_ choice: Bool?) -> Int32 {
        switch choice {
        case .some(true): return 1
        case .some(false): return 0
        case .none: return -1
        }
    }
}
