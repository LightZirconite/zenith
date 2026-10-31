import CoreMotion
import SwiftUI

/** The colors of the grouped lists, the iOS dark ones. */
private enum SettingsPalette {
    /** Grouped lists the iOS way: glass a little see-through, the sky of home faint behind. */
    static let group = Color(.sRGB, red: 0x1C / 255, green: 0x1C / 255, blue: 0x1F / 255, opacity: 0xCC / 255)
    static let hairline = Color.white.opacity(0.1)
    static let accent = Color(.sRGB, red: 0x0A / 255, green: 0x84 / 255, blue: 0xFF / 255, opacity: 1)
    static let destructive = Color(.sRGB, red: 0xFF / 255, green: 0x45 / 255, blue: 0x3A / 255, opacity: 1)
    static let green = Color(.sRGB, red: 0x30 / 255, green: 0xD1 / 255, blue: 0x58 / 255, opacity: 1)
    static let dim = Color(.sRGB, red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255, opacity: 1)
    static let secondary = Color(.sRGB, red: 0xAE / 255, green: 0xAE / 255, blue: 0xB2 / 255, opacity: 1)
}

/**
 * The few choices Zenith offers, in iOS groups (port of SettingsScreen.kt). Everything else is
 * automatic. A change applies at once ([SettingsStore]); the groups arrive one after the other.
 */
struct SettingsView: View {
    let onBack: () -> Void

    @Environment(\.openURL) private var openURL
    @State private var settings = SettingsStore.shared
    @State private var permissions = Permissions.shared
    @State private var updater = Updater.shared
    @State private var spotify = SpotifyAuth.shared
    @State private var trip: URL?

    init(onBack: @escaping () -> Void) {
        self.onBack = onBack
    }

    var body: some View {
        LargeTitlePage(title: "Réglages", leading: { SettingsBackButton(action: onBack) }) {
            alerts.cascade(0)
            car.cascade(1)
            music.cascade(2)
            smallWindow.cascade(3)
            application.cascade(4)
            footer.cascade(5)
        }
        // Rows coming and going (a permission granted, an account connected): the groups resize on a spring.
        .animation(.spring(response: 0.31, dampingFraction: 0.85), value: layout)
        .onAppear { trip = TripRecorder.latestTrip() }
    }

    // MARK: - Groups

    private var alerts: some View {
        SettingsGroup("Alertes") {
            SettingsToggleRow(title: "Annonces vocales", text: voiceText, on: settings.voice) { settings.voice = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                title: "Volume monté en conduite",
                text: "Au départ, le volume monte à 60 % s'il est plus bas, pour bien entendre les alertes et la voix. " +
                    "À l'arrêt, il revient où il était, sauf si tu l'as changé entre-temps.",
                on: settings.raiseVolume
            ) { settings.raiseVolume = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                title: "Musique en pause pendant la voix",
                text: "Désactivé (conseillé) : pendant une annonce, Spotify, Apple Music ou la radio baissent en douceur puis " +
                    "remontent de même. Activé : la musique baisse puis s'arrête le temps de l'annonce. Les bips, eux, ne font que la baisser.",
                on: settings.pauseMusic
            ) { settings.pauseMusic = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                title: "Jeune permis",
                text: "Limites probatoires : 110 sur autoroute, 100 sur voie rapide, 80 sur route à 90.",
                on: settings.probationary
            ) { settings.probationary = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                title: "Pluie automatique",
                text: "Pendant la conduite, la météo (Open-Meteo, gratuit) dit s'il pleut et les limites baissent seules : 110 sur " +
                    "autoroute, 100 sur voie rapide, 80 sur route. Rien à toucher. Seule une position arrondie à ~10 km est envoyée.",
                on: settings.autoRain
            ) { settings.autoRain = $0 }
        }
    }

    private var car: some View {
        SettingsGroup("En voiture") {
            SettingsToggleRow(title: "Démarrage en roulant", text: motionText, on: settings.motionStart, enabled: motionAvailable) { on in
                settings.motionStart = on
                MotionStarter.shared.sync()
            }
            if let missing = motionMissing {
                SettingsSeparator()
                SettingsActionRow(label: missing, sound: .open, action: askMotionPermission)
            }
            SettingsSeparator()
            SettingsInfoRow(
                title: "Démarrage automatique en voiture",
                text: "iOS ne prévient pas les applis quand la voiture se connecte : une automatisation Raccourcis s'en charge, " +
                    "à créer une fois par voiture.\n" +
                    "Raccourcis → Automatisation → + → « CarPlay » (ou « Bluetooth » → ta voiture) → « Est connecté » → " +
                    "action Zenith « Voiture connectée » → « Exécuter immédiatement ».\n" +
                    "Pareil avec « Est déconnecté » → « Voiture déconnectée » : seule une conduite lancée par la voiture s'arrête."
            )
            SettingsSeparator()
            SettingsActionRow(label: "Ouvrir Raccourcis", sound: .open) {
                if let url = URL(string: "shortcuts://") { openURL(url) }
            }
            SettingsSeparator()
            SettingsToggleRow(
                title: "Arrêt automatique",
                text: "Pour la batterie : Zenith s'arrête seul quand la voiture est garée depuis 10 minutes, quand tu repars à pied " +
                    "ou à l'intérieur sans GPS. Jamais sur autoroute ni pendant un bouchon. Une conduite lancée par la voiture " +
                    "(Raccourcis, au-dessus) s'arrête, elle, avec la voiture.",
                on: settings.autoStop
            ) { settings.autoStop = $0 }
        }
    }

    private var music: some View {
        SettingsGroup("Musique") {
            SettingsInfoRow(title: "Spotify", text: spotifyText, done: spotify.connected)
            if spotify.configured {
                SettingsSeparator()
                if spotify.connected {
                    SettingsActionRow(label: "Se déconnecter", color: SettingsPalette.destructive, sound: .close) { spotify.disconnect() }
                } else {
                    SettingsActionRow(label: "Se connecter à Spotify", sound: .open) { spotify.connect() }
                }
            }
            SettingsSeparator()
            SettingsInfoRow(
                title: "Apple Music",
                text: permissions.appleMusic
                    ? "Le titre en cours d'Apple Music, sa progression et ses commandes s'affichent sous les alertes pendant la conduite."
                    : "Titre en cours et commandes d'Apple Music sous les alertes, sans compte. iOS appelle cela « Médias et Apple Music ».",
                done: permissions.appleMusic
            )
            if !permissions.appleMusic {
                SettingsSeparator()
                SettingsActionRow(label: "Autoriser l'accès", sound: .open) { permissions.requestAppleMusic() }
            }
            SettingsSeparator()
            SettingsToggleRow(
                title: "Canvas",
                text: "La courte vidéo en boucle des titres (celle de Spotify), derrière la carte Musique et dans la petite fenêtre, " +
                    "pour Spotify comme pour Apple Music. Services gratuits non officiels : peut cesser de marcher, et tous les " +
                    "titres n'en ont pas. Environ 3 Mo par titre, gardés en cache. Sinon, la pochette floutée.",
                on: settings.spotifyCanvas
            ) { settings.spotifyCanvas = $0 }
        }
    }

    private var smallWindow: some View {
        SettingsGroup("Petite fenêtre et écran verrouillé") {
            SettingsToggleRow(
                title: "Petite fenêtre automatique",
                text: "En quittant Zenith pendant la conduite (Waze, Maps…), la petite fenêtre s'ouvre seule par-dessus. " +
                    "Désactivé : seulement avec son bouton sur l'écran de conduite.",
                on: settings.autoPip
            ) { settings.autoPip = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                title: "Petite fenêtre carrée",
                text: "Désactivé : un bandeau compact en largeur, qui laisse voir Waze ou Maps. Activé : une fenêtre carrée, " +
                    "plus grande. Un pincement sur la fenêtre la redimensionne aussi.",
                on: settings.pipLarge
            ) { settings.pipLarge = $0 }
            SettingsSeparator()
            if !LiveActivityManager.installed {
                // SideStore's "Remove extension" at the install takes the Live Activity away with it.
                SettingsInfoRow(
                    title: "Activité en direct",
                    text: "Indisponible : l'extension a été retirée à l'installation. Pour l'écran verrouillé et la Dynamic Island, " +
                        "réinstalle Zenith depuis SideStore en choisissant « Keep extension » (un App ID de plus, une seule fois)."
                )
            } else {
                SettingsToggleRow(
                    title: "Activité en direct",
                    text: "Vitesse, limite et prochain radar sur l'écran verrouillé et dans la Dynamic Island pendant la conduite.",
                    on: settings.liveActivity
                ) { on in
                    settings.liveActivity = on
                    // Changed during a drive: shown or taken away at once.
                    let model = DriveModel.shared
                    guard model.running else { return }
                    if on { LiveActivityManager.shared.start(demo: model.demo) } else { LiveActivityManager.shared.end() }
                }
            }
        }
    }

    private var application: some View {
        SettingsGroup("Application") {
            SettingsToggleRow(
                title: "Sons de l'interface",
                text: "Petits sons doux sous le doigt, sur chaque bouton. Les alertes de conduite ne sont pas concernées, " +
                    "les vibrations restent.",
                on: settings.uiSounds
            ) { settings.uiSounds = $0 }
            SettingsSeparator()
            SettingsInfoRow(title: "Mises à jour", text: updateText, done: updater.state == .upToDate)
            SettingsSeparator()
            SettingsActionRow(label: "Rechercher maintenant") { updater.check() }
            if updateAvailable {
                SettingsSeparator()
                SettingsActionRow(label: "Installer avec SideStore", sound: .open) { updater.install() }
            }
            SettingsSeparator()
            SettingsActionRow(label: "Ajouter la source à SideStore", sound: .open) { updater.addSource() }
            if let trip {
                SettingsSeparator()
                SettingsInfoRow(
                    title: "Dernier trajet",
                    text: "Fichier GPX de ton dernier trajet, pour signaler une alerte manquée ou fausse : il se rejoue tel quel."
                )
                SettingsSeparator()
                SettingsShareRow(label: "Partager le trajet", file: trip)
            }
        }
    }

    private var footer: some View {
        Text(verbatim: "Zenith \(updater.currentVersion)\nCartes © OpenFreeMap © OpenStreetMap\n" +
            "Radars : data.gouv.fr (Licence Ouverte), OpenStreetMap (ODbL), Lufop\n" +
            "Astuce : appui long sur le logo de l'accueil pour un trajet de démo.")
            .font(.system(size: 12))
            .lineSpacing(2)
            .foregroundStyle(SettingsPalette.dim)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 18)
            .padding(.top, 18)
            .padding(.bottom, 12)
    }

    // MARK: - What the rows say

    /** What can make rows come or go: the groups animate their size when it changes. */
    private var layout: [Bool] {
        [
            settings.motionStart, permissions.location == .always, permissions.motion,
            spotify.connected, permissions.appleMusic, updateAvailable, trip != nil,
        ]
    }

    private var voiceText: String {
        VoiceStatus.shared.frenchMissing
            ? "Pas de voix française sur cet iPhone : télécharge-en une dans Réglages › Accessibilité › Contenu énoncé › " +
                "Voix › Français. En attendant, seuls les bips sonnent."
            : "Désactivé : seulement les bips, sans voix."
    }

    private var motionAvailable: Bool { CMMotionActivityManager.isActivityAvailable() }

    /** The permission the motion start still lacks, as the words of its row; nil when nothing is missing. */
    private var motionMissing: String? {
        guard motionAvailable, settings.motionStart else { return nil }
        if permissions.location != .always { return "Autoriser la position « Toujours »" }
        if !permissions.motion { return "Autoriser « Mouvements et forme »" }
        return nil
    }

    private var motionText: String {
        if !motionAvailable { return "Nécessite un iPhone qui reconnaît les déplacements (coprocesseur de mouvement)." }
        if motionMissing != nil {
            return "Zenith démarre seul dès que l'iPhone sent que tu roules en voiture, même fermé, presque sans batterie. " +
                "Il faut la position « Toujours » et « Mouvements et forme » : sans elles, iOS ne réveille pas Zenith."
        }
        if settings.motionStart {
            return "Zenith démarre seul quand tu roules en voiture, même fermé, et s'arrête une fois garé. Une notification " +
                "le dit : touche-la pour ouvrir la petite fenêtre."
        }
        return "Désactivé : Zenith ne démarre seul qu'avec ta voiture, par Raccourcis (ci-dessous)."
    }

    private func askMotionPermission() {
        if permissions.location == .always {
            permissions.requestMotion()
        } else if permissions.location == .whenInUse {
            // "Toujours" was offered during the welcome: iOS asks only once, its settings page does the rest.
            permissions.openSettings()
        } else {
            permissions.requestLocation()
        }
    }

    private var spotifyText: String {
        if !spotify.configured {
            return "L'identifiant de l'appli Spotify manque dans cette version : la connexion n'est pas possible."
        }
        if spotify.connected {
            let who = spotify.userName ?? "ton compte"
            let free = spotify.premium == false
                ? " Compte gratuit : Spotify réserve les commandes (pause, suivant) à Premium."
                : ""
            return "Connecté : \(who). Titre en cours, pochette et Canvas pendant la conduite." + free
        }
        return "Connecte ton compte pour le titre en cours, sa pochette et son Canvas pendant la conduite, et les commandes " +
            "avec Premium. La connexion se fait sur la page de Spotify."
    }

    private var updateAvailable: Bool {
        if case .available = updater.state { return true }
        return false
    }

    private var updateText: String {
        let current = updater.currentVersion
        switch updater.state {
        case .idle:
            return "Version \(current). Zenith regarde seul s'il y a du nouveau à chaque retour dans l'app, puis toutes " +
                "les 2 minutes ; SideStore l'installe."
        case .checking:
            return "Recherche d'une nouvelle version…"
        case .upToDate:
            return "À jour : version \(current)."
        case .available(let version, let notes):
            let what = notes.trimmingCharacters(in: .whitespacesAndNewlines)
            let base = "Version \(version) disponible : SideStore la signe et l'installe par-dessus celle-ci."
            return what.isEmpty ? base : base + "\n" + what
        case .failed(let message):
            return "\(message). Version \(current)."
        }
    }
}

// MARK: - Rows

/** The way back the iOS way: a round glass button with a chevron. */
private struct SettingsBackButton: View {
    let action: () -> Void

    var body: some View {
        Chevron(color: .white)
            .frame(width: 10, height: 17)
            .padding(.trailing, 2)
            .frame(width: 44, height: 44)
            .glass(Circle())
            .tap(shape: Circle(), sound: .close, lift: 1.12, action: action)
            .accessibilityLabel("Retour")
    }
}

/** A gray heading in capitals over its group, then the group in one block of glass. */
private struct SettingsGroup<Rows: View>: View {
    let title: String
    let rows: Rows

    init(_ title: String, @ViewBuilder rows: () -> Rows) {
        self.title = title
        self.rows = rows()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title.uppercased())
                .font(.system(size: 13, weight: .semibold))
                .tracking(0.5)
                .foregroundStyle(SettingsPalette.dim)
                .padding(.leading, 18)
                .padding(.top, 22)
                .padding(.bottom, 7)
            VStack(alignment: .leading, spacing: 0) { rows }
                .frame(maxWidth: .infinity, alignment: .leading)
                .glass(RoundedRectangle(cornerRadius: 24, style: .continuous), color: SettingsPalette.group)
        }
    }
}

/** The hairline between two rows of a group, inset like iOS. */
private struct SettingsSeparator: View {
    var body: some View {
        Rectangle()
            .fill(SettingsPalette.hairline)
            .frame(height: 0.5)
            .padding(.leading, 18)
    }
}

/** A setting: its name, what it does, and the switch; the whole row toggles it. */
private struct SettingsToggleRow: View {
    let title: String
    let text: String
    let on: Bool
    var enabled = true
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 17))
                    .foregroundStyle(.white)
                Text(text)
                    .font(.system(size: 13))
                    .lineSpacing(2)
                    .foregroundStyle(SettingsPalette.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            PressedSwitch(on: on && enabled)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 13)
        .tap(enabled: enabled, sound: on ? .off : .on, lift: 1) {
            withAnimation(.spring(response: 0.31, dampingFraction: 0.85)) { onToggle(!on) }
        }
        .accessibilityValue(on ? "Activé" : "Désactivé")
    }
}

/** The switch of a row, its knob stretching while the finger is on the row. */
private struct PressedSwitch: View {
    let on: Bool
    @Environment(\.tapPressed) private var pressed

    var body: some View { GlassSwitch(on: on, pressed: pressed) }
}

/** A row that only says something; [done] puts a green check next to its name. */
private struct SettingsInfoRow: View {
    let title: String
    let text: String
    var done = false

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Text(title)
                    .font(.system(size: 17))
                    .foregroundStyle(.white)
                if done {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(SettingsPalette.green)
                        .transition(.scale(scale: 0.3).combined(with: .opacity))
                }
            }
            Text(text)
                .font(.system(size: 13))
                .lineSpacing(2)
                .foregroundStyle(SettingsPalette.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
        .padding(.vertical, 13)
    }
}

/** An action, the iOS way: colored words across the row. */
private struct SettingsActionRow: View {
    let label: String
    var color: Color = SettingsPalette.accent
    var sound: UiSound.Kind = .tap
    let action: () -> Void

    var body: some View {
        Text(label)
            .font(.system(size: 17))
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 18)
            .padding(.vertical, 13)
            .tap(sound: sound, lift: 1, action: action)
    }
}

/** An action row handing a file to iOS's share sheet (the last trip's GPX). */
private struct SettingsShareRow: View {
    let label: String
    let file: URL

    var body: some View {
        ShareLink(item: file) {
            Text(label)
                .font(.system(size: 17))
                .foregroundStyle(SettingsPalette.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18)
                .padding(.vertical, 13)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .simultaneousGesture(TapGesture().onEnded { UiSound.play(.open) })
    }
}
