import Foundation
import Observation
import UIKit

/** Where the update check stands. */
enum UpdateState: Equatable {
    case idle
    case checking
    case upToDate
    /** A newer build is on the SideStore source. */
    case available(version: String, notes: String)
    case failed(String)
}

/**
 * Updates through SideStore: the CI publishes each build on the "ios" release with a SideStore
 * source (sidestore.json). SideStore itself checks the source and notifies; Zenith also checks it
 * at each return to the front and every 2 minutes while in front, and its "Installer" button hands
 * the new IPA to SideStore (sidestore://install), which signs and installs it. iOS never lets an
 * app install itself: one tap in SideStore stays needed. Main thread.
 */
@Observable
final class Updater {
    static let shared = Updater()

    static let sourceURL = URL(string: "https://github.com/LightZirconite/zenith/releases/download/ios/sidestore.json")!

    private(set) var state = UpdateState.idle
    @ObservationIgnored private var lastCheck = Date.distantPast
    @ObservationIgnored private var downloadURL: URL?

    /** This build: "0.1.N". */
    let currentVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    let currentBuild = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0

    /** At most once a minute. */
    func checkIfDue() {
        if Date().timeIntervalSince(lastCheck) >= 60 { check() }
    }

    /** "Rechercher maintenant". */
    func check() {
        guard state != .checking else { return }
        lastCheck = Date()
        state = .checking
        Task {
            let (next, ipa) = await Self.fetch(currentBuild: currentBuild)
            await MainActor.run {
                if let ipa { self.downloadURL = ipa }
                self.state = next
            }
        }
    }

    /** Hands the new IPA to SideStore, which installs it over this one. */
    func install() {
        guard let ipa = downloadURL,
              var parts = URLComponents(string: "sidestore://install") else { return }
        parts.queryItems = [URLQueryItem(name: "url", value: ipa.absoluteString)]
        if let url = parts.url { UIApplication.shared.open(url) }
    }

    /** Adds Zenith's source to SideStore (once, from the settings). */
    func addSource() {
        guard var parts = URLComponents(string: "sidestore://source") else { return }
        parts.queryItems = [URLQueryItem(name: "url", value: Self.sourceURL.absoluteString)]
        if let url = parts.url { UIApplication.shared.open(url) }
    }

    private static func fetch(currentBuild: Int) async -> (UpdateState, URL?) {
        var request = URLRequest(url: sourceURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.setValue("Zenith", forHTTPHeaderField: "User-Agent")
        guard let answer = try? await URLSession.shared.data(for: request),
              (answer.1 as? HTTPURLResponse)?.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: answer.0) as? [String: Any],
              let app = (json["apps"] as? [[String: Any]])?.first,
              let latest = (app["versions"] as? [[String: Any]])?.first,
              let version = latest["version"] as? String,
              let build = Int(latest["buildVersion"] as? String ?? ""),
              let url = (latest["downloadURL"] as? String).flatMap(URL.init(string:)) else {
            return (.failed("Source SideStore injoignable"), nil)
        }
        guard build > currentBuild else { return (.upToDate, nil) }
        return (.available(version: version, notes: latest["localizedDescription"] as? String ?? ""), url)
    }
}
