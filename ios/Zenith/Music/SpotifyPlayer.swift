import Foundation
import UIKit
import ZenithCore

/**
 * What the driver's Spotify plays, read from the Web API while a drive runs (the Spotify part of
 * NowPlaying.kt: on iOS no app can read another one's music). Read about every 3 s while it plays
 * with Zenith in front, 5 s behind Waze or Maps, 10 s when nothing plays; a 429 waits as long as
 * Spotify asks. Commands and the heart go through the same API (the commands need Premium); after
 * one, the player is read again at once. Main thread.
 */
final class SpotifyPlayer {
    /** The item Spotify reported, and where it was when read. */
    struct Track: Equatable {
        /** Stable for the item: its URI. */
        let key: String
        /** Spotify track id; nil for an episode or a local file (no Canvas, no heart). */
        let trackId: String?
        let title: String
        let artist: String?
        let album: String?
        let imageUrl: URL?
        let durationMs: Int64
        let positionMs: Int64
        let readAt: Date
        let playing: Bool

        func position(at now: Date) -> Int64 {
            let moved = playing ? Int64(max(0, now.timeIntervalSince(readAt)) * 1000) : 0
            return durationMs > 0 ? min(max(0, positionMs + moved), durationMs) : max(0, positionMs + moved)
        }

        /** The same item at [positionMs] now, playing or not. */
        func at(_ positionMs: Int64, playing: Bool) -> Track {
            Track(key: key, trackId: trackId, title: title, artist: artist, album: album, imageUrl: imageUrl,
                  durationMs: durationMs, positionMs: positionMs, readAt: Date(), playing: playing)
        }
    }

    private(set) var track: Track?
    private(set) var art: UIImage?
    /** In the driver's Liked Songs; nil while unknown or for an episode. */
    private(set) var liked: Bool?
    /** Last read that found it playing: the most recent player wins a tie. */
    private(set) var lastPlayingAt = Date.distantPast
    /** When to read the player next. */
    private(set) var due = Date.distantPast
    var onChange: () -> Void = {}
    /** A command asks for a read sooner than planned. */
    var onPoke: () -> Void = {}

    private var poked = false
    private var artUrl: URL?
    private var covers: [URL: UIImage] = [:]

    /** Reads what plays and plans the next read. */
    @MainActor
    func poll() async {
        poked = false
        let auth = SpotifyAuth.shared
        guard auth.connected else {
            set(nil)
            schedule(10)
            return
        }
        let sent = Date()
        guard let answer = await auth.request("GET", "/v1/me/player/currently-playing", query: [("additional_types", "track,episode")]) else {
            // Offline: the card keeps what it had.
            schedule(10)
            return
        }
        // The position was taken halfway through the request.
        let readAt = sent.addingTimeInterval(Date().timeIntervalSince(sent) / 2)
        switch answer.status {
        case 200:
            set(answer.data.isEmpty ? nil : Self.track(from: answer.data, readAt: readAt))
        case 204:
            set(nil)
        case 429:
            schedule(answer.retryAfter ?? 30)
            return
        default:
            NSLog("Zenith Spotify: currently playing \(answer.status)")
            schedule(30)
            return
        }
        let front = UIApplication.shared.applicationState == .active
        schedule(track?.playing == true ? (front ? 3 : 5) : 10)
    }

    func playPause() {
        guard let t = track else { return }
        let play = !t.playing
        // Shown at once; the next read confirms.
        set(t.at(t.position(at: Date()), playing: play))
        command("PUT", play ? "/v1/me/player/play" : "/v1/me/player/pause")
    }

    func next() {
        command("POST", "/v1/me/player/next")
    }

    func previous() {
        command("POST", "/v1/me/player/previous")
    }

    func seek(byMs delta: Int64) {
        guard let t = track, t.durationMs > 0 else { return }
        let to = min(max(0, t.position(at: Date()) + delta), t.durationMs)
        set(t.at(to, playing: t.playing))
        command("PUT", "/v1/me/player/seek", query: [("position_ms", String(to))])
    }

    /** Adds the track to Liked Songs or takes it out; put back if Spotify refuses. */
    func toggleLike() {
        guard let id = track?.trackId, let was = liked else { return }
        liked = !was
        onChange()
        Task { @MainActor in
            let answer = await SpotifyAuth.shared.request(was ? "DELETE" : "PUT", "/v1/me/library", query: [("uris", "spotify:track:\(id)")])
            guard !(answer.map { (200..<300).contains($0.status) } ?? false), self.track?.trackId == id else { return }
            self.liked = was
            self.onChange()
        }
    }

    /** Read again within [seconds], whatever was planned. */
    func poke(after seconds: TimeInterval) {
        poked = true
        due = min(due, Date().addingTimeInterval(seconds))
        onPoke()
    }

    /** End of a drive: nothing kept, the first read of the next one comes at once. */
    func stop() {
        track = nil
        art = nil
        artUrl = nil
        liked = nil
        poked = false
        due = .distantPast
    }

    /** Another account (or none): what the last one played goes. */
    func reset() {
        stop()
        onChange()
    }

    // MARK: -

    private func schedule(_ seconds: TimeInterval) {
        let next = Date().addingTimeInterval(seconds)
        due = poked ? min(due, next) : next
        poked = false
    }

    private func set(_ new: Track?) {
        if new?.playing == true { lastPlayingAt = Date() }
        let old = track
        guard new != old else { return }
        track = new
        if new?.trackId != old?.trackId {
            liked = nil
            if let id = new?.trackId { loadLiked(id) }
        }
        if new?.imageUrl != artUrl { loadArt(new?.imageUrl) }
        onChange()
    }

    private func command(_ method: String, _ path: String, query: [(String, String)] = []) {
        Task { @MainActor in
            guard let answer = await SpotifyAuth.shared.request(method, path, query: query) else { return }
            if (200..<300).contains(answer.status) {
                SpotifyAuth.shared.notePremium(true)
            } else if answer.status == 403 && answer.reason == "PREMIUM_REQUIRED" {
                SpotifyAuth.shared.notePremium(false)
                self.onChange()
            } else {
                NSLog("Zenith Spotify: \(path) \(answer.status) \(answer.reason ?? "")")
            }
            // Spotify takes a moment to switch tracks: read again shortly after.
            self.poke(after: 0.6)
        }
    }

    private func loadLiked(_ id: String) {
        Task { @MainActor in
            guard let answer = await SpotifyAuth.shared.request("GET", "/v1/me/library/contains", query: [("uris", "spotify:track:\(id)")]),
                  answer.status == 200,
                  let flags = try? JSONDecoder().decode([Bool].self, from: answer.data),
                  let first = flags.first,
                  self.track?.trackId == id else { return }
            self.liked = first
            self.onChange()
        }
    }

    private func loadArt(_ url: URL?) {
        artUrl = url
        guard let url else {
            art = nil
            return
        }
        if let cover = covers[url] {
            art = cover
            return
        }
        // Never the last track's cover under the new title.
        art = nil
        Task { @MainActor in
            guard let cover = await Self.download(url) else { return }
            if self.covers.count >= 8 { self.covers.removeAll() }
            self.covers[url] = cover
            guard self.artUrl == url else { return }
            self.art = cover
            self.onChange()
        }
    }

    private static func download(_ url: URL) async -> UIImage? {
        guard let reply = try? await URLSession.shared.data(from: url),
              (reply.1 as? HTTPURLResponse)?.statusCode == 200,
              let image = UIImage(data: reply.0) else { return nil }
        // Decoded once here, small: the card and the blurred backdrop need no more.
        let side: CGFloat = 300
        let scale = min(1, side / max(1, max(image.size.width, image.size.height)))
        return image.preparingThumbnail(of: CGSize(width: image.size.width * scale, height: image.size.height * scale)) ?? image
    }

    /** The item of a currently-playing answer; nil for an ad or an unreadable answer. */
    private static func track(from data: Data, readAt: Date) -> Track? {
        guard let now = try? SpotifyJSON.decoder.decode(CurrentlyPlaying.self, from: data),
              let item = now.item, let name = item.name, !name.isEmpty else { return nil }
        let artists = (item.artists ?? []).compactMap(\.name).filter { !$0.isEmpty }.joined(separator: ", ")
        return Track(
            key: item.uri ?? item.id ?? name,
            trackId: Bridge.shared.spotifyTrackId(value: item.uri),
            title: name,
            artist: artists.isEmpty ? item.show?.name : artists,
            album: item.album?.name,
            imageUrl: image(item.album?.images ?? item.images ?? item.show?.images ?? []),
            durationMs: max(0, item.durationMs ?? 0),
            positionMs: max(0, now.progressMs ?? 0),
            readAt: readAt,
            playing: now.isPlaying ?? false
        )
    }

    /** The smallest cover still sharp on the card (Spotify gives 640, 300 and 64 px). */
    private static func image(_ images: [CurrentlyPlaying.Image]) -> URL? {
        let sized = images.compactMap { i in URL(string: i.url).map { ($0, i.width ?? 0) } }
        return sized.filter { $0.1 >= 200 }.min { $0.1 < $1.1 }?.0 ?? sized.max { $0.1 < $1.1 }?.0
    }
}

/** GET /v1/me/player/currently-playing, the fields the card uses (a track or an episode). */
private struct CurrentlyPlaying: Decodable {
    struct Image: Decodable {
        let url: String
        let width: Int?
    }

    struct Named: Decodable {
        let name: String?
        let images: [Image]?
    }

    struct Item: Decodable {
        let id: String?
        let uri: String?
        let name: String?
        let durationMs: Int64?
        let artists: [Named]?
        let album: Named?
        /** An episode's own picture. */
        let images: [Image]?
        /** An episode's podcast. */
        let show: Named?
    }

    let progressMs: Int64?
    let isPlaying: Bool?
    let item: Item?
}
