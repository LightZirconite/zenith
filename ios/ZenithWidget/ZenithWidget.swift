import ActivityKit
import SwiftUI
import WidgetKit

// CONTRACT (agent "pip"): the Live Activity's lock screen and Dynamic Island views.

@main
struct ZenithWidgetBundle: WidgetBundle {
    var body: some Widget {
        DriveLiveActivity()
    }
}

struct DriveLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DriveActivityAttributes.self) { context in
            Text(context.state.speedKmh.map { "\($0) km/h" } ?? "Zenith")
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { Text(context.state.speedKmh.map(String.init) ?? "–") }
            } compactLeading: {
                Text(context.state.speedKmh.map(String.init) ?? "–")
            } compactTrailing: {
                Text(context.state.limitKmh.map(String.init) ?? "")
            } minimal: {
                Text(context.state.speedKmh.map(String.init) ?? "–")
            }
        }
    }
}
