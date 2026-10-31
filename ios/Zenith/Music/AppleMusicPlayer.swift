import MediaPlayer
import UIKit

/**
 * Apple Music through the system player (MPMusicPlayerController.systemMusicPlayer): no API, no
 * account, only the media library authorization (asked by the welcome screen). Its notifications
 * tell track and state changes; the position is read with them and moves on by itself in between.
 * Main thread.
 */
final class AppleMusicPlayer {
    /** The track the Music app holds, and where it was when read. */
    struct Track: Equatable {
        /** Stable for the track: its library id, else its catalog id. */
        let key: String
        let title: String
        let artist: String?
        let album: String?
        let durationMs: Int64
        let positionMs: Int64
        let readAt: Date
        let rate: Double
        let playing: Bool
    }

    private(set) var track: Track?
    private(set) var art: UIImage?
    /** Last read that found it playing: the most recent player wins a tie. */
    private(set) var lastPlayingAt = Date.distantPast
    var onChange: () -> Void = {}

    private var player: MPMusicPlayerController { MPMusicPlayerController.systemMusicPlayer }
    private var following = false
    private var observers: [NSObjectProtocol] = []
    private var artKey: String?

    /** A drive started: follow the player. */
    func start() {
        following = true
        read()
    }

    /** Reads the player; starts listening to it once the library access is granted. */
    func read() {
        guard following else { return }
        guard MPMediaLibrary.authorizationStatus() == .authorized else { return set(nil) }
        if observers.isEmpty { listen() }
        guard let item = player.nowPlayingItem, let title = item.title, !title.isEmpty else { return set(nil) }
        let state = player.playbackState
        let playing = state == .playing || state == .seekingForward || state == .seekingBackward
        let key: String
        if item.persistentID != 0 {
            key = String(item.persistentID)
        } else if !item.playbackStoreID.isEmpty {
            key = item.playbackStoreID
        } else {
            key = title + "|" + (item.artist ?? "")
        }
        if key != artKey {
            artKey = key
            art = item.artwork?.image(at: CGSize(width: 300, height: 300))
        }
        let time = player.currentPlaybackTime
        let duration = item.playbackDuration
        let rate = Double(player.currentPlaybackRate)
        set(Track(
            key: key,
            title: title,
            artist: item.artist.flatMap { $0.isEmpty ? nil : $0 },
            album: item.albumTitle.flatMap { $0.isEmpty ? nil : $0 },
            durationMs: duration.isFinite && duration > 0 ? Int64(duration * 1000) : 0,
            positionMs: time.isFinite && time > 0 ? Int64(time * 1000) : 0,
            readAt: Date(),
            rate: rate.isFinite && rate > 0 ? rate : 1,
            playing: playing
        ))
    }

    /** End of a drive: stops listening, nothing kept. */
    func stop() {
        following = false
        if !observers.isEmpty {
            for observer in observers { NotificationCenter.default.removeObserver(observer) }
            observers = []
            player.endGeneratingPlaybackNotifications()
        }
        track = nil
        art = nil
        artKey = nil
    }

    func playPause() {
        guard let t = track else { return }
        if t.playing { player.pause() } else { player.play() }
        readSoon()
    }

    func next() {
        player.skipToNextItem()
        readSoon()
    }

    /** Like the Music app: back to the start of the track after 3 s, else the track before. */
    func previous() {
        let time = player.currentPlaybackTime
        if time.isFinite && time > 3 { player.skipToBeginning() } else { player.skipToPreviousItem() }
        readSoon()
    }

    func seek(byMs delta: Int64) {
        let time = player.currentPlaybackTime
        guard time.isFinite else { return }
        player.currentPlaybackTime = max(0, time + Double(delta) / 1000)
        readSoon()
    }

    // MARK: -

    private func listen() {
        let p = player
        p.beginGeneratingPlaybackNotifications()
        let names: [Notification.Name] = [.MPMusicPlayerControllerNowPlayingItemDidChange, .MPMusicPlayerControllerPlaybackStateDidChange]
        observers = names.map { name in
            NotificationCenter.default.addObserver(forName: name, object: p, queue: .main) { [weak self] _ in self?.read() }
        }
    }

    /** The player applies a command a moment later than it returns. */
    private func readSoon() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.read() }
    }

    private func set(_ new: Track?) {
        if new?.playing == true { lastPlayingAt = Date() }
        guard new != track else { return }
        if new == nil {
            art = nil
            artKey = nil
        }
        track = new
        onChange()
    }
}
