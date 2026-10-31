import SwiftUI
import UIKit

/**
 * The one screen of Zenith (port of DriveScreen.kt). At home the logo breathes over drifting stars;
 * "Démarrer" is a flight through space: a tunnel while the GPS searches, a jump at the first good
 * position (once the roads around it have loaded), and the scene opens like a portal at the flash.
 * Then the drive: tilted map, big speed, limit sign, alerts, music and the buttons, for portrait,
 * landscape, tablets and car screens. Opened while a drive runs (back from Waze): straight to the
 * map. A drive started from here always flies, even when its first position comes before the
 * screen sees it running (the demo's comes within milliseconds).
 */
struct DriveScreen: View {
    let onOpenSettings: () -> Void

    @State private var model = DriveModel.shared
    @State private var music = MusicCenter.shared
    @State private var settings = SettingsStore.shared
    @State private var updater = Updater.shared
    @State private var crash = CrashGuard.shared
    @Environment(\.scenePhase) private var scenePhase

    /** Opened while a drive was already running: no flight. */
    @State private var openedMidDrive: Bool
    /** The flags of the drive under way (or of home), as last stored; read through [flight]. */
    @State private var stored: Flight
    /** The phase the sounds last followed. */
    @State private var heardPhase: FlightPhase?
    /** The way back takes the whole screen too: the status bar comes back with home. */
    @State private var homeBound = false
    /** Where the driver is, kept after the drive: the globe dives onto it, and the way back leaves from it. */
    @State private var home: GeoPoint?
    /** The scene's portal: 0 closed, 1 open. */
    @State private var portal: Double
    @State private var sceneUp: Bool
    /** Radars (or sections) passed: each sends a band of light down the screen. */
    @State private var passes = 0
    @State private var passFlash = false
    @State private var navApps: [NavApp] = []

    /** The flight lasts at least this long, even when the position comes at once (demo, warm GPS). */
    fileprivate static let minFlightMs = 3_500
    /** The music steps aside for the flight, but not longer than this if the GPS takes its time. */
    fileprivate static let musicAwayMaxMs = 12_000
    /** A position without a trusted speed (indoors) still lands after this wait. */
    fileprivate static let weakFixWaitMs = 15_000
    /** Position locked, the roads around it still loading (slow network): the flight lands anyway after this. */
    fileprivate static let roadsWaitMs = 20_000

    init(onOpenSettings: @escaping () -> Void) {
        self.onOpenSettings = onOpenSettings
        let model = DriveModel.shared
        let landed = model.running && model.snapshot?.speedKmh != nil
        _openedMidDrive = State(initialValue: model.running)
        _stored = State(initialValue: Flight(running: model.running, landed: landed, flightOver: landed))
        _portal = State(initialValue: landed ? 1 : 0)
        _sceneUp = State(initialValue: model.running)
    }

    // MARK: State of the flight

    /**
     * The flags as they stand: reset at once when the drive starts or stops (as Compose's
     * `remember(running)`), before the change is even stored.
     */
    private var flight: Flight {
        stored.running == model.running ? stored
            : stored.next(running: model.running, openedMidDrive: openedMidDrive, speedKnown: model.snapshot?.speedKmh != nil)
    }

    private func change(_ edit: (inout Flight) -> Void) {
        var f = flight
        edit(&f)
        if f != stored { stored = f }
    }

    private var hasFix: Bool { model.snapshot != nil }

    private var fixGood: Bool {
        hasFix && (model.snapshot?.speedKmh != nil || flight.weakFixWaited || model.demo)
    }

    /** The roads around the position are in (or long awaited): the map lands whole. */
    private var roadsOk: Bool { model.demo || model.data.roadsReady || flight.roadsWaited }

    private var phase: FlightPhase {
        guard model.running else { return .idle }
        return flight.landed || (fixGood && roadsOk && flight.minFlightDone) ? .arrive : .search
    }

    /** The demo has no GPS: its satellites lock by themselves during the flight. */
    private var sky: GpsInfo { model.demo ? GpsInfo(used: flight.demoLocked, visible: 9) : model.gps }

    private var showMap: Bool { model.running && hasFix && flight.landed }

    private var homeView: HomeView {
        !model.running ? .idle : !flight.landed ? .flight : .drive
    }

    /**
     * Music mode: slow or stopped, the Canvas rises up the screen, more the slower; from town speeds
     * the map has it. A tap on the music card decides instead until the speed agrees; an alert
     * always brings the map back.
     */
    private var focusTarget: Double {
        guard music.media != nil else { return 0 }
        let s = model.snapshot
        let alerting = !(s?.alerts.isEmpty ?? true) || s?.section != nil
        return Cockpit.music(speedKmh: s?.speedKmh, choice: model.musicChoice, alerting: alerting)
    }

    private var shortcutsShown: Bool {
        model.running && flight.landed && !navApps.isEmpty && !flight.cruising
    }

    private var updateVersion: String? {
        if case .available(let version, _) = updater.state { return version }
        return nil
    }

    // MARK: Screen

    var body: some View {
        cues(timers(screen))
    }

    private var screen: some View {
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            ZStack(alignment: .top) {
                GeometryReader { full in
                    layers(size: full.size, landscape: landscape)
                }
                .ignoresSafeArea()
                column(width: geo.size.width, landscape: landscape, tall: geo.size.height >= 560)
            }
        }
        .background(Color.black.ignoresSafeArea())
        // The flight takes the whole screen: the status bar folds away until the map is there.
        .immersiveBars(hidden: (model.running && !flight.flightOver) || homeBound)
    }

    /** Everything drawn across the whole screen, behind and over the column. */
    @ViewBuilder
    private func layers(size: CGSize, landscape: Bool) -> some View {
        let f = flight
        let phase = self.phase
        let snapshot = model.snapshot
        ZStack(alignment: .topLeading) {
            if !f.flightOver {
                Starfield(
                    phase: phase,
                    gps: sky,
                    onDive: { DispatchQueue.main.async { change { $0.diving = true } } },
                    onLanded: {
                        DispatchQueue.main.async {
                            change { $0.landed = true }
                            // At the boom: the music comes back, low under the chord, then rises.
                            WarpSound.shared.musicBack()
                        }
                    },
                    onGone: { DispatchQueue.main.async { change { $0.flightOver = true } } },
                    comingHome: f.comingHome,
                    home: home,
                    heardAt: { WarpSound.shared.heardAt($0) }
                )
                .frame(width: size.width, height: size.height)
                // The way back starts under the fading scene at once.
                .transition(.asymmetric(
                    insertion: f.comingHome ? AnyTransition.identity : Moves.fade(Moves.tween(800)),
                    removal: Moves.fade(Moves.tween(200))
                ))
            }
            // The scene (Canvas and map) is built at Start: the map and the Canvas load during the
            // flight and are there at the arrival, when it opens like a portal from the tunnel's heart.
            if model.running || sceneUp {
                SceneLayer(
                    focus: focusTarget,
                    withMusic: music.media == nil ? 0 : 1,
                    portal: portal,
                    running: model.running,
                    landscape: landscape,
                    size: size,
                    snapshot: snapshot,
                    radars: model.nearbyRadars,
                    zones: model.nearbyZones,
                    media: music.media,
                    canvas: settings.spotifyCanvas
                )
                .animation(Moves.spring(1, 50), value: focusTarget)
                .animation(Moves.tween(900), value: music.media == nil)
                if model.running {
                    PortalRing(progress: portal)
                        .frame(width: size.width, height: size.height)
                        .allowsHitTesting(false)
                }
            }
            // The flight's words, under the globe at the tunnel's heart; "Position verrouillée" a
            // beat, then they rush past the viewer as the jump begins.
            if phase == .search || (phase == .arrive && !f.diving && !f.landed) {
                FlightCaption(gps: sky, demo: model.demo, locked: phase == .arrive, roadsLoading: fixGood && !roadsOk)
                    .padding(.top, size.height * HEART_Y + min(size.width, size.height) * GLOBE_SHARE * 1.75 + 16)
                    .frame(width: size.width, height: size.height, alignment: .top)
                    .allowsHitTesting(false)
                    .transition(.asymmetric(
                        insertion: Moves.fade(Moves.tween(700, delay: 900))
                            .combined(with: AnyTransition.offset(y: size.height / 4).animation(Moves.tween(900, delay: 900))),
                        removal: Moves.fade(Moves.tween(300)).combined(with: Moves.zoom(1.8, Moves.tween(400)))
                    ))
            }
            // Too fast near a radar: the edges of the screen pulse red.
            DangerGlow(on: f.landed && snapshot?.alerts.first?.overLimit == true)
                .frame(width: size.width, height: size.height)
                .allowsHitTesting(false)
            // Each radar (or section) passed: a band of light sweeps down the screen, as under a gantry.
            KeyframeAnimator(initialValue: 1.0, trigger: passes) { progress in
                RadarPassWave(progress: progress, flash: passFlash)
            } keyframes: { _ in
                KeyframeTrack(\.self) {
                    MoveKeyframe(0.0)
                    LinearKeyframe(1.0, duration: 1.2, timingCurve: Moves.linearOutSlowInCurve)
                }
            }
            .frame(width: size.width, height: size.height)
            .allowsHitTesting(false)
        }
        .frame(width: size.width, height: size.height)
    }

    /** Chips, banners, home or the speed, alerts, music and buttons, within the safe area. */
    @ViewBuilder
    private func column(width: CGFloat, landscape: Bool, tall: Bool) -> some View {
        let f = flight
        let running = model.running
        let alertSnapshot = running && f.landed ? model.snapshot : nil
        VStack(spacing: 0) {
            StatusRow(running: running && f.landed, gps: model.gps, data: model.data, demo: model.demo, snapshot: model.snapshot)
            UpdateBanner(version: updateVersion)
            CrashBanner(report: running ? nil : crash.report)
            if landscape {
                // The map (or home) on the left with the alerts under it, the speed and the
                // buttons in a column on the right, all within the screen's short height.
                let inner = max(0, width - 60)
                let right = inner * (1 - Fades.landSplit)
                HStack(spacing: 0) {
                    VStack(spacing: 0) {
                        homeArea(landscape: true)
                        AlertArea(snapshot: alertSnapshot)
                    }
                    .frame(width: inner * Fades.landSplit)
                    Color.clear.frame(width: 20, height: 1)
                    // A wide car screen: the column keeps a sensible width, centred in its room.
                    VStack(spacing: 0) {
                        landscapeSpeed(tall: tall)
                        Color.clear.frame(height: 12)
                        musicCard
                        buttons(landscape: true, rowWidth: min(right, 520))
                    }
                    .frame(maxWidth: 520)
                    .frame(width: right)
                    .frame(maxHeight: .infinity)
                }
                .frame(maxHeight: .infinity)
            } else {
                homeArea(landscape: false)
                AlertArea(snapshot: alertSnapshot)
                Color.clear.frame(height: 12)
                musicCard
                navShortcuts
                buttons(landscape: false, rowWidth: 0)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        // Upright on a tablet: one column in the middle, not buttons stretched across the screen.
        .frame(maxWidth: landscape ? .infinity : PAGE_MAX_WIDTH)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(Moves.spring(0.85, 400), value: LayoutCue(
            view: homeView,
            alert: alertSnapshot?.alerts.first != nil,
            upcoming: alertSnapshot?.upcoming != nil,
            section: alertSnapshot?.section != nil,
            media: running && f.landed && music.media != nil,
            shortcuts: shortcutsShown,
            compact: running && f.cruising,
            update: updateVersion != nil,
            crash: !running && crash.report != nil
        ))
    }

    /** Home, the flight and the speed swap with a zoom; a little lower than the middle while driving. */
    @ViewBuilder
    private func homeArea(landscape: Bool) -> some View {
        let comingHome = flight.comingHome
        ZStack {
            switch homeView {
            case .idle:
                IdleHome(data: model.data)
                    .transition(.asymmetric(
                        // Back from a drive, home comes out of the distance as the Earth drifts
                        // away; after a flight cut short, as the stars slow down.
                        insertion: comingHome ? Moves.homecomingIn : Moves.cutShortIn,
                        // Start: home rushes past the viewer as the tunnel forms.
                        removal: Moves.rushPast
                    ))
            case .flight:
                // The flight's words sit under the globe, drawn over the whole screen.
                Color.clear.frame(width: 1, height: 1)
            case .drive:
                if landscape {
                    // In landscape the speed has its own column, beside the map.
                    Color.clear.frame(width: 1, height: 1)
                } else {
                    Dashboard(snapshot: model.snapshot, speed: model.shownSpeed)
                        .padding(.top, showMap ? 72 : 0)
                        .transition(.asymmetric(insertion: Moves.landing, removal: Moves.stepBack))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private func landscapeSpeed(tall: Bool) -> some View {
        if homeView == .drive {
            // A tablet or car screen across has the height for the full-size speed; a phone across does not.
            Dashboard(snapshot: model.snapshot, speed: model.shownSpeed, compact: !tall)
                .transition(.asymmetric(insertion: Moves.landing, removal: Moves.stepBack))
        }
    }

    /** After the arrival, the rest settles in one after the other, from the speed down to the music. */
    @ViewBuilder
    private var musicCard: some View {
        if model.running && flight.landed, let media = music.media {
            MediaBar(media: media, onTap: chooseMusic)
                .transition(.asymmetric(
                    insertion: Moves.fade(Moves.tween(400, delay: 200)).combined(with: Moves.rise(0.5, Moves.spring(0.75, 400))),
                    removal: Moves.driveOut
                ))
        }
    }

    @ViewBuilder
    private var navShortcuts: some View {
        if shortcutsShown {
            NavAppsRow(apps: navApps, fillHeight: false)
                .padding(.bottom, 10)
                .transition(.asymmetric(
                    insertion: Moves.fade(Moves.tween(400, delay: 350)).combined(with: Moves.rise(0.5, Moves.spring(0.75, 400))),
                    removal: Moves.driveOut
                ))
        }
    }

    /** Start / Stop (on the road: lower and quieter), then Réglages and the credits at home. */
    @ViewBuilder
    private func buttons(landscape: Bool, rowWidth: CGFloat) -> some View {
        let running = model.running
        let compact = running && flight.cruising
        if landscape {
            // The shortcuts and Stop on one line, the same height, side by side.
            let share = CGFloat(max(navApps.count, 1))
            HStack(spacing: 10) {
                if shortcutsShown {
                    NavAppsRow(apps: navApps, fillHeight: true)
                        .frame(width: max(0, rowWidth - 10) * share / (share + 1))
                        .frame(maxHeight: .infinity)
                        .transition(.asymmetric(
                            insertion: Moves.fade(Moves.tween(400, delay: 350)).combined(with: Moves.zoom(0.9, Moves.spring(0.75, 400))),
                            removal: Moves.fade(Moves.tween(250))
                        ))
                }
                StartStop(running: running, compact: compact, fillHeight: true)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .fixedSize(horizontal: false, vertical: true)
        } else {
            StartStop(running: running, compact: compact, fillHeight: false)
        }
        if !running {
            let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
            Text("Réglages")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .glass(shape)
                .tap(shape: shape, sound: .open, lift: 1.03, action: onOpenSettings)
                .padding(.top, 10)
                .transition(Moves.bottom(late: flight.comingHome))
        }
        if !running {
            Text("Cartes © OpenFreeMap © OpenStreetMap · radars data.gouv.fr, OpenStreetMap, Lufop")
                .font(.system(size: 10))
                .foregroundStyle(Ink.dim.opacity(0.6))
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 10)
                .transition(Moves.bottom(late: flight.comingHome))
        }
    }

    /** A tap on the music card: the other room for the music, until the speed agrees. */
    private func chooseMusic() {
        let big = Cockpit.toggle(shown: focusTarget)
        UiSound.play(big ? .musicUp : .musicDown)
        model.chooseMusic(big)
    }

    // MARK: Timers and sounds

    private func timers<V: View>(_ content: V) -> some View {
        content
            .task(id: model.running) {
                guard model.running else { return }
                if await Self.pause(Self.minFlightMs) { change { $0.minFlightDone = true } }
            }
            // Indoors or in a deep street the speed may stay unknown: the map comes anyway after a while.
            .task(id: [model.running, hasFix]) {
                guard model.running && hasFix else { return }
                if await Self.pause(Self.weakFixWaitMs) { change { $0.weakFixWaited = true } }
            }
            // Locked on: the flight stays in the tunnel while the roads around load, so the map
            // lands whole (limits and radars on the road); offline, it does not wait forever.
            .task(id: [model.running, fixGood]) {
                guard model.running && fixGood else { return }
                if await Self.pause(Self.roadsWaitMs) { change { $0.roadsWaited = true } }
            }
            .task(id: [model.running, model.demo]) {
                while model.running && model.demo && flight.demoLocked < 4 {
                    guard await Self.pause(650) else { return }
                    change { $0.demoLocked += 1 }
                }
            }
            .task(id: flight.comingHome) {
                guard flight.comingHome else { return }
                homeBound = true
                _ = await Self.pause(HOMECOMING_MS)
                homeBound = false
            }
            // A long search (indoors, cold GPS): the music does not wait for the jump.
            .task(id: phase) {
                guard phase == .search, await Self.pause(Self.musicAwayMaxMs) else { return }
                WarpSound.shared.musicBack()
            }
    }

    private func cues<V: View>(_ content: V) -> some View {
        let sound = SoundCue(phase: phase, flightOver: flight.flightOver)
        return content
            .onChange(of: model.running) { _, running in
                stored = flight
                if !running { openedMidDrive = false }
            }
            .task(id: sound) { await playFlight(sound) }
            // Each satellite locked during the search: a soft note, higher each time.
            .onChange(of: LockCue(used: sky.used, phase: phase), initial: true) { _, cue in
                let n = min(cue.used, 4)
                guard cue.phase == .search, n > flight.heardLocks else { return }
                change { $0.heardLocks = n }
                WarpSound.shared.lock(n)
            }
            .onChange(of: model.snapshot?.position, initial: true) { _, position in
                if let position { home = position }
            }
            // On the road the buttons step back (smaller, the shortcuts folded away): nothing to
            // touch by accident, more room for the road; slow or stopped, they come back.
            .onChange(of: model.snapshot?.speedKmh, initial: true) { _, speed in
                change { $0.cruising = Cockpit.cruising(speedKmh: speed, was: $0.cruising) }
            }
            .onChange(of: PortalCue(running: model.running, showMap: showMap), initial: true) { _, cue in
                movePortal(cue)
            }
            .onChange(of: model.snapshot) { _, snapshot in
                guard let event = snapshot?.events.first(where: { $0.kind == .passed || $0.kind == .sectionEnded }) else { return }
                passFlash = event.wouldFlash
                passes += 1
            }
            .onChange(of: scenePhase, initial: true) { _, scene in
                if scene == .active { navApps = NavApp.installed() }
                // Zenith left during the flight: the music comes back at once.
                if scene == .background { WarpSound.shared.musicNow() }
            }
            .onAppear { WarpSound.shared.prepare() }
            .onDisappear { WarpSound.shared.release() }
    }

    /**
     * The flight's sounds, at the same moments as on Android: the engines and the hum at Start, the
     * lock-on, jump and boom in one sound (the pictures follow it), the way back at the stop.
     */
    private func playFlight(_ cue: SoundCue) async {
        let sound = WarpSound.shared
        let before = heardPhase
        heardPhase = cue.phase
        if cue.phase == .search && !cue.flightOver {
            if before == .idle {
                sound.musicAway()
                sound.engage()
                guard await Self.pause(1_400) else { return }
            }
            sound.startHum()
        } else if cue.phase == .arrive && before == .search {
            sound.arrive()
            sound.fadeHum(ms: 400)
        } else {
            if cue.phase == .idle {
                sound.musicNow()
                if flight.comingHome { sound.homecoming() }
            }
            sound.fadeHum()
        }
    }

    /**
     * The arrival: the whole scene opens like a portal from the heart of the tunnel at the flash,
     * rushing in from a zoom, with a shock ring. At the stop it steps back and fades into the stars.
     */
    private func movePortal(_ cue: PortalCue) {
        if cue.showMap {
            sceneUp = true
            withAnimation(Moves.tween(650)) { portal = 1 }
        } else if cue.running {
            sceneUp = true
            var still = Transaction()
            still.disablesAnimations = true
            withTransaction(still) { portal = 0 }
        } else if portal > 0 {
            withAnimation(Moves.tween(CLOSE_MS)) {
                portal = 0
            } completion: {
                if !model.running { sceneUp = false }
            }
        } else {
            sceneUp = false
        }
    }

    fileprivate static func pause(_ ms: Int) async -> Bool {
        do {
            try await Task.sleep(nanoseconds: UInt64(max(ms, 0)) * 1_000_000)
            return true
        } catch {
            return false
        }
    }
}

// MARK: - Flight state

extension DriveScreen {
    /** What the middle of the drive screen shows: home, the flight while the GPS searches, the speed. */
    fileprivate enum HomeView { case idle, flight, drive }

    /** The flags of one drive (or of home between two), all reset at each start and stop. */
    fileprivate struct Flight: Equatable {
        var running: Bool
        var landed = false
        var flightOver = false
        /** The jump itself has begun: the flight's words rush past. */
        var diving = false
        var minFlightDone = false
        var weakFixWaited = false
        var roadsWaited = false
        var demoLocked = 0
        var heardLocks = 0
        var cruising = false
        /** A drive that had reached the map just ended: the way back, the arrival backwards. */
        var comingHome = false

        /** The flags for a drive that just started or stopped ([now]); a flight cut short only lets the tunnel slow down. */
        func next(running now: Bool, openedMidDrive: Bool, speedKnown: Bool) -> Flight {
            let landed = now && openedMidDrive && speedKnown
            return Flight(running: now, landed: landed, flightOver: landed, comingHome: !now && running && self.landed)
        }
    }

    /** What the flight's sounds follow. */
    fileprivate struct SoundCue: Equatable {
        let phase: FlightPhase
        let flightOver: Bool
    }

    /** Satellites locked during the search, one note each. */
    fileprivate struct LockCue: Equatable {
        let used: Int
        let phase: FlightPhase
    }

    /** What opens or closes the scene's portal. */
    fileprivate struct PortalCue: Equatable {
        let running: Bool
        let showMap: Bool
    }

    /** What changes the column's layout: those changes glide instead of jumping. */
    fileprivate struct LayoutCue: Equatable {
        let view: HomeView
        let alert: Bool
        let upcoming: Bool
        let section: Bool
        let media: Bool
        let shortcuts: Bool
        let compact: Bool
        let update: Bool
        let crash: Bool
    }

    /** A navigation app installed: Zenith keeps alerting from behind it, in the small window. */
    fileprivate struct NavApp: Identifiable, Equatable {
        let label: String
        let url: URL
        var id: String { label }

        /** Waze and Google Maps when installed, Apple Plans otherwise. */
        @MainActor
        static func installed() -> [NavApp] {
            var apps: [NavApp] = []
            if let waze = URL(string: "waze://"), UIApplication.shared.canOpenURL(waze) {
                apps.append(NavApp(label: "Waze", url: waze))
            }
            if let google = URL(string: "comgooglemaps://"), UIApplication.shared.canOpenURL(google) {
                apps.append(NavApp(label: "Maps", url: google))
            } else if let plans = URL(string: "maps://") {
                apps.append(NavApp(label: "Plans", url: plans))
            }
            return apps
        }
    }
}

// MARK: - Look and motion

extension DriveScreen {
    /** The drive screen's colours (iOS system colours, as on Android). */
    fileprivate enum Ink {
        static let red = hex(0xFF3B30)
        static let orange = hex(0xFF9F0A)
        static let green = hex(0x30D158)
        static let blue = hex(0x0A84FF)
        static let dim = hex(0x8E8E93)

        static func hex(_ rgb: UInt32) -> Color {
            Color(
                .sRGB,
                red: Double((rgb >> 16) & 0xFF) / 255,
                green: Double((rgb >> 8) & 0xFF) / 255,
                blue: Double(rgb & 0xFF) / 255,
                opacity: 1
            )
        }

        /** Speed digits: white, orange just under the limit, red above it. */
        static func speed(_ speedKmh: Int?, _ limitKmh: Int?) -> Color {
            guard let speedKmh, let limitKmh else { return .white }
            if speedKmh > limitKmh { return red }
            if speedKmh >= limitKmh - 3 { return orange }
            return .white
        }
    }

    /** Numbers as the driver reads them. */
    fileprivate enum Words {
        static func distance(_ m: Double) -> String {
            if m < 1_000 { return "\(Int((m / 10).rounded()) * 10) m" }
            return String(format: "%.1f km", m / 1_000).replacingOccurrences(of: ".", with: ",")
        }

        static func thousands(_ n: Int) -> String {
            let digits = String(n.magnitude)
            var out = ""
            for (i, c) in digits.enumerated() {
                if i > 0 && (digits.count - i) % 3 == 0 { out.append(" ") }
                out.append(c)
            }
            return n < 0 ? "-" + out : out
        }
    }

    /** The map's and the Canvas's shares of the screen, and the fades between them. */
    fileprivate enum Fades {
        /** The map's height, as a share of the screen: lower than where it visibly ends, for its faint tail. */
        static let mapHeight: CGFloat = 0.8
        /** Landscape: the share of the width for the map (and home) on the left; the speed and buttons take the rest. */
        static let landSplit: CGFloat = 0.56
        /** Landscape: the map's width, as a share of the screen; it melts away under the right column. */
        static let landMapWidth: CGFloat = 0.66
        /** Landscape: the Canvas's width from the right edge, as a share of the screen; wider in music mode. */
        static let landCanvasWidth: Double = 0.62
        static let landCanvasWide: Double = 0.85

        /** No mask: the map whole (no music). Same number of stops as the others, to blend between them. */
        static let mapWhole: [FadeStop] = [(0, 1), (0.35, 1), (0.45, 1), (0.575, 1), (0.7, 1), (0.8, 1), (0.925, 1), (1, 1)]
        /**
         * With music, the map's opacity along its height: the crossfade with the Canvas sits where the
         * old fade was (half and half near the middle of the screen), then a long faint tail lower down.
         */
        static let mapOverCanvas: [FadeStop] = [(0, 1), (0.35, 1), (0.45, 0.85), (0.575, 0.55), (0.7, 0.3), (0.8, 0.15), (0.925, 0.05), (1, 0)]
        /** Music mode: the map keeps only the top of the screen, the Canvas takes the rest. */
        static let mapInMusicMode: [FadeStop] = [(0, 1), (0.15, 1), (0.25, 0.6), (0.33, 0.35), (0.42, 0.18), (0.52, 0.08), (0.65, 0.02), (1, 0)]
        /** No mask across the width (portrait). */
        static let across: [FadeStop] = [(0, 1), (1, 1)]
        /** Landscape, the map's opacity across its width: alone, it fades into the black under the speed. */
        static let landMapAlone: [FadeStop] = [(0, 1), (0.62, 1), (0.78, 0.65), (0.9, 0.25), (1, 0)]
        /** Landscape with music: the crossfade with the Canvas starts a little earlier. */
        static let landMapOverCanvas: [FadeStop] = [(0, 1), (0.52, 1), (0.7, 0.55), (0.86, 0.18), (1, 0)]
        /** Landscape music mode: the map keeps the left, the Canvas comes in from the right. */
        static let landMapInMusicMode: [FadeStop] = [(0, 1), (0.32, 1), (0.52, 0.45), (0.72, 0.12), (1, 0)]

        /** Alone, the map fades into the black where it always did (62 % of the screen), black below. */
        static let mapAlone: [Gradient.Stop] = [
            shade(0.45, 0), shade(0, 0.093), shade(0, 0.326), shade(0.35, 0.48), shade(0.75, 0.62), shade(1, 0.775), shade(1, 1),
        ]
        /** With music: darker only under the chips at the top, a light veil for the speed; the mask does the rest. */
        static let mapOverMusic: [Gradient.Stop] = [shade(0.45, 0), shade(0, 0.093), shade(0.1, 0.35), shade(0.3, 1)]
        /** Landscape: a darker band only under the chips at the top. */
        static let landMapShade: [Gradient.Stop] = [shade(0.45, 0), shade(0, 0.14), shade(0.2, 1)]

        static func shade(_ alpha: Double, _ at: CGFloat) -> Gradient.Stop {
            Gradient.Stop(color: Color.black.opacity(alpha), location: at)
        }

        /** A fade's stops as a mask gradient. */
        static func mask(_ stops: [FadeStop]) -> [Gradient.Stop] {
            stops.map { Gradient.Stop(color: Color.black.opacity(min(max($0.opacity, 0), 1)), location: $0.at) }
        }
    }

    /** Compose's springs and easings, and the screen's transitions. */
    fileprivate enum Moves {
        /** Compose's spring(dampingRatio, stiffness), unit mass. */
        static func spring(_ damping: Double, _ stiffness: Double) -> Animation {
            .spring(response: 2 * .pi / stiffness.squareRoot(), dampingFraction: damping)
        }

        /** FastOutSlowIn, Compose's default tween. */
        static func tween(_ ms: Int, delay: Int = 0) -> Animation {
            .timingCurve(0.4, 0, 0.2, 1, duration: Double(ms) / 1_000).delay(Double(delay) / 1_000)
        }

        static func linearOutSlowIn(_ ms: Int) -> Animation {
            .timingCurve(0, 0, 0.2, 1, duration: Double(ms) / 1_000)
        }

        static func easeOutBack(_ ms: Int, delay: Int) -> Animation {
            .timingCurve(0.34, 1.56, 0.64, 1, duration: Double(ms) / 1_000).delay(Double(delay) / 1_000)
        }

        static let fastOutSlowInCurve = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0.4, y: 0), endControlPoint: UnitPoint(x: 0.2, y: 1))
        static let linearOutSlowInCurve = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0, y: 0), endControlPoint: UnitPoint(x: 0.2, y: 1))

        static func fade(_ animation: Animation) -> AnyTransition { AnyTransition.opacity.animation(animation) }

        static func zoom(_ from: CGFloat, _ animation: Animation) -> AnyTransition {
            AnyTransition.scale(scale: from).animation(animation)
        }

        /** Slides in from (out to) [share] of its own height below. */
        static func rise(_ share: CGFloat, _ animation: Animation) -> AnyTransition {
            AnyTransition.modifier(active: Rise(share: share), identity: Rise(share: 0)).animation(animation)
        }

        /** Unveiled from the bottom up (Compose's expandVertically). */
        static func reveal(_ animation: Animation) -> AnyTransition {
            AnyTransition.modifier(active: Reveal(shown: 0), identity: Reveal(shown: 1)).animation(animation)
        }

        static let rushPast = fade(tween(450)).combined(with: zoom(2.4, tween(600)))
        static let landing = fade(tween(250, delay: 50)).combined(with: zoom(1.5, spring(0.65, 400)))
        static let stepBack = fade(tween(250)).combined(with: zoom(0.85, tween(400)))
        static let homecomingIn = fade(tween(500, delay: 800)).combined(with: zoom(0.6, easeOutBack(800, delay: 700)))
        static let cutShortIn = fade(tween(700, delay: 350)).combined(with: zoom(0.55, spring(0.75, 50)))
        /** The drive's cards step back and fade with the scene. */
        static let driveOut = fade(tween(220)).combined(with: zoom(0.9, tween(320)))
        /** Chips pop in with a spring and shrink away. */
        static let chip = AnyTransition.asymmetric(
            insertion: fade(tween(300)).combined(with: zoom(0.7, spring(0.6, 1_500))),
            removal: fade(tween(200)).combined(with: zoom(0.85, tween(200)))
        )
        static let alert = AnyTransition.asymmetric(
            insertion: rise(0.5, spring(0.75, 400)).combined(with: fade(tween(250))).combined(with: zoom(0.92, spring(0.75, 400))),
            removal: rise(1.0 / 3, tween(300)).combined(with: fade(tween(250))).combined(with: zoom(0.95, tween(300)))
        )
        static let upcoming = AnyTransition.asymmetric(
            insertion: fade(tween(400)).combined(with: reveal(spring(0.85, 400))),
            removal: fade(tween(250)).combined(with: reveal(tween(300)))
        )
        static let banner = fade(tween(300)).combined(with: reveal(spring(1, 400)))

        /** Home's buttons rise back into place (after a drive, once the Earth has drifted away) and sink away at Start. */
        static func bottom(late: Bool) -> AnyTransition {
            let insertion = late
                ? fade(tween(500, delay: 1_100)).combined(with: AnyTransition.move(edge: .bottom).animation(easeOutBack(700, delay: 1_000)))
                : fade(tween(500, delay: 400)).combined(with: AnyTransition.move(edge: .bottom).animation(spring(0.8, 200)))
            return .asymmetric(insertion: insertion, removal: fade(tween(200)).combined(with: rise(0.5, tween(300))))
        }
    }

    /** Offsets the content by a share of its own height. */
    fileprivate struct Rise: ViewModifier, Animatable {
        var share: CGFloat
        var animatableData: CGFloat {
            get { share }
            set { share = newValue }
        }

        func body(content: Content) -> some View {
            let share = self.share
            return content.visualEffect { effect, proxy in
                effect.offset(y: proxy.size.height * share)
            }
        }
    }

    /** Shows the bottom [shown] share of the content. */
    fileprivate struct Reveal: ViewModifier, Animatable {
        var shown: CGFloat
        var animatableData: CGFloat {
            get { shown }
            set { shown = newValue }
        }

        func body(content: Content) -> some View {
            content.mask(alignment: .bottom) {
                Rectangle().scaleEffect(x: 1, y: max(shown, 0.001), anchor: .bottom)
            }
        }
    }
}

// MARK: - Scene

extension DriveScreen {
    /**
     * The music's ambience from the bottom (the Canvas, else the blurred cover) under the tilted map,
     * which melts into it: alone, the map fades into the black where it always did; with music, a
     * few of its lines stay faint over the Canvas lower down. Animated through [focus] (music mode),
     * [withMusic] and [portal] (the opening at the arrival, the fade at the stop).
     */
    fileprivate struct SceneLayer: View, Animatable {
        var focus: Double
        var withMusic: Double
        var portal: Double
        let running: Bool
        let landscape: Bool
        let size: CGSize
        let snapshot: Snapshot?
        let radars: [RadarInfo]
        let zones: [ZoneInfo]
        let media: MediaInfo?
        let canvas: Bool

        var animatableData: AnimatablePair<AnimatablePair<Double, Double>, Double> {
            get { AnimatablePair(AnimatablePair(focus, withMusic), portal) }
            set {
                focus = newValue.first.first
                withMusic = newValue.first.second
                portal = newValue.second
            }
        }

        var body: some View {
            let k = portal
            // Opening: a portal from the heart. Leaving: the whole scene steps back and fades.
            let scale = running ? 1.35 - 0.35 * k : 0.88 + 0.12 * k
            let alpha = running ? (k <= 0 ? 0 : 1) : k
            ZStack(alignment: .topLeading) {
                if let media {
                    // Landscape: the right part only (the 9:16 video less cropped), a little wider in
                    // music mode; its fade is the map's in reverse, so the two always add up.
                    let share = CGFloat(Fades.landCanvasWidth + (Fades.landCanvasWide - Fades.landCanvasWidth) * focus)
                    MusicBackdrop(
                        media: media,
                        canvas: canvas,
                        // An alert on screen: the music steps back.
                        calm: !(snapshot?.alerts.isEmpty ?? true),
                        focus: focus,
                        fromRight: landscape
                    )
                    .frame(width: landscape ? size.width * share : size.width, height: size.height)
                    .frame(width: size.width, height: size.height, alignment: landscape ? .trailing : .center)
                }
                // The car where it always was on screen, the map now taller; in landscape a little
                // low, to see far ahead in the short height.
                DriveMap(snapshot: snapshot, radars: radars, zones: zones, carAt: landscape ? 0.62 : 0.28 / Double(Fades.mapHeight))
                    .overlay {
                        LinearGradient(stops: shade, startPoint: .top, endPoint: .bottom)
                            .allowsHitTesting(false)
                    }
                    .mask { mapMask }
                    .frame(
                        width: landscape ? size.width * Fades.landMapWidth : size.width,
                        height: landscape ? size.height : size.height * Fades.mapHeight
                    )
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .scaleEffect(CGFloat(scale))
            .opacity(alpha)
            .mask { portalMask(k) }
        }

        private var shade: [Gradient.Stop] {
            if landscape { return Fades.landMapShade }
            return media != nil ? Fades.mapOverMusic : Fades.mapAlone
        }

        /** The map's own fade: along its height upright, across its width in landscape. */
        private var mapMask: some View {
            let down = landscape ? Fades.mapWhole
                : blendStops(Fades.mapWhole, blendStops(Fades.mapOverCanvas, Fades.mapInMusicMode, focus), withMusic)
            let across = landscape
                ? blendStops(Fades.landMapAlone, blendStops(Fades.landMapOverCanvas, Fades.landMapInMusicMode, focus), withMusic)
                : Fades.across
            return LinearGradient(stops: Fades.mask(down), startPoint: .top, endPoint: .bottom)
                .mask {
                    LinearGradient(stops: Fades.mask(across), startPoint: .leading, endPoint: .trailing)
                }
        }

        /** Shows the scene through a circle around the tunnel's heart, with a soft rim; open: no mask. */
        @ViewBuilder
        private func portalMask(_ progress: Double) -> some View {
            if running && progress < 1 {
                let r = CGFloat(min(max(progress * 1.15, 0.001), 1.15))
                RadialGradient(
                    stops: [
                        Gradient.Stop(color: .black, location: 0),
                        Gradient.Stop(color: .black, location: min(r * 0.82, 1)),
                        Gradient.Stop(color: .clear, location: min(r, 1)),
                    ],
                    center: UnitPoint(x: 0.5, y: HEART_Y),
                    startRadius: 0,
                    endRadius: hypot(size.width, size.height)
                )
            } else {
                Rectangle()
            }
        }
    }

    /** The shock ring of the arrival, following the portal frame by frame. */
    fileprivate struct PortalRing: View, Animatable {
        var progress: Double
        var animatableData: Double {
            get { progress }
            set { progress = newValue }
        }

        var body: some View { ShockRing(progress: progress) }
    }
}

// MARK: - Home

extension DriveScreen {
    /** Home: the logo breathing with a red glow, the name, the radars in memory counting up. */
    fileprivate struct IdleHome: View {
        let data: DataStatus
        @State private var counted = 0.0

        var body: some View {
            VStack(spacing: 0) {
                PhaseAnimator([false, true]) { up in
                    ZStack {
                        Circle()
                            .fill(RadialGradient(colors: [Ink.red, Ink.red.opacity(0)], center: .center, startRadius: 0, endRadius: 75))
                            .frame(width: 150, height: 150)
                            .opacity(up ? 0.28 : 0.10)
                            .scaleEffect(up ? 1.04 : 0.96)
                        // The icon drawing leaves a margin for the masks: drawn larger here so the
                        // dial keeps its size on screen.
                        ZenithLogo()
                            .frame(width: 174, height: 174)
                            .scaleEffect(up ? 1.04 : 0.96)
                    }
                } animation: { _ in
                    Moves.tween(2_200)
                }
                .contentShape(Rectangle())
                // Hidden: a long press on the logo starts the simulated drive.
                .onLongPressGesture {
                    UiSound.play(.success, long: true)
                    DriveEngine.shared.start(origin: .demo)
                }
                Text("Zenith")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundStyle(Color.white)
                Color.clear.frame(height: 8)
                // The radars in memory count up as the screen opens, like a dashboard waking up.
                RadarCount(value: counted, total: data.radarCount, zones: data.zoneCount)
                    .animation(Moves.tween(1_400), value: counted)
            }
            .onAppear { counted = Double(data.radarCount) }
            .onChange(of: data.radarCount) { _, n in counted = Double(n) }
        }
    }

    /** "12 345 radars en mémoire", the number rolling as it counts. */
    fileprivate struct RadarCount: View, Animatable {
        var value: Double
        let total: Int
        let zones: Int
        var animatableData: Double {
            get { value }
            set { value = newValue }
        }

        var body: some View {
            let radars = Words.thousands(Int(value.rounded()))
            let text = total == 0 ? "Radars et limitations chargés au démarrage"
                : zones == 0 ? "\(radars) radars en mémoire"
                : "\(radars) radars · \(Words.thousands(zones)) itinéraires de contrôle"
            Text(text)
                .font(.system(size: 16))
                .foregroundStyle(Ink.dim)
                .multilineTextAlignment(.center)
        }
    }

    /** Zenith's logo: speedometer arc with the needle in the red zone (the app icon's drawing). */
    fileprivate struct ZenithLogo: View {
        var body: some View {
            Canvas { context, size in
                // The icon's 108-unit canvas, drawing scaled to 0.86 around (54, 48) and moved down 3.
                let unit = size.width / 108
                let k = 0.86 * unit
                func at(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
                    CGPoint(x: ((x - 54) * 0.86 + 54) * unit, y: ((y - 48) * 0.86 + 51) * unit)
                }
                let hub = at(54, 54)
                var dial = Path()
                dial.addRelativeArc(center: hub, radius: 24 * k, startAngle: .degrees(150), delta: .degrees(240))
                context.stroke(dial, with: .color(.white), style: StrokeStyle(lineWidth: 5.5 * k, lineCap: .round))
                var zone = Path()
                zone.addRelativeArc(center: hub, radius: 24 * k, startAngle: .degrees(-60), delta: .degrees(90))
                context.stroke(zone, with: .color(Ink.red), style: StrokeStyle(lineWidth: 5.5 * k, lineCap: .round))
                var needle = Path()
                needle.move(to: hub)
                needle.addLine(to: at(62.5, 40.9))
                context.stroke(needle, with: .color(Ink.red), style: StrokeStyle(lineWidth: 5 * k, lineCap: .round))
                context.fill(Path(ellipseIn: CGRect(x: hub.x - 4.5 * k, y: hub.y - 4.5 * k, width: 9 * k, height: 9 * k)), with: .color(.white))
            }
        }
    }

    /** GPS and data chips; they appear while something is pending and fade once all is fine. */
    fileprivate struct StatusRow: View {
        let running: Bool
        let gps: GpsInfo
        let data: DataStatus
        let demo: Bool
        let snapshot: Snapshot?
        @State private var showGps = true

        var body: some View {
            let estimated = snapshot?.estimated == true
            let gpsGood = (gps.accuracyM ?? 99) <= 15
            // Control itinerary: a movable radar may be anywhere along it.
            let zone = snapshot?.zone
            let demoChip = running && demo
            let tunnelChip = running && !demo && estimated
            let zoneChip = running && zone != nil && !estimated
            let gpsChip = running && !demo && !estimated && zone == nil && showGps
            let dataChip = data.loading || data.error != nil
            HStack(spacing: 6) {
                if demoChip {
                    Chip(text: "Démo · trajet simulé", dot: Ink.blue).transition(Moves.chip)
                }
                if tunnelChip {
                    Chip(text: "Tunnel · position estimée", dot: Ink.orange).transition(Moves.chip)
                }
                if zoneChip {
                    let km = max(1, Int(((zone?.remainingM ?? 0) / 1_000).rounded()))
                    Chip(text: "Itinéraire de contrôle · \(km) km", dot: Ink.orange).transition(Moves.chip)
                }
                if gpsChip {
                    Chip(text: gpsText(good: gpsGood), dot: gpsGood ? Ink.green : Ink.orange).transition(Moves.chip)
                }
                Spacer(minLength: 0)
                if dataChip {
                    Chip(text: data.error ?? "Chargement des routes…", dot: data.error != nil ? Ink.orange : Ink.dim)
                        .transition(Moves.chip)
                }
            }
            .frame(height: 32)
            .animation(Moves.spring(0.6, 1_500), value: [demoChip, tunnelChip, zoneChip, gpsChip, dataChip])
            .task(id: [gpsGood, running]) {
                if gpsGood {
                    if await DriveScreen.pause(3_000) { showGps = false }
                } else {
                    showGps = true
                }
            }
        }

        private func gpsText(good: Bool) -> String {
            guard let accuracy = gps.accuracyM else { return "Satellites \(gps.used)/\(gps.visible) · recherche" }
            let metres = Int(accuracy.rounded())
            return good ? "GPS ±\(metres) m · \(gps.used) sat." : "GPS faible ±\(metres) m · \(gps.used)/\(gps.visible)"
        }
    }

    /** A glass pill with a coloured dot: one state at a glance. */
    fileprivate struct Chip: View {
        let text: String
        let dot: Color

        var body: some View {
            HStack(spacing: 8) {
                Circle().fill(dot).frame(width: 8, height: 8)
                Text(text)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .glass(Capsule())
        }
    }

    /** A new version on the SideStore source: one tap hands it to SideStore. */
    fileprivate struct UpdateBanner: View {
        let version: String?

        var body: some View {
            if let version {
                let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
                Text("Mise à jour \(version) · Installer")
                    .font(.system(size: 14))
                    .foregroundStyle(Color.white)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(12)
                    .glass(shape)
                    .tap(shape: shape, lift: 1.03) { Updater.shared.install() }
                    .padding(.top, 8)
                    .transition(Moves.banner)
            }
        }
    }

    /** After a crash, at home: one tap shares what happened (the driver decides where it goes). */
    fileprivate struct CrashBanner: View {
        let report: String?

        var body: some View {
            if let report {
                let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
                Text("Zenith s'est fermé la dernière fois · toucher pour envoyer le rapport")
                    .font(.system(size: 14))
                    .foregroundStyle(Color.white)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(12)
                    .glass(shape)
                    .tap(shape: shape, sound: .open, lift: 1.03) {
                        Self.share(report)
                        CrashGuard.shared.clear()
                    }
                    .padding(.top, 8)
                    .transition(Moves.banner)
            }
        }

        /**
         * The share sheet, presented by the window's top controller rather than by the banner
         * (which goes away at once, as on Android).
         */
        @MainActor
        private static func share(_ text: String) {
            let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
            guard var top = (windows.first(where: \.isKeyWindow) ?? windows.first)?.rootViewController else { return }
            while let presented = top.presentedViewController { top = presented }
            let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            if let popover = sheet.popoverPresentationController {
                popover.sourceView = top.view
                popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            top.present(sheet, animated: true)
        }
    }

    /** Shortcuts to the navigation apps installed: Zenith keeps alerting from behind them, in the small window. */
    fileprivate struct NavAppsRow: View {
        let apps: [NavApp]
        /** In a row of fixed height (landscape, beside Stop): each button takes it all. */
        let fillHeight: Bool

        var body: some View {
            HStack(spacing: 10) {
                ForEach(apps) { app in
                    let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
                    Text("Ouvrir \(app.label)")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity, maxHeight: fillHeight ? .infinity : nil)
                        .glass(shape)
                        .tap(shape: shape, sound: .open) {
                            // The small window opens over the other app, whatever the setting.
                            PipController.shared.start()
                            UIApplication.shared.open(app.url)
                        }
                }
            }
        }
    }

    /** Start, then Stop; [compact] on the road: lower and quieter, still there. */
    fileprivate struct StartStop: View {
        let running: Bool
        let compact: Bool
        /** In a row of fixed height (landscape): the button takes it all. */
        let fillHeight: Bool

        var body: some View {
            let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
            ZStack {
                // The word rolls over, like a switch flipping.
                if running {
                    Text("Arrêter").foregroundStyle(Color.white).transition(Self.roll)
                } else {
                    Text("Démarrer").foregroundStyle(Color.black).transition(Self.roll)
                }
            }
            .font(.system(size: 18, weight: .semibold))
            .clipped()
            .opacity(compact ? 0.7 : 1)
            .animation(Moves.tween(500), value: compact)
            .padding(.vertical, compact ? 9 : 18)
            .animation(Moves.spring(0.85, 200), value: compact)
            .frame(maxWidth: .infinity, maxHeight: fillHeight ? .infinity : nil)
            .glass(shape, color: running ? Glass : Color.white)
            .animation(Moves.spring(1, 1_500), value: running)
            // Only the touch: the flight and the way back are the sound.
            .tap(shape: shape, sound: .silent, lift: 1.04, glow: running ? PressLight : PressShade) {
                if running { DriveEngine.shared.stop() } else { DriveEngine.shared.start() }
            }
            // At home, the button sends out a soft ring of light now and then: the one thing to touch.
            .background {
                if !running { StartRing() }
            }
        }

        private static let roll = AnyTransition.asymmetric(
            insertion: AnyTransition.move(edge: .bottom).animation(Moves.spring(0.75, 400)).combined(with: Moves.fade(Moves.tween(250))),
            removal: AnyTransition.move(edge: .top).animation(Moves.tween(250)).combined(with: Moves.fade(Moves.tween(200)))
        )
    }

    /** The ring of light leaving the Start button every 2.6 s. */
    fileprivate struct StartRing: View {
        var body: some View {
            KeyframeAnimator(initialValue: 0.0, repeating: true) { wave in
                let k = min(wave / 0.7, 1)
                let grow = CGFloat(10 * k)
                RoundedRectangle(cornerRadius: 18 + grow, style: .continuous)
                    .stroke(Color.white.opacity(wave > 0.7 ? 0 : 0.35 * (1 - k)), lineWidth: 1.5)
                    .padding(-grow)
            } keyframes: { _ in
                KeyframeTrack(\.self) {
                    MoveKeyframe(0.0)
                    LinearKeyframe(1.0, duration: 2.6, timingCurve: Moves.fastOutSlowInCurve)
                }
            }
            .allowsHitTesting(false)
        }
    }
}

// MARK: - Drive

extension DriveScreen {
    /** Road name, big speed, limit sign and the next limit on the road. */
    fileprivate struct Dashboard: View {
        let snapshot: Snapshot?
        /** Straight from each GPS fix, ahead of the rest of the drive (the demo has only its snapshot). */
        let speed: Int?
        var compact = false

        var body: some View {
            let limit = snapshot?.limitKmh
            VStack(spacing: 0) {
                Text(snapshot?.roadName ?? " ")
                    .font(.system(size: compact ? 15 : 18))
                    .foregroundStyle(Ink.dim)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .shadow(color: .black, radius: 10)
                HStack(alignment: .center, spacing: 0) {
                    VStack(spacing: 0) {
                        // Short roll between two fixes: the number glides but keeps up with the car.
                        SpeedDigits(value: Double(speed ?? 0), known: speed != nil, size: compact ? 96 : 150)
                            .animation(Moves.linearOutSlowIn(250), value: speed)
                            .foregroundStyle(Ink.speed(speed, limit))
                            .animation(Moves.spring(1, 1_500), value: Ink.speed(speed, limit))
                            // Waiting for the GPS: the dashes breathe instead of sitting still.
                            .phaseAnimator([0.35, 1.0]) { content, alpha in
                                content.opacity(speed == nil ? alpha : 1)
                            } animation: { _ in
                                Moves.tween(900)
                            }
                            // Dark halo so the digits stay readable over the map.
                            .shadow(color: .black, radius: 10)
                        Text("km/h")
                            .font(.system(size: compact ? 16 : 20))
                            .foregroundStyle(Ink.dim)
                    }
                    if let limit {
                        HStack(spacing: 0) {
                            Color.clear.frame(width: compact ? 14 : 20, height: 1)
                            VStack(spacing: 0) {
                                LimitSign(limit: limit, uncertain: snapshot?.limitSource?.isGuess == true, size: compact ? 80 : 104)
                                Text(snapshot?.limitAhead.map { "\($0.limitKmh) dans \(Words.distance($0.distanceM))" } ?? " ")
                                    .font(.system(size: 15))
                                    .foregroundStyle(Ink.dim)
                                    .shadow(color: .black, radius: 10)
                                    .padding(.top, 8)
                            }
                        }
                        .transition(Moves.fade(Moves.spring(1, 400)))
                    }
                }
            }
        }
    }

    /** The speed in big fixed-width digits, set tight: the number does not jump as it changes. */
    fileprivate struct SpeedDigits: View, Animatable {
        var value: Double
        let known: Bool
        let size: CGFloat
        var animatableData: Double {
            get { value }
            set { value = newValue }
        }

        var body: some View {
            Text(known ? String(Int(value.rounded())) : "--")
                .font(.system(size: size, weight: .bold).monospacedDigit())
                .tracking(-4)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
    }

    /** Round limit sign; grey ring when the limit is only inferred (road type, built-up area or not). */
    fileprivate struct LimitSign: View {
        let limit: Int
        let uncertain: Bool
        let size: CGFloat
        @State private var pops = 0

        var body: some View {
            let edge = uncertain ? Ink.dim : Ink.red
            // A new limit pops in and sends out a ring, so the eye catches the change.
            KeyframeAnimator(initialValue: SignMotion(), trigger: pops) { motion in
                ZStack {
                    if motion.ring < 1 {
                        let reach = size * CGFloat(1 + 0.7 * motion.ring)
                        Circle()
                            .stroke(edge.opacity(0.6 * (1 - motion.ring)), lineWidth: CGFloat(3 * (1 - motion.ring) + 0.25))
                            .frame(width: reach, height: reach)
                    }
                    Circle().fill(Color.white).frame(width: size, height: size)
                    Circle().strokeBorder(edge, lineWidth: size * 0.106).frame(width: size, height: size)
                    Text("\(limit)")
                        .font(.system(size: size * (limit >= 100 ? 0.327 : 0.385), weight: .bold))
                        .foregroundStyle(Color.black)
                        .lineLimit(1)
                        .fixedSize()
                }
                .frame(width: size, height: size)
                .scaleEffect(CGFloat(motion.pop))
            } keyframes: { _ in
                KeyframeTrack(\.pop) {
                    MoveKeyframe(1.15)
                    SpringKeyframe(1.0, spring: Spring(response: 2 * .pi / 200.0.squareRoot(), dampingRatio: 0.45))
                }
                KeyframeTrack(\.ring) {
                    MoveKeyframe(0.0)
                    LinearKeyframe(1.0, duration: 0.9, timingCurve: Moves.linearOutSlowInCurve)
                }
            }
            .onAppear { pops += 1 }
            .onChange(of: limit) { pops += 1 }
        }
    }

    /** The limit sign's pop and ring, from 0 (new limit) to rest. */
    fileprivate struct SignMotion {
        var pop = 1.0
        var ring = 1.0
    }

    /** Section under way, then the radar alert or, without one, the next radar on the road. */
    fileprivate struct AlertArea: View {
        let snapshot: Snapshot?

        var body: some View {
            let alert = snapshot?.alerts.first
            VStack(spacing: 0) {
                if let section = snapshot?.section {
                    SectionCard(section: section)
                }
                if let alert {
                    AlertCard(alert: alert).transition(Moves.alert)
                }
                if alert == nil, let upcoming = snapshot?.upcoming {
                    UpcomingStrip(upcoming: upcoming).transition(Moves.upcoming)
                }
            }
        }
    }

    /** The radar being announced: type, distance, countdown bar; red and pulsing when too fast. */
    fileprivate struct AlertCard: View {
        let alert: AlertInfo
        /** Distance at first sight of this radar, to draw the countdown bar. */
        @State private var first = FirstSight(id: "", distanceM: 0)

        var body: some View {
            let start = first.id == alert.radar.id ? max(first.distanceM, alert.distanceM) : alert.distanceM
            let progress = start > 0 ? 1 - min(max(alert.distanceM / start, 0), 1) : 0
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    RadarBadge(kind: alert.radar.kind, limitKmh: alert.limitKmh, size: 54)
                    Color.clear.frame(width: 16, height: 1)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(alert.radar.label)
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(Color.white)
                        if !details.isEmpty {
                            Text(details)
                                .font(.system(size: 14))
                                .foregroundStyle(Color.white.opacity(0.75))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Text(Words.distance(alert.distanceM))
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                        .fixedSize()
                }
                .padding(20)
                ProgressLine(progress: progress)
            }
            .frame(maxWidth: .infinity)
            .glass(RoundedRectangle(cornerRadius: 22, style: .continuous), color: alert.overLimit ? Ink.red : Glass)
            .animation(Moves.tween(300), value: alert.overLimit)
            .sheen()
            .phaseAnimator([1.0, 0.55]) { content, alpha in
                content.opacity(alert.overLimit ? alpha : 1)
            } animation: { _ in
                Moves.tween(450)
            }
            .onChange(of: alert.radar.id, initial: true) { _, id in
                first = FirstSight(id: id, distanceM: alert.distanceM)
            }
            .onChange(of: alert.distanceM) { _, d in
                if first.id == alert.radar.id && d > first.distanceM { first.distanceM = d }
            }
        }

        /** Unverified direction, age of a mobile report. */
        private var details: String {
            var parts: [String] = []
            if alert.radar.directionDeg == nil { parts.append("sens non vérifié") }
            if let age = alert.ageMs { parts.append("signalé il y a \(max(1, age / 60_000)) min") }
            return parts.joined(separator: " · ")
        }
    }

    /** A radar and its distance when first seen. */
    fileprivate struct FirstSight {
        let id: String
        var distanceM: Double
    }

    /** The next radar on the road, before it is announced. */
    fileprivate struct UpcomingStrip: View {
        let upcoming: UpcomingInfo

        var body: some View {
            HStack(spacing: 0) {
                RadarBadge(kind: upcoming.radar.kind, limitKmh: upcoming.limitKmh)
                Color.clear.frame(width: 14, height: 1)
                VStack(alignment: .leading, spacing: 0) {
                    Text(upcoming.radar.label)
                        .font(.system(size: 17))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                    if let road = upcoming.roadName {
                        Text("sur \(road)")
                            .font(.system(size: 14))
                            .foregroundStyle(Ink.dim)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(Words.distance(upcoming.distanceM))
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                    .fixedSize()
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .glass(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    /** One number to read (the average, next to the limit sign), one sentence saying what to do. */
    fileprivate struct SectionCard: View {
        let section: SectionInfo

        var body: some View {
            let slowDown = section.lost || section.targetKmh < section.limitKmh
            let progress = section.lengthM > 0 ? min(max(1 - section.remainingM / section.lengthM, 0), 1) : 0
            let advice = section.lost ? "Moyenne trop haute : elle finira au-dessus de \(section.limitKmh)"
                : slowDown ? "Pour finir sous \(section.limitKmh) : pas plus de \(section.targetKmh) jusqu'au bout"
                : "Moyenne bonne : roule jusqu'à \(section.limitKmh), pas plus"
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    RadarBadge(kind: .sectionStart, limitKmh: section.limitKmh, size: 54)
                    Color.clear.frame(width: 16, height: 1)
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Ta moyenne sur le tronçon")
                            .font(.system(size: 14))
                            .foregroundStyle(Color.white.opacity(0.75))
                        Text("\(Int(section.averageKmh.rounded())) km/h")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundStyle(Color.white)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    VStack(alignment: .trailing, spacing: 0) {
                        Text("encore")
                            .font(.system(size: 14))
                            .foregroundStyle(Color.white.opacity(0.75))
                        Text(Words.distance(section.remainingM))
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(Color.white)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                Text(advice)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .padding(.bottom, 14)
                ProgressLine(progress: progress)
            }
            .frame(maxWidth: .infinity)
            .glass(RoundedRectangle(cornerRadius: 22, style: .continuous), color: slowDown ? Ink.red : Glass)
            .animation(Moves.tween(400), value: slowDown)
            .sheen()
            .padding(.bottom, 12)
        }
    }

    /** Thin bar along the bottom of a card, filling as the radar (or the section's end) nears. */
    fileprivate struct ProgressLine: View {
        let progress: Double

        var body: some View {
            ZStack(alignment: .leading) {
                Rectangle().fill(Color.white.opacity(0.15))
                Rectangle()
                    .fill(Color.white)
                    .scaleEffect(x: max(progress, 0), y: 1, anchor: .leading)
            }
            .frame(height: 6)
            .animation(Moves.spring(1, 1_500), value: progress)
        }
    }
}
