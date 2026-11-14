import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var profiles: ProfileStore
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var trakt: TraktStore
    @State private var newAddon = ""
    @State private var addonNames: [String: String] = [:]
    @StateObject private var updates = UpdateChecker()

    var body: some View {
        NavigationStack {
            List {
                Section("Profile and accounts") {
                    if let p = profiles.active {
                        NavigationLink { ProfilesSettingsView() } label: {
                            HStack(spacing: 20) {
                                ProfileAvatar(profile: p, size: 50)
                                Text(p.name)
                                Spacer()
                                Text("Profiles").foregroundStyle(.secondary)
                            }
                        }
                    }
                    NavigationLink { AccountSettingsView() } label: {
                        HStack {
                            Text("Meowio account")
                            Spacer()
                            Text(account.signedIn ? (account.email ?? "Signed in") : (AccountStore.available ? "Not signed in" : "Off"))
                                .foregroundStyle(.secondary)
                        }
                    }
                    // Trakt is only offered when this build has Trakt sign-in keys (or you're already signed in).
                    if TraktStore.configured || trakt.connected {
                        NavigationLink { TraktSettingsView() } label: {
                            HStack {
                                Text("Trakt")
                                Spacer()
                                Text(trakt.connected ? (trakt.username ?? "Connected") : "Not connected").foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Section("Stream add-ons") {
                    ForEach(settings.addons, id: \.self) { base in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(addonNames[base] ?? base)
                                Text(base).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            Button("Remove") { settings.addons.removeAll { $0 == base } }
                        }
                    }
                    TextField("Add-on link (https://… or stremio://…)", text: $newAddon)
                    Button("Add add-on") {
                        settings.addAddon(newAddon)
                        newAddon = ""
                    }
                }

                Section("Playback") {
                    Picker("Source selection", selection: $settings.streamMode) {
                        ForEach(StreamMode.allCases) { Text($0.title).tag($0) }
                    }
                    Toggle("Play next episode automatically", isOn: $settings.autoNext)
                    Toggle("Prefer the same release for every episode", isOn: $settings.sameRelease)
                    Toggle("Find the next episode's sources early", isOn: $settings.preloadNext)
                    Toggle("Switch sources silently when one fails", isOn: $settings.failoverEnabled)
                    Picker("Most source switches per title", selection: $settings.maxSwitches) {
                        ForEach([2, 4, 6, 10, 15], id: \.self) { Text("\($0)").tag($0) }
                    }
                    Picker("Wait for a source to start", selection: $settings.startupTimeout) {
                        ForEach([10, 15, 20, 30, 45], id: \.self) { Text("\($0) seconds").tag($0) }
                    }
                }

                Section("Player") {
                    Toggle("Show Skip Intro on intro and recap chapters", isOn: $settings.skipIntro)
                    Toggle("Show Next Episode near the end", isOn: $settings.upNextCard)
                    Picker("Audio language", selection: $settings.audioLanguage) {
                        Text("File default").tag("")
                        ForEach(Languages.all, id: \.code) { Text($0.name).tag($0.code) }
                    }
                    Toggle("Turn subtitles on", isOn: $settings.subtitlesOn)
                    Picker("Subtitle language", selection: $settings.subtitleLanguage) {
                        Text("Apple TV language").tag("")
                        ForEach(Languages.all, id: \.code) { Text($0.name).tag($0.code) }
                    }
                }

                Section {
                    Picker("Service", selection: $settings.debridService) {
                        ForEach(DebridService.allCases) { Text($0.title).tag($0) }
                    }
                    if settings.debridService != .none {
                        SecureField("\(settings.debridService.title) API key", text: $settings.debridKey)
                    }
                } header: {
                    Text("Debrid (plays torrent results)")
                } footer: {
                    Text("Apple TV can't run torrents itself. With a debrid key, torrent results from your add-ons are turned into direct links, and uncached ones are skipped so playback never waits on a download. The key stays on this device and is never in backups.")
                }

                if !settings.addons.isEmpty {
                    Section("Pinned sources (tried first)") {
                        ForEach(Array(addonNames.values).sorted(), id: \.self) { name in
                            Toggle(name, isOn: Binding(
                                get: { settings.pinnedSources.contains(name) },
                                set: { _ in settings.togglePinned(name) }))
                        }
                    }
                }

                Section("Appearance") {
                    Picker("Theme", selection: $settings.theme) {
                        ForEach(MeowioTheme.allCases) { Text($0.title).tag($0) }
                    }
                    Toggle("Show Live TV", isOn: $settings.liveTabVisible)
                }

                Section("Live TV playlists (none = free built-in channels)") {
                    ForEach(settings.liveSources) { source in
                        NavigationLink {
                            LiveSourceEditor(original: source)
                        } label: {
                            HStack {
                                Text(source.label)
                                Spacer()
                                Text(source.enabled ? (source.type == .xtream ? "Xtream" : "M3U") : "Off")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    NavigationLink("Add a playlist or login") { LiveSourceEditor(original: nil) }
                }

                Section("Live TV VPN (only Live TV uses it, never debrid)") {
                    Toggle("Use for Live TV", isOn: $settings.liveVpnEnabled)
                    TextField("Proxy, such as http://user:pass@host:8080", text: $settings.liveVpnProxy)
                    Text(liveVpnStatus).font(.caption).foregroundStyle(.secondary)
                }

                Section("More") {
                    NavigationLink("Watch insights") { InsightsView() }
                    NavigationLink("AI assistant") { AiAssistantView() }
                }

                Section("AI assistant key (stays on this device)") {
                    SecureField("Anthropic API key", text: $settings.aiKey)
                    TextField("Model (optional)", text: $settings.aiModel)
                }

                Section("Backup and restore") {
                    NavigationLink("Back up or restore settings") { BackupView() }
                }

                Section("About") {
                    Text("Meowio for Apple TV \(UpdateChecker.currentVersion)")
                    switch updates.state {
                    case .idle, .checking:
                        Text("Checking for updates…").foregroundStyle(.secondary)
                    case .upToDate:
                        Text("You have the latest version.").foregroundStyle(.secondary)
                    case .available(let tag):
                        Text("Meowio \(tag) is available. Download Meowio-tvos.ipa from github.com/layton-brewer/Meowio/releases and sideload it over this version.")
                    case .failed:
                        Text("Could not check for updates.").foregroundStyle(.secondary)
                    }
                    Button("Check for updates") { Task { await updates.check() } }
                    Text("MKV and other formats play through the built-in mpv engine. TV apps do not offer downloads. Surround sound passes through Apple TV's own audio settings.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
        .task { if case .idle = updates.state { await updates.check() } }
        .task(id: settings.addons) {
            var map: [String: String] = [:]
            for base in settings.addons { map[base] = await AddonClient.addonName(for: base) }
            addonNames = map
        }
    }
}

struct InsightsView: View {
    @EnvironmentObject var library: LibraryStore

    private var week: [WatchRecord] {
        let cutoff = Date().addingTimeInterval(-7 * 86400)
        return library.records.filter { $0.at >= cutoff }
    }
    private func hours(_ list: [WatchRecord]) -> String {
        String(format: "%.1f h", list.reduce(0) { $0 + $1.seconds } / 3600)
    }
    private var topShows: [(String, Double)] {
        var totals: [String: Double] = [:]
        for r in library.records { totals[r.title, default: 0] += r.seconds }
        return totals.sorted { $0.value > $1.value }.prefix(8).map { ($0.key, $0.value) }
    }

    var body: some View {
        List {
            Section("Totals") {
                row("Watch time", hours(library.records))
                row("This week", hours(week))
                row("Movies finished", "\(library.records.filter { $0.completed && !$0.isEpisode }.count)")
                row("Episodes finished", "\(library.records.filter { $0.completed && $0.isEpisode }.count)")
            }
            Section("Most watched") {
                ForEach(topShows, id: \.0) { t in
                    row(t.0, String(format: "%.1f h", t.1 / 3600))
                }
                if topShows.isEmpty { Text("Nothing watched yet.") }
            }
        }
        .navigationTitle("Watch insights")
    }

    private func row(_ a: String, _ b: String) -> some View {
        HStack { Text(a); Spacer(); Text(b).foregroundStyle(.secondary) }
    }
}

// MARK: - AI assistant

struct AiPick: Identifiable, Hashable {
    let id = UUID()
    var title: String
    var year: Int?
    var isSeries: Bool
    var reason: String
}

enum AiClient {
    static let endpoint = "https://api.anthropic.com/v1/messages"
    static let defaultModel = "claude-opus-5-5"

    enum Failure: Error { case noKey, badKey, rateLimited, declined, network, unexpected }

    private static let system = "You help someone decide what to watch in a streaming app. Suggest up to 8 real, released movies or TV series that fit their request, best match first. Use each title's most common English name so it can be found by search, and give one short sentence on why it fits. Treat the titles they have watched or saved as hints about their taste. If the request is not about finding something to watch, return no picks."

    static func ask(prompt: String, key: String, model: String, watched: [String], saved: [String]) async throws -> [AiPick] {
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else { throw Failure.noKey }
        var user = "Request: " + String(prompt.trimmingCharacters(in: .whitespacesAndNewlines).prefix(600))
        if !watched.isEmpty { user += "\n\nRecently watched (do not suggest these): " + watched.joined(separator: "; ") }
        let extra = saved.filter { !watched.contains($0) }
        if !extra.isEmpty { user += "\n\nSaved to their library: " + extra.joined(separator: "; ") }

        let schema: [String: Any] = [
            "type": "object",
            "properties": ["picks": ["type": "array", "items": [
                "type": "object",
                "properties": [
                    "title": ["type": "string"], "year": ["type": "integer"],
                    "type": ["type": "string", "enum": ["movie", "series"]], "reason": ["type": "string"]
                ],
                "required": ["title", "year", "type", "reason"],
                "additionalProperties": false
            ] as [String: Any]]],
            "required": ["picks"],
            "additionalProperties": false
        ]
        let body: [String: Any] = [
            "model": model.trimmingCharacters(in: .whitespaces).isEmpty ? defaultModel : model.trimmingCharacters(in: .whitespaces),
            "max_tokens": 8000,
            "fallbacks": "default",
            "system": system,
            "output_config": ["effort": "low", "format": ["type": "json_schema", "schema": schema]],
            "messages": [["role": "user", "content": user]]
        ]
        var request = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue(trimmedKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
        request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        let data: Data
        let status: Int
        do {
            let (d, r) = try await URLSession.shared.data(for: request)
            data = d
            status = (r as? HTTPURLResponse)?.statusCode ?? 0
        } catch { throw Failure.network }
        guard (200..<300).contains(status) else {
            switch status {
            case 401, 403: throw Failure.badKey
            case 429, 529: throw Failure.rateLimited
            default: throw Failure.unexpected
            }
        }
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.unexpected }
        if (root["stop_reason"] as? String) == "refusal" { throw Failure.declined }
        let text = ((root["content"] as? [[String: Any]]) ?? [])
            .filter { ($0["type"] as? String) == "text" }
            .compactMap { $0["text"] as? String }.joined()
        guard let s = text.firstIndex(of: "{"), let e = text.lastIndex(of: "}"), s < e,
              let obj = try? JSONSerialization.jsonObject(with: Data(text[s...e].utf8)) as? [String: Any],
              let items = obj["picks"] as? [[String: Any]] else { throw Failure.unexpected }
        var seen = Set<String>()
        var picks: [AiPick] = []
        for item in items {
            guard let title = (item["title"] as? String)?.trimmingCharacters(in: .whitespaces), !title.isEmpty else { continue }
            let isSeries = (item["type"] as? String) == "series"
            guard seen.insert("\(title.lowercased())|\(isSeries)").inserted else { continue }
            picks.append(AiPick(title: title, year: item["year"] as? Int, isSeries: isSeries,
                                reason: (item["reason"] as? String) ?? ""))
        }
        if picks.isEmpty { throw Failure.unexpected }
        return Array(picks.prefix(8))
    }

    static func message(_ f: Failure) -> String {
        switch f {
        case .noKey: return "Add your Anthropic API key in Settings first."
        case .badKey: return "The API key was not accepted."
        case .rateLimited: return "The service is busy or rate limited. Try again in a moment."
        case .declined: return "The assistant declined that request."
        case .network: return "Could not reach the service."
        case .unexpected: return "The assistant gave no suggestions. Try describing what you want to watch."
        }
    }
}

struct AiAssistantView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @State private var prompt = ""
    @State private var picks: [AiPick] = []
    @State private var busy = false
    @State private var error: String?
    @State private var opened: MetaItem?

    var body: some View {
        List {
            Section {
                TextField("What do you feel like watching?", text: $prompt)
                Button(busy ? "Thinking…" : "Suggest") { Task { await run() } }
                    .disabled(busy || prompt.trimmingCharacters(in: .whitespaces).isEmpty)
                if let error { Text(error).foregroundStyle(.orange) }
            }
            Section {
                ForEach(picks) { p in
                    Button { Task { await open(p) } } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(p.title)\(p.year.map { " (\($0))" } ?? "")").font(.headline)
                            if !p.reason.isEmpty { Text(p.reason).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
        }
        .navigationTitle("AI assistant")
        .navigationDestination(item: $opened) { DetailView(item: $0) }
    }

    private func run() async {
        busy = true
        error = nil
        let watched = library.continueWatching.map { $0.meta.name }
        let saved = library.saved.map(\.name)
        do {
            picks = try await AiClient.ask(prompt: prompt, key: settings.aiKey, model: settings.aiModel,
                                           watched: Array(watched.prefix(25)), saved: Array(saved.prefix(25)))
        } catch let f as AiClient.Failure {
            self.error = AiClient.message(f)
        } catch {
            self.error = AiClient.message(.unexpected)
        }
        busy = false
    }

    private func open(_ p: AiPick) async {
        let results = await AddonClient.catalog(type: p.isSeries ? "series" : "movie", search: p.title)
        if let hit = results.first(where: { $0.name.lowercased() == p.title.lowercased() }) ?? results.first {
            opened = hit
        } else {
            error = "Could not find \(p.title)."
        }
    }
}

private extension SettingsView {
    var liveVpnStatus: String {
        if !settings.liveVpnEnabled { return "Off. Live TV connects directly." }
        if LiveVpnGuard.parseProxy(settings.liveVpnProxy) == nil { return "Enter an http:// proxy address with a port." }
        return "Switches on by itself while a channel plays and off when you leave. Debrid and other playback never use it."
    }
}
