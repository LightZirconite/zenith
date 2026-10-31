import Foundation
import ZenithCore

/**
 * Records the drive as a GPX file, like on Android: points are appended every 30 s (written over
 * the closing tags, which follow again), so the file is always a whole GPX and never rewritten.
 * The 10 latest trips are kept. Drive queue only.
 */
final class TripRecorder {
    private static let keep = 10
    private let file: URL
    private var handle: FileHandle?
    private var pending = ""
    private var lastFlush = Date()
    private let footer = Data(Bridge.shared.gpxFooter().utf8)

    init() {
        let dir = Self.directory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        let name = "trajet_\(f.string(from: Date()))"
        file = dir.appendingPathComponent("\(name).gpx")
        let start = Data(Bridge.shared.gpxHeader(name: name).utf8) + footer
        FileManager.default.createFile(atPath: file.path, contents: start)
        handle = try? FileHandle(forUpdating: file)
        Self.prune()
    }

    func add(_ fix: GpsFix) {
        pending += Bridge.shared.gpxPoint(fix: fix)
        if Date().timeIntervalSince(lastFlush) >= 30 { flush() }
    }

    func close() {
        flush()
        try? handle?.close()
        handle = nil
    }

    private func flush() {
        lastFlush = Date()
        guard !pending.isEmpty, let handle else { return }
        do {
            let end = try handle.seekToEnd()
            try handle.seek(toOffset: end - UInt64(footer.count))
            try handle.write(contentsOf: Data(pending.utf8) + footer)
            pending = ""
        } catch {
            NSLog("Zenith trip: \(error)")
        }
    }

    static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("trips", isDirectory: true)
    }

    /** The latest recorded trip, to share from the settings. */
    static func latestTrip() -> URL? { trips().first }

    private static func trips() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return files.filter { $0.pathExtension == "gpx" }.sorted { $0.lastPathComponent > $1.lastPathComponent }
    }

    private static func prune() {
        for old in trips().dropFirst(keep) { try? FileManager.default.removeItem(at: old) }
    }
}
