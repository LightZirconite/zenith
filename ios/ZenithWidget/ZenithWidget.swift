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
 * The drive's Live Activity, Android's drive notification on iOS. On the lock screen one card: the
 * logo and the road, the big speed and the limit sign, the next radar with its countdown, and
 * "Arrêter la conduite". In the Dynamic Island the speed and the radar's distance (or the limit),
 * opened with the speed, the limit and the radar on its road; the small family for CarPlay and the
 * Apple Watch. Red when too fast near a radar. While Zenith is in front iOS hides it from the
 * Dynamic Island: the drive screen draws its own island there (DriveScreen.AlertIsland).
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
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(s.speedText)
                            .font(.system(size: 26, weight: .heavy))
                            .monospacedDigit()
                            .foregroundStyle(s.speedColor)
                            .contentTransition(.numericText(value: Double(s.speedKmh ?? 0)))
                        Text("km/h")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Palette.label)
                    }
                    .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let limit = s.limitKmh {
                        LimitDisc(limit: limit, guess: s.limitGuess, size: 34)
                            .padding(.trailing, 6)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Group {
                        if let radar = s.radar {
                            VStack(spacing: 10) {
                                HStack(spacing: 12) {
                                    RadarSquare(color: s.radarTint, size: 32)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(radar)
                                            .font(.system(size: 16, weight: .semibold))
                                            .foregroundStyle(.white)
                                            .lineLimit(1)
                                        Text(s.road.map { "sur \($0)" } ?? (context.attributes.demo ? "Démo · trajet simulé" : "sur ta route"))
                                            .font(.system(size: 13))
                                            .foregroundStyle(Palette.label)
                                            .lineLimit(1)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    if let distance = s.radarDistance {
                                        DistanceText(text: distance, number: 24, unit: 13)
                                    }
                                }
                                Countdown(progress: s.radarProgress ?? 0)
                            }
                        } else {
                            InfoRow(info: s.infos.first { $0.kind != .road } ?? s.fallback, primary: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(.horizontal, 6)
                }
            } compactLeading: {
                HStack(alignment: .firstTextBaseline, spacing: 2) {
                    Text(s.speedText)
                        .font(.system(size: 16, weight: .heavy))
                        .monospacedDigit()
                        .foregroundStyle(s.speedColor)
                    Text("km/h")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Palette.label)
                }
            } compactTrailing: {
                if let distance = s.radarDistance, s.radar != nil {
                    HStack(spacing: 5) {
                        RadarSquare(color: s.radarTint, size: 16)
                        Text(distance)
                            .font(.system(size: 14, weight: .bold))
                            .monospacedDigit()
                            .foregroundStyle(.white)
                    }
                } else if let limit = s.limitKmh {
                    LimitDisc(limit: limit, guess: s.limitGuess, size: 22)
                }
            } minimal: {
                Text(s.speedText)
                    .font(.system(size: 14, weight: .heavy))
                    .monospacedDigit()
                    .foregroundStyle(s.danger ? Palette.red : s.speedColor)
            }
            .keylineTint(s.danger ? Palette.red : nil)
        }
        .supplementalActivityFamilies([.small])
    }
}

// MARK: - Lock screen

/** The lock screen card; the small family (CarPlay, Apple Watch) keeps the speed, the limit and one line. */
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

    /**
     * One card, kept within the 160 pt iOS gives a Live Activity on the lock screen: header, speed
     * and limit, the radar and its countdown, the stop button.
     */
    private var full: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                LogoMark()
                    .frame(width: 16, height: 16)
                Text("Zenith")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.mist.opacity(0.7))
                Spacer(minLength: 8)
                Text(headerRight)
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.label)
                    .lineLimit(1)
            }
            .frame(height: 15)
            HStack(alignment: .center, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(state.speedText)
                        .font(.system(size: 56, weight: .heavy))
                        .tracking(-2.4)
                        .monospacedDigit()
                        .foregroundStyle(state.speedColor)
                        .contentTransition(.numericText(value: Double(state.speedKmh ?? 0)))
                    Text("km/h")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Palette.label)
                }
                Spacer(minLength: 8)
                if let limit = state.limitKmh {
                    LimitDisc(limit: limit, guess: state.limitGuess, size: 48)
                }
            }
            .frame(height: 50)
            if let radar = state.radar {
                VStack(spacing: 4) {
                    HStack {
                        Text(radar)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        if let distance = state.radarDistance {
                            Text(distance)
                                .font(.system(size: 15, weight: .bold))
                                .monospacedDigit()
                                .foregroundStyle(.white)
                        }
                    }
                    Countdown(progress: state.radarProgress ?? 0)
                }
                .frame(height: 27)
            } else {
                InfoRow(info: state.infos.first { $0.kind != .road } ?? state.fallback, primary: false)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(height: 27)
            }
            StopButton()
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
    }

    /** The road, or what the drive is (demo, tunnel). */
    private var headerRight: String {
        if demo { return "Démo · trajet simulé" }
        if state.tunnel { return "Tunnel · position estimée" }
        return state.road ?? ""
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
    static let red = Color(red: 1, green: 0x45 / 255, blue: 0x3A / 255)
    static let orange = Color(red: 1, green: 0x9F / 255, blue: 0x0A / 255)
    static let dim = Color(red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255)
    static let purple = Color(red: 0xBF / 255, green: 0x5A / 255, blue: 0xF2 / 255)
    /** iOS's label grey: secondary text is [label]. */
    static let mist = Color(red: 0xEB / 255, green: 0xEB / 255, blue: 0xF5 / 255)
    static let label = mist.opacity(0.6)
    static let background = Color.black.opacity(0.82)
    static let dangerTint = Color(red: 0.42, green: 0.04, blue: 0.03).opacity(0.92)
}

/** One line of the drive when no radar is ahead: what it says and the sign before it. */
private struct Info: Hashable {
    enum Kind { case section, radar, zone, road, quiet }

    let kind: Kind
    let text: String
    var radarColor: UInt32? = nil
}

private extension DriveActivityAttributes.ContentState {
    /** Android's order: section under way, next radar, control itinerary, road. */
    var infos: [Info] {
        var out: [Info] = []
        if let section { out.append(Info(kind: .section, text: section)) }
        if let radar {
            let text = radarDistance.map { "\(radar) · \($0)" } ?? radar
            out.append(Info(kind: .radar, text: text, radarColor: radarColor))
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

    /** The radar's kind colour (red for most). */
    var radarTint: Color { radarColor.map(rgbColor) ?? Palette.red }
}

private func rgbColor(_ rgb: UInt32) -> Color {
    Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
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
            RadarSquare(color: info.radarColor.map(rgbColor) ?? Palette.red, size: size)
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

/** "380 m": the number big, the unit small and grey. */
private struct DistanceText: View {
    let text: String
    let number: CGFloat
    let unit: CGFloat

    var body: some View {
        let parts = text.split(separator: " ", maxSplits: 1).map(String.init)
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(parts.first ?? text)
                .font(.system(size: number, weight: .heavy))
                .tracking(-0.6)
                .monospacedDigit()
                .foregroundStyle(.white)
            if parts.count > 1 {
                Text(parts[1])
                    .font(.system(size: unit, weight: .semibold))
                    .foregroundStyle(Palette.label)
            }
        }
        .lineLimit(1)
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
            Circle().strokeBorder(guess ? Palette.dim : Palette.red, lineWidth: size * 0.11)
            Text("\(limit)")
                .font(.system(size: size * (limit >= 100 ? 0.33 : 0.39), weight: .heavy))
                .foregroundStyle(Color.black)
        }
        .frame(width: size, height: size)
    }
}

/** A radar in a small rounded square of its kind's colour, a camera inside. */
private struct RadarSquare: View {
    let color: Color
    let size: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.31, style: .continuous).fill(color)
            Image(systemName: "video.fill")
                .font(.system(size: size * 0.5, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
    }
}

/** The countdown to the radar, 5 high, orange. */
private struct Countdown: View {
    let progress: Double

    var body: some View {
        let share = CGFloat(min(max(progress, 0), 1))
        Capsule()
            .fill(Color.white.opacity(0.14))
            .overlay(alignment: .leading) {
                GeometryReader { geo in
                    Capsule()
                        .fill(Palette.orange)
                        .frame(width: max(5, geo.size.width * share))
                        .opacity(share > 0 ? 1 : 0)
                }
            }
            .frame(height: 5)
    }
}

/** Zenith's logo, small: the dial open at the bottom, its red zone on the right, the hub. */
private struct LogoMark: View {
    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let line = side * 0.11
            ZStack {
                LogoArc(start: 135, end: 405)
                    .stroke(Color.white, style: StrokeStyle(lineWidth: line, lineCap: .round))
                LogoArc(start: 310, end: 405)
                    .stroke(Palette.red, style: StrokeStyle(lineWidth: line, lineCap: .round))
                Circle()
                    .fill(Color.white)
                    .frame(width: side * 0.18, height: side * 0.18)
                    .position(x: geo.size.width / 2, y: geo.size.height * 0.59)
            }
        }
    }
}

/** An arc of the logo's dial, degrees clockwise from 3 o'clock. */
private struct LogoArc: Shape {
    let start: Double
    let end: Double

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let side = min(rect.width, rect.height)
        path.addRelativeArc(
            center: CGPoint(x: rect.midX, y: rect.minY + side * 0.59),
            radius: side * 0.36,
            startAngle: .degrees(start),
            delta: .degrees(end - start)
        )
        return path
    }
}

/** "Arrêter la conduite", like the Android notification's action: stops the drive in the app's process. */
private struct StopButton: View {
    var body: some View {
        Button(intent: StopDriveIntent()) {
            Text("Arrêter la conduite")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 30)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.1)))
        }
        .buttonStyle(.plain)
    }
}
