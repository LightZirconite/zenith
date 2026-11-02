import SwiftUI

/**
 * The Earth at the heart of the tunnel, drawn frame by frame: land as a field of dots on a dark
 * ocean lit from the upper left, a halo of atmosphere, city lights on the night side, and the
 * satellites in view travelling three tilted orbits (no lines drawn), each a small satellite with
 * its two solar panels, turned along its way. As they lock, the globe slows and turns home (the
 * driver's position once known, France before) to the viewer; a beacon there grows with each lock,
 * and from every locked satellite a curved link draws itself down to it.
 * Aimed, it settles on home like a camera, by the shortest way: the dive lands on the driver.
 */
final class Globe {
    /** Land dots on the unit sphere: x right, y up, z toward the viewer before any turn. */
    private let px: [Double]
    private let py: [Double]
    private let pz: [Double]
    private var home: (x: Double, y: Double, z: Double)
    private var homeSpin: Double
    private var homeTilt: Double

    /** France starts a quarter turn away, to the left: it comes into view as the search goes on. */
    private var spin: Double
    private var tilt = 0.35
    private var spinSpeed = Globe.spinRate
    private var tiltSpeed = 0.0

    /** When each locked satellite locked (scene seconds): its link draws itself from then. */
    private var lockedAt: [Double] = []
    /** The beacon's halo, springing a little larger with each lock. */
    private var haloRadius = 8.0
    private var haloSpeed = 0.0
    private var lastDrawT: Double?

    init() {
        let spinHome = -Globe.homeLon * .pi / 180
        home = Globe.unit(Globe.homeLat, Globe.homeLon)
        homeSpin = spinHome
        homeTilt = Globe.homeLat * .pi / 180
        spin = spinHome - 1.7
        var x = [Double](), y = [Double](), z = [Double]()
        x.reserveCapacity(1_900)
        y.reserveCapacity(1_900)
        z.reserveCapacity(1_900)
        var value = [Int](repeating: 0, count: 128)
        for (i, c) in Globe.alphabet.utf8.enumerated() { value[Int(c)] = i }
        let mask = Array(Globe.land.utf8)
        var bit = 0
        for row in 0..<Globe.rows {
            let lat = Globe.lat0 + Globe.stepDeg * Double(row)
            let n = Int(floor(360 * cos(lat * .pi / 180) / Globe.stepDeg))
            for c in 0..<n {
                if (value[Int(mask[bit / 6])] >> (5 - bit % 6)) & 1 == 1 {
                    let p = Globe.unit(lat, -180 + (Double(c) + 0.5) * 360 / Double(n))
                    x.append(p.x)
                    y.append(p.y)
                    z.append(p.z)
                }
                bit += 1
            }
        }
        px = x
        py = y
        pz = z
    }

    /** Home is where the driver is: the globe turns there from now on. */
    func aimAt(lat: Double, lon: Double) {
        home = Globe.unit(lat, lon)
        homeSpin = -lon * .pi / 180
        homeTilt = lat * .pi / 180
    }

    /** Home straight in front at once (the way back out of it, at the end of a drive). */
    func face() {
        spin = homeSpin
        tilt = homeTilt
        spinSpeed = 0
        tiltSpeed = 0
    }

    /**
     * Spins while searching, slower with each satellite ([lock], 0 to 1). [aim]: home comes to the
     * center like a camera settling, from the speed the globe already has and always by the shortest
     * way (never a whole turn when home moves a little behind); [firm] through the jump, where it must
     * be dead center before the Earth grows large.
     */
    func step(dt: Double, lock: Double, aim: Bool, firm: Bool = false) {
        if aim {
            let w = firm ? 10.0 : 3.0
            spinSpeed += (w * w * Globe.wrap(homeSpin - spin) - 2 * w * spinSpeed) * dt
            tiltSpeed += (w * w * (homeTilt - tilt) - 2 * w * tiltSpeed) * dt
        } else {
            let k = min(max(lock, 0), 1)
            spinSpeed += (Globe.spinRate * (1 - 0.7 * k) - spinSpeed) * min(2 * dt, 1)
            let target = 0.35 + (homeTilt - 0.35) * k * k * (3 - 2 * k)
            tiltSpeed += (9 * (target - tilt) - 6 * tiltSpeed) * dt
        }
        spin = Globe.wrap(spin + spinSpeed * dt)
        tilt += tiltSpeed * dt
    }

    /**
     * Draws the globe of radius [r] at ([cx], [cy]). [orbits] fades the satellites alone (they leave
     * first at the jump); [used] of the [visible] satellites are locked, [lock] is 0 to 1. [flare]
     * (0 to 1): the lock-on, a ring of light running out of the beacon.
     */
    func draw(
        _ ctx: GraphicsContext,
        cx: Double,
        cy: Double,
        r: Double,
        alpha: Double,
        orbits: Double,
        t: Double,
        visible: Int,
        used: Int,
        lock: Double,
        flare: Double = 0
    ) {
        guard alpha > 0.01, r >= 1 else { return }
        track(used: used, t: t)
        let center = CGPoint(x: cx, y: cy)
        let h = rotated(home)
        let beacon: CGPoint? = h.z > 0.12 ? CGPoint(x: cx + h.x * r, y: cy - h.y * r) : nil
        if orbits > 0.01 {
            satellites(ctx, back: true, cx: cx, cy: cy, r: r, alpha: alpha * orbits, t: t, visible: visible, used: used)
        }
        var lit = ctx
        lit.opacity = alpha
        // Atmosphere: a wide soft glow, stronger on the sunlit side (upper left); no line at the edge.
        lit.fill(Globe.circle(center, r * 1.6), with: .radialGradient(Globe.atmosphere, center: center, startRadius: 0, endRadius: r * 1.6))
        let sunSide = CGPoint(x: cx - 0.18 * r, y: cy - 0.2 * r)
        lit.fill(Globe.circle(sunSide, r * 1.35), with: .radialGradient(Globe.sunRim, center: sunSide, startRadius: 0, endRadius: r * 1.35))
        // The ocean, lit from the upper left, with the sun's soft glint on it.
        lit.fill(
            Globe.circle(center, r),
            with: .radialGradient(Globe.ocean, center: CGPoint(x: cx - 0.45 * r, y: cy - 0.5 * r), startRadius: 0, endRadius: 1.9 * r)
        )
        let glint = CGPoint(x: cx - 0.4 * r, y: cy - 0.42 * r)
        lit.fill(Globe.circle(glint, 0.36 * r), with: .radialGradient(Globe.glint, center: glint, startRadius: 0, endRadius: 0.36 * r))
        // Dots grow as the globe nears, up to a point: the dive reads as halftone rushing past.
        let dot = min(max(r * 0.019, 0.4), 7)
        let shades = project(cx: cx, cy: cy, r: r, dot: dot)
        for s in 0..<3 where !shades[s].isEmpty {
            var g = ctx
            g.opacity = alpha * Globe.shadeAlpha[s]
            g.fill(shades[s], with: .color(s == 0 ? Globe.landLit : Globe.landColor))
        }
        // The night side: a soft shadow over the lower right, which gives the globe its roundness;
        // the city lights shine through it.
        let night = CGPoint(x: cx - 0.55 * r, y: cy - 0.6 * r)
        lit.fill(Globe.circle(center, r), with: .radialGradient(Globe.nightShade, center: night, startRadius: 0, endRadius: 2.1 * r))
        if !shades[3].isEmpty {
            var g = ctx
            g.opacity = alpha * Globe.shadeAlpha[3]
            g.fill(shades[3], with: .color(Globe.cityLight))
        }
        if let b = beacon {
            if orbits > 0.01 {
                links(ctx, cx: cx, cy: cy, r: r, alpha: alpha * orbits, t: t, visible: visible, used: used, beacon: b)
            }
            let strength = alpha * (0.55 + 0.45 * min(max(lock, 0), 1))
            ctx.fill(Globe.circle(b, haloRadius), with: .color(Globe.lightBlue.opacity(0.18 * strength)))
            ctx.fill(Globe.circle(b, 5.5), with: .color(.white.opacity(strength)))
            ctx.fill(Globe.circle(b, 2.5), with: .color(Globe.blue.opacity(strength)))
            if flare > 0 && flare < 1 {
                let k = 1 - (1 - flare) * (1 - flare)
                ctx.stroke(
                    Globe.circle(b, r * (0.04 + 0.55 * k)),
                    with: .color(Globe.lightBlue.opacity(alpha * (1 - flare))),
                    lineWidth: 3 * (1 - flare) + 0.6
                )
                ctx.fill(
                    Globe.circle(b, 34),
                    with: .radialGradient(Gradient(colors: [.white.opacity(0.8 * alpha * (1 - flare)), .clear]), center: b, startRadius: 0, endRadius: 34)
                )
            }
        }
        if orbits > 0.01 {
            satellites(ctx, back: false, cx: cx, cy: cy, r: r, alpha: alpha * orbits, t: t, visible: visible, used: used)
        }
    }

    /** Keeps when each satellite locked, and springs the beacon's halo toward its size for [used] locks. */
    private func track(used: Int, t: Double) {
        let n = min(max(used, 0), 12)
        if lockedAt.count > n { lockedAt.removeLast(lockedAt.count - n) }
        while lockedAt.count < n { lockedAt.append(t) }
        let dt = lastDrawT.map { min(max(t - $0, 0), 0.05) } ?? 0
        lastDrawT = t
        let target = 8 + 4 * Double(min(n, 4))
        // A springy bounce: about 0.45 damping, a quarter second to get there.
        let w = 16.0
        haloSpeed += (w * w * (target - haloRadius) - 2 * 0.45 * w * haloSpeed) * dt
        haloRadius = max(haloRadius + haloSpeed * dt, 2)
    }

    /** The land dots facing the viewer, one path per shade (bright day, dim day, edge or night, city light). */
    private func project(cx: Double, cy: Double, r: Double, dot: Double) -> [Path] {
        var shades = [Path](repeating: Path(), count: 4)
        let cs = cos(spin), sn = sin(spin), ct = cos(tilt), st = sin(tilt)
        let city = dot * 0.8
        for i in 0..<px.count {
            let x1 = px[i] * cs + pz[i] * sn
            let z1 = -px[i] * sn + pz[i] * cs
            let y2 = py[i] * ct - z1 * st
            let z2 = py[i] * st + z1 * ct
            if z2 <= 0 { continue }
            let light = x1 * Globe.lightX + y2 * Globe.lightY + z2 * Globe.lightZ
            let shade: Int
            if light < -0.1 && i % 3 == 0 {
                // A few lights on the night side; the rest of it stays dark.
                shade = 3
            } else if light < 0 {
                shade = 2
            } else {
                let b = (0.3 + 0.7 * light) * (0.4 + 0.6 * z2)
                shade = b > 0.5 ? 0 : (b > 0.28 ? 1 : 2)
            }
            let d = shade == 3 ? city : dot
            shades[shade].addEllipse(in: CGRect(x: cx + x1 * r - d / 2, y: cy - y2 * r - d / 2, width: d, height: d))
        }
        return shades
    }

    /** The satellites behind the globe ([back]) or in front of it: dim while in view, bright with a halo once used. */
    private func satellites(
        _ ctx: GraphicsContext,
        back: Bool,
        cx: Double,
        cy: Double,
        r: Double,
        alpha: Double,
        t: Double,
        visible: Int,
        used: Int
    ) {
        let base = min(max(r / 80, 0.55), 1.1)
        for i in 0..<min(visible, 12) {
            let k = i % 3
            let u = Double(i) * 2.3999632 + t * Globe.orbitSpeed[k]
            let front = sin(u) > 0
            if front == back { continue }
            let p = orbitPoint(k, u, cx: cx, cy: cy, r: r)
            if !front && hypot(Double(p.x) - cx, Double(p.y) - cy) < r { continue }
            let locked = i < used
            let a = alpha * (front ? 1 : 0.6) * (locked ? 1 : 0.45)
            // Nearer the viewer on the front of its orbit: a little larger.
            let scale = base * (0.8 + 0.2 * sin(u))
            if locked {
                let halo = 17 * scale
                ctx.fill(
                    Globe.circle(p, halo),
                    with: .radialGradient(
                        Gradient(stops: [
                            .init(color: Globe.blue.opacity(0.22 * a), location: 0),
                            .init(color: Globe.blue.opacity(0.14 * a), location: 0.6),
                            .init(color: .clear, location: 1),
                        ]),
                        center: p, startRadius: 0, endRadius: halo
                    )
                )
            }
            // Turned along its way round.
            let ahead = orbitPoint(k, u + 0.02, cx: cx, cy: cy, r: r)
            let angle = atan2(Double(ahead.y - p.y), Double(ahead.x - p.x))
            var g = ctx
            g.opacity = a
            g.translateBy(x: p.x, y: p.y)
            g.rotate(by: .radians(angle))
            g.scaleBy(x: scale, y: scale)
            Globe.drawSatellite(g)
        }
    }

    /**
     * From each used satellite down to the beacon, a curve bowing away from the globe's centre: a
     * glow under a fine line paling toward home. Each draws itself in 450 ms as its satellite locks.
     */
    private func links(
        _ ctx: GraphicsContext,
        cx: Double,
        cy: Double,
        r: Double,
        alpha: Double,
        t: Double,
        visible: Int,
        used: Int,
        beacon: CGPoint
    ) {
        for i in 0..<min(min(visible, 12), used) {
            let k = i % 3
            let u = Double(i) * 2.3999632 + t * Globe.orbitSpeed[k]
            let p = orbitPoint(k, u, cx: cx, cy: cy, r: r)
            if sin(u) <= 0 && hypot(Double(p.x) - cx, Double(p.y) - cy) < r { continue }
            let since = lockedAt.indices.contains(i) ? t - lockedAt[i] : 1
            let x = min(max(since / 0.45, 0), 1)
            let drawn = 1 - (1 - x) * (1 - x) * (1 - x)
            guard drawn > 0.001 else { continue }
            let mx = (Double(p.x) + Double(beacon.x)) / 2
            let my = (Double(p.y) + Double(beacon.y)) / 2
            var dx = mx - cx
            var dy = my - cy
            let len = hypot(dx, dy)
            if len > 0.001 {
                dx /= len
                dy /= len
            } else {
                dx = 0
                dy = -1
            }
            let bow = 0.3 * hypot(Double(beacon.x - p.x), Double(beacon.y - p.y))
            var curve = Path()
            curve.move(to: p)
            curve.addQuadCurve(to: beacon, control: CGPoint(x: mx + dx * bow, y: my + dy * bow))
            let line = drawn < 1 ? curve.trimmedPath(from: 0, to: drawn) : curve
            ctx.stroke(line, with: .color(Globe.blue.opacity(0.2 * alpha)), style: StrokeStyle(lineWidth: 6, lineCap: .round))
            ctx.stroke(
                line,
                with: .linearGradient(
                    Gradient(colors: [Globe.blue.opacity(0.15 * alpha), Globe.paleBlue.opacity(alpha)]),
                    startPoint: p,
                    endPoint: beacon
                ),
                style: StrokeStyle(lineWidth: 1.8, lineCap: .round)
            )
        }
    }

    /** One satellite at the origin, panels along x: body 7 × 10, a panel each side on a short boom, the dish under. */
    private static func drawSatellite(_ g: GraphicsContext) {
        g.fill(Globe.panels, with: .color(Globe.panelFill))
        g.stroke(Globe.panels, with: .color(Globe.panelEdge), lineWidth: 1)
        g.stroke(Globe.panelLines, with: .color(Globe.panelEdge), lineWidth: 0.7)
        g.stroke(Globe.boom, with: .color(Globe.satWhite), lineWidth: 1.4)
        g.fill(Globe.body, with: .color(Globe.satWhite))
        g.stroke(Globe.antenna, with: .color(Globe.satWhite), style: StrokeStyle(lineWidth: 1.2, lineCap: .round))
    }

    private static let panels: Path = {
        var p = Path()
        p.addRoundedRect(in: CGRect(x: -15, y: -4, width: 10, height: 8), cornerSize: CGSize(width: 1, height: 1))
        p.addRoundedRect(in: CGRect(x: 5, y: -4, width: 10, height: 8), cornerSize: CGSize(width: 1, height: 1))
        return p
    }()

    private static let panelLines: Path = {
        var p = Path()
        p.move(to: CGPoint(x: -10, y: -4))
        p.addLine(to: CGPoint(x: -10, y: 4))
        p.move(to: CGPoint(x: 10, y: -4))
        p.addLine(to: CGPoint(x: 10, y: 4))
        return p
    }()

    private static let boom: Path = {
        var p = Path()
        p.move(to: CGPoint(x: -5, y: 0))
        p.addLine(to: CGPoint(x: 5, y: 0))
        return p
    }()

    private static let body = Path(roundedRect: CGRect(x: -3.5, y: -5, width: 7, height: 10), cornerRadius: 1.6)

    private static let antenna: Path = {
        var p = Path()
        p.move(to: CGPoint(x: 0, y: 5))
        p.addLine(to: CGPoint(x: 0, y: 8.5))
        p.move(to: CGPoint(x: -2.5, y: 9.5))
        p.addQuadCurve(to: CGPoint(x: 2.5, y: 9.5), control: CGPoint(x: 0, y: 11.5))
        return p
    }()

    private func orbitPoint(_ k: Int, _ u: Double, cx: Double, cy: Double, r: Double) -> CGPoint {
        let a = Globe.orbitRadius[k] * r
        let lx = a * cos(u)
        let ly = a * sin(Globe.orbitInclination[k]) * sin(u)
        let c = cos(Globe.orbitNode[k])
        let s = sin(Globe.orbitNode[k])
        return CGPoint(x: cx + lx * c - ly * s, y: cy + lx * s + ly * c)
    }

    /** [p] on the unit sphere turned and tilted as the globe is now. */
    private func rotated(_ p: (x: Double, y: Double, z: Double)) -> (x: Double, y: Double, z: Double) {
        let x1 = p.x * cos(spin) + p.z * sin(spin)
        let z1 = -p.x * sin(spin) + p.z * cos(spin)
        return (x1, p.y * cos(tilt) - z1 * sin(tilt), p.y * sin(tilt) + z1 * cos(tilt))
    }

    /** [a] brought between -π and π: the shortest way to turn. */
    static func wrap(_ a: Double) -> Double {
        let tau = 2 * Double.pi
        return ((a + .pi).truncatingRemainder(dividingBy: tau) + tau).truncatingRemainder(dividingBy: tau) - .pi
    }

    private static func unit(_ latDeg: Double, _ lonDeg: Double) -> (x: Double, y: Double, z: Double) {
        let lat = latDeg * .pi / 180
        let lon = lonDeg * .pi / 180
        return (cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
    }

    private static func circle(_ c: CGPoint, _ radius: Double) -> Path {
        Path(ellipseIn: CGRect(x: Double(c.x) - radius, y: Double(c.y) - radius, width: 2 * radius, height: 2 * radius))
    }

    private static func rgb(_ hex: Int) -> Color {
        Color(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    private static let oceanLit = rgb(0x1C5DA8)
    private static let oceanMid = rgb(0x0B2B55)
    private static let oceanDark = rgb(0x020814)
    private static let atmosphereBlue = rgb(0x4DA8FF)
    private static let landColor = rgb(0xD2EAFF)
    private static let landLit = rgb(0xF2F8FF)
    private static let sunGlow = rgb(0x9FD0FF)
    private static let cityLight = rgb(0xFFB35C)
    private static let blue = rgb(0x3D8BFF)
    private static let lightBlue = rgb(0x5AA2FF)
    private static let paleBlue = rgb(0xCFE3FF)
    private static let satWhite = rgb(0xE6F0FF)
    private static let panelFill = rgb(0x1B4E8F)
    private static let panelEdge = rgb(0x9CC2F0)

    /** Bright day, dim day, edge or night, city light. */
    private static let shadeAlpha: [Double] = [0.97, 0.62, 0.22, 0.85]

    private static let atmosphere = Gradient(stops: [
        .init(color: atmosphereBlue.opacity(0.3), location: 0),
        .init(color: atmosphereBlue.opacity(0.3), location: 0.6),
        .init(color: atmosphereBlue.opacity(0.18), location: 0.66),
        .init(color: atmosphereBlue.opacity(0.05), location: 0.8),
        .init(color: .clear, location: 1),
    ])
    private static let sunRim = Gradient(stops: [
        .init(color: .clear, location: 0),
        .init(color: .clear, location: 0.62),
        .init(color: sunGlow.opacity(0.32), location: 0.72),
        .init(color: .clear, location: 1),
    ])
    private static let glint = Gradient(colors: [.white.opacity(0.2), .clear])
    private static let nightShade = Gradient(stops: [
        .init(color: .clear, location: 0),
        .init(color: .clear, location: 0.55),
        .init(color: .black.opacity(0.45), location: 0.85),
        .init(color: .black.opacity(0.7), location: 1),
    ])
    private static let ocean = Gradient(stops: [
        .init(color: oceanLit, location: 0),
        .init(color: oceanMid, location: 0.5),
        .init(color: oceanDark, location: 1),
    ])

    /** France until the first position: the globe turns home to the viewer as the satellites lock, and the beacon lights up there. */
    private static let homeLat = 46.5
    private static let homeLon = 2.5

    /** Turn of the globe while the GPS searches, in radians per second. */
    private static let spinRate = 0.32

    /** Sun from the upper left and a little ahead: the day side faces the viewer, the night side lies low right. */
    private static let lightX = -0.55
    private static let lightY = 0.55
    private static let lightZ = 0.63

    /** Rows of dots every 2.5° from 56.25° S to 76.25° N, as many per row as fit evenly around. */
    private static let lat0 = -56.25
    private static let stepDeg = 2.5
    private static let rows = 54

    /** Three orbits: size (globe radii), inclination toward the viewer and tilt on screen (radians), speed. */
    private static let orbitRadius: [Double] = [1.42, 1.7, 1.98]
    private static let orbitInclination: [Double] = [0.3, 0.5, 0.2]
    private static let orbitNode: [Double] = [-0.35, 0.3, 0.12]
    private static let orbitSpeed: [Double] = [0.55, 0.4, 0.3]

    private static let alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Land or sea at each dot, six dots per character (generated once from a global land mask:
     * a dot is land when most of the ground around it is).
     */
    private static let land = "AAAAAAAAAAAAAAAAAGAAAAAAAAAAAAABgAAAAAAAAAAAAAA4AAAAAAAAAAAAAAAwAAAAAAAAAAgAAAABgAAAAAAAAAAEAAAAAOAAAAAAAAAAQGAAAAAHwAAAAAAAAAAAIAAAAAPwAAAAAAAAABwAAAAAAA/AAAB4AAAAOPwAAAAAAA/gAAA+AAAAD/+AAAAAAAD/AAAB8AAAAH/+AAAAAAAB/gAAA/AAAAB//gAAAAAAAH/AAAD+MAAAD//AAAAAAAAH/wAAD/GAAAA//gAAAAAAAB/+AAA/8YAAAA/8AAAAAAAAH/8AAA/+wAAAA/YAAAAAAAAP/+AAA/+IAAAAHIAAAAAAAAH//gAAH/gAAAAARAAAAAAAAA//+AAA/+AAAABoNAAAAAAAAH//8AAD/4AAABgA9QAAAAAAAP//wAAH/wAAAGKHwAAAAAAAAf/8AAAf/wAAAZ0YAAAAAAAAAf/gAAA//wAABz5AAAAAAAAAAf+AAAB//4AADjgAAAAAAAAAA/wAAPv//wAICBAAAAAAAAAAP+AAA////gBgKBgAAAAAAAABHIAAP///2AMBYcAAAAAAAAB4AAAD///+AHA+CAAAAAAAAHgBAAB///3gHB+EAAAAAAAAdgQAAD///fwfH0AAAAAAAADmIAAAf///+H9+AAAAAAAAHwAAAAP//3+P//0AAAAAAAfggAAA////P///gAAAAAAH8IAAAH///f///wAAAAAAH/wAAAP+7////+AAAAAAH/+AAAD8A////8cAAAAAf/8AAALwf////kgAAAAf/+AAAcC////+RAAAAB//wAAHi+////4AAAAD//wAAHfH////kAAAB//+AAH/v////AAAAf/+QAH/////+AAAD//8AB//////AAAH/3wAV/////iAIP/nAATf///4QDH/HAAO////CB//iEAd////8P/8mAP//////+bgP///+f9I4Hn//4BmeAB/4ATHgE8AC/wAYA"
}
