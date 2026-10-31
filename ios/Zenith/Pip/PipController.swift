import SwiftUI
import UIKit

// CONTRACT (agent "pip"): keep these names and signatures; fill the bodies.

/**
 * The small window over Waze / Maps: AVPictureInPictureController on an
 * AVSampleBufferDisplayLayer fed with frames of PipScreen. Main thread.
 */
final class PipController: NSObject {
    static let shared = PipController()
    /** The window is open. */
    private(set) var isActive = false
    /** Starts it now (button), if possible. */
    func start() {}
    func stop() {}
}

/** Hosts the window's layer in the drive screen while driving (iOS needs it on screen to open the window). */
struct PipAnchor: View {
    var body: some View { Color.clear.frame(width: 2, height: 2) }
}
