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
    /** Drawn a few times a second (the small window): drops resting on the glass, nothing falling fast. */
    var still = false

    var body: some View {
        Canvas { ctx, size in
            guard strength > 0.01 else { return }
            switch kind {
            case .rain, .storm:
                if !still { Self.rain(&ctx, size, time, strength, speedKmh, heavy: kind == .storm) }
                Self.drops(&ctx, size, time, strength, still ? 0 : speedKmh, slide: !still)
                Self.lightning(&ctx, size, flashAge, strength)
            case .snow: Self.snow(&ctx, size, time, strength, speedKmh)
            case .fog: Self.fog(&ctx, size, time, strength)
            case .clear: break
            }
        }
        .allowsHitTesting(false)
    }

    /** Stable pseudo-random 0..1 for particle [i], channel [c] (the Android hash, bit for bit). */
    static func hash(_ i: Int, _ c: Int) -> Double {
        var x = Int32(truncatingIfNeeded: i) &* 374_761_393 &+ Int32(truncatingIfNeeded: c) &* 668_265_263
        x = (x ^ Int32(bitPattern: UInt32(bitPattern: x) >> 13)) &* 1_274_126_177
        let y = x ^ Int32(bitPattern: UInt32(bitPattern: x) >> 16)
        return Double(y & 0xFFFFFF) / 16_777_216
    }

    private static func rgba(_ r: Double, _ g: Double, _ b: Double, _ a: Double) -> Color {
        Color(red: r / 255, green: g / 255, blue: b / 255).opacity(min(max(a, 0), 1))
    }

    /** Fewer particles in a small window (the picture-in-picture) than on a whole phone screen. */
    private static func areaShare(_ size: CGSize) -> Double {
        min(max(Double(size.width * size.height) / (390 * 690), 0.3), 1)
    }

    private static func dot(_ ctx: inout GraphicsContext, _ c: CGPoint, _ r: Double, _ color: Color) {
        guard r > 0.05 else { return }
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(color))
    }

    private static func segment(_ ctx: inout GraphicsContext, _ a: CGPoint, _ b: CGPoint, _ color: Color, _ width: Double) {
        var p = Path()
        p.move(to: a)
        p.addLine(to: b)
        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }

    /**
     * Rain falling: thin streaks in depth (far ones short and faint, near ones long and bright), each
     * fading toward its tail, leaning and quickening with the speed.
     */
    private static func rain(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ strength: Double, _ speed: Double, heavy: Bool) {
        guard min(size.width, size.height) >= 1 else { return }
        // A light rain (0.35) must still be seen at a glance: density and light follow the square root.
        let k = strength.squareRoot()
        let w = size.width, h = size.height
        let unit = min(w, h) / 400
        let fast = min(max(speed / 130, 0), 1)
        let lean = 0.10 + 0.40 * fast
        let count = max(12, Int(Double(max(30, Int(Double(heavy ? 220 : 170) * k))) * areaShare(size)))
        for i in 0..<count {
            let d = hash(i, 1)
            let len = (10 + 44 * d * d) * unit * (heavy ? 1.15 : 1)
            let width = (0.5 + 1.5 * d) * unit
            let a = (0.10 + 0.42 * d) * k
            let ln = lean + (hash(i, 8) - 0.5) * 0.06
            let fall = h * (1.2 + 1.4 * d) * (1 + 0.8 * fast) * (heavy ? 1.2 : 1)
            let y = (hash(i, 2) + t * fall / h).truncatingRemainder(dividingBy: 1) * (h + len) - len
            let x = hash(i, 3) * (w * (1 + ln)) - w * ln * 0.5 - ln * (y + len)
            let mid = CGPoint(x: x - ln * len * 0.5, y: y + len * 0.5)
            segment(&ctx, CGPoint(x: x, y: y), mid, rgba(200, 220, 255, a * 0.35), width)
            segment(&ctx, mid, CGPoint(x: x - ln * len, y: y + len), rgba(215, 230, 255, a), width)
        }
    }

    /**
     * One drop of water on the glass, read as a lens: lighter where it gathers the light below, a
     * dark rim, a bright crescent at the bottom, a sharp highlight at the top left.
     */
    private static func waterDrop(_ ctx: inout GraphicsContext, _ c: CGPoint, _ r: Double, _ ry: Double, _ a: Double) {
        guard r >= 0.3 else { return }
        let oval = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - ry, width: 2 * r, height: 2 * ry))
        ctx.fill(oval, with: .radialGradient(
            Gradient(stops: [
                .init(color: rgba(235, 243, 255, 0.26 * a), location: 0),
                .init(color: rgba(160, 180, 210, 0.08 * a), location: 0.7),
                .init(color: rgba(0, 0, 0, 0.28 * a), location: 1),
            ]),
            center: CGPoint(x: c.x, y: c.y + ry * 0.35), startRadius: 0, endRadius: max(r, ry) * 1.05
        ))
        ctx.stroke(oval, with: .color(rgba(0, 0, 0, 0.30 * a)), lineWidth: max(r * 0.09, 0.6))
        var crescent = Path()
        crescent.addArc(center: .zero, radius: 1, startAngle: .degrees(20), endAngle: .degrees(160), clockwise: false)
        let arc = crescent.applying(CGAffineTransform(a: r * 0.72, b: 0, c: 0, d: ry * 0.72, tx: c.x, ty: c.y))
        ctx.stroke(arc, with: .color(rgba(255, 255, 255, 0.38 * a)), style: StrokeStyle(lineWidth: r * 0.2, lineCap: .round))
        dot(&ctx, CGPoint(x: c.x - r * 0.38, y: c.y - ry * 0.42), r * 0.17, rgba(255, 255, 255, 0.92 * a))
        dot(&ctx, CGPoint(x: c.x + r * 0.3, y: c.y + ry * 0.38), r * 0.07, rgba(255, 255, 255, 0.5 * a))
    }

    /**
     * Water on the glass, as on a windscreen: a fine mist of tiny droplets, and drops that land with
     * a little splash, stay, sometimes run down leaving a trail of beads, then dry. At speed they run
     * more and live shorter. [slide] false (drawn a few times a second): they stay put.
     */
    private static func drops(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ strength: Double, _ speed: Double, slide: Bool) {
        guard min(size.width, size.height) >= 1 else { return }
        let k = strength.squareRoot()
        let w = size.width, h = size.height
        let unit = min(w, h) / 400
        let fast = min(max(speed / 130, 0), 1)
        let share = areaShare(size)
        let mist = Int((40 + 90 * k) * share)
        for i in 0..<mist {
            let life = 4 + 6 * hash(i, 21)
            let shifted = t + hash(i, 22) * life
            let cycle = Int(shifted / life)
            let age = (shifted - Double(cycle) * life) / life
            let seed = i &* 104_729 &+ cycle
            let a = (age < 0.1 ? age / 0.1 : age > 0.75 ? (1 - age) / 0.25 : 1) * k
            let r = (0.8 + 1.6 * hash(seed, 23)) * unit
            let c = CGPoint(x: hash(seed, 24) * w, y: hash(seed, 25) * h)
            dot(&ctx, c, r, rgba(220, 232, 255, 0.18 * a))
            dot(&ctx, CGPoint(x: c.x - r * 0.35, y: c.y - r * 0.35), r * 0.35, rgba(255, 255, 255, 0.7 * a))
        }
        let count = max(6, Int((16 + 44 * k) * share))
        for i in 0..<count {
            let life = 2.4 + 3.8 * hash(i, 11) - 1.2 * fast
            let shifted = t + hash(i, 12) * life
            let cycle = Int(shifted / life)
            let age = (shifted - Double(cycle) * life) / life
            let seed = i &* 7919 &+ cycle
            let r0 = (3 + 8 * hash(seed, 13) * hash(seed, 14)) * unit
            let x0 = hash(seed, 15) * w
            let y0 = hash(seed, 16) * h
            let slides = slide && r0 > 5 * unit && hash(seed, 17) < 0.4 + 0.4 * fast
            let run = slides ? max(0, age - 0.2) / 0.8 : 0
            let slid = run * run * (60 + 140 * fast) * unit
            let y = y0 + slid
            let a = (age < 0.1 ? age / 0.1 : age > 0.72 ? (1 - age) / 0.28 : 1) * k
            let pop = age < 0.1 ? 0.55 + 0.45 * age / 0.1 : 1
            let r = r0 * pop * (slides ? 0.92 : 1)
            let ry = r * (0.88 + 0.1 * hash(seed, 18)) * (slides && slid > 0 ? 1.25 : 1)
            if slides && slid > r {
                // The trail: tiny beads left behind, fainter the older.
                let step = r * 1.6
                var s = y0
                while s < y - ry {
                    let f = (s - y0) / (y - y0)
                    let bead = Int(s)
                    let br = r * (0.18 + 0.12 * hash(seed &+ bead, 19))
                    let bx = x0 + (hash(seed &+ bead, 20) - 0.5) * r * 0.4
                    dot(&ctx, CGPoint(x: bx, y: s), br, rgba(220, 232, 255, 0.22 * a * (0.4 + 0.6 * f)))
                    dot(&ctx, CGPoint(x: bx - br * 0.3, y: s - br * 0.3), br * 0.35, rgba(255, 255, 255, 0.6 * a * (0.4 + 0.6 * f)))
                    s += step
                }
            }
            waterDrop(&ctx, CGPoint(x: x0, y: y), r, ry, a)
            if age < 0.1 {
                let q = age / 0.1
                let rs = r0 * (1 + 1.3 * q)
                ctx.stroke(Path(ellipseIn: CGRect(x: x0 - rs, y: y - rs, width: 2 * rs, height: 2 * rs)),
                           with: .color(rgba(255, 255, 255, 0.4 * (1 - q) * k)), lineWidth: unit * 0.8)
            }
        }
    }

    /**
     * Snow in depth: far flakes tiny and sharp, mid ones glowing, the nearest few large and out of
     * focus (bokeh), some with six arms turning; they drift with the wind and fall faster at speed.
     */
    private static func snow(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ strength: Double, _ speed: Double) {
        guard min(size.width, size.height) >= 1 else { return }
        let k = strength.squareRoot()
        let w = size.width, h = size.height
        let unit = min(w, h) / 400
        let fast = min(max(speed / 130, 0), 1)
        let count = max(20, Int(170 * k * areaShare(size)))
        for i in 0..<count {
            let d = hash(i, 1)
            let fall = h * (0.06 + 0.10 * d + 0.45 * fast * d)
            let wind = (0.04 + 0.10 * fast) * w * d
            let y = (hash(i, 2) + t * fall / h).truncatingRemainder(dividingBy: 1) * (h + 40) - 20
            let sway = sin(t * (0.4 + 0.8 * hash(i, 4)) + hash(i, 5) * 2 * .pi) * (10 + 18 * d) * unit
            var x = (hash(i, 3) * w + t * wind + sway).truncatingRemainder(dividingBy: w + 40)
            if x < -20 { x += w + 40 }
            x -= 20
            let c = CGPoint(x: x, y: y)
            if d > 0.9 {
                let r = (9 + 10 * hash(i, 9)) * unit
                let a = 0.16 * k
                ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: .radialGradient(
                    Gradient(stops: [
                        .init(color: rgba(255, 255, 255, a), location: 0),
                        .init(color: rgba(255, 255, 255, a * 0.8), location: 0.75),
                        .init(color: .clear, location: 1),
                    ]),
                    center: c, startRadius: 0, endRadius: r
                ))
                continue
            }
            let r = (1 + 3.2 * d * d) * unit
            let a = (0.35 + 0.55 * d) * k
            let glow = r * 2.4
            ctx.fill(Path(ellipseIn: CGRect(x: x - glow, y: y - glow, width: 2 * glow, height: 2 * glow)), with: .radialGradient(
                Gradient(colors: [rgba(255, 255, 255, a * 0.45), .clear]), center: c, startRadius: 0, endRadius: glow
            ))
            if d > 0.7 {
                let spin = t * (0.4 + hash(i, 6)) + hash(i, 7) * 6
                for arm in 0..<3 {
                    let ang = spin + Double(arm) * .pi / 3
                    let dx = cos(ang) * r * 1.5, dy = sin(ang) * r * 1.5
                    segment(&ctx, CGPoint(x: x - dx, y: y - dy), CGPoint(x: x + dx, y: y + dy), rgba(255, 255, 255, a), r * 0.28)
                }
                dot(&ctx, c, r * 0.35, rgba(255, 255, 255, a))
            } else {
                dot(&ctx, c, r, rgba(255, 255, 255, a))
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
