import ActivityKit
import Foundation
import SwiftUI
import UIKit

/**
 * The drive on the lock screen, in the Dynamic Island and on CarPlay (ActivityKit), like Android's
 * drive notification: speed and limit, the next radar, the section, the control itinerary, the
 * road, and an "Arrêter" button. Updated by the app itself (no push: it runs in the background for
 * the whole drive) when a shown value changes, at most once a second, and every 30 s anyway so it
 * never looks stale while it is not. Like the Android notification, it comes back: swiped away, or
 * ended by iOS after hours, a new one is asked; iOS refuses to start one from the background (drive
 * started by the car or by riding), so it comes once Zenith is in front again. Main thread.
 */
final class LiveActivityManager {
    static let shared = LiveActivityManager()

    private typealias State = DriveActivityAttributes.ContentState

    private var activity: Activity<DriveActivityAttributes>?
    /** A drive wants one: start(demo:) was called and end() not yet. */
    private var wanted = false
    private var demo = false
    private var lastAsk = Date.distantPast
    private var sent: State?
    private var sentAt = Date.distantPast
    private var pending: DispatchWorkItem?
    /** Updates go out one after the other, never an older one over a newer. */
    private var sending: Task<Void, Never>?

    private static let minGap: TimeInterval = 1
    private static let refreshEvery: TimeInterval = 30
    private static let staleAfter: TimeInterval = 120
    /** Between two requests while iOS refuses one (Zenith in the background). */
    private static let askEvery: TimeInterval = 5

    /**
     * The widget extension is in the app: SideStore offers to remove extensions at the install (it
     * saves an App ID of a free account), and the Live Activity goes with it.
     */
    static let installed: Bool = {
        guard let plugins = Bundle.main.builtInPlugInsURL,
              let names = try? FileManager.default.contentsOfDirectory(atPath: plugins.path) else { return false }
        return names.contains("ZenithLiveActivity.appex")
    }()

    func start(demo: Bool) {
        guard Self.installed else { return }
        end()
        wanted = true
        self.demo = demo
        request(Self.state(nil))
    }

    func update(_ s: Snapshot) {
        let state = Self.state(s)
        guard let activity else {
            if wanted, Date().timeIntervalSince(lastAsk) >= Self.askEvery { request(state) }
            return
        }
        guard activity.activityState != .dismissed && activity.activityState != .ended else {
            // Swiped away, or ended by iOS after hours: a new one as soon as iOS allows it.
            self.activity = nil
            sent = nil
            request(state)
            return
        }
        let now = Date()
        if state == sent && now.timeIntervalSince(sentAt) < Self.refreshEvery { return }
        let wait = Self.minGap - now.timeIntervalSince(sentAt)
        pending?.cancel()
        pending = nil
        if wait > 0 {
            let work = DispatchWorkItem { [weak self] in self?.send(state) }
            pending = work
            DispatchQueue.main.asyncAfter(deadline: .now() + wait, execute: work)
        } else {
            send(state)
        }
    }

    func end() {
        wanted = false
        pending?.cancel()
        pending = nil
        // Also any left by an earlier run that did not end its drive (app killed).
        var ending = Activity<DriveActivityAttributes>.activities
        if let activity, !ending.contains(where: { $0.id == activity.id }) { ending.append(activity) }
        let last = sent
        activity = nil
        sent = nil
        let previous = sending
        sending = nil
        guard !ending.isEmpty else { return }
        Task {
            await previous?.value
            for a in ending {
                await a.end(last.map { ActivityContent(state: $0, staleDate: nil) }, dismissalPolicy: .immediate)
            }
        }
    }

    // MARK: -

    private func request(_ state: State) {
        lastAsk = Date()
        guard ActivityAuthorizationInfo().areActivitiesEnabled, Self.inFront() else { return }
        do {
            activity = try Activity.request(
                attributes: DriveActivityAttributes(demo: demo),
                content: ActivityContent(state: state, staleDate: Date().addingTimeInterval(Self.staleAfter)),
                pushType: nil
            )
            sent = state
            sentAt = Date()
        } catch {
            NSLog("Zenith live activity: \(error)")
        }
    }

    private func send(_ state: State) {
        pending = nil
        guard let activity else { return }
        sent = state
        sentAt = Date()
        let content = ActivityContent(state: state, staleDate: Date().addingTimeInterval(Self.staleAfter))
        let previous = sending
        sending = Task {
            await previous?.value
            await activity.update(content)
        }
    }

    /** iOS starts a Live Activity only for an app in front. */
    private static func inFront() -> Bool {
        MainActor.assumeIsolated { UIApplication.shared.applicationState == .active }
    }

    /** What the Live Activity shows for this snapshot (nil: the GPS still searching). */
    private static func state(_ s: Snapshot?) -> State {
        let alert = s?.alerts.first
        let upcoming = s?.upcoming
        let radar = alert?.radar ?? upcoming?.radar
        let distance = alert?.distanceM ?? upcoming?.distanceM
        return State(
            speedKmh: DriveModel.shared.shownSpeed ?? s?.speedKmh,
            limitKmh: s?.limitKmh,
            limitGuess: s?.limitSource?.isGuess == true,
            danger: alert?.overLimit == true,
            radar: radar?.label,
            radarColor: radar.map { rgb(radarColor($0.kind)) },
            radarLimitKmh: alert.map { $0.limitKmh } ?? upcoming?.limitKmh,
            radarDistance: distance.map { "\(km($0)) km" },
            section: s?.section.map { "Tronçon : moyenne \(Int($0.averageKmh)), limite \($0.limitKmh) · encore \(km($0.remainingM)) km" },
            zone: s?.zone.map { "Itinéraire de contrôle · \(max(1, Int(($0.remainingM ?? $0.lengthM) / 1_000))) km" },
            road: s?.roadName,
            tunnel: s?.estimated == true
        )
    }

    /** Tenths of a kilometre, French decimal comma: changes rarely, so few updates. */
    private static func km(_ m: Double) -> String {
        String(format: "%.1f", m / 1_000).replacingOccurrences(of: ".", with: ",")
    }

    /** 0xRRGGBB of a colour, for the widget, which has no radar kinds of its own. */
    private static func rgb(_ color: Color) -> UInt32 {
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        func byte(_ v: CGFloat) -> UInt32 { UInt32((min(max(v, 0), 1) * 255).rounded()) }
        return byte(r) << 16 | byte(g) << 8 | byte(b)
    }
}
