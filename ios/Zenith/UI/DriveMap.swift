import SwiftUI

// CONTRACT (agent "drive"): port of DriveMap.kt with MapLibre Native iOS (OpenFreeMap tiles).

struct DriveMap: View {
    let snapshot: Snapshot?
    let radars: [RadarInfo]
    var zones: [ZoneInfo] = []
    /** Height fraction where the car arrow sits, from the top. */
    var carAt: Double = 0.45
    var body: some View { Color.black }
}
