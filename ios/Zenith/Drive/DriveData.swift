import Foundation
import ZenithCore

/**
 * Offline data built by the CI and published on the GitHub "data" release, as on Android:
 * radars.json (whole France) and 0.5° road tiles with speed limits, downloaded around the car and
 * cached on the phone, so alerts keep working without network. Loading runs on its own queue; the
 * road network is read by the drive queue under a lock.
 */
final class DriveData {
    private static let base = "https://github.com/LightZirconite/zenith/releases/download/data"
    private static let day: TimeInterval = 24 * 3600
    private static let retry: TimeInterval = 60
    /** radars.json is rebuilt several times a day (new official cameras, Lufop). */
    private static let radarsMaxAge: TimeInterval = 4 * 3600

    private let queue = DispatchQueue(label: "zenith-data")
    private let lock = NSLock()
    private let dir: URL
    private let tileDir: URL
    private let radarFile: URL
    private let manifestFile: URL
    private let schemaFile: URL
    private let http: URLSession

    // Data queue only.
    private var tiles: [String: [RoadSegment]] = [:]
    private var pending = Set<String>()
    private var failedAt: [String: Date] = [:]
    private var lastWanted: [String] = []
    private var availableTiles: [String: Int64?]?
    private var status = DataStatus()

    private var _network: RoadNetwork?
    /** Current road network around the car (any thread). */
    var network: RoadNetwork? {
        lock.lock()
        defer { lock.unlock() }
        return _network
    }

    /** Called on the main thread when the status changes. */
    var onStatus: ((DataStatus) -> Void)?

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = support.appendingPathComponent("data", isDirectory: true)
        tileDir = dir.appendingPathComponent("tiles", isDirectory: true)
        radarFile = dir.appendingPathComponent("radars.json")
        manifestFile = dir.appendingPathComponent("manifest.json")
        schemaFile = tileDir.appendingPathComponent("schema")
        try? FileManager.default.createDirectory(at: tileDir, withIntermediateDirectories: true)
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 120
        config.waitsForConnectivity = false
        http = URLSession(configuration: config)
    }

    /** Loads cached radars and zones now, refreshes them from GitHub when older than 4 h. */
    func loadRadars(_ onLoaded: @escaping (RadarData) -> Void) {
        queue.async { [self] in
            let exists = FileManager.default.fileExists(atPath: radarFile.path)
            if exists { publishRadars(onLoaded) }
            if !exists || age(radarFile) > Self.radarsMaxAge {
                if download("radars.json", to: radarFile) {
                    publishRadars(onLoaded)
                } else if !exists {
                    // With a cached list the drive goes on with it: nothing to warn about.
                    change { $0.radarError = "Données radars indisponibles" }
                }
            }
        }
    }

    /** Makes sure the road tiles around the point are loaded; cheap to call on every fix. */
    func ensureTiles(lat: Double, lon: Double) {
        queue.async { [self] in
            let flat = Bridge.shared.tilesAround(lat: lat, lon: lon, marginM: 5_000).map { Int32($0.intValue) }
            var wanted: [(Int32, Int32)] = []
            var i = 0
            while i + 1 < flat.count {
                wanted.append((flat[i], flat[i + 1]))
                i += 2
            }
            let names = wanted.map { Bridge.shared.tileFileName(i: $0.0, j: $0.1) }
            lastWanted = names
            let now = Date()
            let missing = zip(names, wanted).filter { name, _ in
                tiles[name] == nil && !pending.contains(name) && now.timeIntervalSince(failedAt[name] ?? .distantPast) > Self.retry
            }
            if missing.isEmpty {
                let ready = settled(names)
                if status.roadsReady != ready { change { $0.roadsReady = ready } }
                return
            }
            change { $0.roadsReady = false; $0.loading = true }
            let available = availableTiles ?? loadManifest()
            var failed: String?
            for (name, _) in missing {
                pending.insert(name)
                let file = tileDir.appendingPathComponent(name)
                if let segments = loadTile(name, file: file, available: available) {
                    tiles[name] = segments
                    failedAt[name] = nil
                } else {
                    failedAt[name] = Date()
                    failed = "Données tuile \(name) indisponibles"
                }
                pending.remove(name)
            }
            // Keep a ring of neighbours in memory, drop tiles far behind.
            var keep = Set<String>()
            for (ti, tj) in wanted {
                for di in -1...1 { for dj in -1...1 { keep.insert(Bridge.shared.tileFileName(i: ti + Int32(di), j: tj + Int32(dj))) } }
            }
            tiles = tiles.filter { keep.contains($0.key) }
            let net = Bridge.shared.network(tiles: Array(tiles.values))
            lock.lock()
            _network = net
            lock.unlock()
            let ready = settled(lastWanted)
            let count = tiles.count
            // A tile that failed once and came back (network back) no longer shows an error.
            change { $0.tilesLoaded = count; $0.loading = false; $0.tileError = failed; $0.roadsReady = ready }
        }
    }

    /** One tile: cached, refreshed when the manifest gives another size, parsed; nil on failure. */
    private func loadTile(_ name: String, file: URL, available: [String: Int64?]?) -> [RoadSegment]? {
        if let available, available[name] == nil { return [] }
        let exists = FileManager.default.fileExists(atPath: file.path)
        if !exists {
            guard download(name, to: file) else { return nil }
        } else {
            // A size different from the manifest's: the tile was rebuilt (weekly), fetch the new one.
            let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? -1
            let outdated: Bool
            if let known = available?[name], let expected = known { outdated = expected != size } else { outdated = age(file) > 30 * Self.day }
            // Offline: the cached tile still does.
            if outdated { _ = download(name, to: file) }
        }
        guard let gz = try? Data(contentsOf: file), let raw = Gunzip.inflate(gz), let text = String(data: raw, encoding: .utf8),
              let segments = Bridge.shared.tile(text: text) else {
            // A damaged cached tile would fail forever: fetched again on the next try.
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        return segments
    }

    /** Each tile is loaded, or failed and waits for its retry (offline): nothing more to wait for. */
    private func settled(_ names: [String]) -> Bool {
        names.allSatisfy { (tiles[$0] != nil || failedAt[$0] != nil) && !pending.contains($0) }
    }

    private func loadManifest() -> [String: Int64?]? {
        if !FileManager.default.fileExists(atPath: manifestFile.path) || age(manifestFile) > Self.day {
            // Without it every tile is simply tried: a failing tile says so itself.
            _ = download("manifest.json", to: manifestFile)
        }
        guard let data = try? Data(contentsOf: manifestFile),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = json["tiles"] as? [String: Any] else { return nil }
        dropOutdatedTiles(schema: (json["schema"] as? Int) ?? 1)
        var out: [String: Int64?] = [:]
        for (name, size) in list { out[name] = (size as? NSNumber)?.int64Value }
        availableTiles = out
        return out
    }

    /** Tiles of an older content version are deleted once, so they come back new. */
    private func dropOutdatedTiles(schema: Int) {
        let cached = (try? String(contentsOf: schemaFile, encoding: .utf8)).flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 1
        guard schema > cached else { return }
        let files = (try? FileManager.default.contentsOfDirectory(at: tileDir, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.lastPathComponent.hasSuffix(".json.gz") { try? FileManager.default.removeItem(at: f) }
        try? String(schema).write(to: schemaFile, atomically: true, encoding: .utf8)
    }

    private func publishRadars(_ onLoaded: (RadarData) -> Void) {
        guard let text = try? String(contentsOf: radarFile, encoding: .utf8), let pack = Bridge.shared.radarData(text: text) else {
            change { $0.radarError = "Données radars indisponibles" }
            return
        }
        let modified = (try? FileManager.default.attributesOfItem(atPath: radarFile.path)[.modificationDate] as? Date) ?? nil
        let radars = Int(Bridge.shared.radarCount(data: pack))
        let zones = Int(Bridge.shared.zoneCount(data: pack))
        change {
            $0.radarCount = radars
            $0.zoneCount = zones
            $0.radarsUpdated = modified
            $0.radarError = nil
        }
        onLoaded(pack)
    }

    private func change(_ edit: (inout DataStatus) -> Void) {
        edit(&status)
        let s = status
        DispatchQueue.main.async { [weak self] in self?.onStatus?(s) }
    }

    private func age(_ url: URL) -> TimeInterval {
        let modified = (try? FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate] as? Date) ?? .distantPast
        return Date().timeIntervalSince(modified)
    }

    /** Blocking download on the data queue; true when [target] was replaced. */
    private func download(_ name: String, to target: URL) -> Bool {
        guard let url = URL(string: "\(Self.base)/\(name)") else { return false }
        let done = DispatchSemaphore(value: 0)
        var ok = false
        let task = http.downloadTask(with: url) { tmp, response, error in
            defer { done.signal() }
            guard error == nil, let tmp, (response as? HTTPURLResponse)?.statusCode == 200 else {
                NSLog("Zenith data \(name): \(error?.localizedDescription ?? "HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")")
                return
            }
            let fm = FileManager.default
            try? fm.removeItem(at: target)
            ok = (try? fm.moveItem(at: tmp, to: target)) != nil
        }
        task.resume()
        done.wait()
        return ok
    }
}
