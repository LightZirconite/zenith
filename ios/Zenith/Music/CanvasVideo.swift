import SwiftUI

// CONTRACT (agent "music"): keep these names and signatures; fill the bodies. Port of CanvasVideo.kt.

/** A Canvas video from its local file: muted, looping, cropped to fill; plays while [playing]. */
struct CanvasVideo: View {
    let file: URL
    let playing: Bool
    var onFirstFrame: () -> Void = {}
    var body: some View { Color.clear }
}
