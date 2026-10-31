import SwiftUI

// CONTRACT (agent "music"): keep these names and signatures; fill the bodies. Port of MediaBar.kt.

/** The music card: cover, title, artist, controls, progress line; swipe for next / previous. */
struct MediaBar: View {
    let media: MediaInfo
    /** A tap on the cover or the title: music mode. */
    var onTap: () -> Void = {}
    var body: some View { Text(media.title).foregroundStyle(.white).onTapGesture(perform: onTap) }
}

/**
 * The music's mood at the scale of the screen: the Canvas video, else the blurred cover, rising
 * from the bottom under the map. [focus] 0…1: music mode. [calm]: an alert, it fades further.
 */
struct MusicBackdrop: View {
    let media: MediaInfo
    let canvas: Bool
    let calm: Bool
    var focus: Double = 0
    var fromRight: Bool = false
    var body: some View { Color.clear }
}
