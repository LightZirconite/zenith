import SwiftUI
import UIKit

/** Colors and sizes of the music card and its backdrop. */
private enum MusicLook {
    static let dim = Color(red: 0xB0 / 255, green: 0xB0 / 255, blue: 0xB5 / 255)
    static let liked = Color(red: 0x1E / 255, green: 0xD7 / 255, blue: 0x60 / 255)
    static let coverWell = Color(red: 0x2C / 255, green: 0x2C / 255, blue: 0x30 / 255)
    static let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
    /** How far the strip must travel for a swipe to change track (about 120 pt of finger). */
    static let swipeThreshold: CGFloat = 72
    /** The strip moves slower than the finger. */
    static let resistance: CGFloat = 0.6
    /** No second change within this delay: a quick double swipe skips one track, not two. */
    static let swipeCooldown: TimeInterval = 1

    /**
     * Opacity of the backdrop down the screen: nothing above a fifth, a faint tail, whole from the
     * middle of the screen, where the map has melted away.
     */
    static let mask: [FadeStop] = [(0, 0), (0.2, 0), (0.3, 0.5), (0.42, 1), (1, 1)]
    /**
     * Music mode: it rises higher, whole from a third of the screen, but still through a long soft
     * fade under the map's top (a hard edge up there would spoil the crossfade).
     */
    static let maskFocus: [FadeStop] = [(0, 0), (0.05, 0), (0.18, 0.5), (0.33, 1), (1, 1)]
    /**
     * Landscape, across the backdrop's own width (62 % of the screen from the right): the reverse
     * of the map's fade over the Canvas, stop for stop on screen, so map and Canvas always add up to
     * a whole picture, no dark seam between them.
     */
    static let landMask: [FadeStop] = [(0, 0), (0.13, 0.45), (0.31, 0.82), (0.45, 1), (1, 1)]
    /** Landscape music mode (85 % of the screen): the reverse of the map's music mode the same way. */
    static let landMaskFocus: [FadeStop] = [(0, 0), (0.07, 0), (0.227, 0.55), (0.38, 0.88), (0.6, 1)]
}

/**
 * Music controls under the alerts, for Spotify and Apple Music: cover, title, artist and the
 * buttons a driver needs, on a glass strip over [MusicBackdrop]. The heart (Spotify) shows when
 * the strip is wide enough; a thin line at the bottom follows the track. A tap on the cover or the
 * title switches the music mode ([onTap]), a long press opens the player. Without Premium, Spotify
 * takes no command from Zenith: the buttons dim. Swiping the strip changes track like on the lock
 * screen, to the left the next one, to the right the previous one; never by accident: only a long,
 * clearly sideways swipe counts, a tick and a label tell when it will, coming back before letting
 * go cancels it, and a second change waits a second.
 */
struct MediaBar: View {
    let media: MediaInfo
    /** A tap on the cover or the title: music mode. */
    var onTap: () -> Void = {}

    @State private var swipe: CGFloat = 0
    @State private var armed = false
    @State private var tracking = false
    @State private var drift: CGFloat = 0
    @State private var lastY: CGFloat = 0
    @State private var lastChange = Date.distantPast
    @State private var width: CGFloat = 0
    @GestureState private var dragging = false

    var body: some View {
        let reveal = min(1, abs(swipe) / MusicLook.swipeThreshold)
        ZStack {
            // What letting go will do, revealed on the side the strip leaves.
            if swipe != 0 {
                Text(swipe < 0 ? "Suivant ›" : "‹ Précédent")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white.opacity(reveal >= 1 ? 0.95 : reveal * 0.5))
                    .padding(.horizontal, 18)
                    .frame(maxWidth: .infinity, alignment: swipe < 0 ? .trailing : .leading)
            }
            row
                .offset(x: swipe)
                .opacity(1 - min(0.6, abs(swipe) / (MusicLook.swipeThreshold * 2.5)))
        }
        .frame(maxWidth: .infinity)
        .frame(height: 72)
        .overlay(alignment: .bottom) {
            if let progress = media.progress {
                MediaProgressLine(progress: progress, playing: media.playing)
            }
        }
        .glass(MusicLook.shape)
        .clipShape(MusicLook.shape)
        .contentShape(MusicLook.shape)
        .simultaneousGesture(swipeGesture)
        .onChange(of: dragging) { _, now in
            // A swipe cut short by the system: the strip comes back all the same.
            if !now && swipe != 0 { settle() }
        }
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.size.width
        } action: { newWidth in
            width = newWidth
        }
        .padding(.bottom, 10)
    }

    private var row: some View {
        HStack(spacing: 0) {
            HStack(spacing: 12) {
                cover
                VStack(alignment: .leading, spacing: 2) {
                    Text(media.title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .contentTransition(.opacity)
                    if let artist = media.artist {
                        Text(artist)
                            .font(.system(size: 13))
                            .foregroundStyle(MusicLook.dim)
                            .lineLimit(1)
                            .contentTransition(.opacity)
                    }
                }
                .shadow(color: .black.opacity(0.8), radius: 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .animation(.easeInOut(duration: 0.3), value: media.id)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            // The music mode's own sound is played by the screen, which knows which way it goes.
            .tap(
                shape: RoundedRectangle(cornerRadius: 14, style: .continuous),
                sound: nil,
                lift: 0.98,
                onLongPress: { MusicCenter.shared.open() },
                action: onTap
            )
            .accessibilityElement(children: .combine)
            .accessibilityHint("Mode musique ; appui long : ouvrir le lecteur")

            // Below that width the heart would leave the title almost no room.
            if let liked = media.liked, width >= 340 {
                MediaHeartButton(liked: liked)
            }
            MediaGlyphButton(symbol: "backward.fill", label: "Précédent", enabled: media.canControl) { MusicCenter.shared.previous() }
            MediaPlayButton(playing: media.playing, enabled: media.canControl)
            MediaGlyphButton(symbol: "forward.fill", label: "Suivant", enabled: media.canControl) { MusicCenter.shared.next() }
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
    }

    private var cover: some View {
        MusicLook.coverWell
            .overlay {
                if let art = media.art {
                    Image(uiImage: art)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.35), value: media.art)
            .frame(width: 52, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 12)
            .updating($dragging) { _, state, _ in state = true }
            .onChanged { value in
                if !tracking {
                    tracking = true
                    drift = 0
                    lastY = 0
                }
                drift += abs(value.translation.height - lastY)
                lastY = value.translation.height
                let x = value.translation.width * MusicLook.resistance
                swipe = x
                let past = abs(x) >= MusicLook.swipeThreshold
                // A tick when the swipe would now change track; coming back cancels it.
                if past && !armed { UiSound.play(.tick) }
                armed = past
            }
            .onEnded { value in
                let finger = abs(value.translation.width)
                let now = Date()
                // Sideways enough (a scroll or a fumble drifts up or down) and not right after a change.
                if finger * MusicLook.resistance >= MusicLook.swipeThreshold && drift < finger * 0.5
                    && now.timeIntervalSince(lastChange) > MusicLook.swipeCooldown {
                    lastChange = now
                    UiSound.play(.swipe)
                    if value.translation.width < 0 { MusicCenter.shared.next() } else { MusicCenter.shared.previous() }
                }
                settle()
            }
    }

    private func settle() {
        tracking = false
        armed = false
        withAnimation(.spring(response: 0.45, dampingFraction: 0.6)) { swipe = 0 }
    }
}

/** Thin line along the bottom edge; redrawn four times a second while playing, never laid out again. */
private struct MediaProgressLine: View {
    let progress: PlaybackProgress
    let playing: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 0.25, paused: !playing)) { timeline in
            Canvas { context, size in
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.white.opacity(0.16)))
                let done = size.width * progress.fraction(at: timeline.date, playing: playing)
                context.fill(Path(CGRect(x: 0, y: 0, width: done, height: size.height)), with: .color(.white.opacity(0.85)))
            }
        }
        .frame(height: 3)
        .allowsHitTesting(false)
    }
}

/** Previous or next: a big touch target, a small symbol; dimmed while the player takes no command. */
private struct MediaGlyphButton: View {
    let symbol: String
    let label: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(.white)
            .opacity(enabled ? 1 : 0.35)
            .frame(width: 44, height: 44)
            .contentShape(Circle())
            .tap(enabled: enabled, shape: Circle(), lift: 1.15, action: action)
            .accessibilityLabel(label)
    }
}

/** Play / pause, the main button: a white disc; the symbol turns into the other one. */
private struct MediaPlayButton: View {
    let playing: Bool
    let enabled: Bool

    var body: some View {
        Circle()
            .fill(.white)
            .padding(3)
            .overlay {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(.black)
                    // The triangle's weight sits left of its box: nudged to look centered.
                    .offset(x: playing ? 0 : 1.5)
                    .contentTransition(.symbolEffect(.replace))
            }
            .animation(.spring(response: 0.3, dampingFraction: 0.6), value: playing)
            .frame(width: 48, height: 48)
            .opacity(enabled ? 1 : 0.35)
            .tap(
                enabled: enabled,
                shape: Circle(),
                sound: playing ? .pause : .play,
                lift: 1.12,
                glow: PressShade,
                action: { MusicCenter.shared.playPause() }
            )
            .accessibilityLabel(playing ? "Pause" : "Lecture")
    }
}

/** Spotify's Liked Songs: outlined heart, green and filled once liked, bouncing as it changes. */
private struct MediaHeartButton: View {
    let liked: Bool

    var body: some View {
        Image(systemName: liked ? "heart.fill" : "heart")
            .font(.system(size: 19, weight: .semibold))
            .foregroundStyle(liked ? MusicLook.liked : .white)
            .contentTransition(.symbolEffect(.replace))
            .symbolEffect(.bounce, value: liked)
            .animation(.spring(response: 0.3, dampingFraction: 0.5), value: liked)
            .frame(width: 44, height: 44)
            .contentShape(Circle())
            .tap(
                shape: Circle(),
                sound: liked ? .unlike : .like,
                lift: 1.15,
                action: { MusicCenter.shared.toggleLike() }
            )
            .accessibilityLabel(liked ? "Retirer des titres likés" : "Ajouter aux titres likés")
    }
}

/**
 * The music's mood at the scale of the screen: the Canvas video, else the blurred cover, rising
 * from the bottom under the map. [focus] 0…1: music mode. [calm]: an alert, it fades further.
 */
struct MusicBackdrop: View {
    let media: MediaInfo
    let canvas: Bool
    let calm: Bool
    var focus: Double = 0
    var fromRight: Bool = false

    /** The file whose first frame is up: the video fades in only then, never onto black. */
    @State private var shownFile: URL? = nil

    var body: some View {
        let file = canvas ? MusicCenter.shared.canvasFile : nil
        let videoUp = file != nil && shownFile == file
        let videoAlpha = videoUp ? min(1, (calm ? 0.35 : 0.85) + 0.15 * focus) : 0
        let artAlpha = min(1, (calm ? 0.25 : 0.5) + 0.3 * focus)
        ZStack {
            if let art = media.art {
                // A tiny copy upscaled is already soft; the blur smooths it out.
                Color.clear
                    .overlay {
                        Image(uiImage: CoverWash.of(art))
                            .resizable()
                            .interpolation(.medium)
                            .scaledToFill()
                    }
                    .blur(radius: 24, opaque: true)
                    .opacity(artAlpha)
                    .animation(.easeInOut(duration: 0.6), value: calm)
            }
            if let file {
                CanvasVideo(file: file, playing: media.playing) { shownFile = file }
                    .opacity(videoAlpha)
                    .animation(.easeInOut(duration: 0.9), value: videoAlpha)
            }
            // Darkening for the speed and the buttons to stay readable, stronger at the bottom; lighter in music mode.
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.35 - 0.2 * focus), location: 0),
                    .init(color: .black.opacity(0.45 - 0.2 * focus), location: 0.5),
                    .init(color: .black.opacity(0.75 - 0.05 * focus), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .clipped()
        .backdropFade(focus: focus, fromRight: fromRight)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private extension View {
    /**
     * The top fades to transparent (a mask, not a black band): faint high up, whole where the map
     * has gone. In landscape it comes in from the right instead, beside the map.
     */
    @ViewBuilder
    func backdropFade(focus: Double, fromRight: Bool) -> some View {
        if fromRight {
            horizontalFade(blendStops(MusicLook.landMask, MusicLook.landMaskFocus, focus))
        } else {
            verticalFade(blendStops(MusicLook.mask, MusicLook.maskFocus, focus))
        }
    }
}

/** A 24-pixel-wide copy of the cover: upscaled, it is a soft wash of its colors. */
@MainActor
private enum CoverWash {
    private static var last: (source: UIImage, wash: UIImage)?

    static func of(_ art: UIImage) -> UIImage {
        if let last, last.source === art { return last.wash }
        let width: CGFloat = 24
        let ratio = art.size.width > 0 ? art.size.height / art.size.width : 1
        let size = CGSize(width: width, height: min(48, max(1, (width * ratio).rounded())))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let wash = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            art.draw(in: CGRect(origin: .zero, size: size))
        }
        last = (art, wash)
        return wash
    }
}
