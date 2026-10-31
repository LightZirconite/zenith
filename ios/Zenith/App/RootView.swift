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
                    if showSettings {
                        SettingsView(onBack: { withAnimation(Self.push) { showSettings = false } })
                            .transition(.move(edge: .trailing))
                            .zIndex(1)
                    }
                }
            }
        }
        .animation(.smooth(duration: 0.5), value: showWelcome)
        .onChange(of: scenePhase, initial: true) { _, phase in
            guard phase == .active else { return }
            permissions.refresh()
            MotionStarter.shared.sync()
            Updater.shared.checkIfDue()
        }
        // The Live Activity follows its setting during a drive too.
        .onChange(of: SettingsStore.shared.liveActivity) { _, on in
            guard model.running else { return }
            if on { LiveActivityManager.shared.start(demo: model.demo) } else { LiveActivityManager.shared.end() }
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
