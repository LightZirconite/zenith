import Foundation
import Observation
import UIKit
import ZenithCore

/** The player a track comes from: Spotify through the Web API, Apple Music through the system player. */
enum MusicSource: Equatable { case spotify, appleMusic }

/**
 * Where a track was at [updatedAt], moving at [speed] while it plays (core PlaybackProgress):
 * enough to draw the progress line between two reads of the player.
 */
struct PlaybackProgress: Equatable {
    let positionMs: Int64
    let updatedAt: Date
    let speed: Double
    let durationMs: Int64

    func position(at now: Date, playing: Bool) -> Int64 {
        let moved = playing ? Int64(max(0, now.timeIntervalSince(updatedAt)) * 1000 * max(0, speed)) : 0
        return min(max(0, positionMs + moved), durationMs)
    }

    func fraction(at now: Date, playing: Bool) -> Double {
        durationMs > 0 ? Double(position(at: now, playing: playing)) / Double(durationMs) : 0
    }
}

/** The track on the music card. */
struct MediaInfo: Equatable {
    /** Stable for a track (animations, Canvas). */
    let id: String
    let title: String
    let artist: String?
    let album: String?
    let art: UIImage?
    let playing: Bool
    let source: MusicSource
    /** Spotify track id (Spotify, or an Apple Music track matched on Spotify): its Canvas. */
    let spotifyTrackId: String?
    /** Nil when no duration or position is known. */
    let progress: PlaybackProgress?
    /** Heart button: liked or not; nil when the player offers none. */
    let liked: Bool?
    /** Previous / play-pause / next work (Spotify needs Premium for them through the Web API). */
    let canControl: Bool
}

/**
 * Now playing, for the drive screen, the small window and the Live Activity (NowPlaying.kt for iOS,
 * where no app can read another one's music): Spotify through the Web API with the driver's
 * account, Apple Music through the system player, both followed while a drive runs. The card
 * follows the core's PlayerChoice (what plays wins, else the last one that played) and the
 * controls go to that player. The track's Canvas is looked up and downloaded here, once for every
 * screen: an Apple Music track gets the one of its Spotify twin. Main thread.
 */
@Observable
final class MusicCenter {
    static let shared = MusicCenter()

    private(set) var media: MediaInfo? = nil
    /** Local file of the current track's Canvas video, once downloaded. */
    private(set) var canvasFile: URL? = nil

    private let spotify = SpotifyPlayer()
    private let apple = AppleMusicPlayer()
    @ObservationIgnored private var active = false
    /** Each drive's loop knows when a newer one replaced it. */
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var nap: Task<Void, Never>?
    @ObservationIgnored private var appleDue = Date.distantPast
    @ObservationIgnored private var chosen: MusicSource?
    @ObservationIgnored private var canvasKey: String?
    @ObservationIgnored private var canvasTask: Task<Void, Never>?
    /** Spotify twins of Apple Music tracks (by MediaInfo id), found by the Canvas search. */
    @ObservationIgnored private var twins: [String: String] = [:]

    private static let spotifyPackage = "com.spotify.music"
    private static let applePackage = "com.apple.android.music"

    init() {
        spotify.onChange = { [weak self] in self?.publish() }
        spotify.onPoke = { [weak self] in self?.nap?.cancel() }
        apple.onChange = { [weak self] in self?.publish() }
    }

    /** A drive started (or the drive screen shows): follow the players. */
    func activate() {
        guard !active else { return }
        active = true
        generation += 1
        let run = generation
        appleDue = .distantPast
        apple.start()
        Task { @MainActor in
            // Name and Premium again for this drive: a lapsed or new subscription shows.
            await SpotifyAuth.shared.refreshProfile()
            self.publish()
        }
        Task { @MainActor in await self.follow(run) }
    }

    func deactivate() {
        guard active else { return }
        active = false
        generation += 1
        nap?.cancel()
        dropCanvas()
        apple.stop()
        spotify.stop()
        chosen = nil
        media = nil
    }

    func playPause() {
        switch media?.source {
        case .spotify?: spotify.playPause()
        case .appleMusic?: apple.playPause()
        case nil: break
        }
    }

    func next() {
        switch media?.source {
        case .spotify?: spotify.next()
        case .appleMusic?: apple.next()
        case nil: break
        }
    }

    func previous() {
        switch media?.source {
        case .spotify?: spotify.previous()
        case .appleMusic?: apple.previous()
        case nil: break
        }
    }

    /** Spotify's Liked Songs (Apple Music offers no heart). */
    func toggleLike() {
        if media?.source == .spotify { spotify.toggleLike() }
    }

    /** Opens the player app. */
    func open() {
        guard let source = media?.source, let url = URL(string: source == .spotify ? "spotify:" : "music://") else { return }
        UIApplication.shared.open(url)
    }

    func seek(byMs: Int64) {
        switch media?.source {
        case .spotify?: spotify.seek(byMs: byMs)
        case .appleMusic?: apple.seek(byMs: byMs)
        case nil: break
        }
    }

    /** The Spotify account was connected or disconnected (settings). */
    func spotifyAccountChanged() {
        spotify.reset()
        if active { spotify.poke(after: 0) }
    }

    /** A Canvas video that would not play: forgotten, the cover stays (asked again for a later play). */
    func discardCanvas(_ file: URL) {
        if canvasFile == file { canvasFile = nil }
        Task { await CanvasStore.shared.discard(file) }
    }

    // MARK: - Following

    /** Reads the players when due, until the drive ends; a command wakes it early. */
    @MainActor
    private func follow(_ run: Int) async {
        while generation == run {
            if Date() >= spotify.due { await spotify.poll() }
            guard generation == run else { return }
            if Date() >= appleDue {
                apple.read()
                // Notifications tell every change; this only corrects the position's drift.
                appleDue = Date().addingTimeInterval(apple.track?.playing == true ? 10 : 30)
            }
            // The Canvas setting may have changed meanwhile.
            syncCanvas()
            await rest(until: min(spotify.due, appleDue))
        }
    }

    @MainActor
    private func rest(until date: Date) async {
        let wait = date.timeIntervalSinceNow
        guard wait > 0 else { return }
        let sleeper = Task { _ = try? await Task.sleep(for: .seconds(wait)) }
        nap = sleeper
        await sleeper.value
        nap = nil
    }

    /** Chooses the player the card follows and publishes its track. */
    private func publish() {
        guard active else {
            if media != nil { media = nil }
            return
        }
        var players: [(source: MusicSource, package: String, playing: Bool, at: Date)] = []
        if let t = spotify.track { players.append((source: .spotify, package: Self.spotifyPackage, playing: t.playing, at: spotify.lastPlayingAt)) }
        if let t = apple.track { players.append((source: .appleMusic, package: Self.applePackage, playing: t.playing, at: apple.lastPlayingAt)) }
        // Most recent first, as Android lists its sessions: the last one that played wins a tie.
        players.sort { $0.at > $1.at }
        let picked = Bridge.shared.pickPlayer(
            ids: players.map { $0.source.key },
            packages: players.map(\.package),
            playing: players.map { KotlinBoolean(value: $0.playing) },
            hasTitle: players.map { _ in KotlinBoolean(value: true) },
            current: chosen?.key
        )
        chosen = players.first { $0.source.key == picked }?.source
        let next: MediaInfo?
        switch chosen {
        case .spotify?: next = spotify.track.map { spotifyInfo($0) }
        case .appleMusic?: next = apple.track.map { appleInfo($0) }
        case nil: next = nil
        }
        let steady = settled(next)
        if steady != media { media = steady }
        syncCanvas()
    }

    private func spotifyInfo(_ t: SpotifyPlayer.Track) -> MediaInfo {
        MediaInfo(
            id: "spotify:" + t.key,
            title: t.title,
            artist: t.artist,
            album: t.album,
            art: spotify.art,
            playing: t.playing,
            source: .spotify,
            spotifyTrackId: t.trackId,
            progress: t.durationMs > 0 ? PlaybackProgress(positionMs: t.positionMs, updatedAt: t.readAt, speed: 1, durationMs: t.durationMs) : nil,
            liked: t.trackId == nil ? nil : spotify.liked,
            canControl: SpotifyAuth.shared.premium != false
        )
    }

    private func appleInfo(_ t: AppleMusicPlayer.Track) -> MediaInfo {
        let id = "apple:" + t.key
        return MediaInfo(
            id: id,
            title: t.title,
            artist: t.artist,
            album: t.album,
            art: apple.art,
            playing: t.playing,
            source: .appleMusic,
            spotifyTrackId: twins[id],
            progress: t.durationMs > 0 ? PlaybackProgress(positionMs: t.positionMs, updatedAt: t.readAt, speed: t.rate, durationMs: t.durationMs) : nil,
            liked: nil,
            canControl: true
        )
    }

    /**
     * Each read restates the position; when it agrees with the line already drawn (within 1.5 s),
     * the card keeps its progress and nothing redraws for it.
     */
    private func settled(_ next: MediaInfo?) -> MediaInfo? {
        guard let next, let old = media, old.id == next.id, old.playing == next.playing,
              let p = next.progress, let q = old.progress, p.durationMs == q.durationMs else { return next }
        let now = Date()
        guard abs(p.position(at: now, playing: next.playing) - q.position(at: now, playing: old.playing)) < 1_500 else { return next }
        return next.with(progress: q)
    }

    // MARK: - Canvas

    /** Looks up the Canvas of the track shown when it changes (setting on); keeps the file of the one found. */
    private func syncCanvas() {
        guard active, SettingsStore.shared.spotifyCanvas, let m = media,
              let key = m.source == .spotify ? m.spotifyTrackId : m.id else {
            dropCanvas()
            return
        }
        guard key != canvasKey else { return }
        canvasTask?.cancel()
        canvasKey = key
        if canvasFile != nil { canvasFile = nil }
        canvasTask = Task { @MainActor in
            var trackId = m.spotifyTrackId
            if trackId == nil, m.source == .appleMusic, let artist = m.artist {
                // Another player's track: its Spotify twin first, found by its words.
                guard let twin = await CanvasStore.shared.search(title: m.title, artist: artist, album: m.album, durationMs: m.progress?.durationMs ?? 0),
                      self.canvasKey == key else { return }
                if self.twins.count >= 200 { self.twins.removeAll() }
                self.twins[m.id] = twin
                self.publish()
                trackId = twin
            }
            guard let id = trackId, let file = await CanvasStore.shared.video(id),
                  !Task.isCancelled, self.canvasKey == key else { return }
            self.canvasFile = file
        }
    }

    private func dropCanvas() {
        canvasTask?.cancel()
        canvasTask = nil
        canvasKey = nil
        if canvasFile != nil { canvasFile = nil }
    }
}

private extension MusicSource {
    /** The player's id for the core's PlayerChoice. */
    var key: String { self == .spotify ? "spotify" : "apple" }
}

private extension MediaInfo {
    func with(progress: PlaybackProgress?) -> MediaInfo {
        MediaInfo(id: id, title: title, artist: artist, album: album, art: art, playing: playing, source: source,
                  spotifyTrackId: spotifyTrackId, progress: progress, liked: liked, canControl: canControl)
    }
}
