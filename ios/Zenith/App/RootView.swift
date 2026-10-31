import SwiftUI

/**
 * The app's screens and the way between them (MainActivity on Android): the welcome while an
 * authorization is missing (never during a drive), the drive screen, the settings pushed over it
 * like an iOS stack (home slides a third to the left, recedes a little and darkens; the reverse on
 * the way back, on a spring without bounce). Each return to the app reads the authorizations again
 * and checks for an update, then every 2 minutes while Zenith stays in front.
 */
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = DriveModel.shared
    @State private var permissions = Permissions.shared
    @State private var showSettings = false
    @AppStorage("onboarded") private var onboarded = false
    /** "Pas de Spotify": the invitation is not shown again (the settings still connect). */
    @AppStorage("spotify_declined") private var spotifyDeclined = false
    @State private var spotify = SpotifyAuth.shared

    /** Spotify can only be read through the driver's account on iOS: asked for once, on the home screen. */
    private var askSpotify: Bool {
        !showWelcome && !model.running && !showSettings && spotify.configured && !spotify.connected && !spotifyDeclined
    }

    private var showWelcome: Bool { !model.running && (!onboarded || !permissions.allGranted) }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                if showWelcome {
                    OnboardingView(returning: onboarded) {
                        onboarded = true
                    }
                    .transition(.asymmetric(insertion: .opacity, removal: .scale(scale: 1.08).combined(with: .opacity)))
                    .zIndex(2)
                } else {
                    DriveScreen(onOpenSettings: { withAnimation(Self.push) { showSettings = true } })
                        .offset(x: showSettings ? -geo.size.width / 3 : 0)
                        .scaleEffect(showSettings ? 0.94 : 1)
                        .overlay(Color.black.opacity(showSettings ? 0.45 : 0).allowsHitTesting(false).ignoresSafeArea())
                        .allowsHitTesting(!showSettings)
                        .transition(.opacity.combined(with: .scale(scale: 0.96)))
                        .zIndex(0)
                    // The small window's layer must be on screen for iOS to open it over Waze / Maps.
                    if model.running { PipAnchor().allowsHitTesting(false) }
                    if askSpotify {
                        SpotifyInvite(
                            onConnect: { spotify.connect() },
                            onDecline: { withAnimation(.smooth(duration: 0.4)) { spotifyDeclined = true } }
                        )
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .zIndex(0.5)
                    }
                    if showSettings {
                        SettingsView(onBack: { withAnimation(Self.push) { showSettings = false } })
                            .transition(.move(edge: .trailing))
                            .zIndex(1)
                    }
                }
            }
        }
        .animation(.smooth(duration: 0.5), value: showWelcome)
        .animation(.spring(response: 0.5, dampingFraction: 0.85), value: askSpotify)
        .onChange(of: scenePhase, initial: true) { _, phase in
            guard phase == .active else { return }
            permissions.refresh()
            MotionStarter.shared.sync()
            Updater.shared.checkIfDue()
        }
        .task(id: scenePhase) {
            // While in front: a new version never waits.
            while scenePhase == .active && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 120_000_000_000)
                Updater.shared.checkIfDue()
            }
        }
    }

    /** iOS's push: a spring without bounce. */
    static let push = Animation.spring(response: 0.45, dampingFraction: 1)
}

/**
 * The invitation to connect Spotify, rising over the home screen until connected or declined: iOS
 * lets Zenith see the music only through the driver's own account (not required: some have none).
 */
private struct SpotifyInvite: View {
    let onConnect: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack {
            Spacer()
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    Image(systemName: "music.note")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Color(.sRGB, red: 0x1E / 255, green: 0xD7 / 255, blue: 0x60 / 255, opacity: 1))
                    Text("Ta musique sur l'écran de conduite")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white)
                }
                Text("Connecte Spotify une fois : titre, pochette, Canvas et commandes pendant la conduite. Apple Music marche déjà sans compte.")
                    .font(.system(size: 14))
                    .foregroundStyle(.white.opacity(0.7))
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 10) {
                    Text("Pas de Spotify")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(.white.opacity(0.8))
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .glass(Capsule())
                        .tap(shape: Capsule(), sound: .close, action: onDecline)
                    Text("Se connecter")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(Capsule().fill(Color(.sRGB, red: 0x1E / 255, green: 0xD7 / 255, blue: 0x60 / 255, opacity: 1)))
                        .tap(shape: Capsule(), sound: .open, glow: PressShade, action: onConnect)
                }
            }
            .padding(18)
            .glass(RoundedRectangle(cornerRadius: 26, style: .continuous))
            .frame(maxWidth: PAGE_MAX_WIDTH)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
    }
}
