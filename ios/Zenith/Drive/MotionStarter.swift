import CoreLocation
import CoreMotion

/**
 * "Démarrage en roulant" on iOS: with the position "Toujours", significant location changes wake
 * Zenith in the background (about every 500 m, nearly no battery, app closed); the motion
 * coprocessor then says whether the phone rides in a vehicle, and the drive starts by itself (a
 * notification says so: the small window opens once Zenith is opened). Walking, running or cycling
 * for sure ends a drive it started, once slow ([DriveEngine.motionExit]); a false start that never
 * drives ends after 5 min (ParkDetector). Main thread.
 */
final class MotionStarter: NSObject, CLLocationManagerDelegate {
    static let shared = MotionStarter()

    private let manager = CLLocationManager()
    private let activity = CMMotionActivityManager()
    private var watching = false

    override init() {
        super.init()
        manager.delegate = self
    }

    /** Follows the setting and the permissions; called at launch, on a background wake and when they change. */
    func sync() {
        let want = SettingsStore.shared.motionStart
            && manager.authorizationStatus == .authorizedAlways
            && CMMotionActivityManager.isActivityAvailable()
            && CMMotionActivityManager.authorizationStatus() != .denied
            && CMMotionActivityManager.authorizationStatus() != .restricted
        if want && !watching {
            watching = true
            manager.startMonitoringSignificantLocationChanges()
            activity.startActivityUpdates(to: .main) { [weak self] a in
                if let a { self?.onActivity(a) }
            }
        } else if !want && watching {
            watching = false
            manager.stopMonitoringSignificantLocationChanges()
            activity.stopActivityUpdates()
        }
    }

    private func onActivity(_ a: CMMotionActivity) {
        guard SettingsStore.shared.motionStart else { return }
        if a.automotive && a.confidence != .low {
            startIfIdle()
        } else if (a.walking || a.running || a.cycling) && a.confidence == .high {
            DriveEngine.shared.motionExit()
        }
    }

    /** Woken by a significant change: was the phone riding these last minutes? */
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard SettingsStore.shared.motionStart, !DriveModel.shared.running else { return }
        let now = Date()
        activity.queryActivityStarting(from: now.addingTimeInterval(-180), to: now, to: .main) { [weak self] list, _ in
            guard let last = list?.last, last.automotive, last.confidence != .low else { return }
            self?.startIfIdle()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        sync()
    }

    private func startIfIdle() {
        guard !DriveModel.shared.running else { return }
        DriveEngine.shared.start(origin: .motion)
    }
}
