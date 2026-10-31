import AVFoundation
import AVKit
import CoreMedia
import CoreVideo
import QuartzCore
import SwiftUI
import UIKit
import VideoToolbox

/**
 * The small window over Waze / Maps (Android's picture-in-picture). iOS has no free floating
 * window, so it is a video window: AVPictureInPictureController on an AVSampleBufferDisplayLayer
 * fed with frames of PipScreen rendered offscreen. The layer lives in the drive screen while
 * driving (PipAnchor); leaving Zenith opens the window by itself (setting), or start() opens it
 * now. Frames: about 1 a second while Zenith is in front (the window then opens on a fresh one), 4
 * a second in the window, 8 while the Canvas plays in it, 10 while the red frame pulses; none in the
 * background with the window closed. The drive over, the window closes rather than staying on the
 * last speed. Nothing at all on a device without picture-in-picture. Main thread.
 */
final class PipController: NSObject {
    static let shared = PipController()
    /** The window is open. */
    private(set) var isActive = false

    private let displayLayer = AVSampleBufferDisplayLayer()
    private var pip: AVPictureInPictureController?
    private weak var host: UIView?
    private var renderer: ImageRenderer<PipFrame>?
    private var pool: CVPixelBufferPool?
    private var poolWidth = 0
    private var poolHeight = 0
    private var tick: DispatchWorkItem?
    /** start() asked while the window could not open yet: tried again for a few seconds. */
    private var wantStart: Date?
    private var observers: [NSObjectProtocol] = []
    private let canvasPlayer = PipCanvasPlayer()

    /** Points × scale of a frame: Android's window sizes, 16:9 strip or square. */
    private static let scale: CGFloat = 2
    private static let strip = CGSize(width: 640, height: 360)
    private static let square = CGSize(width: 480, height: 480)

    private override init() {
        super.init()
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
    }

    /** Starts it now (button), if possible. */
    func start() {
        MainActor.assumeIsolated {
            guard let pip else { return }
            AudioFocus.shared.ensureActive()
            if pip.isPictureInPicturePossible {
                pip.startPictureInPicture()
            } else {
                wantStart = Date()
                schedule(after: 0.1)
            }
        }
    }

    func stop() {
        MainActor.assumeIsolated {
            wantStart = nil
            if let pip, pip.isPictureInPictureActive { pip.stopPictureInPicture() }
        }
    }

    // MARK: - Layer host

    @MainActor
    fileprivate func attach(_ view: UIView) {
        if host !== view {
            host = view
            displayLayer.removeFromSuperlayer()
            view.layer.addSublayer(displayLayer)
        }
        layout(view)
        prepare()
    }

    @MainActor
    fileprivate func layout(_ view: UIView) {
        guard host === view else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.frame = view.bounds
        CATransaction.commit()
    }

    @MainActor
    fileprivate func detach(_ view: UIView) {
        guard host === view else { return }
        host = nil
        displayLayer.removeFromSuperlayer()
        teardown()
    }

    // MARK: - Window

    @MainActor
    private func prepare() {
        guard pip == nil, AVPictureInPictureController.isPictureInPictureSupported() else { return }
        // Picture-in-picture needs a playback audio session, mixable here: the music is never touched.
        AudioFocus.shared.ensureActive()
        let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: displayLayer, playbackDelegate: self)
        let controller = AVPictureInPictureController(contentSource: source)
        controller.delegate = self
        controller.requiresLinearPlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = SettingsStore.shared.autoPip
        pip = controller
        let center = NotificationCenter.default
        observers = [
            // About to leave Zenith: the session active and a fresh frame, so the window opens at once.
            center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                self?.leaving()
            },
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                self?.schedule(after: 0)
            },
        ]
        schedule(after: 0)
    }

    private func leaving() {
        MainActor.assumeIsolated {
            guard pip != nil else { return }
            AudioFocus.shared.ensureActive()
            render()
        }
    }

    @MainActor
    private func teardown() {
        tick?.cancel()
        tick = nil
        wantStart = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        if let pip, pip.isPictureInPictureActive { pip.stopPictureInPicture() }
        pip?.delegate = nil
        pip = nil
        isActive = false
        renderer = nil
        pool = nil
        canvasPlayer.follow(file: nil, playing: false)
        displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }

    private func windowOpen(_ open: Bool) {
        isActive = open
        schedule(after: 0)
    }

    // MARK: - Frames

    private func schedule(after seconds: TimeInterval) {
        tick?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.step() }
        tick = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func step() {
        MainActor.assumeIsolated { stepOnMain() }
    }

    @MainActor
    private func stepOnMain() {
        guard let pip else { return }
        // The drive is over: the window closes rather than staying on the last speed.
        guard DriveModel.shared.running else {
            teardown()
            return
        }
        let settings = SettingsStore.shared
        if pip.canStartPictureInPictureAutomaticallyFromInline != settings.autoPip {
            pip.canStartPictureInPictureAutomaticallyFromInline = settings.autoPip
        }
        let media = MusicCenter.shared.media
        canvasPlayer.follow(
            file: isActive && settings.spotifyCanvas ? MusicCenter.shared.canvasFile : nil,
            playing: media?.playing == true
        )
        if isActive || UIApplication.shared.applicationState != .background { render() }
        if let asked = wantStart {
            if pip.isPictureInPicturePossible {
                wantStart = nil
                pip.startPictureInPicture()
            } else if Date().timeIntervalSince(asked) > 3 {
                wantStart = nil
            }
        }
        let danger = DriveModel.shared.snapshot?.alerts.first?.overLimit == true
        let interval: TimeInterval
        if isActive {
            interval = danger ? 0.1 : canvasPlayer.showing ? 0.125 : 0.25
        } else {
            interval = wantStart != nil ? 0.2 : 1
        }
        schedule(after: interval)
    }

    /** One frame of the window into the layer. */
    @MainActor
    private func render() {
        let model = DriveModel.shared
        let music = MusicCenter.shared
        let settings = SettingsStore.shared
        let size = settings.pipLarge ? Self.square : Self.strip
        let screen = PipScreen(
            snapshot: model.snapshot,
            radars: model.nearbyRadars,
            zones: model.nearbyZones,
            media: music.media,
            canvas: settings.spotifyCanvas,
            musicChoice: model.musicChoice,
            speedKmh: model.shownSpeed,
            roads: model.nearbyRoads,
            canvasFrame: isActive ? canvasPlayer.frame() : nil,
            now: Date()
        )
        let content = PipFrame(screen: screen, size: size)
        let renderer: ImageRenderer<PipFrame>
        if let existing = self.renderer {
            existing.content = content
            renderer = existing
        } else {
            renderer = ImageRenderer(content: content)
            self.renderer = renderer
        }
        renderer.proposedSize = ProposedViewSize(size)
        guard let buffer = pixelBuffer(width: Int(size.width * Self.scale), height: Int(size.height * Self.scale)),
              draw(renderer, into: buffer),
              let sample = Self.sampleBuffer(buffer) else { return }
        let output = displayLayer.sampleBufferRenderer
        if output.status == .failed || output.requiresFlushToResumeDecoding { output.flush() }
        output.enqueue(sample)
    }

    /** Draws the view straight into the pixel buffer with Core Graphics: no GPU, so it works in the background. */
    @MainActor
    private func draw(_ renderer: ImageRenderer<PipFrame>, into buffer: CVPixelBuffer) -> Bool {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        guard let base = CVPixelBufferGetBaseAddress(buffer),
              let ctx = CGContext(
                data: base,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
              ) else { return false }
        ctx.setFillColor(gray: 0, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.scaleBy(x: Self.scale, y: Self.scale)
        renderer.render(rasterizationScale: Self.scale) { _, drawView in drawView(ctx) }
        return true
    }

    /** BGRA buffers backed by IOSurface, recycled once the layer is done with them. */
    private func pixelBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        if pool == nil || poolWidth != width || poolHeight != height {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
            ]
            var created: CVPixelBufferPool?
            CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &created)
            pool = created
            poolWidth = width
            poolHeight = height
        }
        guard let pool else { return nil }
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer)
        return buffer
    }

    /** The frame as a sample shown as soon as it is enqueued (no timebase: a live picture). */
    private static func sampleBuffer(_ buffer: CVPixelBuffer) -> CMSampleBuffer? {
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: buffer, formatDescriptionOut: &format) == noErr,
              let format else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: buffer,
            formatDescription: format,
            sampleTiming: &timing,
            sampleBufferOut: &sample
        ) == noErr, let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let first = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                first,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        return sample
    }
}

// MARK: - Delegates

extension PipController: AVPictureInPictureControllerDelegate {
    func pictureInPictureControllerWillStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        DispatchQueue.main.async { [weak self] in self?.windowOpen(true) }
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        DispatchQueue.main.async { [weak self] in self?.windowOpen(false) }
    }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        NSLog("Zenith pip: \(error)")
        DispatchQueue.main.async { [weak self] in self?.windowOpen(false) }
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping @Sendable (Bool) -> Void
    ) {
        completionHandler(true)
    }
}

/** A live picture: always playing, no end, nothing to skip. */
extension PipController: AVPictureInPictureSampleBufferPlaybackDelegate {
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool) {}

    func pictureInPictureControllerTimeRangeForPlayback(_ pictureInPictureController: AVPictureInPictureController) -> CMTimeRange {
        CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
    }

    func pictureInPictureControllerIsPlaybackPaused(_ pictureInPictureController: AVPictureInPictureController) -> Bool { false }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime,
        completion completionHandler: @escaping @Sendable () -> Void
    ) {
        completionHandler()
    }

    /** Zenith's alerts keep sounding with the window open. */
    func pictureInPictureControllerShouldProhibitBackgroundAudioPlayback(_ pictureInPictureController: AVPictureInPictureController) -> Bool { false }
}

// MARK: - Pieces

/** PipScreen at the window's size, for the offscreen renderer. */
private struct PipFrame: View {
    let screen: PipScreen
    let size: CGSize

    var body: some View {
        screen.frame(width: size.width, height: size.height)
    }
}

/**
 * The Spotify Canvas for the window, like Android's: the track's local video played muted and
 * looping while the window is open and the music plays, a frame taken for each render. Its first
 * frame shows it; if frames stop coming while it plays (decoding refused in the background, broken
 * file), none is given and the cover glows instead. Main thread.
 */
private final class PipCanvasPlayer {
    private var file: URL?
    private var player: AVPlayer?
    private var output: AVPlayerItemVideoOutput?
    private var loop: NSObjectProtocol?
    private var last: CGImage?
    private var lastAt = Date.distantPast
    private var playingSince = Date.distantPast

    /** A frame is on show: the window renders faster so the video moves. */
    private(set) var showing = false

    /** Follows the current track's video; nil releases the player. */
    func follow(file: URL?, playing: Bool) {
        if file != self.file {
            release()
            if let file { open(file) }
        }
        guard let player else { return }
        if playing && player.timeControlStatus == .paused {
            playingSince = Date()
            player.play()
        } else if !playing && player.timeControlStatus != .paused {
            player.pause()
        }
    }

    /** The newest frame; nil before the first one, or once frames stop coming while it plays. */
    func frame() -> CGImage? {
        guard let player, let output, player.currentItem?.status != .failed else {
            showing = false
            return nil
        }
        let time = output.itemTime(forHostTime: CACurrentMediaTime())
        if output.hasNewPixelBuffer(forItemTime: time), let buffer = output.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil) {
            var image: CGImage?
            if VTCreateCGImageFromCVPixelBuffer(buffer, options: nil, imageOut: &image) == noErr, let image {
                last = image
                lastAt = Date()
            }
        }
        let stalled = player.timeControlStatus == .playing && Date().timeIntervalSince(max(lastAt, playingSince)) > 2
        showing = last != nil && !stalled
        return showing ? last : nil
    }

    private func open(_ url: URL) {
        let item = AVPlayerItem(url: url)
        // Small BGRA frames: enough for a window, cheap to turn into images.
        let out = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 360,
            kCVPixelBufferHeightKey as String: 640,
        ])
        item.add(out)
        let p = AVPlayer(playerItem: item)
        p.isMuted = true
        p.actionAtItemEnd = .none
        p.allowsExternalPlayback = false
        p.preventsDisplaySleepDuringVideoPlayback = false
        p.audiovisualBackgroundPlaybackPolicy = .continuesIfPossible
        loop = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak p] _ in
            p?.seek(to: .zero)
        }
        file = url
        player = p
        output = out
    }

    private func release() {
        if let loop { NotificationCenter.default.removeObserver(loop) }
        loop = nil
        player?.pause()
        player = nil
        output = nil
        file = nil
        last = nil
        lastAt = .distantPast
        showing = false
    }
}

/** Hosts the window's layer in the drive screen while driving: iOS needs it on screen to open the window. */
struct PipAnchor: View {
    var body: some View {
        PipLayerHost()
            .frame(width: 2, height: 2)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .ignoresSafeArea()
    }
}

/** The view holding PipController's display layer, a 2-point dot in the top corner. */
private struct PipLayerHost: UIViewRepresentable {
    func makeUIView(context: Context) -> PipHostView { PipHostView() }

    func updateUIView(_ uiView: PipHostView, context: Context) {}

    static func dismantleUIView(_ uiView: PipHostView, coordinator: ()) {
        PipController.shared.detach(uiView)
    }
}

private final class PipHostView: UIView {
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { PipController.shared.attach(self) }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        PipController.shared.layout(self)
    }
}
