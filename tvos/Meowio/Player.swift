import SwiftUI
import AVKit
import Combine

/// Sleep timer. It lives outside any one playback so it keeps counting across episodes and source switches.
@MainActor
final class SleepTimer: ObservableObject {
    static let shared = SleepTimer()

    enum Mode: Equatable {
        case off
        case until(minutes: Int, deadline: Date)
        case endOfEpisode
    }

    static let minuteOptions = [15, 30, 45, 60, 90]
    @Published private(set) var mode: Mode = .off

    func start(minutes: Int) {
        mode = minutes <= 0 ? .off : .until(minutes: minutes, deadline: Date().addingTimeInterval(Double(minutes) * 60))
    }
    func endOfEpisode() { mode = .endOfEpisode }
    func cancel() { mode = .off }

    var label: String {
        switch mode {
        case .off: return "Sleep timer"
        case .until(let minutes, _): return "Sleep in \(minutes) min"
        case .endOfEpisode: return "Sleep after this episode"
        }
    }

    /// True once a timed sleep has run out. Clears the timer.
    func consumeExpired() -> Bool {
        if case .until(_, let deadline) = mode, deadline <= Date() { mode = .off; return true }
        return false
    }

    /// Called when an episode ends. True when playback should stop here instead of starting the next one.
    func consumeEndOfEpisode() -> Bool {
        switch mode {
        case .endOfEpisode: mode = .off; return true
        case .until(_, let deadline) where deadline <= Date(): mode = .off; return true
        default: return false
        }
    }
}

struct PlayRequest: Identifiable {
    let id = UUID()
    var meta: MetaItem
    var videoId: String?
    var title: String
    var candidates: [StreamItem]
    var headers: [String: String] = [:]
    var isLive = false
    /// Where to start, in seconds, for a title the user left part way.
    var startAt: Double = 0
    /// Resolves the request for the episode after this one, when there is one.
    var nextEpisode: (@MainActor () async -> PlayRequest?)? = nil
}

/// Plays the best candidate and silently moves to the next one when it fails or does not start.
@MainActor
final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    let mpv = MPVEngine()
    @Published var usingMPV = false
    private var triedMPV = Set<Int>()
    @Published var failedAll = false
    @Published var request: PlayRequest
    @Published var activeIndex = 0
    @Published var sleptMessage = false
    /// Subtitles from the user's subtitle add-ons for what is playing.
    @Published private(set) var addonSubtitles: [SubtitleItem] = []
    /// Shown near the end of an episode when another one follows ("Next Episode" button).
    @Published private(set) var upNextVisible = false
    /// Where Skip Intro jumps to, while an intro or recap chapter is playing.
    @Published private(set) var skipTarget: Double?
    @Published private(set) var speed: Double = 1
    /// Short status while a torrent result is turned into a link.
    @Published private(set) var status: String?

    private let settings: SettingsStore
    private var index = 0
    private var switches = 0
    private var cancellables = Set<AnyCancellable>()
    private var timeout: Task<Void, Never>?
    private var timeObserver: Any?
    private var ticker: Timer?
    private var resolved: [Int: URL] = [:]
    private var pendingSubtitle: SubtitleItem?
    private var upNextDismissed = false
    private var advancing = false
    private var wasPaused = true
    private var started = false
    private(set) var lastSeconds: Double = 0
    private(set) var lastDuration: Double = 0
    var onProgress: ((PlayRequest, Double, Double, Bool) -> Void)?
    /// Position to start the source that is loading at: the resume point, or where the failed source stopped.
    private var pendingStart: Double = 0
    var onPlayed: ((PlayRequest, StreamItem) -> Void)?
    /// Playback started, paused or stopped, with percent watched. Trakt scrobbling listens.
    var onScrobble: ((PlayRequest, String, Double) -> Void)?
    var onClose: (() -> Void)?

    init(request: PlayRequest, settings: SettingsStore) {
        self.request = request
        self.settings = settings
        player.allowsExternalPlayback = true
    }

    var percent: Double { lastDuration > 0 ? min(100, max(0, lastSeconds / lastDuration * 100)) : 0 }

    func start() {
        let interval = CMTime(seconds: 1, preferredTimescale: 1)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, !self.usingMPV else { return }
                let seconds = time.seconds
                if seconds.isFinite { self.lastSeconds = seconds }
                if let d = self.player.currentItem?.duration.seconds, d.isFinite { self.lastDuration = d }
            }
        }
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        mpv.prepare()
        loadSubtitles()
        load(at: 0, keepPosition: false)
    }

    func stop() {
        finishRecording(completed: false)
        if started { onScrobble?(request, "stop", percent) }
        started = false
        timeout?.cancel()
        ticker?.invalidate()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        mpv.stop()
        cancellables.removeAll()
    }

    /// The user picked a specific source from the quality picker.
    func choose(index i: Int) {
        guard request.candidates.indices.contains(i) else { return }
        load(at: i)
    }

    func setSpeed(_ value: Double) {
        speed = value
        if usingMPV { mpv.setSpeed(value) } else {
            player.defaultRate = Float(value)
            if player.rate != 0 { player.rate = Float(value) }
        }
    }

    /// Plays an add-on subtitle. AVPlayer cannot load outside subtitle files, so the source reopens in mpv
    /// at the same spot.
    func useSubtitle(_ sub: SubtitleItem) {
        if usingMPV {
            mpv.addSubtitle(url: sub.url, title: sub.label, lang: sub.lang, select: true)
        } else {
            pendingSubtitle = sub
            triedMPV.insert(index)
            load(at: index)
        }
    }

    func skipIntro() {
        guard let t = skipTarget else { return }
        if usingMPV { mpv.seek(to: t) } else { player.seek(to: CMTime(seconds: t, preferredTimescale: 600)) }
        skipTarget = nil
    }

    /// "Next Episode" pressed, or the countdown ran out.
    func playNextNow() {
        upNextVisible = false
        advance(completedCurrent: true, userAsked: true)
    }

    func dismissUpNext() {
        upNextDismissed = true
        upNextVisible = false
    }

    private func tick() {
        syncFromMPV()
        if SleepTimer.shared.consumeExpired() {
            player.pause()
            if usingMPV { mpv.pause() }
            sleptMessage = true
        }
        guard !request.isLive else { return }
        // Pause and resume for scrobbling.
        let paused = usingMPV ? mpv.isPaused : player.timeControlStatus == .paused
        if started, paused != wasPaused { onScrobble?(request, paused ? "pause" : "start", percent) }
        wasPaused = paused
        // Next Episode card for the last stretch of an episode.
        let remaining = lastDuration - lastSeconds
        let showUpNext = settings.upNextCard && request.nextEpisode != nil && !upNextDismissed && lastDuration > 300
            && remaining > 0 && remaining <= min(45, lastDuration * 0.05 + 15)
        if showUpNext != upNextVisible { upNextVisible = showUpNext }
        // Skip Intro while an intro or recap chapter plays.
        if settings.skipIntro, usingMPV {
            let chapters = mpv.chapters()
            var target: Double?
            for (i, c) in chapters.enumerated() where i + 1 < chapters.count {
                let next = chapters[i + 1].time
                if lastSeconds >= c.time, lastSeconds < next - 2, Self.isIntro(c.title) { target = next }
            }
            if target != skipTarget { skipTarget = target }
        } else if skipTarget != nil {
            skipTarget = nil
        }
    }

    static func isIntro(_ title: String) -> Bool {
        let t = title.lowercased().trimmingCharacters(in: .whitespaces)
        return t == "op" || t.contains("intro") || t.contains("opening") || t.contains("recap") || t.contains("previously")
    }

    private func syncFromMPV() {
        guard usingMPV else { return }
        if mpv.position.isFinite, mpv.position > 0 { lastSeconds = mpv.position }
        if mpv.duration.isFinite, mpv.duration > 0 { lastDuration = mpv.duration }
    }

    private func finishRecording(completed: Bool) {
        syncFromMPV()
        guard !request.isLive, lastSeconds > 5 else { return }
        let done = completed || (lastDuration > 0 && lastSeconds / lastDuration > 0.92)
        onProgress?(request, lastSeconds, lastDuration, done)
    }

    private func loadSubtitles() {
        addonSubtitles = []
        guard !request.isLive, !settings.addons.isEmpty else { return }
        let type = request.meta.type
        let id = request.videoId ?? request.meta.id
        let addons = settings.addons
        Task { [weak self] in
            let list = await AddonClient.subtitles(type: type, id: id, addons: addons)
            guard let self, (self.request.videoId ?? self.request.meta.id) == id else { return }
            self.addonSubtitles = list
            if self.usingMPV { self.attachSubtitles() }
        }
    }

    /// Hands add-on subtitles to mpv and turns on the preferred language when no subtitle is showing yet.
    private func attachSubtitles() {
        let wanted = settings.subtitleLanguage.isEmpty ? (Locale.current.language.languageCode?.identifier ?? "en") : settings.subtitleLanguage
        let existing = Set(mpv.trackLanguages("sub").filter { $0.external }.compactMap { $0.lang })
        var added = 0
        for sub in addonSubtitles where added < 30 {
            if sub == pendingSubtitle { continue }
            // Only the preferred language plus English, to keep the track list short.
            guard Languages.matches(sub.lang, wanted) || Languages.matches(sub.lang, "en") else { continue }
            if existing.contains(sub.lang ?? "") && added > 0 { continue }
            mpv.addSubtitle(url: sub.url, title: sub.label, lang: sub.lang, select: false)
            added += 1
        }
        if let pending = pendingSubtitle {
            mpv.addSubtitle(url: pending.url, title: pending.label, lang: pending.lang, select: true)
            pendingSubtitle = nil
        } else if settings.subtitlesOn, mpv.selectedSubtitle == nil,
                  let pick = mpv.trackLanguages("sub").first(where: { Languages.matches($0.lang, wanted) }) {
            mpv.selectSubtitle(id: pick.id)
        }
    }

    private func load(at i: Int, keepPosition: Bool = true) {
        guard request.candidates.indices.contains(i) else {
            failedAll = true
            return
        }
        let stream = request.candidates[i]
        if keepPosition, !request.isLive {
            let current = usingMPV ? mpv.position : player.currentTime().seconds
            if current.isFinite, current > 5 { lastSeconds = current }
        }
        index = i
        activeIndex = i
        failedAll = false
        cancellables.removeAll()
        if let url = stream.playableURL ?? resolved[i] {
            open(url: url, stream: stream, at: i)
            return
        }
        guard stream.isTorrentOnly, settings.debridReady else { failover(); return }
        // Torrent result: ask the debrid service for a link, then play it.
        status = "Getting the link from \(settings.debridService.title)…"
        timeout?.cancel()
        let ref = EpisodeRef(request.videoId)
        let service = settings.debridService
        let key = settings.debridKey
        Task { [weak self] in
            let url = try? await Debrid.resolve(stream, season: ref?.season, episode: ref?.episode, service: service, key: key)
            guard let self, self.index == i else { return }
            self.status = nil
            if let url {
                self.resolved[i] = url
                self.open(url: url, stream: stream, at: i)
            } else {
                self.nextSource()
            }
        }
    }

    private func open(url: URL, stream: StreamItem, at i: Int) {
        pendingStart = request.isLive ? 0 : (lastSeconds > 5 ? lastSeconds : request.startAt)
        let headers = stream.headers(merging: request.headers)
        let hint = (stream.behaviorHints?.filename ?? url.lastPathComponent).lowercased()
        let mpvOnly = ["mkv", "avi", "webm", "flv", "wmv", "ts", "m2ts", "mpg", "mpeg"].contains((hint as NSString).pathExtension)
            || stream.behaviorHints?.notWebReady == true
        if mpvOnly || triedMPV.contains(i) {
            player.pause()
            player.replaceCurrentItem(with: nil)
            usingMPV = true
            mpv.onLoaded = { [weak self] in
                guard let self else { return }
                self.timeout?.cancel()
                self.attachSubtitles()
                if self.speed != 1 { self.mpv.setSpeed(self.speed) }
                self.mpv.play()
                self.didStart()
            }
            mpv.onFailed = { [weak self] in self?.failover() }
            mpv.onEnded = { [weak self] in self?.ended() }
            mpv.setPreferences(audioLanguage: settings.audioLanguage, subtitleLanguage: settings.subtitleLanguage,
                               subtitlesOn: settings.subtitlesOn)
            mpv.load(url: url.absoluteString, headers: headers, start: pendingStart)
            armTimeout()
            return
        }
        if usingMPV {
            mpv.stop()
            usingMPV = false
        }
        let asset: AVURLAsset
        if headers.isEmpty {
            asset = AVURLAsset(url: url)
        } else {
            asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        }
        let item = AVPlayerItem(asset: asset)
        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                guard let self else { return }
                if status == .readyToPlay {
                    self.timeout?.cancel()
                    if self.pendingStart > 0 {
                        self.player.seek(to: CMTime(seconds: self.pendingStart, preferredTimescale: 600))
                        self.pendingStart = 0
                    }
                    self.player.defaultRate = Float(self.speed)
                    self.player.play()
                    self.applyLanguages(to: item)
                    self.didStart()
                } else if status == .failed {
                    self.failover()
                }
            }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.failover() }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.ended() }
            .store(in: &cancellables)
        player.replaceCurrentItem(with: item)
        armTimeout()
    }

    private func didStart() {
        onPlayed?(request, request.candidates[index])
        if !request.isLive, !started {
            started = true
            wasPaused = false
            onScrobble?(request, "start", percent)
        }
    }

    /// Picks the preferred audio and subtitle languages in files AVPlayer opens.
    private func applyLanguages(to item: AVPlayerItem) {
        let audio = settings.audioLanguage
        let subs = settings.subtitleLanguage
        let subsOn = settings.subtitlesOn
        Task { @MainActor in
            if !audio.isEmpty, let group = try? await item.asset.loadMediaSelectionGroup(for: .audible),
               let option = group.options.first(where: { Languages.matches($0.extendedLanguageTag ?? $0.locale?.identifier, audio) }) {
                item.select(option, in: group)
            }
            if let group = try? await item.asset.loadMediaSelectionGroup(for: .legible) {
                if !subsOn {
                    if group.allowsEmptySelection { item.select(nil, in: group) }
                } else {
                    let wanted = subs.isEmpty ? (Locale.current.language.languageCode?.identifier ?? "en") : subs
                    if let option = group.options.first(where: { Languages.matches($0.extendedLanguageTag ?? $0.locale?.identifier, wanted) }) {
                        item.select(option, in: group)
                    }
                }
            }
        }
    }

    private func armTimeout() {
        timeout?.cancel()
        let seconds = UInt64(max(settings.startupTimeout, 5)) * 1_000_000_000
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: seconds)
            if Task.isCancelled { return }
            await MainActor.run { self?.failover() }
        }
    }

    /// A failed AVPlayer source is retried once with mpv, which opens far more formats, before moving on.
    private func failover() {
        if !usingMPV, !triedMPV.contains(index), request.candidates.indices.contains(index),
           request.candidates[index].playableURL != nil || resolved[index] != nil {
            triedMPV.insert(index)
            load(at: index)
            return
        }
        nextSource()
    }

    private func nextSource() {
        guard settings.failoverEnabled, switches < settings.maxSwitches,
              request.candidates.indices.contains(index) else { failedAll = true; return }
        let failed = request.candidates[index]
        var next = index + 1
        while next < request.candidates.count, request.candidates[next].url == failed.url,
              request.candidates[next].infoHash == failed.infoHash { next += 1 }
        guard next < request.candidates.count else { failedAll = true; return }
        switches += 1
        load(at: next)
    }

    private func ended() {
        if request.isLive { return }
        advance(completedCurrent: true)
    }

    private func advance(completedCurrent: Bool, userAsked: Bool = false) {
        guard !advancing else { return }
        finishRecording(completed: completedCurrent)
        if started { onScrobble?(request, "stop", completedCurrent ? max(percent, 95) : percent) }
        started = false
        lastSeconds = 0
        if SleepTimer.shared.consumeEndOfEpisode() {
            sleptMessage = true
            return
        }
        guard settings.autoNext || userAsked, let provider = request.nextEpisode else { return }
        advancing = true
        upNextVisible = false
        status = "Finding the next episode…"
        Task {
            let next = await provider()
            status = nil
            advancing = false
            guard let next else { return }
            request = next
            triedMPV.removeAll()
            resolved.removeAll()
            switches = 0
            lastSeconds = 0
            lastDuration = 0
            upNextDismissed = false
            skipTarget = nil
            loadSubtitles()
            load(at: 0, keepPosition: false)
        }
    }
}

struct PlayerViewControllerRepresentable: UIViewControllerRepresentable {
    let player: AVPlayer
    @ObservedObject var controller: PlaybackController
    @ObservedObject var sleep: SleepTimer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        var menus: [UIMenu] = []
        let candidates = controller.request.candidates
        if candidates.count > 1 {
            let actions = candidates.prefix(25).enumerated().map { (i, s) in
                UIAction(title: s.label, subtitle: s.addonName ?? "",
                         state: i == controller.activeIndex ? .on : .off) { [weak controller] _ in
                    Task { @MainActor in controller?.choose(index: i) }
                }
            }
            menus.append(UIMenu(title: "Quality", image: UIImage(systemName: "sparkles.tv"), children: actions))
        }
        if !controller.request.isLive {
            if !controller.addonSubtitles.isEmpty {
                let subs = controller.addonSubtitles.prefix(40).map { sub in
                    UIAction(title: sub.label) { [weak controller] _ in
                        Task { @MainActor in controller?.useSubtitle(sub) }
                    }
                }
                menus.append(UIMenu(title: "Add-on subtitles", image: UIImage(systemName: "captions.bubble"), children: subs))
            }
            let speeds = [0.75, 1.0, 1.25, 1.5, 2.0].map { r in
                UIAction(title: r == 1 ? "Normal" : String(format: "%gx", r), state: controller.speed == r ? .on : .off) { [weak controller] _ in
                    Task { @MainActor in controller?.setSpeed(r) }
                }
            }
            menus.append(UIMenu(title: "Speed", image: UIImage(systemName: "speedometer"), children: speeds))
            var items: [UIAction] = [
                UIAction(title: "Off", state: sleep.mode == .off ? .on : .off) { _ in
                    Task { @MainActor in SleepTimer.shared.cancel() }
                }
            ]
            for m in SleepTimer.minuteOptions {
                items.append(UIAction(title: "\(m) minutes") { _ in
                    Task { @MainActor in SleepTimer.shared.start(minutes: m) }
                })
            }
            items.append(UIAction(title: "End of episode", state: sleep.mode == .endOfEpisode ? .on : .off) { _ in
                Task { @MainActor in SleepTimer.shared.endOfEpisode() }
            })
            menus.append(UIMenu(title: sleep.label, image: UIImage(systemName: "moon.zzz"), children: items))
        }
        vc.transportBarCustomMenuItems = menus
        // Netflix style buttons in the corner: Skip Intro and Next Episode.
        var actions: [UIAction] = []
        if controller.skipTarget != nil {
            actions.append(UIAction(title: "Skip Intro", image: UIImage(systemName: "forward.fill")) { [weak controller] _ in
                Task { @MainActor in controller?.skipIntro() }
            })
        }
        if controller.upNextVisible {
            actions.append(UIAction(title: "Next Episode", image: UIImage(systemName: "forward.end.fill")) { [weak controller] _ in
                Task { @MainActor in controller?.playNextNow() }
            })
        }
        vc.contextualActions = actions
    }
}

struct PlayerScreen: View {
    @StateObject private var controller: PlaybackController
    @ObservedObject private var sleep = SleepTimer.shared
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var streams: StreamStore
    @EnvironmentObject var trakt: TraktStore
    @Environment(\.dismiss) private var dismiss

    init(request: PlayRequest, settings: SettingsStore) {
        _controller = StateObject(wrappedValue: PlaybackController(request: request, settings: settings))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if controller.usingMPV {
                MPVPlayerLayer(controller: controller, sleep: sleep)
            } else {
                PlayerViewControllerRepresentable(player: controller.player, controller: controller, sleep: sleep)
                    .ignoresSafeArea()
            }
            if let status = controller.status {
                VStack {
                    HStack(spacing: 16) { ProgressView(); Text(status) }
                        .padding(.horizontal, 28).padding(.vertical, 16).glass()
                    Spacer()
                }
                .padding(.top, 60)
                .allowsHitTesting(false)
            }
            if controller.failedAll {
                VStack(spacing: 20) {
                    Text("None of the available sources would play.")
                    Button("Close") { dismiss() }
                }
                .padding(40)
                .glass()
            }
            if controller.sleptMessage {
                VStack(spacing: 20) {
                    Text("Sleep timer ended playback.")
                    Button("Close") { dismiss() }
                    Button("Keep watching") {
                        controller.sleptMessage = false
                        controller.player.play()
                        if controller.usingMPV { controller.mpv.play() }
                    }
                }
                .padding(40)
                .glass()
            }
        }
        .onAppear {
            controller.onProgress = { request, seconds, duration, completed in
                library.recordPlay(meta: request.meta, videoId: request.videoId, seconds: seconds,
                                   duration: duration > 0 ? duration : nil, completed: completed)
            }
            controller.onPlayed = { request, stream in
                if request.meta.type == "series" { streams.rememberRelease(parentId: request.meta.id, stream: stream) }
            }
            controller.onScrobble = { request, action, percent in
                trakt.scrobble(action, meta: request.meta, videoId: request.videoId, progress: percent)
            }
            controller.start()
        }
        .onDisappear { controller.stop() }
    }
}

/// Controls for mpv playback, laid out like the system player: click to pause, left and right to skip,
/// swipe up or down for quality, audio, subtitles, speed and the sleep timer.
struct MPVPlayerLayer: View {
    @ObservedObject var controller: PlaybackController
    @ObservedObject var sleep: SleepTimer
    @State private var showMenu = false
    @State private var showBar = true
    @State private var tracks: [MPVEngine.Track] = []
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        ZStack(alignment: .bottom) {
            MPVSurface(engine: controller.mpv).ignoresSafeArea()
            if showBar || showMenu {
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                VStack(alignment: .leading, spacing: 18) {
                    if showMenu { menuPanel }
                    progressBar
                }
                .padding(.horizontal, 80)
                .padding(.bottom, 60)
                .transition(.opacity)
            }
            if !showMenu, let corner = cornerAction {
                // Netflix style corner button. Clicking the remote presses it while it is showing.
                HStack {
                    Spacer()
                    Label(corner, systemImage: controller.skipTarget != nil ? "forward.fill" : "forward.end.fill")
                        .font(.headline)
                        .foregroundStyle(.black)
                        .padding(.horizontal, 30).padding(.vertical, 16)
                        .background(.white, in: RoundedRectangle(cornerRadius: 12))
                        .shadow(radius: 12)
                }
                .padding(.trailing, 80)
                .padding(.bottom, showBar ? 200 : 80)
                .transition(.opacity)
                .allowsHitTesting(false)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showBar)
        .animation(.easeInOut(duration: 0.2), value: showMenu)
        .focusable(!showMenu)
        .onPlayPauseCommand { controller.mpv.togglePause(); poke() }
        .onTapGesture {
            if controller.skipTarget != nil { controller.skipIntro() }
            else if controller.upNextVisible { controller.playNextNow() }
            else { controller.mpv.togglePause() }
            poke()
        }
        .onMoveCommand { direction in
            guard !showMenu else { return }
            switch direction {
            case .left: controller.mpv.seek(by: -10); poke()
            case .right: controller.mpv.seek(by: 10); poke()
            case .up, .down: openMenu()
            @unknown default: break
            }
        }
        .onExitCommand(perform: showMenu ? { showMenu = false; poke() } : nil)
        .onAppear { poke() }
    }

    private var cornerAction: String? {
        if controller.skipTarget != nil { return "Skip Intro" }
        if controller.upNextVisible { return "Next Episode" }
        return nil
    }

    private var progressBar: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let pos = controller.mpv.position
            let dur = controller.mpv.duration
            VStack(alignment: .leading, spacing: 12) {
                Text(controller.request.title).font(.title3.bold()).lineLimit(1)
                if !controller.request.isLive, dur > 0 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(.white.opacity(0.25))
                            Capsule().fill(.white).frame(width: geo.size.width * min(1, max(0, pos / dur)))
                        }
                    }
                    .frame(height: 8)
                    HStack {
                        Text(Self.clock(pos))
                        if controller.mpv.isPaused { Image(systemName: "pause.fill") }
                        if controller.speed != 1 { Text(String(format: "%gx", controller.speed)) }
                        Spacer()
                        Text("-" + Self.clock(max(0, dur - pos)))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                } else if controller.request.isLive {
                    Label("Live", systemImage: "dot.radiowaves.left.and.right").font(.caption).foregroundStyle(.red)
                }
            }
        }
    }

    private var menuPanel: some View {
        HStack(spacing: 30) {
            if controller.request.candidates.count > 1 {
                Menu {
                    ForEach(Array(controller.request.candidates.prefix(25).enumerated()), id: \.offset) { i, s in
                        Button { controller.choose(index: i) } label: {
                            if i == controller.activeIndex { Label("\(s.label) \(s.addonName ?? "")", systemImage: "checkmark") }
                            else { Text("\(s.label) \(s.addonName ?? "")") }
                        }
                    }
                } label: { Label("Quality", systemImage: "sparkles.tv") }
            }
            let audio = tracks.filter { $0.type == "audio" }
            if audio.count > 1 {
                Menu {
                    ForEach(audio) { t in
                        Button { controller.mpv.select(t); refreshTracks() } label: {
                            if t.selected { Label(t.title, systemImage: "checkmark") } else { Text(t.title) }
                        }
                    }
                } label: { Label("Audio", systemImage: "speaker.wave.2") }
            }
            let subs = tracks.filter { $0.type == "sub" }
            if !subs.isEmpty || !controller.addonSubtitles.isEmpty {
                Menu {
                    Button("Off") { controller.mpv.subtitlesOff(); refreshTracks() }
                    ForEach(subs) { t in
                        Button { controller.mpv.select(t); refreshTracks() } label: {
                            if t.selected { Label(t.title, systemImage: "checkmark") } else { Text(t.title) }
                        }
                    }
                    if !controller.addonSubtitles.isEmpty {
                        Menu("More from add-ons") {
                            ForEach(Array(controller.addonSubtitles.prefix(40).enumerated()), id: \.offset) { _, sub in
                                Button(sub.label) { controller.useSubtitle(sub); refreshTracks() }
                            }
                        }
                    }
                } label: { Label("Subtitles", systemImage: "captions.bubble") }
            }
            if !controller.request.isLive {
                Menu {
                    ForEach([0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { r in
                        Button { controller.setSpeed(r) } label: {
                            let title = r == 1 ? "Normal" : String(format: "%gx", r)
                            if controller.speed == r { Label(title, systemImage: "checkmark") } else { Text(title) }
                        }
                    }
                } label: { Label("Speed", systemImage: "speedometer") }
                Menu {
                    Button("Off") { SleepTimer.shared.cancel() }
                    ForEach(SleepTimer.minuteOptions, id: \.self) { m in
                        Button("\(m) minutes") { SleepTimer.shared.start(minutes: m) }
                    }
                    Button("End of episode") { SleepTimer.shared.endOfEpisode() }
                } label: { Label(sleep.label, systemImage: "moon.zzz") }
                Button { controller.mpv.seek(by: -30) } label: { Label("30s", systemImage: "gobackward.30") }
                Button { controller.mpv.seek(by: 30) } label: { Label("30s", systemImage: "goforward.30") }
            }
        }
        .padding(24)
        .glass()
    }

    private func openMenu() {
        refreshTracks()
        showMenu = true
        showBar = true
        hideTask?.cancel()
    }

    private func refreshTracks() { tracks = controller.mpv.tracks() }

    /// Shows the progress bar for a few seconds after any remote press.
    private func poke() {
        showBar = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            if Task.isCancelled { return }
            if !showMenu && !controller.mpv.isPaused { showBar = false }
        }
    }

    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let s = Int(seconds)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                         : String(format: "%d:%02d", s / 60, s % 60)
    }
}
