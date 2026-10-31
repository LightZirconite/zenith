import Foundation
import Observation

// CONTRACT (agent "music"): keep these names and signatures; fill the bodies.

/** The driver's Spotify account (OAuth PKCE, Spotify app of the author: SpotifyClientID in Info.plist). */
@Observable
final class SpotifyAuth {
    static let shared = SpotifyAuth()

    /** A client id was built into the app (repository variable SPOTIFY_CLIENT_ID). */
    var configured: Bool { false }
    private(set) var connected = false
    private(set) var userName: String?
    /** Premium account: playback controls work through the Web API. Nil until known. */
    private(set) var premium: Bool?

    func connect() {}
    func disconnect() {}
}
