// Port of app/src/main/kotlin/zenith/app/SkyWatch.kt.
import CoreLocation
import Foundation
import Observation
import ZenithCore

/**
 * The weather drawn over the screen (WeatherLayer): read at home from the last known position as
 * soon as the app opens, then from each of the drive's weather reports (DriveEngine). Nothing is
 * kept: a new launch reads the sky again. Main thread.
 */
@Observable
final class SkyWatch {
    static let shared = SkyWatch()

    private(set) var look = SkyLook.clear
    /** What the settings say: the last reading and when, or why there is none ("Météo à l'écran"). */
    private(set) var status = "Pas encore de relevé"
    @ObservationIgnored private var readAt: Date?
    @ObservationIgnored private var reading = false
    @ObservationIgnored private let location = CLLocationManager()

    private static let every: TimeInterval = 600

    /** A drive's weather report: the screen follows it. */
    func report(_ sky: CoreSky) {
        let at = Date()
        readAt = at
        look = SkyLook(sky)
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        status = "\(Self.name(look)) · relevé à \(f.string(from: at))"
    }

    private static func name(_ l: SkyLook) -> String {
        switch l.kind {
        case .clear: return "Pas de pluie"
        case .rain: return l.intensity >= 0.65 ? "Forte pluie" : "Pluie"
        case .snow: return "Neige"
        case .storm: return "Orage"
        case .fog: return "Brouillard"
        }
    }

    @ObservationIgnored private var demoTask: Task<Void, Never>?
    private static let demoSkies = [
        SkyLook(kind: .rain, intensity: 0.55),
        SkyLook(kind: .snow, intensity: 0.7),
        SkyLook(kind: .storm, intensity: 1),
        SkyLook(kind: .fog, intensity: 0.8),
    ]

    /**
     * The hidden demo shows every weather in turn (45 s each): rain, snow, storm, fog. On screen
     * only: the demo's limits and announcements stay those of a dry road.
     */
    func demo(_ on: Bool) {
        demoTask?.cancel()
        demoTask = nil
        readAt = on ? Date() : nil
        guard on else {
            look = .clear
            return
        }
        demoTask = Task { @MainActor [weak self] in
            var i = 0
            while !Task.isCancelled {
                self?.look = Self.demoSkies[i % Self.demoSkies.count]
                i += 1
                try? await Task.sleep(nanoseconds: 45_000_000_000)
            }
        }
    }

    /** At home: the sky where the phone last was, at most every 10 minutes. */
    func refreshAtHome() {
        guard SettingsStore.shared.weatherFx, !DriveModel.shared.running, !reading else { return }
        if let readAt, Date().timeIntervalSince(readAt) < Self.every { return }
        guard let p = location.location?.coordinate,
              let url = URL(string: Bridge.shared.weatherUrl(lat: p.latitude, lon: p.longitude)) else {
            status = "Position inconnue pour l'instant"
            return
        }
        reading = true
        Task { [weak self] in
            defer { Task { @MainActor in self?.reading = false } }
            guard let answer = try? await URLSession.shared.data(from: url),
                  (answer.1 as? HTTPURLResponse)?.statusCode == 200,
                  let text = String(data: answer.0, encoding: .utf8),
                  let sky = Bridge.shared.weatherSky(text: text) else {
                await MainActor.run { self?.status = "Relevé impossible (réseau)" }
                return
            }
            await MainActor.run {
                // A drive started meanwhile reads its own.
                guard let self, !DriveModel.shared.running else { return }
                self.report(sky)
            }
        }
    }
}
