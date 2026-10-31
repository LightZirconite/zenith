import SwiftUI

// CONTRACT (agent "drive"): port of DriveScreen.kt (home + flight + dashboard). Reads the shared
// models (DriveModel, SettingsStore, MusicCenter, Updater) itself.

struct DriveScreen: View {
    let onOpenSettings: () -> Void
    @State private var model = DriveModel.shared

    var body: some View {
        VStack {
            Text(model.shownSpeed.map(String.init) ?? "–").font(.system(size: 96, weight: .bold)).foregroundStyle(.white)
            Button(model.running ? "Arrêter" : "Démarrer") {
                if model.running { DriveEngine.shared.stop() } else { DriveEngine.shared.start() }
            }
            Button("Réglages", action: onOpenSettings)
        }
    }
}
