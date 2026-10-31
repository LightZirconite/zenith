import AVFoundation
import SwiftUI
import UIKit

/**
 * A Spotify Canvas (CanvasVideo.kt): the local [file] played muted and looping, cropped to fill.
 * It plays only while [playing] and while Zenith shows (scene active) or a drive runs; the player
 * is released with the view, and a new file is a new view and a new player. [onFirstFrame] comes
 * once the first frame is on screen. A video that cannot play is dropped silently: the cover stays.
 */
struct CanvasVideo: View {
    let file: URL
    let playing: Bool
    var onFirstFrame: () -> Void = {}

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        CanvasLoop(file: file, play: playing && (scenePhase == .active || DriveModel.shared.running), onFirstFrame: onFirstFrame)
            .id(file)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/** The player's layer in SwiftUI. */
private struct CanvasLoop: UIViewRepresentable {
    let file: URL
    let play: Bool
    let onFirstFrame: () -> Void

    func makeUIView(context: Context) -> CanvasLoopView {
        CanvasLoopView(file: file)
    }

    func updateUIView(_ view: CanvasLoopView, context: Context) {
        view.onFirstFrame = onFirstFrame
        view.wanted = play
    }

    static func dismantleUIView(_ view: CanvasLoopView, coordinator: ()) {
        view.tearDown()
    }
}

/** Owns the looping player and its layer; every call is safe in any state. Main thread. */
private final class CanvasLoopView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    var onFirstFrame: () -> Void = {}
    /** Wanted playing; paused otherwise (the decoder rests). */
    var wanted = false {
        didSet { sync() }
    }

    private let file: URL
    private let player = AVQueuePlayer()
    private var looper: AVPlayerLooper?
    private var watches: [NSKeyValueObservation] = []
    private var shown = false
    private var failed = false
    private var released = false

    init(file: URL) {
        self.file = file
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        player.isMuted = true
        // The drive decides when the screen stays on, not a muted loop.
        player.preventsDisplaySleepDuringVideoPlayback = false
        player.allowsExternalPlayback = false
        let looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: file))
        self.looper = looper
        if let videoLayer = layer as? AVPlayerLayer {
            videoLayer.videoGravity = .resizeAspectFill
            videoLayer.player = player
            watches.append(videoLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] observed, _ in
                guard observed.isReadyForDisplay else { return }
                Task { @MainActor in self?.firstFrame() }
            })
        }
        watches.append(looper.observe(\.status, options: [.initial, .new]) { [weak self] observed, _ in
            guard observed.status == .failed else { return }
            Task { @MainActor in self?.fail() }
        })
        // Paused by iOS behind another app: playing again on the way back.
        NotificationCenter.default.addObserver(self, selector: #selector(resume), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    required init?(coder: NSCoder) { nil }

    func tearDown() {
        guard !released else { return }
        released = true
        for watch in watches { watch.invalidate() }
        watches = []
        NotificationCenter.default.removeObserver(self)
        player.pause()
        looper?.disableLooping()
        looper = nil
        player.removeAllItems()
        (layer as? AVPlayerLayer)?.player = nil
    }

    @objc private func resume() {
        sync()
    }

    private func sync() {
        guard !failed, !released else { return }
        if wanted {
            if player.timeControlStatus == .paused {
                // A mixable session: the driver's music is never touched.
                AudioFocus.shared.ensureActive()
                player.play()
            }
        } else if player.timeControlStatus != .paused {
            player.pause()
        }
    }

    private func firstFrame() {
        guard !shown, !failed, !released else { return }
        shown = true
        onFirstFrame()
    }

    private func fail() {
        guard !failed, !released else { return }
        failed = true
        NSLog("Zenith canvas video: \(looper?.error?.localizedDescription ?? "failed")")
        tearDown()
        MusicCenter.shared.discardCanvas(file)
    }
}
