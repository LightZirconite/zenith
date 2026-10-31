import CoreLocation
import CoreMotion
import MediaPlayer
import Observation
import UIKit
import UserNotifications

/**
 * The authorizations Zenith asks for, as on Android (all required, explained before iOS's own
 * prompt): position (then "Toujours"), notifications, motion (start while riding), Apple Music.
 * [refresh] reads them again (at launch and each return to the app). Main thread.
 */
@Observable
final class Permissions: NSObject, CLLocationManagerDelegate {
    static let shared = Permissions()

    enum Location { case notAsked, denied, whenInUse, always }

    private(set) var location = Location.notAsked
    private(set) var notifications = false
    private(set) var notificationsAsked = false
    private(set) var motion = false
    private(set) var motionAsked = false
    private(set) var appleMusic = false
    private(set) var appleMusicAsked = false

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private let activity = CMMotionActivityManager()

    override init() {
        super.init()
        manager.delegate = self
        refresh()
    }

    /** Everything granted (the drive screen opens); the motion start counts only when wanted. */
    var allGranted: Bool { missing == 0 }

    /** How many authorizations are still missing. */
    var missing: Int {
        var n = 0
        if location != .always { n += 1 }
        if !notifications { n += 1 }
        // A device without motion coprocessor (some iPads) cannot give it: never blocks the welcome.
        if SettingsStore.shared.motionStart && CMMotionActivityManager.isActivityAvailable() && !motion { n += 1 }
        if !appleMusic { n += 1 }
        return n
    }

    func refresh() {
        switch manager.authorizationStatus {
        case .authorizedAlways: location = .always
        case .authorizedWhenInUse: location = .whenInUse
        case .denied, .restricted: location = .denied
        default: location = .notAsked
        }
        let m = CMMotionActivityManager.authorizationStatus()
        motion = m == .authorized
        motionAsked = m != .notDetermined
        let music = MPMediaLibrary.authorizationStatus()
        appleMusic = music == .authorized
        appleMusicAsked = music != .notDetermined
        UNUserNotificationCenter.current().getNotificationSettings { s in
            DispatchQueue.main.async {
                self.notifications = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
                self.notificationsAsked = s.authorizationStatus != .notDetermined
            }
        }
    }

    /** First "while using", then "always" (iOS asks the second step itself). Asked twice: the settings. */
    func requestLocation() {
        switch location {
        case .notAsked: manager.requestWhenInUseAuthorization()
        case .whenInUse: manager.requestAlwaysAuthorization()
        case .denied: openSettings()
        case .always: break
        }
    }

    func requestNotifications() {
        if notificationsAsked && !notifications { return openSettings() }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            DispatchQueue.main.async { self.refresh() }
        }
    }

    /** iOS asks for motion on the first activity query. */
    func requestMotion() {
        if motionAsked && !motion { return openSettings() }
        let now = Date()
        activity.queryActivityStarting(from: now.addingTimeInterval(-60), to: now, to: .main) { _, _ in
            self.refresh()
            MotionStarter.shared.sync()
        }
    }

    func requestAppleMusic() {
        if appleMusicAsked && !appleMusic { return openSettings() }
        MPMediaLibrary.requestAuthorization { _ in
            DispatchQueue.main.async { self.refresh() }
        }
    }

    /** Zenith's page in the iOS settings. */
    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        refresh()
        MotionStarter.shared.sync()
    }
}
