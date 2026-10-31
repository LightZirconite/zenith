import AuthenticationServices
import CryptoKit
import Foundation
import Observation
import Security
import UIKit

/**
 * The driver's Spotify account, for the music card: Authorization Code with PKCE (no client secret)
 * in Safari's sheet, tokens kept in the Keychain and refreshed a minute before they expire. The
 * Spotify app of the author gives the client id (SpotifyClientID in Info.plist; empty: not
 * configured). Since February 2026 the profile no longer says Premium: it is learned from the first
 * playback command (403 PREMIUM_REQUIRED), and relearned at each drive. Main thread.
 */
@Observable
final class SpotifyAuth {
    static let shared = SpotifyAuth()

    /** A client id was built into the app (repository variable SPOTIFY_CLIENT_ID). */
    var configured: Bool { !clientId.isEmpty }
    private(set) var connected = false
    private(set) var userName: String? = nil
    /** Premium account: playback controls work through the Web API. Nil until known. */
    private(set) var premium: Bool? = nil

    private let clientId: String
    @ObservationIgnored private var tokens: SpotifyTokens?
    @ObservationIgnored private var refreshing: Task<String?, Never>?
    @ObservationIgnored private var login: ASWebAuthenticationSession?
    /** Held here: the session keeps its presentation provider weakly. */
    @ObservationIgnored private var anchor: LoginAnchor?

    private static let redirectUri = "zenith://spotify-callback"
    private static let scopes = "user-read-currently-playing user-read-playback-state user-modify-playback-state user-library-read user-library-modify"
    private static let api = "https://api.spotify.com"
    private static let userKey = "spotify_user"

    init() {
        let id = (Bundle.main.object(forInfoDictionaryKey: "SpotifyClientID") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // A build without the variable can leave "$(SPOTIFY_CLIENT_ID)" in the plist.
        clientId = id.hasPrefix("$(") ? "" : id
        if configured, let saved = TokenVault.load() {
            tokens = saved
            connected = true
            userName = UserDefaults.standard.string(forKey: Self.userKey)
        }
    }

    /** Opens Spotify's sign-in page; the account is connected when the driver comes back. */
    func connect() {
        guard configured, login == nil else { return }
        Task { @MainActor in self.startLogin() }
    }

    /** Forgets the account here (Spotify keeps the app's access until removed in the account page). */
    func disconnect() {
        login?.cancel()
        login = nil
        refreshing = nil
        tokens = nil
        TokenVault.clear()
        UserDefaults.standard.removeObject(forKey: Self.userKey)
        connected = false
        userName = nil
        premium = nil
        MusicCenter.shared.spotifyAccountChanged()
    }

    // MARK: - For the music card

    /**
     * An authorized Web API request ([path] from "/v1/…"); refreshes the token when it expires or
     * Spotify answers 401. Nil when not connected or the network failed.
     */
    @MainActor
    func request(_ method: String, _ path: String, query: [(String, String)] = []) async -> SpotifyAnswer? {
        guard connected, let token = await accessToken(renew: false) else { return nil }
        guard let first = await Self.send(method, path, query, token) else { return nil }
        guard first.status == 401, let renewed = await accessToken(renew: true) else { return first }
        return await Self.send(method, path, query, renewed) ?? first
    }

    /** Name of the account, and Premium when Spotify still says it (else learned again from the controls). */
    @MainActor
    func refreshProfile() async {
        guard let answer = await request("GET", "/v1/me"), answer.status == 200,
              let me = try? SpotifyJSON.decoder.decode(SpotifyProfile.self, from: answer.data) else { return }
        let name = me.displayName ?? me.id
        if name != userName {
            userName = name
            UserDefaults.standard.set(name, forKey: Self.userKey)
        }
        let known = me.product.map { $0 == "premium" }
        if known != premium { premium = known }
    }

    /** A playback command told whether the account has Premium. */
    func notePremium(_ value: Bool) {
        if premium != value { premium = value }
    }

    // MARK: - Sign-in

    @MainActor
    private func startLogin() {
        guard login == nil else { return }
        let verifier = Self.randomString(64)
        let state = Self.randomString(24)
        let query = Self.encode([
            ("client_id", clientId),
            ("response_type", "code"),
            ("redirect_uri", Self.redirectUri),
            ("code_challenge_method", "S256"),
            ("code_challenge", Self.challenge(verifier)),
            ("scope", Self.scopes),
            ("state", state),
        ])
        guard let url = URL(string: "https://accounts.spotify.com/authorize?" + query) else { return }
        let session = ASWebAuthenticationSession(url: url, callback: .customScheme("zenith")) { callback, error in
            Task { @MainActor in await self.finish(callback: callback, error: error, verifier: verifier, state: state) }
        }
        let provider = LoginAnchor()
        anchor = provider
        session.presentationContextProvider = provider
        // Signed in to Spotify in Safari already: no password to type.
        session.prefersEphemeralWebBrowserSession = false
        login = session
        if !session.start() {
            login = nil
            anchor = nil
        }
    }

    @MainActor
    private func finish(callback: URL?, error: Error?, verifier: String, state: String) async {
        login = nil
        anchor = nil
        if let error { NSLog("Zenith Spotify: sign-in: \(error.localizedDescription)") }
        guard let callback,
              let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems,
              items.first(where: { $0.name == "state" })?.value == state,
              let code = items.first(where: { $0.name == "code" })?.value else { return }
        let result = await Self.tokenRequest([
            ("grant_type", "authorization_code"),
            ("code", code),
            ("redirect_uri", Self.redirectUri),
            ("client_id", clientId),
            ("code_verifier", verifier),
        ], keeping: nil)
        guard case .granted(let fresh) = result else { return }
        store(fresh)
        connected = true
        premium = nil
        await refreshProfile()
        MusicCenter.shared.spotifyAccountChanged()
    }

    // MARK: - Tokens

    /** A valid access token; [renew]: Spotify refused the current one. Concurrent refreshes share one request. */
    @MainActor
    private func accessToken(renew: Bool) async -> String? {
        guard let current = tokens else { return nil }
        if !renew && current.expiresAt.timeIntervalSinceNow > 60 { return current.access }
        if let running = refreshing { return await running.value }
        let task = Task { @MainActor in await self.renew(current) }
        refreshing = task
        let access = await task.value
        refreshing = nil
        return access
    }

    @MainActor
    private func renew(_ old: SpotifyTokens) async -> String? {
        let result = await Self.tokenRequest([
            ("grant_type", "refresh_token"),
            ("refresh_token", old.refresh),
            ("client_id", clientId),
        ], keeping: old.refresh)
        // Signed out or in again meanwhile: this answer is about tokens no longer used.
        guard tokens?.refresh == old.refresh else { return tokens?.access }
        switch result {
        case .granted(let fresh):
            store(fresh)
            return fresh.access
        case .refused:
            // Access removed in the Spotify account: connected no more.
            disconnect()
            return nil
        case .failed:
            return nil
        }
    }

    private func store(_ fresh: SpotifyTokens) {
        tokens = fresh
        TokenVault.save(fresh)
    }

    private enum TokenResult {
        case granted(SpotifyTokens)
        case refused
        case failed
    }

    /** The token endpoint; a refresh answer may come without a new refresh token: [keeping] stays. */
    private static func tokenRequest(_ form: [(String, String)], keeping: String?) async -> TokenResult {
        guard let url = URL(string: "https://accounts.spotify.com/api/token") else { return .failed }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(encode(form).utf8)
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else {
                NSLog("Zenith Spotify: token \(status)")
                return status == 400 ? .refused : .failed
            }
            let answer = try SpotifyJSON.decoder.decode(TokenAnswer.self, from: data)
            guard let refresh = answer.refreshToken ?? keeping else { return .failed }
            return .granted(SpotifyTokens(access: answer.accessToken, refresh: refresh, expiresAt: Date().addingTimeInterval(answer.expiresIn)))
        } catch {
            return .failed
        }
    }

    private static func send(_ method: String, _ path: String, _ query: [(String, String)], _ token: String) async -> SpotifyAnswer? {
        guard let url = URL(string: api + path + (query.isEmpty ? "" : "?" + encode(query))) else { return nil }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        // PUT and POST without a body still say "Content-Length: 0".
        if method != "GET" { request.httpBody = Data() }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { return nil }
            let retry = http.value(forHTTPHeaderField: "Retry-After").flatMap { TimeInterval($0.trimmingCharacters(in: .whitespaces)) }
            return SpotifyAnswer(status: http.statusCode, data: data, retryAfter: retry)
        } catch {
            return nil
        }
    }

    // MARK: - Helpers

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    /** Query or form encoding, every reserved character escaped. */
    private static func encode(_ pairs: [(String, String)]) -> String {
        pairs.map { name, value in
            (name.addingPercentEncoding(withAllowedCharacters: unreserved) ?? name) + "="
                + (value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value)
        }.joined(separator: "&")
    }

    /** PKCE verifier or state: the system's secure random generator over the allowed characters. */
    private static func randomString(_ count: Int) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        var generator = SystemRandomNumberGenerator()
        return String((0..<count).map { _ in alphabet[Int.random(in: 0..<alphabet.count, using: &generator)] })
    }

    /** S256 challenge: SHA-256 of the verifier in URL-safe base64, no padding. */
    private static func challenge(_ verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/** A Web API answer: status, body and, on a 429, how long Spotify asks to wait. */
struct SpotifyAnswer {
    let status: Int
    let data: Data
    let retryAfter: TimeInterval?

    /** The error's reason ("PREMIUM_REQUIRED", "NO_ACTIVE_DEVICE"…), when Spotify gives one. */
    var reason: String? { (try? SpotifyJSON.decoder.decode(SpotifyError.self, from: data))?.error.reason }
}

/** Spotify's JSON, snake_case keys read as camelCase. */
enum SpotifyJSON {
    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()
}

/** Access and refresh tokens of the account. */
struct SpotifyTokens: Codable {
    let access: String
    let refresh: String
    let expiresAt: Date
}

private struct TokenAnswer: Decodable {
    let accessToken: String
    let expiresIn: Double
    let refreshToken: String?
}

private struct SpotifyProfile: Decodable {
    let id: String?
    let displayName: String?
    /** Gone for Development Mode apps since February 2026; read when still there. */
    let product: String?
}

private struct SpotifyError: Decodable {
    struct Detail: Decodable {
        let reason: String?
    }

    let error: Detail
}

/**
 * The tokens in the Keychain, readable once the phone was unlocked after a restart: the music card
 * is read with the screen off during a drive.
 */
private enum TokenVault {
    private static var item: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "io.github.lightzirconite.zenith.spotify",
            kSecAttrAccount as String: "tokens",
        ]
    }

    static func load() -> SpotifyTokens? {
        var query = item
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &found) == errSecSuccess, let data = found as? Data else { return nil }
        return try? JSONDecoder().decode(SpotifyTokens.self, from: data)
    }

    static func save(_ tokens: SpotifyTokens) {
        guard let data = try? JSONEncoder().encode(tokens) else { return }
        let values: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(item as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            let added = item.merging(values) { _, new in new }
            status = SecItemAdd(added as CFDictionary, nil)
        }
        if status != errSecSuccess { NSLog("Zenith Spotify: keychain \(status)") }
    }

    static func clear() {
        SecItemDelete(item as CFDictionary)
    }
}

/** The window Spotify's sign-in sheet shows over: the key window of the app. */
private final class LoginAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap(\.windows)
        if let window = windows.first(where: \.isKeyWindow) ?? windows.first { return window }
        if let scene = scenes.first { return UIWindow(windowScene: scene) }
        return ASPresentationAnchor()
    }
}
