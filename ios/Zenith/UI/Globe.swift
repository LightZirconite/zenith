import SwiftUI

/**
 * The Earth at the heart of the tunnel, drawn frame by frame: land as a field of dots on a dark
 * ocean lit from the upper left, a halo of atmosphere, city lights on the night side, and the
 * satellites in view on three tilted orbits, each with a short trail. As they lock, the globe slows
 * and turns home (the driver's position once known, France before) to the viewer; a beacon lights up
 * there and every locked satellite beams down to it, a pulse of data running along each beam.
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
        let center = CGPoint(x: cx, y: cy)
        let h = rotated(home)
        let beacon: CGPoint? = h.z > 0.12 ? CGPoint(x: cx + h.x * r, y: cy - h.y * r) : nil
        if orbits > 0.01 {
            satellites(ctx, back: true, cx: cx, cy: cy, r: r, alpha: alpha * orbits, t: t, visible: visible, used: used, beacon: nil)
        }
        var lit = ctx
        lit.opacity = alpha
        // Atmosphere: a blue halo just outside the edge.
        lit.fill(Globe.circle(center, r * 1.5), with: .radialGradient(Globe.atmosphere, center: center, startRadius: 0, endRadius: r * 1.5))
        lit.fill(
            Globe.circle(center, r),
            with: .radialGradient(Globe.ocean, center: CGPoint(x: cx - 0.45 * r, y: cy - 0.5 * r), startRadius: 0, endRadius: 1.9 * r)
        )
        // Dots grow as the globe nears, up to a point: the dive reads as halftone rushing past.
        let dot = min(max(r * 0.019, 0.4), 7)
        let shades = project(cx: cx, cy: cy, r: r, dot: dot)
        for s in 0..<4 where !shades[s].isEmpty {
            var g = ctx
            g.opacity = alpha * Globe.shadeAlpha[s]
            g.fill(shades[s], with: .color(s == 3 ? Globe.cityLight : Globe.landColor))
        }
        // Light catching the rim on the sun's side.
        lit.stroke(
            Globe.circle(center, r),
            with: .linearGradient(Globe.rim, startPoint: CGPoint(x: cx - r, y: cy - r), endPoint: CGPoint(x: cx + 0.6 * r, y: cy + 0.6 * r)),
            lineWidth: 1.3
        )
        if let b = beacon {
            let color = lock >= 0.999 ? Globe.locked : Globe.seeking
            let strength = alpha * (0.3 + 0.7 * lock)
            for w in 0..<3 {
                let f = (t * 0.55 + Double(w) / 3).truncatingRemainder(dividingBy: 1)
                ctx.stroke(Globe.circle(b, r * (0.03 + 0.3 * f)), with: .color(color.opacity(0.55 * strength * (1 - f))), lineWidth: 1.2)
            }
            ctx.fill(
                Globe.circle(b, 12),
                with: .radialGradient(Gradient(colors: [color.opacity(0.7 * strength), .clear]), center: b, startRadius: 0, endRadius: 12)
            )
            ctx.fill(Globe.circle(b, 2.6), with: .color(.white.opacity(strength)))
            if flare > 0 && flare < 1 {
                let k = 1 - (1 - flare) * (1 - flare)
                ctx.stroke(
                    Globe.circle(b, r * (0.04 + 0.55 * k)),
                    with: .color(color.opacity(alpha * (1 - flare))),
                    lineWidth: 3 * (1 - flare) + 0.6
                )
                ctx.fill(
                    Globe.circle(b, 34),
                    with: .radialGradient(Gradient(colors: [.white.opacity(0.8 * alpha * (1 - flare)), .clear]), center: b, startRadius: 0, endRadius: 34)
                )
            }
        }
        if orbits > 0.01 {
            satellites(ctx, back: false, cx: cx, cy: cy, r: r, alpha: alpha * orbits, t: t, visible: visible, used: used, beacon: beacon)
        }
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

    /** Orbit lines and satellites behind the globe ([back]) or in front of it, with their beams. */
    private func satellites(
        _ ctx: GraphicsContext,
        back: Bool,
        cx: Double,
        cy: Double,
        r: Double,
        alpha: Double,
        t: Double,
        visible: Int,
        used: Int,
        beacon: CGPoint?
    ) {
        var faint = ctx
        faint.opacity = 0.1 * alpha
        for k in 0..<3 {
            let a = Globe.orbitRadius[k] * r
            let b = a * sin(Globe.orbitInclination[k])
            let c = cos(Globe.orbitNode[k]), s = sin(Globe.orbitNode[k])
            var arc = Path()
            for i in 0...Globe.arcSteps {
                // Behind: the upper half on screen, from 9 o'clock over the top; in front, the lower half.
                let u = (back ? Double.pi : 0) + Double.pi * Double(i) / Double(Globe.arcSteps)
                let lx = a * cos(u), ly = b * sin(u)
                let p = CGPoint(x: cx + lx * c - ly * s, y: cy + lx * s + ly * c)
                if i == 0 { arc.move(to: p) } else { arc.addLine(to: p) }
            }
            faint.stroke(arc, with: .color(.white), lineWidth: 1)
        }
        for i in 0..<min(visible, 12) {
            let k = i % 3
            let u = Double(i) * 2.3999632 + t * Globe.orbitSpeed[k]
            let front = sin(u) > 0
            if front == back { continue }
            let p = orbitPoint(k, u, cx: cx, cy: cy, r: r)
            if !front && hypot(Double(p.x) - cx, Double(p.y) - cy) < r { continue }
            let locked = i < used
            let color = locked ? Globe.locked : Globe.seeking
            let a = alpha * (front ? 1 : 0.45)
            var previous = p
            for s in 1...7 {
                let us = u - Double(s) * 0.05
                let q = orbitPoint(k, us, cx: cx, cy: cy, r: r)
                let hidden = sin(us) <= 0 && hypot(Double(q.x) - cx, Double(q.y) - cy) < r
                if !hidden {
                    var trail = Path()
                    trail.move(to: previous)
                    trail.addLine(to: q)
                    ctx.stroke(
                        trail,
                        with: .color(color.opacity(a * 0.45 * (1 - Double(s) / 8))),
                        style: StrokeStyle(lineWidth: 1.8 - Double(s) * 0.18, lineCap: .round)
                    )
                }
                previous = q
            }
            ctx.fill(Globe.circle(p, 10), with: .radialGradient(Gradient(colors: [color.opacity(0.45 * a), .clear]), center: p, startRadius: 0, endRadius: 10))
            let blink = locked ? 1 : 0.65 + 0.35 * sin(t * 4 + Double(i))
            ctx.fill(Globe.circle(p, 2.3), with: .color(color.opacity(a * blink)))
            if locked, let beacon {
                var beam = Path()
                beam.move(to: p)
                beam.addLine(to: beacon)
                ctx.stroke(
                    beam,
                    with: .linearGradient(
                        Gradient(colors: [Globe.locked.opacity(0.5 * a), Globe.locked.opacity(0.08 * a)]),
                        startPoint: p,
                        endPoint: beacon
                    ),
                    lineWidth: 1.2
                )
                let f = (t * 0.8 + Double(i) * 0.37).truncatingRemainder(dividingBy: 1)
                let q = CGPoint(x: Double(p.x) + Double(beacon.x - p.x) * f, y: Double(p.y) + Double(beacon.y - p.y) * f)
                ctx.fill(Globe.circle(q, 1.8), with: .color(.white.opacity(a * 0.9 * (1 - f * f))))
            }
        }
    }

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
    private static let cityLight = rgb(0xFFB35C)
    private static let locked = rgb(0x30D158)
    private static let seeking = rgb(0xE6F0FF)

    /** Bright day, dim day, edge or night, city light. */
    private static let shadeAlpha: [Double] = [0.95, 0.6, 0.22, 0.75]

    private static let atmosphere = Gradient(stops: [
        .init(color: atmosphereBlue.opacity(0.35), location: 0),
        .init(color: atmosphereBlue.opacity(0.35), location: 0.64),
        .init(color: atmosphereBlue.opacity(0.22), location: 0.69),
        .init(color: atmosphereBlue.opacity(0.06), location: 0.8),
        .init(color: .clear, location: 1),
    ])
    private static let ocean = Gradient(stops: [
        .init(color: oceanLit, location: 0),
        .init(color: oceanMid, location: 0.5),
        .init(color: oceanDark, location: 1),
    ])
    private static let rim = Gradient(colors: [.white.opacity(0.55), atmosphereBlue.opacity(0.18), .clear])

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

    /** Segments of each half orbit line. */
    private static let arcSteps = 32

    private static let alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Land or sea at each dot, six dots per character (generated once from a global land mask:
     * a dot is land when most of the ground around it is).
     */
    private static let land = "AAAAAAAAAAAAAAAAAGAAAAAAAAAAAAABgAAAAAAAAAAAAAA4AAAAAAAAAAAAAAAwAAAAAAAAAAgAAAABgAAAAAAAAAAEAAAAAOAAAAAAAAAAQGAAAAAHwAAAAAAAAAAAIAAAAAPwAAAAAAAAABwAAAAAAA/AAAB4AAAAOPwAAAAAAA/gAAA+AAAAD/+AAAAAAAD/AAAB8AAAAH/+AAAAAAAB/gAAA/AAAAB//gAAAAAAAH/AAAD+MAAAD//AAAAAAAAH/wAAD/GAAAA//gAAAAAAAB/+AAA/8YAAAA/8AAAAAAAAH/8AAA/+wAAAA/YAAAAAAAAP/+AAA/+IAAAAHIAAAAAAAAH//gAAH/gAAAAARAAAAAAAAA//+AAA/+AAAABoNAAAAAAAAH//8AAD/4AAABgA9QAAAAAAAP//wAAH/wAAAGKHwAAAAAAAAf/8AAAf/wAAAZ0YAAAAAAAAAf/gAAA//wAABz5AAAAAAAAAAf+AAAB//4AADjgAAAAAAAAAA/wAAPv//wAICBAAAAAAAAAAP+AAA////gBgKBgAAAAAAAABHIAAP///2AMBYcAAAAAAAAB4AAAD///+AHA+CAAAAAAAAHgBAAB///3gHB+EAAAAAAAAdgQAAD///fwfH0AAAAAAAADmIAAAf///+H9+AAAAAAAAHwAAAAP//3+P//0AAAAAAAfggAAA////P///gAAAAAAH8IAAAH///f///wAAAAAAH/wAAAP+7////+AAAAAAH/+AAAD8A////8cAAAAAf/8AAALwf////kgAAAAf/+AAAcC////+RAAAAB//wAAHi+////4AAAAD//wAAHfH////kAAAB//+AAH/v////AAAAf/+QAH/////+AAAD//8AB//////AAAH/3wAV/////iAIP/nAATf///4QDH/HAAO////CB//iEAd////8P/8mAP//////+bgP///+f9I4Hn//4BmeAB/4ATHgE8AC/wAYA"
}
