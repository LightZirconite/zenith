import MapLibre
import SwiftUI
import UIKit

/**
 * Tilted map at the top of the drive screen (port of DriveMap.kt): arrow for the car, road ahead,
 * nearby radars as mini limit signs and control itineraries as an orange dashed line. The camera
 * frames the next radar on the road when there is one; dragging or pinching looks around, and the
 * camera comes back on its own 5 s after the gesture ends (momentum included), or at once on a tap.
 * Vector tiles from OpenFreeMap (free, no key), credited on the home screen. Any map failure leaves
 * an empty black box. Callers draw their own shade over it (as Android's `overlay`).
 */
struct DriveMap: View {
    let snapshot: Snapshot?
    let radars: [RadarInfo]
    var zones: [ZoneInfo] = []
    /** Height fraction where the car arrow sits, from the top. */
    var carAt: Double = 0.45

    var body: some View {
        DriveMapSurface(snapshot: snapshot, radars: radars, zones: zones, carAt: carAt)
            .background(Color.black)
    }
}

/** Hosts the MapLibre view; every change goes through [DriveMapHolder], which skips what is unchanged. */
private struct DriveMapSurface: UIViewRepresentable {
    let snapshot: Snapshot?
    let radars: [RadarInfo]
    let zones: [ZoneInfo]
    let carAt: Double

    func makeCoordinator() -> DriveMapHolder { DriveMapHolder() }

    func makeUIView(context: Context) -> UIView { context.coordinator.create() }

    func updateUIView(_ uiView: UIView, context: Context) {
        let holder = context.coordinator
        // Before following: the first camera already puts the car at its place.
        holder.frame(carAt)
        if let snapshot { holder.show(snapshot) }
        holder.showRadars(radars, active: Set(snapshot?.alerts.map { $0.radar.id } ?? []))
        holder.showZones(zones)
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: DriveMapHolder) {
        coordinator.release()
    }
}

/** The map view, telling when its size changes (rotation, first layout): the camera is placed again. */
private final class DriveMapView: MLNMapView {
    var onResize: (() -> Void)?
    private var laidOut = CGSize.zero

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != laidOut else { return }
        laidOut = bounds.size
        onResize?()
    }
}

/** Owns the map view and its style objects; every call is a no-op for the style until it is loaded. */
private final class DriveMapHolder: NSObject, MLNMapViewDelegate, UIGestureRecognizerDelegate {
    private static let styleURL = "https://tiles.openfreemap.org/styles/dark"
    private static let accent = mapColor(0x0A84FF)
    private static let zoneColor = mapColor(0xFF9F0A)
    private static let puck = "puck"
    private static let srcMe = "me"
    private static let srcAhead = "ahead"
    private static let srcRadars = "radars"
    private static let srcZones = "zones"
    /** The camera comes back this long after a look around. */
    private static let lookAround: TimeInterval = 5
    /** If the end of a gesture is never reported. */
    private static let gestureMax: TimeInterval = 30
    private static let gestures: MLNCameraChangeReason = [
        .gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom, .gestureTilt,
    ]

    private var map: DriveMapView?
    private var style: MLNStyle?
    private var carAt = 0.45
    private var zoom: Double?
    private var bearing: Double?
    private var placed = false
    private var last: Snapshot?
    private var radars: [RadarInfo] = []
    private var active: Set<String> = []
    private var zones: [ZoneInfo] = []

    /** The driver is looking around: the camera stops following until then. */
    private var freeUntil: TimeInterval = 0
    private var gesture = false

    private var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    func create() -> UIView {
        guard let url = URL(string: Self.styleURL) else { return Self.blackBox() }
        let map = DriveMapView(frame: .zero, styleURL: url)
        map.backgroundColor = .black
        map.automaticallyAdjustsContentInset = false
        map.contentInset = .zero
        // Credited on the home screen ("Cartes © OpenFreeMap © OpenStreetMap").
        map.logoView.isHidden = true
        map.attributionButton.isHidden = true
        map.compassView.compassVisibility = .hidden
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.delegate = self
        // A tap brings the camera back at once (a double tap still zooms).
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
        tap.delegate = self
        for other in map.gestureRecognizers ?? [] {
            if let double = other as? UITapGestureRecognizer, double.numberOfTapsRequired > 1 {
                tap.require(toFail: double)
            }
        }
        map.addGestureRecognizer(tap)
        map.onResize = { [weak self] in self?.resized() }
        self.map = map
        return map
    }

    func release() {
        map?.delegate = nil
        map?.onResize = nil
        map = nil
        style = nil
    }

    /** Moves the car to another place in the view. */
    func frame(_ at: Double) {
        guard at != carAt else { return }
        carAt = at
        if let last { follow(last) }
    }

    /** A new fix. */
    func show(_ s: Snapshot) {
        guard s != last else { return }
        follow(s)
    }

    func showRadars(_ list: [RadarInfo], active ids: Set<String>) {
        guard list != radars || ids != active else { return }
        radars = list
        active = ids
        (style?.source(withIdentifier: Self.srcRadars) as? MLNShapeSource)?.shape = radarShape()
    }

    func showZones(_ list: [ZoneInfo]) {
        guard list != zones else { return }
        zones = list
        (style?.source(withIdentifier: Self.srcZones) as? MLNShapeSource)?.shape = zoneShape()
    }

    /**
     * Camera behind the car, seen fairly high from above so the road far ahead shows. Zooms out
     * with speed, and further when needed to frame the next radar on the road (up to 4 km).
     */
    private func follow(_ s: Snapshot) {
        last = s
        let heading = s.headingDeg ?? bearing ?? 0
        (style?.source(withIdentifier: Self.srcMe) as? MLNShapeSource)?.shape = Self.point(s.position, ["h": heading])
        (style?.source(withIdentifier: Self.srcAhead) as? MLNShapeSource)?.shape = (Self.line(s.ahead) as MLNShape?) ?? Self.empty()
        guard let map, map.bounds.width > 0, map.bounds.height > 0, now >= freeUntil else { return }

        let kmh = Double(s.speedKmh ?? 0)
        let speedZoom = 16.3 - (min(max(kmh - 30, 0), 100) / 100) * 2.3
        let height = Double(map.bounds.height)
        // Car at [carAt] of the map height: the speed counter covers the faded part below.
        let aheadPts = height * carAt
        let framed = s.alerts.first?.distanceM ?? (s.upcoming?.roadName != nil ? s.upcoming?.distanceM : nil)
        var fitZoom: Double?
        if let d = framed, d >= 50, d <= 4_000 {
            // Ground metres per point at zoom z: 40075016.686 cos(lat) / (512 · 2^z); the tilt
            // stretches the far half of the view, roughly ×1.7 at 40°.
            fitZoom = log2(40_075_016.686 * cos(s.position.lat * .pi / 180) * aheadPts * 1.7 / (512 * d * 1.25))
        }
        let target = min(max(min(speedZoom, fitZoom ?? speedZoom), 12.5), 17)
        let z = zoom.map { $0 + (target - $0) * 0.3 } ?? target
        zoom = z
        // Zoomed out for a far radar: look more from above.
        let tilt = 42 - (min(max(speedZoom - z, 0), 2) / 2) * 12
        bearing = heading
        // Padding at the bottom lifts the car above the middle, at the top lowers it below.
        let padding = UIEdgeInsets(
            top: CGFloat(height * max(0, 2 * carAt - 1)),
            left: 0,
            bottom: CGFloat(height * max(0, 1 - 2 * carAt)),
            right: 0
        )
        // The same size MapLibre converts the altitude back with.
        let altitude = MLNAltitudeForZoomLevel(z, CGFloat(tilt), s.position.lat, map.frame.size)
        let camera = MLNMapCamera(lookingAtCenter: s.position.coordinate, altitude: altitude, pitch: CGFloat(tilt), heading: heading)
        // The first position lands at once: never a zoom from the whole world at start.
        let first = !placed
        placed = true
        map.setCamera(
            camera,
            withDuration: first ? 0 : 1,
            animationTimingFunction: CAMediaTimingFunction(name: .linear),
            edgePadding: padding,
            completionHandler: nil
        )
    }

    private func resized() {
        if let last { follow(last) }
    }

    // MARK: Looking around

    @objc private func tapped() {
        // Looking around: the tap brings the camera back on the car, with its sound.
        if gesture || now < freeUntil { UiSound.play(.recenter) }
        gesture = false
        freeUntil = 0
        if let last { follow(last) }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    /** A drag or a pinch frees the camera; it comes back on its own after the gesture, never in the middle of it. */
    func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        guard !reason.isDisjoint(with: Self.gestures) else { return }
        gesture = true
        freeUntil = now + Self.gestureMax
    }

    /** Called once the gesture and its momentum are over. */
    func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        guard gesture else { return }
        let touching = mapView.gestureRecognizers?.contains { $0.state == .began || $0.state == .changed } ?? false
        guard !touching else { return }
        gesture = false
        freeUntil = now + Self.lookAround
    }

    // MARK: Style

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        guard style.source(withIdentifier: Self.srcMe) == nil else { return }
        setUp(style)
        self.style = style
        if let last { follow(last) }
    }

    /** No style, no tiles: the black box stays. */
    func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
        mapView.isHidden = true
    }

    private func setUp(_ s: MLNStyle) {
        restyle(s)
        s.setImage(Self.puckImage(), forName: Self.puck)

        let zonesSource = MLNShapeSource(identifier: Self.srcZones, shape: zoneShape(), options: nil)
        let aheadSource = MLNShapeSource(identifier: Self.srcAhead, shape: Self.empty(), options: nil)
        s.addSource(zonesSource)
        s.addSource(aheadSource)
        let zoneLine = MLNLineStyleLayer(identifier: "zones", source: zonesSource)
        zoneLine.lineColor = NSExpression(forConstantValue: Self.zoneColor)
        zoneLine.lineWidth = NSExpression(forConstantValue: 4)
        zoneLine.lineOpacity = NSExpression(forConstantValue: 0.55)
        zoneLine.lineDashPattern = NSExpression(forConstantValue: [2, 1.5])
        zoneLine.lineJoin = NSExpression(forConstantValue: "round")
        s.addLayer(zoneLine)

        let radarsSource = MLNShapeSource(identifier: Self.srcRadars, shape: radarShape(), options: nil)
        let meSource = MLNShapeSource(identifier: Self.srcMe, shape: Self.empty(), options: nil)
        s.addSource(radarsSource)
        s.addSource(meSource)

        let glow = MLNLineStyleLayer(identifier: "ahead-glow", source: aheadSource)
        glow.lineColor = NSExpression(forConstantValue: Self.accent)
        glow.lineWidth = NSExpression(forConstantValue: 16)
        glow.lineOpacity = NSExpression(forConstantValue: 0.16)
        glow.lineBlur = NSExpression(forConstantValue: 8)
        glow.lineCap = NSExpression(forConstantValue: "round")
        glow.lineJoin = NSExpression(forConstantValue: "round")
        s.addLayer(glow)

        let ahead = MLNLineStyleLayer(identifier: "ahead", source: aheadSource)
        ahead.lineColor = NSExpression(forConstantValue: Self.accent)
        ahead.lineWidth = NSExpression(forConstantValue: 5)
        ahead.lineOpacity = NSExpression(forConstantValue: 0.85)
        ahead.lineCap = NSExpression(forConstantValue: "round")
        ahead.lineJoin = NSExpression(forConstantValue: "round")
        s.addLayer(ahead)

        let radarColor = NSExpression(mglJSONObject: ["to-color", ["get", "c"]] as [Any])
        let halo = MLNCircleStyleLayer(identifier: "radar-halo", source: radarsSource)
        halo.circleColor = radarColor
        halo.circleOpacity = NSExpression(forConstantValue: 0.25)
        halo.circleRadius = NSExpression(mglJSONObject: ["+", ["get", "r"], 6] as [Any])
        halo.circleBlur = NSExpression(forConstantValue: 0.6)
        s.addLayer(halo)

        let disc = MLNCircleStyleLayer(identifier: "radar", source: radarsSource)
        disc.circleColor = NSExpression(forConstantValue: UIColor.white)
        disc.circleRadius = NSExpression(mglJSONObject: ["get", "r"] as [Any])
        disc.circleStrokeColor = radarColor
        disc.circleStrokeWidth = NSExpression(forConstantValue: 3)
        s.addLayer(disc)

        let limit = MLNSymbolStyleLayer(identifier: "radar-limit", source: radarsSource)
        limit.text = NSExpression(mglJSONObject: ["get", "l"] as [Any])
        limit.textFontNames = NSExpression(forConstantValue: ["Noto Sans Bold"])
        limit.textFontSize = NSExpression(forConstantValue: 10)
        limit.textColor = NSExpression(forConstantValue: UIColor.black)
        limit.textAllowsOverlap = NSExpression(forConstantValue: true)
        limit.textIgnoresPlacement = NSExpression(forConstantValue: true)
        s.addLayer(limit)

        let meGlow = MLNCircleStyleLayer(identifier: "me-glow", source: meSource)
        meGlow.circleColor = NSExpression(forConstantValue: Self.accent)
        meGlow.circleRadius = NSExpression(forConstantValue: 22)
        meGlow.circleOpacity = NSExpression(forConstantValue: 0.22)
        meGlow.circleBlur = NSExpression(forConstantValue: 1)
        meGlow.circlePitchAlignment = NSExpression(forConstantValue: "map")
        s.addLayer(meGlow)

        // Navigation arrow lying flat on the road, pointing the travel direction.
        let me = MLNSymbolStyleLayer(identifier: "me", source: meSource)
        me.iconImageName = NSExpression(forConstantValue: Self.puck)
        me.iconRotation = NSExpression(mglJSONObject: ["get", "h"] as [Any])
        me.iconRotationAlignment = NSExpression(forConstantValue: "map")
        me.iconPitchAlignment = NSExpression(forConstantValue: "map")
        me.iconAllowsOverlap = NSExpression(forConstantValue: true)
        me.iconIgnoresPlacement = NSExpression(forConstantValue: true)
        s.addLayer(me)
    }

    /** Darker, calmer roads and a black background, so the map melts into the app. */
    private func restyle(_ s: MLNStyle) {
        let colors: [(String, UInt32)] = [
            ("background", 0x000000),
            ("water", 0x0B1220),
            ("building", 0x0E0E11),
            ("highway_minor", 0x24242A),
            ("highway_major_casing", 0x1A1A1F),
            ("highway_major_inner", 0x34343C),
            ("highway_major_subtle", 0x34343C),
            ("highway_motorway_casing", 0x1E1E24),
            ("highway_motorway_inner", 0x45454F),
            ("highway_motorway_subtle", 0x45454F),
        ]
        for (id, rgb) in colors {
            guard let found = s.layer(withIdentifier: id) else { continue }
            let color = NSExpression(forConstantValue: mapColor(rgb))
            switch found {
            case let layer as MLNBackgroundStyleLayer: layer.backgroundColor = color
            case let layer as MLNFillStyleLayer: layer.fillColor = color
            case let layer as MLNLineStyleLayer: layer.lineColor = color
            default: break
            }
        }
        for layer in s.layers {
            let id = layer.identifier
            if id.hasPrefix("boundary") || id.hasPrefix("place_country") || id.hasPrefix("place_state") {
                layer.isVisible = false
            }
        }
    }

    // MARK: Shapes

    private func radarShape() -> MLNShape {
        let features: [MLNShape & MLNFeature] = radars.map { r in
            Self.point(r.position, [
                "c": radarColorHex(r.kind),
                "l": r.limitKmh.map(String.init) ?? "",
                "r": active.contains(r.id) ? 12 : 9,
            ])
        }
        return MLNShapeCollectionFeature(shapes: features)
    }

    private func zoneShape() -> MLNShape {
        let lines: [MLNShape & MLNFeature] = zones.compactMap { Self.line($0.points) }
        return MLNShapeCollectionFeature(shapes: lines)
    }

    private static func point(_ p: GeoPoint, _ attributes: [String: Any]) -> MLNPointFeature {
        let feature = MLNPointFeature()
        feature.coordinate = p.coordinate
        feature.attributes = attributes
        return feature
    }

    private static func line(_ points: [GeoPoint]) -> MLNPolylineFeature? {
        guard points.count >= 2 else { return nil }
        let coordinates = points.map(\.coordinate)
        return MLNPolylineFeature(coordinates: coordinates, count: UInt(coordinates.count))
    }

    private static func empty() -> MLNShape {
        MLNShapeCollectionFeature(shapes: [] as [MLNShape & MLNFeature])
    }

    private static func blackBox() -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        return view
    }

    /** Arrow pointing up: blue body, white outline, soft blue glow. */
    private static func puckImage() -> UIImage {
        let side: CGFloat = 40
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image { context in
            let path = UIBezierPath()
            path.move(to: CGPoint(x: side * 0.5, y: side * 0.12))
            path.addLine(to: CGPoint(x: side * 0.82, y: side * 0.86))
            path.addLine(to: CGPoint(x: side * 0.5, y: side * 0.68))
            path.addLine(to: CGPoint(x: side * 0.18, y: side * 0.86))
            path.close()
            let cg = context.cgContext
            cg.saveGState()
            cg.setShadow(offset: .zero, blur: side * 0.08, color: accent.withAlphaComponent(CGFloat(0xAA) / 255).cgColor)
            accent.setFill()
            path.fill()
            cg.restoreGState()
            path.lineJoinStyle = .round
            path.lineWidth = side * 0.07
            UIColor.white.setStroke()
            path.stroke()
        }
    }
}

/** 0xRRGGBB as an opaque colour, for the style. */
private func mapColor(_ rgb: UInt32) -> UIColor {
    UIColor(
        red: CGFloat((rgb >> 16) & 0xFF) / 255,
        green: CGFloat((rgb >> 8) & 0xFF) / 255,
        blue: CGFloat(rgb & 0xFF) / 255,
        alpha: 1
    )
}
