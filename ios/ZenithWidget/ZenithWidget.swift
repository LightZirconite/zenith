import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct ZenithWidgetBundle: WidgetBundle {
    var body: some Widget {
        DriveLiveActivity()
    }
}

/**
 * The drive's Live Activity, Android's drive notification on iOS: on the lock screen a dark banner
 * (big speed, limit sign, next radar, section, itinerary, road, "Arrêter"), in the Dynamic Island
 * the speed and the limit, opened with the next radar; the small family for CarPlay and the
 * Apple Watch. Red when too fast near a radar.
 */
struct DriveLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DriveActivityAttributes.self) { context in
            DriveBanner(state: context.state, demo: context.attributes.demo, stale: context.isStale)
                .activityBackgroundTint(context.state.danger ? Palette.dangerTint : Palette.background)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    SpeedBlock(state: s, size: 40)
                        .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let limit = s.limitKmh {
                        LimitDisc(limit: limit, guess: s.limitGuess, size: 44)
                            .padding(.trailing, 6)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.demo ? "Démo · trajet simulé" : s.tunnel ? "Tunnel · position estimée" : s.road ?? "Zenith")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 8) {
                        InfoRow(info: s.infos.first { $0.kind != .road } ?? s.fallback, primary: true)
                        Spacer(minLength: 4)
                        StopButton()
                    }
                    .padding(.horizontal, 6)
                }
            } compactLeading: {
                Text(s.speedText)
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(s.speedColor)
            } compactTrailing: {
                if let limit = s.limitKmh {
                    LimitDisc(limit: limit, guess: s.limitGuess, size: 22)
                } else {
                    Text("km/h")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.6))
                }
            } minimal: {
                Text(s.speedText)
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(s.danger ? Palette.red : s.speedColor)
            }
            .keylineTint(s.danger ? Palette.red : nil)
        }
        .supplementalActivityFamilies([.small])
    }
}

// MARK: - Lock screen

/** The lock screen banner; the small family (CarPlay, Apple Watch) keeps the speed, the limit and one line. */
private struct DriveBanner: View {
    let state: DriveActivityAttributes.ContentState
    let demo: Bool
    let stale: Bool
    @Environment(\.activityFamily) private var family

    var body: some View {
        Group {
            switch family {
            case .small: small
            default: full
            }
        }
        // No news from the app for 2 minutes: shown faded rather than as if live.
        .opacity(stale ? 0.5 : 1)
    }

    private var full: some View {
        let infos = state.infos
        return VStack(spacing: 10) {
            HStack(alignment: .center, spacing: 14) {
                SpeedBlock(state: state, size: 48)
                VStack(alignment: .leading, spacing: 4) {
                    InfoRow(info: infos.first ?? state.fallback, primary: true)
                    ForEach(Array(infos.dropFirst().prefix(2)), id: \.self) { InfoRow(info: $0, primary: false) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let limit = state.limitKmh {
                    LimitDisc(limit: limit, guess: state.limitGuess, size: 54)
                }
            }
            HStack(spacing: 6) {
                if demo { Chip(text: "Démo · trajet simulé", color: Palette.blue) }
                if state.tunnel { Chip(text: "Tunnel · position estimée", color: Palette.dim) }
                Spacer(minLength: 0)
                StopButton()
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
    }

    private var small: some View {
        HStack(spacing: 10) {
            SpeedBlock(state: state, size: 32)
            InfoRow(info: state.infos.first { $0.kind != .road } ?? state.fallback, primary: false)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let limit = state.limitKmh {
                LimitDisc(limit: limit, guess: state.limitGuess, size: 34)
            }
        }
        .padding(10)
    }
}

// MARK: - Pieces

/** Colours of the drive screen. */
private enum Palette {
    static let red = Color(red: 1, green: 0x3B / 255, blue: 0x30 / 255)
    static let orange = Color(red: 1, green: 0x9F / 255, blue: 0x0A / 255)
    static let dim = Color(red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255)
    static let blue = Color(red: 0x0A / 255, green: 0x84 / 255, blue: 1)
    static let purple = Color(red: 0xBF / 255, green: 0x5A / 255, blue: 0xF2 / 255)
    static let background = Color.black.opacity(0.82)
    static let dangerTint = Color(red: 0.42, green: 0.04, blue: 0.03).opacity(0.92)
}

/** One line of the drive: what it says and the sign before it. */
private struct Info: Hashable {
    enum Kind { case section, radar, zone, road, quiet }

    let kind: Kind
    let text: String
    var radarColor: UInt32? = nil
    var radarLimit: Int? = nil
}

private extension DriveActivityAttributes.ContentState {
    /** Android's order: section under way, next radar, control itinerary, road. */
    var infos: [Info] {
        var out: [Info] = []
        if let section { out.append(Info(kind: .section, text: section)) }
        if let radar {
            let text = radarDistance.map { "\(radar) · \($0)" } ?? radar
            out.append(Info(kind: .radar, text: text, radarColor: radarColor, radarLimit: radarLimitKmh))
        }
        if let zone { out.append(Info(kind: .zone, text: zone)) }
        if let road { out.append(Info(kind: .road, text: road)) }
        return out
    }

    /** Nothing to tell: still searching, or a clear road. */
    var fallback: Info {
        Info(kind: .quiet, text: speedKmh == nil ? "Recherche des satellites…" : "Aucun radar sur ta route pour l'instant")
    }

    var speedText: String { speedKmh.map(String.init) ?? "--" }

    /** White, orange just under the limit, red above it. */
    var speedColor: Color {
        guard let speed = speedKmh, let limit = limitKmh else { return .white }
        if speed > limit { return Palette.red }
        if speed >= limit - 3 { return Palette.orange }
        return .white
    }
}

private struct InfoRow: View {
    let info: Info
    let primary: Bool

    var body: some View {
        HStack(spacing: 6) {
            sign
            Text(info.text)
                .font(primary ? Font.headline : Font.subheadline)
                .foregroundStyle(primary ? Color.white : Color.white.opacity(0.6))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
    }

    @ViewBuilder
    private var sign: some View {
        let size: CGFloat = primary ? 22 : 16
        switch info.kind {
        case .radar:
            RadarDot(color: info.radarColor.map(rgbColor) ?? Palette.red, limit: info.radarLimit, size: size)
        case .section:
            Image(systemName: "gauge.with.needle").font(.system(size: size * 0.7, weight: .bold)).foregroundStyle(Palette.purple)
        case .zone:
            Image(systemName: "point.topleft.down.curvedto.point.bottomright.up").font(.system(size: size * 0.7, weight: .bold)).foregroundStyle(Palette.orange)
        case .road:
            Image(systemName: "signpost.right").font(.system(size: size * 0.65, weight: .semibold)).foregroundStyle(.white.opacity(0.6))
        case .quiet:
            Image(systemName: "checkmark.shield").font(.system(size: size * 0.7, weight: .semibold)).foregroundStyle(.white.opacity(0.6))
        }
    }

    private func rgbColor(_ rgb: UInt32) -> Color {
        Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

/** The big number: white, orange near the limit, red over it; it rolls from one value to the next. */
private struct SpeedBlock: View {
    let state: DriveActivityAttributes.ContentState
    let size: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: -size * 0.12) {
            Text(state.speedText)
                .font(.system(size: size, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(state.speedColor)
                .contentTransition(.numericText(value: Double(state.speedKmh ?? 0)))
            Text("km/h")
                .font(.system(size: size * 0.26, weight: .semibold))
                .foregroundStyle(.white.opacity(0.55))
        }
        .fixedSize()
    }
}

/** Round limit sign: white disc, red ring (grey when the limit is only inferred). */
private struct LimitDisc: View {
    let limit: Int
    let guess: Bool
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color.white)
            Circle().strokeBorder(guess ? Palette.dim : Palette.red, lineWidth: size * 0.106)
            Text("\(limit)")
                .font(.system(size: size * (limit >= 100 ? 0.327 : 0.385), weight: .bold))
                .foregroundStyle(Color.black)
        }
        .frame(width: size, height: size)
    }
}

/** A radar's small sign, like the map's: white disc, ring in its kind's colour, its limit inside. */
private struct RadarDot: View {
    let color: Color
    let limit: Int?
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color.white)
            Circle().strokeBorder(color, lineWidth: size / 9)
            Text(limit.map(String.init) ?? "!")
                .font(.system(size: size * ((limit ?? 0) >= 100 ? 0.30 : 0.36), weight: .bold))
                .foregroundStyle(Color.black)
        }
        .frame(width: size, height: size)
    }
}

/** A small coloured label: the demo, the tunnel. */
private struct Chip: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(color)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(0.18)))
    }
}

/** "Arrêter", like the Android notification's action: stops the drive in the app's process. */
private struct StopButton: View {
    var body: some View {
        Button(intent: StopDriveIntent()) {
            Label("Arrêter", systemImage: "stop.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(Color.white.opacity(0.16)))
        }
        .buttonStyle(.plain)
    }
}
