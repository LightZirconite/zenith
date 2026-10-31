import ActivityKit
import Foundation

// CONTRACT (agent "pip"): shared by the app and the widget extension.

struct DriveActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var speedKmh: Int?
        var limitKmh: Int?
    }

    var demo: Bool
}
