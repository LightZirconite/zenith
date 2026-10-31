import SwiftUI

// CONTRACT (agent "pip"): keep these names and signatures; fill the bodies. Port of PipScreen.kt.

/** Zenith in miniature, drawn into the picture-in-picture window. */
struct PipScreen: View {
    let snapshot: Snapshot?
    let radars: [RadarInfo]
    var zones: [ZoneInfo] = []
    var media: MediaInfo? = nil
    var canvas: Bool = false
    var musicChoice: Bool? = nil
    var speedKmh: Int? = nil
    var body: some View { Color.black }
}
