import Foundation

// CONTRACT (agent "style"): keep these names and signatures; fill the bodies. Port of UiSound.kt.

/** Soft synthesized sounds under the finger, one per gesture, with a light haptic. */
enum UiSound {
    enum Kind {
        case tap, on, off, open, close, success, like, unlike, play, pause, tick, swipe, musicUp, musicDown, recenter, silent
    }

    /** Synthesizes the sounds once (at launch). */
    static func prepare() {}

    /** Plays [kind] (if the setting allows) and gives a haptic tick; [long]: a long press. */
    static func play(_ kind: Kind, long: Bool = false) {}
}
