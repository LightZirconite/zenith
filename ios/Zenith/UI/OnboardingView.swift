import SwiftUI

/** The colors of the welcome. */
private enum OnboardingPalette {
    static let surface = Color(.sRGB, red: 0x16 / 255, green: 0x16 / 255, blue: 0x1A / 255, opacity: 0xD9 / 255)
    static let green = Color(.sRGB, red: 0x30 / 255, green: 0xD1 / 255, blue: 0x58 / 255, opacity: 1)
    static let accent = Color(.sRGB, red: 0x0A / 255, green: 0x84 / 255, blue: 0xFF / 255, opacity: 1)
    static let dim = Color(.sRGB, red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255, opacity: 1)
}

/** One authorization of the welcome, in the order the big button asks for them. */
private enum OnboardingStep: CaseIterable {
    case location, notifications, motion, appleMusic
}

/**
 * Every authorization Zenith needs, each with the reason in plain words, so none of iOS's prompts
 * comes as a surprise (port of Onboarding.kt). All are required: the screen comes back whenever one
 * is missing (outside a drive), and lets the driver through only once everything is granted. A bar
 * fills as they come, each granted step lights up with a check and a ring of light, and the one big
 * button walks through the missing ones before turning into a glowing "C'est parti".
 */
struct OnboardingView: View {
    /** Seen before (an authorization was withdrawn since): "Il manque une autorisation". */
    let returning: Bool
    let onDone: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var permissions = Permissions.shared
    @State private var settings = SettingsStore.shared
    /** iOS showed a prompt since the last request (the app stopped being active). */
    @State private var promptSeen = false

    init(returning: Bool, onDone: @escaping () -> Void) {
        self.returning = returning
        self.onDone = onDone
    }

    var body: some View {
        let missing = permissions.missing
        let total = steps.count
        LargeTitlePage(
            title: returning ? "Autorisations" : "Bienvenue",
            bottom: {
                OnboardingGoButton(missing: missing) {
                    if missing == 0 {
                        onDone()
                    } else if let next = steps.first(where: { !granted($0) }) {
                        ask(next)
                    }
                }
            }
        ) {
            Text(intro)
                .font(.system(size: 16))
                .foregroundStyle(OnboardingPalette.dim)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
                .cascade(0)

            OnboardingProgress(done: max(total - missing, 0), total: total)
                .padding(.vertical, 18)
                .cascade(1)

            ForEach(Array(steps.enumerated()), id: \.element) { index, step in
                OnboardingStepCard(
                    title: title(step),
                    text: text(step),
                    granted: granted(step),
                    action: actionLabel(step)
                ) {
                    ask(step)
                }
                .cascade(2 + index)
            }
            .animation(.spring(response: 0.4, dampingFraction: 0.85), value: steps)

            Spacer(minLength: 12)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                permissions.refresh()
            } else {
                promptSeen = true
            }
        }
    }

    private var intro: String {
        if returning {
            return "Il en manque pour que Zenith marche à fond : nouvelles fonctions, ou une autorisation retirée. " +
                "Voici pourquoi chacune est là."
        }
        return "Zenith a besoin de quelques autorisations pour t'alerter, même écran éteint ou derrière Waze. " +
            "iOS va afficher ses propres fenêtres : voici pourquoi chacune est là."
    }

    // MARK: - Steps

    /** The motion start counts only when wanted, as on Android. */
    private var steps: [OnboardingStep] {
        OnboardingStep.allCases.filter { $0 != .motion || settings.motionStart }
    }

    private func granted(_ step: OnboardingStep) -> Bool {
        switch step {
        case .location: return permissions.location == .always
        case .notifications: return permissions.notifications
        case .motion: return permissions.motion
        case .appleMusic: return permissions.appleMusic
        }
    }

    /** Refused once: iOS will not ask again, the button opens Zenith's settings page instead. */
    private func refused(_ step: OnboardingStep) -> Bool {
        switch step {
        case .location: return permissions.location == .denied
        case .notifications: return permissions.notificationsAsked && !permissions.notifications
        case .motion: return permissions.motionAsked && !permissions.motion
        case .appleMusic: return permissions.appleMusicAsked && !permissions.appleMusic
        }
    }

    private func ask(_ step: OnboardingStep) {
        promptSeen = false
        switch step {
        case .location:
            let upgrading = permissions.location == .whenInUse
            permissions.requestLocation()
            guard upgrading else { return }
            // iOS offers "Toujours" only once: when no prompt came, its settings page instead.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                if !promptSeen && permissions.location == .whenInUse { permissions.openSettings() }
            }
        case .notifications:
            permissions.requestNotifications()
        case .motion:
            permissions.requestMotion()
        case .appleMusic:
            permissions.requestAppleMusic()
        }
    }

    private func title(_ step: OnboardingStep) -> String {
        switch step {
        case .location: return "Position précise"
        case .notifications: return "Notifications"
        case .motion: return "Démarrage en roulant"
        case .appleMusic: return "Apple Music"
        }
    }

    private func actionLabel(_ step: OnboardingStep) -> String {
        if refused(step) { return "Réglages" }
        if step == .location && permissions.location == .whenInUse { return "Toujours" }
        return "Autoriser"
    }

    private func text(_ step: OnboardingStep) -> String {
        switch step {
        case .location:
            switch permissions.location {
            case .denied:
                return "Refusée pour l'instant : touche Réglages, puis Position → Toujours, et garde « Position exacte »."
            case .whenInUse:
                return "Encore « Toujours » : Zenith peut alors démarrer seul quand tu roules, même fermé, et garder ses " +
                    "alertes écran éteint. Si iOS ne demande rien, ses réglages s'ouvrent : Position → Toujours."
            case .notAsked, .always:
                return "Pour mesurer ta vitesse et savoir quels radars sont sur ta route. Ta position ne quitte jamais l'iPhone. " +
                    "Choisis d'abord « Lorsque l'app est active » et garde « Position exacte » ; « Toujours » vient juste après."
            }
        case .notifications:
            if refused(step) { return "Refusées pour l'instant : touche Réglages, puis Notifications → Autoriser." }
            return "Pour te prévenir quand Zenith démarre seul en roulant ou avec ta voiture : une touche sur la notification " +
                "ouvre la petite fenêtre."
        case .motion:
            if refused(step) { return "Refusé pour l'instant : touche Réglages, puis active « Mouvements et forme »." }
            return "Zenith reconnaît que tu montes en voiture grâce au détecteur de mouvement de l'iPhone, presque sans " +
                "batterie, et démarre seul, même fermé. iOS appelle cela « Mouvements et forme »."
        case .appleMusic:
            if refused(step) { return "Refusé pour l'instant : touche Réglages, puis active « Médias et Apple Music »." }
            return "Le titre en cours d'Apple Music, sa pochette et ses commandes pendant la conduite, sans compte. iOS appelle " +
                "cela « Médias et Apple Music ». Spotify se connecte, lui, dans les réglages."
        }
    }
}

/** How many of the authorizations are granted: a bar of light filling on a spring, green when full. */
private struct OnboardingProgress: View {
    let done: Int
    let total: Int

    var body: some View {
        let k = total > 0 ? min(max(CGFloat(done) / CGFloat(total), 0), 1) : 0
        let full = total > 0 && done >= total
        VStack(alignment: .leading, spacing: 8) {
            Text("\(done) sur \(total)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(0.6))
                .contentTransition(.numericText())
            Capsule()
                .fill(.white.opacity(0.1))
                .frame(height: 6)
                .overlay(alignment: .leading) {
                    GeometryReader { geo in
                        ZStack {
                            Capsule().fill(fill(OnboardingPalette.accent))
                            Capsule().fill(fill(OnboardingPalette.green)).opacity(full ? 1 : 0)
                        }
                        .frame(width: geo.size.width * k)
                    }
                }
                .clipShape(Capsule())
        }
        .animation(.easeInOut(duration: 0.6), value: full)
        .animation(.spring(response: 0.44, dampingFraction: 0.75), value: done)
    }

    private func fill(_ color: Color) -> LinearGradient {
        LinearGradient(colors: [color.opacity(0.6), color], startPoint: .leading, endPoint: .trailing)
    }
}

/** The one button: the next authorization, the count rolling down, then a glowing "C'est parti". */
private struct OnboardingGoButton: View {
    let missing: Int
    let action: () -> Void

    var body: some View {
        let ready = missing == 0
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        ZStack {
            Text(label)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.black)
                .multilineTextAlignment(.center)
                .id(missing)
                .transition(.asymmetric(
                    insertion: .move(edge: .bottom).combined(with: .opacity),
                    removal: .move(edge: .top).combined(with: .opacity)
                ))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
        .background(Color.white)
        .clipShape(shape)
        .animation(.spring(response: 0.31, dampingFraction: 0.75), value: missing)
        .background {
            if ready {
                // A ring of green light breathing out of the button, over and over.
                TimelineView(.animation) { context in
                    let pulse = Self.pulse(at: context.date)
                    let grow = 8 * pulse
                    RoundedRectangle(cornerRadius: 18 + grow, style: .continuous)
                        .stroke(OnboardingPalette.green.opacity(0.35 * (1 - pulse)), lineWidth: 2)
                        .padding(-grow)
                }
                .transition(.opacity)
            }
        }
        .tap(shape: shape, sound: ready ? .success : .open, lift: 1.04, glow: PressShade, action: action)
        .padding(.top, 12)
    }

    private var label: String {
        switch missing {
        case 0: return "C'est parti"
        case 1: return "Continuer · dernière autorisation"
        default: return "Continuer · encore \(missing)"
        }
    }

    /** 0 to 1 and back over 2.8 s, eased at both ends. */
    private static func pulse(at date: Date) -> CGFloat {
        let cycle = (date.timeIntervalSinceReferenceDate / 1.4).truncatingRemainder(dividingBy: 2)
        let x = cycle < 1 ? cycle : 2 - cycle
        return CGFloat(x * x * (3 - 2 * x))
    }
}

/**
 * One authorization: its name, why, and its button; granted, a check pops in with a ring of light
 * running out of it and a warm little chord, and the card glows green on its right.
 */
private struct OnboardingStepCard: View {
    let title: String
    let text: String
    let granted: Bool
    let action: String
    let onTap: () -> Void

    @State private var seen: Bool
    @State private var burst: CGFloat = 1
    /** When the card appeared: iOS answers some reads late (notifications), not a grant by the driver. */
    @State private var since = Date()

    init(title: String, text: String, granted: Bool, action: String, onTap: @escaping () -> Void) {
        self.title = title
        self.text = text
        self.granted = granted
        self.action = action
        self.onTap = onTap
        _seen = State(initialValue: granted)
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                Text(text)
                    .font(.system(size: 14))
                    .foregroundStyle(.white.opacity(0.75))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            ZStack {
                if granted {
                    ZStack {
                        BurstRing(k: burst)
                        Circle().fill(OnboardingPalette.green)
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.black)
                    }
                    .frame(width: 30, height: 30)
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.3).combined(with: .opacity),
                        removal: .scale(scale: 0.6).combined(with: .opacity)
                    ))
                } else {
                    Text(action)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(OnboardingPalette.accent))
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.3).combined(with: .opacity),
                            removal: .scale(scale: 0.6).combined(with: .opacity)
                        ))
                }
            }
            .animation(.spring(response: 0.31, dampingFraction: 0.45), value: granted)
        }
        .padding(18)
        .background {
            LinearGradient(
                colors: [.clear, OnboardingPalette.green.opacity(0.35)],
                startPoint: UnitPoint(x: 0.5, y: 0.5),
                endPoint: .trailing
            )
            .opacity(granted ? 1 : 0)
            .animation(.easeInOut(duration: 0.6), value: granted)
        }
        .glass(shape, color: OnboardingPalette.surface)
        .tap(enabled: !granted, shape: shape, sound: .open, lift: 0.98, action: onTap)
        .padding(.bottom, 12)
        .onChange(of: granted) { _, now in
            // Granted while the screen is open: the chord, the ring of light.
            if now && !seen && Date().timeIntervalSince(since) > 0.8 {
                UiSound.play(.success)
                burst = 0
                DispatchQueue.main.async {
                    withAnimation(.timingCurve(0, 0, 0.2, 1, duration: 0.8)) { burst = 1 }
                }
            }
            seen = now
        }
    }
}

/** A ring of light running out of the check, thinning as it grows; [k] 0 to 1, gone at 1. */
private struct BurstRing: View, Animatable {
    var k: CGFloat

    var animatableData: CGFloat {
        get { k }
        set { k = newValue }
    }

    var body: some View {
        Circle()
            .stroke(OnboardingPalette.green.opacity(Double(0.6 * (1 - k))), lineWidth: 2 * (1 - k) + 0.5)
            .scaleEffect(1 + 1.4 * k)
    }
}
