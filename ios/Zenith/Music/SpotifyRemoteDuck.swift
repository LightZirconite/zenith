import Foundation
import UIKit
import ZenithCore

/**
 * Spotify lowered on another device (Spotify Connect: the car's head unit, a speaker) while Zenith
 * speaks, then put back: iOS's ducking only reaches what this phone plays. Same rules as Android
 * (core SpotifyDuck): only another device that takes volume commands, music playing, Premium
 * (Spotify refuses otherwise); never put back if the driver changed the volume meanwhile. Calls run
 * one after the other, off the alert's way (fire and forget). Main thread.
 */
enum SpotifyRemoteDuck {
    private static var ducked: (deviceId: String, before: Int32, to: Int32)?
    private static var last: Task<Void, Never>?

    /** Zenith starts speaking (the first sound holds the session). */
    static func duck() {
        chain {
            guard ducked == nil, let found = await activeDevice(), let id = found.1.id, let volume = found.1.volumePercent else { return }
            let (player, device) = found
            let premium: Int32 = SpotifyAuth.shared.premium.map { $0 ? 1 : 0 } ?? -1
            let names = ["iPhone", UIDevice.current.name, UIDevice.current.model]
            guard Bridge.shared.spotifyShouldDuck(
                deviceType: device.type, deviceName: device.name, supportsVolume: device.supportsVolume ?? false,
                playing: player.isPlaying ?? false, premium: premium, phoneNames: names
            ) else { return }
            let to = Bridge.shared.spotifyDuckedVolume(current: Int32(volume))
            guard to < volume, let answer = await setVolume(to, id) else { return }
            if answer.status == 403 { SpotifyAuth.shared.notePremium(false) }
            if (200..<300).contains(answer.status) { ducked = (id, Int32(volume), to) }
        }
    }

    /** Zenith is done (the session handed back): the device back to its volume. */
    static func restore() {
        chain {
            guard let d = ducked else { return }
            ducked = nil
            let now = await activeDevice().flatMap { $0.1.id == d.deviceId ? $0.1.volumePercent : nil } ?? -1
            let back = Bridge.shared.spotifyRestoreVolume(before: d.before, duckedTo: d.to, now: Int32(now))
            if back >= 0 { _ = await setVolume(back, d.deviceId) }
        }
    }

    private static func chain(_ work: @escaping @MainActor () async -> Void) {
        guard SpotifyAuth.shared.connected else { return }
        let previous = last
        last = Task { @MainActor in
            await previous?.value
            await work()
        }
    }

    @MainActor
    private static func activeDevice() async -> (PlayerState, PlayerState.Device)? {
        guard let answer = await SpotifyAuth.shared.request("GET", "/v1/me/player"), answer.status == 200,
              let player = try? JSONDecoder().decode(PlayerState.self, from: answer.data),
              let device = player.device else { return nil }
        return (player, device)
    }

    @MainActor
    private static func setVolume(_ percent: Int32, _ deviceId: String) async -> SpotifyAnswer? {
        await SpotifyAuth.shared.request("PUT", "/v1/me/player/volume", query: [("volume_percent", String(percent)), ("device_id", deviceId)])
    }

    /** The part of GET /v1/me/player read here. */
    private struct PlayerState: Decodable {
        struct Device: Decodable {
            let id: String?
            let name: String?
            let type: String?
            let volumePercent: Int?
            let supportsVolume: Bool?

            enum CodingKeys: String, CodingKey {
                case id, name, type
                case volumePercent = "volume_percent"
                case supportsVolume = "supports_volume"
            }
        }

        let isPlaying: Bool?
        let device: Device?

        enum CodingKeys: String, CodingKey {
            case isPlaying = "is_playing"
            case device
        }
    }
}
