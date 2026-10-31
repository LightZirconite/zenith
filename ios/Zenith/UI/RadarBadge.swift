import SwiftUI

/** Colour of each radar type as 0xRRGGBB, shared by the map badges and the screen. */
private func radarRGB(_ kind: RadarKind) -> UInt32 {
    switch kind {
    case .redLight: return 0xFF9F0A
    case .sectionStart, .sectionEnd: return 0xBF5AF2
    case .workzone: return 0xFFD60A
    case .mobile, .mobileVehicle, .police: return 0xFF375F
    default: return 0xFF3B30
    }
}

/** Colour of a radar type on the map and the cards. */
func radarColor(_ kind: RadarKind) -> Color {
    let rgb = radarRGB(kind)
    return Color(
        .sRGB,
        red: Double((rgb >> 16) & 0xFF) / 255,
        green: Double((rgb >> 8) & 0xFF) / 255,
        blue: Double(rgb & 0xFF) / 255,
        opacity: 1
    )
}

/** "#RRGGBB" for MapLibre styles. */
func radarColorHex(_ kind: RadarKind) -> String {
    String(format: "#%06X", radarRGB(kind))
}

/** Round badge like the ones on the map: white disc, ring in the radar's colour, limit inside. */
struct RadarBadge: View {
    let kind: RadarKind
    let limitKmh: Int?
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            Circle().fill(Color.white)
            Circle().strokeBorder(radarColor(kind), lineWidth: size / 9)
            Text(limitKmh.map(String.init) ?? "!")
                .font(.system(size: size * ((limitKmh ?? 0) >= 100 ? 0.30 : 0.36), weight: .bold))
                .foregroundStyle(Color.black)
                .lineLimit(1)
                .fixedSize()
        }
        .frame(width: size, height: size)
    }
}
