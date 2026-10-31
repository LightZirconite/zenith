import Foundation
import Observation
import UIKit

// CONTRACT (agent "music"): keep these names and signatures; fill the bodies. Port of NowPlaying.kt
// for iOS: Spotify through the Web API (account connected in the settings) and Apple Music through
// MPMusicPlayerController.systemMusicPlayer.

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

/** Now playing, for the drive screen, the small window and the Live Activity. Main thread. */
@Observable
final class MusicCenter {
    static let shared = MusicCenter()

    private(set) var media: MediaInfo?
    /** Local file of the current track's Canvas video, once downloaded. */
    private(set) var canvasFile: URL?

    /** A drive started (or the drive screen shows): follow the players. */
    func activate() {}
    func deactivate() {}
    func playPause() {}
    func next() {}
    func previous() {}
    func toggleLike() {}
    /** Opens the player app. */
    func open() {}
    func seek(byMs: Int64) {}
}
