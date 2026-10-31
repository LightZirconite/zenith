import AppIntents

/** Zenith's actions offered to Siri and Shortcuts without any setup ("Démarre Zenith"). */
struct DriveShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartDriveIntent(),
            phrases: ["Démarre \(.applicationName)", "Lance \(.applicationName)", "Démarrer la conduite avec \(.applicationName)"],
            shortTitle: "Démarrer la conduite",
            systemImageName: "gauge.with.needle"
        )
        AppShortcut(
            intent: StopDriveIntent(),
            phrases: ["Arrête \(.applicationName)", "Arrêter la conduite avec \(.applicationName)"],
            shortTitle: "Arrêter la conduite",
            systemImageName: "stop.circle"
        )
        AppShortcut(
            intent: CarConnectedIntent(),
            phrases: ["Voiture connectée à \(.applicationName)"],
            shortTitle: "Voiture connectée",
            systemImageName: "car"
        )
        AppShortcut(
            intent: CarDisconnectedIntent(),
            phrases: ["Voiture déconnectée de \(.applicationName)"],
            shortTitle: "Voiture déconnectée",
            systemImageName: "car.side.arrowtriangle.down"
        )
    }
}
