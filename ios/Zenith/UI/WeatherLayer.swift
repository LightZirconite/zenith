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
            case .rain: Self.rain(&ctx, size, time, strength, speedKmh, heavy: false)
            case .storm:
                Self.rain(&ctx, size, time, strength, speedKmh, heavy: true)
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

    private static func snow(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ strength: Double, _ speed: Double) {
        let k = strength.squareRoot()
        let w = size.width, h = size.height
        let unit = min(w, h) / 400
        let count = max(14, Int(90 * k))
        let fall = h * (0.08 + 0.35 * min(max(speed / 130, 0), 1))
        for i in 0..<count {
            let depth = 0.3 + 0.7 * hash(i, 1)
            let y = (hash(i, 2) + t * fall * (0.5 + depth) / h).truncatingRemainder(dividingBy: 1) * (h + 20) - 10
            let sway = sin(t * (0.5 + hash(i, 4)) + hash(i, 5) * 2 * .pi) * 14 * unit * depth
            let x = hash(i, 3) * w + sway
            let r = (1.2 + 2.6 * depth) * unit
            ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)),
                     with: .color(.white.opacity((0.35 + 0.5 * depth) * k)))
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
