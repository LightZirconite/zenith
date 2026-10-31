import SwiftUI

// CONTRACT (agent "flight"): keep these names and signatures; fill the bodies. Port of Warp.kt.

enum FlightPhase { case idle, search, arrive }

/** Sounds of the flight whose start the picture follows. */
enum FlightSound { case arrive, homecoming }

/** Where the tunnel's heart and the portal sit, as a fraction of the height. */
let HEART_Y: CGFloat = 0.42
/** Share of the smaller side the globe takes in the tunnel. */
let GLOBE_SHARE: CGFloat = 0.15
/** Locked on, before the jump. */
let HOLD_MS = 250
let DIVE_MS = 1_300
let LANDING: Double = 0.66
/** From the lock to the flash: the map lands there. */
let JUMP_IMPACT_MS = HOLD_MS + Int(Double(DIVE_MS) * LANDING)
/** The drive scene fading out on the way back. */
let CLOSE_MS = 500
let HOMECOMING_MS = 2_000

/**
 * The sky behind everything: drifting stars at home, the tunnel and the globe while searching, the
 * dive at the lock, the way back at the end of a drive.
 */
struct Starfield: View {
    let phase: FlightPhase
    let gps: GpsInfo
    let onDive: () -> Void
    let onLanded: () -> Void
    let onGone: () -> Void
    var comingHome: Bool = false
    var home: GeoPoint? = nil
    var heardAt: (FlightSound) -> Date? = { _ in nil }

    var body: some View { Color.black }
}

/** A ring of light leaving the heart of the tunnel; [progress] 0 to 1. */
struct ShockRing: View {
    let progress: Double
    var body: some View { EmptyView() }
}

/** The words under the globe: searching, triangulation, locked, roads loading. */
struct FlightCaption: View {
    let gps: GpsInfo
    let demo: Bool
    var locked: Bool = false
    var roadsLoading: Bool = false
    var body: some View { Text(locked ? "Position verrouillée" : "Contact des satellites…").foregroundStyle(.white) }
}

/** Too fast near a radar: the edges of the whole screen pulse red. */
struct DangerGlow: View {
    let on: Bool
    var body: some View { EmptyView() }
}

/** A radar passed: a band of light sweeps down the screen, green (red if it would have flashed). */
struct RadarPassWave: View {
    let progress: Double
    let flash: Bool
    var body: some View { EmptyView() }
}

/** The quiet sky of home behind the pages (settings, permissions). */
struct SpaceBackdrop: View {
    var body: some View { Color.black.ignoresSafeArea() }
}
