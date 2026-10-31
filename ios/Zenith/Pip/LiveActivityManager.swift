import Foundation

// CONTRACT (agent "pip"): keep these names and signatures; fill the bodies.

/** The drive on the lock screen and in the Dynamic Island (ActivityKit). Main thread. */
final class LiveActivityManager {
    static let shared = LiveActivityManager()
    func start(demo: Bool) {}
    func update(_ s: Snapshot) {}
    func end() {}
}
