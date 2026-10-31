import SwiftUI

// CONTRACT (agent "style"): keep these names and signatures; fill the bodies. Port of Style.kt.

/** Translucent "glass" of the drive screen, shared by the music strip, chips, cards and buttons. */
let Glass = Color(.sRGB, red: 0x1F / 255, green: 0x1F / 255, blue: 0x24 / 255, opacity: 0xA6 / 255)
/** Light under the finger on a dark surface; a white surface darkens a little instead. */
let PressLight = Color.white.opacity(0.2)
let PressShade = Color.black.opacity(0.1)
/** Widest a page or the drive screen's column grows on a tablet or a car screen. */
let PAGE_MAX_WIDTH: CGFloat = 640

/** (fraction of the height or width, opacity) stops of a fade mask. */
typealias FadeStop = (at: CGFloat, opacity: Double)

/** Stop by stop, [from] at 0 to [to] at 1; both lists the same length. */
func blendStops(_ from: [FadeStop], _ to: [FadeStop], _ k: Double) -> [FadeStop] {
    zip(from, to).map { a, b in (a.at + (b.at - a.at) * CGFloat(k), a.opacity + (b.opacity - a.opacity) * k) }
}

extension View {
    /** A glass surface of [shape]: translucent fill lit from above, a rim catching the light. */
    func glass<S: Shape>(_ shape: S, color: Color = Glass) -> some View {
        background(shape.fill(color)).clipShape(shape)
    }

    /** A band of light crossing the surface once as it appears. */
    func sheen() -> some View { self }

    /** Arrives in turn with the rest of its screen: rises a little and fades in, [index] × 50 ms after the first. */
    func cascade(_ index: Int) -> some View { self }

    /**
     * Tappable the liquid-glass way: swells under the finger ([lift]), lights up from the touch
     * point within [shape], springs back; plays [sound] with a light haptic; a long press opens.
     */
    func tap<S: Shape>(
        enabled: Bool = true,
        shape: S = Rectangle(),
        sound: UiSound.Kind? = .tap,
        lift: CGFloat = 1.05,
        glow: Color = PressLight,
        onLongPress: (() -> Void)? = nil,
        action: @escaping () -> Void
    ) -> some View {
        onTapGesture {
            guard enabled else { return }
            if let sound { UiSound.play(sound) }
            action()
        }
    }

    /** Fades the content out along its height: stops are (height fraction, opacity). */
    func verticalFade(_ stops: [FadeStop]) -> some View { self }

    /** Same across the width. */
    func horizontalFade(_ stops: [FadeStop]) -> some View { self }

    /** Folds the status bar away while [hidden], its room kept (nothing jumps). */
    func immersiveBars(hidden: Bool) -> some View { statusBarHidden(hidden) }
}

/** A full page the iOS way over the sky of home: large title that shrinks into the bar. */
struct LargeTitlePage<Leading: View, Bottom: View, Content: View>: View {
    let title: String
    @ViewBuilder let leading: () -> Leading
    @ViewBuilder let bottom: () -> Bottom
    @ViewBuilder let content: () -> Content

    init(title: String, @ViewBuilder leading: @escaping () -> Leading, @ViewBuilder bottom: @escaping () -> Bottom, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.leading = leading
        self.bottom = bottom
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading) {
            leading()
            Text(title).font(.largeTitle.bold())
            ScrollView { content() }
            bottom()
        }
    }
}

/** A chevron pointing back (‹), drawn. */
struct Chevron: View {
    let color: Color
    var body: some View { Image(systemName: "chevron.left").foregroundStyle(color) }
}

/** The iOS switch: the knob springs across and the track turns green; held down, the knob stretches. */
struct GlassSwitch: View {
    let on: Bool
    var pressed: Bool = false
    var body: some View { Capsule().fill(on ? Color.green : Color.gray).frame(width: 51, height: 31) }
}
