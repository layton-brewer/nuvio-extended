import Foundation
import SwiftUI

enum StreamMode: String, Codable, CaseIterable, Identifiable {
    case smart, manual
    var id: String { rawValue }
    var title: String { self == .smart ? "Smart" : "Manual" }
}

enum LiveSourceType: String, Codable { case m3u, xtream }

/// Debrid services that can turn torrent results into direct links, like Direct Debrid on Android TV.
enum DebridService: String, Codable, CaseIterable, Identifiable {
    case none, realDebrid, torbox, premiumize
    var id: String { rawValue }
    var title: String {
        switch self {
        case .none: return "None"
        case .realDebrid: return "Real-Debrid"
        case .torbox: return "TorBox"
        case .premiumize: return "Premiumize"
        }
    }
}

/// A playlist or provider login the user added for Live TV. With none added, the built-in free channels show.
struct LiveSource: Codable, Hashable, Identifiable {
    var type: LiveSourceType = .m3u
    var url: String = ""
    var username: String = ""
    var password: String = ""
    var guideUrl: String = ""
    var name: String = ""
    var enabled: Bool = true

    init(type: LiveSourceType = .m3u, url: String = "", username: String = "", password: String = "",
         guideUrl: String = "", name: String = "", enabled: Bool = true) {
        self.type = type; self.url = url; self.username = username; self.password = password
        self.guideUrl = guideUrl; self.name = name; self.enabled = enabled
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = (try? c.decode(LiveSourceType.self, forKey: .type)) ?? .m3u
        url = (try? c.decode(String.self, forKey: .url)) ?? ""
        username = (try? c.decode(String.self, forKey: .username)) ?? ""
        password = (try? c.decode(String.self, forKey: .password)) ?? ""
        guideUrl = (try? c.decode(String.self, forKey: .guideUrl)) ?? ""
        name = (try? c.decode(String.self, forKey: .name)) ?? ""
        enabled = (try? c.decode(Bool.self, forKey: .enabled)) ?? true
    }

    /// Stable id for this playlist, the same one the Android apps use.
    var key: String {
        let text = "\(type.rawValue)|\(url.trimmingCharacters(in: .whitespaces))|\(username.trimmingCharacters(in: .whitespaces))"
        var h: Int32 = 0
        for u in text.utf16 { h = h &* 31 &+ Int32(bitPattern: UInt32(u)) }
        return String(UInt32(bitPattern: h), radix: 36)
    }
    var id: String { key }
    var isEmpty: Bool { url.trimmingCharacters(in: .whitespaces).isEmpty }

    var label: String {
        let n = name.trimmingCharacters(in: .whitespaces)
        if !n.isEmpty { return n }
        let host = URLComponents(string: type == .xtream ? XtreamApi.normalizeServer(url) : url)?.host ?? url
        return host.isEmpty ? (type == .xtream ? "Xtream login" : "Playlist") : host
    }
}

/// Colour themes. The first five match the Android apps' base themes, the last three are Meowio's own.
enum MeowioTheme: String, Codable, CaseIterable, Identifiable {
    case meowio, ocean, emerald, amber, rose, sunset, aurora, midnight
    var id: String { rawValue }
    var title: String { rawValue.capitalized }

    var accent: Color {
        switch self {
        case .meowio: return Color(red: 0.69, green: 0.45, blue: 0.95)
        case .ocean: return Color(red: 0.22, green: 0.60, blue: 0.95)
        case .emerald: return Color(red: 0.20, green: 0.78, blue: 0.52)
        case .amber: return Color(red: 0.98, green: 0.70, blue: 0.20)
        case .rose: return Color(red: 0.95, green: 0.42, blue: 0.58)
        case .sunset: return Color(red: 1.0, green: 0.42, blue: 0.37)
        case .aurora: return Color(red: 0.22, green: 0.72, blue: 0.91)
        case .midnight: return Color(red: 0.33, green: 0.41, blue: 1.0)
        }
    }

    var background: Color {
        switch self {
        case .meowio: return Color(red: 0.07, green: 0.05, blue: 0.11)
        case .ocean: return Color(red: 0.04, green: 0.07, blue: 0.11)
        case .emerald: return Color(red: 0.04, green: 0.09, blue: 0.07)
        case .amber: return Color(red: 0.10, green: 0.08, blue: 0.04)
        case .rose: return Color(red: 0.10, green: 0.06, blue: 0.08)
        case .sunset: return Color(red: 0.063, green: 0.043, blue: 0.043)
        case .aurora: return Color(red: 0.035, green: 0.055, blue: 0.063)
        case .midnight: return Color(red: 0.027, green: 0.031, blue: 0.059)
        }
    }
}

/// Everything the user can change. Defaults match the Android TV app: Smart selection, auto-next,
/// same-release preference, preloading and six silent source switches per title.
@MainActor
final class SettingsStore: ObservableObject {
    @Published var addons: [String] { didSet { save() } }
    @Published var streamMode: StreamMode { didSet { save() } }
    @Published var autoNext: Bool { didSet { save() } }
    @Published var sameRelease: Bool { didSet { save() } }
    @Published var preloadNext: Bool { didSet { save() } }
    @Published var failoverEnabled: Bool { didSet { save() } }
    @Published var maxSwitches: Int { didSet { save() } }
    @Published var startupTimeout: Int { didSet { save() } }
    @Published var pinnedSources: [String] { didSet { save() } }
    @Published var theme: MeowioTheme { didSet { save() } }
    /// Live TV playlists and logins. Empty means the built-in free channels.
    @Published var liveSources: [LiveSource] { didSet { save() } }
    /// Live TV VPN: an HTTP proxy used only by Live TV playback, never by debrid or anything else.
    /// It is used while a channel plays and dropped as soon as playback leaves Live TV.
    @Published var liveVpnEnabled: Bool { didSet { save() } }
    @Published var liveVpnProxy: String { didSet { save() } }
    @Published var liveTabVisible: Bool { didSet { save() } }
    @Published var aiKey: String { didSet { save() } }
    @Published var aiModel: String { didSet { save() } }
    @Published var debridService: DebridService { didSet { save() } }
    @Published var debridKey: String { didSet { save() } }
    /// Preferred audio and subtitle languages as ISO 639-1 codes. Empty means the file's default.
    @Published var audioLanguage: String { didSet { save() } }
    @Published var subtitleLanguage: String { didSet { save() } }
    @Published var subtitlesOn: Bool { didSet { save() } }
    @Published var skipIntro: Bool { didSet { save() } }
    @Published var upNextCard: Bool { didSet { save() } }

    /// Storage for this profile, and for its add-ons (the main profile's when it shares them).
    private let store: UserDefaults
    private let addonStore: UserDefaults

    var debridReady: Bool { debridService != .none && !debridKey.trimmingCharacters(in: .whitespaces).isEmpty }

    init(sharesPrimaryAddons: Bool = false) {
        let d = ProfileScope.defaults
        store = d
        addonStore = sharesPrimaryAddons ? ProfileScope.defaults(for: 1) : d
        addons = addonStore.stringArray(forKey: "addons") ?? []
        streamMode = StreamMode(rawValue: d.string(forKey: "streamMode") ?? "") ?? .smart
        autoNext = d.object(forKey: "autoNext") as? Bool ?? true
        sameRelease = d.object(forKey: "sameRelease") as? Bool ?? true
        preloadNext = d.object(forKey: "preloadNext") as? Bool ?? true
        failoverEnabled = d.object(forKey: "failoverEnabled") as? Bool ?? true
        maxSwitches = d.object(forKey: "maxSwitches") as? Int ?? 6
        startupTimeout = d.object(forKey: "startupTimeout") as? Int ?? 20
        pinnedSources = d.stringArray(forKey: "pinnedSources") ?? []
        theme = MeowioTheme(rawValue: d.string(forKey: "theme") ?? "") ?? .meowio
        if let data = d.data(forKey: "liveSources"), let list = try? JSONDecoder().decode([LiveSource].self, from: data) {
            liveSources = list
        } else if let data = d.data(forKey: "liveSource"), let s = try? JSONDecoder().decode(LiveSource.self, from: data) {
            liveSources = s.isEmpty ? [] : [s]
        } else if let legacy = d.string(forKey: "playlistURL"), !legacy.isEmpty {
            liveSources = [LiveSource(type: .m3u, url: legacy)]
        } else {
            liveSources = []
        }
        liveVpnEnabled = d.object(forKey: "liveVpnEnabled") as? Bool ?? false
        liveVpnProxy = d.string(forKey: "liveVpnProxy") ?? ""
        liveTabVisible = d.object(forKey: "liveTabVisible") as? Bool ?? true
        aiKey = d.string(forKey: "aiKey") ?? ""
        aiModel = d.string(forKey: "aiModel") ?? ""
        debridService = DebridService(rawValue: d.string(forKey: "debridService") ?? "") ?? .none
        debridKey = d.string(forKey: "debridKey") ?? ""
        audioLanguage = d.string(forKey: "audioLanguage") ?? ""
        subtitleLanguage = d.string(forKey: "subtitleLanguage") ?? ""
        subtitlesOn = d.object(forKey: "subtitlesOn") as? Bool ?? false
        skipIntro = d.object(forKey: "skipIntro") as? Bool ?? true
        upNextCard = d.object(forKey: "upNextCard") as? Bool ?? true
    }

    private func save() {
        let d = store
        addonStore.set(addons, forKey: "addons")
        d.set(streamMode.rawValue, forKey: "streamMode")
        d.set(autoNext, forKey: "autoNext")
        d.set(sameRelease, forKey: "sameRelease")
        d.set(preloadNext, forKey: "preloadNext")
        d.set(failoverEnabled, forKey: "failoverEnabled")
        d.set(maxSwitches, forKey: "maxSwitches")
        d.set(startupTimeout, forKey: "startupTimeout")
        d.set(pinnedSources, forKey: "pinnedSources")
        d.set(theme.rawValue, forKey: "theme")
        if let data = try? JSONEncoder().encode(liveSources) { d.set(data, forKey: "liveSources") }
        d.set(liveVpnEnabled, forKey: "liveVpnEnabled")
        d.set(liveVpnProxy, forKey: "liveVpnProxy")
        d.set(liveTabVisible, forKey: "liveTabVisible")
        d.set(aiKey, forKey: "aiKey")
        d.set(aiModel, forKey: "aiModel")
        d.set(debridService.rawValue, forKey: "debridService")
        d.set(debridKey, forKey: "debridKey")
        d.set(audioLanguage, forKey: "audioLanguage")
        d.set(subtitleLanguage, forKey: "subtitleLanguage")
        d.set(subtitlesOn, forKey: "subtitlesOn")
        d.set(skipIntro, forKey: "skipIntro")
        d.set(upNextCard, forKey: "upNextCard")
    }

    func addAddon(_ raw: String) {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("stremio://") { s = "https://" + s.dropFirst("stremio://".count) }
        if s.hasSuffix("/manifest.json") { s = String(s.dropLast("/manifest.json".count)) }
        while s.hasSuffix("/") { s.removeLast() }
        guard !s.isEmpty, URL(string: s) != nil, !addons.contains(s) else { return }
        addons.append(s)
    }

    /// The proxy Live TV playback should use now, or "" when the Live TV VPN is off or not set up.
    var liveProxyForPlayback: String {
        guard liveVpnEnabled, let proxy = LiveVpnGuard.parseProxy(liveVpnProxy) else { return "" }
        return proxy.asUrl()
    }

    func addLiveSource(_ source: LiveSource) {
        if let i = liveSources.firstIndex(where: { $0.key == source.key }) { liveSources[i] = source }
        else { liveSources.append(source) }
    }

    func togglePinned(_ name: String) {
        if let i = pinnedSources.firstIndex(of: name) { pinnedSources.remove(at: i) } else { pinnedSources.append(name) }
    }

    // MARK: Backup and restore

    /// Settings backup. The AI key and debrid key are left out on purpose so a backup is safe to share.
    struct Backup: Codable {
        var app = "meowio-tvos"
        var version = 1
        var addons: [String]
        var streamMode: StreamMode
        var autoNext: Bool
        var sameRelease: Bool
        var preloadNext: Bool
        var failoverEnabled: Bool
        var maxSwitches: Int
        var startupTimeout: Int
        var pinnedSources: [String]
        var theme: MeowioTheme
        /// Older backups hold one playlist here; newer ones use liveSources.
        var liveSource: LiveSource?
        var liveTabVisible: Bool
        /// True when personal add-on links were left out; restoring keeps this device's own. Absent in older backups.
        var privateAddonLinksLeftOut: Bool?
        var audioLanguage: String?
        var subtitleLanguage: String?
        var subtitlesOn: Bool?
        var skipIntro: Bool?
        var upNextCard: Bool?
        var debridService: DebridService?
        var liveSources: [LiveSource]?
        var liveVpnEnabled: Bool?
    }

    /// True for an add-on link that carries anything beyond a host and /manifest.json (usually a debrid key).
    static func isPrivateAddonLink(_ link: String) -> Bool {
        guard let c = URLComponents(string: link.trimmingCharacters(in: .whitespaces)),
              let host = c.host, !host.isEmpty else { return true }
        if c.user != nil || c.password != nil || c.query != nil || c.fragment != nil { return true }
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        if path.lowercased().hasSuffix("/manifest.json") { path.removeLast("/manifest.json".count) }
        return !path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).isEmpty
    }

    func exportBackup(includePrivateAddonLinks: Bool = false) -> String {
        let backedUpAddons = includePrivateAddonLinks ? addons : addons.filter { !Self.isPrivateAddonLink($0) }
        var b = Backup(addons: backedUpAddons, streamMode: streamMode, autoNext: autoNext, sameRelease: sameRelease,
                       preloadNext: preloadNext, failoverEnabled: failoverEnabled, maxSwitches: maxSwitches,
                       startupTimeout: startupTimeout, pinnedSources: pinnedSources, theme: theme,
                       liveSource: nil, liveTabVisible: liveTabVisible)
        b.liveSources = liveSources
        // The proxy address can hold a login, so only the on/off choice is backed up.
        b.liveVpnEnabled = liveVpnEnabled
        if !includePrivateAddonLinks { b.privateAddonLinksLeftOut = true }
        b.audioLanguage = audioLanguage
        b.subtitleLanguage = subtitleLanguage
        b.subtitlesOn = subtitlesOn
        b.skipIntro = skipIntro
        b.upNextCard = upNextCard
        // The debrid service is kept, its key is not, so a backup is safe to share.
        b.debridService = debridService
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(b), let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }

    @discardableResult
    func restoreBackup(_ text: String) -> Bool {
        guard let data = text.data(using: .utf8), let b = try? JSONDecoder().decode(Backup.self, from: data),
              b.app.hasPrefix("meowio") else { return false }
        if b.privateAddonLinksLeftOut == true {
            let kept = addons.filter { Self.isPrivateAddonLink($0) && !b.addons.contains($0) }
            addons = b.addons + kept
        } else {
            addons = b.addons
        }
        streamMode = b.streamMode
        autoNext = b.autoNext
        sameRelease = b.sameRelease
        preloadNext = b.preloadNext
        failoverEnabled = b.failoverEnabled
        maxSwitches = b.maxSwitches
        startupTimeout = b.startupTimeout
        pinnedSources = b.pinnedSources
        theme = b.theme
        if let list = b.liveSources { liveSources = list }
        else if let one = b.liveSource { liveSources = one.isEmpty ? [] : [one] }
        if let v = b.liveVpnEnabled { liveVpnEnabled = v }
        liveTabVisible = b.liveTabVisible
        if let v = b.audioLanguage { audioLanguage = v }
        if let v = b.subtitleLanguage { subtitleLanguage = v }
        if let v = b.subtitlesOn { subtitlesOn = v }
        if let v = b.skipIntro { skipIntro = v }
        if let v = b.upNextCard { upNextCard = v }
        if let v = b.debridService, v != debridService { debridService = v; if v == .none { debridKey = "" } }
        return true
    }
}

/// Glass panels: translucent material with a thin light rim, like the Android apps.
struct GlassBackground: ViewModifier {
    var radius: CGFloat = 20
    func body(content: Content) -> some View {
        content
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius))
            // Soft highlight along the top, like light catching the glass.
            .overlay(
                RoundedRectangle(cornerRadius: radius)
                    .fill(LinearGradient(colors: [Color.white.opacity(0.11), Color.clear], startPoint: .top, endPoint: .center))
                    .allowsHitTesting(false)
            )
            // Rim lit from the top left with a faint catch light on the opposite corner.
            .overlay(
                RoundedRectangle(cornerRadius: radius)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(0.38), Color.white.opacity(0.08), Color.white.opacity(0.16)],
                            startPoint: .topLeading, endPoint: .bottomTrailing),
                        lineWidth: 1)
            )
    }
}

extension View {
    func glass(radius: CGFloat = 20) -> some View { modifier(GlassBackground(radius: radius)) }
}
