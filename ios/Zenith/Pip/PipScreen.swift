import SwiftUI
import UIKit

/**
 * Zenith in miniature, drawn into the picture-in-picture window over Waze or Maps (PipScreen.kt).
 * The map fills the window: a vector mini-map, heading up and tilted like the drive screen's, with
 * the roads around in grey (wider for the big ones), the road ahead lit in blue, nearby radars as
 * small signs, control itineraries dashed in orange and the car's arrow lying on the road. The next
 * radar sits on top, the speed and the limit at the bottom; over the limit near a radar, a red frame
 * pulses. While a Spotify Canvas plays ([canvas]: the setting), it fills the window behind the map
 * from its first frame on, the cover's glow otherwise; the map stays whole at the top and turns
 * barely see-through toward the bottom, more when slow or stopped (Cockpit, like the drive screen);
 * an alert gives the map back and calms the Canvas. Everything scales with the window: one unit is
 * 1 % of its shorter side. Rendered offscreen for each frame (PipController): [now] drives what
 * moves between two fixes, the smoothing lives in PipCamera.
 */
struct PipScreen: View {
    let snapshot: Snapshot?
    let radars: [RadarInfo]
    var zones: [ZoneInfo] = []
    var media: MediaInfo? = nil
    var canvas: Bool = false
    /** The music's room chosen by a tap on the drive screen, if any. */
    var musicChoice: Bool? = nil
    /** The big number of the drive screen (live speed); nil: the snapshot's. */
    var speedKmh: Int? = nil
    /** Roads around the car, the map's background. */
    var roads: [RoadLine] = []
    /** The Canvas video's current frame (PipController); nil: the cover glows instead. */
    var canvasFrame: CGImage? = nil
    /** Time of the frame: the pulse, the car carried on between two fixes, the eased camera. */
    var now: Date = Date()

    var body: some View {
        GeometryReader { geo in
            window(geo.size)
        }
        .background(Color.black)
    }

    @ViewBuilder
    private func window(_ size: CGSize) -> some View {
        let unit = min(size.width, size.height) / 100
        let alert = snapshot?.alerts.first
        let danger = alert?.overLimit == true
        let speed = speedKmh ?? snapshot?.speedKmh
        let limit = snapshot?.limitKmh
        let share = Cockpit.music(speedKmh: snapshot?.speedKmh, choice: musicChoice, alerting: alert != nil || snapshot?.section != nil)
        let pose = snapshot.map { PipCamera.shared.pose($0, music: share, now: now) }
        let video = canvas ? canvasFrame : nil
        let glow = canvas && video == nil ? PipGlow.image(for: media) : nil
        let melt = blendStops(Self.melt, Self.meltSlow, pose?.music ?? share)
        // The map stays whole with nothing behind it.
        let blend = video == nil && glow == nil ? 0.0 : 1.0
        ZStack {
            Color.black
            Group {
                if let video {
                    Image(decorative: video, scale: 1)
                        .resizable()
                        .interpolation(.medium)
                        .aspectRatio(contentMode: .fill)
                } else if let glow {
                    Image(uiImage: glow)
                        .resizable()
                        .interpolation(.high)
                        .aspectRatio(contentMode: .fill)
                }
            }
            .frame(width: size.width, height: size.height)
            .clipped()
            .opacity(alert != nil ? 0.6 : 1)
            .overlay(Color.black.opacity(blend * 0.25))
            ZStack {
                Color.black
                if let snapshot, let pose {
                    PipMap(snapshot: snapshot, pose: pose, roads: roads, radars: radars, zones: zones, unit: unit)
                }
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.55), location: 0),
                        .init(color: .black.opacity(0.2), location: 0.25),
                        .init(color: .black.opacity(0.25), location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .mask {
                LinearGradient(
                    stops: melt.map { Gradient.Stop(color: .black.opacity(1 - blend * (1 - $0.opacity)), location: $0.at) },
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            // Over the map and the cover alike: the speed and the limit stay readable.
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0.45),
                    .init(color: .black.opacity(0.45), location: 0.75),
                    .init(color: .black.opacity(0.88), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            nextRadar(unit: unit, danger: danger)
            speedAndLimit(unit: unit, speed: speed, limit: limit)
            if danger {
                // Too fast near a radar: a red frame pulses around the whole window.
                RoundedRectangle(cornerRadius: unit * 4)
                    .strokeBorder(PipPalette.red, lineWidth: unit * 2.5)
                    .opacity(pulse)
            }
        }
        .frame(width: size.width, height: size.height)
    }

    /** The radar being announced, else the next one on the road: a pill at the top, red while too fast for it. */
    @ViewBuilder
    private func nextRadar(unit: CGFloat, danger: Bool) -> some View {
        let alert = snapshot?.alerts.first
        let upcoming = snapshot?.upcoming
        if let radar = alert?.radar ?? upcoming?.radar, let distance = alert?.distanceM ?? upcoming?.distanceM {
            HStack(spacing: unit * 3) {
                RadarBadge(kind: radar.kind, limitKmh: alert.map { $0.limitKmh } ?? upcoming?.limitKmh, size: unit * 14)
                Text(Self.distanceText(distance))
                    .font(.system(size: unit * 11, weight: .bold))
                    .monospacedDigit()
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                    .fixedSize()
            }
            .padding(.leading, unit * 2)
            .padding(.trailing, unit * 4)
            .padding(.vertical, unit * 2)
            .background(Capsule().fill(danger ? PipPalette.red : PipPalette.pill))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.top, unit * 5)
        }
    }

    /** Speed and limit, big, at the bottom. */
    private func speedAndLimit(unit: CGFloat, speed: Int?, limit: Int?) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: -unit * 2) {
                    Text(speed.map(String.init) ?? "--")
                        .font(.system(size: unit * 30, weight: .bold))
                        .monospacedDigit()
                        .foregroundStyle(Self.speedColor(speed, limit))
                    Text("km/h")
                        .font(.system(size: unit * 7, weight: .medium))
                        .foregroundStyle(Color.white.opacity(0.6))
                }
                .shadow(color: .black.opacity(0.9), radius: unit * 2.5)
                Spacer(minLength: unit * 4)
                if let limit {
                    PipLimitSign(limit: limit, guess: snapshot?.limitSource?.isGuess == true, size: unit * 26)
                        .padding(.bottom, unit * 3)
                }
            }
            .padding(.horizontal, unit * 6)
            .padding(.vertical, unit * 4)
        }
    }

    /** The red frame's opacity, 0.35 to 1 and back every 0.9 s. */
    private var pulse: Double {
        let phase = now.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9
        let k = phase < 0.5 ? phase * 2 : 2 - phase * 2
        return 0.35 + 0.65 * k * k * (3 - 2 * k)
    }

    /**
     * Opacity of the map once a Canvas (or the cover) is behind it: whole at the top, barely
     * see-through lower down, so it only colours the map from behind, never covering a road.
     */
    private static let melt: [FadeStop] = [(at: 0, opacity: 1), (at: 0.35, opacity: 0.92), (at: 1, opacity: 0.68)]

    /** Slow or stopped: the map still whole at the top, the Canvas clearly through it lower down. */
    private static let meltSlow: [FadeStop] = [(at: 0, opacity: 0.95), (at: 0.35, opacity: 0.6), (at: 1, opacity: 0.3)]

    /** Speed digits: white, orange just under the limit, red above it. */
    private static func speedColor(_ speed: Int?, _ limit: Int?) -> Color {
        guard let speed, let limit else { return .white }
        if speed > limit { return PipPalette.red }
        if speed >= limit - 3 { return PipPalette.orange }
        return .white
    }

    /** "850 m", "1,2 km". */
    private static func distanceText(_ m: Double) -> String {
        if m < 1_000 { return "\(Int((m / 10).rounded()) * 10) m" }
        return String(format: "%.1f km", m / 1_000).replacingOccurrences(of: ".", with: ",")
    }
}

/** Colours of the small window, the drive screen's. */
private enum PipPalette {
    static let red = Color(red: 1, green: 0x3B / 255, blue: 0x30 / 255)
    static let orange = Color(red: 1, green: 0x9F / 255, blue: 0x0A / 255)
    static let dim = Color(red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255)
    static let accent = Color(red: 0x0A / 255, green: 0x84 / 255, blue: 1)
    static let zone = Color(red: 1, green: 0x9F / 255, blue: 0x0A / 255)
    static let pill = Color(.sRGB, red: 0x16 / 255, green: 0x16 / 255, blue: 0x1A / 255, opacity: 0xE6 / 255)
}

/** Round limit sign: white disc, red ring (grey when the limit is only inferred). */
private struct PipLimitSign: View {
    let limit: Int
    let guess: Bool
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color.white)
            Circle().strokeBorder(guess ? PipPalette.dim : PipPalette.red, lineWidth: size * 0.106)
            Text("\(limit)")
                .font(.system(size: size * (limit >= 100 ? 0.327 : 0.385), weight: .bold))
                .foregroundStyle(Color.black)
        }
        .frame(width: size, height: size)
    }
}

// MARK: - Camera

/** Where the window looks from for one frame. */
private struct PipPose {
    /** The car, at its place on screen. */
    let center: GeoPoint
    /** Up on screen, degrees clockwise from north. */
    let headingDeg: Double
    /** Ground seen ahead of the car, up to the top of the window. */
    let rangeM: Double
    /** The music's share (Cockpit), eased. */
    let music: Double
}

/**
 * The window's camera between two fixes. PipScreen is rebuilt for every frame, so what eases lives
 * here: the car carried on along the road ahead at its speed (fixes come once a second, frames
 * faster), the heading turning smoothly, the view widening with the speed and to frame the next
 * radar, the music's share. Main thread.
 */
private final class PipCamera {
    static let shared = PipCamera()

    private var fixTimeMs: Int64?
    private var fixAt = Date.distantPast
    private var heading: Double?
    private var range: Double?
    private var music: Double?
    private var last = Date.distantPast

    func pose(_ s: Snapshot, music target: Double, now: Date) -> PipPose {
        let dt = now.timeIntervalSince(last)
        // First frame, or frames resumed after a pause: no easing from an old view.
        let snap = dt < 0 || dt > 3
        last = now
        if s.timeMs != fixTimeMs {
            fixTimeMs = s.timeMs
            fixAt = now
        }
        let fixHeading = s.headingDeg ?? heading ?? 0
        let carried = Double(s.speedKmh ?? 0) / 3.6 * min(max(now.timeIntervalSince(fixAt), 0), 1.2)
        let (center, bearing) = Self.along([s.position] + s.ahead, carried, heading: fixHeading)
        let wantedHeading = carried > 1 ? bearing : fixHeading
        let h = (snap ? nil : heading).map { Self.ease(angle: $0, to: wantedHeading, dt: dt, tau: 0.35) } ?? wantedHeading
        heading = h

        let kmh = Double(s.speedKmh ?? 0)
        var wantedRange = 260 + min(max((kmh - 30) / 100, 0), 1) * 440
        // Zoomed out to frame the radar being announced, or the next one on the road (up to 1.2 km:
        // the roads around only reach about 600 m, the road ahead and the radar go further).
        let framed = s.alerts.first?.distanceM ?? (s.upcoming?.roadName != nil ? s.upcoming?.distanceM : nil)
        if let d = framed, d >= 50, d <= 4_000 { wantedRange = max(wantedRange, min(d * 1.25, 1_200)) }
        let r = (snap ? nil : range).map { Self.ease($0, to: wantedRange, dt: dt, tau: 1) } ?? wantedRange
        range = r

        let m = (snap ? nil : music).map { Self.ease($0, to: target, dt: dt, tau: 0.4) } ?? target
        music = m

        return PipPose(center: center, headingDeg: h, rangeM: r, music: m)
    }

    /** The point [dist] meters along [line] and the line's bearing there; past its end, straight on. */
    private static func along(_ line: [GeoPoint], _ dist: Double, heading: Double) -> (GeoPoint, Double) {
        guard dist > 0, let first = line.first else { return (line.first ?? GeoPoint(lat: 0, lon: 0), heading) }
        var left = dist
        var bearing = heading
        var end = first
        for (a, b) in zip(line, line.dropFirst()) {
            let d = meters(a, b)
            end = b
            if d < 0.5 { continue }
            bearing = bearingDeg(a, b)
            if left <= d {
                let k = left / d
                return (GeoPoint(lat: a.lat + (b.lat - a.lat) * k, lon: a.lon + (b.lon - a.lon) * k), bearing)
            }
            left -= d
        }
        let rad = bearing * .pi / 180
        let lat = end.lat + left * cos(rad) / 110_540
        let lon = end.lon + left * sin(rad) / (111_320 * cos(end.lat * .pi / 180))
        return (GeoPoint(lat: lat, lon: lon), bearing)
    }

    private static func meters(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let e = (b.lon - a.lon) * 111_320 * cos(a.lat * .pi / 180)
        let n = (b.lat - a.lat) * 110_540
        return (e * e + n * n).squareRoot()
    }

    private static func bearingDeg(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let e = (b.lon - a.lon) * 111_320 * cos(a.lat * .pi / 180)
        let n = (b.lat - a.lat) * 110_540
        let deg = atan2(e, n) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }

    /** Exponential easing over [dt] seconds, time constant [tau]. */
    private static func ease(_ value: Double, to target: Double, dt: Double, tau: Double) -> Double {
        value + (target - value) * (1 - exp(-dt / tau))
    }

    /** Same for an angle, by the shorter way round. */
    private static func ease(angle: Double, to target: Double, dt: Double, tau: Double) -> Double {
        let diff = (target - angle + 540).truncatingRemainder(dividingBy: 360) - 180
        let out = (angle + diff * (1 - exp(-dt / tau))).truncatingRemainder(dividingBy: 360)
        return out < 0 ? out + 360 : out
    }
}

/** The music's cover shrunk to a few pixels: stretched over the window, a soft glow of its colours. */
private enum PipGlow {
    private static var source: UIImage?
    private static var glow: UIImage?

    static func image(for media: MediaInfo?) -> UIImage? {
        guard let art = media?.art else { return nil }
        if art === source, let glow { return glow }
        let side: CGFloat = 12
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let small = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            art.draw(in: CGRect(x: 0, y: 0, width: side, height: side))
        }
        source = art
        glow = small
        return small
    }
}

// MARK: - Map

/**
 * Ground seen from behind and above the car: a perspective that keeps straight lines straight
 * (meters ahead shrink with depth), the car at 60 % of the height, the far end of [PipPose.rangeM]
 * just under the top.
 */
private struct PipProjection {
    /** Vertical squash of the ground, as seen from about 40°. */
    static let squash = 0.78

    let center: GeoPoint
    let sinH: Double
    let cosH: Double
    let metersPerLon: Double
    let car: CGPoint
    /** Points per meter at the car. */
    let k: Double
    /** Depth of the perspective, meters. */
    let depth: Double
    /** Nothing further behind than this is drawn (it would blow up), meters. */
    let near: Double

    init(pose: PipPose, size: CGSize, unit: CGFloat) {
        center = pose.center
        let h = pose.headingDeg * .pi / 180
        sinH = sin(h)
        cosH = cos(h)
        metersPerLon = 111_320 * cos(pose.center.lat * .pi / 180)
        let carY = Double(size.height) * 0.6
        let d = pose.rangeM * 1.4
        car = CGPoint(x: Double(size.width) / 2, y: carY)
        depth = d
        k = max(carY - Double(unit) * 6, 1) * (1 + pose.rangeM / d) / (pose.rangeM * Self.squash)
        near = -0.6 * d
    }

    /** Meters in the car's frame: x to the right, y ahead. */
    func local(_ p: GeoPoint) -> SIMD2<Double> {
        local(east: (p.lon - center.lon) * metersPerLon, north: (p.lat - center.lat) * 110_540)
    }

    func local(east e: Double, north n: Double) -> SIMD2<Double> {
        SIMD2(e * cosH - n * sinH, e * sinH + n * cosH)
    }

    /** Size factor at this depth: 1 at the car, smaller ahead, larger behind. */
    func scale(_ q: SIMD2<Double>) -> Double { 1 / (1 + q.y / depth) }

    func screen(_ q: SIMD2<Double>) -> CGPoint {
        let s = scale(q)
        return CGPoint(x: Double(car.x) + q.x * k * s, y: Double(car.y) - q.y * k * Self.squash * s)
    }

    /** A line in the car's frame, cut where it passes too far behind: the stretches left to draw. */
    func clip(_ pts: [SIMD2<Double>]) -> [[SIMD2<Double>]] {
        var runs: [[SIMD2<Double>]] = []
        var run: [SIMD2<Double>] = []
        var previous: SIMD2<Double>?
        for b in pts {
            defer { previous = b }
            guard let a = previous else {
                if b.y >= near { run.append(b) }
                continue
            }
            switch (a.y >= near, b.y >= near) {
            case (true, true):
                run.append(b)
            case (true, false):
                run.append(cut(a, b))
                runs.append(run)
                run = []
            case (false, true):
                run = [cut(a, b), b]
            case (false, false):
                break
            }
        }
        runs.append(run)
        return runs.filter { $0.count > 1 }
    }

    func path(_ runs: [[SIMD2<Double>]]) -> Path {
        var path = Path()
        for run in runs {
            guard let first = run.first else { continue }
            path.move(to: screen(first))
            for q in run.dropFirst() { path.addLine(to: screen(q)) }
        }
        return path
    }

    private func cut(_ a: SIMD2<Double>, _ b: SIMD2<Double>) -> SIMD2<Double> {
        a + (b - a) * ((near - a.y) / (b.y - a.y))
    }
}

/** Road classes of the map, drawn from the smallest up: grey and width of the drive screen's map. */
private enum PipRoad: Int, CaseIterable {
    case minor, major, motorway

    init(_ roadClass: String?) {
        switch roadClass ?? "" {
        case "motorway", "trunk", "motorway_link", "trunk_link": self = .motorway
        case "primary", "secondary", "tertiary", "primary_link", "secondary_link", "tertiary_link": self = .major
        default: self = .minor
        }
    }

    var color: Color {
        switch self {
        case .minor: return Color(red: 0x24 / 255, green: 0x24 / 255, blue: 0x2A / 255)
        case .major: return Color(red: 0x34 / 255, green: 0x34 / 255, blue: 0x3C / 255)
        case .motorway: return Color(red: 0x45 / 255, green: 0x45 / 255, blue: 0x4F / 255)
        }
    }

    /** Units. */
    var width: CGFloat {
        switch self {
        case .minor: return 0.8
        case .major: return 1.3
        case .motorway: return 1.9
        }
    }
}

/** The mini-map, drawn with vectors (MapLibre cannot draw offscreen or in the background). */
private struct PipMap: View {
    let snapshot: Snapshot
    let pose: PipPose
    let roads: [RoadLine]
    let radars: [RadarInfo]
    let zones: [ZoneInfo]
    let unit: CGFloat

    var body: some View {
        Canvas { ctx, size in
            let p = PipProjection(pose: pose, size: size, unit: unit)
            drawRoads(&ctx, p, size)
            drawZones(&ctx, p)
            drawAhead(&ctx, p)
            drawRadars(&ctx, p, size)
            drawCar(&ctx, p)
        }
    }

    /**
     * The roads around, in the drive screen's map greys, wider for the big ones and thinner zoomed
     * out; they fade out before the edge of what is loaded (about 600 m).
     */
    private func drawRoads(_ ctx: inout GraphicsContext, _ p: PipProjection, _ size: CGSize) {
        guard !roads.isEmpty else { return }
        var paths = [Path](repeating: Path(), count: PipRoad.allCases.count)
        for road in roads {
            let kind = PipRoad(road.roadClass)
            paths[kind.rawValue].addPath(p.path(p.clip(road.points.map(p.local))))
        }
        let fadeFrom = p.screen(SIMD2(0, 380)).y
        let fadeTo = p.screen(SIMD2(0, 600)).y
        let zoom = CGFloat(min(max((400 / pose.rangeM).squareRoot(), 0.6), 1.15))
        for kind in PipRoad.allCases {
            let fade = Gradient(stops: [
                .init(color: kind.color.opacity(0), location: 0),
                .init(color: kind.color, location: 1),
            ])
            ctx.stroke(
                paths[kind.rawValue],
                with: .linearGradient(fade, startPoint: CGPoint(x: 0, y: fadeTo), endPoint: CGPoint(x: 0, y: max(fadeFrom, fadeTo + 1))),
                style: StrokeStyle(lineWidth: unit * kind.width * zoom, lineCap: .round, lineJoin: .round)
            )
        }
    }

    /** Control itineraries: dashed orange, brighter for the one being driven. */
    private func drawZones(_ ctx: inout GraphicsContext, _ p: PipProjection) {
        let style = StrokeStyle(lineWidth: unit * 1.1, lineCap: .butt, lineJoin: .round, dash: [unit * 2.2, unit * 1.6])
        for zone in zones {
            let path = p.path(p.clip(zone.points.map(p.local)))
            let driven = zone.id == snapshot.zone?.id
            ctx.stroke(path, with: .color(PipPalette.zone.opacity(driven ? 0.85 : 0.55)), style: style)
        }
    }

    /** The road the driver most likely follows: a soft glow, then the line, thinner far away. */
    private func drawAhead(_ ctx: inout GraphicsContext, _ p: PipProjection) {
        let runs = p.clip(([snapshot.position] + snapshot.ahead).map(p.local))
        guard !runs.isEmpty else { return }
        ctx.stroke(
            p.path(runs),
            with: .color(PipPalette.accent.opacity(0.16)),
            style: StrokeStyle(lineWidth: unit * 4.5, lineCap: .round, lineJoin: .round)
        )
        var line = ctx
        line.opacity = 0.88
        // One layer: the round ends of the pieces never double up where they meet.
        line.drawLayer { layer in
            for run in runs {
                for (a, b) in zip(run, run.dropFirst()) {
                    let s = min(max(p.scale((a + b) / 2), 0.45), 1.5)
                    var piece = Path()
                    piece.move(to: p.screen(a))
                    piece.addLine(to: p.screen(b))
                    layer.stroke(piece, with: .color(PipPalette.accent), style: StrokeStyle(lineWidth: unit * 1.4 * s, lineCap: .round))
                }
            }
        }
    }

    /** Radars as small signs, far ones first; the one announced or next on the road larger. */
    private func drawRadars(_ ctx: inout GraphicsContext, _ p: PipProjection, _ size: CGSize) {
        let active = Set(snapshot.alerts.map(\.radar.id) + [snapshot.upcoming?.radar.id].compactMap { $0 })
        let margin = unit * 10
        let shown = radars
            .map { ($0, p.local($0.position)) }
            .filter { $0.1.y > p.near * 0.8 }
            .sorted { $0.1.y > $1.1.y }
        for (radar, q) in shown {
            let at = p.screen(q)
            guard at.x > -margin, at.x < size.width + margin, at.y > -margin, at.y < size.height + margin else { continue }
            let s = CGFloat(min(max(p.scale(q), 0.55), 1.25))
            let r = unit * (active.contains(radar.id) ? 4.6 : 3.4) * s
            let color = radarColor(radar.kind)
            let halo = r + unit * 1.6 * s
            ctx.fill(Path(ellipseIn: CGRect(x: at.x - halo, y: at.y - halo, width: halo * 2, height: halo * 2)), with: .color(color.opacity(0.25)))
            let disc = Path(ellipseIn: CGRect(x: at.x - r, y: at.y - r, width: r * 2, height: r * 2))
            ctx.fill(disc, with: .color(.white))
            let ring = r * 0.24
            ctx.stroke(
                Path(ellipseIn: CGRect(x: at.x - r + ring / 2, y: at.y - r + ring / 2, width: r * 2 - ring, height: r * 2 - ring)),
                with: .color(color),
                lineWidth: ring
            )
            if let limit = radar.limitKmh {
                let text = Text("\(limit)")
                    .font(.system(size: r * (limit >= 100 ? 0.78 : 0.92), weight: .bold))
                    .foregroundStyle(Color.black)
                ctx.draw(text, at: at, anchor: .center)
            }
        }
    }

    /** The car: a blue arrow lying flat on the road, pointing up; paler while estimated (tunnel). */
    private func drawCar(_ ctx: inout GraphicsContext, _ p: PipProjection) {
        let c = p.car
        let glow = unit * 9
        ctx.fill(
            Path(ellipseIn: CGRect(x: c.x - glow, y: c.y - glow, width: glow * 2, height: glow * 2)),
            with: .radialGradient(
                Gradient(colors: [PipPalette.accent.opacity(0.35), PipPalette.accent.opacity(0)]),
                center: c,
                startRadius: 0,
                endRadius: glow
            )
        )
        let a = unit * 10
        let squash = CGFloat(PipProjection.squash)
        func at(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: c.x + x * a, y: c.y + y * a * squash) }
        var arrow = Path()
        arrow.move(to: at(0, -0.38))
        arrow.addLine(to: at(0.32, 0.36))
        arrow.addLine(to: at(0, 0.18))
        arrow.addLine(to: at(-0.32, 0.36))
        arrow.closeSubpath()
        var car = ctx
        car.opacity = snapshot.estimated ? 0.55 : 1
        car.fill(arrow, with: .color(PipPalette.accent))
        car.stroke(arrow, with: .color(.white), style: StrokeStyle(lineWidth: unit * 0.7, lineJoin: .round))
    }
}
