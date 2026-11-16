// Port of app/src/main/kotlin/zenith/app/WeatherLayer.kt.
import SwiftUI

/**
 * The weather over the screen: rain streaks that lean and quicken with the speed, snowflakes that
 * drift, a storm's distant lightning, fog drifting in soft banks. Every particle is a pure function
 * of its index and the time. strength from SkyLook.strength; at 0 nothing is drawn and no frame
 * is asked for. The caller masks it like the map, so it never covers the music's Canvas.
 */
struct WeatherLayer: View {
    let look: SkyLook
    let strength: Double
    let speedKmh: Int?
    let alerting: Bool

    @State private var kind: SkyKind = .clear
    /** The strength drawn: the last one above zero, so the effect fades out whole. */
    @State private var drawn = 0.0
    @State private var visible = false
    @State private var flashAt = Date.distantPast
    @State private var start = Date()

    var body: some View {
        Group {
            if drawn > 0.01 {
                TimelineView(.animation) { context in
                    WeatherCanvas(
                        kind: kind,
                        strength: drawn,
                        speedKmh: Double(min(max(speedKmh ?? 0, 0), 150)),
                        time: context.date.timeIntervalSince(start).truncatingRemainder(dividingBy: 600),
                        flashAge: alerting ? -1 : context.date.timeIntervalSince(flashAt)
                    )
                }
                .opacity(visible ? 1 : 0)
            }
        }
        .allowsHitTesting(false)
        .onAppear {
            if look.kind != .clear { kind = look.kind }
            follow(strength)
        }
        // A change of weather fades over 2 s; the kind drawn stays the last real one while it fades out.
        .onChange(of: look.kind) { _, k in if k != .clear { kind = k } }
        .onChange(of: strength) { _, s in follow(s) }
        // Faded out: no more frames asked for.
        .task(id: visible) {
            guard !visible else { return }
            try? await Task.sleep(nanoseconds: 2_100_000_000)
            if !Task.isCancelled && !visible { drawn = 0 }
        }
        // Storm: lightning now and then, the first a few seconds in.
        .task(id: kind) {
            guard kind == .storm else { return }
            try? await Task.sleep(nanoseconds: UInt64.random(in: 2_000...UInt64(SkyLook.flashMinMs)) * 1_000_000)
            while !Task.isCancelled {
                flashAt = Date()
                try? await Task.sleep(nanoseconds: UInt64.random(in: UInt64(SkyLook.flashMinMs)...UInt64(SkyLook.flashMaxMs)) * 1_000_000)
            }
        }
    }

    private func follow(_ s: Double) {
        if s > 0.01 { drawn = s }
        withAnimation(.linear(duration: 2)) { visible = s > 0.01 }
    }
}

/**
 * One frame of the weather at [time] seconds (also drawn by the small window, frame by frame).
 * [flashAge] seconds since the last lightning, negative: none.
 */
struct WeatherCanvas: View {
    let kind: SkyKind
    let strength: Double
    let speedKmh: Double
    let time: Double
    var flashAge: Double = -1

    var body: some View {
        Canvas { ctx, size in
            guard strength > 0.01 else { return }
            switch kind {
            case .rain:
                Self.rain(&ctx, size, time, strength, speedKmh, heavy: false)
                Self.drops(&ctx, size, time, strength, speedKmh)
            case .storm:
                Self.rain(&ctx, size, time, strength, speedKmh, heavy: true)
                Self.drops(&ctx, size, time, strength, speedKmh)
                Self.lightning(&ctx, size, flashAge, strength)
            case .snow: Self.snow(&ctx, size, time, strength, speedKmh)
            case .fog: Self.fog(&ctx, size, time, strength)
            case .clear: break
            }
        }
        .allowsHitTesting(false)
    }

    private static let rainTint = Color(red: 0.75, green: 0.85, blue: 1)

    /** Stable pseudo-random 0..1 for particle [i], channel [c] (the Android hash, bit for bit). */
    static func hash(_ i: Int, _ c: Int) -> Double {
        var x = Int32(truncatingIfNeeded: i) &* 374_761_393 &+ Int32(truncatingIfNeeded: c) &* 668_265_263
        x = (x ^ Int32(bitPattern: UInt32(bitPattern: x) >> 13)) &* 1_274_126_177
        let y = x ^ Int32(bitPattern: UInt32(bitPattern: x) >> 16)
        return Double(y & 0xFFFFFF) / 16_777_216
    }

    private static func rain(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ strength: Double, _ speed: Double, heavy: Bool) {
        // A light rain (0.35) must still be seen at a glance: density and light follow the square root.
        let k = strength.squareRoot()
        let w = size.width, h = size.height
        let unit = min(w, h) / 400
        let count = max(20, Int(Double(heavy ? 170 : 130) * k))
        // Faster car: the streaks lean more and fall faster, as on a windscreen.
        let fast = min(max(speed / 130, 0), 1)
        let lean = 0.12 + 0.38 * fast
        let fall = h * (1.1 + 0.9 * fast) * (heavy ? 1.25 : 1)
        for i in 0..<count {
            let depth = 0.35 + 0.65 * hash(i, 1)
            let len = (14 + 26 * depth) * unit * (heavy ? 1.2 : 1)
            let y = (hash(i, 2) + t * fall * depth / h).truncatingRemainder(dividingBy: 1) * (h + len) - len
            let x0 = hash(i, 3) * (w * (1 + lean)) - w * lean * 0.5
            let x = x0 - lean * (y + len)
            var path = Path()
            path.move(to: CGPoint(x: x, y: y))
            path.addLine(to: CGPoint(x: x - lean * len, y: y + len))
            ctx.stroke(path, with: .color(rainTint.opacity((0.22 + 0.38 * depth) * k)),
                       style: StrokeStyle(lineWidth: (1.0 + 1.1 * depth) * unit, lineCap: .round))
        }
    }

    /**
     * Drops landing on the glass, as on a windscreen: each lands with a little splash, catches the
     * light (a bright crescent on top, a darker rim below), stays a few seconds, sometimes slides
     * down leaving a faint trail, then dries. At speed they slide more and live shorter.
     */
    private static func drops(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ strength: Double, _ speed: Double) {
        guard min(size.width, size.height) >= 1 else { return }
        let k = strength.squareRoot()
        let w = size.width, h = size.height
        let unit = min(w, h) / 400
        let fast = min(max(speed / 130, 0), 1)
        let count = Int(14 + 46 * k)
        for i in 0..<count {
            let life = 2.2 + 3.8 * hash(i, 11) - 1.2 * fast
            let shifted = t + hash(i, 12) * life
            let cycle = Int(shifted / life)
            let age = (shifted - Double(cycle) * life) / life
            let seed = i &* 7919 &+ cycle
            let r = (3.2 + 7.5 * hash(seed, 13) * hash(seed, 14)) * unit
            let x0 = hash(seed, 15) * w
            let y0 = hash(seed, 16) * h
            let slides = hash(seed, 17) < 0.33 + 0.4 * fast
            let slide = slides ? age * age * (50 + 110 * fast) * unit : 0
            let y = y0 + slide
            let alpha = (age < 0.12 ? age / 0.12 : age > 0.7 ? (1 - age) / 0.3 : 1) * k
            let rr = r * (age < 0.12 ? 0.6 + 0.4 * (age / 0.12) : 1)
            let c = CGPoint(x: x0, y: y)
            if slides && slide > rr {
                var trail = Path()
                trail.move(to: CGPoint(x: x0, y: y0))
                trail.addLine(to: CGPoint(x: x0, y: y - rr))
                ctx.stroke(trail, with: .color(.white.opacity(0.10 * alpha)), style: StrokeStyle(lineWidth: rr * 0.45, lineCap: .round))
            }
            let body = Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
            let water = Color(red: 0.87, green: 0.92, blue: 1)
            ctx.fill(body, with: .radialGradient(
                Gradient(colors: [water.opacity(0.05 * alpha), water.opacity(0.22 * alpha)]),
                center: CGPoint(x: c.x, y: c.y - rr * 0.3), startRadius: 0, endRadius: rr * 1.2
            ))
            var rim = Path()
            rim.addArc(center: c, radius: rr, startAngle: .degrees(20), endAngle: .degrees(160), clockwise: false)
            ctx.stroke(rim, with: .color(.black.opacity(0.35 * alpha)), lineWidth: rr * 0.22)
            var shine = Path()
            shine.addArc(center: c, radius: rr * 0.7, startAngle: .degrees(200), endAngle: .degrees(290), clockwise: false)
            ctx.stroke(shine, with: .color(.white.opacity(0.75 * alpha)), style: StrokeStyle(lineWidth: rr * 0.2, lineCap: .round))
            let dot = rr * 0.16
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - rr * 0.35 - dot, y: c.y - rr * 0.4 - dot, width: 2 * dot, height: 2 * dot)),
                     with: .color(.white.opacity(0.85 * alpha)))
            if age < 0.12 {
                let q = age / 0.12
                let rs = r * (1 + 1.2 * q)
                ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rs, y: c.y - rs, width: 2 * rs, height: 2 * rs)),
                           with: .color(.white.opacity(0.4 * (1 - q) * k)), lineWidth: unit * 0.8)
            }
        }
    }

    private static func snow(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ strength: Double, _ speed: Double) {
        guard min(size.width, size.height) >= 1 else { return }
        let k = strength.squareRoot()
        let w = size.width, h = size.height
        let unit = min(w, h) / 400
        let count = max(24, Int(110 * k))
        let fall = h * (0.08 + 0.35 * min(max(speed / 130, 0), 1))
        for i in 0..<count {
            let depth = 0.3 + 0.7 * hash(i, 1)
            let y = (hash(i, 2) + t * fall * (0.5 + depth) / h).truncatingRemainder(dividingBy: 1) * (h + 20) - 10
            let sway = sin(t * (0.5 + hash(i, 4)) + hash(i, 5) * 2 * .pi) * 14 * unit * depth
            let x = hash(i, 3) * w + sway
            let r = (1.6 + 3.4 * depth * depth) * unit
            let a = (0.45 + 0.5 * depth) * k
            let glow = r * 2.6
            // Soft glow, then the flake.
            ctx.fill(Path(ellipseIn: CGRect(x: x - glow, y: y - glow, width: 2 * glow, height: 2 * glow)), with: .radialGradient(
                Gradient(colors: [.white.opacity(0.35 * a), .clear]), center: CGPoint(x: x, y: y), startRadius: 0, endRadius: glow
            ))
            if depth > 0.85 {
                // The nearest ones show their six arms, turning slowly.
                let spin = t * (0.3 + hash(i, 6)) + hash(i, 7) * 6
                for arm in 0..<3 {
                    let ang = spin + Double(arm) * .pi / 3
                    let dx = cos(ang) * r * 1.6, dy = sin(ang) * r * 1.6
                    var line = Path()
                    line.move(to: CGPoint(x: x - dx, y: y - dy))
                    line.addLine(to: CGPoint(x: x + dx, y: y + dy))
                    ctx.stroke(line, with: .color(.white.opacity(a)), style: StrokeStyle(lineWidth: r * 0.35, lineCap: .round))
                }
            } else {
                ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(.white.opacity(a)))
            }
        }
    }

    private static func fog(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ k: Double) {
        let w = size.width, h = size.height
        // Three soft banks drifting sideways, lower ones thicker, the road still seen through.
        for b in 0..<3 {
            let cy = h * (0.32 + 0.2 * Double(b))
            let drift = sin(t * 0.05 * Double(1 + b) + Double(b) * 2.1)
            let cx = w * (0.5 + 0.35 * drift)
            let rx = w * (0.9 + 0.2 * Double(b))
            let ry = h * 0.16
            let a = (0.12 + 0.08 * Double(b)) * k
            let rect = CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2)
            ctx.fill(Path(ellipseIn: rect), with: .radialGradient(
                Gradient(colors: [.white.opacity(a), .clear]),
                center: CGPoint(x: cx, y: cy), startRadius: 0, endRadius: rx
            ))
        }
    }

    /** A distant flash: two quick flickers of a soft light from the top, fading out. */
    private static func lightning(_ ctx: inout GraphicsContext, _ size: CGSize, _ age: Double, _ k: Double) {
        guard age >= 0, age <= 0.9 else { return }
        let flicker = exp(-age * 9) + 0.6 * exp(-pow((age - 0.18) * 22, 2))
        let a = min(max(0.16 * flicker * k, 0), 0.2)
        guard a > 0.005 else { return }
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .radialGradient(
            Gradient(colors: [Color(red: 0.87, green: 0.9, blue: 1).opacity(a), .clear]),
            center: CGPoint(x: size.width / 2, y: 0), startRadius: 0, endRadius: max(size.width, size.height) * 0.9
        ))
    }
}
