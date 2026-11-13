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
    /** iOS's secondary label: footers and the status card. */
    static let label = Color(.sRGB, red: 0xEB / 255, green: 0xEB / 255, blue: 0xF5 / 255, opacity: 0.6)
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
    @State private var model = DriveModel.shared
    @State private var trip: URL?

    init(onBack: @escaping () -> Void) {
        self.onBack = onBack
    }

    var body: some View {
        LargeTitlePage(title: "Réglages", leading: { SettingsBackButton(action: onBack) }) {
            status.cascade(0)
            alerts.cascade(1)
            announced.cascade(2)
            car.cascade(3)
            music.cascade(4)
            smallWindow.cascade(5)
            application.cascade(6)
            footer.cascade(7)
        }
        // Rows coming and going (a permission granted, an account connected): the groups resize on a spring.
        .animation(.spring(response: 0.31, dampingFraction: 0.85), value: layout)
        .onAppear { trip = TripRecorder.latestTrip() }
    }

    // MARK: - Groups

    /** Zenith at a glance: the radars in memory, how fresh, and whether this version is the latest. */
    private var status: some View {
        HStack(spacing: 14) {
            // The icon's drawing leaves a margin for the masks: drawn larger so the dial fills its place.
            ZenithLogo()
                .frame(width: 72, height: 72)
                .frame(width: 46, height: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "Zenith")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                Text(verbatim: baseText)
                    .font(.system(size: 13))
                    .foregroundStyle(SettingsPalette.label)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let pill = updatePill {
                Text(verbatim: pill.text)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(pill.color)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(pill.color.opacity(0.14)))
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .glass(RoundedRectangle(cornerRadius: 22, style: .continuous), color: Color.white.opacity(0.06))
        .padding(.top, 18)
        .animation(.spring(response: 0.31, dampingFraction: 0.7), value: updatePill?.text)
    }

    /** What is said besides the radars: each can be turned off, for those who want the radars only. */
    private var announced: some View {
        SettingsGroup("Ce qui est annoncé", footer: announcedFooter) {
            SettingsToggleRow(
                icon: .speeding,
                title: "Excès de vitesse",
                text: "« Limite 80 » quand tu roules trop vite",
                on: settings.speeding
            ) { settings.speeding = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .early,
                title: "Prévenir dès 3 km/h au-dessus",
                text: "Plus tôt que la marge des radars",
                on: settings.earlySpeeding
            ) { settings.earlySpeeding = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .danger,
                title: "Bips de danger",
                text: "Plus rapides, trop vite près d'un radar",
                on: settings.dangerBeeps
            ) { settings.dangerBeeps = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .passResult,
                title: "Résultat au passage",
                text: "« Flash probable », moyenne du tronçon",
                on: settings.passResult
            ) { settings.passResult = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .zones,
                title: "Itinéraires de contrôle",
                text: "« Radar possible » à l'entrée",
                on: settings.zones
            ) { settings.zones = $0 }
        }
    }

    private var alerts: some View {
        SettingsGroup("Alertes", footer: alertsFooter) {
            SettingsToggleRow(icon: .voice, title: "Annonces vocales", text: voiceText, on: settings.voice) { settings.voice = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .volume,
                title: "Volume monté en conduite",
                text: "À 60 % au départ, rendu à l'arrêt",
                on: settings.raiseVolume
            ) { settings.raiseVolume = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .pause,
                title: "Musique en pause pendant la voix",
                text: "Désactivé conseillé",
                on: settings.pauseMusic
            ) { settings.pauseMusic = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .learner,
                title: "Jeune permis",
                text: "110 · 100 · 80",
                on: settings.probationary
            ) { settings.probationary = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .rain,
                title: "Pluie automatique",
                text: "Limites de pluie, sans rien toucher",
                on: settings.autoRain
            ) { settings.autoRain = $0 }
        }
    }

    private var car: some View {
        SettingsGroup("En voiture", footer: carFooter) {
            SettingsToggleRow(
                icon: .autoStart,
                title: "Démarrage automatique",
                text: settings.autoStart ? "En roulant ou par Raccourcis" : "Seulement avec « Démarrer »",
                on: settings.autoStart
            ) { on in
                settings.autoStart = on
                MotionStarter.shared.sync()
            }
            if settings.autoStart {
                SettingsSeparator()
                autoStartRows
            }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .autoStop,
                title: "Arrêt automatique",
                text: "Garé 10 min, ou à pied",
                on: settings.autoStop
            ) { settings.autoStop = $0 }
        }
    }

    /** Rows of the automatic start, shown while its master switch is on. */
    @ViewBuilder private var autoStartRows: some View {
        SettingsToggleRow(icon: .moving, title: "Démarrage en roulant", text: motionText, on: settings.motionStart, enabled: motionAvailable) { on in
            settings.motionStart = on
            MotionStarter.shared.sync()
        }
        if let missing = motionMissing {
            SettingsSeparator()
            SettingsActionRow(label: missing, sound: .open, action: askMotionPermission)
        }
        SettingsSeparator()
        SettingsInfoRow(icon: .shortcuts, title: "Voiture connectée", text: "Automatisation Raccourcis, une par voiture")
        SettingsSeparator()
        SettingsActionRow(label: "Ouvrir Raccourcis", sound: .open) {
            if let url = URL(string: "shortcuts://") { openURL(url) }
        }
    }

    private var music: some View {
        SettingsGroup("Musique", footer: musicFooter) {
            SettingsInfoRow(icon: .spotify, title: "Compte Spotify", text: spotifyText, done: spotify.connected)
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
                icon: .appleMusic,
                title: "Apple Music",
                text: permissions.appleMusic ? "Titre et commandes en conduite" : "Accès « Médias et Apple Music »",
                done: permissions.appleMusic
            )
            if !permissions.appleMusic {
                SettingsSeparator()
                SettingsActionRow(label: "Autoriser l'accès", sound: .open) { permissions.requestAppleMusic() }
            }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .canvas,
                title: "Canvas",
                text: "Vidéo en boucle derrière la carte",
                on: settings.spotifyCanvas
            ) { settings.spotifyCanvas = $0 }
        }
    }

    private var smallWindow: some View {
        SettingsGroup("Petite fenêtre et écran verrouillé", footer: smallWindowFooter) {
            SettingsToggleRow(
                icon: .pip,
                title: "Petite fenêtre automatique",
                text: "En quittant Zenith pendant la conduite",
                on: settings.autoPip
            ) { settings.autoPip = $0 }
            SettingsSeparator()
            SettingsToggleRow(
                icon: .pipLarge,
                title: "Petite fenêtre carrée",
                text: settings.pipLarge ? "Carrée, plus grande" : "Bandeau compact en largeur",
                on: settings.pipLarge
            ) { settings.pipLarge = $0 }
            SettingsSeparator()
            if !LiveActivityManager.installed {
                // SideStore's "Remove extension" at the install takes the Live Activity away with it.
                SettingsInfoRow(icon: .liveActivity, title: "Activité en direct", text: "Indisponible : extension retirée")
            } else {
                SettingsToggleRow(
                    icon: .liveActivity,
                    title: "Activité en direct",
                    text: "Écran verrouillé et Dynamic Island",
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
        SettingsGroup("Application", footer: applicationFooter) {
            SettingsToggleRow(
                icon: .uiSounds,
                title: "Sons de l'interface",
                text: "Un son doux sur chaque bouton",
                on: settings.uiSounds
            ) { settings.uiSounds = $0 }
            SettingsSeparator()
            SettingsInfoRow(icon: .updates, title: "Mises à jour", text: updateText, done: updater.state == .upToDate)
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
                SettingsInfoRow(icon: .trip, title: "Dernier trajet", text: "Fichier GPX, il se rejoue tel quel")
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

    // MARK: - Footers: what the rows no longer say

    private var alertsFooter: String {
        var parts: [String] = []
        if VoiceStatus.shared.frenchMissing {
            parts.append("Pas de voix française sur cet iPhone : télécharge-en une dans Réglages › Accessibilité › " +
                "Contenu énoncé › Voix › Français. En attendant, seuls les bips sonnent.")
        }
        parts.append("Pendant une annonce, la musique baisse en douceur puis remonte de même ; en pause pendant la voix, " +
            "elle baisse puis s'arrête le temps de l'annonce. Les bips ne font que la baisser, jamais de coupure nette.")
        parts.append("Jeune permis et pluie : 110 sur autoroute, 100 sur voie rapide, 80 sur route. La météo (Open-Meteo, " +
            "gratuit) ne reçoit qu'une position arrondie à ~10 km.")
        return parts.joined(separator: "\n\n")
    }

    private var announcedFooter: String {
        "Les radars sont toujours annoncés ; pour n'entendre qu'eux, désactive le reste. Trop vite : à partir de la vitesse " +
            "où un radar flashe, sa marge déduite (56 pour 50, 96 pour 90, 138 pour 130), sur une limite sûre et quelques " +
            "secondes après le panneau."
    }

    private var carFooter: String {
        guard settings.autoStart else {
            return "Zenith ne démarre jamais seul, seulement quand tu touches « Démarrer ». Les automatisations Raccourcis " +
                "restent, mais ne démarrent plus rien. Arrêt automatique : jamais sur autoroute ni dans un bouchon."
        }
        var parts = ["Tu n'es pas au volant ? Touche « Arrêter » : Zenith ne redémarre plus seul avant la fin du trajet. " +
            "Désactiver le démarrage automatique est déconseillé."]
        if motionMissing != nil {
            parts.append("Démarrage en roulant : sans la position « Toujours » et « Mouvements et forme », iOS ne réveille pas Zenith.")
        } else if settings.motionStart {
            parts.append("En roulant, Zenith démarre seul même fermé, presque sans batterie ; une notification le dit, touche-la " +
                "pour ouvrir la petite fenêtre.")
        }
        parts.append("Voiture connectée : iOS ne prévient pas les applis, une automatisation Raccourcis s'en charge. " +
            "Automatisation → + → « CarPlay » (ou « Bluetooth » → ta voiture) → « Est connecté » → action Zenith " +
            "« Voiture connectée » → « Exécuter immédiatement ». Pareil avec « Est déconnecté » → « Voiture déconnectée » : " +
            "seule une conduite lancée par la voiture s'arrête.")
        parts.append("Arrêt automatique : garé depuis 10 minutes, reparti à pied ou à l'intérieur sans GPS ; jamais sur autoroute " +
            "ni dans un bouchon. Une conduite lancée par la voiture s'arrête avec elle.")
        return parts.joined(separator: "\n\n")
    }

    private var musicFooter: String {
        var parts: [String] = []
        if !spotify.configured {
            parts.append("L'identifiant de l'appli Spotify manque dans cette version : la connexion n'est pas possible.")
        } else if spotify.connected && spotify.premium == false {
            parts.append("Compte gratuit : Spotify réserve les commandes (pause, suivant) à Premium.")
        } else if !spotify.connected {
            parts.append("Spotify : titre en cours, pochette et Canvas pendant la conduite, les commandes avec Premium. " +
                "La connexion se fait sur la page de Spotify.")
        }
        parts.append("Canvas : la courte vidéo en boucle des titres, derrière la carte Musique et dans la petite fenêtre, pour " +
            "Spotify comme pour Apple Music. Services gratuits non officiels, tous les titres n'en ont pas ; environ 3 Mo par " +
            "titre, gardés en cache. Sinon, la pochette floutée.")
        return parts.joined(separator: "\n\n")
    }

    private var smallWindowFooter: String {
        var parts = ["Petite fenêtre automatique désactivée : seulement avec un raccourci Waze ou Maps de l'écran de conduite. " +
            "Un pincement sur la petite fenêtre la redimensionne aussi."]
        if !LiveActivityManager.installed {
            parts.append("Activité en direct : l'extension a été retirée à l'installation. Pour l'écran verrouillé et la Dynamic " +
                "Island, réinstalle Zenith depuis SideStore en choisissant « Keep extension » (un App ID de plus, une seule fois).")
        }
        return parts.joined(separator: "\n\n")
    }

    private var applicationFooter: String {
        var parts = ["Les alertes de conduite ne dépendent pas des sons de l'interface, les vibrations restent.",
                     "Zenith regarde seul s'il y a du nouveau à chaque retour dans l'app, puis toutes les 2 minutes ; SideStore l'installe."]
        if case .available(_, let notes) = updater.state {
            let what = notes.trimmingCharacters(in: .whitespacesAndNewlines)
            if !what.isEmpty { parts.append(what) }
        }
        if trip != nil {
            parts.append("Dernier trajet : partage-le pour signaler une alerte manquée ou fausse.")
        }
        return parts.joined(separator: "\n\n")
    }

    // MARK: - What the rows say

    /** What can make rows come or go: the groups animate their size when it changes. */
    private var layout: [Bool] {
        [
            settings.autoStart, settings.motionStart, permissions.location == .always, permissions.motion,
            spotify.connected, permissions.appleMusic, updateAvailable, trip != nil,
        ]
    }

    private var voiceText: String {
        VoiceStatus.shared.frenchMissing ? "Pas de voix française : seuls les bips" : "Sinon, seulement les bips"
    }

    private var motionAvailable: Bool { CMMotionActivityManager.isActivityAvailable() }

    /** The permission the motion start still lacks, as the words of its row; nil when nothing is missing. */
    private var motionMissing: String? {
        guard motionAvailable, settings.motionWanted else { return nil }
        if permissions.location != .always { return "Autoriser la position « Toujours »" }
        if !permissions.motion { return "Autoriser « Mouvements et forme »" }
        return nil
    }

    private var motionText: String {
        if !motionAvailable { return "Pas de coprocesseur de mouvement" }
        if motionMissing != nil { return "Position « Toujours » requise" }
        if settings.motionStart { return "Démarre seul, s'arrête une fois garé" }
        return "Seulement par Raccourcis"
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
        if !spotify.configured { return "Identifiant absent de cette version" }
        if spotify.connected { return "Connecté : \(spotify.userName ?? "ton compte")" }
        return "Titre, pochette et Canvas en conduite"
    }

    private var updateAvailable: Bool {
        if case .available = updater.state { return true }
        return false
    }

    private var updateText: String {
        let current = updater.currentVersion
        switch updater.state {
        case .idle: return "Version \(current)"
        case .checking: return "Recherche d'une nouvelle version…"
        case .upToDate: return "À jour : version \(current)"
        case .available(let version, _): return "Version \(version) disponible"
        case .failed(let message): return message
        }
    }

    /** "9 318 radars · base du 2 novembre, 15:35". */
    private var baseText: String {
        let data = model.data
        guard data.radarCount > 0 else { return "Radars chargés au démarrage" }
        let radars = "\(DriveScreen.Words.thousands(data.radarCount)) radars"
        guard let updated = data.radarsUpdated else { return radars }
        return "\(radars) · base du \(Self.baseDate.string(from: updated))"
    }

    private static let baseDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "d MMMM, HH:mm"
        return f
    }()

    /** The pill of the status card: up to date, or an update waiting. */
    private var updatePill: (text: String, color: Color)? {
        switch updater.state {
        case .upToDate: return ("À jour", SettingsPalette.green)
        case .available: return ("Mise à jour", SettingsPalette.accent)
        default: return nil
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

/** The coloured square of a row, the iOS way: a white glyph on the setting's colour. */
private struct SettingsIcon {
    let symbol: String
    let rgb: UInt32

    var color: Color {
        Color(.sRGB, red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255, opacity: 1)
    }

    static let voice = SettingsIcon(symbol: "mic.fill", rgb: 0x5E5CE6)
    static let volume = SettingsIcon(symbol: "speaker.wave.2.fill", rgb: 0xFF9F0A)
    static let pause = SettingsIcon(symbol: "pause.fill", rgb: 0xFF375F)
    static let learner = SettingsIcon(symbol: "graduationcap.fill", rgb: 0x30B0C7)
    static let rain = SettingsIcon(symbol: "cloud.rain.fill", rgb: 0x0A84FF)
    static let speeding = SettingsIcon(symbol: "gauge.with.dots.needle.67percent", rgb: 0xFF453A)
    static let early = SettingsIcon(symbol: "bell.fill", rgb: 0xFF9F0A)
    static let danger = SettingsIcon(symbol: "waveform", rgb: 0xFF375F)
    static let passResult = SettingsIcon(symbol: "checkmark", rgb: 0x30D158)
    static let zones = SettingsIcon(symbol: "road.lanes", rgb: 0xFF9F0A)
    static let autoStart = SettingsIcon(symbol: "power", rgb: 0x32D74B)
    static let moving = SettingsIcon(symbol: "car.fill", rgb: 0x34C759)
    static let autoStop = SettingsIcon(symbol: "parkingsign", rgb: 0x8E8E93)
    static let shortcuts = SettingsIcon(symbol: "link", rgb: 0x64D2FF)
    static let spotify = SettingsIcon(symbol: "music.note", rgb: 0x30D158)
    static let appleMusic = SettingsIcon(symbol: "music.note.list", rgb: 0xFF375F)
    static let canvas = SettingsIcon(symbol: "play.rectangle.fill", rgb: 0xBF5AF2)
    static let pip = SettingsIcon(symbol: "pip.fill", rgb: 0xFF9F0A)
    static let pipLarge = SettingsIcon(symbol: "square.fill.on.square.fill", rgb: 0x5E5CE6)
    static let liveActivity = SettingsIcon(symbol: "lock.fill", rgb: 0x64D2FF)
    static let uiSounds = SettingsIcon(symbol: "hand.tap.fill", rgb: 0x30B0C7)
    static let updates = SettingsIcon(symbol: "arrow.triangle.2.circlepath", rgb: 0x0A84FF)
    static let trip = SettingsIcon(symbol: "point.topleft.down.curvedto.point.bottomright.up", rgb: 0x8E8E93)
}

/** The 30 pt rounded square of a row. */
private struct SettingsIconView: View {
    let icon: SettingsIcon

    var body: some View {
        Image(systemName: icon.symbol)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Color.white)
            .frame(width: 30, height: 30)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(icon.color))
            .accessibilityHidden(true)
    }
}

/** Where a row's words start, after its icon: actions and hairlines line up with the titles. */
private let SettingsTextInset: CGFloat = 14 + 30 + 12

/** A gray heading in capitals over its group, then the group in one block of glass. */
private struct SettingsGroup<Rows: View>: View {
    let title: String
    /** The longer explanations, in grey under the group. */
    let footer: String
    let rows: Rows

    init(_ title: String, footer: String = "", @ViewBuilder rows: () -> Rows) {
        self.title = title
        self.footer = footer
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
            if !footer.isEmpty {
                Text(verbatim: footer)
                    .font(.system(size: 13))
                    .lineSpacing(2)
                    .foregroundStyle(SettingsPalette.label)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
            }
        }
    }
}

/** The hairline between two rows of a group, inset like iOS. */
private struct SettingsSeparator: View {
    var body: some View {
        Rectangle()
            .fill(SettingsPalette.hairline)
            .frame(height: 0.5)
            .padding(.leading, SettingsTextInset)
    }
}

/** A setting: its icon, its name, what it does in a line, and the switch; the whole row toggles it. */
private struct SettingsToggleRow: View {
    let icon: SettingsIcon
    let title: String
    let text: String
    let on: Bool
    var enabled = true
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack(spacing: 12) {
            SettingsIconView(icon: icon)
                .opacity(enabled ? 1 : 0.5)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text(text)
                    .font(.system(size: 13))
                    .foregroundStyle(SettingsPalette.label)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            PressedSwitch(on: on && enabled)
        }
        .padding(.leading, 14)
        .padding(.trailing, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
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

/** A row that only says something; [done] puts a green check at its right. */
private struct SettingsInfoRow: View {
    let icon: SettingsIcon
    let title: String
    let text: String
    var done = false

    var body: some View {
        HStack(spacing: 12) {
            SettingsIconView(icon: icon)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text(text)
                    .font(.system(size: 13))
                    .foregroundStyle(SettingsPalette.label)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if done {
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(SettingsPalette.green)
                    .transition(.scale(scale: 0.3).combined(with: .opacity))
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .accessibilityElement(children: .combine)
    }
}

/** An action, the iOS way: colored words across the row, lined up with the titles above. */
private struct SettingsActionRow: View {
    let label: String
    var color: Color = SettingsPalette.accent
    var sound: UiSound.Kind = .tap
    let action: () -> Void

    var body: some View {
        Text(label)
            .font(.system(size: 16))
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, SettingsTextInset)
            .padding(.trailing, 16)
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
                .font(.system(size: 16))
                .foregroundStyle(SettingsPalette.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, SettingsTextInset)
                .padding(.trailing, 16)
                .padding(.vertical, 13)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .simultaneousGesture(TapGesture().onEnded { UiSound.play(.open) })
    }
}
