import CoreLocation
import Foundation
import UIKit
import UserNotifications
import ZenithCore

/**
 * Runs a drive, like Android's DriveService: GPS (as fast as the receiver goes) → core
 * DrivingSession → shared state, sounds, voice, GPX, Live Activity. Keeps alerting with the screen
 * off or Waze / Maps in front (background location + audio). The pipeline runs on its own queue,
 * one fix after the other, about once a second; the big number comes from each fix on the main
 * thread at once. A failing step is skipped (the Kotlin bridge catches): a drive never stops on an
 * error. Start / stop and audio on the main thread.
 */
final class DriveEngine: NSObject, CLLocationManagerDelegate {
    static let shared = DriveEngine()

    private let model = DriveModel.shared
    private let settings = SettingsStore.shared
    private let queue = DispatchQueue(label: "zenith-drive", qos: .userInitiated)

    // Main thread.
    private(set) var running = false
    private var origin = DriveOrigin.hand
    private var manager: CLLocationManager?
    private var backgroundActivity: CLBackgroundActivitySession?
    private var data: DriveData?
    private var tones: TonePlayer?
    private var speaker: Speaker?
    private var weatherTask: Task<Void, Never>?
    private var liveSpeed = Bridge.shared.speedStabilizer()
    private var lastPipelineFixMs: Int64 = 0
    private var lastDangerBeepMs: Int64 = 0
    private var ending = false
    private var generation = 0
    private var vehicleLeftAt: Date?
    private var appliedProfile: (Bool, Bool)?

    // Drive queue.
    private var session: DrivingSession?
    private var recorder: TripRecorder?
    private var parking: ParkDetector?
    private var radarData: RadarData?
    private var radarIndex: RadarIndex?
    /** Road network of the drive (the tiles around the car, or the demo's). */
    private var roadNetwork: () -> RoadNetwork? = { nil }
    private var queueGeneration = 0
    private var lastFixTimeMs: Int64 = 0
    private var lastFixWall: Date?
    private var lastNearbyMs: Int64 = 0
    private var coastTimer: DispatchSourceTimer?

    /** Fixes handed to the drive queue and not yet taken up. */
    private let queuedLock = NSLock()
    private var queuedFixes = 0

    /** The pipeline's own pace, whatever the receiver's rate (its rules expect about a second). */
    private static let pipelineEveryMs: Int64 = 900
    private static let weatherEvery: TimeInterval = 600
    private static let falseStartMs: Int64 = 5 * 60_000
    /** A vehicle exit received while fast stops the drive at the first slow fix within this delay. */
    private static let vehicleExitGrace: TimeInterval = 180

    // MARK: - Start / stop

    func start(origin: DriveOrigin = .hand) {
        guard !running else { return }
        running = true
        self.origin = origin
        ending = false
        vehicleLeftAt = nil
        generation += 1
        let gen = generation
        lastPipelineFixMs = 0
        lastDangerBeepMs = 0
        appliedProfile = nil
        liveSpeed = Bridge.shared.speedStabilizer()
        model.started(origin)
        tones = TonePlayer()
        speaker = Speaker()
        UIApplication.shared.isIdleTimerDisabled = true
        UIDevice.current.isBatteryMonitoringEnabled = true
        VolumeRaiser.shared.raise()
        MusicCenter.shared.activate()
        if settings.liveActivity { LiveActivityManager.shared.start(demo: origin == .demo) }
        if origin == .demo { startDemo(gen) } else { startLive(gen) }
        // Started by itself (car, riding), Zenith in the background: a notification says so and opens it.
        if (origin == .car || origin == .motion) && UIApplication.shared.applicationState != .active { notifyStarted() }
    }

    private func notifyStarted() {
        let content = UNMutableNotificationContent()
        content.title = "Zenith est en route"
        content.body = "Les alertes radars sont actives. Touche pour ouvrir la petite fenêtre."
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "drive-start", content: content, trigger: nil))
    }

    func stop() {
        guard running else { return }
        running = false
        generation += 1
        manager?.stopUpdatingLocation()
        manager?.delegate = nil
        manager = nil
        backgroundActivity?.invalidate()
        backgroundActivity = nil
        weatherTask?.cancel()
        weatherTask = nil
        let demo = origin == .demo
        queue.async { [self] in
            queueGeneration += 1
            coastTimer?.cancel()
            coastTimer = nil
            recorder?.close()
            recorder = nil
            session = nil
            parking = nil
            roadNetwork = { nil }
            radarData = nil
            radarIndex = nil
            lastFixWall = nil
        }
        data?.onStatus = nil
        data = nil
        speaker?.shutdown()
        speaker = nil
        tones?.release()
        tones = nil
        AudioFocus.shared.shutdown()
        VolumeRaiser.shared.restore()
        MusicCenter.shared.deactivate()
        LiveActivityManager.shared.end()
        // The next drive starts from the weather again, not from this drive's rain.
        if !demo { settings.raining = false }
        UIApplication.shared.isIdleTimerDisabled = false
        model.stopped()
    }

    /** The car disconnected: ends the drive only if [origin] started it (a drive started by hand goes on). */
    func stopIfStartedBy(_ origin: DriveOrigin) {
        if running && self.origin == origin { stop() }
    }

    /** The phone left the vehicle: a drive it started ends once the car is slow, now or within 3 min. */
    func motionExit() {
        guard running, origin == .motion else { return }
        if (model.snapshot?.speedKmh ?? 0) < 10 { stop() } else { vehicleLeftAt = Date() }
    }

    /**
     * Battery saver: a drive ends by itself once over (car parked, walking away, indoors without
     * GPS), when the setting allows.
     */
    private func endByItself() {
        // A drive started by the car ends when the car disconnects instead.
        guard running, origin != .car, !ending, settings.autoStop else { return }
        ending = true
        NSLog("Zenith: drive over, stopping by itself")
        stop()
    }

    // MARK: - Live drive

    private func startLive(_ gen: Int) {
        let live = DriveData()
        data = live
        live.onStatus = { [weak self] s in self?.model.setData(s) }
        let sess = Bridge.shared.liveSession { [weak live] in live?.network }
        let park = Bridge.shared.parkDetector(startMs: Self.nowMs(), falseStartMs: origin == .motion ? Self.falseStartMs : 0)
        let rec = TripRecorder()
        queue.async { [self] in
            queueGeneration = gen
            session = sess
            parking = park
            recorder = rec
            roadNetwork = { [weak live] in live?.network }
            lastNearbyMs = 0
        }
        live.loadRadars { [weak self] pack in
            guard let self else { return }
            self.queue.async {
                guard self.queueGeneration == gen, let session = self.session else { return }
                Bridge.shared.setRadars(session: session, data: pack)
                self.radarData = pack
                self.radarIndex = Bridge.shared.radarIndex(data: pack)
                self.lastNearbyMs = 0
            }
        }
        watchWeather()

        let m = CLLocationManager()
        m.delegate = self
        m.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        m.activityType = .automotiveNavigation
        m.distanceFilter = kCLDistanceFilterNone
        m.pausesLocationUpdatesAutomatically = false
        if m.authorizationStatus == .authorizedAlways || m.authorizationStatus == .authorizedWhenInUse {
            m.allowsBackgroundLocationUpdates = true
            m.showsBackgroundLocationIndicator = true
        }
        // Road tiles around the last known position load while the GPS gets its first fix.
        if let last = m.location { live.ensureTiles(lat: last.coordinate.latitude, lon: last.coordinate.longitude) }
        manager = m
        // Keeps the updates going in the background, even with the "while using" permission.
        backgroundActivity = CLBackgroundActivitySession()
        m.startUpdatingLocation()

        // Tunnel: no fix for 2.5 s → the car keeps moving along the road (DrivingSession.coast).
        queue.async { [self] in
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now() + 1, repeating: 1)
            t.setEventHandler { [weak self] in self?.tick(gen) }
            coastTimer = t
            t.resume()
        }
    }

    /** Drive queue, every second. */
    private func tick(_ gen: Int) {
        guard queueGeneration == gen, let session else { return }
        if let wall = lastFixWall {
            let lost = Date().timeIntervalSince(wall)
            if lost > 2.5, let core = Bridge.shared.coast(session: session, nowMs: lastFixTimeMs + Int64(lost * 1000)) {
                show(Snapshot(core), gen)
            }
        }
        if let p = parking, Bridge.shared.parkOnSilence(p: p, nowMs: Self.nowMs()) {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.generation == gen else { return }
                self.endByItself()
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for loc in locations { handle(loc) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("Zenith location: \(error)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        if status == .authorizedAlways || status == .authorizedWhenInUse {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
        }
    }

    /** Main thread, each fix. */
    private func handle(_ loc: CLLocation) {
        guard running, origin != .demo, loc.horizontalAccuracy >= 0 else { return }
        let gen = generation
        let timeMs = Int64(loc.timestamp.timeIntervalSince1970 * 1000)
        let fix = Bridge.shared.fix(
            timeMs: timeMs,
            lat: loc.coordinate.latitude,
            lon: loc.coordinate.longitude,
            accuracyM: loc.horizontalAccuracy,
            speedMps: loc.speed,
            courseDeg: loc.course
        )
        satellites(accuracy: loc.horizontalAccuracy)
        // The big number first, from this very fix: no wait for the road horizon on the drive queue.
        let shown = Bridge.shared.liveSpeed(stabilizer: liveSpeed, fix: fix, filteredMps: Double(model.snapshot?.speedKmh ?? 0) / 3.6)
        model.setLiveSpeed(shown >= 0 ? Int(shown) : nil)
        if let left = vehicleLeftAt {
            if Date().timeIntervalSince(left) > Self.vehicleExitGrace {
                vehicleLeftAt = nil
            } else if (model.shownSpeed ?? 0) < 10 {
                stop()
                return
            }
        }
        // The receiver may give several fixes a second: the rest keeps its pace of about one a second.
        // A clock that jumped back restarts the pace rather than blocking it.
        let sinceLast = timeMs - lastPipelineFixMs
        if sinceLast >= 0 && sinceLast < Self.pipelineEveryMs { return }
        lastPipelineFixMs = timeMs
        data?.ensureTiles(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
        applyProfile()
        let wall = Date()
        let battery = UIDevice.current.batteryState
        let charging = battery == .charging || battery == .full
        queuedLock.lock()
        queuedFixes += 1
        queuedLock.unlock()
        queue.async { [self] in
            guard queueGeneration == gen else { return }
            recorder?.add(fix)
            lastFixTimeMs = timeMs
            lastFixWall = wall
            queuedLock.lock()
            queuedFixes -= 1
            let latest = queuedFixes == 0
            queuedLock.unlock()
            // The drive queue fell behind: an older fix is skipped for the newest.
            if latest, let session, let core = Bridge.shared.process(session: session, fix: fix) {
                show(Snapshot(core), gen)
            }
            if let p = parking {
                let s = lastShown
                let over = Bridge.shared.parkOnFix(
                    p: p, timeMs: Int64(wall.timeIntervalSince1970 * 1000),
                    lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                    speedKmh: Int32(s?.speedKmh ?? -1), roadClass: s?.roadClass, charging: charging
                )
                if over {
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.generation == gen else { return }
                        self.endByItself()
                    }
                }
            }
        }
    }

    /** iOS gives no satellite count: the take-off counts one per accuracy step reached. */
    private func satellites(accuracy: Double) {
        let used: Int
        switch accuracy {
        case ...10: used = 4
        case ...25: used = 3
        case ...65: used = 2
        case ...150: used = 1
        default: used = 0
        }
        let best = max(used, model.gps.used)
        model.setSatellites(used: best, visible: max(model.gps.visible, best + 3))
    }

    /** Licence and rain rules follow the settings during the drive. */
    private func applyProfile() {
        let p = (settings.probationary, settings.raining)
        if let a = appliedProfile, a == p { return }
        appliedProfile = p
        queue.async { [self] in
            if let session { Bridge.shared.setProfile(session: session, probationary: p.0, raining: p.1) }
        }
    }

    /** Last snapshot shown (drive queue). */
    private var lastShown: Snapshot?

    /** Drive queue: hands a snapshot to the screens and the sounds. */
    private func show(_ s: Snapshot, _ gen: Int) {
        lastShown = s
        var nearby: ([RadarInfo], [ZoneInfo], [RoadLine])?
        if lastNearbyMs == 0 || s.timeMs - lastNearbyMs >= 3_000 {
            lastNearbyMs = s.timeMs
            let radars = radarIndex.map { Bridge.shared.nearbyRadars(index: $0, lat: s.position.lat, lon: s.position.lon, radiusM: 3_000).map(RadarInfo.init) } ?? []
            let zones = radarData.map { Bridge.shared.nearbyZones(data: $0, lat: s.position.lat, lon: s.position.lon, marginM: 4_000).map(ZoneInfo.init) } ?? []
            let roads = Bridge.shared.roadsNear(network: roadNetwork(), lat: s.position.lat, lon: s.position.lon, radiusM: 600).map(RoadLine.init)
            nearby = (radars, zones, roads)
        }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.generation == gen, self.running else { return }
            self.model.update(s)
            if let n = nearby {
                self.model.setNearby(radars: n.0, zones: n.1)
                self.model.setRoads(n.2)
            }
            s.events.forEach(self.announce)
            self.dangerBeep(s)
            if self.settings.liveActivity { LiveActivityManager.shared.update(s) }
        }
    }

    // MARK: - Sounds

    /** Tone first, then the spoken text right after it. Main thread. */
    private func announce(_ e: DriveEvent) {
        guard let tones else { return }
        let tone: Tone?
        switch e.kind {
        case .approach: tone = (e.radar?.measuresSpeed ?? true) ? .approach : .chime
        case .sectionStarted: tone = .approach
        case .brake: tone = .brake
        case .overspeed: tone = e.level == .warn ? .overspeed : nil
        case .passed: tone = e.wouldFlash ? .passed : nil
        case .sectionEnded: tone = .passed
        case .limitChanged, .zoneLeft: tone = nil
        case .zoneEntered: tone = .chime
        }
        if let tone { tones.play(tone) }
        guard settings.voice, let text = e.text else { return }
        // What changes the driver's speed now goes first, dropping older announcements still queued.
        let urgent = e.kind == .brake || (e.kind == .overspeed && e.level == .warn) || (e.kind == .passed && e.wouldFlash)
        speaker?.say(text, delayMs: tone.map { tones.durationMs($0) + 80 } ?? 0, urgent: urgent, pauseMusic: settings.pauseMusic)
    }

    /** Like Coyote: while over the limit near a radar, beeps get faster as it gets closer. */
    private func dangerBeep(_ s: Snapshot) {
        guard let alert = s.alerts.first(where: { $0.overLimit }), let tones else { return }
        if speaker?.speaking == true { return } // never over the voice
        let interval: Int64 = alert.distanceM < 200 ? 500 : alert.distanceM < 500 ? 1_000 : 2_000
        if s.timeMs - lastDangerBeepMs >= interval {
            lastDangerBeepMs = s.timeMs
            tones.play(.danger)
        }
    }

    // MARK: - Weather

    /** Rain mode from the weather: checked every 10 minutes at the car's position (rounded to ~10 km), spoken when it changes. */
    private func watchWeather() {
        let rain = Bridge.shared.rainDetector()
        weatherTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let (autoRain, raining, position) = await MainActor.run { (self.settings.autoRain, self.settings.raining, self.model.snapshot?.position) }
                // Setting turned off while it rained: the limits come back at once.
                if !autoRain && raining { await MainActor.run { self.settings.raining = false } }
                guard autoRain, let position else {
                    try? await Task.sleep(nanoseconds: 20_000_000_000)
                    continue
                }
                var read = false
                if let url = URL(string: Bridge.shared.weatherUrl(lat: position.lat, lon: position.lon)),
                   let answer = try? await URLSession.shared.data(from: url),
                   (answer.1 as? HTTPURLResponse)?.statusCode == 200,
                   let text = String(data: answer.0, encoding: .utf8) {
                    let r = Bridge.shared.weatherReport(rain: rain, text: text)
                    if r >= 0 {
                        read = true
                        let now = r == 1
                        if now != raining { await MainActor.run { self.rainChanged(now) } }
                    }
                }
                try? await Task.sleep(nanoseconds: UInt64((read ? Self.weatherEvery : 120) * 1_000_000_000))
            }
        }
    }

    private func rainChanged(_ raining: Bool) {
        guard running else { return }
        settings.raining = raining
        tones?.play(.chime)
        if settings.voice, let tones {
            speaker?.say(
                raining ? "Pluie, limites abaissées" : "Fin de la pluie, limites normales",
                delayMs: tones.durationMs(.chime) + 80,
                pauseMusic: settings.pauseMusic
            )
        }
    }

    // MARK: - Demo

    /** Hidden demo: replays the core's DemoDrive in real time through the same pipeline, then stops. */
    private func startDemo(_ gen: Int) {
        guard let url = Bundle.main.url(forResource: "roads_85_19", withExtension: "json"),
              let text = try? String(contentsOf: url, encoding: .utf8),
              let scenario = Bridge.shared.demo(tileText: text) else {
            NSLog("Zenith: demo tile missing")
            stop()
            return
        }
        let sess = Bridge.shared.demoSession(s: scenario)
        let pack = Bridge.shared.demoData(s: scenario)
        let fixes = Bridge.shared.demoFixes(s: scenario)
        let t0 = Self.nowMs()
        queue.async { [self] in
            queueGeneration = gen
            session = sess
            radarData = pack
            radarIndex = Bridge.shared.radarIndex(data: pack)
            let network = Bridge.shared.demoNetwork(s: scenario)
            roadNetwork = { network }
            lastNearbyMs = 0
        }
        var lastAt: Int64 = 0
        for fix in fixes {
            let offset = Bridge.shared.fixTimeMs(fix: fix)
            lastAt = max(lastAt, offset)
            queue.asyncAfter(deadline: .now() + Double(offset) / 1000) { [weak self] in
                guard let self, self.queueGeneration == gen, let session = self.session else { return }
                let at = Bridge.shared.retime(fix: fix, timeMs: t0 + offset)
                if let core = Bridge.shared.process(session: session, fix: at) { self.show(Snapshot(core), gen) }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(lastAt) / 1000 + 3) { [weak self] in
            guard let self, self.generation == gen else { return }
            self.stop()
        }
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
