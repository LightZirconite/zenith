import SwiftUI

// CONTRACT (agent "style"): port of SettingsScreen.kt. Keep the init.

struct SettingsView: View {
    let onBack: () -> Void
    var body: some View {
        LargeTitlePage(title: "Réglages", leading: { Button("Retour", action: onBack) }, bottom: { EmptyView() }) {
            Text("Réglages")
        }
    }
}
