import Foundation
import Observation

/** What started the running drive. */
enum DriveOrigin {
    /** The Start button. */
    case hand
    /** The hidden simulated drive. */
    case demo
    /** The phone started riding in a vehicle (motion + significant location changes). */
    case motion
}

/**
 * Satellites for the take-off. iOS gives no satellite count: it is read from the accuracy as the
 * fix tightens (one "satellite" per step), so the flight still locks on one by one.
 */
struct GpsInfo: Equatable {
    var used = 0
    var visible = 0
    var accuracyM: Double?
    var lastFix: Date?
}

/** What the data layer has loaded. */
struct DataStatus: Equatable {
    var radarCount = 0
    var zoneCount = 0
    var radarsUpdated: Date?
    var tilesLoaded = 0
    var loading = false
    /** The road tiles around the car are in (or known unavailable): the take-off may land. */
    var roadsReady = false
    var radarError: String?
    var tileError: String?

    var error: String? { tileError ?? radarError }
}

/**
 * Live state of the drive, shared by the engine and every screen (drive screen, small window,
 * Live Activity). Main thread only: the engine hands its results over with DispatchQueue.main.
 */
@Observable
final class DriveModel {
    static let shared = DriveModel()

    private(set) var running = false
    private(set) var snapshot: Snapshot?
    private(set) var gps = GpsInfo()
    private(set) var data = DataStatus()
    /** Radars around the car, for the map. */
    private(set) var nearbyRadars: [RadarInfo] = []
    /** Control itineraries around the car, for the map. */
    private(set) var nearbyZones: [ZoneInfo] = []
    /** Speed straight from each fix, before the road horizon: the big number. Nil in the demo. */
    private(set) var liveSpeed: Int?
    private(set) var demo = false
    private(set) var origin: DriveOrigin = .hand
    /**
     * The music's room chosen by a tap (true: the Canvas up the screen) until the speed agrees with
     * it or for 3 min at most (Cockpit); nil: the speed decides.
     */
    private(set) var musicChoice: Bool?
    @ObservationIgnored private var musicChoiceAt = Date.distantPast

    /** Speed shown in big: the live one, else the snapshot's (demo, tunnel). */
    var shownSpeed: Int? { liveSpeed ?? snapshot?.speedKmh }

    func chooseMusic(_ big: Bool) {
        musicChoiceAt = Date()
        musicChoice = big
    }

    // MARK: Engine side

    func started(_ origin: DriveOrigin) {
        snapshot = nil
        gps = GpsInfo()
        demo = origin == .demo
        self.origin = origin
        musicChoice = nil
        liveSpeed = nil
        running = true
    }

    func update(_ s: Snapshot) {
        snapshot = s
        let age = Int64(Date().timeIntervalSince(musicChoiceAt) * 1000)
        musicChoice = Cockpit.keepChoice(musicChoice, speedKmh: s.speedKmh, ageMs: age)
        gps.accuracyM = s.accuracyM
        gps.lastFix = Date()
    }

    func setLiveSpeed(_ kmh: Int?) {
        if liveSpeed != kmh { liveSpeed = kmh }
    }

    func setSatellites(used: Int, visible: Int) {
        if gps.used != used || gps.visible != visible {
            gps.used = used
            gps.visible = visible
        }
    }

    func setNearby(radars: [RadarInfo], zones: [ZoneInfo]) {
        if radars != nearbyRadars { nearbyRadars = radars }
        if zones != nearbyZones { nearbyZones = zones }
    }

    func setData(_ status: DataStatus) {
        if status != data { data = status }
    }

    func stopped() {
        running = false
        snapshot = nil
        liveSpeed = nil
        demo = false
        origin = .hand
        nearbyRadars = []
        nearbyZones = []
    }
}
