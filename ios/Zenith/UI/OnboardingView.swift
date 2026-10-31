import SwiftUI

// CONTRACT (agent "style"): port of Onboarding.kt. Keep the init.

struct OnboardingView: View {
    /** Seen before (an authorization was withdrawn since): "Il manque une autorisation". */
    let returning: Bool
    let onDone: () -> Void
    var body: some View {
        Button("C'est parti", action: onDone)
    }
}
