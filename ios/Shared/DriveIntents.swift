import AppIntents
import Foundation

// Actions of Zenith for Shortcuts, Siri and the Live Activity's button. iOS gives apps no event
// when the car's Bluetooth or CarPlay connects: a personal automation of the Shortcuts app does
// ("CarPlay connecté" or "Bluetooth : <voiture>", run immediately) and runs "Voiture connectée",
// which starts the drive in the background, like Android's car link. Compiled into the widget
// extension too (the Live Activity's "Arrêter" button), where perform() is never run: iOS runs a
// LiveActivityIntent in the app's process.

/** The car connected (Shortcuts automation): the drive starts in the background. */
struct CarConnectedIntent: AppIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Voiture connectée"
    static let description = IntentDescription("Démarre les alertes de Zenith en fond. À lancer par une automatisation Raccourcis quand CarPlay ou le Bluetooth de la voiture se connecte.")

    func perform() async throws -> some IntentResult {
        #if ZENITH_APP
        await MainActor.run { DriveEngine.shared.start(origin: .car) }
        #endif
        return .result()
    }
}

/** The car disconnected: a drive the car started ends; one started by hand goes on. */
struct CarDisconnectedIntent: AppIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Voiture déconnectée"
    static let description = IntentDescription("Arrête la conduite si c'est la voiture qui l'a démarrée.")

    func perform() async throws -> some IntentResult {
        #if ZENITH_APP
        await MainActor.run { DriveEngine.shared.stopIfStartedBy(.car) }
        #endif
        return .result()
    }
}

/** Siri or a shortcut: start a drive, as the Start button does. */
struct StartDriveIntent: AppIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Démarrer la conduite"
    static let description = IntentDescription("Démarre les alertes radars de Zenith.")

    func perform() async throws -> some IntentResult {
        #if ZENITH_APP
        await MainActor.run { DriveEngine.shared.start(origin: .hand) }
        #endif
        return .result()
    }
}

/** Siri, a shortcut, or the Live Activity's button: stop the drive. */
struct StopDriveIntent: AppIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Arrêter la conduite"
    static let description = IntentDescription("Arrête les alertes radars de Zenith.")

    func perform() async throws -> some IntentResult {
        #if ZENITH_APP
        await MainActor.run { DriveEngine.shared.stop() }
        #endif
        return .result()
    }
}
