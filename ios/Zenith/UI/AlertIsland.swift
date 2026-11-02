import SwiftUI
import UIKit

// Port of the Android alert island (app/, AlertIsland.kt beside DriveScreen.kt): the radars are no
// longer cards in the drive screen, a black island grows out of the camera and says what matters now. Its rules (which state,
// the countdown, "18 km/h au-dessus") come from the core (core/Island.kt) through Cockpit.

extension DriveScreen {
    /**
     * The alert island, at the top of the drive screen: a radar ahead (type, distance, countdown,
     * limit), "Ralentis" when too fast for it, the average of a section, a short "Radar passé".
     *
     * On an iPhone with a Dynamic Island it rests as a black pill exactly over the hardware island
     * (so it cannot be seen) and grows out of it: iOS hides an app's own Live Activity from the
     * Dynamic Island while the app is in front, so the drive screen draws it there itself. On an
     * iPhone with a notch (or none) and across, it sits just under the top of the safe area and is
     * hidden at rest. The shape springs to each size; its content fades in just after the shape
     * starts growing and fades out first when it shrinks.
     */
    struct AlertIsland: View {
        let snapshot: Snapshot?
        /** The big number's speed. */
        let speed: Int?
        /** Countdown of the radar announced, 0 at first sight to 1 on it. */
        let progress: Double
        /** Since the last radar (or section) passed, -1 when none lately. */
        let passedAgoMs: Int64
        /** That radar would have flashed. */
        let passFlash: Bool
        /** The whole screen's width, safe area included. */
        let screenWidth: CGFloat
        let safeTop: CGFloat
        let landscape: Bool

        /** The hardware Dynamic Island, from the top of the screen. */
        private static let hardware = CGSize(width: 126, height: 37)
        private static let hardwareTop: CGFloat = 11

        var body: some View {
            let alert = snapshot?.alerts.first
            let mode = Cockpit.islandMode(
                alert: alert != nil,
                overLimit: alert?.overLimit == true,
                section: snapshot?.section != nil,
                passedAgoMs: passedAgoMs
            )
            let island = dynamicIsland
            let look = shape(mode, island: island)
            let outline = RoundedRectangle(cornerRadius: look.radius, style: .continuous)
            ZStack(alignment: .top) {
                content(mode, alert: alert, island: island, size: look.size)
            }
            .frame(width: look.size.width, height: look.size.height, alignment: .top)
            .background(outline.fill(Color.black))
            .clipShape(outline)
            .opacity(look.alpha)
            .animation(Moves.spring(0.75, 400), value: mode)
            // VoiceOver reads what the island says, and hears it when it changes.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken(mode, alert: alert))
            .accessibilityAddTraits(.updatesFrequently)
            .accessibilityHidden(mode == .idle)
            .padding(.top, island ? Self.hardwareTop : safeTop + 6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .onChange(of: mode) { _, now in
                guard now != .idle, UIAccessibility.isVoiceOverRunning else { return }
                UIAccessibility.post(notification: .announcement, argument: spoken(now, alert: snapshot?.alerts.first))
            }
        }

        /** Upright on an iPhone with a Dynamic Island (its safe area is that much taller). */
        private var dynamicIsland: Bool {
            !landscape && safeTop >= 54 && UIDevice.current.userInterfaceIdiom == .phone
        }

        /** Size, corner and opacity of the island in [mode]. */
        private func shape(_ mode: IslandMode, island: Bool) -> (size: CGSize, radius: CGFloat, alpha: Double) {
            let wide = max(0, min(screenWidth - 24, landscape ? 420 : 370))
            switch mode {
            case .idle:
                if island { return (Self.hardware, 19, 1) }
                return (CGSize(width: 30, height: 30), 15, 0)
            case .approach:
                return (CGSize(width: wide, height: 88), 36, 1)
            case .slowDown:
                return (CGSize(width: wide, height: 136), 40, 1)
            case .section:
                return (CGSize(width: wide, height: 110), 38, 1)
            case .passed:
                // Around the camera the words need room on both sides of it.
                return (CGSize(width: island ? min(wide, 346) : 230, height: 37), 19, 1)
            }
        }

        // MARK: Content

        @ViewBuilder
        private func content(_ mode: IslandMode, alert: AlertInfo?, island: Bool, size: CGSize) -> some View {
            switch mode {
            case .idle:
                EmptyView()
            case .approach:
                if let alert {
                    approach(alert, island: island, width: size.width)
                        .frame(width: size.width, height: size.height, alignment: .top)
                        .transition(Self.contentFade)
                }
            case .slowDown:
                if let alert {
                    slowDown(alert, island: island, width: size.width)
                        .frame(width: size.width, height: size.height, alignment: .top)
                        .transition(Self.contentFade)
                }
            case .section:
                if let section = snapshot?.section {
                    sectionView(section, island: island, width: size.width)
                        .frame(width: size.width, height: size.height, alignment: .top)
                        .transition(Self.contentFade)
                }
            case .passed:
                passed(island: island)
                    .frame(width: size.width, height: size.height)
                    .transition(Self.contentFade)
            }
        }

        /** In just after the shape starts growing; out at once, before it shrinks. */
        private static let contentFade = AnyTransition.asymmetric(
            insertion: AnyTransition.opacity.animation(.easeOut(duration: 0.2).delay(0.12)),
            removal: AnyTransition.opacity.animation(.easeOut(duration: 0.1))
        )

        /** A radar ahead: what and how far, then where, the countdown and its limit. */
        private func approach(_ alert: AlertInfo, island: Bool, width: CGFloat) -> some View {
            VStack(spacing: 0) {
                radarRow(alert, island: island, width: width, inset: 18)
                    .frame(height: 46)
                HStack(spacing: 12) {
                    if let place = detail(alert) {
                        Text(verbatim: place)
                            .font(.system(size: 13))
                            .foregroundStyle(Ink.label)
                            .lineLimit(1)
                    }
                    IslandBar(progress: progress, color: Ink.orange)
                        .frame(minWidth: 40)
                    if let limit = alert.limitKmh {
                        LimitSign(limit: limit, uncertain: false, size: 28)
                    }
                }
                .frame(height: 28)
                .padding(.top, 6)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18)
        }

        /** Too fast for the radar ahead: "Ralentis" in big red, by how much, the limit with a red halo. */
        private func slowDown(_ alert: AlertInfo, island: Bool, width: CGFloat) -> some View {
            VStack(spacing: 0) {
                radarRow(alert, island: island, width: width, inset: 20)
                    .frame(height: 46)
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: "Ralentis")
                            .font(.system(size: 32, weight: .heavy))
                            .tracking(-1)
                            .foregroundStyle(Ink.red)
                            .lineLimit(1)
                        if let over = Cockpit.islandOverText(speedKmh: speed, limitKmh: alert.limitKmh) {
                            Text(verbatim: over)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(Ink.paleRed.opacity(0.8))
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    if let limit = alert.limitKmh {
                        LimitSign(limit: limit, uncertain: false, size: 52)
                            .background {
                                Circle().fill(Ink.red.opacity(0.25)).frame(width: 62, height: 62)
                            }
                    }
                }
                .frame(height: 56)
                .padding(.top, 4)
                IslandBar(progress: progress, color: Ink.red)
                    .padding(.top, 12)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
        }

        /** An average-speed section: how far still, the average (red when it must come down), what to do. */
        private func sectionView(_ section: SectionInfo, island: Bool, width: CGFloat) -> some View {
            let tooFast = section.lost || section.targetKmh < section.limitKmh
            let done = section.lengthM > 0 ? min(max(1 - section.remainingM / section.lengthM, 0), 1) : 0
            let advice = section.lost ? "Moyenne trop haute : elle finira au-dessus de \(section.limitKmh)"
                : tooFast ? "Pas plus de \(section.targetKmh) jusqu'au bout"
                : "Moyenne bonne : roule jusqu'à \(section.limitKmh)"
            let side = sideWidth(island: island, width: width, inset: 18)
            return VStack(spacing: 0) {
                HStack(spacing: 0) {
                    HStack(spacing: 9) {
                        IslandRadarIcon(kind: .sectionStart, size: 26)
                        Text(verbatim: "Tronçon")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Color.white)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: side, alignment: .leading)
                    Spacer(minLength: 8)
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(verbatim: "encore")
                            .font(.system(size: 13))
                            .foregroundStyle(Ink.label)
                        IslandDistance(metres: section.remainingM, number: 18, unit: 13)
                    }
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: side, alignment: .trailing)
                }
                .frame(height: 46)
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(verbatim: "\(Int(section.averageKmh.rounded()))")
                                .font(.system(size: 30, weight: .bold).monospacedDigit())
                                .foregroundStyle(tooFast ? Ink.red : Color.white)
                            Text(verbatim: "km/h")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Ink.label)
                        }
                        Text(verbatim: advice)
                            .font(.system(size: 13))
                            .foregroundStyle(Ink.label)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    LimitSign(limit: section.limitKmh, uncertain: false, size: 44)
                }
                .frame(height: 50)
                Spacer(minLength: 0)
                IslandBar(progress: done, color: Color.white, height: 3)
                    .padding(.bottom, 9)
            }
            .padding(.horizontal, 18)
        }

        /** The small "Radar passé" (red "Trop vite au radar" if it would have flashed), around the camera on a Dynamic Island. */
        @ViewBuilder
        private func passed(island: Bool) -> some View {
            let color = passFlash ? Ink.red : Ink.green
            let badge = ZStack {
                Circle().fill(color).frame(width: 22, height: 22)
                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .heavy))
                    .foregroundStyle(Color.black)
            }
            if island {
                // The camera sits in the middle: the words go round it.
                let words = passFlash ? ("Trop vite", "au radar") : ("Radar", "passé")
                HStack(spacing: 0) {
                    HStack(spacing: 8) {
                        badge
                        Text(verbatim: words.0).lineLimit(1).minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Color.clear.frame(width: Self.hardware.width, height: 1)
                    Text(verbatim: words.1)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(color)
                .padding(.horizontal, 12)
            } else {
                HStack(spacing: 8) {
                    badge
                    Spacer(minLength: 0)
                    Text(verbatim: passFlash ? "Trop vite au radar" : "Radar passé")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(color)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .padding(.leading, 8)
                .padding(.trailing, 16)
            }
        }

        // MARK: Pieces

        /** The radar's sign and type on the left, its distance on the right; clear of the camera on a Dynamic Island. */
        private func radarRow(_ alert: AlertInfo, island: Bool, width: CGFloat, inset: CGFloat) -> some View {
            let side = sideWidth(island: island, width: width, inset: inset)
            return HStack(spacing: 0) {
                HStack(spacing: 9) {
                    IslandRadarIcon(kind: alert.radar.kind, size: 26)
                    Text(verbatim: alert.radar.label)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .frame(maxWidth: side, alignment: .leading)
                Spacer(minLength: 8)
                IslandDistance(metres: alert.distanceM, number: 20, unit: 13)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: side, alignment: .trailing)
            }
        }

        /** Room on each side of the camera, inside the island's padding; unlimited without a Dynamic Island. */
        private func sideWidth(island: Bool, width: CGFloat, inset: CGFloat) -> CGFloat {
            island ? max(60, (width - Self.hardware.width) / 2 - inset - 6) : .infinity
        }

        /** Where the radar is: the age of a mobile report, the road, else whether its direction is unknown. */
        private func detail(_ alert: AlertInfo) -> String? {
            if let age = alert.ageMs { return "signalé il y a \(max(1, age / 60_000)) min" }
            if let road = snapshot?.roadName, !road.isEmpty { return "sur \(road)" }
            if alert.radar.directionDeg == nil { return "sens non vérifié" }
            return nil
        }

        /** What VoiceOver says for [mode]. */
        private func spoken(_ mode: IslandMode, alert: AlertInfo?) -> String {
            switch mode {
            case .idle:
                return ""
            case .approach:
                guard let alert else { return "" }
                return "\(alert.radar.label) dans \(Words.distance(alert.distanceM))"
            case .slowDown:
                guard let alert else { return "" }
                let over = Cockpit.islandOverText(speedKmh: speed, limitKmh: alert.limitKmh).map { ", \($0)" } ?? ""
                return "Ralentis\(over), \(alert.radar.label) dans \(Words.distance(alert.distanceM))"
            case .section:
                guard let section = snapshot?.section else { return "" }
                return "Tronçon, moyenne \(Int(section.averageKmh.rounded())) kilomètres-heure, limite \(section.limitKmh), " +
                    "encore \(Words.distance(section.remainingM))"
            case .passed:
                return passFlash ? "Trop vite au radar" : "Radar passé"
            }
        }
    }
}

/** The radar's kind in a small rounded square of its colour, a white glyph inside. */
private struct IslandRadarIcon: View {
    let kind: RadarKind
    let size: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.31, style: .continuous)
                .fill(radarColor(kind))
            Image(systemName: symbol)
                .font(.system(size: size * 0.5, weight: .bold))
                .foregroundStyle(Color.white)
        }
        .frame(width: size, height: size)
    }

    private var symbol: String {
        switch kind {
        case .sectionStart, .sectionEnd: return "gauge.with.needle"
        default: return "video.fill"
        }
    }
}

/** "380 m": the number big, the unit small and grey. */
private struct IslandDistance: View {
    let metres: Double
    let number: CGFloat
    let unit: CGFloat

    var body: some View {
        let parts = DriveScreen.Words.distanceParts(metres)
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(verbatim: parts.0)
                .font(.system(size: number, weight: .bold).monospacedDigit())
                .tracking(-0.5)
                .foregroundStyle(Color.white)
            Text(verbatim: parts.1)
                .font(.system(size: unit, weight: .semibold))
                .foregroundStyle(DriveScreen.Ink.label)
        }
    }
}

/** A thin countdown bar, 5 high: the radar (or the section's end) nearing. */
private struct IslandBar: View {
    let progress: Double
    let color: Color
    var height: CGFloat = 5

    var body: some View {
        let share = CGFloat(min(max(progress, 0), 1))
        Capsule()
            .fill(Color.white.opacity(0.14))
            .overlay(alignment: .leading) {
                GeometryReader { geo in
                    Capsule()
                        .fill(color)
                        .frame(width: max(height, geo.size.width * share))
                        .opacity(share > 0 ? 1 : 0)
                }
            }
            .frame(height: height)
            .animation(.linear(duration: 0.3), value: share)
    }
}
