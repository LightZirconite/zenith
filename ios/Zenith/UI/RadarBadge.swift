import SwiftUI

// CONTRACT (agent "drive"): port of RadarBadge.kt.

/** Colour of a radar type on the map and the cards. */
func radarColor(_ kind: RadarKind) -> Color { .orange }

/** A small radar sign: its type's colour and its limit. */
struct RadarBadge: View {
    let kind: RadarKind
    let limitKmh: Int?
    var size: CGFloat = 44
    var body: some View { Circle().fill(radarColor(kind)).frame(width: size, height: size) }
}
