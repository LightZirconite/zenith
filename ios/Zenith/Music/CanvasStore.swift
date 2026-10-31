import Foundation
import ZenithCore

/**
 * Spotify Canvas videos for the music card and the small window (CanvasFinder.kt and
 * SpotifyCanvas.kt). The video's address comes from free unofficial services the core knows, asked
 * in order (the next only when one fails: "none" is a real answer); each answer is remembered per
 * track, a video 3 days and "none" a day. When every service fails, nothing is asked for a pause
 * that doubles from 1 to 30 min. A track of another player (Apple Music) is found by its words
 * first. The MP4 (about 3.6 MB) is downloaded once into Caches/canvas and played from there; past
 * 60 MB the least recently played go. Every failure is silent: the cover stays.
 */
actor CanvasStore {
    static let shared = CanvasStore()

    private struct Memo {
        let url: String?
        let at: Date
        /** The Spotify twin of a track found by its words. */
        let trackId: String?
    }

    private struct Reply {
        let status: Int
        let body: String
    }

    private enum Lookup {
        case noVideo
        case video(String)
    }

    private static let videoTtl: TimeInterval = 3 * 24 * 3600
    private static let noneTtl: TimeInterval = 24 * 3600
    private static let firstPause: TimeInterval = 60
    private static let maxPause: TimeInterval = 30 * 60
    private static let maxMemo = 500
    private static let maxCacheBytes: Int64 = 60 * 1024 * 1024
    private static let maxVideoBytes: Int64 = 16 * 1024 * 1024
    private static let agent = "Zenith (+https://github.com/LightZirconite/zenith)"

    private var memo: [String: Memo] = [:]
    private var failedAt = Date.distantPast
    private var pause: TimeInterval = 0
    private let session: URLSession
    private let dir: URL

    init() {
        let config = URLSessionConfiguration.default
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: config)
        dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("canvas", isDirectory: true)
    }

    /** The video of [trackId] on the phone, downloaded if needed; nil without Canvas or on failure. */
    func video(_ trackId: String) async -> URL? {
        guard Self.isTrackId(trackId) else { return nil }
        let file = dir.appendingPathComponent("\(trackId).mp4")
        if Self.size(of: file) > 0 { return Self.touched(file) }
        guard let address = await find(trackId), Bridge.shared.isCanvasVideoUrl(url: address),
              let url = URL(string: address), !Task.isCancelled else { return nil }
        if Self.size(of: file) > 0 { return Self.touched(file) }
        guard await download(url, to: file) else {
            memo[trackId] = nil
            return nil
        }
        trim(keeping: file)
        return file
    }

    /**
     * The Spotify twin of a track known by its words (Apple Music), remembered like a lookup (and
     * its video under the twin's id); nil when there is none or nobody could be asked.
     */
    func search(title: String, artist: String?, album: String?, durationMs: Int64) async -> String? {
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let key = Self.key(title, artist)
        if let m = memo[key], Self.fresh(m) { return m.url == nil ? nil : m.trackId }
        if pausing() { return nil }
        let address = Bridge.shared.canvasSearchUrl(title: title, artist: artist, album: album, durationMs: durationMs)
        guard let url = URL(string: address), let reply = await get(url) else {
            failed()
            return nil
        }
        let result = Bridge.shared.canvasSearchAnswer(status: Int32(reply.status), body: reply.body)
        guard result.kind != 0 else {
            failed()
            return nil
        }
        pause = 0
        if result.kind == 3, let id = result.trackId, let video = result.url {
            remember(key, Memo(url: video, at: Date(), trackId: id))
            remember(id, Memo(url: video, at: Date(), trackId: nil))
            return id
        }
        remember(key, Memo(url: nil, at: Date(), trackId: nil))
        return nil
    }

    /** A video that would not play (named after its track): deleted, asked afresh next time. */
    func discard(_ file: URL) {
        try? FileManager.default.removeItem(at: file)
        memo[file.deletingPathExtension().lastPathComponent] = nil
    }

    // MARK: - Lookups

    /** The Canvas address of [trackId]; nil when it has none, or nobody could be asked. */
    private func find(_ trackId: String) async -> String? {
        if let m = memo[trackId], Self.fresh(m) { return m.url }
        if pausing() { return nil }
        guard let lookup = await ask(trackId) else {
            failed()
            return nil
        }
        pause = 0
        switch lookup {
        case .noVideo:
            remember(trackId, Memo(url: nil, at: Date(), trackId: nil))
            return nil
        case .video(let url):
            remember(trackId, Memo(url: url, at: Date(), trackId: nil))
            return url
        }
    }

    /** The services in order; the next one only when one fails. Nil when all failed. */
    private func ask(_ trackId: String) async -> Lookup? {
        for (index, address) in Bridge.shared.canvasUrls(trackId: trackId).enumerated() {
            guard let url = URL(string: address), let reply = await get(url) else { continue }
            let result = Bridge.shared.canvasAnswer(index: Int32(index), trackId: trackId, status: Int32(reply.status), body: reply.body)
            if result.kind == 1 { return .noVideo }
            if result.kind == 2, let video = result.url { return .video(video) }
        }
        return nil
    }

    private func pausing() -> Bool {
        pause > 0 && Date().timeIntervalSince(failedAt) < pause
    }

    private func failed() {
        pause = min(max(pause * 2, Self.firstPause), Self.maxPause)
        failedAt = Date()
    }

    private func remember(_ key: String, _ m: Memo) {
        memo[key] = m
        if memo.count > Self.maxMemo, let oldest = memo.min(by: { $0.value.at < $1.value.at })?.key {
            memo[oldest] = nil
        }
    }

    private static func fresh(_ m: Memo) -> Bool {
        Date().timeIntervalSince(m.at) < (m.url != nil ? videoTtl : noneTtl)
    }

    /** Same song, same memo: case, spaces and accents aside (CanvasSearch.key on Android). */
    private static func key(_ title: String, _ artist: String?) -> String {
        "?" + [title, artist ?? ""].map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        }.joined(separator: "|")
    }

    /** A Spotify track id: 22 base-62 characters (also a safe file name). */
    private static func isTrackId(_ s: String) -> Bool {
        s.count == 22 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    /** A service request: status and body (64 KB at most), or nil when the network failed. */
    private func get(_ url: URL) async -> Reply? {
        var request = URLRequest(url: url, timeoutInterval: 6)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(Self.agent, forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return nil }
            return Reply(status: http.statusCode, body: String(decoding: data.prefix(64 * 1024), as: UTF8.self))
        } catch {
            NSLog("Zenith canvas lookup: \(error.localizedDescription)")
            return nil
        }
    }

    // MARK: - Files

    private func download(_ url: URL, to file: URL) async -> Bool {
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue(Self.agent, forHTTPHeaderField: "User-Agent")
        do {
            let (temp, response) = try await session.download(for: request)
            defer { try? FileManager.default.removeItem(at: temp) }
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return false }
            let bytes = Self.size(of: temp)
            guard bytes > 0, bytes <= Self.maxVideoBytes else { return false }
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? FileManager.default.removeItem(at: file)
            try FileManager.default.moveItem(at: temp, to: file)
            return true
        } catch {
            NSLog("Zenith canvas download: \(error.localizedDescription)")
            return false
        }
    }

    /** Keeps the most recently played videos within 60 MB. */
    private func trim(keeping keep: URL) {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys) else { return }
        let videos = files.filter { $0.pathExtension == "mp4" }.map { file -> (URL, Date, Int64) in
            let values = try? file.resourceValues(forKeys: Set(keys))
            return (file, values?.contentModificationDate ?? .distantPast, Int64(values?.fileSize ?? 0))
        }.sorted { $0.1 > $1.1 }
        var total: Int64 = 0
        for (file, _, bytes) in videos {
            total += bytes
            if total > Self.maxCacheBytes && file.lastPathComponent != keep.lastPathComponent {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    private static func size(of file: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    /** Played again: the last to go when the cache is trimmed. */
    private static func touched(_ file: URL) -> URL {
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        return file
    }
}
