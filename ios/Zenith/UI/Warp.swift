import SwiftUI

/** Where the start of a drive stands: stars drifting at home, the tunnel while the GPS searches, the jump. */
enum FlightPhase { case idle, search, arrive }

/** The two moments whose pictures follow their sound: the arrival and the way back. */
enum FlightSound { case arrive, homecoming }

/** Height of the tunnel's heart, as a share of the screen: the globe, the flash and the portal sit there. */
let HEART_Y: CGFloat = 0.42

/** Radius of the globe at the heart while the GPS searches, as a share of the screen's smaller side. */
let GLOBE_SHARE: CGFloat = 0.15

/**
 * Connected: just a beat (the lock-on ring, "Position verrouillée") before the jump. The calm
 * belongs to the search; once the position is there, everything goes fast.
 */
let HOLD_MS = 250

/** The dive, from the first frame of the rush to the last star; the flash comes at [LANDING] of it. */
let DIVE_MS = 1_300
let LANDING: Double = 0.66

/** From the start of the arrival (position locked) to the flash: the boom, the map, the music back. */
let JUMP_IMPACT_MS = HOLD_MS + Int(Double(DIVE_MS) * LANDING)

/** The way back: the scene steps back and fades into the stars over [CLOSE_MS]... */
let CLOSE_MS = 500

/** ...the Earth, behind it, drifts away to its size in the tunnel... */
let RECEDE_MS = 1_300

/** ...then fades into the calm sky of home. */
let HOMECOMING_MS = 2_000

/**
 * Space behind the start of a drive, drawn frame by frame. At home, a calm sky drifts by. Once
 * started, a tube of stars forms around the viewer and rushes past, faster as the search goes on
 * and as satellites lock, rings of light marking its walls; at its end the Earth ([Globe]) turns,
 * its satellites locking one by one onto [home] (the driver's position; France until known).
 *
 * [FlightPhase.arrive]: a beat ([HOLD_MS]) as the position locks with a ring of light ([onDive] at
 * its end), then the jump, fast: the stars stretch into streaks and the Earth rushes at the viewer,
 * swinging home dead center on the way, until it fills the screen; a white flash and the map opens
 * beneath it ([onLanded]), then the stars fade out ([onGone]).
 *
 * [comingHome]: a drive just ended. Behind the scene fading away, the Earth, home in front, drifts
 * back from the viewer and fades into the stars, which slow down to the calm of home. Soft, short,
 * nothing crossing the screen.
 *
 * Both follow their sound ([heardAt]: when it is first heard, output latency included), so the boom
 * lands on the flash. Transparent around the stars: the screen's black is behind it. Give it the
 * whole screen, the same frame as the caption under the globe.
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

    @StateObject private var scene = FlightScene()

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { ctx, size in
                scene.advance(timeline.date.timeIntervalSinceReferenceDate, self)
                scene.draw(ctx, size: size, gps: gps)
            }
        }
        .allowsHitTesting(false)
    }
}

/**
 * The flight's moving parts and clocks, kept from frame to frame. Never observed: the timeline
 * redraws every frame, nothing re-renders on it.
 */
private final class FlightScene: ObservableObject {
    private let sky = FlightStars(count: 380, tube: false)
    private let tube = FlightStars(count: 900, tube: true)
    private let globe = Globe()
    private var started = false
    private var lock = FlightTween(0, seconds: 0.9)
    /** The tube forms around the viewer at Start. */
    private var tunnel = FlightTween(0, seconds: 1.8)
    private var orbit = FlightTween(0, seconds: 0.9)
    private var lockNow = 0.0
    private var tunnelNow = 0.0
    private var orbitNow = 0.0
    private var home: GeoPoint?
    private var comingHome = false
    /** Time since the arrival and since the way back began, in ms; -1 while not under way. */
    private var arriveMs = -1.0
    private var backMs = -1.0
    private var searchingSince: Double?
    private var arriveFrom: Double?
    private var backFrom: Double?
    /** Callbacks given so far in this arrival: dive, landed, gone. */
    private var sent = 0
    private var epoch = 0.0
    private var last: Double?
    /** Seconds since the first frame: the twinkle, the orbits and the rings run on it. */
    private var t = 0.0
    private var speed = 0.035

    /** Moves everything to [now] (seconds) for [view]'s phase, satellites and home. */
    func advance(_ now: Double, _ view: Starfield) {
        let lockGoal = min(Double(view.gps.used) / 4, 1)
        let tunnelGoal = view.phase == .idle ? 0.0 : 1.0
        let orbitGoal = view.phase == .search ? 1.0 : 0.0
        if !started {
            started = true
            epoch = now
            lock = FlightTween(lockGoal, seconds: 0.9)
            tunnel = FlightTween(tunnelGoal, seconds: 1.8)
            orbit = FlightTween(orbitGoal, seconds: 0.9)
            home = view.home
            if let h = view.home { globe.aimAt(lat: h.lat, lon: h.lon) }
            comingHome = view.comingHome
            if comingHome {
                globe.face()
                backMs = 0
            }
        } else {
            lock.aim(lockGoal, at: now)
            tunnel.aim(tunnelGoal, at: now)
            orbit.aim(orbitGoal, at: now)
            if view.home != home {
                home = view.home
                if let h = view.home { globe.aimAt(lat: h.lat, lon: h.lon) }
            }
            if view.comingHome != comingHome {
                comingHome = view.comingHome
                // A drive ended in the last instants of the jump, this sky still up: the way back from here.
                if comingHome && backMs < 0 {
                    globe.face()
                    backMs = 0
                }
            }
        }
        lockNow = lock.value(at: now)
        tunnelNow = tunnel.value(at: now)
        orbitNow = orbit.value(at: now)

        if view.phase == .search && searchingSince == nil { searchingSince = now }
        if view.phase == .idle { searchingSince = nil }
        // The arrival's clock: from its sound once heard, never going back.
        if view.phase == .arrive {
            let from = arriveFrom ?? now
            arriveFrom = from
            var anchor = from
            if let heard = view.heardAt(.arrive)?.timeIntervalSinceReferenceDate, abs(heard - from) < 0.7 { anchor = heard }
            arriveMs = max(arriveMs, max(0, (now - anchor) * 1_000))
            if sent == 0 && arriveMs >= Double(HOLD_MS) {
                sent = 1
                FlightScene.call(view.onDive)
            }
            if sent == 1 && arriveMs >= Double(JUMP_IMPACT_MS) {
                sent = 2
                FlightScene.call(view.onLanded)
            }
            if sent == 2 && arriveMs >= Double(HOLD_MS + DIVE_MS) {
                sent = 3
                FlightScene.call(view.onGone)
            }
        } else {
            arriveFrom = nil
            arriveMs = -1
            sent = 0
        }
        if backMs >= 0 {
            let from = backFrom ?? now
            backFrom = from
            var anchor = from
            if let heard = view.heardAt(.homecoming)?.timeIntervalSinceReferenceDate, abs(heard - from) < 0.7 { anchor = heard }
            backMs = max(backMs, max(0, (now - anchor) * 1_000))
            if backMs >= Double(HOMECOMING_MS) { backMs = -1 }
        } else {
            backFrom = nil
        }
        let searched = searchingSince.map { now - $0 } ?? 0
        // Progressive: from a cruise to a race over the first seconds, a little more per satellite.
        let race = 0.9 * min(searched / 8, 1) + 0.06 * Double(min(view.gps.used, 8))
        let cruise = 0.035 + tunnelNow * (0.45 + race)
        let dive = FlightJump(ms: arriveMs).dive
        // The rush, from the very start of the dive.
        speed = cruise + 18 * dive * dive + FlightBack(ms: backMs).speed
        if let last {
            let dt = min(now - last, 0.05)
            sky.step(dt: dt, speed: speed * 0.35 + 0.02)
            tube.step(dt: dt, speed: speed)
            let arriving = view.phase == .arrive
            globe.step(dt: dt, lock: lockNow, aim: arriving || backMs >= 0 || lockNow >= 0.98, firm: arriving)
        }
        last = now
        t = now - epoch
    }

    func draw(_ ctx: GraphicsContext, size: CGSize, gps: GpsInfo) {
        let w = Double(size.width), h = Double(size.height)
        let cx = w / 2
        let cy = h * Double(HEART_Y)
        let scale = min(w, h) * 0.42
        let base = min(w, h) * Double(GLOBE_SHARE)
        let a = FlightJump(ms: arriveMs)
        let b = FlightBack(ms: backMs)
        let full = CGRect(origin: .zero, size: size)
        // After the flash, the stars give way to the map.
        let fade = a.dive <= LANDING ? 1 : min(max(1 - (a.dive - LANDING) / (1 - LANDING), 0), 1)
        sky.draw(ctx, size: size, cx: cx, cy: cy, scale: scale, speed: speed * 0.35, t: t, fade: fade * (1 - 0.5 * max(tunnelNow, b.tube)), twinkle: true)
        if tunnelNow > 0.01 {
            tubeRings(ctx, cx: cx, cy: cy, scale: scale, alpha: tunnelNow * fade)
            tube.draw(ctx, size: size, cx: cx, cy: cy, scale: scale, speed: speed, t: t, fade: fade * tunnelNow, twinkle: false)
            // The Earth ahead: out of the distance as the tube forms, a little nearer as satellites
            // lock, then rushing at the viewer until it fills the screen at the flash.
            let rush = min(a.dive / LANDING, 1)
            let r = base * (0.2 + 0.8 * tunnelNow) * (0.92 + 0.08 * lockNow) * (1 + 14 * rush * rush)
            let flare = arriveMs < 0 ? 0 : min(max(arriveMs / 600, 0), 1)
            if backMs < 0 {
                globe.draw(
                    ctx, cx: cx, cy: cy, r: r, alpha: tunnelNow * fade, orbits: orbitNow * fade, t: t,
                    visible: gps.visible, used: gps.used, lock: arriveMs >= 0 ? 1 : lockNow, flare: flare
                )
            }
        }
        if backMs >= 0 {
            // Back from a drive: the tunnel's walls drift backwards, the Earth drifts away from the
            // viewer, home in front, and fades into the stars; a soft glow at the heart while it recedes.
            tube.draw(ctx, size: size, cx: cx, cy: cy, scale: scale, speed: speed, t: t, fade: b.tube, twinkle: false)
            if b.glow > 0.01 {
                let glow = Gradient(stops: [
                    .init(color: .white.opacity(0.35 * b.glow), location: 0),
                    .init(color: FlightPalette.starBlue.opacity(0.16 * b.glow), location: 0.4),
                    .init(color: .clear, location: 1),
                ])
                ctx.fill(Path(full), with: .radialGradient(glow, center: CGPoint(x: cx, y: cy), startRadius: 0, endRadius: max(w, h) * 0.7))
            }
            globe.draw(ctx, cx: cx, cy: cy, r: base * b.size, alpha: b.globe, orbits: 0, t: t, visible: 0, used: 0, lock: 1)
        }
        // The jump's flash: white at the heart, blue at the edges, at its brightest as the map opens.
        let x = (a.dive - LANDING) / 0.09
        let flash = arriveMs >= 0 ? exp(-(x * x)) : 0
        if flash > 0.01 {
            let light = Gradient(stops: [
                .init(color: .white.opacity(flash), location: 0),
                .init(color: FlightPalette.starBlue.opacity(flash * 0.7), location: 0.6),
                .init(color: FlightPalette.blue.opacity(flash * 0.35), location: 1),
            ])
            ctx.fill(Path(full), with: .radialGradient(light, center: CGPoint(x: cx, y: cy), startRadius: 0, endRadius: max(w, h)))
        }
    }

    /** Rings of light along the tube's wall, rushing toward the viewer: they make the tunnel read as one. */
    private func tubeRings(_ ctx: GraphicsContext, cx: Double, cy: Double, scale: Double, alpha: Double) {
        let spacing = 0.25
        // Depth of each ring moves with the distance travelled.
        let travelled = (t * speed * 0.6).truncatingRemainder(dividingBy: spacing)
        var z = spacing - travelled
        while z < 1.2 {
            if z > 0.05 {
                let near = min(max(1 - z / 1.2, 0), 1)
                let r = 1.15 / z * scale
                ctx.stroke(
                    Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r)),
                    with: .color(FlightPalette.starBlue.opacity(alpha * near * near * 0.35)),
                    lineWidth: 0.5 + near * 3
                )
            }
            z += spacing
        }
    }

    /** Callbacks leave the frame being drawn: the screen's state changes after it, never during. */
    private static func call(_ action: @escaping () -> Void) {
        DispatchQueue.main.async { action() }
    }
}

/** Where the arrival stands at [ms] since its start (-1: not arriving): the dive, 0 to 1, after the lock-on beat. */
private struct FlightJump {
    let dive: Double

    init(ms: Double) {
        dive = ms < 0 ? 0 : min(max((ms - Double(HOLD_MS)) / Double(DIVE_MS), 0), 1)
    }
}

/** Where the way back stands at [ms] since the stop (-1: not under way). */
private struct FlightBack {
    /** The stars drift gently back toward the heart, then rest. */
    let speed: Double
    let tube: Double
    let glow: Double

    /** The Earth's radius in globe radii: near behind the scene, its size in the tunnel, then a point. */
    let size: Double
    let globe: Double

    init(ms: Double) {
        let on = ms >= 0
        // Drifting away (0 to 1), then fading into the stars (0 to 1).
        let recede = on ? min(max(ms / Double(RECEDE_MS), 0), 1) : 0
        let vanish = on ? min(max((ms - Double(RECEDE_MS)) / Double(HOMECOMING_MS - RECEDE_MS), 0), 1) : 0
        let near = (1 - recede) * (1 - recede)
        speed = on ? -3 * near : 0
        tube = on ? 0.6 * (1 - FlightBack.smooth(vanish)) : 0
        glow = on ? 0.5 * near * FlightBack.smooth(ms / Double(CLOSE_MS)) : 0
        size = (1 + 2.5 * near) * (1 - 0.8 * FlightBack.smooth(vanish))
        globe = on ? FlightBack.smooth((ms - 120) / Double(CLOSE_MS - 120)) * (1 - FlightBack.smooth(vanish)) : 0
    }

    private static func smooth(_ x: Double) -> Double {
        let k = min(max(x, 0), 1)
        return k * k * (3 - 2 * k)
    }
}

/** A value easing to its target over a fixed time, from wherever it is when the target changes. */
private struct FlightTween {
    private(set) var target: Double
    private var from: Double
    private var start = -Double.infinity
    private let duration: Double

    init(_ value: Double, seconds: Double) {
        target = value
        from = value
        duration = seconds
    }

    func value(at now: Double) -> Double {
        let k = (now - start) / duration
        return k >= 1 ? target : from + (target - from) * FlightEasing.fastOutSlowIn(max(k, 0))
    }

    mutating func aim(_ goal: Double, at now: Double) {
        guard goal != target else { return }
        from = value(at: now)
        target = goal
        start = now
    }
}

/** A cubic Bézier easing from (0, 0) to (1, 1), the way Android's are defined. */
private struct FlightEasing {
    let x1: Double
    let y1: Double
    let x2: Double
    let y2: Double

    /** Quick out of the start, slow into the end: Android's FastOutSlowInEasing. */
    static let fastOutSlowIn = FlightEasing(x1: 0.4, y1: 0, x2: 0.2, y2: 1)

    func callAsFunction(_ fraction: Double) -> Double {
        if fraction <= 0 { return 0 }
        if fraction >= 1 { return 1 }
        // The curve's parameter where x is [fraction]: Newton's method, kept inside a shrinking bracket.
        var lo = 0.0, hi = 1.0, s = fraction
        for _ in 0..<16 {
            let dx = FlightEasing.bezier(s, x1, x2) - fraction
            if abs(dx) < 1e-6 { break }
            if dx > 0 { hi = s } else { lo = s }
            let slope = FlightEasing.slope(s, x1, x2)
            let next = slope > 1e-6 ? s - dx / slope : (lo + hi) / 2
            s = next > lo && next < hi ? next : (lo + hi) / 2
        }
        return FlightEasing.bezier(s, y1, y2)
    }

    private static func bezier(_ s: Double, _ p1: Double, _ p2: Double) -> Double {
        let u = 1 - s
        return 3 * u * u * s * p1 + 3 * u * s * s * p2 + s * s * s
    }

    private static func slope(_ s: Double, _ p1: Double, _ p2: Double) -> Double {
        let u = 1 - s
        return 3 * u * u * p1 + 6 * u * s * (p2 - p1) + 3 * s * s * (1 - p2)
    }
}

/** The flight's colors. */
private enum FlightPalette {
    static let blue = rgb(0x0A84FF)
    static let green = rgb(0x30D158)
    static let red = rgb(0xFF3B30)
    static let starBlue = rgb(0x9CC8FF)
    static let starRed = rgb(0xFF6B5E)
    static let nebula = rgb(0x8E5CFF)

    private static func rgb(_ hex: Int) -> Color {
        Color(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/** Small seeded random numbers: the same sky every time. */
private struct FlightRandom {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    /** 0 (included) to 1 (excluded). */
    mutating func unit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Double(z >> 11) / 9_007_199_254_740_992
    }

    /** 0 to [n] - 1. */
    mutating func below(_ n: Int) -> Int { min(Int(unit() * Double(n)), n - 1) }
}

/**
 * Stars ahead of the camera. The sky fills the whole view; the tube keeps them on a wall around
 * the viewer, so at speed they read as a tunnel. Drawn in a few batches (by color, brightness and
 * width) rather than star by star, so a thousand of them stay smooth.
 */
private final class FlightStars {
    private static let alphaLevels = 16
    private static let widthLevels = 8
    private static let batches = 3 * FlightStars.alphaLevels * FlightStars.widthLevels

    /** Red, blue and white at each brightness level. */
    private static let inks: [Color] = {
        var out = [Color]()
        for base in [FlightPalette.starRed, FlightPalette.starBlue, Color.white] {
            for level in 1...FlightStars.alphaLevels { out.append(base.opacity(Double(level) / Double(FlightStars.alphaLevels))) }
        }
        return out
    }()

    private let count: Int
    private let tube: Bool
    private var random: FlightRandom
    private var x: [Double]
    private var y: [Double]
    private var z: [Double]
    /** 0 red, 1 blue, 2 white. */
    private var tint: [Int]
    private var phase: [Double]
    private var lines = [Path](repeating: Path(), count: FlightStars.batches)
    private var dots = [Path](repeating: Path(), count: FlightStars.batches)
    private var filled = [Bool](repeating: false, count: FlightStars.batches)
    private var used: [Int] = []

    init(count: Int, tube: Bool) {
        self.count = count
        self.tube = tube
        random = FlightRandom(seed: tube ? 42 : 7)
        x = [Double](repeating: 0, count: count)
        y = [Double](repeating: 0, count: count)
        z = [Double](repeating: 0, count: count)
        tint = [Int](repeating: 2, count: count)
        phase = [Double](repeating: 0, count: count)
        for i in 0..<count { spawn(i, depth: 0.05 + random.unit() * 1.15) }
    }

    private func spawn(_ i: Int, depth: Double) {
        let angle = random.unit() * 2 * .pi
        let r = tube ? 1 + random.unit() * 0.35 : 0.05 + random.unit().squareRoot() * 2.2
        x[i] = cos(angle) * r
        y[i] = sin(angle) * r
        z[i] = depth
        let shade = random.below(24)
        tint[i] = shade == 0 ? 0 : (shade <= 4 ? 1 : 2)
        phase[i] = random.unit() * 6.28
    }

    /** [speed] below zero: backwards, the stars fall away into the heart and new ones come from beside the viewer. */
    func step(dt: Double, speed: Double) {
        for i in 0..<count {
            z[i] -= speed * dt
            if z[i] <= 0.03 {
                spawn(i, depth: 1.2)
            } else if z[i] > 1.25 {
                spawn(i, depth: 0.05 + random.unit() * 0.15)
            }
        }
    }

    func draw(_ ctx: GraphicsContext, size: CGSize, cx: Double, cy: Double, scale: Double, speed: Double, t: Double, fade: Double, twinkle: Bool) {
        guard fade > 0 else { return }
        for key in used {
            lines[key] = Path()
            dots[key] = Path()
            filled[key] = false
        }
        used.removeAll(keepingCapacity: true)
        let margin = 14.0
        let w = Double(size.width), h = Double(size.height)
        for i in 0..<count {
            let depth = z[i]
            let px = cx + x[i] / depth * scale
            let py = cy + y[i] / depth * scale
            if px < -margin || px > w + margin || py < -margin || py > h + margin { continue }
            let near = min(max(1 - depth / 1.2, 0), 1)
            let shine = twinkle ? 0.65 + 0.35 * sin(t * 2.2 + phase[i]) : 1
            let alpha = min(max((0.15 + near * near * 1.1) * shine * fade, 0), 1)
            let level = min(Int((alpha * Double(FlightStars.alphaLevels)).rounded()), FlightStars.alphaLevels)
            if level == 0 { continue }
            let band = min(Int(near * Double(FlightStars.widthLevels)), FlightStars.widthLevels - 1)
            let key = (tint[i] * FlightStars.alphaLevels + level - 1) * FlightStars.widthLevels + band
            if !filled[key] {
                filled[key] = true
                used.append(key)
            }
            let width = 0.6 + near * 2.6
            // The trail: where the star was a moment ago; long at speed, nothing at rest, outward backwards.
            let tailDepth = min(max(depth + speed * 0.05, depth * 0.3), 1.4)
            let tx = cx + x[i] / tailDepth * scale
            let ty = cy + y[i] / tailDepth * scale
            if (px - tx) * (px - tx) + (py - ty) * (py - ty) < width * width {
                let radius = width / 2 + 0.15
                dots[key].addEllipse(in: CGRect(x: px - radius, y: py - radius, width: 2 * radius, height: 2 * radius))
            } else {
                lines[key].move(to: CGPoint(x: tx, y: ty))
                lines[key].addLine(to: CGPoint(x: px, y: py))
            }
        }
        for key in used {
            let ink = FlightStars.inks[key / FlightStars.widthLevels]
            if !dots[key].isEmpty { ctx.fill(dots[key], with: .color(ink)) }
            if !lines[key].isEmpty {
                let band = Double(key % FlightStars.widthLevels)
                let width = 0.6 + (band + 0.5) / Double(FlightStars.widthLevels) * 2.6
                ctx.stroke(lines[key], with: .color(ink), style: StrokeStyle(lineWidth: width, lineCap: .round))
            }
        }
    }
}

/** The arrival's shock ring: a bright circle racing out from the tunnel's heart as the scene opens. */
struct ShockRing: View, Animatable {
    var progress: Double

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        if progress > 0 && progress < 1 {
            Canvas { ctx, size in
                let w = Double(size.width), h = Double(size.height)
                let k = FlightEasing.fastOutSlowIn(progress)
                let r = k * (w * w + h * h).squareRoot()
                let cy = h * Double(HEART_Y)
                ctx.stroke(
                    Path(ellipseIn: CGRect(x: w / 2 - r, y: cy - r, width: 2 * r, height: 2 * r)),
                    with: .color(.white.opacity(0.8 * (1 - k))),
                    lineWidth: 14 * (1 - k) + 1
                )
            }
            .allowsHitTesting(false)
        }
    }
}

/**
 * The words under the globe while the GPS searches: what is happening, and four lights coming on
 * one by one as satellites lock (four make a position). [locked]: the airlock, the position is
 * there. In the demo, [gps] is simulated.
 */
struct FlightCaption: View {
    let gps: GpsInfo
    let demo: Bool
    var locked: Bool = false
    var roadsLoading: Bool = false

    var body: some View {
        let ready = locked || gps.used >= 4
        // Position found, the roads around it still coming: the tunnel waits for them.
        let waiting = roadsLoading && !locked
        let status: String = {
            if waiting { return "Chargement des routes…" }
            if ready { return "Position verrouillée" }
            if gps.used > 0 { return "Triangulation · \(gps.used) sur 4" }
            if gps.visible > 0 { return "Contact des satellites…" }
            return "Recherche du ciel…"
        }()
        VStack(spacing: 0) {
            // The text rolls: the new line comes up from below, the old one leaves upwards.
            ZStack {
                Text(verbatim: status)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(ready && !waiting ? FlightPalette.green : Color.white)
                    .shadow(color: .black, radius: 6)
                    .fixedSize()
                    .id(status)
                    .transition(FlightCaption.roll)
            }
            .mask { Rectangle().padding(.horizontal, -24) }
            .animation(.spring(response: 0.314, dampingFraction: 0.8), value: status)
            HStack(spacing: 12) {
                ForEach(0..<4, id: \.self) { i in
                    FlightLockLight(on: locked || roadsLoading || i < gps.used, ready: ready)
                }
            }
            .padding(.top, 14)
            Text(verbatim: demo ? "Trajet simulé · démo" : "\(gps.visible) satellites en vue")
                .font(.system(size: 14))
                .foregroundStyle(Color.white.opacity(0.55))
                .shadow(color: .black, radius: 6)
                .padding(.top, 12)
        }
    }

    private static var roll: AnyTransition {
        .asymmetric(
            insertion: AnyTransition.move(edge: .bottom).combined(with: .opacity)
                .animation(.spring(response: 0.314, dampingFraction: 0.8)),
            removal: AnyTransition.move(edge: .top).combined(with: .opacity)
                .animation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.25))
        )
    }
}

/** One of the four lock lights: it pops on with a glow, blue while searching, all green once locked. */
private struct FlightLockLight: View {
    let on: Bool
    let ready: Bool

    var body: some View {
        let color = ready ? FlightPalette.green : FlightPalette.blue
        ZStack {
            Circle()
                .fill(RadialGradient(colors: [color.opacity(0.5), .clear], center: .center, startRadius: 0, endRadius: 15.4))
                .frame(width: 30.8, height: 30.8)
                .opacity(on ? 1 : 0)
            Circle()
                .stroke(Color.white.opacity(0.22), lineWidth: 1)
                .frame(width: 8.4, height: 8.4)
            Circle()
                .fill(color)
                .frame(width: 8.4, height: 8.4)
                .scaleEffect(on ? 1 : 0.001)
                .opacity(on ? 1 : 0)
        }
        .frame(width: 14, height: 14)
        // A bouncy pop: it overshoots, then settles.
        .animation(.spring(response: 0.314, dampingFraction: 0.4), value: on)
    }
}

/** Over the limit near a radar: the edges of the whole screen pulse red, the middle stays clear. */
struct DangerGlow: View {
    let on: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: !on)) { timeline in
            let beat = DangerGlow.beat(timeline.date.timeIntervalSinceReferenceDate)
            Canvas { ctx, size in
                let edge = Gradient(stops: [
                    .init(color: .clear, location: 0.55),
                    .init(color: FlightPalette.red.opacity(0.55 * beat), location: 1),
                ])
                ctx.fill(
                    Path(CGRect(origin: .zero, size: size)),
                    with: .radialGradient(
                        edge,
                        center: CGPoint(x: size.width / 2, y: size.height / 2),
                        startRadius: 0,
                        endRadius: max(size.width, size.height) * 0.75
                    )
                )
            }
        }
        .opacity(on ? 1 : 0)
        .animation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.4), value: on)
        .allowsHitTesting(false)
    }

    /** From 0.45 up to 1 and back, 450 ms each way. */
    private static func beat(_ seconds: Double) -> Double {
        let x = seconds.truncatingRemainder(dividingBy: 0.9)
        let k = x < 0.45 ? x / 0.45 : (0.9 - x) / 0.45
        return 0.45 + 0.55 * FlightEasing.fastOutSlowIn(k)
    }
}

/** A radar just passed: a band of light sweeps down the screen as under a gantry, green, or red if it would flash. */
struct RadarPassWave: View, Animatable {
    var progress: Double
    let flash: Bool

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        if progress > 0 && progress < 1 {
            Canvas { ctx, size in
                let color = flash ? FlightPalette.red : FlightPalette.green
                let w = Double(size.width), h = Double(size.height)
                let y = h * (-0.2 + 1.4 * progress)
                let band = h * 0.22
                let fade = 1 - progress * progress
                let full = Path(CGRect(origin: .zero, size: size))
                let sweep = Gradient(stops: [
                    .init(color: .clear, location: 0),
                    .init(color: color.opacity(0.3 * fade), location: 0.5),
                    .init(color: .clear, location: 1),
                ])
                ctx.fill(full, with: .linearGradient(sweep, startPoint: CGPoint(x: 0, y: y - band), endPoint: CGPoint(x: 0, y: y + band)))
                let edge = Gradient(stops: [
                    .init(color: .clear, location: 0.6),
                    .init(color: color.opacity(0.3 * (1 - progress)), location: 1),
                ])
                ctx.fill(full, with: .radialGradient(edge, center: CGPoint(x: w / 2, y: h / 2), startRadius: 0, endRadius: max(w, h) * 0.75))
            }
            .allowsHitTesting(false)
        }
    }
}

/**
 * The calm sky of home behind the other screens (settings, permissions), with a faint nebula high
 * up: the whole app lives in the same space.
 */
struct SpaceBackdrop: View {
    @StateObject private var sky = HomeSky()

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { ctx, size in
                sky.draw(ctx, size: size, now: timeline.date.timeIntervalSinceReferenceDate)
            }
        }
        .background(Color.black)
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/** The stars of [SpaceBackdrop], drifting from frame to frame. Never observed. */
private final class HomeSky: ObservableObject {
    private static let highBlue = Gradient(colors: [FlightPalette.blue.opacity(0.12), .clear])
    private static let violet = Gradient(colors: [FlightPalette.nebula.opacity(0.08), .clear])

    private let stars = FlightStars(count: 260, tube: false)
    private var epoch: Double?
    private var last: Double?

    func draw(_ ctx: GraphicsContext, size: CGSize, now: Double) {
        if let last { stars.step(dt: min(now - last, 0.05), speed: 0.025) }
        last = now
        let start = epoch ?? now
        epoch = start
        let w = Double(size.width), h = Double(size.height)
        let full = Path(CGRect(origin: .zero, size: size))
        ctx.fill(full, with: .radialGradient(HomeSky.highBlue, center: CGPoint(x: w * 0.15, y: h * 0.08), startRadius: 0, endRadius: max(w, h) * 0.6))
        ctx.fill(full, with: .radialGradient(HomeSky.violet, center: CGPoint(x: w * 0.9, y: h * 0.4), startRadius: 0, endRadius: max(w, h) * 0.5))
        stars.draw(ctx, size: size, cx: w / 2, cy: h * Double(HEART_Y), scale: min(w, h) * 0.42, speed: 0.025, t: now - start, fade: 0.7, twinkle: true)
    }
}
