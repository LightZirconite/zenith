import SwiftUI

// Port of Style.kt: the liquid glass of every surface, the press under the finger, the arrivals and
// the fades shared by all the screens.

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

/**
 * The rim of a glass surface, as liquid glass catches the light: bright on the upper left edge,
 * almost gone along the middle, a softer glint on the lower right.
 */
private let GlassRim = LinearGradient(
    stops: [
        .init(color: .white.opacity(0.3), location: 0),
        .init(color: .white.opacity(0.07), location: 0.35),
        .init(color: .white.opacity(0.05), location: 0.7),
        .init(color: .white.opacity(0.16), location: 1),
    ],
    startPoint: .topLeading,
    endPoint: .bottomTrailing
)

/** Light catching the top of a glass surface. */
private let GlassSheen = LinearGradient(
    stops: [.init(color: .white.opacity(0.09), location: 0), .init(color: .clear, location: 0.5)],
    startPoint: .top,
    endPoint: .bottom
)

private let SwitchOn = Color(.sRGB, red: 0x30 / 255, green: 0xD1 / 255, blue: 0x58 / 255, opacity: 1)
private let SwitchOff = Color(.sRGB, red: 0x39 / 255, green: 0x39 / 255, blue: 0x3D / 255, opacity: 1)

extension EnvironmentValues {
    /** Inside a [tap] surface: the finger is on it (a switch inside stretches its knob). */
    @Entry var tapPressed: Bool = false
}

extension View {
    /**
     * A glass surface of [shape]: iOS's Liquid Glass beneath (the backdrop bends and softens), the
     * translucent dark fill of the drive screen lit from above, a rim catching the light. Clipped
     * to [shape]; a [sheen] after it stays inside.
     */
    func glass<S: Shape>(_ shape: S, color: Color = Glass) -> some View {
        background {
            ZStack {
                shape.fill(color)
                shape.fill(GlassSheen)
            }
        }
        // Half the stroke is clipped away: a 0.75 pt rim inside the edge.
        .overlay { shape.stroke(GlassRim, lineWidth: 1.5) }
        .clipShape(shape)
        // On an empty view, outside the clip: the glass bends what lies behind without touching the content.
        .background { Color.clear.glassEffect(.regular, in: shape) }
        .preference(key: GlassShapeKey.self, value: AnyShape(shape))
    }

    /** A band of light crossing the surface once as it appears, like glass catching the sun. After [glass]. */
    func sheen() -> some View { modifier(SheenPass()) }

    /** Arrives in turn with the rest of its screen: rises a little and fades in, [index] × 50 ms after the first. */
    func cascade(_ index: Int) -> some View { modifier(CascadeIn(index: index)) }

    /**
     * Tappable the liquid-glass way, instead of a plain highlight: under the finger the surface
     * swells ([lift]; below 1 it sinks, for big cards) and lights up from the touch within [shape]
     * ([glow]); let go, it springs back with a little bounce, even after a very brief tap. Each
     * press has its [sound] and a light haptic ([UiSound]); a long press opens (its own sound and
     * a heavier haptic, then [onLongPress]). Put it after the surface's own [glass] so the whole
     * surface moves. Text inside without a color of its own is white.
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
        modifier(LiquidTap(enabled: enabled, shape: shape, sound: sound, lift: lift, glow: glow, onLongPress: onLongPress, action: action))
    }

    /**
     * Fades the content out along its height: stops are (height fraction, opacity), 1 whole, 0 gone.
     * A real mask, not a dark band: what lies beneath shows through, as in a crossfade.
     */
    func verticalFade(_ stops: [FadeStop]) -> some View {
        mask { LinearGradient(stops: gradientStops(stops), startPoint: .top, endPoint: .bottom) }
    }

    /** Same as [verticalFade], across the width: stops are (width fraction, opacity). */
    func horizontalFade(_ stops: [FadeStop]) -> some View {
        mask { LinearGradient(stops: gradientStops(stops), startPoint: .leading, endPoint: .trailing) }
    }

    /** Folds the status bar (time, battery) away while [hidden]; the safe area keeps its room, nothing jumps. */
    func immersiveBars(hidden: Bool) -> some View { statusBarHidden(hidden) }
}

/** A fade's stops as a mask's: opacity is all that counts. */
private func gradientStops(_ stops: [FadeStop]) -> [Gradient.Stop] {
    stops.map { Gradient.Stop(color: .black.opacity(min(max($0.opacity, 0), 1)), location: $0.at) }
}

/** The shape of the glass beneath, so the light crossing it ([sheen]) stays inside. */
private struct GlassShapeKey: PreferenceKey {
    static let defaultValue: AnyShape? = nil

    static func reduce(value: inout AnyShape?, nextValue: () -> AnyShape?) {
        value = value ?? nextValue()
    }
}

/** The band of light of [sheen]: from left of the surface to past its right edge, once. */
private struct SheenPass: ViewModifier {
    @State private var pass: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .overlayPreferenceValue(GlassShapeKey.self) { shape in
                GeometryReader { geo in
                    let width = geo.size.width
                    let band = width * 0.3
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0),
                            .init(color: .white.opacity(0.2), location: 0.5),
                            .init(color: .clear, location: 1),
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    .frame(width: band * 2, height: geo.size.height)
                    .offset(x: width * (-0.4 + 1.8 * pass) - band)
                }
                .clipShape(shape ?? AnyShape(Rectangle()))
                .allowsHitTesting(false)
            }
            .onAppear {
                guard pass == 0 else { return }
                withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 1.1).delay(0.15)) { pass = 1 }
            }
    }
}

/** The arrival of [cascade]: up 36 pt, from 97 % and transparent, on a soft spring. */
private struct CascadeIn: ViewModifier {
    let index: Int
    @State private var k: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .opacity(Double(k))
            .offset(y: (1 - k) * 36)
            .scaleEffect(0.97 + 0.03 * k)
            .onAppear {
                guard k == 0 else { return }
                withAnimation(.spring(response: 0.44, dampingFraction: 0.8).delay(0.08 + 0.05 * Double(index))) { k = 1 }
            }
    }
}

/**
 * What a [tap] remembers between renders without redrawing: its size, a long press already
 * handled, a press seen going down (else the tap was too quick to show one).
 */
private final class PressMemo {
    var size = CGSize.zero
    var longFired = false
    var pressSeen = false
}

/**
 * The [tap] itself: a real button (scrolling cancels it, VoiceOver knows it), drawn by
 * [LiquidPressStyle]. Where the finger lifted comes from a tap watched alongside; a long press
 * swallows the tap that ends it.
 */
private struct LiquidTap<S: Shape>: ViewModifier {
    let enabled: Bool
    let shape: S
    let sound: UiSound.Kind?
    let lift: CGFloat
    let glow: Color
    let onLongPress: (() -> Void)?
    let action: () -> Void

    @State private var point = UnitPoint.center
    @State private var taps = 0
    @State private var memo = PressMemo()

    func body(content: Content) -> some View {
        Button {
            taps += 1
            if memo.longFired {
                memo.longFired = false
                return
            }
            if let sound { UiSound.play(sound) }
            action()
        } label: {
            content
        }
        .buttonStyle(LiquidPressStyle(shape: shape, lift: lift, glow: glow, point: point, taps: taps, memo: memo))
        .onGeometryChange(for: CGSize.self) { proxy in
            proxy.size
        } action: { size in
            memo.size = size
        }
        .simultaneousGesture(
            SpatialTapGesture().onEnded { value in
                let size = memo.size
                guard size.width > 0, size.height > 0 else { return }
                point = UnitPoint(
                    x: min(max(value.location.x / size.width, 0), 1),
                    y: min(max(value.location.y / size.height, 0), 1)
                )
            }
        )
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                guard let onLongPress else { return }
                memo.longFired = true
                UiSound.play(.open, long: true)
                onLongPress()
            },
            including: onLongPress == nil ? .subviews : .all
        )
        .disabled(!enabled)
    }
}

/** The look of a [tap] for its button: see [LiquidPressSurface]. */
private struct LiquidPressStyle<S: Shape>: ButtonStyle {
    let shape: S
    let lift: CGFloat
    let glow: Color
    let point: UnitPoint
    let taps: Int
    let memo: PressMemo

    func makeBody(configuration: Configuration) -> some View {
        LiquidPressSurface(
            label: configuration.label,
            pressed: configuration.isPressed,
            shape: shape,
            lift: lift,
            glow: glow,
            point: point,
            taps: taps,
            memo: memo
        )
    }
}

/**
 * The liquid press: the surface swells toward the finger on a spring and a light rises inside its
 * shape; let go, it springs back past its size and settles. A press too brief to be seen carries
 * on toward the swell first, then bounces back, so every tap shows.
 */
private struct LiquidPressSurface<S: Shape>: View {
    let label: ButtonStyleConfiguration.Label
    let pressed: Bool
    let shape: S
    let lift: CGFloat
    let glow: Color
    let point: UnitPoint
    let taps: Int
    let memo: PressMemo

    @State private var swell: CGFloat = 1
    @State private var light: Double = 0
    @State private var downAt = Date.distantPast

    var body: some View {
        label
            .foregroundStyle(.white)
            .environment(\.tapPressed, pressed)
            .contentShape(shape)
            .overlay {
                GeometryReader { geo in
                    let radius = max(geo.size.width, geo.size.height) * 0.85
                    RadialGradient(colors: [glow, glow.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: radius)
                        .frame(width: radius * 2, height: radius * 2)
                        .position(x: point.x * geo.size.width, y: point.y * geo.size.height)
                        .animation(.easeOut(duration: 0.18), value: point)
                }
                .clipShape(shape)
                .opacity(light)
                .allowsHitTesting(false)
            }
            .scaleEffect(swell)
            .onChange(of: pressed) { _, down in
                if down { pressDown() } else { letGo() }
            }
            .onChange(of: taps) { _, _ in
                // The button fired without the press being seen (a flick in a scroll view): still a bounce.
                if memo.pressSeen { memo.pressSeen = false } else { bounce() }
            }
    }

    private func pressDown() {
        downAt = Date()
        memo.pressSeen = true
        memo.longFired = false
        withAnimation(.spring(response: 0.28, dampingFraction: 0.7)) { swell = lift }
        withAnimation(.easeOut(duration: 0.12)) { light = 1 }
    }

    private func letGo() {
        if Date().timeIntervalSince(downAt) < 0.06 { bounce() } else { relax() }
    }

    /** A quick tap: toward the swell and the light, then back with the bounce. */
    private func bounce() {
        withAnimation(.easeOut(duration: 0.07)) {
            swell = lift
            light = 0.8
        } completion: {
            relax()
        }
    }

    private func relax() {
        withAnimation(.spring(response: 0.32, dampingFraction: 0.42)) { swell = 1 }
        withAnimation(.easeOut(duration: 0.48)) { light = 0 }
    }
}

/** A full page the iOS way over the sky of home: large title that shrinks into the bar. */
struct LargeTitlePage<Leading: View, Bottom: View, Content: View>: View {
    let title: String
    @ViewBuilder let leading: () -> Leading
    @ViewBuilder let bottom: () -> Bottom
    @ViewBuilder let content: () -> Content

    /** How far the large title has gone into the bar: 0 at the top of the page, 1 after 56 pt of scroll. */
    @State private var collapse: CGFloat = 0

    init(title: String, @ViewBuilder leading: @escaping () -> Leading, @ViewBuilder bottom: @escaping () -> Bottom, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.leading = leading
        self.bottom = bottom
        self.content = content
    }

    var body: some View {
        ZStack(alignment: .top) {
            SpaceBackdrop()
                .ignoresSafeArea()
            // Tablet or car screen: a readable column in the middle rather than rows across the whole width.
            VStack(spacing: 0) {
                ZStack {
                    Text(title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .opacity(Double(collapse))
                        .offset(y: (1 - collapse) * 10)
                    // Once the large title has gone into the bar, the back button follows it there.
                    HStack(spacing: 0) {
                        if collapse > 0.5 { leading().transition(.opacity) }
                        Spacer(minLength: 0)
                    }
                    .animation(.easeOut(duration: 0.2), value: collapse > 0.5)
                }
                .frame(height: 44)
                .padding(.horizontal, 20)

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        // The back button beside the large title ("‹ Réglages"), as the author asked.
                        HStack(spacing: 12) {
                            leading()
                            Text(title)
                                .font(.system(size: 34, weight: .bold))
                                .foregroundStyle(.white)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                        }
                        .opacity(Double(1 - collapse))
                        .scaleEffect(1 - 0.12 * collapse, anchor: .leading)
                        .padding(.top, 4)
                        content()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                }
                .scrollIndicators(.hidden)
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    min(max((geometry.contentOffset.y + geometry.contentInsets.top) / 56, 0), 1)
                } action: { _, k in
                    collapse = k
                }

                bottom()
                    .padding(.horizontal, 20)
            }
            .padding(.vertical, 12)
            .frame(maxWidth: PAGE_MAX_WIDTH, maxHeight: .infinity)
        }
    }
}

extension LargeTitlePage where Leading == EmptyView, Bottom == EmptyView {
    /** A page with neither back button nor pinned bottom. */
    init(title: String, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, leading: { EmptyView() }, bottom: { EmptyView() }, content: content)
    }
}

extension LargeTitlePage where Bottom == EmptyView {
    /** A page with a back button and nothing pinned under it. */
    init(title: String, @ViewBuilder leading: @escaping () -> Leading, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, leading: leading, bottom: { EmptyView() }, content: content)
    }
}

extension LargeTitlePage where Leading == EmptyView {
    /** A page with something pinned at the bottom (a big button) and no back button. */
    init(title: String, @ViewBuilder bottom: @escaping () -> Bottom, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, leading: { EmptyView() }, bottom: bottom, content: content)
    }
}

/** A chevron pointing back (‹), drawn: no symbol font needed. Sized by its frame. */
struct Chevron: View {
    let color: Color

    var body: some View {
        ChevronPath()
            .stroke(color, style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))
    }
}

/** The chevron's line, inset by half its width so the round caps stay inside. */
private struct ChevronPath: Shape {
    func path(in rect: CGRect) -> Path {
        let w: CGFloat = 2.4
        var path = Path()
        path.move(to: CGPoint(x: rect.maxX - w / 2, y: rect.minY + w / 2))
        path.addLine(to: CGPoint(x: rect.minX + w / 2, y: rect.midY))
        path.addLine(to: CGPoint(x: rect.maxX - w / 2, y: rect.maxY - w / 2))
        return path
    }
}

/**
 * The iOS switch, 51 × 31: the knob springs across and the track turns green; held down
 * ([pressed]), the knob stretches toward where it will go.
 */
struct GlassSwitch: View {
    let on: Bool
    var pressed: Bool = false

    var body: some View {
        let knob: CGFloat = pressed ? 33 : 27
        Capsule()
            .fill(on ? SwitchOn : SwitchOff)
            .animation(.easeInOut(duration: 0.25), value: on)
            .frame(width: 51, height: 31)
            .overlay(alignment: .leading) {
                Capsule()
                    .fill(.white)
                    .frame(width: knob, height: 27)
                    .shadow(color: .black.opacity(0.28), radius: 3, y: 1.5)
                    .offset(x: 2 + (on ? 47 - knob : 0))
                    // Across on its own spring; the stretch quicker. The switch's own wins when both change.
                    .animation(.spring(response: 0.31, dampingFraction: 0.62), value: on)
                    .animation(.spring(response: 0.16, dampingFraction: 0.7), value: pressed)
            }
            .accessibilityHidden(true)
    }
}
