import SwiftUI
import UIKit
import UserNotifications

@main
struct ZenithApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
        }
    }
}

/**
 * Launch, including a background relaunch by a significant location change (the motion start
 * watches again at once), and notifications shown even while Zenith is in front.
 */
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        CrashGuard.shared.install()
        UNUserNotificationCenter.current().delegate = self
        UiSound.prepare()
        Speaker.prewarm()
        WarpSound.shared.prepare()
        MotionStarter.shared.sync()
        // A Live Activity left by a killed app goes away at once.
        if !DriveModel.shared.running { LiveActivityManager.shared.end() }
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
