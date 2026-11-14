import SwiftUI
import UIKit
import QuartzCore
import Libmpv

/// libmpv playback for formats AVPlayer cannot open (MKV and friends). Mirrors the iOS app's mpv setup.
final class MPVEngine: NSObject {
    /// Layer mpv draws into.
    let layer = TVMetalLayer()
    private var mpv: OpaquePointer?
    private let queue = DispatchQueue(label: "meowio.mpv.events")
    private var lastPosition: Double = 0
    private var lastDuration: Double = 0
    private var loaded = false

    var onLoaded: (() -> Void)?
    var onFailed: (() -> Void)?
    var onEnded: (() -> Void)?

    var position: Double { lastPosition }
    var duration: Double { lastDuration }

    override init() {
        super.init()
        layer.pixelFormat = .bgra8Unorm
        layer.framebufferOnly = true
    }

    private func start() {
        guard mpv == nil else { return }
        mpv = mpv_create()
        guard let mpv else { return }
        var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
        mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &wid)
        let options: [(String, String)] = [
            ("vo", "gpu-next"), ("gpu-api", "vulkan"), ("gpu-context", "moltenvk"),
            ("hwdec", "videotoolbox"), ("hwdec-software-fallback", "300"),
            ("ao", "audiounit"), ("audio-channels", "auto"), ("audio-fallback-to-null", "yes"),
            ("vulkan-swap-mode", "fifo"), ("vulkan-queue-count", "1"),
            ("vulkan-async-compute", "no"), ("vulkan-async-transfer", "no"), ("vulkan-disable-interop", "yes"),
            ("subs-match-os-language", "yes"), ("subs-fallback", "yes"),
            ("keep-open", "yes"), ("target-colorspace-hint", "yes"), ("tone-mapping", "auto"),
            ("cache", "yes"), ("demuxer-max-bytes", "150MiB")
        ]
        for (k, v) in options { mpv_set_option_string(mpv, k, v) }
        mpv_initialize(mpv)
        mpv_observe_property(mpv, 0, "time-pos", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG)
        mpv_set_wakeup_callback(mpv, { ctx in
            guard let ctx else { return }
            let engine = Unmanaged<MPVEngine>.fromOpaque(ctx).takeUnretainedValue()
            engine.readEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    /// Starts mpv early so preferences can be set before the first file loads.
    func prepare() { start() }

    /// [proxy] is the Live TV VPN for a Live TV channel. It is set on every load, so any other file
    /// (debrid links included) always loads with no proxy.
    func load(url: String, headers: [String: String], start startAt: Double = 0, proxy: String = "") {
        start()
        guard let mpv else { onFailed?(); return }
        loaded = false
        lastPosition = 0
        lastDuration = 0
        if headers.isEmpty {
            mpv_set_property_string(mpv, "http-header-fields", "")
        } else {
            let joined = headers.map { "\($0.key): \($0.value)" }.joined(separator: ",")
            mpv_set_property_string(mpv, "http-header-fields", joined)
        }
        mpv_set_property_string(mpv, "http-proxy", proxy)
        mpv_set_property_string(mpv, "start", startAt > 0 ? String(format: "%.1f", startAt) : "none")
        command(["loadfile", url, "replace"])
    }

    func play() { setFlag("pause", false) }
    func pause() { setFlag("pause", true) }
    var isPaused: Bool {
        guard let mpv else { return true }
        var flag: Int32 = 0
        mpv_get_property(mpv, "pause", MPV_FORMAT_FLAG, &flag)
        return flag != 0
    }
    func togglePause() { setFlag("pause", !isPaused) }
    func seek(by seconds: Double) { command(["seek", String(seconds), "relative"]) }

    func seek(to seconds: Double) { command(["seek", String(format: "%.1f", max(0, seconds)), "absolute"]) }

    /// An audio or subtitle track in the file that is playing.
    struct Track: Identifiable, Hashable {
        let id: Int
        let type: String
        let title: String
        let selected: Bool
    }

    /// Audio and subtitle tracks, read from mpv's track list.
    func tracks() -> [Track] {
        let count = Int(string("track-list/count") ?? "") ?? 0
        var out: [Track] = []
        for i in 0..<count {
            guard let type = string("track-list/\(i)/type"), type == "audio" || type == "sub",
                  let id = Int(string("track-list/\(i)/id") ?? "") else { continue }
            let lang = string("track-list/\(i)/lang")
            let title = string("track-list/\(i)/title")
            let codec = string("track-list/\(i)/codec")
            let channels = string("track-list/\(i)/demux-channel-count")
            var parts: [String] = []
            if let lang, !lang.isEmpty { parts.append(Locale.current.localizedString(forLanguageCode: lang) ?? lang) }
            if let title, !title.isEmpty { parts.append(title) }
            if let codec, !codec.isEmpty { parts.append(codec.uppercased()) }
            if type == "audio", let channels, let n = Int(channels), n > 0 { parts.append(n > 2 ? "\(n - 1).1" : (n == 2 ? "Stereo" : "Mono")) }
            let label = parts.isEmpty ? "Track \(id)" : parts.joined(separator: " · ")
            out.append(Track(id: id, type: type, title: label, selected: string("track-list/\(i)/selected") == "yes"))
        }
        return out
    }

    /// Track ids of a type with their language codes, for picking a preferred language.
    func trackLanguages(_ type: String) -> [(id: Int, lang: String?, external: Bool)] {
        let count = Int(string("track-list/count") ?? "") ?? 0
        var out: [(id: Int, lang: String?, external: Bool)] = []
        for i in 0..<count where string("track-list/\(i)/type") == type {
            if let id = Int(string("track-list/\(i)/id") ?? "") {
                out.append((id, string("track-list/\(i)/lang"), string("track-list/\(i)/external") == "yes"))
            }
        }
        return out
    }

    var selectedSubtitle: Int? { Int(string("sid") ?? "") }

    func selectSubtitle(id: Int) {
        guard let mpv else { return }
        mpv_set_property_string(mpv, "sid", String(id))
    }

    /// Adds a subtitle file from an add-on. "auto" leaves the choice to the normal track selection.
    func addSubtitle(url: String, title: String, lang: String?, select: Bool) {
        command(["sub-add", url, select ? "select" : "auto", title, lang ?? ""])
    }

    /// Preferred languages for the next file. Empty audio language keeps the file's default track.
    func setPreferences(audioLanguage: String, subtitleLanguage: String, subtitlesOn: Bool) {
        start()
        guard let mpv else { return }
        mpv_set_property_string(mpv, "alang", Languages.codes(for: audioLanguage).joined(separator: ","))
        mpv_set_property_string(mpv, "slang", Languages.codes(for: subtitleLanguage).joined(separator: ","))
        mpv_set_property_string(mpv, "sid", subtitlesOn ? "auto" : "no")
        mpv_set_property_string(mpv, "subs-match-os-language", subtitlesOn && subtitleLanguage.isEmpty ? "yes" : "no")
    }

    var speed: Double { Double(string("speed") ?? "") ?? 1 }

    func setSpeed(_ value: Double) {
        guard let mpv else { return }
        mpv_set_property_string(mpv, "speed", String(format: "%.2f", value))
    }

    /// Chapters in the file, with their start times, used for Skip Intro.
    func chapters() -> [(title: String, time: Double)] {
        let count = Int(string("chapter-list/count") ?? "") ?? 0
        var out: [(title: String, time: Double)] = []
        for i in 0..<count {
            let title = string("chapter-list/\(i)/title") ?? ""
            let time = Double(string("chapter-list/\(i)/time") ?? "") ?? 0
            out.append((title, time))
        }
        return out
    }

    func select(_ track: Track) {
        guard let mpv else { return }
        mpv_set_property_string(mpv, track.type == "audio" ? "aid" : "sid", String(track.id))
    }

    func subtitlesOff() {
        guard let mpv else { return }
        mpv_set_property_string(mpv, "sid", "no")
    }

    private func string(_ name: String) -> String? {
        guard let mpv, let c = mpv_get_property_string(mpv, name) else { return nil }
        defer { mpv_free(c) }
        return String(cString: c)
    }

    func stop() {
        guard let m = mpv else { return }
        mpv = nil
        mpv_set_wakeup_callback(m, nil, nil)
        mpv_terminate_destroy(m)
    }

    private func setFlag(_ name: String, _ value: Bool) {
        guard let mpv else { return }
        var v: Int32 = value ? 1 : 0
        mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &v)
    }

    private func command(_ args: [String]) {
        guard let mpv else { return }
        let owned: [UnsafeMutablePointer<CChar>?] = args.map { strdup($0) }
        defer { for p in owned { free(p) } }
        var cstrings: [UnsafePointer<CChar>?] = owned.map { $0.map { UnsafePointer($0) } }
        cstrings.append(nil)
        cstrings.withUnsafeMutableBufferPointer { buf in
            _ = mpv_command(mpv, buf.baseAddress)
        }
    }

    private func readEvents() {
        queue.async { [weak self] in
            guard let self else { return }
            while let mpv = self.mpv {
                guard let event = mpv_wait_event(mpv, 0) else { break }
                let id = event.pointee.event_id
                if id == MPV_EVENT_NONE { break }
                switch id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    guard let data = event.pointee.data else { break }
                    let prop = data.assumingMemoryBound(to: mpv_event_property.self).pointee
                    let name = String(cString: prop.name)
                    if name == "time-pos", prop.format == MPV_FORMAT_DOUBLE, let d = prop.data {
                        self.lastPosition = d.assumingMemoryBound(to: Double.self).pointee
                    } else if name == "duration", prop.format == MPV_FORMAT_DOUBLE, let d = prop.data {
                        self.lastDuration = d.assumingMemoryBound(to: Double.self).pointee
                    } else if name == "eof-reached", prop.format == MPV_FORMAT_FLAG, let d = prop.data,
                              d.assumingMemoryBound(to: Int32.self).pointee != 0 {
                        DispatchQueue.main.async { self.onEnded?() }
                    }
                case MPV_EVENT_FILE_LOADED:
                    self.loaded = true
                    DispatchQueue.main.async { self.onLoaded?() }
                case MPV_EVENT_END_FILE:
                    if let data = event.pointee.data {
                        let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                        if end.reason == MPV_END_FILE_REASON_ERROR {
                            DispatchQueue.main.async { self.onFailed?() }
                        }
                    }
                case MPV_EVENT_SHUTDOWN:
                    return
                default:
                    break
                }
            }
        }
    }
}

/// A Metal layer that ignores degenerate drawable sizes while mpv's swapchain is being set up.
final class TVMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 { super.drawableSize = newValue }
        }
    }
}

/// Hosts the engine's layer.
struct MPVSurface: UIViewRepresentable {
    let engine: MPVEngine

    final class Host: UIView {
        var engine: MPVEngine?
        override func layoutSubviews() {
            super.layoutSubviews()
            guard let layer = engine?.layer else { return }
            let scale = window?.screen.nativeScale ?? UIScreen.main.nativeScale
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.contentsScale = scale
            layer.frame = bounds
            layer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
            CATransaction.commit()
        }
    }

    func makeUIView(context: Context) -> Host {
        let v = Host()
        v.backgroundColor = .black
        v.engine = engine
        v.layer.addSublayer(engine.layer)
        return v
    }

    func updateUIView(_ uiView: Host, context: Context) {}
}
