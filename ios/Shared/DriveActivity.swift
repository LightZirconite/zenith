import ActivityKit
import Foundation

/**
 * The drive as a Live Activity (lock screen, Dynamic Island, CarPlay): what the app sends and the
 * widget extension draws, like Android's drive notification. Compiled into both targets.
 */
struct DriveActivityAttributes: ActivityAttributes {
    /** What the drive shows now; the app sends a new one when a shown value changes. */
    struct ContentState: Codable, Hashable {
        /** Nil while the GPS is still searching. */
        var speedKmh: Int?
        var limitKmh: Int?
        /** Limit inferred (road type, built-up area): grey ring, like the drive screen. */
        var limitGuess: Bool
        /** Over the limit near a radar: everything turns red. */
        var danger: Bool
        /** The radar being announced, else the next one on the road: "Radar fixe". */
        var radar: String?
        /** Its kind's colour, 0xRRGGBB. */
        var radarColor: UInt32?
        var radarLimitKmh: Int?
        /** "1,2 km", rounded to 100 m. */
        var radarDistance: String?
        /** "Tronçon : moyenne 67, limite 70 · encore 0,8 km". */
        var section: String?
        /** "Itinéraire de contrôle · 12 km". */
        var zone: String?
        var road: String?
        /** No GPS (tunnel): the position is estimated along the road. */
        var tunnel: Bool
    }

    /** The hidden simulated drive. */
    var demo: Bool
}
